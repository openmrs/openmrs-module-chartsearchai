/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Hybrid retrieval pipeline that combines Lucene BM25 text search with
 * embedding-based kNN semantic search using Reciprocal Rank Fusion (RRF).
 * Provides the same hybrid search quality as the Elasticsearch pipeline
 * but without requiring any external services — everything runs in-process.
 *
 * <p>Selected via the {@code chartsearchai.retrieval.pipeline} global
 * property set to {@code hybrid}.
 */
@Component
public class HybridRetriever {

	private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

	@Autowired
	private LuceneIndexer luceneIndexer;

	@Autowired
	private EmbeddingIndexer embeddingIndexer;

	@Autowired
	private ChartSearchAiDAO dao;

	@Autowired
	@Qualifier("chartSearchAi.embeddingProvider")
	private EmbeddingProvider embeddingProvider;

	/**
	 * Ensures both Lucene and embedding indexes exist for the patient.
	 */
	public void ensureIndexed(Patient patient) {
		if (!luceneIndexer.hasIndex(patient)) {
			log.info("Hybrid: no Lucene index for patient [id={}], indexing now",
					patient.getPatientId());
			luceneIndexer.indexPatient(patient);
		}
		List<ChartEmbedding> embeddings = dao.getByPatient(patient);
		if (embeddings == null || embeddings.isEmpty()) {
			log.info("Hybrid: no embeddings for patient [id={}], indexing now",
					patient.getPatientId());
			embeddingIndexer.indexPatient(patient);
		}
	}

	/**
	 * Runs hybrid search: BM25 via Lucene + kNN via embeddings, fused with
	 * RRF. Returns resource keys (type:id) for the top results.
	 *
	 * @param patient the patient to search
	 * @param queryText the normalized query text
	 * @param queryPrefix prefix for the query embedding (e.g. "" or "search_query: ")
	 * @param maxResults maximum number of results to return
	 * @return set of "resourceType:resourceUuid" keys for the top results
	 */
	public Set<String> search(Patient patient, String queryText,
			String queryPrefix, int maxResults) {
		int windowSize = ChartSearchAiConstants.RRF_RANK_WINDOW_SIZE;

		// BM25 ranked list from Lucene
		List<LuceneIndexer.LuceneSearchResult> bm25Results =
				luceneIndexer.search(patient, queryText, windowSize);
		List<String> bm25Ranked = new ArrayList<String>(bm25Results.size());
		for (LuceneIndexer.LuceneSearchResult r : bm25Results) {
			bm25Ranked.add(ChartSearchAiUtils.resourceKey(r.getResourceType(), r.getResourceUuid()));
		}

		// kNN ranked list from embeddings — defer ranking until we know
		// whether BM25 returned results, to avoid wasted cosine similarity
		// computations when the kNN fallback path recomputes them anyway.
		List<ChartEmbedding> allEmbeddings = dao.getByPatient(patient);
		float[] queryVector = null;
		if (allEmbeddings != null && !allEmbeddings.isEmpty()) {
			queryVector = embeddingProvider.embedQuery(queryPrefix + queryText);
		}

		if (bm25Ranked.isEmpty()) {
			// No keyword matches — fall back to kNN results that pass a
			// minimum similarity threshold plus a z-score gate, then
			// coherence filtering to remove topic outliers.
			if (queryVector == null || allEmbeddings == null || allEmbeddings.isEmpty()) {
				log.debug("Hybrid: no BM25 matches and no embeddings for query '{}', "
						+ "returning empty", queryText);
				return new LinkedHashSet<String>();
			}

			// Compute all similarities once upfront for both z-score
			// distribution and threshold filtering
			List<SimilarityResult> allScored =
					new ArrayList<SimilarityResult>(allEmbeddings.size());
			double[] sims = new double[allEmbeddings.size()];
			for (int i = 0; i < allEmbeddings.size(); i++) {
				ChartEmbedding ce = allEmbeddings.get(i);
				float[] vec = ce.getEmbeddingVector();
				double sim = ChartSearchAiUtils.cosineSimilarity(
						queryVector, vec);
				sims[i] = sim;
				allScored.add(new SimilarityResult(
						ChartSearchAiUtils.resourceKey(ce.getResourceType(), ce.getResourceUuid()),
						sim, vec));
			}

			double effectiveMin = ChartSearchAiConstants.KNN_MIN_SIMILARITY;
			if (allEmbeddings.size() >= ChartSearchAiConstants.MIN_RECORDS_FOR_Z_SCORE) {
				effectiveMin = ChartSearchAiUtils.zScoreFloor(sims,
						effectiveMin,
						ChartSearchAiConstants.KNN_FALLBACK_Z_SCORE);
			}

			// Filter using pre-computed similarities
			List<SimilarityResult> zScoreSurvivors =
					new ArrayList<SimilarityResult>();
			for (SimilarityResult sr : allScored) {
				if (sr.similarity >= effectiveMin) {
					zScoreSurvivors.add(sr);
				}
			}
			// If z-score gate is too aggressive, fall back to absolute floor
			if (zScoreSurvivors.size()
					< ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS
					&& effectiveMin > ChartSearchAiConstants.KNN_MIN_SIMILARITY) {
				zScoreSurvivors.clear();
				for (SimilarityResult sr : allScored) {
					if (sr.similarity >= ChartSearchAiConstants.KNN_MIN_SIMILARITY) {
						zScoreSurvivors.add(sr);
					}
				}
			}
			zScoreSurvivors.sort(new Comparator<SimilarityResult>() {
				@Override
				public int compare(SimilarityResult a, SimilarityResult b) {
					return Double.compare(b.similarity, a.similarity);
				}
			});
			if (zScoreSurvivors.size() > maxResults) {
				zScoreSurvivors = zScoreSurvivors.subList(0, maxResults);
			}

			// Apply coherence filtering to remove topic outliers
			if (zScoreSurvivors.size() >= 3) {
				float[][] vectors = new float[zScoreSurvivors.size()][];
				for (int i = 0; i < zScoreSurvivors.size(); i++) {
					vectors[i] = zScoreSurvivors.get(i).vector;
				}
				boolean[] keep = ChartSearchAiUtils
						.filterByCoherence(vectors);
				List<SimilarityResult> coherent =
						new ArrayList<SimilarityResult>();
				for (int i = 0; i < zScoreSurvivors.size(); i++) {
					if (keep[i]) {
						coherent.add(zScoreSurvivors.get(i));
					}
				}
				log.debug("Hybrid: coherence filter reduced {} to {} "
						+ "candidates for query '{}'",
						zScoreSurvivors.size(), coherent.size(), queryText);
				zScoreSurvivors = coherent;
			}

			List<String> filtered = new ArrayList<String>(
					zScoreSurvivors.size());
			for (SimilarityResult sr : zScoreSurvivors) {
				filtered.add(sr.key);
			}
			log.debug("Hybrid: no BM25 matches for query '{}', kNN fallback "
					+ "returned {} results after z-score gate and coherence "
					+ "filtering", queryText, filtered.size());
			return new LinkedHashSet<String>(filtered);
		}

		List<String> knnRanked;
		if (queryVector == null || allEmbeddings == null || allEmbeddings.isEmpty()) {
			knnRanked = new ArrayList<String>();
		} else {
			knnRanked = rankByCosineSimilarity(allEmbeddings, queryVector, windowSize);
		}

		log.debug("Hybrid: BM25 returned {} results, kNN returned {} results for query '{}'",
				bm25Ranked.size(), knnRanked.size(), queryText);

		List<String> fused = fuseRRF(bm25Ranked, knnRanked,
				ChartSearchAiConstants.RRF_RANK_CONSTANT, maxResults);

		return new LinkedHashSet<String>(fused);
	}

	/**
	 * Ranks embeddings by cosine similarity to the query vector and returns
	 * the top-N as resource keys. Uses the same cosine similarity formula
	 * as the embedding pipeline.
	 */
	static List<String> rankByCosineSimilarity(List<ChartEmbedding> embeddings,
			float[] queryVector, int maxResults) {
		List<Map.Entry<String, Double>> scored =
				new ArrayList<Map.Entry<String, Double>>(embeddings.size());
		for (ChartEmbedding ce : embeddings) {
			float[] vec = ce.getEmbeddingVector();
			double sim = ChartSearchAiUtils.cosineSimilarity(queryVector, vec);
			String key = ChartSearchAiUtils.resourceKey(ce.getResourceType(), ce.getResourceUuid());
			scored.add(new java.util.AbstractMap.SimpleEntry<String, Double>(key, sim));
		}

		scored.sort(new Comparator<Map.Entry<String, Double>>() {
			@Override
			public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
				return Double.compare(b.getValue(), a.getValue());
			}
		});

		List<String> result = new ArrayList<String>(
				Math.min(maxResults, scored.size()));
		for (int i = 0; i < Math.min(maxResults, scored.size()); i++) {
			result.add(scored.get(i).getKey());
		}
		return result;
	}

	/**
	 * Holds a similarity result with its key, similarity, and embedding
	 * vector for coherence filtering.
	 */
	static class SimilarityResult {
		final String key;
		final double similarity;
		final float[] vector;

		SimilarityResult(String key, double similarity, float[] vector) {
			this.key = key;
			this.similarity = similarity;
			this.vector = vector;
		}
	}

	/**
	 * Reciprocal Rank Fusion: merges two ranked lists by assigning each
	 * result a score of {@code 1 / (rankConstant + rank)} (1-based rank)
	 * and summing across lists. Results appearing in both lists get a
	 * boost from both contributions.
	 *
	 * <p>This is a pure function with no side effects, making it easy to
	 * test with hand-crafted ranked lists.
	 *
	 * @param bm25Ranked keys from BM25, ordered by BM25 score descending
	 * @param knnRanked keys from kNN, ordered by similarity descending
	 * @param rankConstant the k parameter (typically 60)
	 * @param maxResults maximum results to return
	 * @return merged keys in RRF score order (descending)
	 */
	public static List<String> fuseRRF(List<String> bm25Ranked,
			List<String> knnRanked, int rankConstant, int maxResults) {
		Map<String, Double> scores = new HashMap<String, Double>();

		for (int i = 0; i < bm25Ranked.size(); i++) {
			String key = bm25Ranked.get(i);
			double rrfScore = 1.0 / (rankConstant + i + 1);
			Double existing = scores.get(key);
			scores.put(key, (existing != null ? existing : 0.0) + rrfScore);
		}
		for (int i = 0; i < knnRanked.size(); i++) {
			String key = knnRanked.get(i);
			double rrfScore = 1.0 / (rankConstant + i + 1);
			Double existing = scores.get(key);
			scores.put(key, (existing != null ? existing : 0.0) + rrfScore);
		}

		List<Map.Entry<String, Double>> entries =
				new ArrayList<Map.Entry<String, Double>>(scores.entrySet());
		entries.sort(new Comparator<Map.Entry<String, Double>>() {
			@Override
			public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
				return Double.compare(b.getValue(), a.getValue());
			}
		});

		List<String> result = new ArrayList<String>(
				Math.min(maxResults, entries.size()));
		for (int i = 0; i < Math.min(maxResults, entries.size()); i++) {
			result.add(entries.get(i).getKey());
		}
		return result;
	}

}
