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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-build refinement phases of the filter pipeline. Each method is a
 * distinct strategy invoked from {@code EmbeddingRankingPipeline.filterPipeline}
 * after candidate building:
 *
 * <ul>
 *   <li>{@link #refineByKeywords} — narrow gap-detected results to the
 *       keyword-supported subset (with semantic-rescue for non-keyword
 *       records).</li>
 *   <li>{@link #applyRefinementPath} — stage 5a, when keyword refinement
 *       identified a relevant subset: branches into the partial-keyword
 *       semantic-core path or non-uniform second-pass gap detection.</li>
 *   <li>{@link #applyNonRefinementPath} — stage 5b, sensitive second-pass
 *       gap detection with ratio floor and concept-pairing rescue.</li>
 * </ul>
 *
 * <p>Shares the {@code EmbeddingRankingPipeline.findAdaptiveCutoff} cutoff
 * helper via package-private access; coherence/keyword-tier helpers come
 * from {@link CoherenceFilters}.</p>
 */
final class RefinementPaths {

	private RefinementPaths() {
	}

	private static final Logger log = LoggerFactory.getLogger(RefinementPaths.class);

	/**
	 * Refinement post-processing path — stage 5a of the filter pipeline.
	 * Runs when keyword refinement identified a relevant subset. Computes
	 * uniform-keyword and semantic-dominance signals, then branches into
	 * the partial-keyword semantic-core path or the non-uniform second-pass
	 * gap detection path.
	 *
	 * @param partialKwValidatedOut set to {@code true} when the semantic
	 *        core expansion path fired — used downstream to skip
	 *        coherence filtering that would double-filter the core.
	 */
	static List<ScoredEmbedding> applyRefinementPath(
			List<ScoredEmbedding> candidates,
			List<ScoredEmbedding> preRefinementCandidates,
			List<ScoredEmbedding> scored,
			String[] queryTerms, int queryTermCount,
			double bonusThreshold, double minScore,
			PipelineConfig config, boolean[] partialKwValidatedOut) {
		double kwMin = Double.MAX_VALUE;
		double kwMax = 0;
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore > 0) {
				if (se.keywordScore < kwMin) {
					kwMin = se.keywordScore;
				}
				if (se.keywordScore > kwMax) {
					kwMax = se.keywordScore;
				}
			}
		}
		if (kwMin == Double.MAX_VALUE) {
			kwMin = kwMax;
		}
		boolean uniformKeywords = (kwMax - kwMin) < 0.01;
		boolean semanticDominance = false;
		double maxKwSem = 0;
		double maxNonKwSem = 0;
		if (uniformKeywords && kwMax < bonusThreshold
				&& queryTermCount > 0) {
			double refMinKw = 1.0 / queryTermCount;
			for (ScoredEmbedding se : preRefinementCandidates) {
				if (se.keywordScore >= refMinKw) {
					if (se.semanticScore > maxKwSem) {
						maxKwSem = se.semanticScore;
					}
				} else {
					if (se.semanticScore > maxNonKwSem) {
						maxNonKwSem = se.semanticScore;
					}
				}
			}
			semanticDominance = maxNonKwSem > maxKwSem;
		}
		boolean partialKeywordMatch = uniformKeywords
				&& kwMax < bonusThreshold;
		log.warn("Pipeline refinement: kwMin={}, kwMax={}, uniform={}, partialKw={}, semDom={}",
				String.format("%.4f", kwMin),
				String.format("%.4f", kwMax), uniformKeywords,
				partialKeywordMatch, semanticDominance);
		if (partialKeywordMatch) {
			List<ScoredEmbedding> nonKeyword = new ArrayList<ScoredEmbedding>();
			for (ScoredEmbedding se : preRefinementCandidates) {
				if (se.keywordScore == 0) {
					nonKeyword.add(se);
				}
			}
			Collections.sort(nonKeyword,
					new Comparator<ScoredEmbedding>() {
				@Override
				public int compare(ScoredEmbedding a, ScoredEmbedding b) {
					return Double.compare(b.semanticScore, a.semanticScore);
				}
			});
			List<ScoredEmbedding> semanticCore = new ArrayList<ScoredEmbedding>();
			boolean coreFallbackUsed = false;
			int coreCutoff = nonKeyword.size();
			if (nonKeyword.size() >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
				double maxSemNk = nonKeyword.get(0).semanticScore;
				double nkMinGap = Math.max(
						maxSemNk * ScoreStatistics.refinementAdaptiveGapRatio(scored),
						ScoreStatistics.secondPassMinGap(scored));
				coreCutoff = EmbeddingRankingPipeline.findAdaptiveCutoff(nonKeyword, nonKeyword.size(),
						config.noiseProfile.absoluteSimilarityFloor(),
						config.scoreGapMultiplier, nkMinGap);
				int fisherCut = nonKeyword.size();
				double totalSumF = 0;
				double totalSumSqF = 0;
				for (ScoredEmbedding se : nonKeyword) {
					totalSumF += se.semanticScore;
					totalSumSqF += se.semanticScore * se.semanticScore;
				}
				int nF = nonKeyword.size();
				double leftSumF = 0;
				double leftSumSqF = 0;
				double rightSumF = totalSumF;
				double rightSumSqF = totalSumSqF;
				double maxGF = 0;
				for (int i = 1; i < nF; i++) {
					double s = nonKeyword.get(i - 1).semanticScore;
					leftSumF += s;
					leftSumSqF += s * s;
					rightSumF -= s;
					rightSumSqF -= s * s;
					if (i < ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
						continue;
					}
					int nR = nF - i;
					if (nR < 2) {
						break;
					}
					double lm = leftSumF / i;
					double rm = rightSumF / nR;
					double lv = leftSumSqF / i - lm * lm;
					double rv = rightSumSqF / nR - rm * rm;
					double den = lv / (i - 1) + rv / (nR - 1) + 1e-12;
					double diff = lm - rm;
					double t2 = diff * diff / den;
					double bGap = nonKeyword.get(i - 1).semanticScore
							- nonKeyword.get(i).semanticScore;
					double gf = bGap * t2;
					if (gf > maxGF) {
						maxGF = gf;
						fisherCut = i;
					}
				}
				log.debug("Fisher cluster cut at {} (score={})",
						fisherCut, String.format("%.2f", maxGF));
				if (fisherCut < coreCutoff) {
					double bGapF = nonKeyword.get(fisherCut - 1).semanticScore
							- nonKeyword.get(fisherCut).semanticScore;
					double prevGapF = nonKeyword.get(fisherCut - 2).semanticScore
							- nonKeyword.get(fisherCut - 1).semanticScore;
					if (bGapF > prevGapF) {
						coreFallbackUsed = true;
						coreCutoff = fisherCut;
					} else {
						log.debug("Fisher cut at {} suppressed: boundary gap {} <= prev gap {}",
								fisherCut,
								String.format("%.4f", bGapF),
								String.format("%.4f", prevGapF));
					}
				}
				for (int i = 0; i < coreCutoff; i++) {
					semanticCore.add(nonKeyword.get(i));
				}
				log.warn("Semantic core: {} non-keyword records, primary gap at {}, core size {}",
						nonKeyword.size(), coreCutoff, semanticCore.size());
				// Drop concepts whose mean inter-concept cosine to non-pair
				// core members is at or below the patient's noiseMean.
				semanticCore = CoherenceFilters.pruneCoherenceOutlierConcepts(
						semanticCore, config.noiseProfile);
			}
			boolean coreHasStructure = !semanticCore.isEmpty()
					&& coreCutoff < nonKeyword.size();
			boolean coreRelevant = false;
			if (coreHasStructure && !semanticCore.isEmpty()
					&& !candidates.isEmpty()) {
				float[] coreVec = semanticCore.get(0).embedding.getEmbeddingVector();
				double maxCoreKwCosine = 0;
				for (ScoredEmbedding se : candidates) {
					double cos = ChartSearchAiUtils.cosineSimilarity(coreVec,
							se.embedding.getEmbeddingVector());
					if (cos > maxCoreKwCosine) {
						maxCoreKwCosine = cos;
					}
				}
				coreRelevant = maxCoreKwCosine
						>= ChartSearchAiConstants.SEMANTIC_CORE_MIN_COSINE;
				log.warn("Core topical check: maxCos={} vs threshold={}, relevant={}",
						String.format("%.4f", maxCoreKwCosine),
						ChartSearchAiConstants.SEMANTIC_CORE_MIN_COSINE,
						coreRelevant);
			}
			log.warn("Core validation: structure={}, relevant={}, cutoff={}/{}, semDom={}",
					coreHasStructure, coreRelevant,
					coreCutoff, nonKeyword.size(), semanticDominance);
			if (semanticDominance || coreRelevant) {
				if (!semanticCore.isEmpty()) {
					double coreMinSem = semanticCore.get(
							semanticCore.size() - 1).semanticScore;
					double coreMaxSem = semanticCore.get(0).semanticScore;
					double expansionRatio = coreFallbackUsed
							? (coreMaxSem > 0
									? coreMinSem / coreMaxSem
									: ChartSearchAiConstants.SEMANTIC_CORE_SCORE_RATIO)
							: ChartSearchAiConstants.SEMANTIC_CORE_SCORE_RATIO;
					double scoreFloor = coreMinSem * expansionRatio;
					List<ScoredEmbedding> expanded =
							new ArrayList<ScoredEmbedding>(semanticCore);
					Set<String> expandedIds = new HashSet<String>();
					for (ScoredEmbedding se : semanticCore) {
						expandedIds.add(se.embedding.getResourceUuid());
					}
					if (!semanticDominance) {
						for (ScoredEmbedding se : preRefinementCandidates) {
							if (expandedIds.contains(se.embedding.getResourceUuid())) {
								continue;
							}
							if (se.semanticScore < scoreFloor) {
								continue;
							}
							float[] vec = se.embedding.getEmbeddingVector();
							double maxCos = 0;
							for (ScoredEmbedding core : semanticCore) {
								double cos = ChartSearchAiUtils.cosineSimilarity(vec,
										core.embedding.getEmbeddingVector());
								if (cos > maxCos) {
									maxCos = cos;
								}
							}
							if (maxCos >= ChartSearchAiConstants.SEMANTIC_CORE_MIN_COSINE) {
								expanded.add(se);
								expandedIds.add(se.embedding.getResourceUuid());
							}
						}
					}
					// Concept-pair rescue for keyword-matched anchors:
					// when a kw-matched record from the refinement input
					// shares its concept name with a record already in
					// expanded, restore the missing partner.
					Set<String> expandedConcepts = new HashSet<String>();
					for (ScoredEmbedding se : expanded) {
						String cn = ConceptNameUtil.extractConceptName(
								se.embedding.getTextContent());
						if (cn != null) {
							expandedConcepts.add(cn);
						}
					}
					for (ScoredEmbedding se : candidates) {
						if (expandedIds.contains(se.embedding.getResourceUuid())) {
							continue;
						}
						if (se.keywordScore <= 0) {
							continue;
						}
						String cn = ConceptNameUtil.extractConceptName(
								se.embedding.getTextContent());
						if (cn != null && expandedConcepts.contains(cn)) {
							expanded.add(se);
							expandedIds.add(se.embedding.getResourceUuid());
							log.debug("Concept-pair rescue: re-added kw-matched [{}] (concept={})",
									se.embedding.getResourceUuid(), cn);
						}
					}
					candidates = expanded;
					log.debug("Semantic core expansion: core={}, floor={}, result={}",
							semanticCore.size(),
							String.format("%.4f", scoreFloor),
							candidates.size());
					partialKwValidatedOut[0] = true;
				} else {
					double maxSemanticKw = 0;
					for (ScoredEmbedding se : candidates) {
						if (se.semanticScore > maxSemanticKw) {
							maxSemanticKw = se.semanticScore;
						}
					}
					double semFloor = maxSemanticKw
							* ScoreStatistics.refinementSemanticRatio(candidates);
					List<ScoredEmbedding> filtered = new ArrayList<ScoredEmbedding>();
					for (ScoredEmbedding se : candidates) {
						if (se.semanticScore >= semFloor) {
							filtered.add(se);
						}
					}
					if (filtered.size() >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
						log.debug("Partial-kw semantic floor: {} -> {} (floor={})",
								candidates.size(), filtered.size(),
								String.format("%.4f", semFloor));
						candidates = filtered;
					}
				}
			} else {
				double maxSemanticKw = 0;
				for (ScoredEmbedding se : candidates) {
					if (se.semanticScore > maxSemanticKw) {
						maxSemanticKw = se.semanticScore;
					}
				}
				double semFloor = maxSemanticKw * config.similarityRatio;
				List<ScoredEmbedding> filtered = new ArrayList<ScoredEmbedding>();
				for (ScoredEmbedding se : candidates) {
					if (se.semanticScore >= semFloor) {
						filtered.add(se);
					}
				}
				if (filtered.size() >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
					log.debug("Low-coverage kw semantic floor: {} -> {} (floor={})",
							candidates.size(), filtered.size(),
							String.format("%.4f", semFloor));
					candidates = filtered;
				}
			}
		} else if (!uniformKeywords) {
			double maxSemanticRefined = 0;
			for (ScoredEmbedding se : candidates) {
				if (se.semanticScore > maxSemanticRefined) {
					maxSemanticRefined = se.semanticScore;
				}
			}
			double adaptiveMinGap = Math.max(
					maxSemanticRefined * ScoreStatistics.refinementAdaptiveGapRatio(scored),
					ScoreStatistics.secondPassMinGap(scored));
			int refinedCutoff = EmbeddingRankingPipeline.findAdaptiveCutoff(candidates,
					candidates.size(), minScore,
					config.scoreGapMultiplier, adaptiveMinGap);
			if (refinedCutoff < candidates.size()) {
				if (CoherenceFilters.isGapCoherent(candidates, refinedCutoff,
						config.gapValidationCosineThreshold)) {
					double semanticFloor = maxSemanticRefined
							* ScoreStatistics.refinementSemanticRatio(candidates);
					List<ScoredEmbedding> floored = new ArrayList<ScoredEmbedding>();
					for (ScoredEmbedding se : candidates) {
						if (se.semanticScore >= semanticFloor) {
							floored.add(se);
						}
					}
					log.debug("Refinement gap is intra-topic, using semantic floor {} instead: {} -> {}",
							String.format("%.4f", semanticFloor),
							candidates.size(), floored.size());
					candidates = floored;
				} else if (queryTerms != null) {
					List<ScoredEmbedding> rescued =
							CoherenceFilters.filterRedundantKeywordTier(candidates,
									queryTerms, kwMax, bonusThreshold);
					if (rescued.size() >= refinedCutoff) {
						log.debug("Refinement gap at {} is a concept boundary: rescued {} records with new coverage",
								refinedCutoff, rescued.size() - refinedCutoff);
						candidates = rescued;
					} else {
						candidates = new ArrayList<ScoredEmbedding>(
								candidates.subList(0, refinedCutoff));
					}
				} else {
					candidates = new ArrayList<ScoredEmbedding>(
							candidates.subList(0, refinedCutoff));
				}
			} else if (queryTerms != null) {
				candidates = CoherenceFilters.filterRedundantKeywordTier(
						candidates, queryTerms, kwMax, bonusThreshold);
			}
		}
		return candidates;
	}

	/**
	 * Non-refinement post-processing path — stage 5b of the filter pipeline.
	 * Sensitive second-pass gap detection, ratio floor, and concept-pairing
	 * rescue.
	 *
	 * @param ratioFloorCandidateCountOut receives the strict-floor count
	 *        (before concept-pairing rescue) — used by the cluster z-score
	 *        gate downstream.
	 */
	static List<ScoredEmbedding> applyNonRefinementPath(
			List<ScoredEmbedding> candidates,
			List<ScoredEmbedding> scored,
			double maxSemanticScore, double maxBaseScore,
			double minScore, PipelineConfig config,
			int[] ratioFloorCandidateCountOut) {
		// Sensitive second-pass: same multiplier as the first pass, but
		// with a much lower absolute floor derived from the score
		// distribution's std.
		int secondCutoff = EmbeddingRankingPipeline.findAdaptiveCutoff(candidates, candidates.size(),
				minScore, config.scoreGapMultiplier,
				ScoreStatistics.secondPassMinGap(scored));
		if (secondCutoff < candidates.size()) {
			if (CoherenceFilters.isGapCoherent(candidates, secondCutoff,
					config.gapValidationCosineThreshold)) {
				log.debug("Second-pass gap at {} is intra-topic, skipping cut",
						secondCutoff);
			} else {
				candidates = new ArrayList<ScoredEmbedding>(
						candidates.subList(0, secondCutoff));
			}
		}
		// Use the lower of maxBaseScore and maxSemanticScore for the
		// floor. Bonuses inflate combined scores; penalties deflate.
		// The per-record check uses min(combined, semantic) so bonuses
		// are stripped (checking semantic) and penalties are preserved.
		//
		// Uniform-keyword exception: when every candidate has the same
		// keyword score, the penalty/bonus is applied equally, so it
		// cancels out in relative ranking but distorts the absolute
		// floor. Fall back to pure-semantic comparison.
		boolean uniformCandidateKw = true;
		double firstKw = candidates.isEmpty() ? 0
				: candidates.get(0).keywordScore;
		for (ScoredEmbedding se : candidates) {
			if (Math.abs(se.keywordScore - firstKw) > 1e-9) {
				uniformCandidateKw = false;
				break;
			}
		}
		// Uniform-keyword tight-cluster bypass: when every candidate
		// shares the same (non-zero) keyword score AND their semantic
		// scores form a tight cluster (within 2× cross-concept noise
		// std), skip the ratio floor — it would arbitrarily drop the
		// lower-scoring members of a cohesive same-topic group.
		boolean tightUniformCluster = false;
		if (uniformCandidateKw && firstKw > 0
				&& candidates.size()
				>= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
			double maxSem = 0;
			double minSem = Double.MAX_VALUE;
			for (ScoredEmbedding se : candidates) {
				if (se.semanticScore > maxSem) maxSem = se.semanticScore;
				if (se.semanticScore < minSem) minSem = se.semanticScore;
			}
			double spread = maxSem - minSem;
			double noiseSpreadBand = 2.0 * config.noiseProfile.noiseStd;
			if (maxSem > 0 && spread <= noiseSpreadBand) {
				tightUniformCluster = true;
				log.debug("Uniform-keyword tight cluster detected: spread={}, noiseBand={}, kw={}, n={} — bypassing ratio floor",
						String.format("%.4f", spread),
						String.format("%.4f", noiseSpreadBand),
						String.format("%.3f", firstKw),
						candidates.size());
			}
		}
		double ratioFloor = uniformCandidateKw
				? maxSemanticScore * config.similarityRatio
				: Math.min(maxBaseScore, maxSemanticScore)
						* config.similarityRatio;
		List<ScoredEmbedding> strict = new ArrayList<ScoredEmbedding>();
		List<ScoredEmbedding> nearMiss = new ArrayList<ScoredEmbedding>();
		for (ScoredEmbedding se : candidates) {
			if (tightUniformCluster) {
				strict.add(se);
				continue;
			}
			double check = uniformCandidateKw ? se.semanticScore
					: Math.min(se.score, se.semanticScore);
			if (check >= ratioFloor) {
				strict.add(se);
			} else {
				nearMiss.add(se);
			}
		}
		ratioFloorCandidateCountOut[0] = strict.size();
		log.debug("Non-refinement: ratioFloor={}, strict={}, nearMiss={}, total={}",
				String.format("%.4f", ratioFloor), strict.size(),
				nearMiss.size(), candidates.size());
		// Concept-pairing rescue: when a record barely misses the
		// ratio floor but its same-concept partner survived, the floor
		// is splitting a natural concept pair. Rescue the near-miss if
		// its gap from the floor is within the noise resolution.
		if (!strict.isEmpty() && !nearMiss.isEmpty()) {
			Map<String, Integer> conceptCounts = new HashMap<String, Integer>();
			Set<String> survivorConcepts = new HashSet<String>();
			for (ScoredEmbedding se : strict) {
				String cn = ConceptNameUtil
						.extractConceptName(se.embedding.getTextContent());
				if (cn != null) {
					survivorConcepts.add(cn);
					Integer count = conceptCounts.get(cn);
					conceptCounts.put(cn, count == null ? 1 : count + 1);
				}
			}
			boolean withinConceptCalibration = false;
			for (int count : conceptCounts.values()) {
				if (count >= 2) {
					withinConceptCalibration = true;
					break;
				}
			}
			if (withinConceptCalibration) {
				Set<String> allConcepts = new HashSet<String>();
				for (ScoredEmbedding se : scored) {
					String cn = ConceptNameUtil
							.extractConceptName(se.embedding.getTextContent());
					if (cn != null) {
						allConcepts.add(cn);
					}
				}
				double rescueTolerance = config.noiseProfile.noiseStd
						/ Math.max(1, allConcepts.size());
				for (ScoredEmbedding se : nearMiss) {
					double eff = Math.min(se.score, se.semanticScore);
					double gap = ratioFloor - eff;
					if (gap > rescueTolerance) {
						continue;
					}
					String cn = ConceptNameUtil
							.extractConceptName(se.embedding.getTextContent());
					if (cn != null && survivorConcepts.contains(cn)) {
						strict.add(se);
						log.debug("Concept-pairing rescue: [{}] gap={}, concept={}",
								se.embedding.getResourceUuid(),
								String.format("%.4f", gap), cn);
					}
				}
			}
		}
		candidates = strict;
		return candidates;
	}

	/**
	 * Refines the gap-detected result set by keeping only records that
	 * have strong keyword matches, when those matches identify a clear
	 * subset. Catches cases where the score distribution is too smooth
	 * for gap detection to find a cutoff but keyword matching provides
	 * a discriminative type signal.
	 */
	static List<ScoredEmbedding> refineByKeywords(List<ScoredEmbedding> candidates,
			int queryTermCount) {
		if (queryTermCount == 0) {
			return candidates;
		}
		// Require at least 1 matching query term — any keyword
		// relevance is sufficient. Gap detection is the primary noise
		// filter; refinement separates "has keyword evidence" from
		// "has no keyword evidence".
		double minKwScore = 1.0 / queryTermCount;

		List<ScoredEmbedding> keywordMatched = new ArrayList<ScoredEmbedding>();
		double kwMinScore = Double.MAX_VALUE;
		for (ScoredEmbedding se : candidates) {
			if (se.keywordScore >= minKwScore) {
				keywordMatched.add(se);
				if (se.keywordScore < kwMinScore) {
					kwMinScore = se.keywordScore;
				}
			}
		}
		int minRecords = ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS;
		if (keywordMatched.size() >= minRecords && keywordMatched.size() < candidates.size()) {
			// Rescue non-keyword records that the semantic model ranks
			// above the WEAKEST keyword tier.
			double maxMinTierSemantic = 0;
			for (ScoredEmbedding se : keywordMatched) {
				if (se.keywordScore <= kwMinScore + 0.01
						&& se.semanticScore > maxMinTierSemantic) {
					maxMinTierSemantic = se.semanticScore;
				}
			}
			List<ScoredEmbedding> refined =
					new ArrayList<ScoredEmbedding>(keywordMatched);
			int rescued = 0;
			for (ScoredEmbedding se : candidates) {
				if (se.keywordScore < minKwScore
						&& se.semanticScore > maxMinTierSemantic) {
					refined.add(se);
					rescued++;
				}
			}
			if (rescued > 0) {
				log.debug("Keyword refinement: keeping {} of {} "
						+ "(minKwScore={}, rescued {} semantic-"
						+ "dominant non-keyword records, "
						+ "minTierSem={})",
						refined.size(), candidates.size(),
						String.format("%.3f", minKwScore), rescued,
						String.format("%.4f", maxMinTierSemantic));
			} else {
				log.debug("Keyword refinement: keeping {} of {} "
						+ "(minKwScore={})",
						keywordMatched.size(), candidates.size(),
						String.format("%.3f", minKwScore));
			}
			return refined;
		}
		return candidates;
	}
}
