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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ElasticsearchIndexer;
import org.openmrs.module.chartsearchai.api.ElasticsearchIndexer.ElasticsearchSearchResult;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;

/**
 * Integration tests for the Elasticsearch kNN fallback path using a real
 * Elasticsearch instance. When BM25 returns no keyword matches for a query
 * like "any cancer?", the search method falls back to kNN results filtered
 * by a minimum cosine similarity threshold. These tests verify that fallback
 * discovers semantically related records (e.g. Kaposi sarcoma) through real
 * ES queries using the production {@link ElasticsearchIndexer#search} method.
 *
 * <p>Requires:
 * <ul>
 *   <li>ONNX embedding model files (auto-skipped when absent)</li>
 *   <li>Elasticsearch 8.14+ at {@code localhost:9200} (auto-skipped when
 *       unreachable). Start with:
 *       {@code docker run -d --name elasticsearch -p 9200:9200
 *       -e "discovery.type=single-node" -e "xpack.security.enabled=false"
 *       elasticsearch:8.17.2}</li>
 * </ul>
 *
 * <p>Uses a dedicated test index ({@code chartsearchai-knn-fallback-test})
 * that is deleted after each run.
 */
public class ElasticsearchKnnFallbackTest {

	private static final String MODEL_PATH = TestDatasetHelper.MODEL_PATH;

	private static final String VOCAB_PATH = TestDatasetHelper.VOCAB_PATH;

	private static final String ES_URI = System.getProperty(
			"chartsearchai.es.uri", "http://localhost:9200");

	private static final String TEST_INDEX = "chartsearchai-knn-fallback-test";

	private static final String SECOND_TEST_INDEX = "chartsearchai-knn-fallback-test-2";

	private static final int TEST_PATIENT_ID = 99999;

	private static final String[] DATASET =
			TestDatasetHelper.FULL_PATIENT_DATASET;

	private static OnnxEmbeddingProvider provider;

	private static RestClient restClient;

	private static ElasticsearchIndexer indexer;

	private static boolean esIsReachable() {
		try {
			RestClient probe = RestClient.builder(
					HttpHost.create(ES_URI)).build();
			try {
				Response response = probe.performRequest(
						new Request("GET", "/_cluster/health"));
				return response.getStatusLine().getStatusCode() == 200;
			} finally {
				probe.close();
			}
		} catch (Exception e) {
			return false;
		}
	}



	@BeforeAll
	static void setUp() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(TestDatasetHelper.modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);
		org.junit.jupiter.api.Assumptions.assumeTrue(esIsReachable(),
				"Skipping: Elasticsearch not reachable at " + ES_URI);

		provider = new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
		restClient = RestClient.builder(HttpHost.create(ES_URI)).build();

		// Build the indexer with real client and embedding provider.
		// Use reflection because setters and BackendType are
		// package-private (test is in a different package).
		indexer = new ElasticsearchIndexer();
		setField(indexer, "client", restClient);
		setField(indexer, "embeddingProvider", provider);

		// Ensure embedding dimensions are detected before creating indexes.
		if (provider.getDimensions() <= 0) {
			provider.embed("dimension detection");
		}

		// Create test index with the production mapping and populate it.
		// ensureIndex() handles backend detection, mapping, and pipeline.
		createAndPopulateIndex(TEST_INDEX, DATASET);
	}

	@AfterAll
	static void tearDown() throws Exception {
		deleteIndex(TEST_INDEX);
		deleteIndex(SECOND_TEST_INDEX);
		if (restClient != null) {
			restClient.close();
		}
		if (provider != null) {
			provider.close();
		}
	}

	/**
	 * Indexes the given dataset into the indexer's current index using the
	 * production {@link ElasticsearchIndexer#indexEmbeddings} method.
	 * Caller must set {@code indexName} on the indexer before calling.
	 */
	private static void indexDatasetInto(String[] dataset)
			throws Exception {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records =
				TestDatasetHelper.toSerializedRecords(dataset);
		List<org.openmrs.module.chartsearchai.model.ChartEmbedding> embeddings =
				org.openmrs.module.chartsearchai.api.EmbeddingIndexer.buildEmbeddings(
						records, provider);

		java.lang.reflect.Method indexMethod = ElasticsearchIndexer.class
				.getDeclaredMethod("indexEmbeddings", int.class, List.class);
		indexMethod.setAccessible(true);
		indexMethod.invoke(indexer, TEST_PATIENT_ID, embeddings);
	}

	/**
	 * Creates an ES index with the production mapping and populates it
	 * with the given dataset using the production {@code ensureIndex}
	 * and {@code indexEmbeddings} methods.
	 */
	private static void createAndPopulateIndex(String indexName, String[] dataset)
			throws Exception {
		deleteIndex(indexName);
		String previousIndex = getField(indexer, "indexName");
		setField(indexer, "indexName", indexName);
		setField(indexer, "indexCreated", false);
		try {
			java.lang.reflect.Method ensureIndex = ElasticsearchIndexer.class
					.getDeclaredMethod("ensureIndex");
			ensureIndex.setAccessible(true);
			ensureIndex.invoke(indexer);
			indexDatasetInto(dataset);
		} finally {
			setField(indexer, "indexName", previousIndex);
		}
	}

	private static void deleteIndex(String indexName) {
		try {
			restClient.performRequest(new Request("DELETE", "/" + indexName));
		} catch (Exception ignored) {
			// Index may not exist yet
		}
	}

	// --- Helpers ---

	private static float[] embedQuery(String question) {
		return provider.embed(LlmInferenceService.prepareEmbeddingInput(
				question, ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX));
	}

	private static List<ElasticsearchSearchResult> search(String question, int topK) {
		Patient patient = new Patient();
		patient.setPatientId(TEST_PATIENT_ID);
		// Mirror production: fetch topK * ES_FETCH_MULTIPLIER from ES,
		// then filterEsResults trims down to the relevant records.
		return indexer.search(patient,
				LlmInferenceService.stripQueryStopwords(question),
				embedQuery(question),
				topK * ChartSearchAiConstants.ES_FETCH_MULTIPLIER);
	}

	// Tests assert on dataset indices. toSerializedRecords encodes the
	// dataset index in the trailing digits of the deterministic UUID, so
	// converting back at the test boundary preserves every assertion verbatim
	// against the UUID-typed production surface.
	private static int indexOf(ElasticsearchSearchResult r) {
		return TestDatasetHelper.indexForUuid(r.getResourceUuid());
	}

	private static boolean containsRecord(List<ElasticsearchSearchResult> results,
			int... datasetIndices) {
		for (ElasticsearchSearchResult r : results) {
			int idx = indexOf(r);
			for (int datasetIndex : datasetIndices) {
				if (idx == datasetIndex) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<ElasticsearchSearchResult> searchAndFilter(
			String question, int topK) {
		float[] queryVector = embedQuery(question);
		List<ElasticsearchSearchResult> results = search(question, topK);
		String normalizedQuery = LlmInferenceService.stripQueryStopwords(question);

		// Mirror the production ES path: run the full embedding pipeline
		// on ALL patient embeddings (not just the ES-returned subset) so
		// filterPipeline sees the full score distribution for gap detection.
		List<ElasticsearchSearchResult> all =
				indexer.fetchAllPatientEmbeddings(TEST_PATIENT_ID);
		// Strip structural prefix from ES-stored text to avoid double-
		// prefixing when findSimilar calls buildPrefixedText internally.
		java.util.Set<String> prefixes =
				org.openmrs.module.chartsearchai.ChartSearchAiUtils.getAllEmbeddingPrefixes();
		List<org.openmrs.module.chartsearchai.model.ChartEmbedding> allEmbeddings =
				new java.util.ArrayList<org.openmrs.module.chartsearchai.model.ChartEmbedding>();
		for (ElasticsearchSearchResult r : all) {
			if (r.getEmbedding() == null) continue;
			org.openmrs.module.chartsearchai.model.ChartEmbedding ce =
					new org.openmrs.module.chartsearchai.model.ChartEmbedding();
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
			allEmbeddings.add(ce);
		}

		// Full-corpus pipeline (same as embedding path)
		java.util.Set<String> pipelineKeys = new java.util.HashSet<String>();
		List<org.openmrs.module.chartsearchai.model.ChartEmbedding> pipelineFiltered =
				LlmInferenceService.findSimilar(allEmbeddings, provider,
						question,
						ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX,
						PipelineConfig.defaults()).records;
		if (pipelineFiltered != null) {
			for (org.openmrs.module.chartsearchai.model.ChartEmbedding ce : pipelineFiltered) {
				pipelineKeys.add(org.openmrs.module.chartsearchai.ChartSearchAiUtils
						.resourceKey(ce.getResourceType(), ce.getResourceUuid()));
			}
		}

		// Return the original ES results whose keys survived the pipeline
		List<ElasticsearchSearchResult> out = new java.util.ArrayList<ElasticsearchSearchResult>();
		for (ElasticsearchSearchResult r : results) {
			if (pipelineKeys.contains(org.openmrs.module.chartsearchai.ChartSearchAiUtils
					.resourceKey(r.getResourceType(), r.getResourceUuid()))) {
				out.add(r);
			}
		}
		return out;
	}

	private static List<Integer> sortedIds(List<ElasticsearchSearchResult> results) {
		List<Integer> ids = new ArrayList<>();
		for (ElasticsearchSearchResult r : results) {
			ids.add(indexOf(r));
		}
		java.util.Collections.sort(ids);
		return ids;
	}

	// --- Tests ---

	@Test
	public void search_cancerQuery_shouldFindKaposiSarcomaViaKnnFallback() {
		List<ElasticsearchSearchResult> results = search("any cancer?", 10);

		assertFalse(results.isEmpty(),
				"RRF search for 'any cancer?' should return results, got empty");

		// Records 11 and 88 (0-based) are Kaposi sarcoma diagnoses.
		// With native RRF, kNN drives the ranking when BM25 has no
		// keyword matches, so Kaposi sarcoma should be in the top results.
		assertTrue(containsRecord(results, 11, 88),
				"Should find Kaposi sarcoma records in top results, got: "
				+ resultIds(results));
	}

	@Test
	public void search_cancerQuery_shouldNotReturnFullDataset() {
		List<ElasticsearchSearchResult> filtered = searchAndFilter("any cancer?", 10);

		assertTrue(filtered.size() <= 10,
				"Filtered results should respect topK, got " + filtered.size());
		assertTrue(filtered.size() < DATASET.length,
				"Filter should remove most records, got " + filtered.size()
				+ " of " + DATASET.length);
	}

	@Test
	public void search_allergyQuery_shouldFindAllergyRecordsViaBm25() {
		// "any allergies?" has BM25 keyword matches — should use normal
		// RRF path, not the kNN fallback.
		List<ElasticsearchSearchResult> results = search("any allergies?", 10);

		assertFalse(results.isEmpty(),
				"ES search should find allergy records for 'any allergies?'");

		// Record 4 (0-based) is Beef allergy
		assertTrue(containsRecord(results, 4),
				"Should find Beef allergy record (index 4), got: " + resultIds(results));
	}

	@Test
	public void search_cancerQuery_shouldReturnEmbeddingVectors() {
		List<ElasticsearchSearchResult> results = search("any cancer?", 10);

		assertFalse(results.isEmpty(), "Should have results");
		for (ElasticsearchSearchResult r : results) {
			float[] embedding = r.getEmbedding();
			assertTrue(embedding != null && embedding.length > 0,
					"Each result should include its embedding vector, got null/empty for "
					+ r.getResourceType() + ":" + r.getResourceUuid());
		}
	}

	@Test
	public void search_cancerQuery_filteredByPipeline_shouldReturnOnlyKaposiSarcoma() {
		List<ElasticsearchSearchResult> filtered = searchAndFilter("any cancer?", 10);

		assertFalse(filtered.isEmpty(),
				"Filtered results should not be empty");
		assertEquals(java.util.Arrays.asList(11, 88), sortedIds(filtered),
				"After pipeline filtering, only Kaposi sarcoma records should remain");
	}

	@Test
	public void search_conditionsQuery_filteredByPipeline_shouldReturnOnlyConditionRecords() {
		List<ElasticsearchSearchResult> filtered =
				searchAndFilter("any conditions?", 10);

		assertFalse(filtered.isEmpty(),
				"Filtered results should not be empty");

		// Records 7 and 54 (0-based) are the two "Medical condition:" records
		// (Tuberculosis and Hypertension). The embedding pipeline returns
		// exactly these two for "any conditions" — the ES pipeline with
		// post-filtering should match.
		assertEquals(java.util.Arrays.asList(7, 54), sortedIds(filtered),
				"After pipeline filtering, only condition records should remain, got: "
				+ resultIds(filtered));
	}

	@Test
	public void search_conditionsQuery_secondDataset_filteredByPipeline_shouldReturnAllConditions()
			throws Exception {
		// The second dataset has 10 "Medical condition:" records at
		// 0-based indices 1, 2, 16, 17, 29, 30, 31, 44, 55, 56.
		// Keyword rescue on "condition" should keep all 10 — same as
		// the embedding pipeline.
		runOnSecondDataset("any conditions?", filtered -> {
			assertFalse(filtered.isEmpty(),
					"Filtered results should not be empty");
			assertEquals(
					java.util.Arrays.asList(1, 2, 16, 17, 29, 30, 31, 44, 55, 56),
					sortedIds(filtered),
					"After pipeline filtering, all 10 condition records should remain, got: "
					+ resultIds(filtered));
		});
	}

	@Test
	public void search_hivStatusQuery_filteredByPipeline_shouldReturnAllHivRecords() {
		List<ElasticsearchSearchResult> filtered =
				searchAndFilter("what is the hiv status?", 10);

		assertFalse(filtered.isEmpty(),
				"Filtered results should not be empty");

		// Records 39, 40, 68, 69, 71, 110 (0-based) are the 6 HIV Disease
		// diagnoses and assessments. The embedding pipeline returns exactly
		// these for "what is the hiv status?" — the ES pipeline with
		// post-filtering should match.
		assertEquals(
				java.util.Arrays.asList(39, 40, 68, 69, 71, 110),
				sortedIds(filtered),
				"After pipeline filtering, only HIV records should remain, got: "
				+ resultIds(filtered));
	}

	@Test
	public void search_hivStatusQuery_secondDataset_filteredByPipeline_shouldReturnEmpty()
			throws Exception {
		// The second dataset has NO HIV records. The pipeline should return
		// empty — "status" must not cause false positives by matching the
		// "Status: ACTIVE" boilerplate in every condition record.
		runOnSecondDataset("what is the hiv status?", filtered ->
			assertTrue(filtered.isEmpty(),
					"Second dataset has no HIV records, filtered results should be empty, got: "
					+ resultIds(filtered)));
	}

	@Test
	public void search_immunizedQuery_secondDataset_filteredByPipeline_shouldReturnEmpty()
			throws Exception {
		// The second dataset has NO immunization records. The pipeline
		// should return empty — "has the patient ever been immunized?"
		// must not surface unrelated conditions (blood transfusion,
		// syphilitic cirrhosis, granuloma annulare).
		runOnSecondDataset("has the patient ever been immunized?", filtered ->
			assertTrue(filtered.isEmpty(),
					"Second dataset has no immunization records, "
					+ "filtered results should be empty, got: "
					+ resultIds(filtered)));
	}

	@Test
	public void search_cd4Query_secondDataset_filteredByPipeline_shouldReturnEmpty()
			throws Exception {
		// The second dataset has NO CD4 records. The pipeline should
		// return empty — pulse readings must not surface as false
		// positives for a CD4 count query.
		runOnSecondDataset(
				"what is the current cd4 count and when was it recorded?",
				filtered -> assertTrue(filtered.isEmpty(),
					"Second dataset has no CD4 records, "
					+ "filtered results should be empty, got: "
					+ resultIds(filtered)));
	}

	@Test
	public void knnMinSimilarity_shouldMatchAbsoluteSimilarityFloor() {
		assertEquals(ChartSearchAiConstants.ABSOLUTE_SIMILARITY_FLOOR,
				ChartSearchAiConstants.KNN_MIN_SIMILARITY,
				"KNN_MIN_SIMILARITY should equal ABSOLUTE_SIMILARITY_FLOOR "
				+ "so both pipelines use the same relevance bar");
	}

	// --- Helpers ---

	/**
	 * Indexes the second dataset, runs a filtered search, passes the
	 * results to the assertion, then restores the original index.
	 */
	private static void runOnSecondDataset(String question,
			java.util.function.Consumer<List<ElasticsearchSearchResult>> assertion)
			throws Exception {
		createAndPopulateIndex(SECOND_TEST_INDEX,
				TestDatasetHelper.SECOND_PATIENT_DATASET);
		setField(indexer, "indexName", SECOND_TEST_INDEX);
		try {
			assertion.accept(searchAndFilter(question, 10));
		} finally {
			setField(indexer, "indexName", TEST_INDEX);
			deleteIndex(SECOND_TEST_INDEX);
		}
	}

	private static String resultIds(List<ElasticsearchSearchResult> results) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < results.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			ElasticsearchSearchResult r = results.get(i);
			sb.append(r.getResourceType()).append(":").append(indexOf(r));
		}
		return sb.append("]").toString();
	}

	@SuppressWarnings("unchecked")
	private static <T> T getField(Object target, String fieldName)
			throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return (T) field.get(target);
	}

	private static void setField(Object target, String fieldName, Object value)
			throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

}
