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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ElasticsearchIndexer;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexer;
import org.openmrs.module.chartsearchai.api.HybridRetriever;
import org.openmrs.module.chartsearchai.api.LuceneIndexer;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.impl.LlmInferenceService.FindSimilarResult;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Picks the retrieval pipeline (embeddings, Lucene, Elasticsearch, hybrid)
 * for a patient query and returns the assembled {@link PatientChart}. Owns
 * the indexer collaborators, the chart cache, the patient-scoped noise
 * profile cache, and the entry into the embedding ranking pipeline. The
 * containing {@link LlmInferenceService} delegates here for chart
 * assembly and otherwise focuses on the LLM call and citation handling.
 */
@Service("chartSearchAi.chartBuildingStrategy")
class ChartBuildingStrategy {

	private static final Logger log = LoggerFactory.getLogger(ChartBuildingStrategy.class);

	private static final int NOISE_PROFILE_CACHE_MAX_SIZE = 100;

	@Autowired
	private PatientRecordLoader recordLoader;

	@Autowired
	private PatientChartSerializer chartSerializer;

	@Autowired
	@Qualifier("chartSearchAi.embeddingProvider")
	private EmbeddingProvider embeddingProvider;

	@Autowired
	private ChartSearchAiDAO dao;

	@Autowired
	private EmbeddingIndexer embeddingIndexer;

	@Autowired
	private LuceneIndexer luceneIndexer;

	@Autowired
	private ElasticsearchIndexer elasticsearchIndexer;

	@Autowired
	private HybridRetriever hybridRetriever;

	@Autowired
	@Qualifier("chartSearchAi.chartCache")
	private ChartCache chartCache;

	private final ConcurrentHashMap<String, ModelNoiseProfile> noiseProfileCache =
			new ConcurrentHashMap<String, ModelNoiseProfile>();

	private void invalidateNoiseProfileCache(Patient patient) {
		String prefix = patient.getPatientId() + ":";
		for (String key : noiseProfileCache.keySet()) {
			if (key.startsWith(prefix)) {
				noiseProfileCache.remove(key);
			}
		}
	}

	PatientChart buildChart(Patient patient, String question) {
		if (!usePreFilter()) {
			PatientChart cached = chartCache.get(patient);
			if (cached != null) {
				return cached;
			}
			PatientChart chart = chartSerializer.serialize(patient);
			chartCache.put(patient, chart);
			return chart;
		}

		if (isHybridPipeline()) {
			return buildChartWithHybrid(patient, question);
		}
		if (isElasticsearchPipeline()) {
			return buildChartWithElasticsearch(patient, question);
		}
		if (isLucenePipeline()) {
			return buildChartWithLucene(patient, question);
		}
		return buildChartWithEmbeddings(patient, question);
	}

	private PatientChart buildChartWithEmbeddings(Patient patient, String question) {
		// findSimilar returns null when no embeddings exist (needs indexing),
		// or an empty list when embeddings exist but nothing matched the query.
		FindSimilarResult fsResult = findSimilarResult(patient, question);

		if (fsResult == null || fsResult.records == null) {
			log.info("No embeddings found for patient [id={}], indexing now", patient.getPatientId());
			try {
				embeddingIndexer.indexPatient(patient);
				// Invalidate cached noise profile — embeddings have changed
				invalidateNoiseProfileCache(patient);
				fsResult = findSimilarResult(patient, question);
			}
			catch (Exception e) {
				log.error("Failed to index patient [id={}], falling back to full chart",
						patient.getPatientId(), e);
			}
		}

		if (fsResult == null || fsResult.records == null) {
			log.debug("Still no embeddings after indexing attempt, falling back to full chart");
			return chartSerializer.serialize(patient);
		}

		List<ChartEmbedding> similar = fsResult.records;

		if (similar.isEmpty()) {
			log.debug("No records matched the query '{}' for patient [id={}], returning empty chart",
					question, patient.getPatientId());
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		Set<String> relevantKeys = new HashSet<String>();
		for (ChartEmbedding ce : similar) {
			relevantKeys.add(ChartSearchAiUtils.resourceKey(ce.getResourceType(), ce.getResourceUuid()));
		}

		log.warn("findSimilar returned {} records for query '{}' patient [id={}]: {}",
				similar.size(), question, patient.getPatientId(), relevantKeys);

		return filterAndSerialize(patient, question, relevantKeys,
				similar.size(), fsResult.keywordMatchCount);
	}

	private PatientChart buildChartWithLucene(Patient patient, String question) {
		if (!luceneIndexer.hasIndex(patient)) {
			log.info("No Lucene index for patient [id={}], indexing now", patient.getPatientId());
			try {
				luceneIndexer.indexPatient(patient);
			}
			catch (Exception e) {
				log.error("Failed to Lucene-index patient [id={}], falling back to full chart",
						patient.getPatientId(), e);
				return chartSerializer.serialize(patient);
			}
		}

		String normalizedQuery = QueryPreprocessor.stripQueryStopwords(question);
		if (normalizedQuery.isEmpty()) {
			return chartSerializer.serialize(patient);
		}

		List<LuceneIndexer.LuceneSearchResult> results = luceneIndexer.search(
				patient, normalizedQuery, PipelineSettings.getTopK() * 10);

		if (results.isEmpty()) {
			log.debug("Lucene returned no results for query '{}', returning empty chart",
					normalizedQuery);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		Set<String> relevantKeys = new HashSet<String>();
		for (LuceneIndexer.LuceneSearchResult result : results) {
			relevantKeys.add(ChartSearchAiUtils.resourceKey(result.getResourceType(), result.getResourceUuid()));
		}

		log.debug("Lucene returned {} results for query '{}'",
				relevantKeys.size(), normalizedQuery);

		return filterAndSerialize(patient, question, relevantKeys);
	}

	private PatientChart buildChartWithElasticsearch(Patient patient, String question) {
		if (!elasticsearchIndexer.isAvailable()) {
			log.warn("Elasticsearch not available, falling back to embedding pipeline");
			return buildChartWithEmbeddings(patient, question);
		}

		if (!elasticsearchIndexer.hasIndex(patient)) {
			log.info("No ES index for patient [id={}], indexing now", patient.getPatientId());
			try {
				elasticsearchIndexer.indexPatient(patient);
			}
			catch (Exception e) {
				log.error("Failed to ES-index patient [id={}], falling back to embeddings",
						patient.getPatientId(), e);
				return buildChartWithEmbeddings(patient, question);
			}
		}

		String normalizedQuery = QueryPreprocessor.stripQueryStopwords(question);
		if (normalizedQuery.isEmpty()) {
			return chartSerializer.serialize(patient);
		}

		String embeddingInput = QueryPreprocessor.prepareEmbeddingInput(question, PipelineSettings.getQueryPrefix());
		float[] queryVector;
		try {
			queryVector = embeddingProvider.embedQuery(embeddingInput);
		}
		catch (Exception e) {
			log.error("Failed to embed query for ES search, falling back to embeddings", e);
			return buildChartWithEmbeddings(patient, question);
		}

		// ES search: RRF combines BM25 keyword matches with kNN semantic
		// matches. Used to identify candidates that may have keyword signal
		// the pure-embedding path would miss.
		List<ElasticsearchIndexer.ElasticsearchSearchResult> esResults =
				elasticsearchIndexer.search(patient, normalizedQuery, queryVector,
						PipelineSettings.getTopK() * ChartSearchAiConstants.ES_FETCH_MULTIPLIER);

		// Run the FULL embedding filter pipeline on all patient embeddings
		// — not just the ES-returned subset. filterPipeline uses adaptive
		// gap-based filtering that needs the full score distribution to
		// distinguish signal from noise.
		List<ElasticsearchIndexer.ElasticsearchSearchResult> allEsEmbeddings =
				elasticsearchIndexer.fetchAllPatientEmbeddings(patient.getPatientId());
		List<ChartEmbedding> allEmbeddings = esResultsToChartEmbeddings(allEsEmbeddings);

		List<ChartEmbedding> pipelineFiltered = null;
		if (!allEmbeddings.isEmpty()) {
			FindSimilarResult fsResult = RetrievalQuery.findSimilar(allEmbeddings,
					embeddingProvider, question, PipelineSettings.getQueryPrefix(),
					new PipelineConfig(PipelineSettings.getKeywordWeight(),
							PipelineSettings.getScoreGapMultiplier(), PipelineSettings.getMinScoreGap(),
							PipelineSettings.getGapValidationCosineThreshold(),
							PipelineSettings.getSimilarityRatio()));
			pipelineFiltered = fsResult.records;
		}

		// Use only the pipeline-filtered records — the full-corpus
		// findSimilar already includes keyword scoring, so BM25-only
		// matches from ES are also captured.
		Set<String> relevantKeys = new HashSet<String>();
		if (pipelineFiltered != null) {
			for (ChartEmbedding ce : pipelineFiltered) {
				relevantKeys.add(ChartSearchAiUtils.resourceKey(
						ce.getResourceType(), ce.getResourceUuid()));
			}
		}

		if (relevantKeys.isEmpty()) {
			log.debug("Elasticsearch: no records survived filtering for query '{}'",
					normalizedQuery);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		log.debug("Elasticsearch returned {} ES results, {} after full pipeline for query '{}': {}",
				esResults.size(), relevantKeys.size(), normalizedQuery, relevantKeys);

		return filterAndSerialize(patient, question, relevantKeys);
	}

	/**
	 * Converts ES search results to {@link ChartEmbedding} objects for use
	 * with the embedding pipeline's findSimilar method. The ES index
	 * stores the PREFIXED text but findSimilar re-prefixes via
	 * {@code buildPrefixedText}, so we strip the structural prefix here
	 * to avoid double-prefixing that corrupts keyword scoring and
	 * concept-name extraction.
	 */
	private static List<ChartEmbedding> esResultsToChartEmbeddings(
			List<ElasticsearchIndexer.ElasticsearchSearchResult> esResults) {
		Set<String> prefixes = ChartSearchAiUtils.getAllEmbeddingPrefixes();
		List<ChartEmbedding> out = new ArrayList<ChartEmbedding>(esResults.size());
		for (ElasticsearchIndexer.ElasticsearchSearchResult r : esResults) {
			if (r.getEmbedding() == null) {
				continue;
			}
			ChartEmbedding ce = new ChartEmbedding();
			ce.setResourceType(r.getResourceType());
			ce.setResourceUuid(r.getResourceUuid());
			ce.setEmbeddingVector(r.getEmbedding());
			String text = r.getText();
			if (text != null) {
				for (String pfx : prefixes) {
					if (text.startsWith(pfx)) {
						text = text.substring(pfx.length());
						break;
					}
				}
			}
			ce.setTextContent(text);
			out.add(ce);
		}
		return out;
	}

	/**
	 * Filters Elasticsearch results through the same scoring and gap
	 * detection pipeline used by the embedding retrieval path. Computes
	 * cosine similarity and keyword scores from the returned embedding
	 * vectors and text, then applies filterPipeline() to remove noise.
	 */
	static List<ElasticsearchIndexer.ElasticsearchSearchResult> filterEsResults(
			List<ElasticsearchIndexer.ElasticsearchSearchResult> results,
			float[] queryVector, String normalizedQuery) {
		String[] queryTerms = QueryPreprocessor.extractQueryTerms(normalizedQuery);

		List<ElasticsearchIndexer.ElasticsearchSearchResult> valid =
				new ArrayList<ElasticsearchIndexer.ElasticsearchSearchResult>();
		for (ElasticsearchIndexer.ElasticsearchSearchResult r : results) {
			if (r.getEmbedding() != null && r.getEmbedding().length == queryVector.length) {
				valid.add(r);
			}
		}
		if (valid.isEmpty()) {
			return results;
		}

		double[] semanticScores = new double[valid.size()];
		double[] keywordScores = new double[valid.size()];
		ChartEmbedding[] embeddings = new ChartEmbedding[valid.size()];
		for (int i = 0; i < valid.size(); i++) {
			ElasticsearchIndexer.ElasticsearchSearchResult r = valid.get(i);
			semanticScores[i] = ChartSearchAiUtils.cosineSimilarity(
					queryVector, r.getEmbedding());
			keywordScores[i] = r.getText() != null
					? SimilarityAndScoringEngine.computeKeywordScore(queryTerms, r.getText()) : 0;

			ChartEmbedding ce = new ChartEmbedding();
			ce.setResourceType(r.getResourceType());
			ce.setResourceUuid(r.getResourceUuid());
			ce.setEmbeddingVector(r.getEmbedding());
			embeddings[i] = ce;
		}

		List<ChartEmbedding> filtered = EmbeddingRankingPipeline.filterPipeline(semanticScores,
				keywordScores, embeddings, queryTerms, valid.size(),
				PipelineConfig.defaults());

		Set<String> survivorKeys = new HashSet<String>();
		for (ChartEmbedding ce : filtered) {
			survivorKeys.add(ChartSearchAiUtils.resourceKey(ce.getResourceType(), ce.getResourceUuid()));
		}

		List<ElasticsearchIndexer.ElasticsearchSearchResult> out =
				new ArrayList<ElasticsearchIndexer.ElasticsearchSearchResult>();
		for (ElasticsearchIndexer.ElasticsearchSearchResult r : results) {
			if (survivorKeys.contains(ChartSearchAiUtils.resourceKey(r.getResourceType(), r.getResourceUuid()))) {
				out.add(r);
			}
		}
		return out;
	}

	private PatientChart buildChartWithHybrid(Patient patient, String question) {
		try {
			hybridRetriever.ensureIndexed(patient);
		}
		catch (Exception e) {
			log.error("Failed to index patient [id={}] for hybrid pipeline, falling back to full chart",
					patient.getPatientId(), e);
			return chartSerializer.serialize(patient);
		}

		String normalizedQuery = QueryPreprocessor.stripQueryStopwords(question);
		if (normalizedQuery.isEmpty()) {
			return chartSerializer.serialize(patient);
		}

		Set<String> relevantKeys;
		try {
			relevantKeys = hybridRetriever.search(
					patient, normalizedQuery, PipelineSettings.getQueryPrefix(), PipelineSettings.getTopK());
		}
		catch (Exception e) {
			log.error("Hybrid search failed for patient [id={}], falling back to full chart",
					patient.getPatientId(), e);
			return chartSerializer.serialize(patient);
		}

		if (relevantKeys.isEmpty()) {
			log.debug("Hybrid returned no results for query '{}', returning empty chart",
					normalizedQuery);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		log.debug("Hybrid returned {} results for query '{}'",
				relevantKeys.size(), normalizedQuery);

		return filterAndSerialize(patient, question, relevantKeys);
	}

	private PatientChart filterAndSerialize(Patient patient, String question,
			Set<String> relevantKeys) {
		return filterAndSerialize(patient, question, relevantKeys, -1, -1);
	}

	private PatientChart filterAndSerialize(Patient patient, String question,
			Set<String> relevantKeys, int pipelineSize,
			int keywordMatchCount) {
		List<SerializedRecord> allRecords = recordLoader.loadAll(patient);

		List<SerializedRecord> filtered = ConceptRescueAndFilter.postRetrievalPipeline(
				allRecords, relevantKeys, question,
				pipelineSize, keywordMatchCount,
				embeddingProvider, PipelineSettings.getQueryPrefix());

		log.debug("Pre-filtered {} records to {} using {}",
				allRecords.size(), filtered.size(),
				isHybridPipeline() ? "Hybrid" :
						isElasticsearchPipeline() ? "Elasticsearch" :
								isLucenePipeline() ? "Lucene" : "embeddings");

		return chartSerializer.serialize(patient, filtered);
	}

	List<ChartEmbedding> findSimilar(Patient patient, String question) {
		FindSimilarResult result = findSimilarResult(patient, question);
		return result != null ? result.records : null;
	}

	FindSimilarResult findSimilarResult(Patient patient, String question) {
		List<ChartEmbedding> allEmbeddings = dao.getByPatient(patient);
		if (allEmbeddings.isEmpty()) {
			return null;
		}

		// Select model-specific pipeline config by fingerprinting the
		// model (embedding a sentinel string). Falls back to path-based
		// detection, then L6-v2 defaults.
		String modelIdentity = embeddingProvider != null
				? embeddingProvider.identifyModel() : null;
		String modelName = embeddingProvider != null
				? embeddingProvider.getModelName() : null;
		PipelineConfig baseConfig = PipelineConfig.forModel(
				modelIdentity != null ? modelIdentity : modelName);

		// Reuse cached noise profile for this patient+model when
		// available, avoiding ~30 redundant ONNX embeddings per query.
		String cacheKey = patient.getPatientId()
				+ ":" + (modelIdentity != null ? modelIdentity : modelName);
		ModelNoiseProfile cachedProfile = noiseProfileCache.get(cacheKey);

		// Use model-specific defaults, but allow global property overrides
		// when an admin has explicitly customized them.
		PipelineConfig config = PipelineConfig.buildEffective(baseConfig,
				PipelineSettings.getKeywordWeight(), PipelineSettings.getScoreGapMultiplier(),
				PipelineSettings.getMinScoreGap(), PipelineSettings.getGapValidationCosineThreshold(),
				PipelineSettings.getSimilarityRatio(), cachedProfile);
		log.warn("Model identity={}, config: kwWeight={}, gapMult={}, "
				+ "minGap={}, gapCosThresh={}, simRatio={}, floorZScore={}",
				modelIdentity, config.keywordWeight, config.scoreGapMultiplier,
				config.minScoreGap, config.gapValidationCosineThreshold,
				config.similarityRatio, config.floorRescueMinZScore);

		try {
			FindSimilarResult result = RetrievalQuery.findSimilar(allEmbeddings,
					embeddingProvider, question, PipelineSettings.getQueryPrefix(), config);
			if (result.noiseProfile != null) {
				if (noiseProfileCache.size() >= NOISE_PROFILE_CACHE_MAX_SIZE) {
					// Evict an arbitrary entry to stay within bounds.
					Iterator<String> it = noiseProfileCache.keySet().iterator();
					if (it.hasNext()) {
						noiseProfileCache.remove(it.next());
					}
				}
				noiseProfileCache.put(cacheKey, result.noiseProfile);
			}
			return result;
		}
		catch (Exception e) {
			log.debug("Embedding provider not available, falling back to full chart", e);
			return null;
		}
	}

	boolean isHybridPipeline() {
		String pipeline = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE);
		return ChartSearchAiConstants.PIPELINE_HYBRID.equalsIgnoreCase(
				pipeline != null ? pipeline.trim() : "");
	}

	boolean isElasticsearchPipeline() {
		String pipeline = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE);
		return ChartSearchAiConstants.PIPELINE_ELASTICSEARCH.equalsIgnoreCase(
				pipeline != null ? pipeline.trim() : "");
	}

	boolean isLucenePipeline() {
		String pipeline = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE);
		return ChartSearchAiConstants.PIPELINE_LUCENE.equalsIgnoreCase(
				pipeline != null ? pipeline.trim() : "");
	}

	boolean usePreFilter() {
		return PipelineSettings.usePreFilter();
	}
}
