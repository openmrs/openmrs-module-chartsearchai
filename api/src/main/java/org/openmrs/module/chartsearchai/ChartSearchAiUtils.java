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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiUtils {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiUtils.class);

	/**
	 * Matches an inline {@code [N]} citation marker in LLM answer prose. The
	 * single source of truth for citation-marker parsing, shared by citation
	 * extraction ({@code LlmInferenceService}) and grounding
	 * ({@code CitationGroundingVerifier}) so the two cannot drift apart.
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
	 * Decodes every inline {@code [N]} citation marker in {@code text} to its record index,
	 * in first-appearance order. The shared decode step over {@link #INLINE_CITATION} for
	 * citation extraction ({@code LlmInferenceService}), grounding
	 * ({@code CitationGroundingVerifier}) and safety echo-scoping ({@code DrugSafetyValidator})
	 * so those consumers cannot drift. (The clause-scoped splitter keeps its own matcher — it
	 * needs each marker's text offset, which a set of indexes cannot carry.) Returns an empty
	 * set for null/blank text.
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
	 * injected record are added — three exist already, and they do not all fall on the same side
	 * (see below).
	 *
	 * <p>Two behaviours now hang off this one classification, not just the display grouping: the
	 * demote-only grounding carve-out in {@code CitationGroundingVerifier} is derived from it via
	 * {@link #isGroundingDemoteOnly}. That gate used to test the {@code drug_reference} type directly,
	 * so when {@code safety_finding} arrived (#110) it was classified here and NOT registered there,
	 * and the module's own deterministic findings were graded as retrieved chart evidence — publishing
	 * unstable {@code grounded} verdicts with no error anywhere (issue #122). Deriving both from one
	 * classification is what makes that class of omission unrepresentable, and it is why editing this
	 * method now also changes whether a type's citations can be verified. Both consequences are swept
	 * off one enumeration in {@code ChartSearchAiReferenceGroupTest}.
	 *
	 * <p>The two groups are exhaustive because exactly two code paths mint a
	 * {@code RecordMapping}: {@code PatientChartSerializer}, which passes through whatever
	 * type querystore retrieved, and {@code DrugReferenceInjector}, which writes
	 * {@code drug_reference}, {@code safety_finding} and {@code active_drug_order}. Not everything
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
						? ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE
						: ChartSearchAiConstants.REFERENCE_GROUP_CHART;
	}

	/**
	 * Whether a cited record of {@code resourceType} is DEMOTE-ONLY for citation grounding: its
	 * verdict may render {@code false} (an off-topic citation) or {@code null} (unverified), never
	 * {@code true}, and it never enters — nor consumes the per-answer cap of — the Tier-2 entailment
	 * pass. True exactly for {@link ChartSearchAiConstants#REFERENCE_GROUP_REFERENCE} material: this
	 * is a named view of {@link #referenceGroup}, not a second classification, so there is no list of
	 * type names here to fall out of step with that one.
	 *
	 * <p><strong>This is the grading rule, not the wire.</strong> Since issue #201 the REST layer
	 * publishes no verdict at all for reference material — {@code grounded} serializes as
	 * {@code null} for a {@code reference}-group citation whatever this pass concluded, at every
	 * emission site (see {@code ChartSearchAiRestController.groundedForWire}). The surviving
	 * {@code false} below is therefore module-internal — it is still computed, still returned on
	 * {@code RecordReference.getGrounded()}, and still what keeps these citations out of Tier-2 and
	 * its per-answer cap — but it stops at the module boundary, because its meaning is "off-topic
	 * citation" and reading it as anything else renders the module's own deterministic finding as
	 * unsupported.
	 *
	 * <p><strong>Why module-supplied material cannot be verified.</strong> An answer sentence citing
	 * module-rendered reference prose is typically a recitation of it, and a recitation embeds
	 * near-identically to its source whether or not it swaps subject roles ("erythromycin decreases X"
	 * against the record's "ivosidenib decreases X … including erythromycin"). The same lexical
	 * containment defeats the Tier-2 judge: measured on the live pipeline, 4/4 role-swapped
	 * recitations were judged entailed while the one faithful recitation was judged not (issue #106).
	 * A passing verdict is therefore false assurance. A FAILING verdict still carries information — it
	 * says the citation is not about the record at all — so the flag is kept and only the pass is
	 * withheld. Faithfulness of reference content is checked deterministically by the
	 * {@code DrugSafetyValidator} chips instead.
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
	 * @return true when a grounding pass may only demote this record's citation
	 */
	public static boolean isGroundingDemoteOnly(String resourceType) {
		return ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(referenceGroup(resourceType));
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
