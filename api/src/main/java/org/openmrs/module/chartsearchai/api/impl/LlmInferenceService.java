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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Answers natural language questions about a patient's chart using direct
 * LLM inference. Delegates chart assembly (retrieval + concept rescue +
 * serialization) to {@link ChartBuildingStrategy} and focuses on the LLM
 * call and citation handling. The static helpers on this class are thin
 * delegates kept for backward-compatible test access; new code should
 * call the underlying class directly.
 */
@Service("chartSearchAi.llmInferenceService")
public class LlmInferenceService implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);

	@Autowired
	private LlmProvider llmProvider;

	@Autowired
	private ChartBuildingStrategy chartBuildingStrategy;

	/**
	 * Bundles the pipeline result list with the computed noise profile so
	 * the caller can cache the profile for reuse on subsequent queries.
	 */
	static class FindSimilarResult {

		final List<ChartEmbedding> records;

		final ModelNoiseProfile noiseProfile;

		final int keywordMatchCount;

		FindSimilarResult(List<ChartEmbedding> records,
				ModelNoiseProfile noiseProfile) {
			this(records, noiseProfile, -1);
		}

		FindSimilarResult(List<ChartEmbedding> records,
				ModelNoiseProfile noiseProfile,
				int keywordMatchCount) {
			this.records = records;
			this.noiseProfile = noiseProfile;
			this.keywordMatchCount = keywordMatchCount;
		}
	}

	@Override
	public ChartAnswer search(Patient patient, String question) {
		PatientChart chart = chartBuildingStrategy.buildChart(patient, question);
		LlmResponse response = llmProvider.search(chartTextOrPlaceholder(chart), question);

		return new ChartAnswer(response.getAnswer(),
				extractCitedReferences(response.getCitations(), chart.getMappings()),
				response.getInputTokens(), response.getOutputTokens(),
				response.getCachedTokens());
	}

	@Override
	public void warmup(Patient patient) {
		if (!isWarmupEnabled()) {
			return;
		}
		// Skip the upstream chart-serialization cost when the active engine
		// gains nothing from warmup (e.g. remote APIs that cache themselves).
		if (!llmProvider.supportsWarmup()) {
			return;
		}
		// Pre-filter pipelines build a different prompt prefix for each
		// query (the records sent depend on the question), so a chart-only
		// warmup wouldn't match what a real query produces — the KV cache
		// would not be reused.
		if (chartBuildingStrategy.usePreFilter()) {
			return;
		}
		PatientChart chart = chartBuildingStrategy.buildChart(patient, "");
		llmProvider.warmup(chartTextOrPlaceholder(chart));
	}

	List<ChartEmbedding> findSimilar(Patient patient, String question) {
		return chartBuildingStrategy.findSimilar(patient, question);
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		PatientChart chart = chartBuildingStrategy.buildChart(patient, question);
		LlmResponse response = llmProvider.searchStreaming(
				chartTextOrPlaceholder(chart), question, tokenConsumer);

		return new ChartAnswer(response.getAnswer(),
				extractCitedReferences(response.getCitations(), chart.getMappings()),
				response.getInputTokens(), response.getOutputTokens(),
				response.getCachedTokens());
	}

	/**
	 * Build the full, retrieval-free patient chart that the chat path uses
	 * as its frozen session prefix. Bypasses {@code chartsearchai.embedding.preFilter}
	 * deliberately — chat needs byte-stability across turns so the LLM's
	 * prompt cache can hit, which pre-filter's query-dependent chart breaks.
	 *
	 * <p>Returned text is the LLM-ready envelope: "Patient records (most
	 * recent first):\n<numbered records>". Stored on
	 * {@code chat_session.chart_snapshot} by {@link ChatServiceImpl}.
	 */
	public PatientChart buildSessionChart(Patient patient) {
		PatientChart chart = chartBuildingStrategy.buildChartUnfiltered(patient);
		String envelope = "Patient records (most recent first):\n"
				+ (chart.getText() == null || chart.getText().trim().isEmpty()
						? "This patient has no records." : chart.getText().stripTrailing());
		return new PatientChart(envelope, chart.getMappings());
	}

	/**
	 * Multi-turn chat using a frozen session-scoped chart envelope. The
	 * envelope and mappings are passed in (by the {@link ChatServiceImpl},
	 * sourced from {@code chat_session.chart_snapshot} +
	 * {@code chart_mappings_json}), so consecutive turns of one session
	 * send the byte-identical chart prefix and the LLM's prompt cache
	 * hits on follow-ups.
	 *
	 * <p>The {@code question} is the raw clinician text — assembleChat
	 * places it as the trailing user message, separate from the chart
	 * envelope.
	 */
	public ChartAnswer chat(String chartEnvelope, List<RecordMapping> mappings,
			List<ChatMessage> priorTurns, String question) {
		LlmResponse response = llmProvider.chat(chartEnvelope, priorTurns, question);

		return new ChartAnswer(response.getAnswer(),
				extractCitedReferences(response.getCitations(), mappings),
				response.getInputTokens(), response.getOutputTokens(),
				response.getCachedTokens());
	}

	/**
	 * Streaming variant of {@link #chat}.
	 */
	public ChartAnswer chatStreaming(String chartEnvelope, List<RecordMapping> mappings,
			List<ChatMessage> priorTurns, String question, Consumer<String> tokenConsumer) {
		LlmResponse response = llmProvider.chatStreaming(
				chartEnvelope, priorTurns, question, tokenConsumer);

		return new ChartAnswer(response.getAnswer(),
				extractCitedReferences(response.getCitations(), mappings),
				response.getInputTokens(), response.getOutputTokens(),
				response.getCachedTokens());
	}

	/**
	 * Substitutes a placeholder when the chart has no records, so the LLM
	 * produces a query-specific "no records" answer instead of one based
	 * on demographics alone.
	 */
	private static String chartTextOrPlaceholder(PatientChart chart) {
		return chart.getMappings().isEmpty() ? "(No relevant records found)" : chart.getText();
	}

	static boolean isWarmupEnabled() {
		String value = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_WARMUP_ENABLED, "true");
		return !"false".equalsIgnoreCase(value.trim());
	}

	static List<RecordReference> extractCitedReferences(List<Integer> citations,
			List<RecordMapping> mappings) {
		Map<Integer, RecordMapping> indexMap = new HashMap<Integer, RecordMapping>();
		for (RecordMapping mapping : mappings) {
			indexMap.put(mapping.getIndex(), mapping);
		}

		Set<Integer> seen = new LinkedHashSet<Integer>();
		for (Integer index : citations) {
			seen.add(index);
		}

		List<RecordReference> references = new ArrayList<RecordReference>();
		for (Integer index : seen) {
			RecordMapping mapping = indexMap.get(index);
			if (mapping != null) {
				references.add(new RecordReference(index, mapping.getResourceType(),
						mapping.getResourceUuid(), mapping.getDate()));
			} else {
				log.warn("LLM cited record [{}] which does not exist in the provided records", index);
			}
		}
		Collections.sort(references, Comparator.comparing(RecordReference::getDate,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return references;
	}

	// =====================================================================
	// Static delegate wrappers — kept so existing test call sites that use
	// LlmInferenceService.X(...) continue to resolve. New code should call
	// the underlying classes (QueryPreprocessor, SimilarityAndScoringEngine,
	// EmbeddingRankingPipeline, ConceptRescueAndFilter) directly.
	// =====================================================================

	static int extractRecencyCap(String question) {
		return QueryPreprocessor.extractRecencyCap(question);
	}

	static String stripQueryStopwords(String question) {
		return QueryPreprocessor.stripQueryStopwords(question);
	}

	static String[] extractQueryTerms(String normalizedQuery) {
		return QueryPreprocessor.extractQueryTerms(normalizedQuery);
	}

	static String prepareEmbeddingInput(String question, String queryPrefix) {
		return QueryPreprocessor.prepareEmbeddingInput(question, queryPrefix);
	}

	static double computeKeywordScore(String[] queryTerms, String textContent) {
		return SimilarityAndScoringEngine.computeKeywordScore(queryTerms, textContent);
	}

	static double computeKeywordScoreRestricted(String[] queryTerms,
			String prefixedText, String body,
			Set<String> typeIndicatorTerms) {
		return SimilarityAndScoringEngine.computeKeywordScoreRestricted(
				queryTerms, prefixedText, body, typeIndicatorTerms);
	}

	static boolean termMatchesText(String term, String lowerText, String[] textWords) {
		return SimilarityAndScoringEngine.termMatchesText(term, lowerText, textWords);
	}

	static double cosineSimilarity(float[] a, float[] b) {
		return ChartSearchAiUtils.cosineSimilarity(a, b);
	}

	static List<SerializedRecord> capPerConcept(List<SerializedRecord> records,
			int maxPerConcept) {
		return ConceptRescueAndFilter.capPerConcept(records, maxPerConcept);
	}

	static List<SerializedRecord> groupByConcept(List<SerializedRecord> records) {
		return ConceptRescueAndFilter.groupByConcept(records);
	}

	static String conceptKey(String text) {
		return ConceptRescueAndFilter.conceptKey(text);
	}

	static List<ScoredEmbedding> growCluster(List<ScoredEmbedding> candidates,
			int seedSize, double cosineThreshold) {
		return CoherenceFilters.growCluster(candidates, seedSize, cosineThreshold);
	}

	static boolean isGapCoherent(List<ScoredEmbedding> scored, int cutoff,
			double cosineThreshold) {
		return CoherenceFilters.isGapCoherent(scored, cutoff, cosineThreshold);
	}

	static List<ScoredEmbedding> rescueBelowFloor(List<ScoredEmbedding> candidates,
			List<ScoredEmbedding> scored, int adaptiveCutoff) {
		return CoherenceFilters.rescueBelowFloor(candidates, scored, adaptiveCutoff);
	}

	static String[] expandKwTermsViaConceptSimilarity(String[] kwTerms,
			float[] queryVector, ChartEmbedding[] embeddings,
			EmbeddingProvider provider, PipelineConfig config) {
		return ConceptKeywordMatching.expandKwTermsViaConceptSimilarity(
				kwTerms, queryVector, embeddings, provider, config);
	}

	static boolean applySlimMarginGate(List<ScoredEmbedding> scored,
			double maxSemanticScore, int queryTermCount,
			int keywordMatchCount, boolean belowFloorRescued,
			PipelineConfig config) {
		return RankingPipelineGates.applySlimMarginGate(scored, maxSemanticScore,
				queryTermCount, keywordMatchCount, belowFloorRescued, config);
	}

	static int findAdaptiveCutoff(List<ScoredEmbedding> scored, int limit, double minScore,
			double gapMultiplier, double minGap) {
		return EmbeddingRankingPipeline.findAdaptiveCutoff(scored, limit, minScore,
				gapMultiplier, minGap);
	}

	static List<ChartEmbedding> filterPipeline(double[] semanticScores,
			double[] keywordScores, ChartEmbedding[] embeddings,
			String[] queryTerms, PipelineConfig config) {
		return EmbeddingRankingPipeline.filterPipeline(semanticScores,
				keywordScores, embeddings, queryTerms, config);
	}

	static FindSimilarResult findSimilar(List<ChartEmbedding> allEmbeddings,
			EmbeddingProvider provider, String question,
			String queryPrefix, PipelineConfig config) {
		return RetrievalQuery.findSimilar(allEmbeddings, provider,
				question, queryPrefix, config);
	}

	static List<SerializedRecord> findRelevantRecords(
			List<ChartEmbedding> allEmbeddings,
			List<SerializedRecord> allRecords,
			EmbeddingProvider provider, String question,
			String queryPrefix, PipelineConfig config) {
		return RetrievalQuery.findRelevantRecords(allEmbeddings, allRecords,
				provider, question, queryPrefix, config);
	}
}
