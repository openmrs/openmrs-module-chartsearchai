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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yes/no decisions on candidate sets that gate the filter pipeline. Each
 * gate either short-circuits with an empty result, lets the pipeline pass
 * through unchanged, or returns a refined candidate list. The gates run in
 * a fixed sequence inside {@link EmbeddingRankingPipeline#filterPipeline}:
 * floor → slim-margin → initial z-score → (candidate building) →
 * outlier removal (phase 1) → zero-keyword validation (phase 2).
 *
 * <p>Coherence helpers ({@code filterByCoherence}, {@code filterByMeanCoherence},
 * {@code preserveUniqueCoverage}) live in {@link CoherenceFilters} and are
 * reached via package-private access.</p>
 */
final class RankingPipelineGates {

	private RankingPipelineGates() {
	}

	private static final Logger log = LoggerFactory.getLogger(RankingPipelineGates.class);

	/**
	 * Floor gate — stage 1 of the filter pipeline.
	 *
	 * <p>If the highest score in the candidate set is below the embedding
	 * model's absolute similarity floor, the candidate set is below the
	 * noise boundary. Attempts a z-score + cluster-density rescue that
	 * recovers vocabulary-mismatch queries (e.g. "how hot is the patient?"
	 * → Temperature records) where the model correctly ranks relevant
	 * records first but cosine similarity is inherently low.
	 *
	 * @return {@code null} if the gate rejects (the caller should return
	 *         an empty result); otherwise a boxed boolean indicating
	 *         whether the rescue fired.
	 */
	static Boolean applyFloorGate(List<ScoredEmbedding> scored,
			double maxSemanticScore, double maxBaseScore,
			int queryTermCount, int keywordMatchCount,
			PipelineConfig config) {
		double floorScore = Math.max(maxSemanticScore, maxBaseScore);
		if (floorScore >= config.noiseProfile.absoluteSimilarityFloor()) {
			return Boolean.FALSE;
		}
		boolean belowFloorRescued = false;
		if (queryTermCount > 0
				&& ScoreStatistics.hasStatisticalVariance(scored)
				&& keywordMatchCount < ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
			double zScore = ScoreStatistics.computeSemanticZScore(scored, maxSemanticScore);
			double floorRescueZThreshold =
					ScoreStatistics.floorRescueGumbelThreshold(scored);
			if (zScore >= floorRescueZThreshold) {
				// Verify the signal comes from a genuine cluster, not
				// an isolated outlier. Count records within a tight band
				// of the max score — vocabulary-mismatch queries produce
				// many records at similar scores, while false positives
				// only have 1-2 outlier conditions in that band.
				Set<String> floorConcepts = new HashSet<String>();
				for (ScoredEmbedding se : scored) {
					String cn = ConceptNameUtil
							.extractConceptName(
									se.embedding
											.getTextContent());
					if (cn != null) {
						floorConcepts.add(cn);
					}
				}
				int nConcepts = Math.max(2, floorConcepts.size());
				double densityBand = 1.0 / nConcepts;
				int minCluster = (int) Math.ceil(
						(double) scored.size() / nConcepts);
				double densityFloor = maxSemanticScore
						* (1 - densityBand);
				int clusterDensity = 0;
				for (ScoredEmbedding se : scored) {
					if (se.semanticScore >= densityFloor) {
						clusterDensity++;
					}
				}
				belowFloorRescued = clusterDensity >= minCluster;
				log.warn("Floor gate z-score rescue: zScore={}, "
						+ "density={} (band={}, floor={}), "
						+ "minCluster={}, rescued={}",
						String.format("%.2f", zScore),
						clusterDensity,
						String.format("%.4f", densityBand),
						String.format("%.4f", densityFloor),
						minCluster,
						belowFloorRescued);
			}
		}
		if (!belowFloorRescued) {
			log.debug("Top score {} (semantic={}, combined={}) is below "
					+ "absolute floor {}, returning empty",
					String.format("%.4f", floorScore),
					String.format("%.4f", maxSemanticScore),
					String.format("%.4f", maxBaseScore),
					config.noiseProfile.absoluteSimilarityFloor());
			return null;
		}
		return Boolean.TRUE;
	}

	/**
	 * Slim-margin gate — stage 2 of the filter pipeline.
	 *
	 * <p>When the top semantic score is above the absolute floor but
	 * within one {@code minScoreGap} of it, with zero keyword matches,
	 * require at least 2 records above the floor. A single record just
	 * above the floor is likely a coincidental near-miss, not genuine
	 * signal.
	 *
	 * @return {@code true} to continue, {@code false} to short-circuit
	 *         with an empty result
	 */
	static boolean applySlimMarginGate(List<ScoredEmbedding> scored,
			double maxSemanticScore, int queryTermCount,
			int keywordMatchCount, boolean belowFloorRescued,
			PipelineConfig config) {
		if (belowFloorRescued || keywordMatchCount != 0 || queryTermCount <= 0) {
			return true;
		}
		double smFloor = config.noiseProfile.absoluteSimilarityFloor();
		if (maxSemanticScore >= smFloor + config.minScoreGap) {
			return true;
		}
		int aboveFloorCount = 0;
		for (ScoredEmbedding se : scored) {
			if (se.semanticScore >= smFloor) {
				aboveFloorCount++;
			}
		}
		if (aboveFloorCount < 2) {
			// Allow a single record if its score is in the upper half
			// of the slim-margin zone — records barely above the floor
			// are likely noise, records further above are more likely
			// genuine.
			if (aboveFloorCount == 1
					&& maxSemanticScore >= smFloor + config.minScoreGap / 2) {
				if (log.isDebugEnabled()) {
					log.debug("Slim-margin gate: single record at {} is in "
							+ "upper half of margin zone [{}, {}), allowing",
							String.format("%.4f", maxSemanticScore),
							String.format("%.4f", smFloor + config.minScoreGap / 2),
							String.format("%.4f", smFloor + config.minScoreGap));
				}
				return true;
			}
			log.warn("Slim-margin gate: maxSem={} is within "
					+ "{} of floor {}, zero keywords, and only "
					+ "{} record(s) above floor — returning empty",
					String.format("%.4f", maxSemanticScore),
					config.minScoreGap,
					String.format("%.4f", smFloor),
					aboveFloorCount);
			return false;
		}
		return true;
	}

	/**
	 * Initial z-score gate — stage 3 of the filter pipeline. When too few
	 * records match any query keyword, require the top semantic score to
	 * be a statistical outlier. Captures the z-score and threshold values
	 * for reuse by the downstream cluster z-score gate.
	 *
	 * @return {@code null} if the gate rejects; otherwise a 2-element
	 *         array {@code [zScore, threshold]} ({@code -1} when not
	 *         computed).
	 */
	static double[] applyInitialZScoreGate(List<ScoredEmbedding> scored,
			double maxSemanticScore, int queryTermCount,
			int keywordMatchCount) {
		return applyInitialZScoreGate(scored, maxSemanticScore,
				queryTermCount, keywordMatchCount, -1);
	}

	static double[] applyInitialZScoreGate(List<ScoredEmbedding> scored,
			double maxSemanticScore, int queryTermCount,
			int keywordMatchCount, double maxZScoreThreshold) {
		double[] result = { -1, -1 };
		if (queryTermCount <= 0 || !ScoreStatistics.hasStatisticalVariance(scored)) {
			return result;
		}
		if (keywordMatchCount >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
			return result;
		}
		double zScore = ScoreStatistics.computeSemanticZScore(scored, maxSemanticScore);
		double threshold = ScoreStatistics.effectiveGumbelThreshold(scored);
		// Allow model-specific configs to cap the threshold for
		// compressed-score models where the Gumbel threshold is too
		// aggressive.
		if (maxZScoreThreshold > 0 && threshold > maxZScoreThreshold) {
			threshold = maxZScoreThreshold;
		}
		result[0] = zScore;
		result[1] = threshold;
		if (zScore < threshold) {
			log.debug("Only {} keyword match(es) and top semantic "
					+ "z-score {} is below threshold {}, "
					+ "returning empty (maxSem={})",
					keywordMatchCount,
					String.format("%.2f", zScore),
					String.format("%.2f", threshold),
					String.format("%.4f", maxSemanticScore));
			return null;
		}
		return result;
	}

	/**
	 * Phase 1 (outlier removal) — stage 6 of the filter pipeline.
	 *
	 * <p>Removes individual candidates that are topically unrelated to
	 * the majority via {@code CoherenceFilters.filterByCoherence}.
	 * Skipped when the partial-keyword semantic-core path already curated
	 * the set, every candidate matches ALL query terms, or the set is a
	 * compound-keyword match (see {@link #isCompoundKeywordMatch}).
	 *
	 * <p>At n=3 coherence filtering only fires when scores are tightly
	 * clustered (min/max ratio ≥ 0.90).
	 */
	static List<ScoredEmbedding> applyOutlierRemovalPhase1(
			List<ScoredEmbedding> candidates, String[] queryTerms,
			boolean partialKwValidated) {
		boolean allFullKeywordMatch = true;
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore < 1.0) {
				allFullKeywordMatch = false;
				break;
			}
		}
		boolean isCompoundKeywordMatch = isCompoundKeywordMatch(
				candidates, queryTerms);
		double bonusThreshold = queryTerms.length == 0 ? 1.0
				: (double) Math.min(2, queryTerms.length) / queryTerms.length;
		if (!partialKwValidated && !allFullKeywordMatch
				&& !isCompoundKeywordMatch
				&& candidates.size() >= 4) {
			return CoherenceFilters.preserveUniqueCoverage(candidates,
					CoherenceFilters.filterByCoherence(candidates), queryTerms,
					bonusThreshold);
		}
		if (!partialKwValidated && !allFullKeywordMatch
				&& !isCompoundKeywordMatch
				&& candidates.size() == 3) {
			double topSemantic = candidates.get(0).semanticScore;
			for (ScoredEmbedding se : candidates) {
				if (se.semanticScore > topSemantic) {
					topSemantic = se.semanticScore;
				}
			}
			double lowestSemantic = candidates.get(0).semanticScore;
			for (ScoredEmbedding se : candidates) {
				if (se.semanticScore < lowestSemantic) {
					lowestSemantic = se.semanticScore;
				}
			}
			if (topSemantic > 0 && lowestSemantic / topSemantic >= 0.90) {
				return CoherenceFilters.preserveUniqueCoverage(candidates,
						CoherenceFilters.filterByCoherence(candidates), queryTerms,
						bonusThreshold);
			}
		}
		return candidates;
	}

	/**
	 * Phase 2 (zero-keyword validation) — stage 7 of the filter pipeline.
	 * When no surviving candidate has keyword support, the result set is
	 * purely semantic and must pass two orthogonal confidence checks
	 * (mean coherence and cluster z-score) plus a small-cluster coherence
	 * gate and a single-candidate structural gate. Candidates with any
	 * keyword match bypass this phase entirely.
	 *
	 * <p>The z-score gate is skipped when tight-cluster evidence already
	 * validated signal — captured via {@code belowFloorRescued},
	 * {@code firstPassGapDetected} vs concept count, or the ratio-floor
	 * tight-cluster signal.
	 */
	static List<ScoredEmbedding> applyZeroKeywordValidationPhase2(
			List<ScoredEmbedding> candidates,
			List<ScoredEmbedding> scored, PipelineConfig config,
			boolean partialKwValidated, boolean belowFloorRescued,
			boolean firstPassGapDetected, int adaptiveCutoff,
			int ratioFloorCandidateCount, double initialZScore,
			double initialZThreshold, double maxSemanticScore) {
		boolean candidatesHaveKeywords = false;
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore > 0) {
				candidatesHaveKeywords = true;
				break;
			}
		}
		if (log.isDebugEnabled()) {
			StringBuilder sb = new StringBuilder();
			for (ScoredEmbedding se : candidates) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(se.embedding.getResourceType())
					.append(':').append(se.embedding.getResourceUuid())
					.append(" sem=").append(String.format("%.4f", se.semanticScore))
					.append(" kw=").append(String.format("%.4f", se.keywordScore));
			}
			log.debug("Phase 2: candidatesHaveKeywords={}, candidates={}: [{}]",
					candidatesHaveKeywords, candidates.size(), sb);
		}
		log.debug("Phase2 entry: candidates={}, hasKw={}, partialKwValidated={}, belowFloorRescued={}, gapDetected={}, ratioFloorCount={}",
				candidates.size(), candidatesHaveKeywords, partialKwValidated,
				belowFloorRescued, firstPassGapDetected, ratioFloorCandidateCount);
		if (partialKwValidated || candidatesHaveKeywords || candidates.isEmpty()) {
			return candidates;
		}
		if (candidates.size() >= 2) {
			int preMeanCoherence = candidates.size();
			candidates = CoherenceFilters.filterByMeanCoherence(candidates, config);
			log.debug("Phase2 meanCoherence: {} -> {}", preMeanCoherence, candidates.size());
		}
		// Small-cluster coherence gate: for ≤3 zero-keyword candidates,
		// apply a stricter coherence threshold derived from the patient's
		// own embedding statistics.
		if (candidates.size() == 3) {
			double intraFloor = config.noiseProfile.intraConceptMean
					- config.noiseProfile.intraConceptStd;
			double sumCos = 0;
			int nPairs = 0;
			for (int ci = 0; ci < candidates.size(); ci++) {
				float[] vi = candidates.get(ci).embedding.getEmbeddingVector();
				if (vi == null) {
					continue;
				}
				for (int cj = ci + 1; cj < candidates.size(); cj++) {
					float[] vj = candidates.get(cj).embedding.getEmbeddingVector();
					if (vj == null) {
						continue;
					}
					sumCos += ChartSearchAiUtils.cosineSimilarity(vi, vj);
					nPairs++;
				}
			}
			if (nPairs > 0) {
				double mc = sumCos / nPairs;
				if (mc < intraFloor) {
					// Extract the coherent same-concept pair if one exists.
					double[] avgCoh = new double[3];
					for (int ci = 0; ci < 3; ci++) {
						double s = 0;
						int cnt = 0;
						float[] vi = candidates.get(ci).embedding.getEmbeddingVector();
						if (vi == null) {
							continue;
						}
						for (int cj = 0; cj < 3; cj++) {
							if (ci == cj) {
								continue;
							}
							float[] vj = candidates.get(cj).embedding.getEmbeddingVector();
							if (vj == null) {
								continue;
							}
							s += ChartSearchAiUtils.cosineSimilarity(vi, vj);
							cnt++;
						}
						avgCoh[ci] = cnt > 0 ? s / cnt : 0;
					}
					int worstIdx = 0;
					for (int ci = 1; ci < 3; ci++) {
						if (avgCoh[ci] < avgCoh[worstIdx]) {
							worstIdx = ci;
						}
					}
					int p1 = -1, p2 = -1;
					for (int ci = 0; ci < 3; ci++) {
						if (ci != worstIdx) {
							if (p1 < 0) {
								p1 = ci;
							} else {
								p2 = ci;
							}
						}
					}
					float[] v1 = candidates.get(p1).embedding.getEmbeddingVector();
					float[] v2 = candidates.get(p2).embedding.getEmbeddingVector();
					double pairCos = (v1 != null && v2 != null)
							? ChartSearchAiUtils.cosineSimilarity(v1, v2)
							: 0;
					String cn1 = ConceptNameUtil.extractConceptName(
							candidates.get(p1).embedding.getTextContent());
					String cn2 = ConceptNameUtil.extractConceptName(
							candidates.get(p2).embedding.getTextContent());
					boolean sameConcept = cn1 != null && cn1.equals(cn2);
					if (pairCos >= intraFloor && sameConcept) {
						List<ScoredEmbedding> pair = new ArrayList<ScoredEmbedding>();
						pair.add(candidates.get(p1));
						pair.add(candidates.get(p2));
						log.debug("Small-cluster gate: removed outlier [{}], kept pair cos={}, concept={}",
								candidates.get(worstIdx).embedding.getResourceUuid(),
								String.format("%.4f", pairCos), cn1);
						candidates = pair;
					} else {
						log.debug("Small-cluster coherence gate: meanCoherence={} < intraFloor={}, returning empty",
								String.format("%.4f", mc),
								String.format("%.4f", intraFloor));
						candidates = Collections.emptyList();
					}
				}
			}
		}
		// Tight-cluster detection: the z-score gate is skipped when
		// structural signals already confirm the cluster.
		Set<String> tightCheckConcepts = new HashSet<String>();
		for (ScoredEmbedding se : scored) {
			String cn = ConceptNameUtil.extractConceptName(se.embedding.getTextContent());
			if (cn != null) {
				tightCheckConcepts.add(cn);
			}
		}
		int tightThreshold = tightCheckConcepts.size();
		Set<String> p2CandidateConcepts = new HashSet<String>();
		for (ScoredEmbedding se : candidates) {
			String cn = ConceptNameUtil.extractConceptName(
					se.embedding.getTextContent());
			if (cn != null) {
				p2CandidateConcepts.add(cn);
			}
		}
		boolean ratioFloorSelective = ratioFloorCandidateCount >= 0
				&& ratioFloorCandidateCount <= scored.size() / 4;
		boolean tightClusterDetected = belowFloorRescued
				|| (firstPassGapDetected && adaptiveCutoff < tightThreshold)
				|| (ratioFloorSelective
						&& ratioFloorCandidateCount < tightThreshold
						&& initialZScore >= config.floorRescueMinZScore
						&& maxSemanticScore
						>= config.noiseProfile.absoluteSimilarityFloor()
								+ config.minScoreGap)
				// Multi-concept umbrella bypass: when the ratio floor
				// produced a tight cluster spanning 3+ distinct concepts
				// AND the initial z-score passed its own gate, the concept
				// diversity provides the structural confidence that
				// floorRescueMinZScore provides for single-concept clusters.
				|| (ratioFloorSelective
						&& ratioFloorCandidateCount < tightThreshold
						&& p2CandidateConcepts.size() >= 3
						&& initialZThreshold > 0
						&& initialZScore >= initialZThreshold
						&& maxSemanticScore
						>= config.noiseProfile.absoluteSimilarityFloor()
								+ config.minScoreGap);
		// Single-candidate structural gate.
		if (!tightClusterDetected
				&& candidates.size() == 1
				&& ratioFloorCandidateCount == 1) {
			log.debug("Single zero-keyword candidate with no tight-cluster support and ratioFloor=1, returning empty");
			candidates = Collections.emptyList();
		}
		if (!tightClusterDetected && !candidates.isEmpty()
				&& ScoreStatistics.hasStatisticalVariance(scored)) {
			double sum = 0;
			for (ScoredEmbedding se : scored) {
				sum += se.semanticScore;
			}
			double mean = sum / scored.size();
			double sqSum = 0;
			for (ScoredEmbedding se : scored) {
				double d = se.semanticScore - mean;
				sqSum += d * d;
			}
			double std = Math.sqrt(sqSum / scored.size());
			if (std > 0) {
				double[] zScores = new double[candidates.size()];
				for (int i = 0; i < candidates.size(); i++) {
					zScores[i] = (candidates.get(i).semanticScore - mean) / std;
				}
				Arrays.sort(zScores);
				double zRepresentative = candidates.size() <= 3
						? zScores[zScores.length - 1]
						: zScores[zScores.length / 2];
				double clusterZThreshold;
				if (candidates.size() <= 3) {
					clusterZThreshold = ScoreStatistics.clusterGumbelThreshold(scored);
				} else {
					clusterZThreshold = ScoreStatistics.medianGumbelThreshold(scored);
				}
				log.debug("Phase2 z-score: mean={}, std={}, z={} ({}), threshold={}, tightCluster={}, candidates={}",
						String.format("%.4f", mean),
						String.format("%.4f", std),
						String.format("%.2f", zRepresentative),
						candidates.size() <= 3 ? "max" : "median",
						String.format("%.2f", clusterZThreshold),
						tightClusterDetected, candidates.size());
				if (zRepresentative < clusterZThreshold) {
					if (candidates.size() <= 3
							&& initialZThreshold > 0
							&& initialZScore >= initialZThreshold) {
						log.debug("Phase2: z-score below threshold but initial gate validated, keeping");
					} else {
						log.debug("Phase2: REJECTED by z-score gate (z={} < threshold={})",
								String.format("%.2f", zRepresentative),
								String.format("%.2f", clusterZThreshold));
						candidates = Collections.emptyList();
					}
				}
			}
		}
		return candidates;
	}

	/**
	 * Returns true when the candidate set as a whole covers all query
	 * terms but no single record matches them all — the structural
	 * signature of a multi-concept query like "HIV and CD4 count" where
	 * HIV records match {hiv} and CD4 records match {cd4, count}.
	 *
	 * <p>Used to bypass {@code CoherenceFilters.filterByCoherence},
	 * which would otherwise drop the minority concept cluster as outliers
	 * despite keyword evidence that those records belong to the queried
	 * set.
	 */
	static boolean isCompoundKeywordMatch(
			List<ScoredEmbedding> candidates, String[] queryTerms) {
		if (queryTerms == null || queryTerms.length < 2) {
			return false;
		}
		Set<String> unionCoverage = new HashSet<String>();
		boolean anyRecordMatchesAll = false;
		for (ScoredEmbedding se : candidates) {
			String text = ChartSearchAiUtils.buildPrefixedText(
					se.embedding.getResourceType(),
					ConceptNameUtil.stripSynonyms(
							se.embedding.getTextContent()))
					.toLowerCase();
			String[] words = text.split("\\s+");
			int matchCount = 0;
			for (String term : queryTerms) {
				if (SimilarityAndScoringEngine.termMatchesText(term, text, words)) {
					unionCoverage.add(term);
					matchCount++;
				}
			}
			if (matchCount == queryTerms.length) {
				anyRecordMatchesAll = true;
			}
		}
		if (anyRecordMatchesAll) {
			return false;
		}
		for (String term : queryTerms) {
			if (!unionCoverage.contains(term)) {
				return false;
			}
		}
		return true;
	}
}
