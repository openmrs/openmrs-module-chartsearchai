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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coherence and clustering primitives consumed by the filter pipeline and
 * its gates. Each method is a leaf operation on a candidate list: cluster
 * growth, gap-coherence checks, coherence-based outlier filtering,
 * below-floor rescue, unique-coverage preservation, concept-pair pruning,
 * and keyword-tier redundancy filtering.
 *
 * <p>None of these methods call each other — they're independent
 * primitives. Callers (mainly {@link EmbeddingRankingPipeline} and
 * {@link RankingPipelineGates}) compose them into the larger pipeline.
 */
final class CoherenceFilters {

	private CoherenceFilters() {
	}

	private static final Logger log = LoggerFactory.getLogger(CoherenceFilters.class);

	/**
	 * Grows a cluster starting from the initial {@code seedSize} records by
	 * adding candidates whose embedding has cosine similarity &ge; threshold
	 * with at least one existing cluster member. Iterates until no more
	 * records can be added (fixed-point). Records are tested in score order
	 * (highest first) so higher-scoring candidates are prioritized.
	 *
	 * @param candidates all candidates (sorted by combined score descending)
	 * @param seedSize number of initial cluster records (the top seedSize)
	 * @param cosineThreshold minimum cosine similarity to join the cluster
	 * @return the grown cluster, preserving the original score order
	 */
	static List<ScoredEmbedding> growCluster(List<ScoredEmbedding> candidates,
			int seedSize, double cosineThreshold) {
		if (seedSize >= candidates.size()) {
			return candidates;
		}
		boolean[] inCluster = new boolean[candidates.size()];
		float[][] vectors = new float[candidates.size()][];
		for (int i = 0; i < candidates.size(); i++) {
			vectors[i] = candidates.get(i).embedding.getEmbeddingVector();
			inCluster[i] = i < seedSize;
		}

		// Iteratively add candidates that are coherent with the cluster
		boolean added = true;
		while (added) {
			added = false;
			for (int i = seedSize; i < candidates.size(); i++) {
				if (inCluster[i] || vectors[i] == null) {
					continue;
				}
				for (int j = 0; j < candidates.size(); j++) {
					if (!inCluster[j] || vectors[j] == null
							|| vectors[j].length != vectors[i].length) {
						continue;
					}
					if (ChartSearchAiUtils.cosineSimilarity(vectors[i], vectors[j]) >= cosineThreshold) {
						inCluster[i] = true;
						added = true;
						break;
					}
				}
			}
		}

		List<ScoredEmbedding> result = new ArrayList<ScoredEmbedding>();
		for (int i = 0; i < candidates.size(); i++) {
			if (inCluster[i]) {
				result.add(candidates.get(i));
			}
		}
		return result;
	}

	/**
	 * Validates whether a gap in the score distribution at the given cutoff
	 * position is intra-topic (both sides belong to the same broad topic)
	 * by computing the average cosine similarity between records just above
	 * and just below the gap boundary. Uses a small window (up to 3
	 * records on each side) to get a robust estimate that doesn't depend
	 * on a single pair.
	 *
	 * <p>When the average cross-boundary cosine meets or exceeds the
	 * threshold, the gap is intra-topic and should NOT be used as a cutoff.
	 *
	 * @param scored the sorted candidate list
	 * @param cutoff the gap position (records 0..cutoff-1 above, cutoff+ below)
	 * @param cosineThreshold minimum average cosine for the gap to be intra-topic
	 * @return true if the gap is intra-topic (should NOT cut here)
	 */
	static boolean isGapCoherent(List<ScoredEmbedding> scored, int cutoff,
			double cosineThreshold) {
		if (cutoff <= 0 || cutoff >= scored.size()) {
			return false;
		}

		// Use a small window on each side of the gap for robustness
		int windowSize = Math.min(3, Math.min(cutoff, scored.size() - cutoff));
		double sumCosine = 0;
		int pairs = 0;

		for (int a = cutoff - windowSize; a < cutoff; a++) {
			float[] vecA = scored.get(a).embedding.getEmbeddingVector();
			if (vecA == null) {
				continue;
			}
			for (int b = cutoff; b < cutoff + windowSize; b++) {
				float[] vecB = scored.get(b).embedding.getEmbeddingVector();
				if (vecB == null || vecB.length != vecA.length) {
					continue;
				}
				sumCosine += ChartSearchAiUtils.cosineSimilarity(vecA, vecB);
				pairs++;
			}
		}

		if (pairs == 0) {
			return false;
		}

		double avgCosine = sumCosine / pairs;
		return avgCosine >= cosineThreshold;
	}

	/**
	 * Filters the final candidate set by inter-candidate coherence to
	 * remove "topic outliers" — records that scored similarly to the query
	 * by coincidence but are conceptually unrelated to the other results.
	 * Delegates the per-vector coherence math to
	 * {@link ChartSearchAiUtils#filterByCoherence(float[][])}.
	 *
	 * @param candidates the post-filtered candidate set
	 * @return the coherence-filtered subset (preserving original order)
	 */
	static List<ScoredEmbedding> filterByCoherence(List<ScoredEmbedding> candidates) {
		int n = candidates.size();

		float[][] vectors = new float[n][];
		for (int i = 0; i < n; i++) {
			byte[] raw = candidates.get(i).embedding.getEmbedding();
			if (raw == null || raw.length == 0) {
				return candidates;
			}
			vectors[i] = candidates.get(i).embedding.getEmbeddingVector();
		}

		boolean[] keep = ChartSearchAiUtils.filterByCoherence(vectors);

		int keepCount = 0;
		for (boolean k : keep) {
			if (k) keepCount++;
		}
		if (keepCount == n) {
			return candidates;
		}

		log.debug("Coherence filter removed {} outlier(s)", n - keepCount);

		List<ScoredEmbedding> filtered = new ArrayList<ScoredEmbedding>();
		for (int i = 0; i < n; i++) {
			if (keep[i]) {
				filtered.add(candidates.get(i));
			}
		}
		return filtered;
	}

	/**
	 * Checks whether a small set of candidates (n &le; 3) are mutually
	 * coherent by computing mean pairwise cosine similarity. If the mean
	 * is below the gap validation threshold, all candidates are rejected
	 * as false positives — they are unrelated topics that individually
	 * scored well against the query but are not coherent as a group.
	 *
	 * <p>This complements {@link #filterByCoherence}, which uses gap
	 * detection and cannot remove candidates when coherence values are
	 * uniformly low (stddev ≈ 0).
	 */
	static List<ScoredEmbedding> filterByMeanCoherence(
			List<ScoredEmbedding> candidates, PipelineConfig config) {
		boolean allHaveEmbeddings = true;
		for (ScoredEmbedding se : candidates) {
			byte[] raw = se.embedding.getEmbedding();
			if (raw == null || raw.length == 0) {
				allHaveEmbeddings = false;
				break;
			}
		}
		if (!allHaveEmbeddings) {
			return candidates;
		}

		double sumCosine = 0;
		int pairCount = 0;
		for (int i = 0; i < candidates.size(); i++) {
			for (int j = i + 1; j < candidates.size(); j++) {
				sumCosine += ChartSearchAiUtils.cosineSimilarity(
						candidates.get(i).embedding.getEmbeddingVector(),
						candidates.get(j).embedding.getEmbeddingVector());
				pairCount++;
			}
		}
		double meanCoherence = sumCosine / pairCount;
		if (meanCoherence < config.gapValidationCosineThreshold) {
			log.debug("{} candidates are not mutually coherent "
					+ "(meanCosine={}, threshold={}), returning empty",
					candidates.size(),
					String.format("%.4f", meanCoherence),
					config.gapValidationCosineThreshold);
			return Collections.emptyList();
		}
		return candidates;
	}

	/**
	 * Rescues records that scored below the similarity floor but are
	 * coherent with the surviving candidate cluster. Computes each
	 * below-floor record's average cosine similarity to the cluster
	 * members and includes it if that coherence is at least as high as
	 * the lowest coherence within the cluster itself.
	 *
	 * @param candidates the post-coherence-filtered cluster
	 * @param scored all scored records (including below-floor ones)
	 * @param adaptiveCutoff the first-pass cutoff (records above the floor)
	 * @return the original candidates plus any rescued below-floor records
	 */
	static List<ScoredEmbedding> rescueBelowFloor(List<ScoredEmbedding> candidates,
			List<ScoredEmbedding> scored, int adaptiveCutoff) {
		if (adaptiveCutoff >= scored.size()) {
			return candidates;
		}

		float[][] clusterVecs = new float[candidates.size()][];
		boolean allValid = true;
		for (int i = 0; i < candidates.size(); i++) {
			byte[] raw = candidates.get(i).embedding.getEmbedding();
			if (raw == null || raw.length == 0) {
				allValid = false;
				break;
			}
			clusterVecs[i] = candidates.get(i).embedding.getEmbeddingVector();
		}
		if (!allValid) {
			return candidates;
		}

		// Compute the minimum within-cluster coherence as the rescue threshold.
		// A below-floor record must be at least as coherent with the cluster
		// as the least coherent existing member.
		double minClusterCoherence = Double.MAX_VALUE;
		for (int i = 0; i < clusterVecs.length; i++) {
			double sum = 0;
			for (int j = 0; j < clusterVecs.length; j++) {
				if (j != i) {
					sum += ChartSearchAiUtils.cosineSimilarity(clusterVecs[i], clusterVecs[j]);
				}
			}
			double coherence = sum / (clusterVecs.length - 1);
			if (coherence < minClusterCoherence) {
				minClusterCoherence = coherence;
			}
		}

		Set<Integer> inCluster = new HashSet<Integer>();
		for (ScoredEmbedding se : candidates) {
			inCluster.add(se.embedding.getEmbeddingId());
		}

		List<ScoredEmbedding> rescued = new ArrayList<ScoredEmbedding>();
		for (int i = adaptiveCutoff; i < scored.size(); i++) {
			ScoredEmbedding se = scored.get(i);
			if (inCluster.contains(se.embedding.getEmbeddingId())) {
				continue;
			}
			byte[] raw = se.embedding.getEmbedding();
			if (raw == null || raw.length == 0) {
				continue;
			}
			float[] vec = se.embedding.getEmbeddingVector();
			double sum = 0;
			for (float[] cv : clusterVecs) {
				sum += ChartSearchAiUtils.cosineSimilarity(vec, cv);
			}
			double coherence = sum / clusterVecs.length;
			if (coherence >= minClusterCoherence) {
				rescued.add(se);
			}
		}

		if (rescued.isEmpty()) {
			return candidates;
		}

		log.debug("Below-floor rescue: recovered {} records "
				+ "(minClusterCoherence={})",
				rescued.size(), String.format("%.4f", minClusterCoherence));
		List<ScoredEmbedding> result = new ArrayList<ScoredEmbedding>(candidates);
		result.addAll(rescued);
		return result;
	}

	/**
	 * After Phase 1's coherence filter runs, restore any candidates whose
	 * keyword evidence covers a query term NOT matched by the survivors.
	 * Coherence filtering uses only embedding geometry and can drop
	 * legitimate cross-concept matches in multi-concept queries (e.g.
	 * Weight records in "blood pressure and weight" — Weight is less
	 * coherent with the BP cluster but matches "weight" which BP doesn't
	 * cover). Records whose keyword matches duplicate the survivors'
	 * coverage remain filtered.
	 */
	static List<ScoredEmbedding> preserveUniqueCoverage(
			List<ScoredEmbedding> original,
			List<ScoredEmbedding> filtered, String[] queryTerms,
			double bonusThreshold) {
		if (queryTerms == null || queryTerms.length == 0) {
			return filtered;
		}
		Set<String> filteredIds = new HashSet<String>();
		Set<String> coveredTerms = new HashSet<String>();
		for (ScoredEmbedding se : filtered) {
			filteredIds.add(se.embedding.getResourceUuid());
			String text = ChartSearchAiUtils.buildPrefixedText(
					se.embedding.getResourceType(),
					ConceptNameUtil.stripSynonyms(
							se.embedding.getTextContent()))
					.toLowerCase();
			String[] words = text.split("\\s+");
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, text, words)) {
					coveredTerms.add(term);
				}
			}
		}
		List<ScoredEmbedding> result =
				new ArrayList<ScoredEmbedding>(filtered);
		Set<String> restoredIds = new HashSet<String>(filteredIds);
		// Pass 1: restore records that cover query terms NOT already
		// covered by coherence survivors.
		for (ScoredEmbedding se : original) {
			if (se.keywordScore <= 0
					|| restoredIds.contains(
							se.embedding.getResourceUuid())) {
				continue;
			}
			String text = ChartSearchAiUtils.buildPrefixedText(
					se.embedding.getResourceType(),
					ConceptNameUtil.stripSynonyms(
							se.embedding.getTextContent()))
					.toLowerCase();
			String[] words = text.split("\\s+");
			for (String term : queryTerms) {
				if (!coveredTerms.contains(term)
						&& SimilarityAndScoringEngine.termMatchesText(term, text, words)) {
					result.add(se);
					restoredIds.add(se.embedding.getResourceUuid());
					break;
				}
			}
		}
		// Pass 2: restore records with strong keyword relevance even
		// when their matched terms overlap with survivors. The bonus
		// threshold (min(2,N)/N query terms) ensures only records with
		// strong keyword evidence are restored.
		for (ScoredEmbedding se : original) {
			if (restoredIds.contains(se.embedding.getResourceUuid())) {
				continue;
			}
			if (se.keywordScore >= bonusThreshold) {
				result.add(se);
				restoredIds.add(se.embedding.getResourceUuid());
			}
		}
		return result;
	}

	/**
	 * Removes concept-pair outliers from a semantic core by comparing
	 * each concept's average inter-concept cosine to the patient's
	 * cross-concept noise baseline. A concept whose mean cosine to
	 * non-pair core members is at or below {@code noiseProfile.noiseMean}
	 * is, by construction, statistically indistinguishable from a
	 * random unrelated pair.
	 *
	 * <p>Only fires when there are &ge; 3 distinct concepts in the core
	 * and &ge; 2 of them have &ge; 2 members.
	 */
	static List<ScoredEmbedding> pruneCoherenceOutlierConcepts(
			List<ScoredEmbedding> core,
			ModelNoiseProfile noiseProfile) {
		if (core.size() < 4) {
			return core;
		}
		Map<String, List<ScoredEmbedding>> byConcept =
				new LinkedHashMap<String, List<ScoredEmbedding>>();
		for (ScoredEmbedding se : core) {
			String cn = ConceptNameUtil.extractConceptName(
					se.embedding.getTextContent());
			if (cn == null) {
				cn = "__unnamed_" + se.embedding.getResourceUuid();
			}
			List<ScoredEmbedding> list = byConcept.get(cn);
			if (list == null) {
				list = new ArrayList<ScoredEmbedding>();
				byConcept.put(cn, list);
			}
			list.add(se);
		}
		if (byConcept.size() < 3) {
			return core;
		}
		int multiMember = 0;
		for (List<ScoredEmbedding> g : byConcept.values()) {
			if (g.size() >= 2) {
				multiMember++;
			}
		}
		if (multiMember < 2) {
			return core;
		}
		Map<String, Double> conceptInterAvg =
				new LinkedHashMap<String, Double>();
		for (Map.Entry<String, List<ScoredEmbedding>> e : byConcept.entrySet()) {
			String concept = e.getKey();
			List<ScoredEmbedding> group = e.getValue();
			double sum = 0;
			int count = 0;
			for (ScoredEmbedding member : group) {
				float[] mv = member.embedding.getEmbeddingVector();
				if (mv == null) {
					continue;
				}
				for (ScoredEmbedding other : core) {
					String ocn = ConceptNameUtil.extractConceptName(
							other.embedding.getTextContent());
					if (ocn != null && ocn.equals(concept)) {
						continue;
					}
					float[] ov = other.embedding.getEmbeddingVector();
					if (ov == null) {
						continue;
					}
					sum += ChartSearchAiUtils.cosineSimilarity(mv, ov);
					count++;
				}
			}
			if (count > 0) {
				conceptInterAvg.put(concept, sum / count);
			}
		}
		if (conceptInterAvg.size() < 3) {
			return core;
		}
		double cutoff = noiseProfile.noiseMean;
		Set<String> drop = new HashSet<String>();
		for (Map.Entry<String, Double> e : conceptInterAvg.entrySet()) {
			if (e.getValue() <= cutoff) {
				drop.add(e.getKey());
			}
		}
		if (drop.isEmpty()) {
			return core;
		}
		int kept = 0;
		for (ScoredEmbedding se : core) {
			String cn = ConceptNameUtil.extractConceptName(
					se.embedding.getTextContent());
			if (cn == null || !drop.contains(cn)) {
				kept++;
			}
		}
		if (kept < ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
			return core;
		}
		List<ScoredEmbedding> pruned = new ArrayList<ScoredEmbedding>();
		for (ScoredEmbedding se : core) {
			String cn = ConceptNameUtil.extractConceptName(
					se.embedding.getTextContent());
			if (cn == null || !drop.contains(cn)) {
				pruned.add(se);
			}
		}
		log.debug("Coherence-outlier concept pruning: dropped {} concepts ({}), kept {} of {} core members (cutoff=noiseMean={})",
				drop.size(), drop, pruned.size(), core.size(),
				String.format("%.4f", cutoff));
		return pruned;
	}

	/**
	 * Filters out lower-keyword-tier records whose matched query terms
	 * are already fully covered by the higher-keyword tier. Records that
	 * match unique terms not covered by the higher tier are kept.
	 *
	 * @param candidates the refined candidate set with non-uniform keywords
	 * @param queryTerms the query terms after stopword removal
	 * @param kwMax the maximum keyword score in the candidate set
	 * @return filtered candidates, or the original list if filtering
	 *         would produce fewer than {@code ADAPTIVE_MIN_RECORDS}
	 */
	static List<ScoredEmbedding> filterRedundantKeywordTier(
			List<ScoredEmbedding> candidates, String[] queryTerms,
			double kwMax, double bonusThreshold) {
		Set<String> coveredTerms = new HashSet<String>();
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore >= kwMax - 0.01) {
				String text = ChartSearchAiUtils.buildPrefixedText(
						se.embedding.getResourceType(),
						ConceptNameUtil.stripSynonyms(
								se.embedding.getTextContent()));
				String lower = text.toLowerCase();
				String[] words = lower.split("\\s+");
				for (String term : queryTerms) {
					if (SimilarityAndScoringEngine.termMatchesText(term, lower, words)) {
						coveredTerms.add(term);
					}
				}
			}
		}

		// Semantic floor for zero-keyword rescued records.
		double maxSemantic = 0;
		for (ScoredEmbedding se : candidates) {
			if (se.semanticScore > maxSemantic) {
				maxSemantic = se.semanticScore;
			}
		}
		double semanticFloor = maxSemantic
				* ScoreStatistics.refinementSemanticRatio(candidates);

		List<ScoredEmbedding> filtered = new ArrayList<ScoredEmbedding>();
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore >= kwMax - 0.01) {
				filtered.add(se);
			} else if (se.keywordScore >= bonusThreshold) {
				filtered.add(se);
			} else if (se.keywordScore == 0
					&& se.semanticScore >= semanticFloor) {
				// Zero-keyword record with strong semantic signal —
				// the embedding model says this is relevant but no
				// query term appears in the text (e.g. "Pulse" for
				// "heart rate").
				filtered.add(se);
			} else {
				String text = ChartSearchAiUtils.buildPrefixedText(
						se.embedding.getResourceType(),
						ConceptNameUtil.stripSynonyms(
								se.embedding.getTextContent()));
				String lower = text.toLowerCase();
				String[] words = lower.split("\\s+");
				boolean addsNewCoverage = false;
				for (String term : queryTerms) {
					if (!coveredTerms.contains(term)
							&& SimilarityAndScoringEngine.termMatchesText(term, lower, words)) {
						addsNewCoverage = true;
						break;
					}
				}
				if (addsNewCoverage) {
					filtered.add(se);
				}
			}
		}
		if (filtered.size() >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
			log.debug("Keyword tier subset filter: {} -> {} "
					+ "(coveredTerms={})",
					candidates.size(), filtered.size(), coveredTerms);
			return filtered;
		}
		return candidates;
	}
}
