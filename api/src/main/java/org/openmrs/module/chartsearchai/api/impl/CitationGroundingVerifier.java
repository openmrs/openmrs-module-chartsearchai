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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
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
 * catches the subject/polarity flips cosine cannot. It runs on Tier-1 passes
 * <em>and</em> failures — the dangerous case (a high-overlap but unsupported
 * citation) is a Tier-1 pass, so confirming only failures would miss it. References are verified
 * in a SINGLE batched call ({@link LlmProvider#entailsBatch}) — except that, under clause-scoped
 * grounding, the citations of one compound sentence are each verified in their OWN call: batched
 * entailment is NOT per-pair independent, so co-batching a compound sentence's citations (whose
 * overlapping clause statements differ only by length) lets the LLM couple their verdicts and
 * silently flip a correct citation to not-grounded. The total is capped at
 * {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS} pairs per answer;
 * references beyond the cap keep their Tier-1 verdict.
 *
 * <p><strong>Lazy Tier-1 under entailment.</strong> Because the Tier-2 verdict overrides Tier-1
 * wherever it lands, running the cosine pass eagerly for every reference would spend a full
 * embedding-model forward pass per record and per sentence — the dominant grounding cost on
 * CPU-only servers — on verdicts that are then discarded. So when entailment is enabled, Tier-1
 * embeds run only where they still decide something: choosing the claim sentence when more than
 * one candidate cites the record (the statement must be the cosine-best match, identical to the
 * eager path), and supplying the fallback verdict for references whose Tier-2 check produced none
 * (cap overflow, engine failure) — computed lazily, after Tier-2. A list-style answer where each
 * line cites its own record runs no Tier-1 embeds at all. A consequence pinned in tests: a
 * broken or absent Tier-1 embedding model no longer blocks Tier-2 verdicts for unambiguous
 * claim sentences — previously it silently downgraded every citation to "unverified".
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
	private EmbeddingProvider embeddingProvider;

	@Autowired
	private LlmProvider llmProvider;

	/** Test seam: production wires {@link EmbeddingProvider} via {@link Autowired}. */
	void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
		this.embeddingProvider = embeddingProvider;
	}

	/** Test seam: production wires {@link LlmProvider} via {@link Autowired}. */
	void setLlmProvider(LlmProvider llmProvider) {
		this.llmProvider = llmProvider;
	}

	/** Embeds text to a vector — abstracts over which module's provider is used. */
	interface TextEmbedder {

		float[] embed(String text);
	}

	/**
	 * Resolves the embedding provider for a grounding run. When querystore is the
	 * retrieval backend ({@code chartsearchai.querystore.enabled=true}), grounding
	 * reuses querystore's configured provider (the same e5/ONNX model that built
	 * the index) so the verifier embeds with the same model as retrieval — and so
	 * no separate chartsearchai embedding model has to be installed. Falls back to
	 * chartsearchai's own {@link EmbeddingProvider} when querystore is absent,
	 * disabled, or its provider can't be resolved. Never throws.
	 *
	 * <p>querystore is a {@code provided}-scope dependency (compiled against, not bundled). It is
	 * now a required module, so it should be present at runtime, but the {@code LinkageError} catch
	 * (covering {@code NoClassDefFoundError}) is kept as defense-in-depth, mirroring
	 * {@code QueryStoreChartBuilder}'s guard.
	 */
	TextEmbedder resolveEmbedder() {
		try {
			if (ChartSearchAiUtils.isQueryStoreEnabled()) {
				org.openmrs.module.querystore.embedding.EmbeddingProvider qs =
						org.openmrs.api.context.Context.getRegisteredComponent(
								"querystore.embedding.dispatcher",
								org.openmrs.module.querystore.embedding.EmbeddingProvider.class);
				if (qs != null) {
					return qs::embed;
				}
			}
		}
		catch (RuntimeException | LinkageError e) {
			log.warn("Grounding: querystore embedding provider unavailable ({}); "
					+ "falling back to chartsearchai's own embedding model", e.toString());
		}
		return embeddingProvider == null ? null : embeddingProvider::embed;
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
	 * {@code null} verdict ("could not verify").
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
	 * record text is confirmed by a Tier-2 LLM entailment verdict that is authoritative
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
	 * compound sentence (see {@link #splitIntoClauseScopedSentences}). Those split citations are each
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
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				textByIndex.put(mapping.getIndex(), mapping.getText());
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
		// Non-isolate candidates share ONE batch — their statements don't overlap, so the batched
		// (not per-pair-independent) LLM cannot couple them.
		List<Integer> batchPositions = new ArrayList<Integer>();
		List<String> batchSources = new ArrayList<String>();
		List<String> batchStatements = new ArrayList<String>();
		// Isolate candidates — the citations of one compound sentence under clause-scope, whose clause
		// statements overlap — are each verified in their OWN single-pair call so the LLM cannot couple
		// their verdicts (see this class's Tier-2 javadoc).
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
			// Tier-2 candidate: needs a concrete claim sentence to fact-check against the record.
			// Candidacy deliberately does NOT require a Tier-1 verdict: for an unambiguous claim
			// sentence the verdict is deferred (and may never be needed), and a broken Tier-1
			// embedder must not block the authoritative Tier-2 check it has no part in.
			if (entailmentEnabled && tier1.bestSentence != null
					&& tier1.recordText != null) {
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
		// Isolate citations (one compound sentence's, under clause-scope) get a single-pair call each,
		// so the batched LLM cannot couple their overlapping clause statements into each other's
		// verdicts. Same null-degrades-to-Tier-1 contract as the batch.
		for (int k = 0; k < isolatePositions.size(); k++) {
			List<Boolean> verdict = safeEntailsBatch(Collections.singletonList(isolateSources.get(k)),
					Collections.singletonList(isolateStatements.get(k)));
			tier2Verdict[isolatePositions.get(k)] = (verdict != null && !verdict.isEmpty())
					? verdict.get(0) : null;
		}

		// Lazy Tier-1: references whose cosine verdict was deferred at claim-selection time get it
		// computed now, but ONLY where Tier-2 did not reach a verdict (cap overflow, engine failure,
		// unparseable reply) — everywhere else the eager cosine would have been overridden and its
		// embedding cost (the dominant grounding cost on CPU) wasted.
		for (int i = 0; i < references.size(); i++) {
			if (tier2Verdict[i] == null && tier1Results[i].deferred) {
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
			log.warn("Citation grounding: could not verify {} of {} citation(s) — embedding provider "
					+ "failed ({}); those citations are left unverified. If querystore is the backend, "
					+ "ensure its embedding model is configured; otherwise set "
					+ "chartsearchai.embedding.modelFilePath.",
					stats.embedFailures, references.size(), stats.firstError);
		}
		return annotated;
	}

	/**
	 * Claim selection for entailment-enabled grounding: identifies the claim sentence Tier-2 will
	 * fact-check for one cited index, running Tier-1 embeds ONLY when the choice is ambiguous.
	 * The common case — exactly one candidate sentence (a list-style answer where each line cites
	 * its own record, or a clause under clause-scope) — selects deterministically with no
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
			return new Tier1Result(null, claim.text, recordText, claim.isolate, only, true);
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
					claim.isolate, bestIdx, false);
		}
		catch (RuntimeException e) {
			stats.recordFailure(e);
			return new Tier1Result(null, null, recordText, false);
		}
	}

	/**
	 * Lazily computes the deferred Tier-1 cosine verdict for a reference whose Tier-2 check
	 * produced no verdict (cap overflow, engine failure, unparseable reply). Reuses the per-call
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
					selected.recordText, selected.isolate, selected.bestSentenceIdx, false);
		}
		catch (RuntimeException e) {
			stats.recordFailure(e);
			return new Tier1Result(null, selected.bestSentence, selected.recordText,
					selected.isolate, selected.bestSentenceIdx, false);
		}
	}

	/**
	 * Computes the Tier-1 cosine verdict for one cited index and identifies the single best-matching
	 * claim sentence — a clause when grounding is clause-scoped — used as the Tier-2 entailment
	 * target. Never throws: an embedding failure yields a {@code null} verdict.
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
			return new Tier1Result(Boolean.valueOf(best >= floor), bestSentence, recordText, bestIsolate);
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

	/** Tier-1 outcome plus the claim sentence/clause, record text, and whether that clause must be
	 *  Tier-2 verified in isolation — everything Tier-2 needs. */
	private static class Tier1Result {

		final Boolean verdict;

		final String bestSentence;

		final String recordText;

		/** True when {@link #bestSentence} is a clause from a multi-citation sentence under
		 *  clause-scope, so it must be Tier-2 verified in its own call rather than co-batched. */
		final boolean isolate;

		/** Index of {@link #bestSentence} in the verify-call's sentence list, or -1 when there is
		 *  none — lets the lazy cosine pass reuse the per-call sentence-vector cache. */
		final int bestSentenceIdx;

		/** True when the Tier-1 cosine verdict was deferred at claim-selection time (entailment
		 *  mode, unambiguous claim sentence) and must be computed lazily by
		 *  {@link #cosineVerdict} if Tier-2 yields no verdict for the reference. */
		final boolean deferred;

		Tier1Result(Boolean verdict, String bestSentence, String recordText, boolean isolate) {
			this(verdict, bestSentence, recordText, isolate, -1, false);
		}

		Tier1Result(Boolean verdict, String bestSentence, String recordText, boolean isolate,
				int bestSentenceIdx, boolean deferred) {
			this.verdict = verdict;
			this.bestSentence = bestSentence;
			this.recordText = recordText;
			this.isolate = isolate;
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
			Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(raw);
			while (marker.find()) {
				sentence.citedIndexes.add(Integer.valueOf(marker.group(1)));
			}
			sentences.add(sentence);
		}
		return sentences;
	}

	/**
	 * Clause-scoped variant of {@link #splitIntoCitedSentences}: a sentence citing MORE than one
	 * record is split so each citation is checked against the answer text up to and including its
	 * own {@code [N]} marker, not the whole compound sentence. This grounds a citation that supports
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
	 * An answer sentence (or, under clause-scoped grounding, a clause) and the citation indices it is
	 * scored against. For a whole sentence (the default path) {@link #citedIndexes} is exactly the
	 * {@code [N]} markers in {@link #text}; for a clause-scoped fragment the text may contain earlier
	 * markers while {@code citedIndexes} holds only the one citation the clause is attributed to — so
	 * do NOT re-derive citedIndexes by re-parsing the text.
	 */
	static class Sentence {

		final String text;

		final java.util.Set<Integer> citedIndexes = new java.util.HashSet<Integer>();

		/**
		 * True when this is a clause split from a MULTI-citation sentence, so its citation must be
		 * Tier-2 verified ALONE — not co-batched with the sentence's other citations, whose
		 * overlapping clause-scoped statements would otherwise couple the (not per-pair-independent)
		 * batched LLM verdict. False for a whole sentence: sentence-scope, or a single-citation
		 * sentence under clause-scope.
		 */
		final boolean isolate;

		Sentence(String text) {
			this(text, java.util.Collections.<Integer> emptySet(), false);
		}

		/** Clause constructor: text, an explicit cited-index set, and whether the clause must be
		 *  Tier-2 verified in isolation (used by clause-scoped splitting, where a clause's text may
		 *  contain earlier markers but is attributed to one citation only). */
		Sentence(String text, java.util.Set<Integer> citedIndexes, boolean isolate) {
			this.text = text;
			this.citedIndexes.addAll(citedIndexes);
			this.isolate = isolate;
		}

		boolean cites(int index) {
			return citedIndexes.contains(Integer.valueOf(index));
		}
	}
}
