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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The retrieval/ranking pipeline. Owns everything reachable from the
 * {@link #findSimilar} / {@link #findSimilarWithProfile} entry points that
 * isn't covered by {@link SimilarityAndScoringEngine} (pure scoring),
 * {@link QueryPreprocessor} (query normalization),
 * {@link ConceptRescueAndFilter} (concept-name floor / category-hint rescue),
 * or {@link ChartBuildingStrategy} (chart assembly). Internally groups into
 * coherence/clustering primitives, keyword/concept matchers, candidate
 * gates, the filter-pipeline orchestration with refinement paths, and the
 * static query entry points consumed by {@link LlmInferenceService}.
 */
final class EmbeddingRankingPipeline {

	private EmbeddingRankingPipeline() {
	}

	private static final Logger log = LoggerFactory.getLogger(EmbeddingRankingPipeline.class);

	/**
	 * Output of {@link #buildCandidates}: the candidate list plus the
	 * scalar outputs ({@code minScore}, {@code adaptiveCutoff}) that
	 * downstream stages still need.
	 */
	static final class CandidateBuildResult {
		final List<ScoredEmbedding> candidates;
		final int adaptiveCutoff;
		final double minScore;

		CandidateBuildResult(List<ScoredEmbedding> candidates,
				int adaptiveCutoff, double minScore) {
			this.candidates = candidates;
			this.adaptiveCutoff = adaptiveCutoff;
			this.minScore = minScore;
		}
	}

	/**
	 * Overload that accepts a term count instead of actual query terms.
	 * The keyword-tier subset check is skipped since the actual terms
	 * are not available.
	 */
	static List<ChartEmbedding> filterPipeline(double[] semanticScores,
			double[] keywordScores, ChartEmbedding[] embeddings,
			int queryTermCount, PipelineConfig config) {
		return filterPipeline(semanticScores, keywordScores, embeddings,
				null, queryTermCount, config);
	}

	/**
	 * Pure filtering pipeline: scores, gates, gap detection, keyword
	 * refinement, coherence filtering, and below-floor rescue. Takes
	 * pre-computed scores and config parameters — no dependency on
	 * OpenMRS Context, making it directly testable.
	 *
	 * @param semanticScores cosine similarity between query and each record
	 * @param keywordScores keyword overlap fraction for each record
	 * @param embeddings the chart embeddings (parallel to score arrays)
	 * @param queryTerms query terms after stopword removal (null-safe:
	 *        when null, the keyword-tier subset check is skipped)
	 * @param config pipeline tuning parameters
	 * @return filtered list of relevant embeddings, or empty list
	 */
	static List<ChartEmbedding> filterPipeline(double[] semanticScores,
			double[] keywordScores, ChartEmbedding[] embeddings,
			String[] queryTerms, PipelineConfig config) {
		return filterPipeline(semanticScores, keywordScores, embeddings,
				queryTerms, queryTerms != null ? queryTerms.length : 0,
				config);
	}

	static List<ChartEmbedding> filterPipeline(double[] semanticScores,
			double[] keywordScores, ChartEmbedding[] embeddings,
			String[] queryTerms, int queryTermCount,
			PipelineConfig config) {
		return filterPipeline(semanticScores, keywordScores, embeddings,
				queryTerms, queryTermCount, config, null);
	}

	/**
	 * @param outGapCutoff if non-null, receives the gap-analysis candidate
	 *        count in [0]. Used by the noise-saturation check to detect
	 *        queries where the dataset has no genuine signal.
	 */
	static List<ChartEmbedding> filterPipeline(double[] semanticScores,
			double[] keywordScores, ChartEmbedding[] embeddings,
			String[] queryTerms, int queryTermCount,
			PipelineConfig config, int[] outGapCutoff) {

		double maxBaseScore = 0;
		double maxSemanticScore = 0;

		// Require ≥2 term matches for the keyword bonus so that single
		// coincidental matches (e.g. "history" in "immunization history"
		// for "history of cancer", or "lab" in "Labs ordered." for
		// "lab orders placed resulted") don't inflate scores.
		double bonusThreshold = queryTermCount == 0 ? 1.0
				: (double) Math.min(2, queryTermCount) / queryTermCount;
		List<ScoredEmbedding> scored = new ArrayList<ScoredEmbedding>();
		for (int i = 0; i < embeddings.length; i++) {
			double semanticScore = semanticScores[i];
			double keywordScore = keywordScores[i];

			// Additive keyword bonus increases the score when enough terms
			// match. For N≤2 queries, a partial keyword match (below the
			// bonus threshold) applies a penalty instead.
			double keywordBonus = keywordScore >= bonusThreshold ? keywordScore : 0.0;
			double keywordPenalty = 0.0;
			if (queryTermCount <= 2 && keywordScore > 0 && keywordScore < bonusThreshold) {
				keywordPenalty = keywordScore;
			}
			double baseScore = semanticScore + config.keywordWeight * keywordBonus
					- config.keywordWeight * keywordPenalty;

			if (semanticScore > maxSemanticScore) {
				maxSemanticScore = semanticScore;
			}
			if (baseScore > maxBaseScore) {
				maxBaseScore = baseScore;
			}

			scored.add(new ScoredEmbedding(embeddings[i], baseScore, keywordScore, semanticScore));
		}

		Collections.sort(scored, new Comparator<ScoredEmbedding>() {
			@Override
			public int compare(ScoredEmbedding a, ScoredEmbedding b) {
				return Double.compare(b.score, a.score);
			}
		});

		if (scored.isEmpty()) {
			return Collections.emptyList();
		}

		int keywordMatchCount = 0;
		for (ScoredEmbedding se : scored) {
			if (se.keywordScore > 0) {
				keywordMatchCount++;
			}
		}

		// Keyword flooding guard: when > 50% of records match keywords,
		// the matching terms are too generic to distinguish relevant
		// from irrelevant records. Zero out keyword scores and re-sort
		// by semantic score only.
		if (keywordMatchCount > scored.size() / 2
				&& queryTermCount > 2) {
			log.warn("Keyword flooding: {}/{} records match keywords,"
					+ " zeroing keyword scores for {}",
					keywordMatchCount, scored.size(),
					Arrays.toString(queryTerms));
			maxBaseScore = 0;
			for (int i = 0; i < scored.size(); i++) {
				ScoredEmbedding se = scored.get(i);
				scored.set(i, new ScoredEmbedding(se.embedding,
						se.semanticScore, 0, se.semanticScore));
				if (se.semanticScore > maxBaseScore) {
					maxBaseScore = se.semanticScore;
				}
			}
			keywordMatchCount = 0;
			Collections.sort(scored, new Comparator<ScoredEmbedding>() {
				@Override
				public int compare(ScoredEmbedding a, ScoredEmbedding b) {
					return Double.compare(b.score, a.score);
				}
			});
		}

		// Floor gate.
		Boolean floorResult = RankingPipelineGates.applyFloorGate(scored, maxSemanticScore,
				maxBaseScore, queryTermCount, keywordMatchCount, config);
		if (floorResult == null) {
			return Collections.emptyList();
		}
		boolean belowFloorRescued = floorResult;

		// Slim-margin gate.
		if (!RankingPipelineGates.applySlimMarginGate(scored, maxSemanticScore,
				queryTermCount, keywordMatchCount, belowFloorRescued,
				config)) {
			return Collections.emptyList();
		}

		// Initial z-score gate.
		double[] zScoreState = RankingPipelineGates.applyInitialZScoreGate(scored,
				maxSemanticScore, queryTermCount, keywordMatchCount,
				config.floorRescueMinZScore);
		if (zScoreState == null) {
			return Collections.emptyList();
		}
		double initialZScore = zScoreState[0];
		double initialZThreshold = zScoreState[1];

		// Gap detection + candidate building.
		CandidateBuildResult built = buildCandidates(scored,
				maxSemanticScore, bonusThreshold, config);
		double minScore = built.minScore;
		int adaptiveCutoff = built.adaptiveCutoff;
		if (outGapCutoff != null && outGapCutoff.length > 0) {
			outGapCutoff[0] = adaptiveCutoff;
		}
		List<ScoredEmbedding> candidates = built.candidates;

		// Keyword refinement.
		List<ScoredEmbedding> preRefinementCandidates = candidates;
		boolean refinementActivated = false;
		boolean selectiveKwRescued = false;
		if (config.keywordWeight > 0) {
			List<ScoredEmbedding> refined = RefinementPaths.refineByKeywords(candidates, queryTermCount);
			refinementActivated = refined.size() < candidates.size();
			if (refinementActivated) {
				double rMaxSem = 0;
				double rMaxKw = 0;
				for (ScoredEmbedding se : refined) {
					if (se.semanticScore > rMaxSem) {
						rMaxSem = se.semanticScore;
					}
					if (se.keywordScore > rMaxKw) {
						rMaxKw = se.keywordScore;
					}
				}
				// Partial-match semantic floor: when keyword refinement
				// kept only PARTIAL matches AND the best semantic score
				// is below max(absoluteFloor, noiseMean - 1 std), the
				// keyword overlap is coincidental — the embedding model
				// sees no topical relevance.
				double partialKwFloor = Math.max(
						config.noiseProfile.absoluteSimilarityFloor(),
						config.noiseProfile.noiseMean
								- config.noiseProfile.noiseStd);
				int selectiveKwMaxRecords =
						ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS;
				if (scored.size()
						> ChartSearchAiConstants.LARGE_CORPUS_SELECTIVE_RESCUE_MIN) {
					selectiveKwMaxRecords = Math.max(
							selectiveKwMaxRecords,
							(int) Math.ceil(scored.size()
									* ChartSearchAiConstants.LARGE_CORPUS_SELECTIVE_KW_FRACTION));
				}
				boolean selectiveKwMatch = refined.size()
						<= selectiveKwMaxRecords;
				if (selectiveKwMatch && rMaxKw < bonusThreshold
						&& rMaxSem < partialKwFloor) {
					// Selective-keyword rescue: route through the
					// non-refinement path (ratio floor + z-score guards)
					// instead of the refinement expansion path that
					// would flood on compressed score models.
					refinementActivated = false;
					selectiveKwRescued = true;
					log.debug("Selective-keyword rescue: keeping {} "
							+ "records via non-refinement path "
							+ "(maxSem={}, floor={})",
							refined.size(),
							String.format("%.4f", rMaxSem),
							String.format("%.4f", partialKwFloor));
				} else if (rMaxKw < bonusThreshold
						&& rMaxSem < partialKwFloor) {
					Set<String> badIds =
							new HashSet<String>();
					for (ScoredEmbedding se : refined) {
						badIds.add(
								se.embedding.getResourceUuid());
					}
					List<ScoredEmbedding> cleaned =
							new ArrayList<ScoredEmbedding>();
					for (ScoredEmbedding se : candidates) {
						if (!badIds.contains(
								se.embedding
										.getResourceUuid())) {
							cleaned.add(se);
						}
					}
					log.warn("Partial-match semantic floor: "
							+ "removing {} below-floor partial "
							+ "kw records (maxSem={}, floor={})",
							badIds.size(),
							String.format("%.4f", rMaxSem),
							String.format("%.4f", partialKwFloor));
					refined = cleaned;
					refinementActivated = false;
				}
			}
			candidates = refined;
		}

		log.warn("Pipeline stages {}: maxSem={}, maxBase={}, floor={}, kwCount={}, "
				+ "termCount={}, gapCutoff={}, candidates={}, refined={}",
				Arrays.toString(queryTerms), String.format("%.4f", maxSemanticScore),
				String.format("%.4f", maxBaseScore),
				String.format("%.4f", config.noiseProfile.absoluteSimilarityFloor()),
				keywordMatchCount, queryTermCount,
				adaptiveCutoff, candidates.size(), refinementActivated);

		boolean partialKwValidated = false;
		boolean firstPassGapDetected = adaptiveCutoff < scored.size();
		int ratioFloorCandidateCount = -1;

		// Post-processing with two paths.
		if (refinementActivated) {
			boolean[] pkvOut = { false };
			candidates = RefinementPaths.applyRefinementPath(candidates,
					preRefinementCandidates, scored, queryTerms,
					queryTermCount, bonusThreshold, minScore,
					config, pkvOut);
			partialKwValidated = pkvOut[0];
		} else {
			int[] rfccOut = { -1 };
			candidates = RefinementPaths.applyNonRefinementPath(candidates, scored,
					maxSemanticScore, maxBaseScore, minScore,
					config, rfccOut);
			ratioFloorCandidateCount = rfccOut[0];
		}

		// Keyword-dominance rescue: when all post-processed candidates
		// have keyword matches and the set is small, scan the full
		// scored list for non-keyword records that are above the ratio
		// floor and individually coherent with the keyword set above a
		// data-derived threshold.
		if (candidates.size()
				<= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS
				&& !candidates.isEmpty()) {
			boolean allKw = true;
			for (ScoredEmbedding se : candidates) {
				if (se.keywordScore == 0) {
					allKw = false;
					break;
				}
			}
			if (allKw) {
				double kwMaxSem = 0;
				double kwMaxScore = 0;
				for (ScoredEmbedding se : candidates) {
					if (se.semanticScore > kwMaxSem) {
						kwMaxSem = se.semanticScore;
					}
					if (se.keywordScore > kwMaxScore) {
						kwMaxScore = se.keywordScore;
					}
				}
				double kwConf = queryTermCount > 0
						? kwMaxScore * queryTermCount
								/ Math.max(bonusThreshold, 0.01)
						: 1.0;
				double perRecordThreshold = Math.sqrt(
						config.noiseProfile.noiseMean
						* config.noiseProfile.noiseP95) * kwConf;
				double rescueRatio = selectiveKwRescued
						? config.similarityRatio
								* config.similarityRatio
						: config.similarityRatio;
				double mergeFloor = kwMaxSem * rescueRatio;
				Set<String> inCandidates = new HashSet<String>();
				for (ScoredEmbedding se : candidates) {
					inCandidates.add(
							se.embedding.getResourceUuid());
				}

				// For the selective-keyword rescue path, compute a
				// distribution-based coherence threshold instead of
				// the fixed noise-profile one.
				double effectiveThreshold = perRecordThreshold;
				if (selectiveKwRescued) {
					List<Double> cohValues =
							new ArrayList<Double>();
					for (ScoredEmbedding se : scored) {
						if (inCandidates.contains(
								se.embedding.getResourceUuid())
								|| se.keywordScore > 0) {
							continue;
						}
						float[] vec = se.embedding
								.getEmbeddingVector();
						if (vec == null) {
							continue;
						}
						double cs = 0;
						int cn = 0;
						for (ScoredEmbedding kw : candidates) {
							float[] kwVec = kw.embedding
									.getEmbeddingVector();
							if (kwVec != null
									&& kwVec.length
									== vec.length) {
								cs += ChartSearchAiUtils
										.cosineSimilarity(
												vec, kwVec);
								cn++;
							}
						}
						if (cn > 0) {
							cohValues.add(cs / cn);
						}
					}
					if (cohValues.size() >= 4) {
						double cohSum = 0;
						for (double v : cohValues) {
							cohSum += v;
						}
						double cohMean = cohSum
								/ cohValues.size();
						double cohSqSum = 0;
						for (double v : cohValues) {
							double d = v - cohMean;
							cohSqSum += d * d;
						}
						double cohStd = Math.sqrt(cohSqSum
								/ cohValues.size());
						effectiveThreshold = cohMean + cohStd / 2;
						log.debug("Selective rescue coherence "
								+ "threshold: mean={}, std={}, "
								+ "effective={} (fixed was {})",
								String.format("%.4f", cohMean),
								String.format("%.4f", cohStd),
								String.format("%.4f",
										effectiveThreshold),
								String.format("%.4f",
										perRecordThreshold));
					}
				}

				List<ScoredEmbedding> rescued =
						new ArrayList<ScoredEmbedding>();
				for (ScoredEmbedding se : scored) {
					if (inCandidates.contains(
							se.embedding.getResourceUuid())
							|| se.keywordScore > 0
							|| se.semanticScore < mergeFloor) {
						continue;
					}
					float[] vec = se.embedding
							.getEmbeddingVector();
					if (vec == null) {
						continue;
					}
					double cosSum = 0;
					int cnt = 0;
					for (ScoredEmbedding kw : candidates) {
						float[] kwVec = kw.embedding
								.getEmbeddingVector();
						if (kwVec != null
								&& kwVec.length == vec.length) {
							cosSum += ChartSearchAiUtils
									.cosineSimilarity(
											vec, kwVec);
							cnt++;
						}
					}
					if (cnt > 0
							&& cosSum / cnt
							>= effectiveThreshold) {
						rescued.add(se);
					}
				}
				if (!rescued.isEmpty()) {
					// Concept-pairing for rescued records.
					Set<String> rescuedConcepts =
							new HashSet<String>();
					Set<String> rescuedIds =
							new HashSet<String>();
					for (ScoredEmbedding se : rescued) {
						rescuedIds.add(
								se.embedding.getResourceUuid());
						String cn = ConceptNameUtil
								.extractConceptName(
										se.embedding
												.getTextContent());
						if (cn != null) {
							rescuedConcepts.add(cn);
						}
					}
					if (selectiveKwRescued
						&& !rescuedConcepts.isEmpty()) {
						for (ScoredEmbedding se : scored) {
							String rid = se.embedding
									.getResourceUuid();
							if (inCandidates.contains(rid)
									|| rescuedIds.contains(rid)) {
								continue;
							}
							String cn = ConceptNameUtil
									.extractConceptName(
											se.embedding
													.getTextContent());
							if (cn != null
									&& rescuedConcepts
											.contains(cn)) {
								rescued.add(se);
								rescuedIds.add(rid);
							}
						}
					}
					log.warn("Keyword-dominance rescue: merging "
							+ "{} non-kw records (threshold={}, "
							+ "kwConf={}) with {} kw records",
							rescued.size(),
							String.format("%.4f",
									effectiveThreshold),
							String.format("%.2f", kwConf),
							candidates.size());
					List<ScoredEmbedding> merged =
							new ArrayList<ScoredEmbedding>(
									candidates);
					merged.addAll(rescued);
					candidates = merged;
					partialKwValidated = true;
				}
			}
		}

		// Phase 1: Outlier removal.
		candidates = RankingPipelineGates.applyOutlierRemovalPhase1(candidates, queryTerms,
				partialKwValidated);
		// Phase 2: Zero-keyword validation.
		candidates = RankingPipelineGates.applyZeroKeywordValidationPhase2(candidates,
				scored, config, partialKwValidated, belowFloorRescued,
				firstPassGapDetected, adaptiveCutoff,
				ratioFloorCandidateCount, initialZScore,
				initialZThreshold, maxSemanticScore);

		List<ChartEmbedding> results = new ArrayList<ChartEmbedding>();
		for (ScoredEmbedding se : candidates) {
			results.add(se.embedding);
		}

		// Safety net: if all downstream gates rejected every record,
		// but the initial z-score gate validated the top score as a
		// genuine statistical outlier (≥ 2x threshold) AND the top
		// semantic score clears floor + 2*minScoreGap, return the top
		// record as a last-resort fallback.
		if (results.isEmpty() && !scored.isEmpty()
				&& initialZScore >= 0
				&& initialZScore >= 2 * initialZThreshold
				&& scored.get(0).semanticScore
						>= config.noiseProfile.absoluteSimilarityFloor()
								+ 2 * config.minScoreGap) {
			log.warn("Safety net: all gates rejected {} records but top "
					+ "semantic score {} >= floor+2*gap {} and z-score "
					+ "{} >= 2*threshold {}, returning top record as "
					+ "fallback",
					scored.size(),
					String.format("%.4f", scored.get(0).semanticScore),
					String.format("%.4f",
							config.noiseProfile.absoluteSimilarityFloor()
									+ 2 * config.minScoreGap),
					String.format("%.2f", initialZScore),
					String.format("%.2f", 2 * initialZThreshold));
			results = Collections.singletonList(scored.get(0).embedding);
		}

		int logLimit = Math.min(20, scored.size());
		StringBuilder scores = new StringBuilder();
		for (int i = 0; i < logLimit; i++) {
			if (i > 0) {
				scores.append(", ");
			}
			ScoredEmbedding se = scored.get(i);
			scores.append(se.embedding.getResourceType())
					.append(":").append(se.embedding.getResourceUuid())
					.append("=").append(String.format("%.4f", se.score));
		}
		log.warn("Similarity scores: [{}], minScore: {}, adaptiveCutoff: {}",
				scores, String.format("%.4f", minScore), adaptiveCutoff);
		log.warn("Returning {} {} of {} candidates (topScore={})",
				Arrays.toString(queryTerms), results.size(), scored.size(),
				String.format("%.4f", maxBaseScore));
		return results;
	}

	/**
	 * Finds the adaptive cutoff point in a sorted list of scored embeddings
	 * by detecting a significant gap in raw semantic similarity scores. Uses
	 * semantic scores (not keyword-inflated combined scores) so that keyword
	 * bonuses don't create artificial cluster boundaries — this ensures
	 * multi-concept queries (e.g. "blood pressure, weight, and temperature")
	 * don't get split by uneven keyword matching across concepts. Always
	 * includes at least {@link ChartSearchAiConstants#ADAPTIVE_MIN_RECORDS}
	 * records (if available above the similarity floor) so the LLM has enough
	 * context.
	 *
	 * @param scored sorted list of scored embeddings (highest combined score first)
	 * @param limit the maximum number of candidates to consider
	 * @param minScore the absolute similarity floor (applied to semantic scores)
	 * @param gapMultiplier how many times larger than the average gap a score
	 *        drop must be to trigger a cutoff
	 * @param minGap the minimum absolute gap required to trigger a cutoff,
	 *        regardless of the multiplier condition
	 * @return the number of records to include in the primary cluster
	 */
	static int findAdaptiveCutoff(List<ScoredEmbedding> scored, int limit, double minScore,
			double gapMultiplier, double minGap) {
		int minRecords = ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS;

		List<Double> semanticSorted = new ArrayList<Double>();
		for (int i = 0; i < limit; i++) {
			semanticSorted.add(scored.get(i).semanticScore);
		}
		Collections.sort(semanticSorted, Collections.reverseOrder());

		int aboveFloor = 0;
		for (int i = 0; i < semanticSorted.size(); i++) {
			if (semanticSorted.get(i) >= minScore) {
				aboveFloor++;
			} else {
				break;
			}
		}

		if (aboveFloor == 0) {
			return 0;
		}

		double gapSum = 0;
		int semanticCutoff = aboveFloor;
		for (int i = 1; i < aboveFloor; i++) {
			double gap = semanticSorted.get(i - 1) - semanticSorted.get(i);

			if (i >= minRecords && i >= 2) {
				double avgGap = gapSum / (i - 1);
				if (gap > avgGap * gapMultiplier && gap > minGap) {
					semanticCutoff = i;
					log.debug("Score gap detected at position {}: gap={}, avgGap={}, multiplier={}",
							i, String.format("%.4f", gap), String.format("%.4f", avgGap),
							gapMultiplier);
					break;
				}
			}
			gapSum += gap;
		}

		return Math.max(semanticCutoff, Math.min(minRecords, aboveFloor));
	}

	/**
	 * Gap detection + candidate building — stage 4 of the filter pipeline.
	 * Computes the permissive {@code minScore} floor, an IQR-based
	 * adaptive {@code minGap} for compressed-distribution models, then
	 * calls {@link #findAdaptiveCutoff} to locate the semantic cluster
	 * boundary. Builds the candidate set from records at or above the
	 * boundary OR with a full keyword match (kwScore ≥ bonusThreshold).
	 */
	static CandidateBuildResult buildCandidates(
			List<ScoredEmbedding> scored, double maxSemanticScore,
			double bonusThreshold, PipelineConfig config) {
		// Permissive floor: gap detection handles the real cutoff.
		double minScore = Math.min(
				config.noiseProfile.absoluteSimilarityFloor() / 2,
				maxSemanticScore / 2);

		// Adaptive min gap: scale to score-range so models with
		// compressed distributions (e.g. MedCPT) can detect gaps that
		// are large relative to their range but small in absolute terms.
		double firstPassMinGap = config.minScoreGap;
		if (scored.size() >= 4) {
			List<Double> semScores = new ArrayList<Double>(scored.size());
			for (ScoredEmbedding se : scored) {
				if (se.semanticScore >= minScore) {
					semScores.add(se.semanticScore);
				}
			}
			Collections.sort(semScores);
			if (semScores.size() >= 4) {
				double q1 = semScores.get(semScores.size() / 4);
				double q3 = semScores.get(3 * semScores.size() / 4);
				double iqr = q3 - q1;
				if (iqr < config.minScoreGap / 2) {
					Set<String> gapConcepts = new HashSet<String>();
					for (ScoredEmbedding se : scored) {
						String cn = ConceptNameUtil
								.extractConceptName(
										se.embedding
												.getTextContent());
						if (cn != null) {
							gapConcepts.add(cn);
						}
					}
					double fraction = (double) gapConcepts.size()
							/ scored.size();
					firstPassMinGap = iqr * fraction;
				}
			}
		}

		int adaptiveCutoff = findAdaptiveCutoff(scored, scored.size(),
				minScore, config.scoreGapMultiplier, firstPassMinGap);

		// Build the candidate set from the semantic cluster.
		List<ScoredEmbedding> candidates;
		if (adaptiveCutoff >= scored.size()) {
			candidates = new ArrayList<ScoredEmbedding>(scored);
		} else {
			List<Double> semanticDesc = new ArrayList<Double>(scored.size());
			for (ScoredEmbedding se : scored) {
				semanticDesc.add(se.semanticScore);
			}
			Collections.sort(semanticDesc, Collections.reverseOrder());
			double clusterThreshold = semanticDesc.get(adaptiveCutoff - 1);
			candidates = new ArrayList<ScoredEmbedding>();
			for (ScoredEmbedding se : scored) {
				// Two independent inclusion signals: semantic cluster
				// or full keyword match. The embedding model may assign
				// low similarity to obscure medical terminology but the
				// keyword match is conclusive evidence the record
				// belongs to the queried category.
				if (se.semanticScore >= clusterThreshold
						|| (config.keywordWeight > 0
								&& se.keywordScore >= bonusThreshold)) {
					candidates.add(se);
				}
			}
			Collections.sort(candidates, new Comparator<ScoredEmbedding>() {
				@Override
				public int compare(ScoredEmbedding a, ScoredEmbedding b) {
					return Double.compare(b.score, a.score);
				}
			});
		}

		return new CandidateBuildResult(candidates, adaptiveCutoff, minScore);
	}

}
