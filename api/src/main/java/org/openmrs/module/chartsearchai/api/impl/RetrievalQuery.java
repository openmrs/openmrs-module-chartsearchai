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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.impl.LlmInferenceService.FindSimilarResult;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static, dependency-free entry points for running a retrieval query end
 * to end against pre-loaded embeddings. Composes the query-preprocessor,
 * scoring engine, concept-similarity expansion, the filter pipeline in
 * {@link EmbeddingRankingPipeline}, type-indicator restriction, the
 * concept-rescue post-retrieval pipeline, the no-type-match / saturation
 * / floor-check guards, and the long-query truncation and dilution
 * rescues.
 *
 * <p>These methods exist so integration tests can drive the full pipeline
 * without DAO or Spring infrastructure — the same logic that
 * {@link LlmInferenceService#findSimilar(org.openmrs.Patient, String)}
 * runs in production.</p>
 */
final class RetrievalQuery {

	private RetrievalQuery() {
	}

	private static final Logger log = LoggerFactory.getLogger(RetrievalQuery.class);

	/**
	 * Static query pipeline that runs the exact same logic as
	 * {@link LlmInferenceService#findSimilar(org.openmrs.Patient, String)}
	 * but without DAO or Spring dependencies. Accepts pre-loaded embeddings
	 * and an embedding provider, making it directly callable from
	 * integration tests with zero simulation.
	 */
	static FindSimilarResult findSimilar(List<ChartEmbedding> allEmbeddings,
			EmbeddingProvider provider, String question,
			String queryPrefix, PipelineConfig config) {
		return findSimilarWithProfile(allEmbeddings, provider, question,
				queryPrefix, config);
	}

	static FindSimilarResult findSimilarWithProfile(
			List<ChartEmbedding> allEmbeddings,
			EmbeddingProvider provider, String question,
			String queryPrefix, PipelineConfig config) {
		String normalizedQuery = QueryPreprocessor.stripQueryStopwords(question);
		String[] queryTerms = QueryPreprocessor.extractQueryTerms(normalizedQuery);
		String embeddingQuery = QueryPreprocessor.buildEmbeddingQuery(normalizedQuery);
		float[] queryVector = provider.embedQuery(queryPrefix + embeddingQuery);

		// Identify "type indicator" query terms — terms that appear in any
		// structural embedding prefix. For these terms, only matches in the
		// prefix portion of a record's text count — body-text matches are
		// demoted as coincidental narrative mentions. Content terms that
		// don't appear in any prefix still match freely in the body.
		Set<String> typeIndicatorTerms = new HashSet<String>();
		if (queryTerms.length > 0) {
			for (String prefix
					: ChartSearchAiUtils.getAllEmbeddingPrefixes()) {
				String lowerPrefix = prefix.toLowerCase();
				String[] prefixWords = lowerPrefix.split("\\s+");
				for (String term : queryTerms) {
					if (typeIndicatorTerms.contains(term)) {
						continue;
					}
					if (SimilarityAndScoringEngine.termMatchesText(term, lowerPrefix, prefixWords)) {
						typeIndicatorTerms.add(term);
					}
				}
			}
		}

		// IDF-based term filtering: terms matching > 20% of records are
		// too generic and would flood keyword scoring with noise.
		String[] kwTerms = queryTerms;
		if (queryTerms.length > 2) {
			int totalDocs = 0;
			int[] docFreq = new int[queryTerms.length];
			for (int i = 0; i < allEmbeddings.size(); i++) {
				ChartEmbedding ce = allEmbeddings.get(i);
				if (ce.getEmbeddingVector() == null) continue;
				totalDocs++;
				String text = (ce.getTextContent() != null
						? ce.getTextContent() : "").toLowerCase();
				for (int t = 0; t < queryTerms.length; t++) {
					if (text.contains(queryTerms[t])) {
						docFreq[t]++;
					}
				}
			}
			if (totalDocs > 0) {
				double threshold = totalDocs * 0.2;
				List<String> filtered = new ArrayList<String>();
				int genericCount = 0;
				for (int t = 0; t < queryTerms.length; t++) {
					if (docFreq[t] <= threshold) {
						filtered.add(queryTerms[t]);
					} else {
						genericCount++;
					}
				}
				// Only activate when the query is dominated by generic
				// terms (> 50% filtered).
				if (!filtered.isEmpty()
						&& genericCount > queryTerms.length / 2) {
					kwTerms = filtered.toArray(
							new String[filtered.size()]);
					log.warn("IDF filter: {} -> {} terms (dropped "
							+ "generic terms from {})",
							queryTerms.length, kwTerms.length,
							Arrays.toString(queryTerms));
				}
			}
		}

		double[] semanticScores = new double[allEmbeddings.size()];
		double[] keywordScores = new double[allEmbeddings.size()];
		ChartEmbedding[] embeddings = new ChartEmbedding[allEmbeddings.size()];
		int validCount = 0;
		// First pass: collect valid records and compute semantic scores.
		// Keyword scoring is deferred until after concept-similarity
		// expansion so the (possibly replaced) kwTerms are scored.
		for (int i = 0; i < allEmbeddings.size(); i++) {
			ChartEmbedding ce = allEmbeddings.get(i);
			float[] vector = ce.getEmbeddingVector();
			if (vector == null || vector.length != queryVector.length) {
				continue;
			}
			embeddings[validCount] = ce;
			semanticScores[validCount] = ChartSearchAiUtils.cosineSimilarity(queryVector, vector);
			validCount++;
		}
		if (validCount < embeddings.length) {
			embeddings = Arrays.copyOf(embeddings, validCount);
		}
		// Concept-similarity expansion: when the query phrasing isn't in
		// any record but a concept name is semantically very close to the
		// query, replace kwTerms with the concept's tokens.
		String[] expandedKwTerms = ConceptKeywordMatching.expandKwTermsViaConceptSimilarity(
				kwTerms, queryVector, embeddings, provider, config);
		if (expandedKwTerms != kwTerms) {
			kwTerms = expandedKwTerms;
			// Type-indicator restriction was computed from the original
			// query terms. After replacement clear it so keyword matching
			// is unconstrained on the replacement tokens.
			typeIndicatorTerms = new HashSet<String>();
		}
		// Second pass: compute keyword scores with the (possibly
		// expanded) kwTerms.
		for (int i = 0; i < embeddings.length; i++) {
			ChartEmbedding ce = embeddings[i];
			// Strip synonym parentheticals before keyword scoring so that
			// "(syn. ...)" doesn't cause false matches.
			String body = ConceptNameUtil.stripSynonyms(ce.getTextContent());
			String keywordText = ChartSearchAiUtils.buildPrefixedText(
					ce.getResourceType(), body);
			if (typeIndicatorTerms.isEmpty()) {
				keywordScores[i] = SimilarityAndScoringEngine.computeKeywordScore(
						kwTerms, keywordText);
			} else {
				keywordScores[i] = SimilarityAndScoringEngine.computeKeywordScoreRestricted(
						kwTerms, keywordText, body,
						typeIndicatorTerms);
			}
		}
		if (validCount < semanticScores.length) {
			semanticScores = Arrays.copyOf(semanticScores, validCount);
			keywordScores = Arrays.copyOf(keywordScores, validCount);
		}

		// Compute noise profile using hint-stripped re-embeddings for
		// cross-concept similarity. Reuse pre-computed profile from config
		// when available.
		ModelNoiseProfile noiseProfile = config.noiseProfile != null
				&& !config.noiseProfile.isConservativeDefault()
				? config.noiseProfile
				: ModelNoiseProfile.compute(embeddings, provider);
		log.warn("NoiseProfile: Q1={} median={} mean={} P95={} "
				+ "intraMean={} floor={}",
				String.format("%.4f", noiseProfile.noiseQ1),
				String.format("%.4f", noiseProfile.noiseMedian),
				String.format("%.4f", noiseProfile.noiseMean),
				String.format("%.4f", noiseProfile.noiseP95),
				String.format("%.4f", noiseProfile.intraConceptMean),
				String.format("%.4f", noiseProfile.absoluteSimilarityFloor()));
		PipelineConfig profiledConfig =
				config.withNoiseProfile(noiseProfile);

		int[] gapCutoffOut = new int[1];
		List<ChartEmbedding> pipelineResult = EmbeddingRankingPipeline.filterPipeline(
				semanticScores, keywordScores, embeddings,
				queryTerms, queryTerms.length, profiledConfig,
				gapCutoffOut);
		int gapCutoff = gapCutoffOut[0];

		int keywordMatchCount = 0;
		for (int i = 0; i < validCount; i++) {
			if (keywordScores[i] > 0) {
				keywordMatchCount++;
			}
		}

		// Query truncation retry: when the pipeline returns empty for a
		// long query (>3 terms), retry with the first 2 core terms and a
		// fresh embedding.
		if ((pipelineResult == null || pipelineResult.isEmpty())
				&& queryTerms.length > 3) {
			String[] coreTerms = Arrays.copyOf(
					queryTerms, Math.min(2, queryTerms.length));
			String coreQuery = String.join(" ", coreTerms);
			float[] coreVector = provider.embedQuery(
					queryPrefix + coreQuery);

			double[] coreSemantic = new double[validCount];
			double[] coreKw = new double[validCount];
			for (int i = 0; i < validCount; i++) {
				coreSemantic[i] = ChartSearchAiUtils.cosineSimilarity(
						coreVector, embeddings[i].getEmbeddingVector());
				String body = ConceptNameUtil.stripSynonyms(
						embeddings[i].getTextContent());
				String kwText = ChartSearchAiUtils.buildPrefixedText(
						embeddings[i].getResourceType(), body);
				coreKw[i] = SimilarityAndScoringEngine.computeKeywordScore(coreTerms, kwText);
			}

			int[] retryGapCutoff = new int[1];
			pipelineResult = EmbeddingRankingPipeline.filterPipeline(coreSemantic, coreKw,
					embeddings, coreTerms, coreTerms.length,
					profiledConfig, retryGapCutoff);
			if (pipelineResult != null && !pipelineResult.isEmpty()) {
				gapCutoff = retryGapCutoff[0];
				log.warn("Query truncation retry: '{}' -> '{}', "
						+ "found {} results",
						String.join(" ", queryTerms), coreQuery,
						pipelineResult.size());
				keywordMatchCount = 0;
				for (int i = 0; i < validCount; i++) {
					if (coreKw[i] > 0) keywordMatchCount++;
					semanticScores[i] = coreSemantic[i];
				}
			}
		}

		// Dilution rescue: long queries (>3 terms) may dilute the
		// embedding. Embed the first 2 terms as a focused query and check
		// if the improvement in max similarity exceeds 1 std of the
		// focused score distribution — a statistically significant
		// signal gain that indicates the full query lost a concept to
		// dilution.
		if (pipelineResult != null && !pipelineResult.isEmpty()
				&& queryTerms.length > 3) {
			double maxFullSem = 0;
			for (int i = 0; i < validCount; i++) {
				if (semanticScores[i] > maxFullSem) {
					maxFullSem = semanticScores[i];
				}
			}
			String[] coreTerms2 = Arrays.copyOf(
					queryTerms, Math.min(2, queryTerms.length));
			String coreQuery2 = String.join(" ", coreTerms2);
			float[] coreVector2 = provider.embedQuery(
					queryPrefix + coreQuery2);
			double[] coreSem2 = new double[validCount];
			double maxCoreSem = 0;
			double sumCoreSem = 0;
			double sumCoreSemSq = 0;
			for (int i = 0; i < validCount; i++) {
				double sim = ChartSearchAiUtils.cosineSimilarity(coreVector2,
						embeddings[i].getEmbeddingVector());
				coreSem2[i] = sim;
				if (sim > maxCoreSem) maxCoreSem = sim;
				sumCoreSem += sim;
				sumCoreSemSq += sim * sim;
			}
			double meanCore = validCount > 0
					? sumCoreSem / validCount : 0;
			double stdCore = validCount > 1
					? Math.sqrt(Math.max(0,
							sumCoreSemSq / validCount
									- meanCore * meanCore))
					: 0;
			if (maxCoreSem - maxFullSem > stdCore) {
				double[] coreKw2 = new double[validCount];
				for (int i = 0; i < validCount; i++) {
					String body = ConceptNameUtil.stripSynonyms(
							embeddings[i].getTextContent());
					String kwText =
							ChartSearchAiUtils.buildPrefixedText(
									embeddings[i].getResourceType(),
									body);
					coreKw2[i] = SimilarityAndScoringEngine.computeKeywordScore(
							coreTerms2, kwText);
				}
				int[] mergeGapCutoff = new int[1];
				List<ChartEmbedding> mergeResult = EmbeddingRankingPipeline.filterPipeline(
						coreSem2, coreKw2, embeddings, coreTerms2,
						coreTerms2.length, profiledConfig,
						mergeGapCutoff);
				if (mergeResult != null
						&& !mergeResult.isEmpty()
						&& mergeResult.size()
								<= ChartSearchAiConstants
										.ADAPTIVE_MIN_RECORDS) {
					Set<ChartEmbedding> existing =
							Collections.newSetFromMap(
									new IdentityHashMap<
											ChartEmbedding,
											Boolean>());
					existing.addAll(pipelineResult);
					boolean anyOverlap = false;
					for (ChartEmbedding ce : mergeResult) {
						if (existing.contains(ce)) {
							anyOverlap = true;
							break;
						}
					}
					if (!anyOverlap) {
						List<ChartEmbedding> merged =
								new ArrayList<>(pipelineResult);
						merged.addAll(mergeResult);
						log.warn("Dilution rescue: merged {}"
								+ " focused results into {} "
								+ "pipeline results for '{}'",
								mergeResult.size(),
								pipelineResult.size(),
								coreQuery2);
						pipelineResult = merged;
					}
				}
			}
		}

		// When type indicators are present, remove straggler records
		// whose structural prefix doesn't match any type indicator term.
		// Only fires when >75% of pipeline results already match — this
		// targets the case where a few wrong-type records leak through
		// on semantic similarity alone.
		int typeMatchCount = -1;
		if (!typeIndicatorTerms.isEmpty()
				&& pipelineResult != null && !pipelineResult.isEmpty()) {
			typeMatchCount = 0;
			for (ChartEmbedding ce : pipelineResult) {
				if (ConceptKeywordMatching.matchesTypeIndicator(ce, typeIndicatorTerms)) {
					typeMatchCount++;
				}
			}
			if (typeMatchCount > 0
					&& typeMatchCount > pipelineResult.size() * 2 / 3) {
				int beforeFilter = pipelineResult.size();
				List<ChartEmbedding> filtered = new ArrayList<>();
				for (ChartEmbedding ce : pipelineResult) {
					if (ConceptKeywordMatching.matchesTypeIndicator(ce, typeIndicatorTerms)) {
						filtered.add(ce);
					}
				}
				pipelineResult = filtered;
				if (pipelineResult.size() != beforeFilter) {
					log.warn("Type-indicator filter {}: {} -> {}",
							typeIndicatorTerms, beforeFilter,
							pipelineResult.size());
				}
			}
		}

		// Concept-name re-ranking for zero-keyword queries.
		boolean noTypeMatch = !typeIndicatorTerms.isEmpty()
				&& typeMatchCount == 0;
		if (pipelineResult != null && !pipelineResult.isEmpty()
				&& keywordMatchCount == 0 && queryTerms.length > 0) {
			List<ChartEmbedding> preGateCandidates = pipelineResult;
			int beforeRerank = pipelineResult.size();
			pipelineResult = ConceptNameReranker.rerankByConceptName(pipelineResult,
					embeddings, validCount, queryVector, provider,
					noiseProfile, profiledConfig, noTypeMatch);
			if (pipelineResult.size() != beforeRerank) {
				StringBuilder kept = new StringBuilder();
				for (ChartEmbedding ce : pipelineResult) {
					if (kept.length() > 0) kept.append(", ");
					kept.append(ce.getResourceType()).append(":")
							.append(ce.getResourceUuid());
				}
				log.warn("Concept-name rerank {}: {} -> {} [{}]",
						Arrays.toString(queryTerms),
						beforeRerank, pipelineResult.size(), kept);
			}

			// Type-indicator rescue: when the gate rejects all
			// candidates but type indicators exist, rescue
			// candidates whose record type matches the indicated
			// type from the pre-gate set.
			if (pipelineResult.isEmpty()
					&& !typeIndicatorTerms.isEmpty()) {
				List<ChartEmbedding> rescued = new ArrayList<>();
				for (ChartEmbedding ce : preGateCandidates) {
					if (ConceptKeywordMatching.matchesTypeIndicator(ce,
							typeIndicatorTerms)) {
						rescued.add(ce);
					}
				}
				if (!rescued.isEmpty()) {
					pipelineResult = rescued;
					log.warn("Type-indicator rescue after "
							+ "concept-name gate: 0 -> {} for {}",
							rescued.size(), typeIndicatorTerms);
				}
			}

		}

		// No-type-match check: when type indicators are present but ALL
		// pipeline results are wrong type AND no records of the indicated
		// type exist in the dataset, the patient has no records of the
		// queried type.
		if (noTypeMatch
				&& pipelineResult != null
				&& !pipelineResult.isEmpty()) {
			boolean bodyMentionsType = false;
			for (ChartEmbedding ce : pipelineResult) {
				String body = (ce.getTextContent() != null
						? ce.getTextContent() : "").toLowerCase();
				String[] bodyWords = body.split("\\s+");
				for (String term : typeIndicatorTerms) {
					if (SimilarityAndScoringEngine.termMatchesText(term, body, bodyWords)) {
						bodyMentionsType = true;
						break;
					}
				}
				if (bodyMentionsType) break;
			}
			if (!bodyMentionsType) {
				List<ChartEmbedding> typeMatching =
						new ArrayList<>();
				for (int i = 0; i < validCount; i++) {
					if (ConceptKeywordMatching.matchesTypeIndicator(embeddings[i],
							typeIndicatorTerms)) {
						typeMatching.add(embeddings[i]);
					}
				}
				if (typeMatching.isEmpty()) {
					log.warn("No-type-match empty: {} wrong-type "
							+ "results, 0 type-matching in "
							+ "dataset — returning empty for {}",
							pipelineResult.size(),
							typeIndicatorTerms);
					pipelineResult = Collections.emptyList();
				} else if (typeMatching.size()
						<= ChartSearchAiConstants
								.ADAPTIVE_MIN_RECORDS) {
					log.warn("No-type-match replacement: "
							+ "replacing {} wrong-type with "
							+ "{} type-matching records for {}",
							pipelineResult.size(),
							typeMatching.size(),
							typeIndicatorTerms);
					pipelineResult = typeMatching;
				}
			}
		}

		// Type-indicator full-scan rescue: when the pipeline returns
		// empty but type indicators exist, scan ALL embeddings for
		// records matching the indicated type.
		if ((pipelineResult == null || pipelineResult.isEmpty())
				&& !typeIndicatorTerms.isEmpty()) {
			List<ChartEmbedding> rescued = new ArrayList<>();
			for (int i = 0; i < validCount; i++) {
				if (ConceptKeywordMatching.matchesTypeIndicator(embeddings[i],
						typeIndicatorTerms)) {
					rescued.add(embeddings[i]);
				}
			}
			if (!rescued.isEmpty()
					&& rescued.size()
					<= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS) {
				pipelineResult = rescued;
				log.warn("Type-indicator full-scan rescue: "
						+ "found {} records for {}",
						rescued.size(), typeIndicatorTerms);
			}
		}

		// Concept-name floor check: when pipeline results have no
		// keyword anchoring, the results rely entirely on embedding
		// similarity. If all results share a single concept whose name
		// is below the noise floor, return empty.
		if (!pipelineResult.isEmpty()
				&& queryTerms.length > 0) {
			boolean pipelineHasKwAnchoring = false;
			if (keywordMatchCount > 0) {
				Set<ChartEmbedding> kwFloorSet =
						Collections.newSetFromMap(
								new IdentityHashMap<
										ChartEmbedding, Boolean>());
				kwFloorSet.addAll(pipelineResult);
				for (int i = 0; i < validCount; i++) {
					if (kwFloorSet.contains(embeddings[i])
							&& keywordScores[i] > 0) {
						pipelineHasKwAnchoring = true;
						break;
					}
				}
			}
			if (!pipelineHasKwAnchoring) {
				Set<String> seenConcepts = new HashSet<>();
				for (ChartEmbedding ce : pipelineResult) {
					String name = ConceptNameUtil.extractConceptName(
							ce.getTextContent());
					if (name != null) {
						seenConcepts.add(name);
					}
				}
				if (seenConcepts.size() == 1) {
					String conceptName =
							seenConcepts.iterator().next();
					float[] nameVec =
							provider.embedQuery(conceptName);
					double conceptSim = ChartSearchAiUtils.cosineSimilarity(
							queryVector, nameVec);
					double floorWithMargin = noiseProfile
							.absoluteSimilarityFloor()
							* profiledConfig.conceptFloorMargin;
					if (conceptSim < floorWithMargin) {
						log.warn("Concept-name floor check: "
								+ "sim({})={} < floor*{}={}, "
								+ "returning empty for {}",
								conceptName,
								String.format("%.4f", conceptSim),
								String.format("%.2f",
										profiledConfig.conceptFloorMargin),
								String.format("%.4f",
										floorWithMargin),
								Arrays.toString(
										queryTerms));
						pipelineResult = Collections.emptyList();
					}
				}
			}
		}

		// Gap-saturation check: when kwCount=0 and the gap analysis
		// found no meaningful gap, the embedding scores are
		// undifferentiated — every record looks equally relevant.
		// Catches queries about topics not in the patient's chart.
		if (keywordMatchCount == 0
				&& !pipelineResult.isEmpty()
				&& validCount > 0
				&& profiledConfig.gapSaturationThreshold > 0) {
			double gapRatio = (double) gapCutoff / validCount;
			if (gapRatio > profiledConfig.gapSaturationThreshold) {
				log.warn("Gap-saturation check: "
						+ "gapCutoff={}/{} ({}) — "
						+ "returning empty for {}",
						gapCutoff, validCount,
						String.format("%.0f%%",
								gapRatio * 100),
						Arrays.toString(
								queryTerms));
				pipelineResult = Collections.emptyList();
			}
		}

		return new FindSimilarResult(pipelineResult, noiseProfile,
				keywordMatchCount);
	}

	/**
	 * Composed retrieval pipeline: runs {@link #findSimilar}, then
	 * {@link LlmInferenceService#postRetrievalPipeline} — the same
	 * post-retrieval sequence that production's {@code filterAndSerialize}
	 * runs before handing records to {@code chartSerializer.serialize}.
	 */
	static List<SerializedRecord> findRelevantRecords(
			List<ChartEmbedding> allEmbeddings,
			List<SerializedRecord> allRecords,
			EmbeddingProvider provider, String question,
			String queryPrefix, PipelineConfig config) {
		FindSimilarResult fsResult = findSimilar(allEmbeddings, provider,
				question, queryPrefix, config);
		List<ChartEmbedding> similar = fsResult.records;
		if (similar == null) {
			return null;
		}
		if (similar.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> relevantKeys = new HashSet<String>();
		for (ChartEmbedding ce : similar) {
			relevantKeys.add(ChartSearchAiUtils.resourceKey(
					ce.getResourceType(), ce.getResourceUuid()));
		}

		log.warn("findRelevantRecords: {} embeddings -> {} keys",
				similar.size(), relevantKeys.size());

		return ConceptRescueAndFilter.postRetrievalPipeline(allRecords, relevantKeys, question,
				similar.size(), fsResult.keywordMatchCount,
				provider,
				ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX);
	}
}
