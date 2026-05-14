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

import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.COHERENCE_ADAPTIVE_GAP_RATIO;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.COHERENCE_REFERENCE_N;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.COHERENCE_SAME_TOPIC_FLOOR;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.PIPELINE_HYBRID;
import static org.openmrs.module.chartsearchai.ChartSearchAiConstants.PIPELINE_LUCENE;
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

import org.openmrs.Concept;
import org.openmrs.ConceptSet;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiUtils {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiUtils.class);

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
	 * prefix. Used to enrich {@code ChartEmbedding.textContent} so downstream
	 * consumers (keyword scoring, concept-name extraction) see the same
	 * hint-augmented text that was used for embedding. The 2-arg
	 * {@link #buildPrefixedText(String, String)} called on hint-injected body
	 * produces the same prefixed text as the 3-arg overload called on the
	 * raw body with hints — so embeddings and keyword text stay consistent.
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
	 * <p>Used by {@link org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile}
	 * to compute stable noise statistics unaffected by enrichment.</p>
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
	 * Computes a dynamic similarity floor using z-score gating. Returns
	 * the higher of {@code absoluteFloor} and {@code mean + zThreshold * stddev}.
	 *
	 * @param scores all scores in the distribution
	 * @param absoluteFloor minimum floor regardless of distribution
	 * @param zThreshold number of standard deviations above mean
	 * @return the effective minimum score threshold
	 */
	public static double zScoreFloor(double[] scores, double absoluteFloor,
			double zThreshold) {
		if (scores.length == 0) {
			return absoluteFloor;
		}
		double sum = 0;
		for (double s : scores) {
			sum += s;
		}
		double mean = sum / scores.length;
		double variance = 0;
		for (double s : scores) {
			variance += (s - mean) * (s - mean);
		}
		double stddev = Math.sqrt(variance / scores.length);
		double zFloor = mean + zThreshold * stddev;
		return Math.max(absoluteFloor, zFloor);
	}

	/**
	 * Filters a set of embedding vectors by pairwise coherence, removing
	 * topic outliers. Returns a boolean array where {@code true} means
	 * the vector at that index should be kept.
	 *
	 * <p>Computes average pairwise cosine similarity for each vector against
	 * all others. Detects a gap in the coherence distribution using the
	 * same gap-multiplier approach as the embedding pipeline's coherence
	 * filter: a gap must exceed both {@code COHERENCE_GAP_MULTIPLIER} times
	 * the running average gap AND an adaptive minimum gap proportional to
	 * the coherence range. Vectors below the gap are marked for removal.
	 *
	 * @param vectors the embedding vectors to filter
	 * @return boolean array indicating which vectors to keep
	 */
	public static boolean[] filterByCoherence(float[][] vectors) {
		int n = vectors.length;
		boolean[] keep = new boolean[n];
		if (n < 3) {
			java.util.Arrays.fill(keep, true);
			return keep;
		}

		// Compute average coherence per vector without allocating O(n^2)
		// matrix -- accumulate pairwise sums directly into per-vector totals.
		double[] coherence = new double[n];
		double[] pairSum = new double[n];
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				double sim = cosineSimilarity(vectors[i], vectors[j]);
				pairSum[i] += sim;
				pairSum[j] += sim;
			}
		}
		for (int i = 0; i < n; i++) {
			coherence[i] = pairSum[i] / (n - 1);
		}

		// Sort by coherence descending
		Integer[] sortedIdx = new Integer[n];
		for (int i = 0; i < n; i++) {
			sortedIdx[i] = i;
		}
		java.util.Arrays.sort(sortedIdx, new java.util.Comparator<Integer>() {
			@Override
			public int compare(Integer a, Integer b) {
				return Double.compare(coherence[b], coherence[a]);
			}
		});

		// Gap detection
		double maxCoherence = coherence[sortedIdx[0]];

		// Gap multiplier: Gumbel extreme value theory — the
		// coherence filter has n*(n-1)/2 pairwise comparisons
		// feeding each average. Using sqrt(2*ln(nPairs)) as the
		// gap multiplier means we only trigger on gaps that would
		// be extreme across that many comparisons.
		double nPairs = n * (n - 1.0) / 2.0;
		double gapMultiplier = Math.sqrt(
				2.0 * Math.log(Math.max(2.0, nPairs)));

		double minCoherence = coherence[sortedIdx[n - 1]];
		double coherenceRange = maxCoherence - minCoherence;
		int refPairs = COHERENCE_REFERENCE_N - 1;
		double scaleFactor = Math.max(1.0,
				Math.sqrt((double) refPairs / (n - 1)));
		double base = n <= COHERENCE_REFERENCE_N
				? coherenceRange : maxCoherence;
		double coherenceMinGap = base
				* COHERENCE_ADAPTIVE_GAP_RATIO * scaleFactor;

		int keepCount = n;
		double gapSum = 0;
		for (int i = 1; i < n; i++) {
			double gap = coherence[sortedIdx[i - 1]] - coherence[sortedIdx[i]];
			if (i >= ADAPTIVE_MIN_RECORDS && i >= 2) {
				double avgGap = gapSum / (i - 1);
				if (gap > avgGap * gapMultiplier
						&& gap > coherenceMinGap) {
					keepCount = i;
					break;
				}
			}
			gapSum += gap;
		}

		// Same-topic floor check
		if (keepCount < n) {
			double highestRemovedCoherence = coherence[sortedIdx[keepCount]];
			if (highestRemovedCoherence >= COHERENCE_SAME_TOPIC_FLOOR) {
				keepCount = n;
			}
		}

		java.util.Arrays.fill(keep, false);
		for (int i = 0; i < keepCount; i++) {
			keep[sortedIdx[i]] = true;
		}
		return keep;
	}

	/**
	 * Returns true if the given pipeline value uses a Lucene index
	 * (either the pure Lucene pipeline or the hybrid pipeline that
	 * combines Lucene BM25 with embedding kNN).
	 */
	public static boolean usesLuceneIndex(String pipeline) {
		if (pipeline == null) {
			return false;
		}
		String trimmed = pipeline.trim();
		return PIPELINE_LUCENE.equalsIgnoreCase(trimmed)
				|| PIPELINE_HYBRID.equalsIgnoreCase(trimmed);
	}

	private ChartSearchAiUtils() {
	}
}
