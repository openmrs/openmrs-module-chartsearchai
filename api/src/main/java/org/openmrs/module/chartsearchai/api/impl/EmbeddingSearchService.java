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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searches a patient's chart embeddings by computing cosine similarity between the query
 * embedding and all stored embeddings for that patient. Retrieves the top matching records,
 * then sends them to the LLM for reasoning and synthesis (RAG pipeline).
 *
 * <p>For typical patient charts (&lt;2000 records), brute-force in-memory similarity is
 * fast enough (&lt;10ms).</p>
 */
@Service("chartSearchAi.embeddingSearchService")
@Transactional(readOnly = true)
public class EmbeddingSearchService implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingSearchService.class);

	@Autowired
	@Qualifier("chartSearchAi.embeddingProvider")
	private EmbeddingProvider embeddingProvider;

	@Autowired
	private ChartSearchAiDAO dao;

	@Autowired
	private LlmProvider llmProvider;

	@Override
	public ChartAnswer search(Patient patient, String question) {
		List<ChartEmbedding> results = findSimilar(patient, question,
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		List<RecordReference> references = new ArrayList<RecordReference>();
		StringBuilder sb = new StringBuilder();
		Map<String, Integer> typeCounters = new HashMap<String, Integer>();
		for (int i = 0; i < results.size(); i++) {
			ChartEmbedding ce = results.get(i);
			String type = ce.getResourceType();
			String displayType = PatientChartSerializer.toDisplayType(type);
			int count = typeCounters.containsKey(type) ? typeCounters.get(type) + 1 : 1;
			typeCounters.put(type, count);
			String label = displayType + " #" + count;
			sb.append("[").append(label).append("] ").append(ce.getTextContent()).append("\n");
			references.add(new RecordReference(label, type, ce.getResourceId()));
		}

		log.debug("Sending {} retrieved records to LLM", references.size());
		String response = llmProvider.search(sb.toString(), question);

		List<RecordReference> citedReferences = LlmInferenceService.filterCitedReferences(
				response, references);

		return new ChartAnswer(response, citedReferences);
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		List<ChartEmbedding> results = findSimilar(patient, question,
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		List<RecordReference> references = new ArrayList<RecordReference>();
		StringBuilder sb = new StringBuilder();
		Map<String, Integer> typeCounters = new HashMap<String, Integer>();
		for (int i = 0; i < results.size(); i++) {
			ChartEmbedding ce = results.get(i);
			String type = ce.getResourceType();
			String displayType = PatientChartSerializer.toDisplayType(type);
			int count = typeCounters.containsKey(type) ? typeCounters.get(type) + 1 : 1;
			typeCounters.put(type, count);
			String label = displayType + " #" + count;
			sb.append("[").append(label).append("] ").append(ce.getTextContent()).append("\n");
			references.add(new RecordReference(label, type, ce.getResourceId()));
		}

		log.debug("Streaming {} retrieved records to LLM", references.size());
		String response = llmProvider.searchStreaming(sb.toString(), question, tokenConsumer);

		List<RecordReference> citedReferences = LlmInferenceService.filterCitedReferences(
				response, references);

		return new ChartAnswer(response, citedReferences);
	}

	/**
	 * Find the most relevant embeddings for a patient matching a query by cosine similarity.
	 *
	 * @param patient the patient whose chart to search
	 * @param query the natural-language query
	 * @param topK the maximum number of results to return
	 * @return the most relevant chart embeddings, ordered by similarity (highest first)
	 */
	public List<ChartEmbedding> findSimilar(Patient patient, String query, int topK) {
		float[] queryVector = embeddingProvider.embed(query);

		List<ChartEmbedding> allEmbeddings = dao.getByPatient(patient);
		if (allEmbeddings.isEmpty()) {
			return Collections.emptyList();
		}

		List<ScoredEmbedding> scored = new ArrayList<ScoredEmbedding>();
		for (ChartEmbedding ce : allEmbeddings) {
			double similarity = cosineSimilarity(queryVector, ce.getEmbeddingVector());
			scored.add(new ScoredEmbedding(ce, similarity));
		}

		Collections.sort(scored, new Comparator<ScoredEmbedding>() {
			@Override
			public int compare(ScoredEmbedding a, ScoredEmbedding b) {
				return Double.compare(b.score, a.score);
			}
		});

		List<ChartEmbedding> results = new ArrayList<ChartEmbedding>();
		int limit = Math.min(topK, scored.size());
		for (int i = 0; i < limit; i++) {
			results.add(scored.get(i).embedding);
		}
		return results;
	}

	/**
	 * Find similar with the default topK value.
	 */
	public List<ChartEmbedding> findSimilar(Patient patient, String query) {
		return findSimilar(patient, query, ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);
	}

	private double cosineSimilarity(float[] a, float[] b) {
		if (a.length != b.length) {
			log.warn("Embedding dimension mismatch: query={} vs stored={}. "
					+ "This may indicate a model change or corrupted data.", a.length, b.length);
			return 0;
		}
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		double denominator = Math.sqrt(normA) * Math.sqrt(normB);
		if (denominator == 0) {
			return 0;
		}
		return dot / denominator;
	}

	private static class ScoredEmbedding {

		final ChartEmbedding embedding;

		final double score;

		ScoredEmbedding(ChartEmbedding embedding, double score) {
			this.embedding = embedding;
			this.score = score;
		}
	}
}
