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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ElasticsearchIndexerTest {

	private ElasticsearchIndexer indexer;

	private ObjectMapper mapper;

	@BeforeEach
	public void setUp() {
		indexer = new ElasticsearchIndexer();
		mapper = new ObjectMapper();
	}

	// --- Elasticsearch index mapping tests ---

	@Test
	public void buildIndexMapping_elasticsearch_shouldIncludeTextAndDenseVectorFields() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.ELASTICSEARCH);
		String json = indexer.buildIndexMapping(384);
		JsonNode root = mapper.readTree(json);

		JsonNode properties = root.path("mappings").path("properties");
		assertFalse(properties.isMissingNode(), "Should have properties node");

		assertEquals("integer", properties.path("patient_id").path("type").asText());
		assertEquals("keyword", properties.path("resource_type").path("type").asText());
		assertEquals("keyword", properties.path("resource_uuid").path("type").asText());
		assertEquals("text", properties.path("text").path("type").asText());
		assertEquals("english", properties.path("text").path("analyzer").asText());
		assertEquals("dense_vector", properties.path("embedding").path("type").asText());
		assertEquals(384, properties.path("embedding").path("dims").asInt());
		assertTrue(properties.path("embedding").path("index").asBoolean());
		assertEquals("cosine", properties.path("embedding").path("similarity").asText());

		assertTrue(root.path("settings").isMissingNode(), "ES should not have knn settings");
	}

	@Test
	public void buildIndexMapping_elasticsearch_shouldUseDimensionsFromProvider() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.ELASTICSEARCH);
		String json768 = indexer.buildIndexMapping(768);
		JsonNode root = mapper.readTree(json768);
		assertEquals(768, root.path("mappings").path("properties")
				.path("embedding").path("dims").asInt());
	}

	// --- OpenSearch index mapping tests ---

	@Test
	public void buildIndexMapping_opensearch_shouldUseKnnVectorWithHnsw() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.OPENSEARCH);
		String json = indexer.buildIndexMapping(384);
		JsonNode root = mapper.readTree(json);

		assertTrue(root.path("settings").path("index.knn").asBoolean(),
				"OpenSearch should enable index.knn");

		JsonNode embedding = root.path("mappings").path("properties").path("embedding");
		assertEquals("knn_vector", embedding.path("type").asText());
		assertEquals(384, embedding.path("dimension").asInt());
		assertTrue(embedding.path("dims").isMissingNode(),
				"OpenSearch should use 'dimension' not 'dims'");

		JsonNode method = embedding.path("method");
		assertEquals("hnsw", method.path("name").asText());
		assertEquals("cosinesimil", method.path("space_type").asText());
		assertEquals("lucene", method.path("engine").asText());

		assertTrue(embedding.path("similarity").isMissingNode(),
				"OpenSearch should not have 'similarity' field");
	}

	@Test
	public void buildIndexMapping_opensearch_shouldUseDimensionsFromProvider() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.OPENSEARCH);
		String json = indexer.buildIndexMapping(768);
		JsonNode root = mapper.readTree(json);
		assertEquals(768, root.path("mappings").path("properties")
				.path("embedding").path("dimension").asInt());
	}

	// --- Elasticsearch search query tests ---

	@Test
	public void buildSearchQuery_elasticsearch_shouldContainRrfRetrieverWithBm25AndKnn() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.ELASTICSEARCH);
		float[] queryVector = new float[] { 0.1f, 0.2f, 0.3f };
		String json = indexer.buildSearchQuery(42, "cancer history", queryVector, 10);
		JsonNode root = mapper.readTree(json);

		assertEquals(10, root.path("size").asInt());

		JsonNode source = root.path("_source");
		assertTrue(source.isArray());
		assertEquals(4, source.size());

		// RRF retriever structure
		JsonNode rrf = root.path("retriever").path("rrf");
		assertFalse(rrf.isMissingNode(), "Should have rrf retriever");
		assertEquals(ChartSearchAiConstants.RRF_RANK_WINDOW_SIZE, rrf.path("rank_window_size").asInt());
		assertEquals(ChartSearchAiConstants.RRF_RANK_CONSTANT, rrf.path("rank_constant").asInt());

		JsonNode retrievers = rrf.path("retrievers");
		assertEquals(2, retrievers.size(), "Should have BM25 and kNN retrievers");

		// BM25 retriever
		JsonNode bm25 = retrievers.get(0).path("standard");
		assertFalse(bm25.isMissingNode());
		JsonNode bm25Bool = bm25.path("query").path("bool");
		assertEquals("cancer history",
				bm25Bool.path("must").get(0).path("match").path("text").asText());
		assertEquals(42,
				bm25Bool.path("filter").get(0).path("term").path("patient_id").asInt());

		// kNN retriever
		JsonNode knn = retrievers.get(1).path("knn");
		assertFalse(knn.isMissingNode());
		assertEquals("embedding", knn.path("field").asText());
		assertEquals(10, knn.path("k").asInt());
		assertEquals(ElasticsearchIndexer.KNN_NUM_CANDIDATES, knn.path("num_candidates").asInt());
		assertEquals(42, knn.path("filter").path("term").path("patient_id").asInt());

		JsonNode qv = knn.path("query_vector");
		assertEquals(3, qv.size());
		assertEquals(0.1, qv.get(0).doubleValue(), 0.001);
		assertEquals(0.2, qv.get(1).doubleValue(), 0.001);
		assertEquals(0.3, qv.get(2).doubleValue(), 0.001);

		assertTrue(root.path("query").isMissingNode(), "ES should not have hybrid query");
	}

	@Test
	public void buildSearchQuery_elasticsearch_shouldFilterByPatientInBothRetrievers() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.ELASTICSEARCH);
		float[] queryVector = new float[] { 0.5f };
		String json = indexer.buildSearchQuery(99, "medications", queryVector, 5);
		JsonNode root = mapper.readTree(json);

		JsonNode retrievers = root.path("retriever").path("rrf").path("retrievers");

		int bm25PatientId = retrievers.get(0).path("standard").path("query")
				.path("bool").path("filter").get(0).path("term")
				.path("patient_id").asInt();
		assertEquals(99, bm25PatientId);

		int knnPatientId = retrievers.get(1).path("knn").path("filter")
				.path("term").path("patient_id").asInt();
		assertEquals(99, knnPatientId);
	}

	@Test
	public void buildSearchQuery_elasticsearch_shouldCapKnnKAtNumCandidates() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.ELASTICSEARCH);
		float[] queryVector = new float[] { 0.1f };
		String json = indexer.buildSearchQuery(1, "test", queryVector, 200);
		JsonNode root = mapper.readTree(json);

		JsonNode knn = root.path("retriever").path("rrf").path("retrievers")
				.get(1).path("knn");
		assertEquals(ElasticsearchIndexer.KNN_NUM_CANDIDATES, knn.path("k").asInt(),
				"k should be capped at num_candidates when maxResults exceeds it");
		assertEquals(ElasticsearchIndexer.KNN_NUM_CANDIDATES, knn.path("num_candidates").asInt());
	}

	// --- OpenSearch search query tests ---

	@Test
	public void buildSearchQuery_opensearch_shouldContainHybridQueryWithBm25AndKnn() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.OPENSEARCH);
		float[] queryVector = new float[] { 0.1f, 0.2f, 0.3f };
		String json = indexer.buildSearchQuery(42, "cancer history", queryVector, 10);
		JsonNode root = mapper.readTree(json);

		assertEquals(10, root.path("size").asInt());

		JsonNode source = root.path("_source");
		assertTrue(source.isArray());
		assertEquals(4, source.size());

		assertTrue(root.path("retriever").isMissingNode(),
				"OpenSearch should not have retriever API");

		// Hybrid query structure
		JsonNode queries = root.path("query").path("hybrid").path("queries");
		assertFalse(queries.isMissingNode(), "Should have hybrid queries");
		assertEquals(2, queries.size(), "Should have BM25 and kNN queries");

		// BM25 query
		JsonNode bm25Bool = queries.get(0).path("bool");
		assertFalse(bm25Bool.isMissingNode());
		assertEquals("cancer history",
				bm25Bool.path("must").get(0).path("match").path("text").asText());
		assertEquals(42,
				bm25Bool.path("filter").get(0).path("term").path("patient_id").asInt());

		// kNN query
		JsonNode knn = queries.get(1).path("knn").path("embedding");
		assertFalse(knn.isMissingNode());
		assertEquals(10, knn.path("k").asInt());
		assertEquals(42, knn.path("filter").path("term").path("patient_id").asInt());

		JsonNode qv = knn.path("vector");
		assertEquals(3, qv.size());
		assertEquals(0.1, qv.get(0).doubleValue(), 0.001);
		assertEquals(0.2, qv.get(1).doubleValue(), 0.001);
		assertEquals(0.3, qv.get(2).doubleValue(), 0.001);
	}

	@Test
	public void buildSearchQuery_opensearch_shouldFilterByPatientInBothQueries() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.OPENSEARCH);
		float[] queryVector = new float[] { 0.5f };
		String json = indexer.buildSearchQuery(99, "medications", queryVector, 5);
		JsonNode root = mapper.readTree(json);

		JsonNode queries = root.path("query").path("hybrid").path("queries");

		int bm25PatientId = queries.get(0).path("bool").path("filter")
				.get(0).path("term").path("patient_id").asInt();
		assertEquals(99, bm25PatientId);

		int knnPatientId = queries.get(1).path("knn").path("embedding")
				.path("filter").path("term").path("patient_id").asInt();
		assertEquals(99, knnPatientId);
	}

	@Test
	public void buildSearchQuery_opensearch_shouldCapKnnK() throws IOException {
		indexer.setBackendType(ElasticsearchIndexer.BackendType.OPENSEARCH);
		float[] queryVector = new float[] { 0.1f };
		String json = indexer.buildSearchQuery(1, "test", queryVector, 200);
		JsonNode root = mapper.readTree(json);

		JsonNode knn = root.path("query").path("hybrid").path("queries")
				.get(1).path("knn").path("embedding");
		assertEquals(ElasticsearchIndexer.KNN_NUM_CANDIDATES, knn.path("k").asInt(),
				"k should be capped at num_candidates when maxResults exceeds it");
	}

	// --- Search pipeline tests ---

	@Test
	public void buildSearchPipelineBody_shouldContainRrfScoreRanker() throws IOException {
		String json = indexer.buildSearchPipelineBody();
		JsonNode root = mapper.readTree(json);

		JsonNode processors = root.path("phase_results_processors");
		assertTrue(processors.isArray());
		assertEquals(1, processors.size());

		JsonNode combination = processors.get(0)
				.path("score-ranker-processor").path("combination");
		assertEquals("rrf", combination.path("technique").asText());
		assertEquals(ChartSearchAiConstants.RRF_RANK_CONSTANT,
				combination.path("rank_constant").asInt());
	}

	// --- Default backend (null) falls back to Elasticsearch ---

	@Test
	public void buildSearchQuery_defaultBackend_shouldUseElasticsearchRetriever() throws IOException {
		// backendType is null by default — should produce ES format
		float[] queryVector = new float[] { 0.1f };
		String json = indexer.buildSearchQuery(1, "test", queryVector, 5);
		JsonNode root = mapper.readTree(json);
		assertFalse(root.path("retriever").isMissingNode(),
				"Default (null) backend should use ES retriever API");
	}

	// --- Client null safety tests ---

	@Test
	public void isAvailable_shouldReturnFalseWhenClientIsNull() {
		assertFalse(indexer.isAvailable());
	}

	@Test
	public void hasIndex_shouldReturnFalseWhenClientIsNull() {
		org.openmrs.Patient patient = new org.openmrs.Patient();
		patient.setPatientId(1);
		assertFalse(indexer.hasIndex(patient));
	}

	@Test
	public void search_shouldReturnEmptyWhenClientIsNull() {
		org.openmrs.Patient patient = new org.openmrs.Patient();
		patient.setPatientId(1);
		float[] queryVector = new float[] { 0.1f, 0.2f };
		List<ElasticsearchIndexer.ElasticsearchSearchResult> results =
				indexer.search(patient, "test", queryVector, 10);
		assertNotNull(results);
		assertTrue(results.isEmpty());
	}

	@Test
	public void elasticsearchSearchResult_shouldExposeFields() {
		float[] embedding = new float[] { 0.1f, 0.2f };
		String uuid42 = "00000000-0000-0000-0000-000000000042";
		ElasticsearchIndexer.ElasticsearchSearchResult result =
				new ElasticsearchIndexer.ElasticsearchSearchResult(
						"obs", uuid42, 1.5f, embedding, "test text");
		assertEquals("obs", result.getResourceType());
		assertEquals(uuid42, result.getResourceUuid());
		assertEquals(1.5f, result.getScore(), 0.001f);
		assertEquals(embedding, result.getEmbedding());
		assertEquals("test text", result.getText());
	}
}
