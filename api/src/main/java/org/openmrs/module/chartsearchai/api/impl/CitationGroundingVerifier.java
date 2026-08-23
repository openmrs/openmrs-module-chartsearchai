/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Checks that each cited record actually supports the answer sentence(s) that
 * cite it, rather than trusting the LLM's citations blindly.
 *
 * <p>{@link LlmInferenceService} already validates that a {@code [N]} marker
 * maps to a real retrieved record. That catches a model citing a record number
 * that does not exist, but not the more dangerous case of a real record cited
 * for a claim it does not support (e.g. "patient has diabetes [5]" where record
 * 5 is a blood-pressure reading). This verifier closes that gap with a Tier-1
 * semantic-overlap check: embed each cited record and the answer sentence(s)
 * that reference it, and require their cosine similarity to clear a tunable
 * floor ({@code chartsearchai.grounding.minCosine}). Below the floor, the
 * citation is annotated {@code grounded=false} so the UI can flag it. (When
 * Tier-2 entailment is enabled, this cosine pass runs lazily — see "Lazy
 * Tier-1 under entailment" below.)
 *
 * <p><strong>Scope and limits.</strong> This is intentionally an annotate-only,
 * non-destructive pass: it never rewrites the answer prose or drops citations,
 * it only attaches a verdict. Cosine overlap reliably separates off-topic
 * citations from on-topic ones, but it does <em>not</em> separate subtle
 * subject/polarity flips ("patient has X" vs "mother had X", "denies X" vs
 * "has X") — those embed nearly identically.
 *
 * <p><strong>Tier-2 (optional).</strong> When
 * {@code chartsearchai.grounding.entailment.enabled} is set, the cited references are
 * confirmed by a yes/no LLM entailment verdict that is authoritative. This is what
 * catches the subject/polarity flips cosine cannot — for chart records; two kinds of citation are
 * excepted and both are below, module-supplied reference material and a COMPOUND claim unit. It runs on Tier-1 passes
 * <em>and</em> failures — the dangerous case (a high-overlap but unsupported
 * citation) is a Tier-1 pass, so confirming only failures would miss it. References are verified
 * in a SINGLE batched call ({@link LlmProvider#entailsBatch}) — except for the citations of ONE
 * sentence whose statements overlap, which are each verified in their OWN call: batched entailment
 * is NOT per-pair independent, so co-batching them lets the LLM couple their verdicts and silently
 * flip a correct citation to not-grounded. Two shapes qualify — a compound sentence under
 * clause-scoped grounding (whose cumulative-prefix statements differ only by length), and an
 * ENUMERATING sentence in either mode (whose per-item statements share a preamble, see
 * {@code splitEnumeration} and issue #278). The total is capped at
 * {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS} pairs per answer;
 * references beyond the cap keep their Tier-1 verdict.
 *
 * <p><strong>Lazy Tier-1 under entailment.</strong> Because the Tier-2 verdict overrides Tier-1
 * wherever it lands, running the cosine pass eagerly for every reference would spend a full
 * embedding-model forward pass per record and per sentence — the dominant grounding cost on
 * CPU-only servers — on verdicts that are then discarded. So when entailment is enabled, Tier-1
 * embeds run only where they still decide something: choosing the claim sentence when more than
 * one candidate cites the record (the statement must be the cosine-best match, identical to the
 * eager path), and supplying the fallback verdict for references whose Tier-2 check produced none —
 * because it failed or could not answer (cap overflow, engine failure), or because it was never asked
 * AND a Tier-1 verdict could still be published, which since #302 means reference material but not a
 * compound claim unit — computed lazily, after Tier-2. A list-style
 * answer where each line cites its own record runs no Tier-1 embeds at all. A consequence pinned in
 * tests: a broken or absent Tier-1 embedding model no longer blocks Tier-2 verdicts for unambiguous
 * claim sentences the judge is ASKED about — previously it silently downgraded every citation to
 * "unverified". Since issue #302 it is not asked about a compound claim unit, which on such a
 * deployment has no tier left and renders unverified; see the compound-claim paragraph below.
 *
 * <p><strong>Module-supplied reference citations are demote-only.</strong> A record whose
 * resource type groups as reference material
 * ({@link ChartSearchAiUtils#isGroundingDemoteOnly}) is module-rendered
 * reference prose, and an answer sentence citing it is typically a recitation of that
 * prose. A recitation embeds near-identically to its source whether or not it swaps
 * subject roles ("erythromycin decreases X" vs the record's "ivosidenib decreases X …
 * including erythromycin"), and the same lexical containment defeats the Tier-2 judge:
 * measured on the live pipeline, 4/4 role-swapped recitations were judged entailed while
 * the one faithful recitation was judged not (issue #106). So these citations never enter
 * Tier-2 (nor consume its per-answer cap), and Tier-1 can only <em>demote</em>: a cosine
 * fail still flags an off-topic citation ({@code grounded=false}), but a pass renders
 * {@code null} (unverified), never {@code true}. (Where such a citation also sits inside a compound
 * claim unit under entailment, the stronger rule below wins and even the fail is withheld — no
 * consumer can see the difference, since #201 withholds every reference-group verdict at the wire.)
 * Faithfulness of reference content is
 * checked deterministically instead: by the {@code DrugSafetyValidator} chips, and — for the one
 * part of a recitation that no semantic check can see, an ATC class code the model edited while
 * citing the record that carries it — by {@link ClassCodeFidelityCheck} (issue #142). Accepted
 * cost: under entailment mode these citations now take the lazy Tier-1 path (up to two
 * embedding passes each) that the amortized Tier-2 batch previously spared them — the
 * off-topic flag is kept mode-uniform at the price of ~one embed pair per reference
 * citation on CPU deployments. That surviving {@code false} is a module-internal signal: since
 * issue #201 the REST layer publishes no verdict at all for a reference-group citation, because
 * its meaning is "off-topic citation" and a client reading it as "unsupported claim" renders the
 * module's own deterministic finding in red.
 *
 * <p>Two record types are reference material today —
 * {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_REFERENCE}, the case #106 measured, and
 * {@link ChartSearchAiConstants#RESOURCE_TYPE_SAFETY_FINDING}, the deterministic drug-safety join
 * #110 injects as a citable record. The finding was graded as chart evidence until issue #122,
 * because this carve-out named the drug-reference type instead of deriving from the group; its
 * verdicts tracked embedding noise, and it also spent Tier-2 cap slots meant for chart claims. An
 * injected {@link ChartSearchAiConstants#RESOURCE_TYPE_ACTIVE_DRUG_ORDER} record is NOT reference
 * material and is graded normally — see the carve-out site in {@link #verify} for why that is
 * right, and why "the module injected it" is the wrong test.
 *
 * <p><strong>A COMPOUND claim unit is UNVERIFIABLE, which is stronger than demote-only.</strong> A claim unit
 * that attaches its citations to different pieces of its own text — more than one citation, with
 * claim text standing between two of its markers ({@link Sentence#compoundClaim()}) — states a
 * conjunction that no one cited record answers for all of. Asked to entail it, a correct judge
 * replies "no" whether the citation is right or wrong, so the verdict says nothing about the
 * citation; published, that is what marked correct, active, unvoided medication citations as
 * unsupported on the module's most common question — 8 of the 30 chart citations in #302's own
 * 12-patient sweep, all 8 in the colon-less multi-citation row. This rule reaches those of them whose
 * markers are separated by claim text; #302's closing sub-shape ({@code Salicylic acid [1], [2].}) is
 * co-citation by the definition below and stays graded, so the fix is not all 8.
 *
 * <p>Such citations never enter Tier-2, never consume its cap, and publish NO verdict at all —
 * {@code null} in either direction. Both tiers ask the wrong-sized question of them: the judge is
 * handed a conjunction the record answers for only part of, and the cosine that would stand in for it
 * is measured against that same conjunction, so a correct citation's score is diluted by the items it
 * is not cited for. Withholding only the pass and keeping the fail was measured to reintroduce this
 * issue's own harm — on the cell where a lenient judge's YES had rescued a diluted score, main
 * published {@code true} and that draft published {@code false}, Unsupported on a correct and active
 * citation. So nothing is published, and no embedding is spent computing a verdict that would be
 * discarded. {@code chartsearchai.grounding.clauseScoped} narrows the FIRST citation's claim to its own clause
 * and is the existing remedy for that one; it does not extend to the rest, whose cumulative prefix
 * still names the earlier items, and turning it on removes this rule rather than adding to it
 * (a single-cited fragment is not a compound claim unit).
 *
 * <p>The rule applies under ENTAILMENT ONLY. With Tier-2 off, cosine is not standing in for a judge
 * that could not be asked — it is the whole of what the mode promises, every verdict in it is that
 * same comparison, and withholding a compound unit's alone would cost a correct citation its verdict
 * for no defect removed. That is where this rule and the reference-group one part company, and the two are
 * otherwise independent with neither subsuming the other — this one is about the SHAPE of the claim,
 * that one about the PROVENANCE of the record, and it demotes in both modes because recited prose is
 * unverifiable by either tier.
 *
 * <p>A citation the model put only in the structured citations array is demoted with the rest when
 * {@link #selectClaim}'s no-inline-cite fallback attributes it to a compound claim unit. That is
 * deliberate and is pinned: the statement asserts more than THAT record is responsible for too, so
 * the judge's refusal is no more informative for it, and asking instead whether the claim unit cites
 * it would send an array-only citation to the judge against the whole conjunction — the shape issue
 * #284 is about.
 *
 * <p><strong>Cost: this rule only removes work.</strong> Nothing is published for these citations, so
 * the judge is never asked and the lazy Tier-1 fallback never runs. Claim SELECTION still embeds where
 * several sentences cite one record and the cosine argmax has to choose between them — that predates
 * this rule and is unchanged by it, which is why the A/B below adds no passes rather than removing
 * them all. Measured by A/B through the 6-arg {@link #verify} over a 12-citation
 * answer with entailment on, counting the real {@link TextEmbedder} and {@link LlmProvider} calls: one
 * compound line citing all 12 drops the batch entirely (1 {@code entailsBatch} call to 0, 12 pairs to
 * 0) and adds no embedding passes; 8 sentences of which 4 are compound, covering 9 of the 12, keep the
 * batch for the rest (1 call to 1, 12 pairs to 3) and likewise add none. Tier-1-only mode is
 * unchanged and under {@code clauseScoped} nothing moves at all. Neither degraded deployment is a
 * special case either: with no Tier-1 embedder, or with the judge unavailable, a compound unit renders
 * {@code null} exactly as it does everywhere else. The gate still keys on the CONFIGURED mode rather
 * than on whether the judge answered, because conditioning on "Tier-2 reached some verdict for this
 * answer" would switch the rule off for a single-compound-sentence answer — #302's own headline case.
 *
 * <p><strong>What it gives up.</strong> Compute is not the only thing this rule removes: a compound
 * unit under entailment now has NO tier at all, so a genuinely off-topic or mis-pointed citation
 * inside the module's most common answer shape gets no signal either. That is accepted because the
 * signal it replaces did not discriminate — the judge refuses every conjunction, and cosine against a
 * conjunction is diluted for the correct record too, which is the sweep's finding that all 8 published
 * {@code false} sat on correct, active records. It is a real loss all the same, and it is stated here
 * because this is the paragraph a reader consults for what the rule costs.
 *
 * <p>The verifier never throws into the search path: any failure (embedding
 * error, missing text) degrades to a {@code null} verdict — "could not verify"
 * — which renders as unverified, exactly as if grounding were disabled.
 */
@Service("chartSearchAi.citationGroundingVerifier")
public class CitationGroundingVerifier {

	private static final Logger log = LoggerFactory.getLogger(CitationGroundingVerifier.class);

	/**
	 * Splits an answer into claim units, on terminal punctuation followed by
	 * whitespace OR on line breaks. The line-break case matters: the system
	 * prompt instructs the model to "use numbered lines or simple newlines to
	 * structure lists", so a multi-item answer often has no sentence-ending
	 * punctuation — without splitting on newlines every citation would be scored
	 * against the whole answer instead of its own line.
	 */
	private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|[\\r\\n]+");

	@Autowired
	private LlmProvider llmProvider;

	/** Test seam: production wires {@link LlmProvider} via {@link Autowired}. */
	void setLlmProvider(LlmProvider llmProvider) {
		this.llmProvider = llmProvider;
	}

	/** Embeds text to a vector — abstracts over which module's provider is used. */
	interface TextEmbedder {

		float[] embed(String text);
	}

	/**
	 * Resolves the embedding provider for a grounding run: querystore's configured provider
	 * (the same e5/ONNX model that built the index), so the verifier embeds with the same model
	 * as retrieval and no separate chartsearchai embedding model has to be installed. Returns
	 * {@code null} when querystore's provider can't be resolved — Tier-1 cosine checks are then
	 * skipped and Tier-2 entailment (the authoritative pass) still applies to every citation it is
	 * asked about. Since issue #302 it is not asked about a citation of a compound claim unit, which
	 * renders unverified on any deployment, so an absent embedder cannot change its verdict. It can
	 * still change the LOG: where several sentences cite the record, claim selection embeds to choose
	 * between them, and that failure is counted in the run's embedding-failure summary. Never throws.
	 *
	 * <p>chartsearchai's own ONNX embedding provider was removed in the querystore migration (#51);
	 * querystore is now the only grounding embedder. querystore is a {@code provided}-scope
	 * dependency (compiled against, not bundled) and a required module, so it should be present at
	 * runtime; the {@code LinkageError} catch (covering {@code NoClassDefFoundError}) is kept as
	 * defense-in-depth, mirroring {@code QueryStoreChartBuilder}'s guard.
	 *
	 * <p>Overridable as a test seam (matching the {@code resolveX()} pattern elsewhere) so tests can
	 * inject a deterministic {@link TextEmbedder} without an OpenMRS context.
	 */
	TextEmbedder resolveEmbedder() {
		try {
			org.openmrs.module.querystore.embedding.EmbeddingProvider qs =
					org.openmrs.api.context.Context.getRegisteredComponent(
							"querystore.embedding.dispatcher",
							org.openmrs.module.querystore.embedding.EmbeddingProvider.class);
			if (qs != null) {
				return qs::embed;
			}
		}
		catch (RuntimeException | LinkageError e) {
			log.warn("Grounding: querystore embedding provider unavailable ({}); Tier-1 cosine "
					+ "checks will be skipped (Tier-2 entailment still applies).", e.toString());
		}
		return null;
	}

	/** Accumulates embedding failures across a run so they are logged once, not per citation. */
	private static final class GroundingStats {

		int embedFailures;

		String firstError;

		void recordFailure(Throwable t) {
			embedFailures++;
			if (firstError == null) {
				firstError = t.getClass().getSimpleName() + ": " + t.getMessage();
			}
		}
	}

	/**
	 * Returns a copy of {@code references} with each entry's grounding verdict
	 * set. A reference is grounded when its record's text is at least
	 * {@link ChartSearchAiUtils#getGroundingMinCosine()} cosine-similar to the
	 * best-matching answer sentence that cites it (or, when no sentence cites it
	 * inline — e.g. it appeared only in the structured citations array — to the
	 * best-matching sentence anywhere in the answer). References whose record
	 * carries no text, or that cannot be embedded, are returned with a
	 * {@code null} verdict ("could not verify"). Two kinds of citation are held back from a verdict,
	 * by different amounts and under different conditions: module-supplied reference material is
	 * demote-only in either mode (a cosine pass renders {@code null}, a cosine fail still flags), and
	 * a COMPOUND claim unit — a statement attaching its citations to different pieces of itself —
	 * publishes nothing in either direction, but only when entailment is enabled; with Tier-2 off it
	 * is graded like any other citation. See the class javadoc for both.
	 *
	 * @param answer the full answer prose, with inline {@code [N]} markers
	 * @param references the index-validated references to annotate
	 * @param mappings the record mappings carrying each index's source text
	 * @return a new list, same order, with grounding verdicts attached
	 */
	public List<RecordReference> verify(String answer, List<RecordReference> references,
			List<RecordMapping> mappings) {
		return verify(answer, references, mappings, ChartSearchAiUtils.getGroundingMinCosine(),
				ChartSearchAiUtils.isGroundingEntailmentEnabled(),
				ChartSearchAiUtils.isGroundingClauseScoped());
	}

	/**
	 * Backward-compatible 5-arg overload — sentence-scoped grounding (the original behaviour).
	 * Existing tests pin this; the public {@link #verify} now delegates to the 6-arg form.
	 */
	List<RecordReference> verify(String answer, List<RecordReference> references,
			List<RecordMapping> mappings, double floor, boolean entailmentEnabled) {
		return verify(answer, references, mappings, floor, entailmentEnabled, false);
	}

	/**
	 * Flag-explicit overload — the seam the public {@link #verify} delegates to
	 * after reading {@code chartsearchai.grounding.minCosine} and
	 * {@code chartsearchai.grounding.entailment.enabled}. Package-private so unit
	 * tests can exercise the grounding logic without an OpenMRS context.
	 *
	 * <p>When {@code entailmentEnabled}, every reference with a resolvable claim sentence and
	 * record text — except two kinds that never enter Tier-2, citations of module-supplied reference
	 * material and citations of a COMPOUND claim unit (both in the class javadoc) — is confirmed by a
	 * Tier-2 LLM entailment verdict that is authoritative
	 * (cosine errs in both directions, and the dangerous error — a high-overlap
	 * but unsupported citation — is exactly the case Tier-1 cannot self-detect,
	 * so the LLM must see Tier-1 passes too, not only failures). They are confirmed in a batched
	 * call ({@link LlmProvider#entailsBatch}), capped at
	 * {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS} pairs per answer; references
	 * beyond the cap keep their Tier-1 verdict (but see the clause-scoped exception below).
	 * Tier-1's own cosine verdict is computed lazily in this mode — see the class javadoc's
	 * "Lazy Tier-1 under entailment" section.
	 *
	 * <p>When {@code clauseScoped}, a sentence citing multiple records is split so each citation is
	 * checked against the answer text up to and including its own {@code [N]} marker, not the whole
	 * compound sentence (see {@link #splitIntoClauseScopedSentences}). Independently of this flag, an
	 * ENUMERATING sentence is split per item (#278) — so a split fragment is not evidence that
	 * {@code clauseScoped} was set. Those split citations are each
	 * Tier-2 verified in their OWN entailment call rather than co-batched: batched entailment is not
	 * per-pair independent, so co-batching a compound sentence's citations (whose clause statements
	 * overlap) lets the LLM couple their verdicts. Every other citation is still confirmed in the one
	 * shared batched call.
	 */
	List<RecordReference> verify(String answer, List<RecordReference> references,
			List<RecordMapping> mappings, double floor, boolean entailmentEnabled, boolean clauseScoped) {
		if (references == null || references.isEmpty()) {
			return references;
		}

		Map<Integer, String> textByIndex = new HashMap<Integer, String>();
		// Module-supplied records get demote-only verdicts (see class javadoc): an answer sentence
		// citing one is typically a recitation of module-rendered reference prose, which embeds
		// near-identically to its source whether or not it swaps subject roles — so a passing
		// verdict would be false assurance (issue #106).
		Set<Integer> demoteOnlyIndexes = new HashSet<Integer>();
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				textByIndex.put(mapping.getIndex(), mapping.getText());
				// Membership is DERIVED from the reference group (ChartSearchAiUtils.referenceGroup,
				// read through isGroundingDemoteOnly), not from a list of type names, so
				// "module-supplied material is demote-only" is one rule keyed off one classification
				// and a newly injected type inherits the right side of it. It had to be remembered
				// here once and was not: #110's safety_finding was classified as reference material
				// and left out of this set, so the module's own deterministic findings were graded as
				// retrieved chart evidence and published verdicts that tracked embedding noise —
				// grounded=false on a MAJOR finding beside two byte-identical siblings at true, and
				// one finding flipping across runs of a single probe (issue #122). Since issue #201 the
				// REST serializer withholds the whole verdict for a reference-group citation, derived
				// from the same classification, so a type left out HERE no longer reaches a client as
				// grounded=true — but do not read that as cover. Two consequences are still this set's
				// alone and neither is visible downstream: the omitted type spends Tier-2 cap slots
				// meant for chart claims, and it records an entailment verdict on its RecordReference
				// that the judge cannot competently give on recited prose. Nor is the
				// wire a reason to relax it on the assumption that a client re-filters by group —
				// `group` is a provenance DISCLOSURE, not a second gate, which is why the withholding
				// is stated server-side in README's reference contract.
				//
				// active_drug_order (#118) is the case that fixes the rule's SHAPE, and the reason it
				// cannot be "everything the module injects": it is chart evidence, so withholding a
				// pass would cost a real check, and the #106 hazard does not apply — that is about
				// reference prose whose subject roles can swap while still embedding near-identically ("A
				// interacts with B"), whereas this record is one drug name asserted of this patient,
				// so a passing verdict is real assurance. Keyed off the group it stays graded, with
				// nothing to remember.
				if (ChartSearchAiUtils.isGroundingDemoteOnly(mapping.getResourceType())) {
					demoteOnlyIndexes.add(Integer.valueOf(mapping.getIndex()));
				}
			}
		}

		List<Sentence> sentences = clauseScoped
				? splitIntoClauseScopedSentences(answer)
				: splitIntoCitedSentences(answer);
		TextEmbedder embedder = resolveEmbedder();

		// Embedding caches: each record and each sentence is embedded at most
		// once per call, even when an index is cited by several sentences.
		Map<Integer, float[]> recordVectors = new HashMap<Integer, float[]>();
		Map<Integer, float[]> sentenceVectors = new HashMap<Integer, float[]>();
		GroundingStats stats = new GroundingStats();

		// Pass 1: claim selection (and, where still needed, Tier-1 cosine) for every reference,
		// collecting the claim/record pairs Tier-2 should confirm — up to the per-answer cap.
		// Tier-2 runs on Tier-1 passes AND failures: the dangerous case (high cosine but
		// unsupported, e.g. a family-history flip) is a Tier-1 pass, so confirming only failures
		// would miss it.
		//
		// When entailment is enabled, Tier-1's cosine verdict is computed LAZILY (the "Lazy
		// Tier-1" block below, after the Tier-2 calls and before Pass 2): the
		// authoritative Tier-2 verdict overrides it wherever Tier-2 reaches one, so the eager
		// cosine work — a full embedding-model forward pass per record and per sentence, the
		// dominant grounding cost on CPU-only servers — would be discarded for exactly the
		// references it ran for. Eager Tier-1 here is needed only to CHOOSE the claim sentence
		// when the choice is ambiguous (more than one candidate); the common list-answer case
		// (every citation has exactly one citing sentence) selects deterministically and runs
		// no embeds at all.
		int entailmentBudget = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		int cappedCount = 0;
		Tier1Result[] tier1Results = new Tier1Result[references.size()];
		// Two reasons a citation is kept away from the judge, and they do NOT share a disposition —
		// which is why there are two arrays and not one. Both are decided ONCE per reference, from the
		// value claim selection returned, never re-read off tier1Results (cosineVerdict REBUILDS those
		// for every reference reaching the lazy Tier-1 block, and a flag lost in a rebuild would fail
		// open). Wiring a new reason into only one site is not hypothetical: #110's safety_finding was
		// left out of the reference-group set and spent Tier-2 cap slots meant for chart claims for two
		// releases (#122).
		//
		//   * the RECORD is module-supplied reference material (issue #106/#122), in either mode:
		//     DEMOTE-ONLY. Tier-1 still runs and its FAIL is kept — it says the citation is not about
		//     the record at all — while a PASS renders null, because a recitation embeds like its
		//     source whether or not it swapped roles. Where a reference-group citation is ALSO inside a
		//     compound claim unit, the stronger rule below wins and its fail is withheld too; nothing
		//     downstream can tell, because #201 withholds every reference-group verdict at the wire.
		//   * its CLAIM UNIT is compound and entailment is on (issue #302): UNVERIFIABLE, no verdict in
		//     either direction. Both tiers are asking the wrong-sized question there — the judge is
		//     asked to entail a conjunction the record answers for only part of, and cosine is measured
		//     against that same conjunction, so a correct citation's score is diluted by the items it
		//     is not cited for. Keeping the cosine FAIL was measured to reintroduce #302's own harm:
		//     where the judge's YES had rescued a diluted score, main published true and this branch
		//     published false on a correct, active citation. So nothing is published, and no embedding
		//     is spent computing a verdict that would be discarded.
		//     With entailment OFF this does not apply: cosine is then the only tier, every verdict in
		//     the mode is that same comparison, and withholding a compound unit's alone would cost a
		//     correct citation its verdict for no defect removed. That restriction is guarded here AND
		//     by verdictTier1 reporting the flag false; each alone suffices, so neither is individually
		//     observable to the suite — see the note at that method. clauseScoped remains this module's
		//     remedy for sentence-scope dilution, unchanged by #302.
		boolean[] noTier2 = new boolean[references.size()];
		boolean[] unverifiable = new boolean[references.size()];
		// Non-isolate candidates share ONE batch. That is safe for the citations of DIFFERENT claim
		// units, whose statements are different text. It is a known residue for CO-CITATION — several
		// citations of one claim unit with nothing but a list separator between their markers
		// ("Infections [1], [2]") — whose statements are not merely overlapping but IDENTICAL, and
		// which is non-isolate and co-batched here. That predates issue #302 and is unchanged by it;
		// #302 only records it, because that issue's rule deliberately leaves co-citation graded
		// (Sentence.compoundClaim()) and so depends on this batch. Whether such a pair should be
		// isolated is a separate question on its own evidence, and isolating it would cost a call per
		// citation (see splitEnumeration's measured note on that trade).
		List<Integer> batchPositions = new ArrayList<Integer>();
		List<String> batchSources = new ArrayList<String>();
		List<String> batchStatements = new ArrayList<String>();
		// Isolate candidates — the citations of ONE sentence whose per-citation statements overlap: a
		// clause-scoped compound (prefixes overlapping by length) or an enumeration in either mode
		// (items sharing a preamble). Each is verified in its OWN single-pair call so the LLM cannot
		// couple their verdicts (see this class's Tier-2 javadoc).
		List<Integer> isolatePositions = new ArrayList<Integer>();
		List<String> isolateSources = new ArrayList<String>();
		List<String> isolateStatements = new ArrayList<String>();
		for (int i = 0; i < references.size(); i++) {
			RecordReference reference = references.get(i);
			Tier1Result tier1 = entailmentEnabled
					? selectClaim(reference.getIndex(), textByIndex, sentences,
							floor, recordVectors, sentenceVectors, embedder, stats)
					: verdictTier1(reference.getIndex(), textByIndex, sentences,
							floor, recordVectors, sentenceVectors, embedder, stats);
			tier1Results[i] = tier1;
			unverifiable[i] = entailmentEnabled && tier1.compoundClaim;
			noTier2[i] = unverifiable[i]
					|| demoteOnlyIndexes.contains(Integer.valueOf(reference.getIndex()));
			// Tier-2 candidate: needs a concrete claim sentence to fact-check against the record.
			// Candidacy deliberately does NOT require a Tier-1 verdict: for an unambiguous claim
			// sentence the verdict is deferred (and may never be needed), and a broken Tier-1
			// embedder must not block the authoritative Tier-2 check it has no part in.
			// Citations of module-supplied reference material are never candidates: the judge's "yes"
			// is false assurance on recited reference prose (issue #106), and the skipped pair must
			// not consume the per-answer entailment cap that chart citations rely on — which the
			// safety findings were doing until issue #122, several per polypharmacy answer.
			// A COMPOUND claim unit is excluded for the parallel reason (issue #302): its statement
			// attaches different citations to different pieces of itself, so the record is asked to
			// entail a conjunction it answers for only part of, and a correct judge replies "no"
			// whether the citation is right or wrong. Published, that is what marked correct
			// medication citations as unsupported. Both exclusions sit OUTSIDE the budget branch
			// below, so the skipped pairs do not spend the per-answer cap single-claim citations rely
			// on.
			if (entailmentEnabled && tier1.bestSentence != null
					&& tier1.recordText != null
					&& !noTier2[i]) {
				if (entailmentBudget > 0) {
					entailmentBudget--;
					String statement = stripCitationMarkers(tier1.bestSentence);
					if (tier1.isolate) {
						isolatePositions.add(i);
						isolateSources.add(tier1.recordText);
						isolateStatements.add(statement);
					} else {
						batchPositions.add(i);
						batchSources.add(tier1.recordText);
						batchStatements.add(statement);
					}
				} else {
					cappedCount++;
				}
			}
		}

		Boolean[] tier2Verdict = new Boolean[references.size()];
		// One batched call confirms all NON-isolate citations at once (the latency win); a null/short
		// result leaves those verdicts null, i.e. the Tier-1 verdict stands. A null entry in
		// tier2Verdict means "not a Tier-2 candidate" OR "Tier-2 could not verify" — Pass 2 keeps the
		// Tier-1 verdict for both, so they collapse to one null with no information lost.
		if (!batchSources.isEmpty()) {
			List<Boolean> batchVerdicts = safeEntailsBatch(batchSources, batchStatements);
			for (int k = 0; k < batchPositions.size(); k++) {
				tier2Verdict[batchPositions.get(k)] = (batchVerdicts != null && k < batchVerdicts.size())
						? batchVerdicts.get(k) : null;
			}
		}
		// Isolate citations (one sentence's overlapping fragments — a clause-scoped compound, or an
		// enumeration in either mode) get a single-pair call each, so the batched LLM cannot couple
		// their statements into each other's verdicts. Same null-degrades-to-Tier-1 contract as the
		// batch.
		for (int k = 0; k < isolatePositions.size(); k++) {
			List<Boolean> verdict = safeEntailsBatch(Collections.singletonList(isolateSources.get(k)),
					Collections.singletonList(isolateStatements.get(k)));
			tier2Verdict[isolatePositions.get(k)] = (verdict != null && !verdict.isEmpty())
					? verdict.get(0) : null;
		}

		// Lazy Tier-1: references whose cosine verdict was deferred at claim-selection time get it
		// computed now, but ONLY where Tier-2 did not reach a verdict — everywhere else the eager
		// cosine would have been overridden and its embedding cost (the dominant grounding cost on
		// CPU) wasted. Tier-2 reaches none where it failed or could not answer (cap overflow, engine
		// failure, unparseable reply) and where it was never asked — a reference-group citation, which
		// still needs its cosine because its FAIL is kept, and a compound claim unit, which does not:
		// that one publishes nothing either way, so computing the cosine would spend the pass this
		// block exists to avoid on a verdict Pass 2 discards.
		for (int i = 0; i < references.size(); i++) {
			if (tier2Verdict[i] == null && tier1Results[i].deferred && !unverifiable[i]) {
				tier1Results[i] = cosineVerdict(tier1Results[i], floor, references.get(i).getIndex(),
						sentences, recordVectors, sentenceVectors, embedder, stats);
			}
		}

		// Pass 2: assemble — Tier-2 is authoritative when it reached a verdict, else keep Tier-1.
		List<RecordReference> annotated = new ArrayList<RecordReference>(references.size());
		for (int i = 0; i < references.size(); i++) {
			Boolean verdict = tier1Results[i].verdict;
			Boolean llmVerdict = tier2Verdict[i];
			if (llmVerdict != null) {
				verdict = llmVerdict; // authoritative; null (no Tier-2 or unverifiable) -> keep Tier-1
			}
			if (unverifiable[i]) {
				// A compound claim unit under entailment publishes nothing (issue #302). Neither tier
				// asked a question about THIS citation: the judge was handed a conjunction the record
				// answers for only part of, and the cosine that would stand in for it is measured
				// against that same conjunction. Withholding only the pass and keeping the fail was
				// measured to reintroduce the issue's own harm — a correct citation whose diluted
				// cosine failed while the judge's yes had rescued it under main went from true to a
				// published false. Guarded by
				// compoundClaim_publishesNothingWhicheverWayTheJudgeWouldHaveAnswered.
				verdict = null;
			} else if (Boolean.TRUE.equals(verdict)
					&& demoteOnlyIndexes.contains(Integer.valueOf(references.get(i).getIndex()))) {
				// Demote-only: a cosine pass on a recited reference record carries no faithfulness
				// signal, so it renders unverified rather than verified; a fail (an off-topic
				// citation) still flags. Reference content is verified deterministically by the
				// DrugSafetyValidator, not by this pass.
				verdict = null;
			}
			annotated.add(references.get(i).withGrounded(verdict));
		}
		if (cappedCount > 0) {
			log.info("Tier-2 entailment cap ({}) reached; {} citation(s) kept their Tier-1 verdict only",
					ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS, cappedCount);
		}
		// One summary line instead of a per-citation stacktrace: the usual cause is a
		// misconfigured/absent embedding model, which would otherwise spam the log once
		// per citation and bury the root cause.
		if (stats.embedFailures > 0) {
			log.warn("Citation grounding: could not verify {} of {} citation(s) — querystore's embedding "
					+ "provider failed ({}); those citations are left unverified (Tier-2 entailment still "
					+ "applies wherever it was asked). Ensure querystore's embedding model is configured.",
					stats.embedFailures, references.size(), stats.firstError);
		}
		return annotated;
	}

	/**
	 * Claim selection for entailment-enabled grounding: identifies the claim sentence Tier-2 will
	 * fact-check for one cited index — or, where that sentence turns out to be a COMPOUND claim unit,
	 * the one Tier-1 alone will score, since {@link #verify} then asks Tier-2 nothing (issue #302).
	 * Runs Tier-1 embeds ONLY when the choice is ambiguous.
	 * The common case — exactly one candidate sentence (a list-style answer where each line cites
	 * its own record, a clause under clause-scope, or an enumeration item in either mode) — selects
	 * deterministically with no
	 * embedding work, and the cosine verdict is DEFERRED ({@link Tier1Result#deferred}): it is
	 * computed lazily by {@link #cosineVerdict} only if Tier-2 fails to produce a verdict.
	 * With several candidates the eager cosine argmax runs exactly as {@link #verdictTier1}
	 * would, so the chosen statement is byte-identical to the eager path's; the verdict then
	 * comes for free and is not deferred. Never throws: a selection-embedding failure yields a
	 * {@code null}-verdict, no-claim result (no Tier-2 candidate), mirroring the eager path.
	 */
	private Tier1Result selectClaim(int index, Map<Integer, String> textByIndex,
			List<Sentence> sentences, double floor,
			Map<Integer, float[]> recordVectors, Map<Integer, float[]> sentenceVectors,
			TextEmbedder embedder, GroundingStats stats) {
		String recordText = textByIndex.get(index);
		if (recordText == null || recordText.trim().isEmpty()) {
			return new Tier1Result(null, null, null, false); // nothing to compare against
		}
		// Candidate claim sentences: the ones citing this index inline; when none does
		// (citations-array-only), every sentence is a candidate — same fallback as the
		// eager path, so the selected statement cannot differ from it.
		List<Integer> candidates = new ArrayList<Integer>();
		for (int s = 0; s < sentences.size(); s++) {
			if (sentences.get(s).cites(index)) {
				candidates.add(Integer.valueOf(s));
			}
		}
		if (candidates.isEmpty()) {
			for (int s = 0; s < sentences.size(); s++) {
				candidates.add(Integer.valueOf(s));
			}
		}
		if (candidates.isEmpty()) {
			return new Tier1Result(null, null, recordText, false); // no sentences (empty answer)
		}
		if (candidates.size() == 1) {
			int only = candidates.get(0).intValue();
			Sentence claim = sentences.get(only);
			return new Tier1Result(null, claim.text, recordText, claim.isolate, claim.compoundClaim(),
					only, true);
		}
		try {
			float[] recordVector = embedRecord(index, recordText, recordVectors, embedder);
			double best = -Double.MAX_VALUE;
			int bestIdx = -1;
			for (Integer candidate : candidates) {
				int s = candidate.intValue();
				double sim = similarity(recordVector, s, sentences, sentenceVectors, embedder);
				if (sim > best) {
					best = sim;
					bestIdx = s;
				}
			}
			Sentence claim = sentences.get(bestIdx);
			return new Tier1Result(Boolean.valueOf(best >= floor), claim.text, recordText,
					claim.isolate, claim.compoundClaim(), bestIdx, false);
		}
		catch (RuntimeException e) {
			stats.recordFailure(e);
			return new Tier1Result(null, null, recordText, false);
		}
	}

	/**
	 * Lazily computes the deferred Tier-1 cosine verdict for a reference whose Tier-2 check
	 * produced no verdict — because it failed or could not answer (cap overflow, engine failure,
	 * unparseable reply), or because it was never asked AND a Tier-1 verdict could still be published.
	 * A compound claim unit does not reach here at all: it publishes nothing, so its caller skips it. Reuses the per-call
	 * record/sentence vector caches, so the work and the result are exactly what the eager path
	 * would have produced for the same (record, claim sentence) pair. Never throws: an embedding
	 * failure degrades to a {@code null} ("could not verify") verdict.
	 */
	private Tier1Result cosineVerdict(Tier1Result selected, double floor, int index,
			List<Sentence> sentences, Map<Integer, float[]> recordVectors,
			Map<Integer, float[]> sentenceVectors, TextEmbedder embedder, GroundingStats stats) {
		try {
			float[] recordVector = embedRecord(index, selected.recordText, recordVectors, embedder);
			double sim = similarity(recordVector, selected.bestSentenceIdx, sentences,
					sentenceVectors, embedder);
			return new Tier1Result(Boolean.valueOf(sim >= floor), selected.bestSentence,
					selected.recordText, selected.isolate, selected.compoundClaim,
					selected.bestSentenceIdx, false);
		}
		catch (RuntimeException e) {
			stats.recordFailure(e);
			return new Tier1Result(null, selected.bestSentence, selected.recordText,
					selected.isolate, selected.compoundClaim, selected.bestSentenceIdx, false);
		}
	}

	/**
	 * Computes the Tier-1 cosine verdict for one cited index and identifies the single best-matching
	 * claim sentence — a per-citation fragment wherever the sentence was split, by clause scope or by
	 * enumeration — used as the Tier-2 entailment target. Never throws: an embedding failure yields a {@code null} verdict.
	 */
	private Tier1Result verdictTier1(int index, Map<Integer, String> textByIndex,
			List<Sentence> sentences, double floor,
			Map<Integer, float[]> recordVectors, Map<Integer, float[]> sentenceVectors,
			TextEmbedder embedder, GroundingStats stats) {
		String recordText = textByIndex.get(index);
		if (recordText == null || recordText.trim().isEmpty()) {
			return new Tier1Result(null, null, null, false); // nothing to compare against
		}
		try {
			float[] recordVector = embedRecord(index, recordText, recordVectors, embedder);

			// Track whether ANY comparison happened separately from the best
			// score: a negative cosine is the strongest "not grounded" signal,
			// so it must produce FALSE, not be mistaken for "nothing to compare".
			double best = -Double.MAX_VALUE;
			String bestSentence = null;
			boolean bestIsolate = false;
			boolean compared = false;
			boolean anyInlineCite = false;
			for (int s = 0; s < sentences.size(); s++) {
				if (sentences.get(s).cites(index)) {
					anyInlineCite = true;
					compared = true;
					double sim = similarity(recordVector, s, sentences, sentenceVectors, embedder);
					if (sim > best) {
						best = sim;
						bestSentence = sentences.get(s).text;
						bestIsolate = sentences.get(s).isolate;
					}
				}
			}
			// No sentence cited this index inline (citations-array-only): fall
			// back to the best match against any sentence, so a record wholly
			// unrelated to the answer is still flagged without over-flagging a
			// record the model legitimately listed but did not inline-cite.
			if (!anyInlineCite) {
				for (int s = 0; s < sentences.size(); s++) {
					compared = true;
					double sim = similarity(recordVector, s, sentences, sentenceVectors, embedder);
					if (sim > best) {
						best = sim;
						bestSentence = sentences.get(s).text;
						bestIsolate = sentences.get(s).isolate;
					}
				}
			}

			if (!compared) {
				return new Tier1Result(null, null, recordText, false); // no sentences (empty answer)
			}
			// compoundClaim is reported false, not computed: this method runs only when entailment is
			// OFF (it is the other arm of the ternary that calls selectClaim), and the withholding the
			// flag feeds is gated on entailment being ON, so nothing can read a value computed here.
			//
			// The mode restriction is therefore guarded TWICE — here and by `entailmentEnabled &&` in
			// verify's unverifiable[] fill — and each guard alone is sufficient, which means neither is
			// individually observable: measured, breaking either one leaves all of
			// CitationGroundingVerifierTest green and only breaking BOTH reddens
			// compoundClaim_leavesTheTier1OnlyPathUntouched. Do not read that case as a guard on this
			// line. If the gate is ever deliberately removed, both places have to move together.
			return new Tier1Result(Boolean.valueOf(best >= floor), bestSentence, recordText, bestIsolate,
					false, -1, false);
		}
		catch (RuntimeException e) {
			// Never break the search path on a verification failure; count it for the
			// single summary log in verify() rather than spamming per citation.
			stats.recordFailure(e);
			return new Tier1Result(null, null, recordText, false);
		}
	}

	/**
	 * Wraps the batched Tier-2 call so a failure degrades to all-"could not verify" ({@code null}),
	 * leaving every affected citation on its Tier-1 verdict — the verifier never breaks the search
	 * path. Returns {@code null} (not an empty list) on failure so the caller can tell "batch failed"
	 * from "batch ran and returned verdicts".
	 */
	private List<Boolean> safeEntailsBatch(List<String> sources, List<String> statements) {
		try {
			return llmProvider.entailsBatch(sources, statements);
		}
		catch (RuntimeException e) {
			log.warn("Tier-2 batch entailment failed for {} citation(s); keeping Tier-1 verdicts",
					sources.size(), e);
			return null;
		}
	}

	/**
	 * Removes inline {@code [N]} citation markers so the entailment STATEMENT is
	 * the clinical claim alone — the markers are UI metadata, not part of what
	 * the record must support, and leaving them in only adds noise the fact-check
	 * LLM has to ignore.
	 */
	static String stripCitationMarkers(String text) {
		return ChartSearchAiUtils.INLINE_CITATION.matcher(text).replaceAll("").trim();
	}

	/** Tier-1 outcome plus the claim sentence/clause, record text, whether that clause must be
	 *  Tier-2 verified in isolation, and whether it is a compound claim unit — what {@link #verify}
	 *  needs to decide whether Tier-2 runs at all and, if it does, how. */
	private static class Tier1Result {

		final Boolean verdict;

		final String bestSentence;

		final String recordText;

		/** True when {@link #bestSentence} is a per-citation fragment of a multi-citation sentence —
		 *  a clause under clause-scope, or an enumeration item in either mode — so it must be Tier-2
		 *  verified in its own call rather than co-batched. */
		final boolean isolate;

		/** Index of {@link #bestSentence} in the verify-call's sentence list, or -1 when there is
		 *  none — lets the lazy cosine pass reuse the per-call sentence-vector cache. */
		final int bestSentenceIdx;

		/** True when the Tier-1 cosine verdict was deferred at claim-selection time (entailment
		 *  mode, unambiguous claim sentence) and must be computed lazily by
		 *  {@link #cosineVerdict} if Tier-2 yields no verdict for the reference. */
		final boolean deferred;

		/** True when the selected claim unit is a COMPOUND claim ({@link Sentence#compoundClaim()}).
		 *  {@link #verify} reads it once, at claim selection, folding it with the entailment mode into
		 *  its {@code unverifiable} array (and, with the reference-group reason, into {@code noTier2}),
		 *  and decides from those afterwards — so {@link #cosineVerdict} rebuilding a Tier1Result
		 *  cannot drop it. A flag lost in that rebuild would fail OPEN: the withholding silently
		 *  ceases and whatever cosine verdict exists is published, in either direction. The rebuild
		 *  carries it anyway, so the field is not stale for a later reader; that is belt-and-braces,
		 *  not what correctness rests on. */
		final boolean compoundClaim;

		Tier1Result(Boolean verdict, String bestSentence, String recordText, boolean isolate) {
			this(verdict, bestSentence, recordText, isolate, false, -1, false);
		}

		Tier1Result(Boolean verdict, String bestSentence, String recordText, boolean isolate,
				boolean compoundClaim, int bestSentenceIdx, boolean deferred) {
			this.verdict = verdict;
			this.bestSentence = bestSentence;
			this.recordText = recordText;
			this.isolate = isolate;
			this.compoundClaim = compoundClaim;
			this.bestSentenceIdx = bestSentenceIdx;
			this.deferred = deferred;
		}
	}

	private double similarity(float[] recordVector, int sentenceIdx,
			List<Sentence> sentences, Map<Integer, float[]> sentenceVectors, TextEmbedder embedder) {
		float[] sentenceVector = sentenceVectors.get(sentenceIdx);
		if (sentenceVector == null) {
			sentenceVector = embedder.embed(sentences.get(sentenceIdx).text);
			sentenceVectors.put(sentenceIdx, sentenceVector);
		}
		return ChartSearchAiUtils.cosineSimilarity(recordVector, sentenceVector);
	}

	private float[] embedRecord(int index, String recordText, Map<Integer, float[]> recordVectors,
			TextEmbedder embedder) {
		float[] vector = recordVectors.get(index);
		if (vector == null) {
			vector = embedder.embed(recordText);
			recordVectors.put(index, vector);
		}
		return vector;
	}

	/**
	 * Splits the answer into sentences, recording for each the set of {@code [N]}
	 * indices it cites inline. Returns an empty list for null/blank answers.
	 *
	 * <p>A sentence that ENUMERATES its citations is split per item by
	 * {@link #splitEnumeration} — in BOTH scoping modes, because an enumeration's claim is
	 * mis-identified rather than merely wide-scoped (issue #278). Every other sentence is returned
	 * whole, so sentence-scope keeps handing a compound sentence's citations one shared statement.
	 */
	static List<Sentence> splitIntoCitedSentences(String answer) {
		List<Sentence> sentences = new ArrayList<Sentence>();
		if (answer == null || answer.trim().isEmpty()) {
			return sentences;
		}
		for (String raw : SENTENCE_SPLIT.split(answer)) {
			if (raw.trim().isEmpty()) {
				continue;
			}
			Sentence sentence = new Sentence(raw);
			sentence.citedIndexes.addAll(ChartSearchAiUtils.citedIndexes(raw));
			List<Sentence> items = splitEnumeration(sentence);
			if (items != null) {
				sentences.addAll(items);
			} else {
				sentences.add(sentence);
			}
		}
		return sentences;
	}

	/**
	 * Leading separator of an enumerated item — the punctuation and coordinating conjunction that
	 * join it to its siblings ({@code ", "}, {@code ", and "}, {@code " or "}). Stripped so a
	 * claim reads as its own statement rather than a dangling continuation.
	 *
	 * <p>The {@code \b} after {@code and|or} is load-bearing: without it a first item named
	 * {@code Orphenadrine} loses its {@code Or} and the claim asks about "phenadrine", a drug that
	 * does not exist. The conjunction is optional and the punctuation classes around it are not, so
	 * {@code ", Ketoconazole"} strips exactly {@code ", "}.
	 */
	/**
	 * Most whitespace-separated words an enumerated item may carry and still be treated as a NAME
	 * rather than a clause. The split is only sound while the shared preamble carries the sentence's
	 * SUBJECT; an item long enough to be a clause may carry its own, and then the siblings' claims lose
	 * it — "Findings: the patient has diabetes [1] and asthma [2]" would ask about "Findings: asthma",
	 * which a family-history record for someone else's asthma entails. That is a citation published
	 * grounded=true that the whole-sentence claim correctly refused: fail-OPEN, in the exact
	 * subject-flip case Tier-2 exists to catch, so the bound refuses the split instead.
	 *
	 * <p><strong>This is a heuristic and the honest limit of it is stated rather than implied.</strong>
	 * Whether the preamble holds the subject is not decidable from the text without parsing it, and I
	 * could not establish a general discriminator. Word count is a proxy for "noun phrase, not clause":
	 * 3 admits the widest name-with-qualifier form the live answers produce ("Aspirin (drug allergen)")
	 * and the bare names beside it, and refuses a four-word clause. Candidates considered and NOT
	 * chosen, with no evidence separating them: requiring a comma between markers (a serial-list
	 * signal, but it admits "…diabetes [1], and asthma [2]" and refuses the common two-item "X [1] and
	 * Y [2]" list), and testing only the FIRST item (the subject can only be lost from item 1, but a
	 * later clause-shaped item is equally a sign the colon is a lead-in rather than a list header).
	 * Both directions of error are bounded the same way: too strict leaves a citation mis-scoped, which
	 * is today's behaviour and visible; too loose publishes a wrong verdict silently. So when in doubt
	 * this refuses to split.
	 *
	 * <p>Length is not the subject test at all, and this bound was measured down from doing that job.
	 * {@link #CLAUSE_MARKER} refuses a clause by its GRAMMAR at any length, which is both sharper and
	 * sufficient for every subject-bearing shape tested — a long item with no pronoun and no finite verb
	 * is a noun phrase, and splitting on it is correct rather than unsafe. What remains here is only a
	 * backstop against runaway text, so the claim handed to the judge stays bounded.
	 *
	 * <p><strong>Measured 2026-08-18, which is why it is 8 and not 3.</strong> Driving
	 * {@link #splitIntoCitedSentences} (the production splitter, no predicate re-expressed) over the
	 * 7452 names the real {@code DdiDrugReferenceSource.parse} publishes from the shipped 19 MB KB, a
	 * bound of 3 refuses <strong>1190</strong> of them — 16% of real drug names, a far bigger loss than
	 * the clause it was added to catch, and every one a citation left mis-scoped. 8 refuses 93 (1.2%).
	 * Raise this only with a fresh sweep; the distribution is long-tailed, so a value chosen by eye is
	 * wrong in the tail that matters.
	 */
	private static final int MAX_ENUMERATION_ITEM_WORDS = 8;

	/**
	 * Marks an enumerated item as a CLAUSE rather than a name, at any length — a personal pronoun or
	 * possessive, or a finite verb of clinical assertion. Any of these means the
	 * item carries its own subject, so the shared preamble is not the sentence's subject and splitting
	 * would strip it from the siblings (see {@link #MAX_ENUMERATION_ITEM_WORDS} for the failure that
	 * causes).
	 *
	 * <p>This exists because the word bound alone is POROUS, which is worth stating plainly rather than
	 * leaving for the next reader to rediscover: "he has diabetes" is three words, so it clears the
	 * bound while being exactly the clause the bound was added to refuse. Length is a proxy for
	 * "name, not clause"; these tokens say so directly, so the two nets are independent rather than
	 * redundant — one bounds size, the other detects grammar.
	 *
	 * <p>This net does NOT make the rule sound, and no claim is made that it does; it refuses the
	 * subject-bearing shapes that have been constructed and tested, and a verbless clause would pass.
	 *
	 * <p><strong>The set was measured against real names, and that removed two members.</strong> Both
	 * sweeps drive {@link #splitIntoCitedSentences} — the production splitter, no predicate
	 * re-expressed — and attribute each refusal at the CURRENT bound, so these figures are this net's
	 * own and not the length net's.
	 *
	 * <ul>
	 * <li><strong>Drug names</strong>, the 7452 the real {@code DdiDrugReferenceSource.parse} publishes
	 * from the shipped 19 MB KB: a version including the pronoun {@code i} refused <strong>14</strong>,
	 * every one a radioisotope form where {@code I} is iodine rather than a pronoun ({@code Iodide
	 * I-131}, {@code Iobenguane (I-123)}, {@code Iodine,I-125} …).</li>
	 * <li><strong>Condition, diagnosis and allergen names</strong>, the 1194 distinct forms behind the
	 * 704 conditions, 704 diagnoses and 23 allergies on the 3.7.1 demo database: a version including
	 * {@code patient} refused <strong>2</strong> — {@code Patient died} and {@code Smear positive, new
	 * tuberculosis patient}. It bought no safety in exchange: the clause it was added for ("the patient
	 * has diabetes") is caught by {@code has}, and both cycle-1 regression tests still fail closed
	 * without it.</li>
	 * </ul>
	 *
	 * <p><strong>Family and relative terms were measured and REJECTED — do not re-propose them.</strong>
	 * They look obviously right, because the family-history flip is the canonical case Tier-2 exists for
	 * and a verbless clause like "mother with asthma [1] and diabetes [2]" really does slip through this
	 * net, costing item 2 the qualifier. Adding {@code mother|father|sibling|child|family|maternal…}
	 * refused <strong>13</strong> real names across the two corpora: 6 on {@code child} alone
	 * ({@code Child Aspirin}, {@code Aspirin Child Chewable}, {@code Well child visit, newborn},
	 * {@code pfizer-biontech covid-19 vaccine (child)} …), and the rest on names such as
	 * {@code Family history of hypertension}, {@code Sibling rivalry disorder} and {@code Malaria in
	 * mother complicating pregnancy}. That is not merely a cost — it is aimed at the wrong position. An
	 * item that CARRIES a family qualifier is safe to split, because its own claim keeps it; the danger
	 * is a LATER item that lacks one. So the terms refuse exactly the lists they were meant to protect
	 * and leave the case they were meant to catch, whose item-1 text they cannot be keyed on without
	 * knowing which position it occupies. The verbless-clause residual is therefore accepted and stated
	 * rather than papered over.
	 *
	 * <p>With {@code i} and {@code patient} dropped, <strong>0</strong> of either corpus matches (93 and
	 * 24 refusals respectively, all by length). The earlier form of this comment — asserting that no
	 * drug, condition or allergen name carries a member as a whole token — was an over-claim twice
	 * over: it covered three name kinds while only drugs had been swept, and it was false for the kind
	 * that had been. Re-run BOTH sweeps before adding a member; short words are exactly what chemistry
	 * and clinical phrasing reuse. Residual errors are refusals ({@code IT band syndrome} matches
	 * {@code it}), which cost a mis-scoped citation rather than a wrong verdict.
	 */
	private static final Pattern CLAUSE_MARKER = Pattern.compile(
			"\\b(?:we|you|he|she|they|it|his|her|their"
					+ "|has|have|had|is|are|was|were|shows|showed|reports|reported"
					+ "|denies|denied|takes|took|receives|received|presents|remains)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern LEADING_ITEM_SEPARATOR =
			Pattern.compile("^[\\s,;]*(?:(?:and|or)\\b[\\s,;]*)?", Pattern.CASE_INSENSITIVE);

	/**
	 * Splits a sentence that ENUMERATES its cited records into one claim per record — the shared
	 * preamble plus that record's OWN item — or returns {@code null} when the sentence is not an
	 * enumeration and must be left to the caller's normal scoping.
	 *
	 * <p><strong>Why this exists.</strong> Both scoping modes ask the wrong-sized question of an
	 * enumeration (issue #278). Sentence-scope hands every citation the whole sentence, so each
	 * allergy record is asked to entail a conjunction naming the OTHER allergens too; clause-scope
	 * hands citation <em>k</em> the cumulative prefix, which still names items 1..<em>k</em>−1. Only
	 * the first citation is ever asked about its own claim, and a correct judge answers "no" to
	 * every other one — measured live as {@code grounded=false} on all three citations of a correct,
	 * fully-cited allergy list, which a client renders as <em>Unsupported</em>.
	 *
	 * <p><strong>Why it is keyed on a colon.</strong> The claim for one item is "preamble + that
	 * item", and the preamble is the text the items hang off — so the split needs the boundary
	 * between the preamble and the FIRST item. That boundary is not recoverable in general: in
	 * "Has diabetes [1] and hypertension [2]" the preamble could be "Has" or "Has diabetes", and
	 * guessing it short strips the subject, which is the one thing the cumulative prefix exists to
	 * retain (a subject-stripped fragment cannot catch the family-history / negation flips Tier-2 is
	 * for). A list-introducing colon is the sentence declaring that boundary itself, so it is taken
	 * as the only reliable signal rather than as a convenience. Consequence, stated rather than
	 * hidden: a comma-only enumeration ("The patient has diabetes [1], hypertension [2]") is NOT
	 * split and remains mis-scoped. Narrow and correct beats broad and
	 * subject-stripping, because this decides whether a TRUE citation is published as unsupported.
	 *
	 * <p><strong>What that consequence costs is no longer a wrong verdict (issue #302).</strong> An
	 * unsplit sentence of this shape is a COMPOUND claim unit, so under entailment its citations
	 * publish no verdict — see {@link Sentence#compoundClaim()} and this class's javadoc. They stay mis-scoped, which is
	 * what the boundary problem above makes unavoidable here, but the module no longer publishes the
	 * conjunction's refusal as each citation's own verdict.
	 *
	 * <p>Returns {@code null} — meaning "not an enumeration" — for a single-citation sentence, for a
	 * sentence with no colon before its first marker, for one where any item contributes no text of its
	 * OWN beyond its marker, and for one where any item is longer than
	 * {@link #MAX_ENUMERATION_ITEM_WORDS} words or matches {@link #CLAUSE_MARKER} (either means the
	 * item is a clause, so the colon is a lead-in rather than a list header and the preamble is not the
	 * subject — see those two constants, which are independent nets over size and grammar). That last guard is why it tests the MARKER-STRIPPED item: the
	 * substring always ends in {@code [N]}, whose characters {@link #LEADING_ITEM_SEPARATOR} cannot
	 * consume, so an emptiness check on the raw item is unreachable. A colon followed immediately by
	 * a citation ("allergies: [1], Ketoconazole [2]") is not a list of NAMED items, so reading it as
	 * one is a misread — falling back beats handing that citation a preamble-only claim that asserts
	 * nothing.
	 * Each returned item is flagged {@link Sentence#isolate}: the items share a preamble, so
	 * co-batching them would let the not-per-pair-independent LLM couple their verdicts.
	 *
	 * <p><strong>That isolation has a measured cost, and it is paid deliberately.</strong> N items
	 * become N single-pair Tier-2 calls where the whole sentence was one batched call. Measured
	 * 2026-08-18 on the streaming endpoint of a local 3.7.1 standalone (gemma-4-E4B-it-Q4_K_M, same
	 * patient and session, from the {@code [timing] searchStreaming groundMs} field): a three-item
	 * enumeration grounded in 2387 ms against 1335 ms for a one-pair batched answer, so roughly half a
	 * second per additional citation. The existing
	 * {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS} cap bounds the worst case. Do not
	 * "optimise" this back into the shared batch: coupled verdicts flip a correct citation to
	 * not-grounded SILENTLY, which is the failure class this method exists to remove, and a slower
	 * honest verdict beats a fast wrong one. Note what is NOT claimed — no before/after of the same
	 * question was measured, because the comparison above is between two shapes within one build.
	 */
	private static List<Sentence> splitEnumeration(Sentence sentence) {
		if (sentence.citedIndexes.size() <= 1) {
			return null;
		}
		Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(sentence.text);
		if (!marker.find()) {
			return null;
		}
		// lastIndexOf, not indexOf: with several colons before the items the one nearest the first
		// item is the one introducing the list ("Findings: allergies: X [1], Y [2]").
		int colon = sentence.text.lastIndexOf(':', marker.start());
		if (colon < 0) {
			return null;
		}
		String preamble = sentence.text.substring(0, colon + 1);

		List<Sentence> items = new ArrayList<Sentence>();
		int itemStart = colon + 1;
		marker.reset();
		while (marker.find()) {
			// `item` keeps its marker because the fragment's text is built from it below; `named` is
			// the same slice with the marker gone. Both come from itemSlice, so the guard below, the
			// fragment handed to the judge, and claimTextSeparatesCitations cannot disagree about
			// where this item starts.
			String item = itemSlice(sentence.text, itemStart, marker.end());
			String named = stripCitationMarkers(item);
			if (named.isEmpty() || named.split("\\s+").length > MAX_ENUMERATION_ITEM_WORDS
					|| CLAUSE_MARKER.matcher(named).find()) {
				return null;
			}
			items.add(new Sentence(preamble + " " + item,
					Collections.singleton(Integer.valueOf(marker.group(1))), true));
			itemStart = marker.end();
		}
		return items;
	}

	/**
	 * One enumerated item, from {@code fromIndex} to the end of the marker that closes it, with the
	 * leading separator that joins it to its siblings stripped ({@link #LEADING_ITEM_SEPARATOR}) —
	 * where one item ends and the next begins, defined once.
	 *
	 * <p>Three things read this boundary and all three must agree: the FRAGMENT TEXT
	 * {@link #splitEnumeration} publishes as the claim Tier-2 is asked about, that method's guard on
	 * whether the item names anything of its own, and {@link #claimTextSeparatesCitations}. The first
	 * two are the pair worth naming — a separator rule that moved for the guard but not for the
	 * fragment would let an item pass as "names something" while the statement handed to the judge
	 * still carried the joining words, silently, in the method that decides whether a TRUE citation is
	 * published as unsupported.
	 *
	 * <p>The slice keeps its citation marker, because {@link #splitEnumeration} builds the published
	 * fragment text from it; both readers that want the item's BARE text put
	 * {@link #stripCitationMarkers} over the result, and an empty answer there means the item names
	 * nothing beyond its marker.
	 */
	private static String itemSlice(String text, int fromIndex, int toIndex) {
		return LEADING_ITEM_SEPARATOR.matcher(text.substring(fromIndex, toIndex)).replaceFirst("").trim();
	}

	/**
	 * True when some pair of consecutive {@code [N]} markers in {@code text} has claim text between
	 * them — anything left once {@link #itemSlice} has taken the punctuation and coordinating
	 * conjunction that merely join a list.
	 *
	 * <p>Reads the TEXT deliberately, and only ever as a question about shape — never to re-derive
	 * which citations a claim unit is attributed to, which {@link Sentence} warns against because a
	 * clause-scoped fragment's text carries markers it is not attributed to. {@link
	 * Sentence#compoundClaim()} is the only caller and it guards this with the attributed count for
	 * exactly that reason.
	 */
	private static boolean claimTextSeparatesCitations(String text) {
		Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(text);
		if (!marker.find()) {
			return false;
		}
		int previousEnd = marker.end();
		while (marker.find()) {
			if (!stripCitationMarkers(itemSlice(text, previousEnd, marker.end())).isEmpty()) {
				return true;
			}
			previousEnd = marker.end();
		}
		return false;
	}

	/**
	 * Clause-scoped variant of {@link #splitIntoCitedSentences}: a sentence citing MORE than one
	 * record is split so each citation is checked against the answer text up to and including its
	 * own {@code [N]} marker, not the whole compound sentence. An ENUMERATING sentence never reaches
	 * this rule — {@link #splitIntoCitedSentences} has already split it per item, in either mode, so
	 * every fragment arriving here cites exactly one record and passes through unchanged (#278). The
	 * cumulative prefix below therefore governs the compound sentences that are NOT enumerations. This grounds a citation that supports
	 * its own clause but not a later clause cited by a different record — e.g. "Hearing Loss was
	 * noted as a condition [89] and diagnosed as a provisional condition [91]", where [89] (an
	 * active condition) does not support the "provisional diagnosis" clause that [91] backs. The
	 * clause is the cumulative prefix through the marker, so it retains the sentence subject whenever
	 * the subject precedes the first marker (the normal case — answers state the finding before its
	 * citation): a family-history / negation flip in a later clause is then still judged against the
	 * full preceding claim, not a subject-stripped fragment. A leading {@code [N]} with no text
	 * before it yields an empty clause, which grounds conservatively (not-grounded) rather than
	 * spuriously. Single-citation sentences are returned unchanged. Each split clause is flagged for
	 * isolation ({@link Sentence#isolate}) so Tier-2 verifies it in its OWN call rather than
	 * co-batching the sentence's citations, whose overlapping clause statements would otherwise couple
	 * the (not per-pair-independent) batched LLM verdict.
	 */
	static List<Sentence> splitIntoClauseScopedSentences(String answer) {
		List<Sentence> clauses = new ArrayList<Sentence>();
		for (Sentence sentence : splitIntoCitedSentences(answer)) {
			if (sentence.citedIndexes.size() <= 1) {
				clauses.add(sentence);
				continue;
			}
			Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(sentence.text);
			while (marker.find()) {
				Integer idx = Integer.valueOf(marker.group(1));
				clauses.add(new Sentence(sentence.text.substring(0, marker.end()),
						Collections.singleton(idx), true));
			}
		}
		return clauses;
	}

	/**
	 * An answer sentence, or a per-citation fragment of one — a clause under clause-scoped grounding,
	 * or one item of an enumerating sentence in either mode — and the citation indices it is scored
	 * against. For a whole sentence {@link #citedIndexes} is exactly the {@code [N]} markers in
	 * {@link #text}; for a clause-scoped fragment the text may contain EARLIER markers while
	 * {@code citedIndexes} holds only the one citation it is attributed to. So do NOT re-derive
	 * citedIndexes by re-parsing the text — that an enumeration item happens to carry exactly its own
	 * marker does not make re-parsing safe, because the clause-scoped fragment beside it does not.
	 */
	static class Sentence {

		final String text;

		final java.util.Set<Integer> citedIndexes = new java.util.HashSet<Integer>();

		/**
		 * True when this is a per-citation FRAGMENT of a multi-citation sentence, so its citation must
		 * be Tier-2 verified ALONE — not co-batched with the sentence's other citations, whose
		 * statements overlap (they share text) and would otherwise couple the (not
		 * per-pair-independent) batched LLM verdict. Two splitters produce such fragments: clause
		 * scope, whose cumulative prefixes overlap by length, and enumeration splitting, whose items
		 * share a preamble — the latter in EITHER mode, so this is not a clause-scope-only flag.
		 * False for a whole sentence, which is what both modes keep for a single-citation sentence and
		 * what sentence-scope keeps for a non-enumerating compound.
		 */
		final boolean isolate;

		Sentence(String text) {
			this(text, java.util.Collections.<Integer> emptySet(), false);
		}

		/** Fragment constructor: text, an explicit cited-index set, and whether the fragment must be
		 *  Tier-2 verified in isolation. Used by BOTH splitters — clause-scoped splitting, whose text
		 *  may contain earlier markers while being attributed to one citation only, and enumeration
		 *  splitting, whose text is a preamble plus one item. */
		Sentence(String text, java.util.Set<Integer> citedIndexes, boolean isolate) {
			this.text = text;
			this.citedIndexes.addAll(citedIndexes);
			this.isolate = isolate;
		}

		boolean cites(int index) {
			return citedIndexes.contains(Integer.valueOf(index));
		}

		/**
		 * True when this claim unit attaches its citations to DIFFERENT pieces of its own text: it is
		 * attributed to more than one citation AND claim text stands between two of its {@code [N]}
		 * markers. Its statement is then a conjunction, and no one cited record answers for all of it
		 * — so an entailment "no" is the expected reply whether the citation is right or wrong, and
		 * publishing that as the citation's verdict marks a correct list unsupported (issue #302).
		 *
		 * <p><strong>Both halves are load-bearing.</strong> The citation count alone would catch
		 * CO-CITATION, where nothing but a list separator stands between the markers
		 * ({@code "Infections [5], [12], [15]"}) — several records cited for ONE claim, which is a
		 * shape this module manufactures: {@code LlmAnswerExtractor.normalizeSlashCitations} rewrites
		 * a corroborated {@code [5/12/15]} group into exactly that. There the statement IS each
		 * citation's own claim, the judge's question is well-formed, and both directions of its answer
		 * are worth publishing. The text test alone would catch a clause-scoped fragment, whose text
		 * carries EARLIER markers while it is attributed to one citation; that is a real residual of
		 * the cumulative prefix and it is deliberately not closed here — clause scope is off by
		 * default, and {@link #splitIntoClauseScopedSentences} owns that trade.
		 */
		boolean compoundClaim() {
			return citedIndexes.size() > 1 && claimTextSeparatesCitations(text);
		}
	}
}
