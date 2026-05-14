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

import java.io.IOException;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ElasticsearchIndexer.BackendType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stateless JSON construction for the Elasticsearch / OpenSearch REST API.
 * Isolates the backend-divergent payloads — Elasticsearch uses the
 * {@code retriever} API with {@code dense_vector}; OpenSearch uses
 * {@code hybrid} queries with {@code knn_vector} and a search pipeline
 * for RRF — so that {@link ElasticsearchIndexer} can focus on connection
 * lifecycle and request orchestration.
 *
 * <p>Reads schema constants ({@code FIELD_*}, {@code KNN_NUM_CANDIDATES})
 * from {@link ElasticsearchIndexer} via package-private access.</p>
 */
final class ElasticsearchQueryBuilder {

	private ElasticsearchQueryBuilder() {
	}

	/**
	 * Builds the index creation JSON with mappings for text (BM25) and
	 * dense_vector/knn_vector fields, adapting to the detected backend.
	 */
	static String buildIndexMapping(ObjectMapper mapper, BackendType backend, int dims)
			throws IOException {
		ObjectNode body = mapper.createObjectNode();

		if (backend == BackendType.OPENSEARCH) {
			body.putObject("settings").put("index.knn", true);
		}

		ObjectNode mappings = body.putObject("mappings");
		ObjectNode properties = mappings.putObject("properties");

		properties.putObject(ElasticsearchIndexer.FIELD_PATIENT_ID).put("type", "integer");
		properties.putObject(ElasticsearchIndexer.FIELD_RESOURCE_TYPE).put("type", "keyword");
		properties.putObject(ElasticsearchIndexer.FIELD_RESOURCE_UUID).put("type", "keyword");
		properties.putObject(ElasticsearchIndexer.FIELD_TEXT).put("type", "text").put("analyzer", "english");

		ObjectNode embeddingField = properties.putObject(ElasticsearchIndexer.FIELD_EMBEDDING);
		if (backend == BackendType.OPENSEARCH) {
			embeddingField.put("type", "knn_vector");
			embeddingField.put("dimension", dims);
			ObjectNode method = embeddingField.putObject("method");
			method.put("name", "hnsw");
			method.put("space_type", "cosinesimil");
			method.put("engine", "lucene");
		} else {
			embeddingField.put("type", "dense_vector");
			embeddingField.put("dims", dims);
			embeddingField.put("index", true);
			embeddingField.put("similarity", "cosine");
		}

		return mapper.writeValueAsString(body);
	}

	/**
	 * Builds the OpenSearch search pipeline JSON body for RRF.
	 */
	static String buildSearchPipelineBody(ObjectMapper mapper) throws IOException {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode processors = body.putArray("phase_results_processors");
		ObjectNode processor = processors.addObject().putObject("score-ranker-processor");
		ObjectNode combination = processor.putObject("combination");
		combination.put("technique", "rrf");
		combination.put("rank_constant", ChartSearchAiConstants.RRF_RANK_CONSTANT);
		return mapper.writeValueAsString(body);
	}

	/**
	 * Builds the hybrid search query JSON, adapting to the detected backend.
	 * Elasticsearch uses the retriever API (ES 8.14+); OpenSearch uses
	 * a hybrid query with a search pipeline for RRF (OS 2.19+).
	 */
	static String buildSearchQuery(ObjectMapper mapper, BackendType backend, int patientId,
			String queryText, float[] queryVector, int maxResults) throws IOException {
		if (backend == BackendType.OPENSEARCH) {
			return buildOpenSearchQuery(mapper, patientId, queryText, queryVector, maxResults);
		}
		return buildElasticsearchQuery(mapper, patientId, queryText, queryVector, maxResults);
	}

	/**
	 * Builds the RRF hybrid search query using the Elasticsearch
	 * retriever API (ES 8.14+).
	 */
	static String buildElasticsearchQuery(ObjectMapper mapper, int patientId, String queryText,
			float[] queryVector, int maxResults) throws IOException {
		ObjectNode body = newSearchBody(mapper, maxResults);

		ObjectNode retriever = body.putObject("retriever");
		ObjectNode rrf = retriever.putObject("rrf");
		rrf.put("rank_window_size", ChartSearchAiConstants.RRF_RANK_WINDOW_SIZE);
		rrf.put("rank_constant", ChartSearchAiConstants.RRF_RANK_CONSTANT);

		ArrayNode retrievers = rrf.putArray("retrievers");

		// BM25 text retriever
		ObjectNode bm25Retriever = retrievers.addObject().putObject("standard");
		ObjectNode bm25Query = bm25Retriever.putObject("query").putObject("bool");
		bm25Query.putArray("must").addObject()
				.putObject("match").put(ElasticsearchIndexer.FIELD_TEXT, queryText);
		bm25Query.putArray("filter").addObject()
				.putObject("term").put(ElasticsearchIndexer.FIELD_PATIENT_ID, patientId);

		// kNN vector retriever
		ObjectNode knnRetriever = retrievers.addObject().putObject("knn");
		knnRetriever.put("field", ElasticsearchIndexer.FIELD_EMBEDDING);
		ArrayNode qv = knnRetriever.putArray("query_vector");
		for (float v : queryVector) {
			qv.add(v);
		}
		knnRetriever.put("k", Math.min(maxResults, ElasticsearchIndexer.KNN_NUM_CANDIDATES));
		knnRetriever.put("num_candidates", ElasticsearchIndexer.KNN_NUM_CANDIDATES);
		knnRetriever.putObject("filter")
				.putObject("term").put(ElasticsearchIndexer.FIELD_PATIENT_ID, patientId);

		return mapper.writeValueAsString(body);
	}

	/**
	 * Builds the hybrid search query for OpenSearch (OS 2.19+).
	 * Uses the hybrid query type with BM25 and kNN sub-queries;
	 * RRF is applied via the search pipeline set on the request.
	 */
	static String buildOpenSearchQuery(ObjectMapper mapper, int patientId, String queryText,
			float[] queryVector, int maxResults) throws IOException {
		ObjectNode body = newSearchBody(mapper, maxResults);

		ObjectNode hybrid = body.putObject("query").putObject("hybrid");
		ArrayNode queries = hybrid.putArray("queries");

		// BM25 text query
		ObjectNode bm25Bool = queries.addObject().putObject("bool");
		bm25Bool.putArray("must").addObject()
				.putObject("match").put(ElasticsearchIndexer.FIELD_TEXT, queryText);
		bm25Bool.putArray("filter").addObject()
				.putObject("term").put(ElasticsearchIndexer.FIELD_PATIENT_ID, patientId);

		// kNN vector query
		ObjectNode knnOuter = queries.addObject().putObject("knn");
		ObjectNode knnInner = knnOuter.putObject(ElasticsearchIndexer.FIELD_EMBEDDING);
		ArrayNode qv = knnInner.putArray("vector");
		for (float v : queryVector) {
			qv.add(v);
		}
		knnInner.put("k", Math.min(maxResults, ElasticsearchIndexer.KNN_NUM_CANDIDATES));
		knnInner.putObject("filter")
				.putObject("term").put(ElasticsearchIndexer.FIELD_PATIENT_ID, patientId);

		return mapper.writeValueAsString(body);
	}

	/**
	 * Builds a JSON term query for filtering by patient ID, used by the
	 * indexer's delete-by-query and count-by-query paths.
	 */
	static String buildPatientTermQuery(ObjectMapper mapper, int patientId) throws IOException {
		ObjectNode root = mapper.createObjectNode();
		root.putObject("query").putObject("term")
				.put(ElasticsearchIndexer.FIELD_PATIENT_ID, patientId);
		return mapper.writeValueAsString(root);
	}

	private static ObjectNode newSearchBody(ObjectMapper mapper, int maxResults) {
		ObjectNode body = mapper.createObjectNode();
		body.put("size", maxResults);
		ArrayNode source = body.putArray("_source");
		source.add(ElasticsearchIndexer.FIELD_RESOURCE_TYPE);
		source.add(ElasticsearchIndexer.FIELD_RESOURCE_UUID);
		source.add(ElasticsearchIndexer.FIELD_EMBEDDING);
		source.add(ElasticsearchIndexer.FIELD_TEXT);
		return body;
	}
}
