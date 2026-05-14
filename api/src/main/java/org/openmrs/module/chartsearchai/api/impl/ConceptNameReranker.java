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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mid-retrieval re-ranker for zero-keyword queries: compares each
 * candidate's concept name against the query vector at the concept-name
 * level (not the full record-text level), and applies a full-distribution
 * z-score outlier gate plus a "within 1 std of the mean" filter.
 *
 * <p>Operates on {@link ChartEmbedding} from the filter pipeline, before
 * results become {@link org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord}s
 * for the LLM prompt — distinct from the post-retrieval, SerializedRecord-
 * level filtering in {@link ConceptRescueAndFilter}.</p>
 */
final class ConceptNameReranker {

	private ConceptNameReranker() {
	}

	private static final Logger log = LoggerFactory.getLogger(ConceptNameReranker.class);

	/**
	 * Re-ranks results by concept-name similarity to the query. For each
	 * unique concept in the result set, embeds the concept name with
	 * the query encoder and scores it against the query vector. Drops
	 * concepts whose name-level similarity falls below the median,
	 * keeping only the most query-relevant concepts.
	 */
	static List<ChartEmbedding> rerankByConceptName(
			List<ChartEmbedding> candidates,
			ChartEmbedding[] allEmbeddings, int allCount,
			float[] queryVector, EmbeddingProvider provider,
			ModelNoiseProfile noiseProfile,
			PipelineConfig config) {
		return rerankByConceptName(candidates, allEmbeddings, allCount,
				queryVector, provider, noiseProfile, config, false);
	}

	/**
	 * @param forceGate when true, skip the small-result bypass so the
	 *        z-score outlier gate always runs. Used when type indicators
	 *        are present but zero pipeline results match the indicated
	 *        type — the z-score gate uses ALL concept names (not just
	 *        candidates) so it is statistically valid even for small
	 *        sets.
	 */
	static List<ChartEmbedding> rerankByConceptName(
			List<ChartEmbedding> candidates,
			ChartEmbedding[] allEmbeddings, int allCount,
			float[] queryVector, EmbeddingProvider provider,
			ModelNoiseProfile noiseProfile,
			PipelineConfig config, boolean forceGate) {
		// Group candidates by concept name
		Map<String, List<ChartEmbedding>> byConcept =
				new LinkedHashMap<String, List<ChartEmbedding>>();
		for (ChartEmbedding ce : candidates) {
			String name = ConceptNameUtil.extractConceptName(
					ce.getTextContent());
			if (name == null) {
				name = ce.getResourceType() + ":" + ce.getResourceUuid();
			}
			byConcept.computeIfAbsent(name,
					k -> new ArrayList<ChartEmbedding>()).add(ce);
		}

		// Collect ALL unique concept names in the dataset
		Set<String> allConceptNames =
				new LinkedHashSet<String>();
		for (int i = 0; i < allCount; i++) {
			String name = ConceptNameUtil.extractConceptName(
					allEmbeddings[i].getTextContent());
			if (name != null) {
				allConceptNames.add(name);
			}
		}
		allConceptNames.addAll(byConcept.keySet());

		// Score ALL concept names against the query
		Map<String, Double> conceptScores =
				new HashMap<String, Double>();
		for (String name : allConceptNames) {
			float[] nameVec = provider.embedQuery(name);
			conceptScores.put(name,
					ChartSearchAiUtils.cosineSimilarity(
							queryVector, nameVec));
		}

		// Small-result bypass: skip the gate when the result set is
		// below the model's configured minimum. Bypassed when forceGate
		// is true.
		if (!forceGate
				&& (candidates.size()
						< config.conceptNameGateMinCandidates
				|| byConcept.size()
						< config.conceptNameGateMinCandidates)) {
			return candidates;
		}

		// Full-distribution concept-name outlier gate: check if any
		// CANDIDATE concept name is a statistical outlier among ALL
		// concept names in the dataset. Uses N^(3/4) as effective
		// degrees of freedom.
		if (allConceptNames.size() >= 5) {
			double[] cnScores = new double[conceptScores.size()];
			int ci = 0;
			for (double s : conceptScores.values()) {
				cnScores[ci++] = s;
			}
			double cnSum = 0;
			for (double s : cnScores) cnSum += s;
			double cnMean = cnSum / cnScores.length;
			double cnSqSum = 0;
			for (double s : cnScores) {
				cnSqSum += (s - cnMean) * (s - cnMean);
			}
			double cnStd = Math.sqrt(cnSqSum / cnScores.length);
			if (cnStd > 1e-9) {
				double maxCandidateScore = 0;
				for (String name : byConcept.keySet()) {
					Double s = conceptScores.get(name);
					if (s != null && s > maxCandidateScore) {
						maxCandidateScore = s;
					}
				}
				double candidateZ = (maxCandidateScore - cnMean)
						/ cnStd;
				double effectiveN = Math.pow(cnScores.length,
					0.75);
				double zThreshold = Math.sqrt(
						2 * Math.log(Math.max(2, effectiveN)));
				if (candidateZ < zThreshold) {
					log.warn("Concept-name outlier gate: z={}"
							+ " < threshold={} (best={}, N={})",
							String.format("%.2f", candidateZ),
							String.format("%.2f", zThreshold),
							String.format("%.4f",
									maxCandidateScore),
							cnScores.length);
					return Collections.emptyList();
				}
			}
		}

		// Compute mean and std of concept-name scores, then keep
		// concepts within 1 std of the max. This adapts to each query's
		// score distribution without a fixed threshold.
		double[] scores = new double[conceptScores.size()];
		int idx = 0;
		for (double s : conceptScores.values()) {
			scores[idx++] = s;
		}
		double sum = 0;
		for (double s : scores) sum += s;
		double mean = sum / scores.length;
		double sqSum = 0;
		for (double s : scores) sqSum += (s - mean) * (s - mean);
		double std = Math.sqrt(sqSum / scores.length);

		if (std < 1e-9) {
			return candidates;
		}

		// Drop only outliers: concepts scoring below mean - std.
		double threshold = mean - std;

		List<ChartEmbedding> reranked = new ArrayList<ChartEmbedding>();
		for (Map.Entry<String, List<ChartEmbedding>> entry
				: byConcept.entrySet()) {
			if (conceptScores.get(entry.getKey()) >= threshold) {
				reranked.addAll(entry.getValue());
			}
		}

		// If nothing was dropped, return original
		if (reranked.size() == candidates.size()) {
			return candidates;
		}

		log.warn("Concept-name re-ranking: {} concepts, mean={}, "
				+ "std={}, threshold={}, kept={} of {} records",
				byConcept.size(),
				String.format("%.4f", mean),
				String.format("%.4f", std),
				String.format("%.4f", threshold),
				reranked.size(), candidates.size());

		return reranked.isEmpty() ? candidates : reranked;
	}
}
