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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-retrieval filtering and concept-rescue helpers. After
 * {@link EmbeddingRankingPipeline} returns the relevant embeddings, this
 * class is responsible for the secondary precision pass: applying recency
 * caps, rescuing same-concept partners, dropping weak-match concept
 * outliers, and grouping the final list by concept for the LLM prompt.
 */
final class ConceptRescueAndFilter {

	private ConceptRescueAndFilter() {
	}

	private static final Logger log = LoggerFactory.getLogger(ConceptRescueAndFilter.class);

	/**
	 * Caps the number of records per concept to {@code maxPerConcept}.
	 * Groups records by their concept key (the text stripped of its
	 * trailing numeric value, e.g. "Clinical observation: Test — Weight
	 * (kg): 94.0" becomes "Clinical observation: Test — Weight (kg)").
	 * Since the input list is already sorted most-recent-first, the first
	 * N records per group are the most recent.
	 *
	 * <p>Records whose text does not end with a numeric value (e.g.
	 * conditions, allergies) are treated as unique groups and always
	 * kept — the recency cap only limits repeated measurements.
	 */
	static List<SerializedRecord> capPerConcept(List<SerializedRecord> records,
			int maxPerConcept) {
		Map<String, Integer> groupCounts = new HashMap<String, Integer>();
		List<SerializedRecord> capped = new ArrayList<SerializedRecord>();
		for (SerializedRecord record : records) {
			String key = conceptKey(record.getText());
			int count = groupCounts.getOrDefault(key, 0);
			if (count < maxPerConcept) {
				capped.add(record);
				groupCounts.put(key, count + 1);
			}
		}
		return capped;
	}

	/**
	 * Filters {@code allRecords} to those whose resource keys are in
	 * {@code relevantKeys}, then applies the recency cap derived from
	 * {@code question}. The input {@code allRecords} list must already
	 * be sorted most-recent-first so that {@link #capPerConcept} keeps
	 * the latest record per concept group.
	 */
	static List<SerializedRecord> filterAndCap(
			List<SerializedRecord> allRecords,
			Set<String> relevantKeys, String question) {
		List<SerializedRecord> filtered = new ArrayList<SerializedRecord>();
		for (SerializedRecord record : allRecords) {
			if (relevantKeys.contains(ChartSearchAiUtils.resourceKey(
					record.getResourceType(), record.getResourceUuid()))) {
				filtered.add(record);
			}
		}
		int recencyCap = QueryPreprocessor.extractRecencyCap(question);
		if (recencyCap > 0) {
			filtered = capPerConcept(filtered, recencyCap);
		}
		return filtered;
	}

	/**
	 * Shared post-retrieval pipeline. Single source of truth for:
	 * filterAndCap, concept-name rescue, post-cap precision filter, and
	 * groupByConcept.
	 *
	 * @param allRecords        all patient records
	 * @param relevantKeys      keys selected by the retrieval stage
	 * @param question          the user's natural-language question
	 * @param pipelineSize      records returned by findSimilar (for
	 *                          detecting recency-cap reduction); pass
	 *                          -1 to skip rescue/post-cap filter
	 * @param keywordMatchCount keyword matches from the pipeline; pass
	 *                          -1 to skip rescue/post-cap filter
	 * @param provider          embedding provider (null skips
	 *                          rescue/filter)
	 * @param queryPrefix       prefix for embedding queries
	 * @return filtered, rescued, and concept-grouped records
	 */
	static List<SerializedRecord> postRetrievalPipeline(
			List<SerializedRecord> allRecords,
			Set<String> relevantKeys,
			String question,
			int pipelineSize,
			int keywordMatchCount,
			EmbeddingProvider provider,
			String queryPrefix) {
		List<SerializedRecord> filtered = filterAndCap(
				allRecords, relevantKeys, question);

		boolean recencyCapReduced = pipelineSize > 0
				&& filtered.size() < pipelineSize / 2;

		// Step 1: Single-record concept-name rescue (e.g. Height for
		// "latest BMI" when only Weight survived the cap).
		if (recencyCapReduced && keywordMatchCount == 0
				&& provider != null && filtered.size() == 1) {
			String normalizedQ = QueryPreprocessor.stripQueryStopwords(question);
			String embQ = QueryPreprocessor.buildEmbeddingQuery(normalizedQ);
			float[] qVec = provider.embedQuery(
					queryPrefix + embQ);
			filtered = conceptNameRescueRecords(filtered,
					allRecords, provider, qVec, question);
		}

		// Step 2: Post-cap concept-name precision filter — remove
		// irrelevant concepts from zero-keyword queries (e.g. SpO2 for
		// "BMI"). For keyword-matching queries, the keyword scoring
		// already provides precision — the multi-concept rescue handles
		// cleanup for comma-list queries.
		if (recencyCapReduced && keywordMatchCount == 0
				&& provider != null
				&& filtered.size() >= 3
				&& filtered.size() <= 10) {
			String normalizedQ = QueryPreprocessor.stripQueryStopwords(question);
			filtered = applyPostCapConceptFilter(filtered,
					provider, normalizedQ, queryPrefix);
		}

		// Step 3: Multi-concept rescue for comma-separated queries.
		// Runs AFTER the precision filter so rescued concepts aren't
		// contaminated by noise that should have been filtered.
		if (provider != null && question.contains(",")
				&& !filtered.isEmpty()) {
			filtered = multiConceptRescue(filtered, allRecords,
					provider, question, queryPrefix);
		}

		// Step 4: Weak-match cleanup for multi-concept queries. When a
		// record's concept name matches fewer query terms than another
		// record matching the same term, it's a weak match.
		if (question.contains(",") && !filtered.isEmpty()) {
			String normalizedQ = QueryPreprocessor.stripQueryStopwords(question);
			String[] qTerms = QueryPreprocessor.extractQueryTerms(normalizedQ);
			if (qTerms.length > 1) {
				filtered = dropWeakMatches(filtered, qTerms);
			}
		}

		return groupByConcept(filtered);
	}

	/**
	 * Groups records by concept key, preserving the original order
	 * within each group. Groups appear in the order their first record
	 * is encountered. For example, interleaved [BP, Weight, BP, Temp,
	 * Weight] becomes [BP, BP, Weight, Weight, Temp]. This helps small
	 * LLMs process multi-concept queries by reducing the need to
	 * mentally sort interleaved records.
	 */
	static List<SerializedRecord> groupByConcept(List<SerializedRecord> records) {
		Map<String, List<SerializedRecord>> groups = new LinkedHashMap<String, List<SerializedRecord>>();
		for (SerializedRecord record : records) {
			String key = conceptKey(record.getText());
			groups.computeIfAbsent(key, k -> new ArrayList<SerializedRecord>()).add(record);
		}
		List<SerializedRecord> result = new ArrayList<SerializedRecord>();
		for (List<SerializedRecord> group : groups.values()) {
			result.addAll(group);
		}
		return result;
	}

	/**
	 * Extracts a concept grouping key from record text by stripping the
	 * date prefix and trailing numeric value with optional unit. If the
	 * text does not end with a numeric value, the full text (minus
	 * date) is returned, making each such record its own group.
	 */
	static String conceptKey(String text) {
		if (text == null) {
			return "";
		}
		// Strip date like "(2025-10-30) " that appears after the type prefix
		String stripped = text.replaceAll("\\(\\d{4}-\\d{2}-\\d{2}\\)\\s*", "");
		// Strip trailing numeric value and optional unit like ": 94.0 kg"
		// or ": 36.7 DEG C" or ": 988.0 cells/mmL"
		return stripped.replaceAll(":\\s*[\\d.]+(?:\\s+\\S+)*\\s*$", "").trim();
	}

	/**
	 * Drops records whose concept name is a weak match for the query.
	 * A weak match is one where the concept name matches fewer query
	 * terms than another record's concept name for the same term. E.g.
	 * "Blood Oxygen Saturation" matches only "blood" (1 term), while
	 * "Systolic Blood Pressure" matches "blood" + "pressure" (2 terms).
	 * SpO2 is weaker and gets dropped.
	 */
	static List<SerializedRecord> dropWeakMatches(
			List<SerializedRecord> records, String[] queryTerms) {
		// Score each unique concept name by how many query terms it matches
		Map<String, Integer> conceptTermCount = new HashMap<>();
		for (SerializedRecord r : records) {
			String cn = ConceptNameUtil.extractConceptName(r.getText());
			if (cn == null) continue;
			if (conceptTermCount.containsKey(cn)) continue;
			String lowerCn = cn.toLowerCase();
			String[] cnWords = lowerCn.split("\\s+");
			int count = 0;
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, lowerCn, cnWords)) {
					count++;
				}
			}
			conceptTermCount.put(cn, count);
		}

		// For each query term, find the max term count among concepts
		// that match it
		Map<String, Integer> termMaxCount = new HashMap<>();
		for (Map.Entry<String, Integer> entry
				: conceptTermCount.entrySet()) {
			String lowerCn = entry.getKey().toLowerCase();
			String[] cnWords = lowerCn.split("\\s+");
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, lowerCn, cnWords)) {
					Integer prev = termMaxCount.get(term);
					if (prev == null || entry.getValue() > prev) {
						termMaxCount.put(term, entry.getValue());
					}
				}
			}
		}

		// Drop records whose concept matches a term but with fewer
		// total term matches than the best record for that term
		List<SerializedRecord> result = new ArrayList<>();
		for (SerializedRecord r : records) {
			String cn = ConceptNameUtil.extractConceptName(r.getText());
			if (cn == null) {
				result.add(r);
				continue;
			}
			Integer myCount = conceptTermCount.get(cn);
			if (myCount == null || myCount == 0) {
				result.add(r);
				continue;
			}
			String lowerCn = cn.toLowerCase();
			String[] cnWords = lowerCn.split("\\s+");
			boolean bestForSomeTerm = false;
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, lowerCn, cnWords)) {
					Integer max = termMaxCount.get(term);
					if (max != null && myCount >= max) {
						bestForSomeTerm = true;
						break;
					}
				}
			}
			if (bestForSomeTerm) {
				result.add(r);
			}
		}

		if (result.size() < records.size()) {
			log.warn("Weak-match cleanup: {} -> {}", records.size(),
					result.size());
		}
		return result.isEmpty() ? records : result;
	}

	/**
	 * Multi-concept rescue for comma-separated queries. When a query
	 * lists concepts like "blood pressure, weight, and temperature" and
	 * some are missing from results, rescues the most recent record for
	 * each missing concept by checking which query terms appear in
	 * concept names of allRecords but not in the results.
	 */
	static List<SerializedRecord> multiConceptRescue(
			List<SerializedRecord> filtered,
			List<SerializedRecord> allRecords,
			EmbeddingProvider provider, String question,
			String queryPrefix) {
		String normalizedQ = QueryPreprocessor.stripQueryStopwords(question);
		String[] queryTerms = QueryPreprocessor.extractQueryTerms(normalizedQ);
		if (queryTerms.length < 3) {
			return filtered;
		}

		// Find which query terms are covered by current results
		Set<String> coveredTerms = new HashSet<String>();
		for (SerializedRecord r : filtered) {
			String text = (r.getText() != null
					? r.getText() : "").toLowerCase();
			String[] textWords = text.split("\\s+");
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, text, textWords)) {
					coveredTerms.add(term);
				}
			}
		}

		// For each uncovered term, check if it appears in a concept
		// name of any record (not just body text). Require 3+ concept
		// name matches to confirm it's a real missing concept.
		List<SerializedRecord> rescued = new ArrayList<>(filtered);
		Set<String> rescuedConcepts = new HashSet<>();
		for (SerializedRecord r : filtered) {
			String cn = ConceptNameUtil.extractConceptName(r.getText());
			if (cn != null) rescuedConcepts.add(cn);
		}

		int recencyCap = QueryPreprocessor.extractRecencyCap(question);

		for (String term : queryTerms) {
			if (coveredTerms.contains(term)) {
				continue;
			}
			List<SerializedRecord> matches = new ArrayList<>();
			for (SerializedRecord r : allRecords) {
				String conceptName =
						ConceptNameUtil.extractConceptName(r.getText());
				if (conceptName == null
						|| rescuedConcepts.contains(conceptName)) {
					continue;
				}
				String lowerName = conceptName.toLowerCase();
				String[] nameWords = lowerName.split("\\s+");
				if (SimilarityAndScoringEngine.termMatchesText(term, lowerName, nameWords)) {
					matches.add(r);
				}
			}
			if (!matches.isEmpty()) {
				String cn = ConceptNameUtil.extractConceptName(
						matches.get(0).getText());
				rescuedConcepts.add(cn);
				// Apply recency cap to just the rescued concept's
				// records before adding.
				if (recencyCap > 0) {
					matches = capPerConcept(matches, recencyCap);
				}
				rescued.addAll(matches);
			}
		}

		if (rescued.size() != filtered.size()) {
			log.warn("Multi-concept rescue: {} -> {} for '{}'",
					filtered.size(), rescued.size(), question);
		}
		return rescued;
	}

	/**
	 * Concept-name rescue for post-cap results. When the recency cap
	 * reduces a set to 1-2 records for a zero-keyword query, related
	 * concepts may have been lost (e.g. "Height" for "BMI" when only
	 * "Weight" survived). Scans all records' concept names, scores them
	 * against the query, and rescues the most recent record for the
	 * single best-scoring missing concept above the data-derived
	 * threshold.
	 */
	static List<SerializedRecord> conceptNameRescueRecords(
			List<SerializedRecord> filtered,
			List<SerializedRecord> allRecords,
			EmbeddingProvider provider, float[] queryVector,
			String question) {
		// Score existing candidates' concept names
		Set<String> existingConcepts = new HashSet<>();
		double minExistingScore = Double.MAX_VALUE;
		for (SerializedRecord r : filtered) {
			String name = ConceptNameUtil.extractConceptName(
					r.getText());
			if (name != null && !existingConcepts.contains(name)) {
				existingConcepts.add(name);
				float[] nameVec = provider.embedQuery(name);
				double score = ChartSearchAiUtils.cosineSimilarity(
						queryVector, nameVec);
				if (score < minExistingScore) {
					minExistingScore = score;
				}
			}
		}

		// Score all unique concept names across allRecords
		Map<String, Double> conceptScores = new HashMap<>();
		Map<String, SerializedRecord> mostRecent = new HashMap<>();
		for (SerializedRecord r : allRecords) {
			String name = ConceptNameUtil.extractConceptName(
					r.getText());
			if (name == null || existingConcepts.contains(name)) {
				continue;
			}
			if (!conceptScores.containsKey(name)) {
				float[] nameVec = provider.embedQuery(name);
				conceptScores.put(name,
						ChartSearchAiUtils.cosineSimilarity(
								queryVector, nameVec));
			}
			// allRecords is most-recent-first, so first occurrence is
			// the most recent record for each concept.
			if (!mostRecent.containsKey(name)) {
				mostRecent.put(name, r);
			}
		}

		// Rescue concepts scoring within the top quartile of ALL
		// dataset concept-name scores.
		double[] allScores = new double[conceptScores.size()
				+ existingConcepts.size()];
		int ai = 0;
		for (double s : conceptScores.values()) {
			allScores[ai++] = s;
		}
		for (SerializedRecord r : filtered) {
			String name = ConceptNameUtil.extractConceptName(
					r.getText());
			if (name != null) {
				float[] nameVec = provider.embedQuery(name);
				allScores[ai++] = ChartSearchAiUtils.cosineSimilarity(
						queryVector, nameVec);
			}
		}
		if (ai < allScores.length) {
			allScores = Arrays.copyOf(allScores, ai);
		}
		Arrays.sort(allScores);
		int q3Start = allScores.length * 3 / 4;
		double q3Sum = 0;
		for (int i = q3Start; i < allScores.length; i++) {
			q3Sum += allScores[i];
		}
		double rescueThreshold = q3Sum / (allScores.length - q3Start);

		// Find the single best-scoring non-existing concept above the
		// rescue threshold. Limiting to 1 prevents over-rescuing.
		String bestRescueName = null;
		double bestRescueScore = 0;
		for (Map.Entry<String, Double> entry
				: conceptScores.entrySet()) {
			if (entry.getValue() >= rescueThreshold
					&& entry.getValue() > bestRescueScore) {
				bestRescueScore = entry.getValue();
				bestRescueName = entry.getKey();
			}
		}

		int recencyCap = QueryPreprocessor.extractRecencyCap(question);
		List<SerializedRecord> rescued = new ArrayList<>(filtered);
		List<String> rescuedNames = new ArrayList<>();
		if (bestRescueName != null) {
			SerializedRecord r = mostRecent.get(bestRescueName);
			if (r != null) {
				rescued.add(r);
				rescuedNames.add(bestRescueName);
			}
		}

		// Apply recency cap to rescued records too
		if (recencyCap > 0 && rescued.size() > filtered.size()) {
			rescued = capPerConcept(rescued, recencyCap);
		}

		if (rescued.size() > filtered.size()) {
			log.warn("Concept-name rescue: {} -> {} (threshold={}, "
					+ "rescued: {})",
					filtered.size(), rescued.size(),
					String.format("%.4f", rescueThreshold),
					rescuedNames);
		}
		return rescued;
	}

	/**
	 * Post-cap concept-name precision filter for recency-capped results.
	 * When a recency cap drastically reduces the set, irrelevant concepts
	 * from the same time period can survive. Scores each unique concept
	 * name against the query and drops concepts below candidate mean -
	 * 0.5 std.
	 */
	static List<SerializedRecord> applyPostCapConceptFilter(
			List<SerializedRecord> records,
			EmbeddingProvider provider, String normalizedQuery,
			String queryPrefix) {
		Map<String, List<SerializedRecord>> byConcept =
				new LinkedHashMap<String, List<SerializedRecord>>();
		for (SerializedRecord r : records) {
			String name = ConceptNameUtil.extractConceptName(r.getText());
			if (name == null) {
				name = r.getResourceType() + ":" + r.getResourceUuid();
			}
			byConcept.computeIfAbsent(name,
					k -> new ArrayList<SerializedRecord>()).add(r);
		}
		if (byConcept.size() < 2) {
			return records;
		}

		String embeddingQuery = QueryPreprocessor.buildEmbeddingQuery(normalizedQuery);
		float[] queryVector = provider.embedQuery(
				queryPrefix + embeddingQuery);
		double[] candScores = new double[byConcept.size()];
		String[] candNames = new String[byConcept.size()];
		int ci = 0;
		for (String name : byConcept.keySet()) {
			float[] nameVec = provider.embedQuery(name);
			candScores[ci] = ChartSearchAiUtils.cosineSimilarity(
					queryVector, nameVec);
			candNames[ci] = name;
			ci++;
		}

		double sum = 0;
		for (double s : candScores) sum += s;
		double mean = sum / candScores.length;
		double sqSum = 0;
		for (double s : candScores) {
			sqSum += (s - mean) * (s - mean);
		}
		double std = Math.sqrt(sqSum / candScores.length);
		if (std < 1e-9) {
			return records;
		}
		// Use mean - 0.5*std: tighter than mean - std but not as
		// aggressive as mean.
		double threshold = mean - 0.5 * std;

		List<SerializedRecord> result = new ArrayList<>();
		for (int i = 0; i < candScores.length; i++) {
			if (candScores[i] >= threshold) {
				result.addAll(byConcept.get(candNames[i]));
			}
		}
		if (result.size() < records.size() && !result.isEmpty()) {
			log.warn("Post-cap concept filter: {} -> {} (threshold={})",
					records.size(), result.size(),
					String.format("%.4f", threshold));
			return result;
		}
		return records;
	}

}
