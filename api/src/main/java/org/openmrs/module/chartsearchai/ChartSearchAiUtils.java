/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_CONDITION;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_OBS;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_ORDER;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.Concept;
import org.openmrs.ConceptSet;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiUtils {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiUtils.class);

	/**
	 * Matches an inline {@code [N]} citation marker in LLM answer prose. The
	 * single source of truth for citation-marker parsing, shared by every consumer
	 * {@link #citedIndexes} names — citation extraction ({@code LlmInferenceService}),
	 * grounding ({@code CitationGroundingVerifier}), safety echo-scoping
	 * ({@code DrugSafetyValidator}) and the class-code parenthetical check
	 * ({@code ClassCodeFidelityCheck}) — so they cannot drift apart.
	 *
	 * <p>Deliberately single-index. Small local models also emit compact shorthand —
	 * {@code [6, 7]} (measured on the rc.2 standalone, 2026-07-21: the #76 guard read such
	 * an answer as citing nothing inline and dropped every reference) and {@code [6/7]} —
	 * but that shorthand is rewritten into single-index markers UPSTREAM by
	 * {@code LlmAnswerExtractor.normalizeSlashCitations}, and only when the structured
	 * citations array corroborates the group. Matching compact forms here instead would
	 * turn bracketed numeric VALUES ({@code [120, 80]}) into phantom citations in
	 * extraction and strip them from grounding claim text before entailment.
	 */
	public static final Pattern INLINE_CITATION = Pattern.compile("\\[(\\d{1,9})\\]");

	/**
	 * The characters this module reads as the end of a sentence. One home, because
	 * {@link #SENTENCE_BOUNDARY}, {@link #mayEndASentence} and
	 * {@code DrugSafetyValidator.endSentence} are three QUESTIONS over one set and a second spelling
	 * of the set would let them disagree about what a sentence is — which is not hypothetical here:
	 * {@code ReferenceProseFidelityCheck}'s record-sentence exit is only safe while the character
	 * {@code endSentence} appends is one this set contains, and that check's own javadoc rests on it.
	 *
	 * <p>Public for that third consumer, in another package. A caller does not test a character
	 * against a literal of its own.
	 *
	 * <p>It is interpolated into {@link #SENTENCE_BOUNDARY}'s character class through
	 * {@link Pattern#quote}, so a member with meaning inside a class — {@code ]}, {@code ^},
	 * {@code \} or a {@code -} in range position — is a literal rather than a syntax change. Unquoted
	 * it changes the PATTERN rather than the set: appending {@code ]} makes the lookbehind read
	 * "a terminator followed by {@code ]}", so the splitter stops splitting on punctuation while
	 * {@link #mayEndASentence}, which reads this by {@code indexOf}, carries on. That is the
	 * divergence one set exists to prevent, and it is LOUD rather than silent — that arrangement
	 * reddens the grounding verifier's own suite wholesale. No count of it is published here: one was,
	 * and it went stale in the very commit that wrote it, because the case added beside it in that
	 * commit adds a failure of its own. Append a character and read the failures. The quoting is here
	 * so the set can be edited as a set, not because the alternative hides.
	 */
	public static final String SENTENCE_TERMINATORS = ".!?";

	/**
	 * Where one sentence of an answer or a record ends and the next begins: a {@code .}, {@code !}
	 * or {@code ?} followed by whitespace, or a line break. The SPLITTING question over
	 * {@link #SENTENCE_TERMINATORS}: {@code CitationGroundingVerifier} cuts a text into the units it
	 * grades on it, and it is strict because a splitter that cut at every dot would halve a sentence
	 * at {@code Q12H.} or at an abbreviation. {@link #mayEndASentence} is the other question over the
	 * same set — could a sentence have ended in this GAP — and is deliberately weaker; read its
	 * javadoc before reaching for either, because they are not interchangeable in either direction.
	 *
	 * <p>Two spellings of one terminator set is the shape issue #260 records the cost of: the two
	 * disagreed in both directions and both silently. So a consumer takes one of these two entry
	 * points, and never a regex of its own.
	 *
	 * <p>The line-break arm is not decoration: the system prompt instructs the model to "use numbered
	 * lines or simple newlines to structure lists", so a multi-item answer often carries no
	 * sentence-ending punctuation at all.
	 */
	public static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
			"(?<=[" + Pattern.quote(SENTENCE_TERMINATORS) + "])\\s+|[\\r\\n]+");

	/**
	 * @return whether a sentence COULD have ended inside {@code between} — the text separating two
	 *         adjacent words — which is a deliberately weaker question than
	 *         {@link #SENTENCE_BOUNDARY} asks. Any terminator anywhere in the gap answers yes, and
	 *         so does a line break; nothing has to follow the terminator.
	 *
	 *         <p><b>Weaker on purpose, and the weakness is the correctness.</b> Its caller
	 *         ({@code ReferenceProseFidelityCheck}) uses the answer only to STAY SILENT, so a gap
	 *         read as a sentence end can only suppress a report and never cause one — which is what
	 *         makes that check's "loses recall, never precision" property true. Asking
	 *         {@code SENTENCE_BOUNDARY} instead was measured wrong in exactly that direction: it
	 *         requires the terminator to be followed IMMEDIATELY by whitespace, so a quotation the
	 *         model closed — {@code ."} or {@code .)} , and this module's own reference prose is full
	 *         of {@code (SSRIs)} and {@code (M1)} — is not a boundary, and a faithful quotation
	 *         followed by the model's own next sentence was reported as a substitution.
	 *
	 *         <p>It must not be used to SPLIT a text into units: {@code Q12H. }-shaped prose and an
	 *         abbreviation dot both answer yes here, and a splitter that believed them would cut a
	 *         sentence in half. That is {@link #SENTENCE_BOUNDARY}'s question, and the two are kept
	 *         apart for the reason issue #260 records — one rule, one terminator set, one named entry
	 *         point per question, never a second regex at a call site.
	 */
	public static boolean mayEndASentence(String between) {
		if (between == null) {
			return false;
		}
		for (int at = 0; at < between.length(); at++) {
			char c = between.charAt(at);
			if (SENTENCE_TERMINATORS.indexOf(c) >= 0 || c == '\r' || c == '\n') {
				return true;
			}
		}
		return false;
	}

	/**
	 * Decodes every inline {@code [N]} citation marker in {@code text} to its record index,
	 * in first-appearance order. The shared decode step over {@link #INLINE_CITATION} for
	 * citation extraction ({@code LlmInferenceService}), grounding
	 * ({@code CitationGroundingVerifier}), safety echo-scoping ({@code DrugSafetyValidator}) and —
	 * since issue #338 — the check that asks whether a marker sits INSIDE a class-code parenthetical
	 * ({@code ClassCodeFidelityCheck}), so those consumers cannot drift. (The clause-scoped splitter
	 * keeps its own matcher — it needs each marker's text offset, which a set of indexes cannot
	 * carry.) Returns an empty set for null/blank text.
	 */
	public static Set<Integer> citedIndexes(String text) {
		Set<Integer> indexes = new java.util.LinkedHashSet<Integer>();
		if (text == null || text.isEmpty()) {
			return indexes;
		}
		java.util.regex.Matcher marker = INLINE_CITATION.matcher(text);
		while (marker.find()) {
			indexes.add(Integer.valueOf(marker.group(1)));
		}
		return indexes;
	}

	/**
	 * Classifies a cited record's resource type into the group a client renders it under:
	 * {@link ChartSearchAiConstants#REFERENCE_GROUP_CHART} for evidence retrieved from this
	 * patient's chart, {@link ChartSearchAiConstants#REFERENCE_GROUP_REFERENCE} for
	 * module-supplied reference prose. This is the single entry point for the PROVENANCE decision:
	 * code that labels or orders references for a client must ask here rather than
	 * compare {@code resourceType} itself, so the split stays in one place as further kinds of
	 * injected record are added — several exist already, and they do not all fall on the same side
	 * (see below).
	 *
	 * <p>Four behaviours now hang off this one classification, not just the display grouping. The
	 * demote-only grounding carve-out in {@code CitationGroundingVerifier} is derived from it via
	 * {@link #isGroundingDemoteOnly}. That gate used to test the {@code drug_reference} type directly,
	 * so when {@code safety_finding} arrived (#110) it was classified here and NOT registered there,
	 * and the module's own deterministic findings were graded as retrieved chart evidence — publishing
	 * unstable {@code grounded} verdicts with no error anywhere (issue #122). Deriving both from one
	 * classification is what makes that class of omission unrepresentable, and it is why editing this
	 * method now also changes whether a type's citations can be verified. Those two are swept off one
	 * enumeration in {@code ChartSearchAiReferenceGroupTest}. The third is the wire: since #201 a
	 * reference-group citation publishes no verdict at all, so editing this method also changes what a
	 * CLIENT can see — swept off its own enumeration in
	 * {@code ChartSearchAiReferenceGroundingWithholdingTest}, in the omod module, because that is
	 * where the serializer lives.
	 *
	 * <p>The fourth is prompt COST: {@link #referenceSlice} measures how much of an assembled chart is
	 * reference material, which is the durable observable issue #229 asks for. It reads this
	 * classification rather than a list of type names for the same reason the other three do — so a
	 * further injected kind is measured automatically instead of being silently omitted — and the
	 * fail-safe below means it UNDER-reports an unrecognised type rather than over-reporting it,
	 * which is the safe direction for a number an operator reads as a floor on prompt spend.
	 *
	 * <p>The two groups are exhaustive because exactly two code paths mint a
	 * {@code RecordMapping}: {@code PatientChartSerializer}, which passes through whatever
	 * type querystore retrieved, and {@code DrugReferenceInjector}, which writes
	 * {@code drug_reference}, {@code safety_finding}, {@code drug_class_note} and
	 * {@code active_drug_order}. Not everything
	 * injected is reference material: an {@code active_drug_order} record is the patient's own
	 * active order, read from {@code OrderService} when the retrieved chart cannot substantiate it,
	 * so it groups as chart evidence — which is also what the fallback below yields, deliberately
	 * rather than by omission (the decision is recorded in {@code ChartSearchAiReferenceGroupTest}).
	 *
	 * <p>Anything unrecognised — including {@code null} — fails safe to chart evidence.
	 * Labelling an unknown type as reference material would assert a module provenance we
	 * cannot demonstrate; grouping it as chart evidence keeps it in the main list where it is
	 * judged against the record it points at.
	 *
	 * @param resourceType the cited record's resource type, may be null
	 * @return the group wire value, never null
	 */
	public static String referenceGroup(String resourceType) {
		return ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(resourceType)
				|| ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(resourceType)
				|| ChartSearchAiConstants.RESOURCE_TYPE_DRUG_CLASS_NOTE.equals(resourceType)
						? ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE
						: ChartSearchAiConstants.REFERENCE_GROUP_CHART;
	}

	/**
	 * Whether a cited record of {@code resourceType} is DEMOTE-ONLY for citation grounding: its
	 * verdict may render {@code false} (an off-topic citation) or {@code null} (unverified), never
	 * {@code true}, and it never enters — nor consumes the per-answer cap of — the Tier-2 entailment
	 * pass. (The {@code false} survives except where such a citation also sits inside a compound claim
	 * unit under entailment, where the stronger #302 rule withholds even that; nothing downstream sees
	 * the difference, since #201 withholds every reference-group verdict at the wire.) True exactly for {@link ChartSearchAiConstants#REFERENCE_GROUP_REFERENCE} material: this
	 * is a named view of {@link #referenceGroup}, not a second classification, so there is no list of
	 * type names here to fall out of step with that one.
	 *
	 * <p><strong>This is the grading rule, not the wire — and since issue #284 it also decides one
	 * CHART citation's published verdict.</strong> A chart citation whose claim rests on a record
	 * this predicate calls reference material has its entailment NEGATIVE withheld, so what is
	 * classified here is no longer the only citation affected by the classification. The rest of
	 * this paragraph is about the classified citation's own verdict. Since issue #201 the REST layer
	 * publishes no verdict at all for reference material — {@code grounded} serializes as
	 * {@code null} for a {@code reference}-group citation whatever this pass concluded, at every
	 * emission site (see {@code ChartSearchAiRestController.groundedForWire}). The surviving
	 * {@code false} below is therefore module-internal: still computed, still returned on
	 * {@code RecordReference.getGrounded()}, and no longer published — because its meaning is
	 * "off-topic citation" and reading it as anything else renders the module's own deterministic
	 * finding as unsupported. Note that the Tier-2 exclusion, the {@code TRUE}-to-{@code null}
	 * demotion and the composite-claim withholding above are driven by THIS predicate rather than by
	 * that verdict — but it is not the only thing that holds a verdict back:
	 * {@code CitationGroundingVerifier} treats a COMPOUND claim unit (issue #302) — a fact about the
	 * shape of the claim rather than the provenance of the record — more strictly still. That one
	 * publishes nothing in either direction and skips Tier-1 as well as Tier-2, under entailment only,
	 * where this predicate demotes in either mode and — except where the two overlap, and the stronger
	 * rule wins — keeps its cosine FAIL. So the two are not the same treatment, and a citation can be
	 * held back without this predicate being true of it.
	 *
	 * <p><strong>Why module-supplied material cannot be verified.</strong> An answer sentence citing
	 * module-rendered reference prose is typically a recitation of it, and a recitation embeds
	 * near-identically to its source whether or not it swaps subject roles ("erythromycin decreases X"
	 * against the record's "ivosidenib decreases X … including erythromycin"). The same lexical
	 * containment defeats the Tier-2 judge: measured on the live pipeline, 4/4 role-swapped
	 * recitations were judged entailed while the one faithful recitation was judged not (issue #106).
	 * A passing verdict is therefore false assurance. A FAILING verdict still carries information — it
	 * says the citation is not about the record at all — so the flag is kept and only the pass is
	 * withheld. Faithfulness of reference content is checked deterministically instead, by two exact
	 * comparisons that run after every answer: {@code ClassCodeFidelityCheck} for an ATC class code
	 * the answer states that no cited record does (issue #142), report-only, and
	 * {@code ReferenceProseFidelityCheck} for an answer that reproduces a cited reference record's
	 * prose and then substitutes its own words inside the sentence it was copying (issue #337), whose
	 * answer is also published as {@code unfaithfullyRenderedCitations}. NOT
	 * by the {@code DrugSafetyValidator} chips, which this javadoc said until #337: they carry the
	 * deterministic text but are an independent list nothing reconciles against the answer.
	 *
	 * <p><strong>It follows from provenance, not from being injected.</strong> An
	 * {@link ChartSearchAiConstants#RESOURCE_TYPE_ACTIVE_DRUG_ORDER} record is injected yet groups as
	 * chart evidence — one drug name asserted of this patient, carrying the real {@code Order} uuid,
	 * with no subject roles to swap — so it is graded normally; demoting it would strip the
	 * faithfulness check from the very record injected to stop the answer contradicting the safety
	 * chips (#118). Conversely a {@link ChartSearchAiConstants#RESOURCE_TYPE_SAFETY_FINDING} is
	 * patient-specific but module-derived, and its rendering ("&lt;Drug&gt; interacts with active order
	 * &lt;Partner&gt; — Major. &lt;mechanism&gt;") is precisely the role-swappable prose above, which is why
	 * grading it produced verdicts that tracked embedding noise rather than the finding (issue #122).
	 *
	 * <p><strong>The unrecognised-type fallback grades normally</strong>, following
	 * {@link #referenceGroup}'s fail-safe, and that is deliberate in this direction too: querystore
	 * passes through chart types this module declares no constant for ({@code drug_order},
	 * {@code visit}, {@code encounter} …), so demoting unknown types would silently stop verifying
	 * most real chart citations. The cost is that a module-supplied type introduced as a bare string
	 * literal — rather than as a {@code RESOURCE_TYPE_*} constant, which
	 * {@code ChartSearchAiReferenceGroupTest}'s sweep would catch — would be graded as chart evidence;
	 * {@code DrugReferenceInjector}'s class javadoc warns against exactly that.
	 *
	 * @param resourceType the cited record's resource type, may be null
	 * @return true when a grounding pass may at most demote this record's citation
	 */
	public static boolean isGroundingDemoteOnly(String resourceType) {
		return isReferenceMaterial(resourceType);
	}

	/**
	 * The one spelling of "this type is module-supplied reference material", which both
	 * {@link #isGroundingDemoteOnly} and {@link #referenceSlice} delegate to. Private because it is
	 * not a third classification: {@link #referenceGroup} decides, and this is the boolean reading of
	 * its answer. It exists so the comparison is written once — the same argument
	 * {@code isGroundingDemoteOnly}'s javadoc makes for having no type list of its own, applied one
	 * level down now that a second view needs the same question.
	 *
	 * <p>The size metric deliberately does NOT go through {@code isGroundingDemoteOnly}. That method
	 * names a GRADING rule, and a caller measuring prompt cost has no business depending on what
	 * grounding does; were the two ever to diverge, the one that must move is the grading rule.
	 */
	private static boolean isReferenceMaterial(String resourceType) {
		return ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(referenceGroup(resourceType));
	}

	/**
	 * How much of an assembled chart is module-supplied reference material — the record count and the
	 * character total, together, because they answer different halves of one question and either
	 * alone is misleading (issue #229).
	 *
	 * <p><b>Why this exists.</b> Nothing bounds how many {@code drug_reference} and
	 * {@code safety_finding} records {@code DrugReferenceInjector} appends;
	 * {@code MAX_INTERACTION_RENDER_CHARS} is a per-RECORD budget, so N records cost N times it, and
	 * the only signal that any of it happened was one DEBUG line. On OpenMRS the {@code log.level}
	 * global property is not applied at startup, so that line is not reachable by configuration
	 * alone — the prompt slice a clinician's answer was built from could not be measured after the
	 * fact at all. This is the derivation the durable channel reads:
	 * {@code ChartAnswer.getReferenceSlice()} carries it to the audit row.
	 *
	 * <p><b>What it counts, stated so the number is not read as more than it is.</b> It counts every
	 * mapping the chart carries whose type {@link #referenceGroup} calls reference material — not
	 * "everything the injector added", which is a different and wrong set: an
	 * {@link ChartSearchAiConstants#RESOURCE_TYPE_ACTIVE_DRUG_ORDER} record is injected and is the
	 * patient's own prescription, so it groups as chart evidence and is outside this number. Nor is
	 * it a claim about who MINTED the record: {@code PatientChartSerializer} passes through whatever
	 * type querystore retrieved, so a reference-group type arriving that way would be counted here —
	 * which is the honest reading for a prompt-cost figure, since the cost is the same whoever wrote
	 * the line.
	 *
	 * <p>Characters are the rendered record text, which is what the model reads and what crowds out
	 * chart records, and it excludes the {@code "[N] "} citation prefix and the newline the chart's own
	 * assembly adds. Scope the reading of that to the injector's records, which is where every
	 * reference-group record comes from today: there the mapping text and the chart line are
	 * byte-identical by construction, so the total is a floor on the bytes spent. It is not a general
	 * property of a {@code RecordMapping} — {@code PatientChartSerializer} carries an inline date and
	 * group label on the mapping that the chart line run-length-dedups away — so were a reference-group
	 * type ever to arrive through querystore, its characters could exceed what the prompt spent on it.
	 *
	 * @param mappings the assembled chart's mappings, may be null
	 * @return the slice, never null; zero/zero when nothing reference-group is present, which is a
	 *         real measurement and not the same as "nothing was measured"
	 */
	public static ReferenceSlice referenceSlice(List<RecordMapping> mappings) {
		int records = 0;
		int characters = 0;
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				if (mapping != null && isReferenceMaterial(mapping.getResourceType())) {
					records++;
					if (mapping.getText() != null) {
						characters += mapping.getText().length();
					}
				}
			}
		}
		return new ReferenceSlice(records, characters);
	}

	/**
	 * How much reference material one assembled chart carried: a record count and a character total,
	 * held together because a count alone does not say what the slice cost and a character total
	 * alone does not say how many citations the model was offered.
	 *
	 * <p>One type rather than two ints so the pair cannot come apart in transit — it travels from the
	 * chart, through {@code ChartAnswer}, to two audit columns, and a caller cannot supply one half
	 * of it.
	 */
	public static final class ReferenceSlice {

		private final int records;

		private final int characters;

		public ReferenceSlice(int records, int characters) {
			this.records = records;
			this.characters = characters;
		}

		/** How many reference-group records the chart carried. */
		public int getRecords() {
			return records;
		}

		/** How many characters of rendered reference-record text the chart carried. */
		public int getCharacters() {
			return characters;
		}

		@Override
		public String toString() {
			return records + " record(s), " + characters + " chars";
		}
	}

	/**
	 * The drug CLASS one assembled chart reports as named-but-unresolved, or {@code null} where it
	 * reports none — the wire-facing half of issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/354">#354</a>, carried
	 * to the response as {@code ChartAnswer.getUnresolvedDrugClass()}.
	 *
	 * <p><b>Why the chart is asked rather than the question.</b> The statement and the injected
	 * {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_CLASS_NOTE} record must never disagree, and a
	 * consumer that re-asked {@code DrugReferenceService.namedDrugClass} would be a second resolution
	 * of one question — the shape issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/151">#151</a> records as
	 * having let two layers disagree about the patient's own orders, silently and in one direction.
	 * That accessor is also not enough on its own: the note is raised only where the question resolved
	 * NO substance, and only where question-driven injection is enabled at all, neither of which it
	 * asks. Reading the injector's own output makes the wire statement true exactly when the prompt
	 * carries the note, by construction rather than by agreement.
	 *
	 * <p><b>It names the ONE type deliberately, and that is not the hardcode
	 * {@link #referenceGroup} forbids.</b> That rule is about GROUPING questions — asking whether a
	 * citation is reference material by naming a type is how {@code safety_finding} was graded as
	 * chart evidence (issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/122">#122</a>). This
	 * asks which record states a class, and exactly one type does; a fourth reference-group type
	 * added later states nothing about a class and must not be picked up here.
	 *
	 * <p>The value is the note's {@code resourceUuid}, which for this type is the class name and not
	 * a row — see {@code DrugReferenceInjector.injectRecords}, which is the sole writer of it, and the
	 * {@code README} section that tells a client the same thing. The FIRST such record wins; the
	 * injector appends at most one, so nothing here picks between two.
	 *
	 * @param mappings the assembled chart's mappings, may be null
	 * @return the class name, or null where the chart carries no class note — which covers a question
	 *         naming no class, a question that resolved a substance, and the drug-reference feature
	 *         being off, and deliberately does not distinguish them: what a client renders is the
	 *         positive statement, and there is no negative one to make
	 */
	public static String unresolvedDrugClass(List<RecordMapping> mappings) {
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				if (mapping != null && ChartSearchAiConstants.RESOURCE_TYPE_DRUG_CLASS_NOTE
						.equals(mapping.getResourceType())) {
					return mapping.getResourceUuid();
				}
			}
		}
		return null;
	}

	/**
	 * Builds a composite key from a resource type and resource UUID.
	 * This is the single canonical format for resource keys used across
	 * retrieval pipelines, filter methods, and result sets.
	 *
	 * @param resourceType the resource type constant
	 * @param resourceUuid the resource UUID
	 * @return a key in the format "resourceType:resourceUuid"
	 */
	public static String resourceKey(String resourceType, String resourceUuid) {
		return resourceType + ":" + resourceUuid;
	}

	/**
	 * Returns a semantic prefix for the given resource type and text, used when computing
	 * embeddings to help the embedding model distinguish between record types.
	 * This prefix is only prepended to the text for embedding computation, not
	 * for display in the LLM prompt.
	 *
	 * @param resourceType the resource type constant
	 * @param text the serialized record text, used to refine the prefix for types
	 *        that have sub-types (e.g. drug orders vs test orders)
	 * @return a descriptive prefix ending with ": "
	 */
	private static String getEmbeddingPrefix(String resourceType, String text) {
		switch (resourceType) {
			case RESOURCE_TYPE_OBS:
				return "Clinical observation: ";
			case RESOURCE_TYPE_CONDITION:
				return "Medical condition: ";
			case RESOURCE_TYPE_ALLERGY:
				return "Patient allergy: ";
			case RESOURCE_TYPE_DIAGNOSIS:
				return "Clinical diagnosis: ";
			case RESOURCE_TYPE_ORDER:
				if (text != null && text.startsWith("Drug order:")) {
					return "Medication prescription: ";
				}
				if (text != null && text.startsWith("Test order:")) {
					return "Lab or diagnostic test: ";
				}
				if (text != null && text.startsWith("Referral order:")) {
					return "Clinical referral: ";
				}
				return "Clinical order: ";
			case RESOURCE_TYPE_PROGRAM:
				return "Program enrollment: ";
			case RESOURCE_TYPE_MEDICATION_DISPENSE:
				return "Medication dispensed: ";
			default:
				return "";
		}
	}

	/**
	 * Builds the full prefixed text used for embedding and keyword matching.
	 * This is the single source of truth for the
	 * {@code getEmbeddingPrefix(resourceType, text) + text} pattern.
	 *
	 * @param resourceType the resource type constant
	 * @param text the serialized record text
	 * @return the prefixed text ready for embedding or keyword scoring
	 */
	public static String buildPrefixedText(String resourceType, String text) {
		return buildPrefixedText(resourceType, text, Collections.<String>emptyList());
	}

	/**
	 * Builds the prefixed embedding text with optional category hints injected
	 * between the structural prefix and the serialized text. Hints come from
	 * OpenMRS concept metadata (currently {@code getSetsContainingConcept}) and
	 * help the embedding model bridge category-name queries (e.g. "vital signs"
	 * → Temperature/BP/Pulse) when the literal category word does not appear
	 * in the serialized record text. Empty hints produce identical output to
	 * the 2-arg overload.
	 *
	 * <p>Example output with hints {@code ["Vital signs"]}:
	 * {@code "Clinical observation: Vital signs / Finding — Temperature: 36.7"}.
	 *
	 * @param resourceType the resource type constant
	 * @param text the serialized record text
	 * @param categoryHints concept-set names (or other category metadata)
	 *        derived from the source domain object; may be empty
	 * @return the prefixed text ready for embedding or keyword scoring
	 */
	public static String buildPrefixedText(String resourceType, String text,
			List<String> categoryHints) {
		return getEmbeddingPrefix(resourceType, text)
				+ injectCategoryHints(text, categoryHints);
	}

	/**
	 * Prepends category hints to the body text without adding a structural
	 * prefix. Used to enrich a record's serialized text so any consumer that
	 * re-prefixes the hint-augmented body gets a consistent string. The 2-arg
	 * {@link #buildPrefixedText(String, String)} called on hint-injected body
	 * produces the same prefixed text as the 3-arg overload called on the
	 * raw body with hints.
	 *
	 * <p>Empty or null hints return the body unchanged.</p>
	 *
	 * @param body the serialized record body (no structural prefix)
	 * @param categoryHints hints to inject
	 * @return body with hints prepended (e.g. "Vital signs / Finding — Temp: 37"),
	 *         or unchanged body if hints are empty
	 */
	public static String injectCategoryHints(String body, List<String> categoryHints) {
		if (categoryHints == null || categoryHints.isEmpty()) {
			return body;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < categoryHints.size(); i++) {
			if (i > 0) {
				sb.append(" / ");
			}
			sb.append(categoryHints.get(i));
		}
		sb.append(" / ").append(body);
		return sb.toString();
	}

	/**
	 * Strips category hint prefixes from text that was enriched by
	 * {@link #injectCategoryHints}. The hint format is
	 * {@code "hint1 / hint2 / ... / originalBody"}. This method finds
	 * the original body by scanning for the first occurrence of a known
	 * record-body pattern (em-dash for Obs, "Condition:", "Diagnosis:",
	 * etc.) and taking the " / " boundary just before it.
	 *
	 * @param text the potentially hint-enriched text
	 * @return the original body without hint prefixes, or the input
	 *         unchanged if no hints are detected
	 */
	public static String stripCategoryHints(String text) {
		if (text == null || !text.contains(" / ")) {
			return text;
		}
		// Find the earliest known record-body pattern
		int earliest = text.length();
		// Obs: "TYPE — CONCEPT:"
		int emDash = text.indexOf(" \u2014 ");
		if (emDash >= 0 && emDash < earliest) {
			earliest = emDash;
		}
		// Condition/Diagnosis/Order/Allergy/Program patterns
		String[] patterns = { "Condition: ", "Diagnosis: ", "Drug order: ",
				"Test order: ", "Referral order: ", "Dispensed: ",
				"Allergy: ", "Program: " };
		for (String p : patterns) {
			int idx = text.indexOf(p);
			if (idx >= 0 && idx < earliest) {
				earliest = idx;
			}
		}
		if (earliest == text.length()) {
			return text; // no known pattern found
		}
		// Find the " / " boundary just before the pattern
		String prefix = text.substring(0, earliest);
		int lastSlash = prefix.lastIndexOf(" / ");
		if (lastSlash >= 0) {
			return text.substring(lastSlash + 3);
		}
		return text;
	}

	/**
	 * Extracts category hints for a concept by looking up the concept sets
	 * (CIEL convention: e.g. concept 1114 "Vital signs" contains Temperature,
	 * BP, Pulse, RR, SpO2). The returned list contains the names of the
	 * containing set concepts and is intended to be passed to the 3-arg
	 * {@link #buildPrefixedText(String, String, List)} so the literal
	 * category word ends up in the embedding input.
	 *
	 * <p>Returns an empty list when the concept is null, has no containing
	 * sets, or when the OpenMRS context is unavailable (e.g. during tests
	 * that bypass Spring). This is intentional — callers should not need to
	 * special-case the no-hints scenario.
	 *
	 * <p>Only concept-set names are used as hints. Concept descriptions are
	 * deliberately excluded — they can restate the concept name with
	 * different vocabulary, creating asymmetric semantic bias between
	 * related concepts (e.g. "Patient's weight in kilograms" has more
	 * overlap with "BMI" than "Patient's height in centimeters", causing
	 * Height to be dropped from BMI queries).
	 *
	 * @param concept the source concept
	 * @return list of containing-set names, or empty list
	 */
	public static List<String> extractCategoryHints(Concept concept) {
		if (concept == null) {
			return Collections.emptyList();
		}
		List<String> hints = new ArrayList<String>();

		// Concept-set membership (e.g. Temperature → "Vital signs")
		try {
			List<ConceptSet> sets = Context.getConceptService()
					.getSetsContainingConcept(concept);
			if (sets != null) {
				for (ConceptSet cs : sets) {
					Concept setConcept = cs.getConceptSet();
					if (setConcept == null || setConcept.getName() == null) {
						continue;
					}
					String name = setConcept.getName().getName();
					if (name != null && !name.trim().isEmpty()) {
						hints.add(name.trim());
					}
				}
			}
		}
		catch (Exception e) {
			// Context unavailable (test bypass) or transient API failure
		}

		return hints;
	}

	/**
	 * Returns the complete set of structural embedding prefixes used by
	 * {@link #getEmbeddingPrefix} across all supported resource types and
	 * sub-types. Used by keyword scoring to identify "type indicator"
	 * query terms — words that appear in any structural prefix and so
	 * should only match the prefix portion of records, not narrative
	 * body text. The set is the static prefix vocabulary, independent
	 * of which resource types appear in any particular dataset.
	 *
	 * @return set of all possible prefix strings (each ends with ": ")
	 */
	public static Set<String> getAllEmbeddingPrefixes() {
		Set<String> prefixes = new java.util.HashSet<String>();
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_OBS, ""));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_CONDITION, ""));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_ALLERGY, ""));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_DIAGNOSIS, ""));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_PROGRAM, ""));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_MEDICATION_DISPENSE, ""));
		// ORDER has sub-type prefixes triggered by body text — enumerate them.
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_ORDER, "Drug order:"));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_ORDER, "Test order:"));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_ORDER, "Referral order:"));
		prefixes.add(getEmbeddingPrefix(RESOURCE_TYPE_ORDER, ""));
		prefixes.remove("");
		return prefixes;
	}

	/**
	 * Resolves a model path relative to the OpenMRS application data directory.
	 * Rejects paths containing ".." to prevent path traversal and verifies the
	 * resolved path stays within the application data directory.
	 *
	 * @param relativePath the relative path from the global property (e.g. "chartsearchai/model.gguf")
	 * @param globalPropertyName the global property name, used in error messages
	 * @return the absolute path to the model file
	 * @throws IllegalStateException if the path is invalid, traverses outside the data directory,
	 *         or the file does not exist
	 */
	public static String resolveModelPath(String relativePath, String globalPropertyName) {
		return ModelFileResolver.resolveModelPath(relativePath, globalPropertyName);
	}

	/**
	 * Computes cosine similarity between two embedding vectors.
	 *
	 * @param a first embedding vector
	 * @param b second embedding vector
	 * @return cosine similarity in [-1, 1], or 0 if either vector is
	 *         null, empty, or the vectors differ in length
	 */
	public static double cosineSimilarity(float[] a, float[] b) {
		if (a == null || b == null || a.length == 0 || b.length == 0
				|| a.length != b.length) {
			return 0;
		}
		double dot = 0, normA = 0, normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		double denom = Math.sqrt(normA) * Math.sqrt(normB);
		return denom == 0 ? 0 : dot / denom;
	}

	/**
	 * @return true when post-answer citation grounding is enabled via
	 *         {@link ChartSearchAiConstants#GP_GROUNDING_ENABLED}.
	 *         Fails safe to {@code false} when no admin service is available.
	 */
	public static boolean isGroundingEnabled() {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENABLED,
							String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ENABLED));
			return "true".equalsIgnoreCase(value.trim());
		}
		catch (RuntimeException e) {
			// No admin service (e.g. context not started) -> treat grounding as
			// off rather than breaking the search path. Grounding is an opt-in
			// annotation; its absence is always safe.
			return false;
		}
	}

	/**
	 * @return true when the Tier-2 entailment confirmation is enabled via
	 *         {@link ChartSearchAiConstants#GP_GROUNDING_ENTAILMENT_ENABLED}.
	 *         Fails safe to {@code false} when no admin service is available.
	 */
	public static boolean isGroundingEntailmentEnabled() {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED,
							String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ENTAILMENT_ENABLED));
			return "true".equalsIgnoreCase(value.trim());
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * @return true when grounding is clause-scoped via
	 *         {@link ChartSearchAiConstants#GP_GROUNDING_CLAUSE_SCOPED} — each citation in a
	 *         multi-citation sentence is checked against its own clause rather than the whole
	 *         sentence. Fails safe to {@code false} (sentence-scoped) when no admin service is
	 *         available.
	 */
	public static boolean isGroundingClauseScoped() {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_CLAUSE_SCOPED,
							String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_CLAUSE_SCOPED));
			return "true".equalsIgnoreCase(value.trim());
		}
		catch (RuntimeException e) {
			return ChartSearchAiConstants.DEFAULT_GROUNDING_CLAUSE_SCOPED;
		}
	}

	/**
	 * @return true when async grounding is enabled via
	 *         {@link ChartSearchAiConstants#GP_GROUNDING_ASYNC} — the streaming endpoint then
	 *         emits {@code done} before the grounding pass and delivers verdicts in a trailing
	 *         {@code grounded} event. Fails safe to {@code false} (classic single grounded
	 *         {@code done}) when no admin service is available.
	 */
	public static boolean isGroundingAsyncEnabled() {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ASYNC,
							String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ASYNC));
			return "true".equalsIgnoreCase(value.trim());
		}
		catch (RuntimeException e) {
			return ChartSearchAiConstants.DEFAULT_GROUNDING_ASYNC;
		}
	}

	/**
	 * @return the grammar-enforced character cap for the chart-answer {@code reasoning}
	 *         scratchpad, from {@link ChartSearchAiConstants#GP_LLM_REASONING_MAX_CHARS};
	 *         {@code 0} = uncapped. Fails safe to {@code 0} (uncapped — today's behavior) on a
	 *         missing admin service, an unparseable value, or a negative value.
	 */
	public static int getReasoningMaxChars() {
		return Math.max(getIntGlobalProperty(ChartSearchAiConstants.GP_LLM_REASONING_MAX_CHARS,
				ChartSearchAiConstants.DEFAULT_LLM_REASONING_MAX_CHARS), 0);
	}

	/**
	 * @return the cosine floor below which a citation is treated as ungrounded,
	 *         read from {@link ChartSearchAiConstants#GP_GROUNDING_MIN_COSINE},
	 *         falling back to {@link ChartSearchAiConstants#DEFAULT_GROUNDING_MIN_COSINE}
	 *         when unset or unparseable
	 */
	public static double getGroundingMinCosine() {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE, "");
			if (value != null && !value.trim().isEmpty()) {
				return Double.parseDouble(value.trim());
			}
		}
		catch (NumberFormatException e) {
			log.warn("Invalid {} value, using default {}",
					ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE,
					ChartSearchAiConstants.DEFAULT_GROUNDING_MIN_COSINE);
		}
		catch (RuntimeException e) {
			// No admin service (e.g. context not started) -> use default.
			log.warn("Could not read {} (admin service unavailable); using default {}",
					ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE,
					ChartSearchAiConstants.DEFAULT_GROUNDING_MIN_COSINE);
		}
		return ChartSearchAiConstants.DEFAULT_GROUNDING_MIN_COSINE;
	}

	/**
	 * Reads a boolean global property, failing safe to {@code defaultValue} when
	 * the value is unset/blank or no admin service is available (e.g. context not
	 * started, or a unit test). Mirrors {@link #isGroundingEnabled()} but
	 * parameterized so the drug-reference feature's several toggles share one
	 * reader instead of copy-pasting the try/catch each time.
	 */
	public static boolean getBooleanGlobalProperty(String property, boolean defaultValue) {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(property, String.valueOf(defaultValue));
			if (value == null || value.trim().isEmpty()) {
				return defaultValue;
			}
			return "true".equalsIgnoreCase(value.trim());
		}
		catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/**
	 * Reads a string global property, failing safe to {@code defaultValue} when the
	 * value is unset/blank or no admin service is available (context not started, or a
	 * unit test). The string counterpart of {@link #getBooleanGlobalProperty}.
	 */
	public static String getStringGlobalProperty(String property, String defaultValue) {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(property, defaultValue);
			return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
		}
		catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/**
	 * Reads an integer global property, failing safe to {@code defaultValue} when the value is
	 * unset/blank/unparseable or no admin service is available. The int counterpart of
	 * {@link #getBooleanGlobalProperty}.
	 */
	public static int getIntGlobalProperty(String property, int defaultValue) {
		try {
			String value = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(property, String.valueOf(defaultValue));
			if (value == null || value.trim().isEmpty()) {
				return defaultValue;
			}
			return Integer.parseInt(value.trim());
		}
		catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/** @return true when {@code value} is null or contains only whitespace — the one blank-string
	 *          predicate shared by the drug-reference parse boundaries and renderers. */
	public static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/** @return the first non-blank of {@code values} (as given, untrimmed), or null when none —
	 *          the one note-or-token / label coalescer shared by the drug-reference renderer and
	 *          validator so blank-vs-null handling cannot drift between them. */
	public static String firstNonBlank(String... values) {
		for (String value : values) {
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	/**
	 * @return true when the DrugReference resource type and the post-answer
	 *         drug-safety validator are enabled (the master switch). Default
	 *         {@code false} — additive and opt-in.
	 */
	public static boolean isDrugReferenceEnabled() {
		return getBooleanGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_ENABLED);
	}

	private ChartSearchAiUtils() {
	}
}
