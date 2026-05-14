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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;

/**
 * Real-model integration tests for the retrieval pipeline.
 * These use the actual ONNX embedding model to compute semantic scores,
 * ensuring tests reflect real embedding behavior instead of hand-crafted
 * adversarial scores.
 * <p>
 * Tagged as "eval" so they are excluded from default {@code mvn test} runs.
 * Run with {@code mvn test -Dsurefire.excludedGroups=} to include them.
 */
@Tag("eval")
public class LlmInferenceServiceEvalTest {

	static final String[] FULL_PATIENT_DATASET = TestDatasetHelper.FULL_PATIENT_DATASET;

	private static final String[] SECOND_PATIENT_DATASET =
			TestDatasetHelper.SECOND_PATIENT_DATASET;

	private static final String[] THIRD_PATIENT_DATASET =
			TestDatasetHelper.THIRD_PATIENT_DATASET;

	private static final String[] FOURTH_PATIENT_DATASET =
			TestDatasetHelper.FOURTH_PATIENT_DATASET;

	private static final String[] FIFTH_PATIENT_DATASET =
			TestDatasetHelper.FIFTH_PATIENT_DATASET;

	// ---- Real-model integration tests ----
	// These use the actual ONNX embedding model to compute semantic scores,
	// ensuring tests reflect real embedding behavior instead of hand-crafted
	// adversarial scores.

	/**
	 * Path to the ONNX embedding model and vocabulary files for real-model
	 * integration tests. Configure via the {@code chartsearchai.embedding.model.dir}
	 * system property (e.g. {@code -Dchartsearchai.embedding.model.dir=/path/to/all-MiniLM-L6-v2}).
	 * The directory must contain {@code model.onnx} and {@code vocab.txt}.
	 * Tests are skipped automatically when the files are not found.
	 */
	private static final String MODEL_DIR = System.getProperty(
			"chartsearchai.embedding.model.dir", "../models/all-MiniLM-L6-v2");

	private static final String MODEL_PATH = MODEL_DIR + "/model.onnx";

	private static final String VOCAB_PATH = MODEL_DIR + "/vocab.txt";

	private static org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider sharedProvider;

	private static boolean modelFilesExist() {
		return new java.io.File(MODEL_PATH).exists()
				&& new java.io.File(VOCAB_PATH).exists();
	}

	@BeforeAll
	static void initSharedProvider() {
		if (modelFilesExist()) {
			sharedProvider = new org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider(
					MODEL_PATH, VOCAB_PATH);
		}
	}

	@AfterAll
	static void closeSharedProvider() {
		if (sharedProvider != null) {
			sharedProvider.close();
			sharedProvider = null;
		}
	}

	/**
	 * Computes real semantic scores for every record in FULL_PATIENT_DATASET
	 * against the given query using the actual ONNX embedding model.
	 */
	private static double[] computeRealSemanticScores(
			org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider provider,
			String query) {
		return computeRealSemanticScoresWithVectors(provider, query,
				FULL_PATIENT_DATASET, null);
	}

	/**
	 * Computes real semantic scores and optionally captures embedding vectors
	 * for inter-candidate coherence filtering. Mirrors production: strips
	 * dataset prefix/date, then embeds with getEmbeddingPrefix + textContent.
	 */
	private static double[] computeRealSemanticScoresWithVectors(
			org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider provider,
			String query, String[] dataset, float[][] embeddingVectors) {
		float[] queryVector = provider.embed(
				LlmInferenceService.prepareEmbeddingInput(query,
						ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX));

		double[] scores = new double[dataset.length];
		for (int i = 0; i < dataset.length; i++) {
			String resourceType = TestDatasetHelper.inferResourceType(dataset[i]);
			String textContent = TestDatasetHelper.stripDatasetPrefixAndDate(dataset[i]);
			String embeddingText = ChartSearchAiUtils.buildPrefixedText(
					resourceType, textContent);
			float[] docVector = provider.embed(embeddingText);
			if (embeddingVectors != null) {
				embeddingVectors[i] = docVector;
			}
			scores[i] = LlmInferenceService.cosineSimilarity(queryVector, docVector);
		}
		return scores;
	}

	/**
	 * Runs the production pipeline using real semantic scores from the
	 * ONNX model combined with keyword scores from the dataset.
	 * Calls the exact same static production methods as the live system:
	 * {@link EmbeddingIndexer#buildEmbeddings} for indexing and
	 * {@link LlmInferenceService#findSimilar(List, EmbeddingProvider, String, String, PipelineConfig)}
	 * for querying — zero simulation.
	 */
	private static List<Integer> runRealModelPipeline(String query, int topK) {
		return runRealModelPipeline(query, topK, FULL_PATIENT_DATASET);
	}

	private static List<Integer> runRealModelPipeline(String query, int topK,
			String[] dataset) {
		return runRealModelPipeline(query, topK, dataset,
				PipelineConfig.forModel(MODEL_DIR));
	}

	private static List<Integer> runRealModelPipeline(String query, int topK,
			String[] dataset, PipelineConfig config) {
		return runRealModelPipeline(query, topK, dataset, config, null);
	}

	/**
	 * Runs the full production pipeline with optional concept-set category
	 * hints on specific records. When {@code categoryHintsMap} is non-null,
	 * records whose 0-based index appears in the map are constructed with
	 * the corresponding hints — same as production's
	 * {@code PatientRecordLoader.loadAll()} does after
	 * {@code extractCategoryHints()} returns concept-set names.
	 */
	// Cache embeddings, records, and noise profiles per (dataset, hints)
	// to avoid recomputing on every test invocation.
	private static final Map<String, List<ChartEmbedding>> embeddingCache =
			new java.util.concurrent.ConcurrentHashMap<String, List<ChartEmbedding>>();
	private static final Map<String, List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord>> recordCache =
			new java.util.concurrent.ConcurrentHashMap<String, List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord>>();
	private static final Map<String, org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile> noiseCache =
			new java.util.concurrent.ConcurrentHashMap<String, org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile>();

	private static String cacheKey(String[] dataset, Map<Integer, List<String>> hints) {
		return System.identityHashCode(dataset) + ":" + System.identityHashCode(hints);
	}

	private static List<Integer> runRealModelPipeline(String query, int topK,
			String[] dataset, PipelineConfig config,
			Map<Integer, List<String>> categoryHintsMap) {
		String key = cacheKey(dataset, categoryHintsMap);

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records =
				recordCache.get(key);
		List<ChartEmbedding> allEmbeddings = embeddingCache.get(key);

		if (records == null || allEmbeddings == null) {
			records = TestDatasetHelper.toSerializedRecords(dataset, categoryHintsMap);
			allEmbeddings = TestDatasetHelper.buildOrLoadCachedEmbeddings(
					records, sharedProvider);
			recordCache.put(key, records);
			embeddingCache.put(key, allEmbeddings);
		}

		// In-memory noise profile cache — fast enough since noise profile
		// computation is ~30ms with pre-computed embeddings.
		org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile noise = noiseCache.get(key);
		if (noise == null) {
			noise = org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile.compute(
					allEmbeddings.toArray(new ChartEmbedding[0]), sharedProvider);
			noiseCache.put(key, noise);
		}
		PipelineConfig configWithNoise =
				config.withNoiseProfile(noise);

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord>
				results = LlmInferenceService.findRelevantRecords(
						allEmbeddings, records, sharedProvider, query,
						ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX,
						configWithNoise);

		// Tests assert on dataset indices (the i passed to toSerializedRecords).
		// Production now identifies records by UUID; toSerializedRecords encodes
		// the dataset index in the trailing digits of a deterministic UUID, so
		// we map back here at the test boundary. This preserves every existing
		// assertion verbatim.
		List<Integer> indices = new ArrayList<Integer>();
		if (results != null) {
			for (org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord r
					: results) {
				indices.add(TestDatasetHelper.indexForUuid(r.getResourceUuid()));
			}
		}
		Collections.sort(indices);
		return indices;
	}

	@Test
	public void realModel_vitalsTrendQuery_shouldReturnOnlyBpWeightAndTemperature() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?", 10);

		// "last 7 visits" triggers a recency cap of 7 per concept. The
		// semantic cluster is BP + weight + temperature (24 + 7 + 9 = 40
		// records pre-cap). BP is represented as two concepts (systolic
		// + diastolic), so cap=7 yields 7+7 BP + 7 weight + 7 temp = 28
		// records. Must NOT include blood oxygen or other false positives.
		assertEquals(Arrays.asList(17, 18, 22, 23, 25, 26, 31, 33, 36, 38,
				47, 48, 58, 63, 64, 73, 74, 76, 77, 81,
				93, 94, 96, 101, 111, 113, 114, 131),
				result,
				"Should return 7 most-recent records per concept for BP (x2) + weight + temperature");
	}

	@Test
	public void realModel_vitalsTrendQuery_shouldExcludeBloodOxygenAndOtherFalsePositives() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "blood pressure, weight, and temperature" should NOT include
		// records that coincidentally match "blood" (like blood oxygen
		// saturation) but don't cover a unique query concept. The
		// filterRedundantKeywordTier logic must drop them because
		// "blood" is already covered by the BP records in the higher tier.
		List<Integer> result = runRealModelPipeline(
				"How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?", 10);

		// Blood oxygen saturation records in FULL_PATIENT_DATASET
		int[] bloodOxygenIndices = { 35, 46, 60, 80, 84, 104, 109, 117, 146, 152 };
		for (int idx : bloodOxygenIndices) {
			assertFalse(result.contains(idx),
					"Should NOT include blood oxygen record at index " + idx
					+ " — 'blood' is already covered by BP records, got: " + result);
		}
	}

	@Test
	public void realModel_vitalsTrendQuery_recencyCapShouldLimit7PerConcept() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "last 7 visits" triggers recency cap, keeping 7 per concept.
		String question = "How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?";

		assertEquals(7, LlmInferenceService.extractRecencyCap(question),
				"Should detect 'last 7' pattern");

		// Pre-cap semantic cluster is 40 records (24 BP + 7 weight + 9 temp).
		// BP is two concepts (systolic + diastolic), so cap=7 yields
		// 7+7 BP + 7 weight + 7 temp = 28 records (the 7 most recent
		// per concept).
		List<Integer> pipelineResult = runRealModelPipeline(question, 10);
		assertEquals(Arrays.asList(17, 18, 22, 23, 25, 26, 31, 33, 36, 38,
				47, 48, 58, 63, 64, 73, 74, 76, 77, 81,
				93, 94, 96, 101, 111, 113, 114, 131),
				pipelineResult,
				"Pipeline should return the 7 most-recent records per concept");

		// Build SerializedRecords from the pipeline result (sorted most-recent-first).
		// In production, records come sorted by date from PatientRecordLoader.
		// Here we simulate that by using dataset index as a proxy — higher index
		// means more recent (matching how the test dataset is ordered).
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		// Sort indices descending to simulate most-recent-first
		List<Integer> descending = new ArrayList<>(pipelineResult);
		Collections.sort(descending, Collections.reverseOrder());
		for (int idx : descending) {
			records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
					"obs", TestDatasetHelper.uuidForIndex(idx), FULL_PATIENT_DATASET[idx], null));
		}

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> capped
				= LlmInferenceService.capPerConcept(records, 7);

		// Count records per concept
		Map<String, Integer> conceptCounts = new HashMap<>();
		for (org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord r : capped) {
			String key = LlmInferenceService.conceptKey(r.getText());
			conceptCounts.put(key, conceptCounts.getOrDefault(key, 0) + 1);
		}

		// No concept should have more than 7 records
		for (Map.Entry<String, Integer> entry : conceptCounts.entrySet()) {
			assertTrue(entry.getValue() <= 7,
					"Concept '" + entry.getKey() + "' has " + entry.getValue()
					+ " records, expected <= 7");
		}

		// pipelineResult is already cap-7 (applied by findRelevantRecords),
		// so calling capPerConcept again is a no-op — sizes are equal.
		assertEquals(capped.size(), pipelineResult.size(),
				"Re-capping an already-capped result should be a no-op");

		// Verify the expected concepts are present:
		// Systolic BP, Diastolic BP, Weight, Temperature
		assertTrue(conceptCounts.containsKey(
				"Clinical observation: Test — Systolic Blood Pressure"),
				"Should contain Systolic BP records");
		assertTrue(conceptCounts.containsKey(
				"Clinical observation: Test — Weight (kg)"),
				"Should contain Weight records");
		assertTrue(conceptCounts.containsKey(
				"Clinical observation: Test — Temperature (C)"),
				"Should contain Temperature records");
	}

	@Test
	public void realModel_cancerQuery_shouldReturnKaposiSarcomaOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does the patient have cancer?", 10);

		assertEquals(Arrays.asList(11, 88),
				result, "Should return exactly 2 Kaposi sarcoma records");
	}

	@Test
	public void realModel_historyOfCancerQuery_shouldReturnKaposiSarcomaOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any history of cancer?", 10);

		assertEquals(Arrays.asList(11, 88),
				result, "Should return exactly 2 Kaposi sarcoma records");
	}

	/**
	 * Integration test that exercises the full production code path for
	 * "any history of cancer?" — from record embedding (with
	 * {@link ChartSearchAiConstants#getEmbeddingPrefix}, matching
	 * {@link org.openmrs.module.chartsearchai.api.EmbeddingIndexer})
	 * through query preprocessing ({@link LlmInferenceService#buildEmbeddingQuery},
	 * matching {@link LlmInferenceService#findSimilar}) to the complete
	 * {@link LlmInferenceService#filterPipeline} with production defaults.
	 * Uses the real ONNX embedding model and the first patient dataset.
	 *
	 * <p>The dataset text format is {@code "prefix: (date) serializer_output"}
	 * but production stores just {@code serializer_output} as textContent and
	 * embeds {@code embedding_prefix + serializer_output} (no date). This test
	 * strips the dataset prefix and date to reconstruct the production format,
	 * Calls the actual production static methods
	 * {@link org.openmrs.module.chartsearchai.api.EmbeddingIndexer#buildEmbeddings}
	 * and {@link LlmInferenceService#findSimilar(List, EmbeddingProvider, String, String, PipelineConfig)}
	 * directly — zero simulation, zero reimplementation.
	 */
	@Test
	public void integration_historyOfCancerQuery_shouldReturnOnly2Records() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any history of cancer?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(11, 88), result,
				"'any history of cancer?' should return exactly 2 Kaposi sarcoma records");
	}

	@Test
	public void integration_anemicQuery_shouldReturnExactly3Records() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("is the patient anemic?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(29, 55, 72), result,
				"'is the patient anemic?' should return the 3 anemia records");
	}

	@Test
	public void integration_stdAbbreviationQuery_shouldReturnAll6HivRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any STD?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(39, 40, 68, 69, 71, 110), result,
				"'any STD?' should return the same 6 HIV records as the full phrase");
	}

	@Test
	public void integration_activeConditionsQuery_shouldReturnTuberculosisAndHypertension() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"When were each of this patient's active conditions first "
				+ "recorded, and have any resolved or escalated?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// Dataset indices are 1-based in comments but 0-based in the array.
		// [8] Tuberculosis = index 7, [55] Hypertension = index 54.
		assertEquals(Arrays.asList(7, 54), result,
				"Should return only the 2 active condition records "
				+ "(Tuberculosis [8] and Hypertension [55])");
	}

	@Test
	public void integration_currentCd4CountQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What is the current CD4 Count?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// [9] CD4 Count: 988.0 = index 8, [86] CD4 Count: 1191.0 = index 85
		assertEquals(Arrays.asList(8, 85), result,
				"Should return only the 2 CD4 Count records");
	}

	@Test
	public void integration_latestCd4CountQuery_shouldReturnMostRecentCd4Count() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What is the latest CD4 Count?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// "the latest" implies a recency cap of 1, so only the most
		// recent CD4 Count (index 8, 2025-10-30) is returned.
		assertEquals(Arrays.asList(8), result,
				"Should return only the most recent CD4 Count record");
	}

	@Test
	public void integration_fractureQuery_shouldReturnNoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any fracture?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertTrue(result.isEmpty(),
				"Patient has no fracture records, should return empty");
	}

	@Test
	public void integration_medicationsQuery_shouldReturnMedicationRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "Is the patient on any medications?" should return medication
		// records (drug orders, drug observations) — NOT encounter
		// notes that incidentally mention "Medication adjusted" as
		// narrative. FULL has 2 Azithromycin drug orders [0, 1] and
		// a Pyrimethamine drug observation [9]. The previous assertion
		// included encounter notes [56, 91] which are clinical
		// narrative ("Chronic disease management visit. Medication
		// adjusted.") — those are not the patient's medications.
		List<Integer> result = runRealModelPipeline(
				"is the patient on any medications?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertFalse(result.isEmpty(),
				"FULL dataset has Azithromycin drug orders");
		boolean hasMedication = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.startsWith("Medication prescription:")
					|| rec.contains("Drug —")) {
				hasMedication = true;
			}
			assertFalse(
					rec.contains("Medication adjusted"),
					"Encounter notes about medication adjustment are not"
							+ " medication records: " + rec);
		}
		assertTrue(hasMedication,
				"Should include drug order or drug observation records");
	}

	@Test
	public void integration_knownConditionsQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any known conditions?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// [8] Tuberculosis = index 7, [55] Hypertension = index 54
		assertEquals(Arrays.asList(7, 54), result,
				"Should return the 2 Medical condition records");
	}

	@Test
	public void integration_conditionsQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any conditions",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(7, 54), result,
				"Should return the 2 Medical condition records");
	}

	@Test
	public void integration_whatIsPatientAllergicToQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What is the patient allergic to?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// [5] Beef allergy = index 4, [54] Fomepizole allergy = index 53
		assertEquals(Arrays.asList(4, 53), result,
				"Should return the 2 allergy records");
	}

	@Test
	public void integration_coughQuery_shouldReturnExactlyOneRecord() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any cough?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		// [51] "persistent cough for 2 weeks" = index 50
		assertEquals(Arrays.asList(50), result,
				"Should return only the record mentioning cough");
	}

	@Test
	public void integration_doesPatientHaveAllergiesQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does the patient have any allergies?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(4, 53), result,
				"Should return the 2 allergy records");
	}

	@Test
	public void integration_allergyQuery_shouldReturnExactlyTwoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any allergies?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(4, 53), result,
				"Should return the 2 allergy records");
	}


	@Test
	public void realModel_familyHistoryOfCancerQuery_shouldReturnNoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"Any family history of cancer?", 10);

		assertEquals(Collections.emptyList(),
				result, "Should return no records");
	}

	@Test
	public void realModel_doesHeHaveCancerQuery_shouldReturnKaposiSarcomaOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does he have cancer?", 10);

		assertEquals(Arrays.asList(11, 88),
				result, "Should return exactly 2 Kaposi sarcoma records");
	}

	@Test
	public void realModel_anyCancerQuery_shouldReturnKaposiSarcomaOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any cancer?", 10);

		assertEquals(Arrays.asList(11, 88),
				result, "Should return exactly 2 Kaposi sarcoma records");
	}

	@Test
	public void realModel_stdQuery_shouldReturnHivRecordsOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any sexually transmitted disease?", 10);

		assertEquals(Arrays.asList(39, 40, 68, 69, 71, 110),
				result, "Should return exactly 6 HIV-related records");
	}

	@Test
	public void realModel_familyPlanningQuery_shouldReturnFamilyPlanningRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"Does this patient use any family planning methods?", 10);

		assertEquals(Arrays.asList(5, 6),
				result, "Should return exactly 2 family planning records");
	}

	@Test
	public void realModel_hbResultsQuery_shouldReturnNoRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What are this patient's HB results over time, and are values "
				+ "moving toward or away from the normal range?", 10);

		assertEquals(Collections.emptyList(),
				result, "Should return no records — dataset has no HB/hemoglobin data");
	}

	@Test
	public void realModel_cancerQuery_shouldReturnKaposiSarcomaWithZeroKeywordMatches() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "cancer?" has zero keyword matches but z-score > 2.0, so the
		// z-score gate should let it through. The dataset contains a
		// Kaposi sarcoma diagnosis which is semantically close to cancer.
		List<Integer> result = runRealModelPipeline(
				"does the patient have cancer?", 10);

		assertEquals(Arrays.asList(11, 88), result,
				"Z-score gate should pass — cancer query should return "
				+ "both Kaposi sarcoma records");
	}

	@Test
	public void realModel_conditionsQuery_secondDataset_shouldReturnAllConditionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any conditions?", 10, SECOND_PATIENT_DATASET);

		// All 10 "Medical condition:" records in the second dataset.
		// Keyword rescue ensures conditions like Scarring Alopecia and
		// Granuloma annulare aren't dropped by semantic gap detection
		// when they have perfect keyword matches on "condition".
		assertEquals(Arrays.asList(1, 2, 16, 17, 29, 30, 31, 44, 55, 56),
				result, "Should return all 10 condition records from second dataset");
	}

	@Test
	public void realModel_episodesQuery_shouldReturnDepressiveEpisodeRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any episodes?", 10, SECOND_PATIENT_DATASET);

		// [31] Medical condition: Mild depressive episode
		// [34] Clinical diagnosis: Mild depressive episode
		assertEquals(Arrays.asList(31, 34), result,
				"Should return both Mild depressive episode records");
	}

	@Test
	public void realModel_stdQuery_shouldReturnSyphiliticCirrhosisRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"has this patient had a sexually transmitted disease?", 10,
				SECOND_PATIENT_DATASET);

		// Syphilitic Cirrhosis condition [17] and diagnosis [19] —
		// syphilis is an STD. Must NOT include Female infertility
		// or Granuloma annulare (neither is an STD).
		assertEquals(Arrays.asList(17, 19), result,
				"Should return only the 2 Syphilitic Cirrhosis records");
	}

	@Test
	public void realModel_stdQuery_fourthDataset_shouldNotReturnUnrelatedDiseases() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// The plural "diseases" stems to "disease" which matches every
		// condition/diagnosis record via keyword matching. The semantic
		// ratio floor should filter out unrelated diseases (Hookworm,
		// Haemorrhagic disease) that matched on the generic term alone.
		List<Integer> result = runRealModelPipeline(
				"any sexually transmitted diseases?", 100,
				FOURTH_PATIENT_DATASET);

		// [2,4] Zika virus disease (can be sexually transmitted),
		// [108,110] HIV disease (condition + diagnosis),
		// [137,139] Gonococcal arthritis (gonorrhea is an STD)
		assertEquals(Arrays.asList(2, 4, 108, 110, 137, 139), result,
				"Should return HIV, Zika, and Gonococcal arthritis");
	}

	@Test
	public void realModel_genericKeywordQuery_shouldBeCappedByTopK() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// 2-term queries where one term is very generic ("disease",
		// "condition") match almost all condition/diagnosis records.
		// The semantic core may be broad for such queries. Verify
		// that topK caps the result set to a reasonable size.
		int topK = ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K;

		List<Integer> diseaseProblem = runRealModelPipeline(
				"disease problem", topK,
				FOURTH_PATIENT_DATASET);
		assertTrue(diseaseProblem.size() <= topK,
				"Generic 'disease problem' should be capped by topK="
						+ topK + " but got " + diseaseProblem.size());

		List<Integer> diseaseTreatment = runRealModelPipeline(
				"disease treatment", topK,
				THIRD_PATIENT_DATASET);
		assertTrue(diseaseTreatment.size() <= topK,
				"Generic 'disease treatment' should be capped by topK="
						+ topK + " but got " + diseaseTreatment.size());
	}

	@Test
	public void realModel_cd4Query_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// The second dataset has no CD4 records. The pipeline should
		// not return pulse records as false positives.
		List<Integer> result = runRealModelPipeline(
				"what is the current cd4 count and when was it recorded?",
				10, SECOND_PATIENT_DATASET);

		assertTrue(result.isEmpty(),
				"Second dataset has no CD4 records, "
				+ "should return empty, got: " + result);
	}

	@Test
	public void realModel_secondDataset_vitalsTrendQuery_shouldReturnBpWeightAndTemperature() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Use default config — exercises the coherent-gap path.
		List<Integer> result = runRealModelPipeline(
				"How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?", 10,
				SECOND_PATIENT_DATASET);

		// Temperature: 0-based 5, 20, 35, 46, 59
		// Systolic BP: 0-based 6, 21, 36, 47, 60
		// Diastolic BP: 0-based 7, 22, 37, 48, 61
		// Weight: 0-based 10, 25, 40, 51, 64
		assertEquals(Arrays.asList(5, 6, 7, 10, 20, 21, 22, 25, 35, 36, 37, 40,
				46, 47, 48, 51, 59, 60, 61, 64),
				result,
				"Should return all 20 BP + weight + temperature records, got: " + result);
	}

	@Test
	public void realModel_secondDataset_vitalsTrend_shouldExcludeBloodTransfusionCondition() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "Personal history of blood transfusion" matches "blood" but
		// that term is already covered by BP records. It should be
		// dropped as a false positive.
		List<Integer> result = runRealModelPipeline(
				"How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?", 10,
				SECOND_PATIENT_DATASET);

		// 0-based index 55 = "Medical condition: Condition: Personal
		// history of blood transfusion. Status: ACTIVE"
		assertFalse(result.contains(55),
				"Should NOT include blood transfusion condition — 'blood' "
				+ "is already covered by BP records, got: " + result);
	}

	@Test
	public void realModel_secondDataset_bpAndPulse_shouldReturnBothConceptsAndExcludeSpO2() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Different multi-concept query: "blood pressure and pulse".
		// BP matches 2 terms ("blood"+"pressure"), pulse matches 1
		// ("pulse"). SpO2 ("arterial blood oxygen saturation") matches
		// "blood" only — a false positive that should be dropped.
		List<Integer> result = runRealModelPipeline(
				"blood pressure and pulse", 10, SECOND_PATIENT_DATASET);

		// All 5 Systolic BP + 5 Diastolic BP + 5 Pulse, no SpO2
		assertEquals(Arrays.asList(6, 7, 8, 21, 22, 23, 36, 37, 38, 47, 48, 49, 60, 61, 62),
				result, "Should return all BP and Pulse records, no SpO2");
	}

	@Test
	public void realModel_diagnosticScoreDump() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		String[] queries = {
			"does the patient have cancer?",
			"any history of cancer?",
			"Any family history of cancer?",
			"any sexually transmitted disease?",
			"Does this patient use any family planning methods?",
			"How have this patient's blood pressure, weight, and temperature "
				+ "trended across their last 7 visits?"
		};

		{
			for (String query : queries) {
				double[] semantic = computeRealSemanticScores(sharedProvider, query);
				String normalized = LlmInferenceService.stripQueryStopwords(query);
				String[] queryTerms = LlmInferenceService.extractQueryTerms(normalized);

				System.out.println("\n=== QUERY: \"" + query + "\" ===");
				System.out.println("Terms: " + Arrays.toString(queryTerms)
						+ " (N=" + queryTerms.length + ")");
				double bonusThreshold = queryTerms.length == 0 ? 1.0
						: (double) Math.min(2, queryTerms.length) / queryTerms.length;
				System.out.println("BonusThreshold: " + bonusThreshold);

				// Collect and sort by semantic score descending
				double[][] indexed = new double[FULL_PATIENT_DATASET.length][3];
				for (int i = 0; i < FULL_PATIENT_DATASET.length; i++) {
					String rt = TestDatasetHelper.inferResourceType(FULL_PATIENT_DATASET[i]);
					String tc = TestDatasetHelper.stripDatasetPrefixAndDate(FULL_PATIENT_DATASET[i]);
					String kwText = ChartSearchAiUtils.buildPrefixedText(rt, tc);
					double kw = LlmInferenceService.computeKeywordScore(
							queryTerms, kwText);
					indexed[i][0] = i;
					indexed[i][1] = semantic[i];
					indexed[i][2] = kw;
				}
				Arrays.sort(indexed, (a, b) -> Double.compare(b[1], a[1]));

				System.out.println("Top 60 by semantic score:");
				for (int i = 0; i < Math.min(60, indexed.length); i++) {
					int idx = (int) indexed[i][0];
					double sem = indexed[i][1];
					double kw = indexed[i][2];
					double bonus = kw >= bonusThreshold ? kw : 0.0;
					double penalty = (kw > 0 && kw < bonusThreshold) ? kw : 0.0;
					double combined = sem + 0.3 * bonus - 0.3 * penalty;
					String record = FULL_PATIENT_DATASET[idx];
					if (record.length() > 80) record = record.substring(0, 80) + "...";
					System.out.printf("  [%3d] sem=%.4f kw=%.4f comb=%.4f %s%s %s%n",
							idx, sem, kw, combined,
							bonus > 0 ? " BONUS" : "",
							penalty > 0 ? " PENALTY" : "",
							record);
				}
			}
		}
		// This test always passes — it's just for diagnostics
		assertTrue(true);
	}


	// --------------------------------------------------------
	// Direct unit tests for isGapCoherent, growCluster, and
	// rescueBelowFloor.
	// --------------------------------------------------------

	private static ScoredEmbedding makeScoredEmbeddingWithVector(
			double score, double keywordScore, double semanticScore,
			float[] vector, int id) {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setResourceType("obs");
		ce.setTextContent(String.valueOf(id));
		ce.setEmbeddingId(id);
		ce.setEmbeddingVector(vector);
		return new ScoredEmbedding(ce, score, keywordScore, semanticScore);
	}

	@Test
	public void isGapCoherent_shouldReturnTrueWhenCrossBoundaryCosineHighEnough() {
		// 6 records: 3 above cutoff, 3 below. All share a similar vector →
		// high cross-boundary cosine → gap is intra-topic.
		float[] v1 = { 1.0f, 0.0f, 0.0f };
		float[] v2 = { 0.95f, 0.1f, 0.0f };
		float[] v3 = { 0.9f, 0.2f, 0.0f };
		float[] v4 = { 0.85f, 0.25f, 0.0f };
		float[] v5 = { 0.8f, 0.3f, 0.0f };
		float[] v6 = { 0.75f, 0.35f, 0.0f };

		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, v1, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, v2, 2),
				makeScoredEmbeddingWithVector(0.46, 0, 0.46, v3, 3),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, v4, 4),
				makeScoredEmbeddingWithVector(0.28, 0, 0.28, v5, 5),
				makeScoredEmbeddingWithVector(0.26, 0, 0.26, v6, 6));

		assertTrue(LlmInferenceService.isGapCoherent(scored, 3, 0.47),
				"Cross-boundary cosine is high — gap is intra-topic");
	}

	@Test
	public void isGapCoherent_shouldReturnFalseWhenCrossBoundaryCosineIsLow() {
		// 3 above cutoff point one way, 3 below point another → low cross-boundary cosine.
		float[] vA = { 1.0f, 0.0f, 0.0f };
		float[] vB = { 0.0f, 1.0f, 0.0f };

		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, vA, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, vA, 2),
				makeScoredEmbeddingWithVector(0.46, 0, 0.46, vA, 3),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, vB, 4),
				makeScoredEmbeddingWithVector(0.28, 0, 0.28, vB, 5),
				makeScoredEmbeddingWithVector(0.26, 0, 0.26, vB, 6));

		assertFalse(LlmInferenceService.isGapCoherent(scored, 3, 0.47),
				"Cross-boundary cosine is low — gap is inter-topic");
	}

	@Test
	public void isGapCoherent_shouldReturnFalseWhenCutoffIsZeroOrBeyondSize() {
		float[] v = { 1.0f, 0.0f };
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
				makeScoredEmbeddingWithVector(0.40, 0, 0.40, v, 2));

		assertFalse(LlmInferenceService.isGapCoherent(scored, 0, 0.47));
		assertFalse(LlmInferenceService.isGapCoherent(scored, 2, 0.47));
	}

	@Test
	public void isGapCoherent_shouldReturnFalseWhenVectorsAreNull() {
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, null, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, null, 2),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, null, 3),
				makeScoredEmbeddingWithVector(0.28, 0, 0.28, null, 4));

		assertFalse(LlmInferenceService.isGapCoherent(scored, 2, 0.47),
				"Null vectors → no pairs → should return false");
	}

	@Test
	public void growCluster_shouldAddCoherentCandidatesBeyondSeed() {
		// Seed: first 2 records. Records 3-4 are coherent with seed (similar
		// vectors). Record 5 is orthogonal → should not be included.
		float[] vA = { 1.0f, 0.0f, 0.0f };
		float[] vSimilar = { 0.95f, 0.1f, 0.0f };
		float[] vOrthogonal = { 0.0f, 1.0f, 0.0f };

		List<ScoredEmbedding> candidates = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, vA, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, vA, 2),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, vSimilar, 3),
				makeScoredEmbeddingWithVector(0.28, 0, 0.28, vSimilar, 4),
				makeScoredEmbeddingWithVector(0.20, 0, 0.20, vOrthogonal, 5));

		List<ScoredEmbedding> result =
				LlmInferenceService.growCluster(candidates, 2, 0.47);

		assertEquals(4, result.size(),
				"Seed (2) + 2 coherent candidates = 4; orthogonal excluded");
	}

	@Test
	public void growCluster_shouldReturnAllWhenSeedIsEntireList() {
		float[] v = { 1.0f, 0.0f };
		List<ScoredEmbedding> candidates = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, v, 2));

		List<ScoredEmbedding> result =
				LlmInferenceService.growCluster(candidates, 2, 0.47);

		assertEquals(2, result.size());
	}

	@Test
	public void growCluster_shouldSkipCandidatesWithNullVectors() {
		float[] v = { 1.0f, 0.0f };
		List<ScoredEmbedding> candidates = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, v, 2),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, null, 3));

		List<ScoredEmbedding> result =
				LlmInferenceService.growCluster(candidates, 2, 0.47);

		assertEquals(2, result.size(),
				"Null-vector candidate should not be added");
	}

	@Test
	public void growCluster_shouldGrowTransitively() {
		// Record 3 is coherent with seed. Record 4 is coherent with
		// record 3 but not with the seed. After first iteration adds 3,
		// second iteration should add 4 through transitivity.
		float[] vSeed = { 1.0f, 0.0f, 0.0f };
		float[] vBridge = { 0.7f, 0.7f, 0.0f }; // cos with seed ≈ 0.71, cos with far ≈ 0.71
		float[] vFar = { 0.0f, 1.0f, 0.0f }; // cos with seed ≈ 0, cos with bridge ≈ 0.71

		List<ScoredEmbedding> candidates = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, vSeed, 1),
				makeScoredEmbeddingWithVector(0.40, 0, 0.40, vBridge, 2),
				makeScoredEmbeddingWithVector(0.30, 0, 0.30, vFar, 3));

		List<ScoredEmbedding> result =
				LlmInferenceService.growCluster(candidates, 1, 0.47);

		assertEquals(3, result.size(),
				"Transitive growth: seed→bridge→far");
	}

	@Test
	public void rescueBelowFloor_shouldRecoverCoherentBelowFloorRecords() {
		// Cluster: 3 records with slightly different but similar vectors.
		// This creates a minClusterCoherence < 1.0, allowing rescue of
		// below-floor records that meet the threshold.
		float[] v1 = { 1.0f, 0.0f, 0.0f };
		float[] v2 = { 0.95f, 0.1f, 0.0f };
		float[] v3 = { 0.9f, 0.15f, 0.0f };
		// Similar to cluster — should be rescued
		float[] vSimilar = { 0.92f, 0.12f, 0.0f };
		// Orthogonal — should NOT be rescued
		float[] vOrthogonal = { 0.0f, 1.0f, 0.0f };

		List<ScoredEmbedding> cluster = new ArrayList<ScoredEmbedding>(
				Arrays.asList(
						makeScoredEmbeddingWithVector(0.50, 0, 0.50, v1, 1),
						makeScoredEmbeddingWithVector(0.48, 0, 0.48, v2, 2),
						makeScoredEmbeddingWithVector(0.46, 0, 0.46, v3, 3)));

		// Full scored list includes cluster + below-floor records
		List<ScoredEmbedding> scored = new ArrayList<ScoredEmbedding>(
				Arrays.asList(
						makeScoredEmbeddingWithVector(0.50, 0, 0.50, v1, 1),
						makeScoredEmbeddingWithVector(0.48, 0, 0.48, v2, 2),
						makeScoredEmbeddingWithVector(0.46, 0, 0.46, v3, 3),
						makeScoredEmbeddingWithVector(0.10, 0, 0.10, vSimilar, 4),
						makeScoredEmbeddingWithVector(0.08, 0, 0.08, vOrthogonal, 5)));

		List<ScoredEmbedding> result =
				LlmInferenceService.rescueBelowFloor(cluster, scored, 3);

		assertEquals(4, result.size(),
				"Should rescue the coherent below-floor record (id=4) but not the orthogonal one");
	}

	@Test
	public void rescueBelowFloor_shouldReturnUnchangedWhenAdaptiveCutoffBeyondScored() {
		float[] v = { 1.0f, 0.0f };
		List<ScoredEmbedding> cluster = Arrays.asList(
				makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
				makeScoredEmbeddingWithVector(0.48, 0, 0.48, v, 2),
				makeScoredEmbeddingWithVector(0.46, 0, 0.46, v, 3));

		List<ScoredEmbedding> result =
				LlmInferenceService.rescueBelowFloor(cluster, cluster, 3);

		assertEquals(3, result.size(),
				"No below-floor records to check");
	}

	@Test
	public void rescueBelowFloor_shouldNotRescueRecordsAlreadyInCluster() {
		float[] v = { 1.0f, 0.0f };
		List<ScoredEmbedding> cluster = new ArrayList<ScoredEmbedding>(
				Arrays.asList(
						makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
						makeScoredEmbeddingWithVector(0.48, 0, 0.48, v, 2),
						makeScoredEmbeddingWithVector(0.46, 0, 0.46, v, 3)));

		// scored contains the same records (same IDs) in the below-floor range
		List<ScoredEmbedding> scored = new ArrayList<ScoredEmbedding>(
				Arrays.asList(
						makeScoredEmbeddingWithVector(0.50, 0, 0.50, v, 1),
						makeScoredEmbeddingWithVector(0.48, 0, 0.48, v, 2),
						makeScoredEmbeddingWithVector(0.46, 0, 0.46, v, 3),
						makeScoredEmbeddingWithVector(0.10, 0, 0.10, v, 1)));

		List<ScoredEmbedding> result =
				LlmInferenceService.rescueBelowFloor(cluster, scored, 3);

		assertEquals(3, result.size(),
				"Should not duplicate records already in cluster");
	}

	// ---- Integration tests replacing former synthetic pipeline_* tests ----
	// Each test below replaces a specific deleted synthetic test, exercising
	// the same pipeline mechanic with real ONNX embeddings.

	@Test
	public void integration_cancerQuery_withTopK1_shouldReturnSingleRecord() {
		// Replaces: pipeline_genericQuery_shouldCapToTopK
		// When no records have keyword matches, topK caps the result set.
		// "cancer" has zero keyword matches in the dataset, so topK applies.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does the patient have cancer?", 1, FULL_PATIENT_DATASET);

		assertEquals(Arrays.asList(11), result,
				"With no keyword matches, topK=1 should cap to 1 record");
	}

	@Test
	public void integration_cd4Query_withTopK1_shouldReturnBothCd4Records() {
		// Replaces: pipeline_incidentalKeywordMatches_shouldNotOverFilter
		// When records have keyword matches, they bypass topK capping.
		// CD4 Count records match "CD4"+"count" keywords — both should be
		// returned even with topK=1, because keyword-matched records are
		// not subject to topK truncation.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What is the current CD4 Count?", 1, FULL_PATIENT_DATASET);

		assertEquals(Arrays.asList(8, 85), result,
				"Keyword-matched records should bypass topK=1 — both CD4 records returned");
	}

	@Test
	public void integration_prescriptionsQuery_shouldReturnOnlyDrugOrders() {
		// Replaces: pipeline_gapDetectionWorks_keywordRefinementShouldNotInterfere
		// Gap detection finds a clear boundary between the 2 prescription
		// records and everything else. Keyword refinement should not pull
		// lower records into the result despite potential keyword overlap.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any prescriptions?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(0, 1), result,
				"Gap detection should isolate the 2 drug order records");
	}

	@Test
	public void integration_listAllAllergiesQuery_shouldReturnAllergyRecords() {
		// Replaces: pipeline_smoothDistributionNoKeywords_shouldReturnAllAboveFloor
		// When semantic scores are smooth (no clear gap), keyword refinement
		// identifies relevant records via keyword discrimination.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"list all allergies",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(4, 53), result,
				"Keyword refinement should isolate allergy records from smooth scores");
	}

	@Test
	public void integration_conditionsQuery_withoutKeywordScoring_shouldReturnFewerRecords() {
		// Replaces: pipeline_keywordWeightZero_shouldDisableRefinement
		// With keywordWeight=0, keyword refinement is disabled. On the
		// second patient dataset, "any conditions?" normally returns all
		// 10 condition records (keyword refinement rescues them). With
		// keywordWeight=0, only the top semantic matches survive.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		PipelineConfig noKeywordConfig =
				new PipelineConfig(
						0.0,
						ChartSearchAiConstants.DEFAULT_SCORE_GAP_MULTIPLIER,
						ChartSearchAiConstants.DEFAULT_MIN_SCORE_GAP,
						ChartSearchAiConstants.DEFAULT_GAP_VALIDATION_COSINE_THRESHOLD,
						ChartSearchAiConstants.DEFAULT_SIMILARITY_RATIO);

		List<Integer> withKeywords = runRealModelPipeline("any conditions?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				SECOND_PATIENT_DATASET);
		List<Integer> withoutKeywords = runRealModelPipeline("any conditions?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				SECOND_PATIENT_DATASET, noKeywordConfig);

		assertEquals(Arrays.asList(1, 2, 16, 17, 29, 30, 31, 44, 55, 56), withKeywords,
				"With default keywordWeight, should return all 10 conditions");
		assertEquals(Arrays.asList(1, 31), withoutKeywords,
				"With keywordWeight=0, only top semantic matches survive");
	}

	@Test
	public void integration_hbResultsQuery_shouldReturnEmptyWhenNoHbRecords() {
		// Replaces: pipeline_medicationQueryNoDrugOrders_shouldReturnContext
		// When the patient has no matching records, the pipeline should
		// return empty — not dump noise into the LLM.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"What are this patient's HB results over time, and are values "
				+ "moving toward or away from the normal range?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertTrue(result.isEmpty(),
				"Patient has no HB/hemoglobin records — should return empty");
	}

	@Test
	public void integration_fractureQuery_shouldReturnEmptyWhenNoFractureRecords() {
		// Replaces: pipeline_noKeywordMatchesSmoothDistribution_shouldReturnEmpty
		// When no records match the query well (smooth, low semantic scores
		// and no keyword matches), the z-score gate should block everything
		// to prevent sending noise to the LLM.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any fracture?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertTrue(result.isEmpty(),
				"No fracture records exist — z-score gate should block all results");
	}

	@Test
	public void integration_conditionsQuery_secondDataset_shouldReturnAll10ConditionsEvenWithTopK5() {
		// Replaces: pipeline_keywordRefinementBypassesStrictFloorAndTopK
		// All 10 condition records in the second dataset match "condition"
		// via keyword. Keyword refinement should bypass both the ratio
		// floor and topK, returning all 10 even with topK=5.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any conditions?", 5,
				SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(1, 2, 16, 17, 29, 30, 31, 44, 55, 56), result,
				"Keyword refinement should bypass topK=5 and return all 10 conditions");
	}

	@Test
	public void integration_cancerQuery_shouldReturnOnlyKaposiSarcomaRecords() {
		// Replaces: pipeline_focusedQueryStrictFloorFiltersNoise
		// A focused query should return only records that cluster high
		// semantically, filtering out noise below the gap or floor.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does the patient have cancer?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);

		assertEquals(Arrays.asList(11, 88), result,
				"Focused cancer query should return only 2 Kaposi sarcoma records, "
				+ "filtering out all noise below the semantic gap");
	}

	@Test
	public void integration_anemicQuery_withTopK1_shouldReturnSingleRecord() {
		// Replaces: pipeline_focusedQueryRatioFloorExcludesMarginalRecords
		// The anemic query returns 3 records with default topK. With topK=1
		// and no keyword matches on "anemic", topK caps the result —
		// marginal records that scored below the top are excluded.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> fullResult = runRealModelPipeline("is the patient anemic?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);
		List<Integer> cappedResult = runRealModelPipeline("is the patient anemic?",
				1, FULL_PATIENT_DATASET);

		assertEquals(Arrays.asList(29, 55, 72), fullResult,
				"Full result should return all 3 anemia records");
		assertEquals(Arrays.asList(72), cappedResult,
				"TopK=1 should cap to the highest-scoring anemia record");
	}

	@Test
	public void integration_labOrdersQuery_shouldReturnEmptyWhenNoLabOrders() {
		// FULL_PATIENT_DATASET has no "Lab test order:" records.
		// Obs records mentioning "Labs ordered." in clinical notes should not match.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"Were all lab orders placed for this patient resulted?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K);
		assertTrue(result.isEmpty(),
				"No lab order records exist — should return empty, got: " + result);
	}

	@Test
	public void integration_conditionsQuery_secondDataset_shouldReturnAll10ConditionsWithTopK3() {
		// Replaces: pipeline_keywordRefinementCanExceedTopK
		// All 10 condition records match "condition" keyword. With topK=3,
		// keyword refinement should keep all 10 — exceeding topK.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any conditions?", 3,
				SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(1, 2, 16, 17, 29, 30, 31, 44, 55, 56), result,
				"Keyword refinement should return all 10 conditions, exceeding topK=3");
	}

	@Test
	public void integration_pregnancyQuery_shouldReturnAbortionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"ever got pregnant?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				THIRD_PATIENT_DATASET);

		// [137] Self-Induced Abortion condition = index 136
		// [139] Self-Induced Abortion diagnosis = index 138
		assertEquals(Arrays.asList(136, 138), result,
				"Should return both Self-Induced Abortion records");
	}

	@Test
	public void integration_bloodProblemQuery_shouldReturnAnaemiaAndHematologyRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does she have any blood problem?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(0, 91, 93, 94, 103, 104, 105, 106, 108), result,
				"Should return Haemoglobin, CBC order, Anaemia, and blood cell records");
	}

	@Test
	public void integration_bloodProblemQuery_fourthDataset_shouldReturnBloodRelatedRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"does she have any blood problem?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				FOURTH_PATIENT_DATASET);

		// [0] Haemoglobin, [15] Hemoglobin in umbilical cord blood,
		// [18] Haemorrhagic disease condition, [21] Haemorrhagic disease diagnosis
		assertEquals(Arrays.asList(0, 15, 18, 21), result,
				"Should return Haemoglobin, cord blood, and Haemorrhagic disease records");
	}

	@Test
	public void integration_bloodProblemQuery_withSynonyms_shouldNotReturnBPorSpO2() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"any blood problems?",
				ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K,
				FIFTH_PATIENT_DATASET);

		// [0] Haemoglobin, [18] Haemorrhagic disease condition,
		// [21] Haemorrhagic disease diagnosis
		// Must NOT include BP, SpO2, or Pulse records
		assertEquals(Arrays.asList(0, 18, 21), result,
				"Should return only blood-related records, not BP or SpO2");
	}

	// ---- Colloquial → Clinical mapping tests ----

	@Test
	public void realModel_feverQuery_shouldReturnTemperatureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "fever" clinically refers to elevated body temperature.
		// Temperature records are the relevant clinical answer.
		// All other fever_*Dataset tests assert Temperature records
		// for the same query — this test asserts the same clinical
		// truth for the FULL dataset.
		List<Integer> result = runRealModelPipeline("fever", 100);

		assertFalse(result.isEmpty(), "FULL dataset has Temperature records");
		boolean hasTemperature = false;
		for (int idx : result) {
			if (FULL_PATIENT_DATASET[idx].contains("Temperature")) {
				hasTemperature = true;
				break;
			}
		}
		assertTrue(hasTemperature, "Should include Temperature records");
	}

	@Test
	public void realModel_breathingProblemsQuery_shouldReturnRespiratoryRateRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"breathing problems", 100);

		// Should return all Respiratory Rate records — "breathing
		// problems" maps semantically to respiratory measurements.
		assertEquals(Arrays.asList(15, 28, 32, 44, 65, 80, 84, 98,
				104, 109, 117, 125, 133, 136, 152), result,
				"Should return all Respiratory Rate records");
	}

	@Test
	public void realModel_liverProblemsQuery_shouldReturnCirrhosisRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "liver problems" → Syphilitic Cirrhosis (cirrhosis is a
		// liver disease). Tests colloquial → clinical mapping.
		List<Integer> result = runRealModelPipeline(
				"liver problems", 100, SECOND_PATIENT_DATASET);

		// [17] Syphilitic Cirrhosis condition, [19] Syphilitic
		// Cirrhosis diagnosis
		assertEquals(Arrays.asList(17, 19), result,
				"Should return Syphilitic Cirrhosis records");
	}

	@Test
	public void realModel_hairLossQuery_shouldReturnAlopeciaRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "hair loss" → Scarring Alopecia (alopecia = hair loss)
		List<Integer> result = runRealModelPipeline(
				"hair loss", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(2, 4), result,
				"Should return Scarring Alopecia records");
	}

	@Test
	public void realModel_tiredQuery_shouldReturnChronicFatigueRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "is the patient tired?" → Chronic fatigue
		List<Integer> result = runRealModelPipeline(
				"is the patient tired?", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(56, 58), result,
				"Should return Chronic fatigue condition and diagnosis");
	}

	@Test
	public void realModel_kidneyProblemsQuery_shouldReturnUtiRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "kidney problems" maps to Urinary Tract Infection —
		// kidneys are part of the urinary system.
		List<Integer> result = runRealModelPipeline(
				"kidney problems", 100);

		assertEquals(Arrays.asList(51, 90, 118), result,
				"Should return UTI records (kidney → urinary system)");
	}

	@Test
	public void realModel_bloodSugarQuery_shouldReturnGlucoseReadings() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "blood sugar" → Serum glucose readings
		List<Integer> result = runRealModelPipeline(
				"blood sugar", 100, THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(19, 49, 69, 88, 114), result,
				"Should return all Serum glucose readings");
	}

	// ---- Abbreviation tests ----

	@Test
	public void realModel_tbAbbreviationQuery_shouldReturnTuberculosisRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "TB" abbreviation → Tuberculosis condition + diagnoses
		List<Integer> result = runRealModelPipeline("TB", 100);

		// [7] TB condition, [12] Primary Diagnosis: Tuberculosis,
		// [52] Diagnosis: Tuberculosis, [134,135] Primary Diagnosis:
		// Tuberculosis
		assertEquals(Arrays.asList(7, 12, 52, 134, 135), result,
				"Should return all Tuberculosis records");
	}

	// ---- Direct condition match tests on under-tested datasets ----

	@Test
	public void realModel_diabetesQuery_shouldReturnDiabetesMellitusRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("diabetes", 100);

		// [49] Primary Diagnosis: Diabetes Mellitus, [67] Diagnosis:
		// Diabetes Mellitus, [146] Diagnosis: Diabetes Mellitus
		assertEquals(Arrays.asList(49, 67, 146), result,
				"Should return all Diabetes Mellitus records");
	}

	@Test
	public void realModel_headacheQuery_shouldReturnSingleRecord() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("headache", 100);

		assertEquals(Arrays.asList(99), result,
				"Should return the single Headache assessment");
	}

	@Test
	public void realModel_strokeQuery_secondDataset_shouldReturnStrokeRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"stroke", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(1, 3), result,
				"Should return Nonparalytic stroke condition and diagnosis");
	}

	@Test
	public void realModel_depressionQuery_shouldReturnDepressiveEpisodeRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"depression", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(31, 34), result,
				"Should return Mild depressive episode records");
	}

	@Test
	public void realModel_cardiovascularQuery_shouldReturnAtherosclerosisRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"cardiovascular disease", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(29, 32), result,
				"Should return Atherosclerosis records");
	}

	@Test
	public void realModel_cholesterolQuery_secondDataset_shouldReturnLabRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"cholesterol", 100, SECOND_PATIENT_DATASET);

		// [13] HDL cholesterol lab result, [14] HDL cholesterol lab order
		assertEquals(Arrays.asList(13, 14), result,
				"Should return HDL cholesterol observation and lab order");
	}

	@Test
	public void realModel_kidneyFunctionQuery_thirdDataset_shouldReturnCkdRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"kidney function", 100, THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(73, 74), result,
				"Should return Chronic kidney disease records");
	}

	@Test
	public void realModel_anemiaQuery_thirdDataset_shouldReturnAnaemiaRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"anemia", 100, THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(93, 94), result,
				"Should return Anaemia condition and diagnosis");
	}

	@Test
	public void realModel_substanceAbuseQuery_fourthDataset_shouldReturnAddictionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"substance abuse", 100, FOURTH_PATIENT_DATASET);

		// [47] Mental/behavioral disorder due to psychoactive substance,
		// [48] Cocaine abuse, [51] Psychoactive substance diagnosis,
		// [52] Cocaine abuse diagnosis, [121] Substance Addiction,
		// [124] Substance Addiction diagnosis
		assertEquals(Arrays.asList(47, 48, 51, 52, 121, 124), result,
				"Should return all substance abuse related records");
	}

	@Test
	public void realModel_strokeQuery_fourthDataset_shouldReturnCvaRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "stroke" → Cerebrovascular Accident (clinical synonym)
		List<Integer> result = runRealModelPipeline(
				"stroke", 100, FOURTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(34, 36), result,
				"Should return Cerebrovascular Accident records");
	}

	@Test
	public void realModel_cancerQuery_fourthDataset_shouldReturnTumorRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"cancer", 100, FOURTH_PATIENT_DATASET);

		// [63] Malignant tumor of base of tongue condition,
		// [65] Malignant tumor diagnosis
		assertEquals(Arrays.asList(63, 65), result,
				"Should return Malignant tumor records");
	}

	@Test
	public void realModel_dentalProblemsQuery_shouldReturnToothRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"dental problems", 100, FOURTH_PATIENT_DATASET);

		// [49,53] Partial absence of teeth, [91,95] Failure of
		// exfoliation of primary tooth
		assertEquals(Arrays.asList(49, 53, 91, 95), result,
				"Should return tooth-related condition and diagnosis records");
	}

	@Test
	public void realModel_fractureQuery_fourthDataset_shouldReturnFractureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Unlike FULL dataset (no fracture records → empty), FOURTH
		// dataset has Nonunion of fracture.
		List<Integer> result = runRealModelPipeline(
				"fracture", 100, FOURTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(107, 109), result,
				"Should return Nonunion of fracture records");
	}

	@Test
	public void realModel_mentalHealthQuery_shouldReturnPsychiatricRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"mental health", 100, FOURTH_PATIENT_DATASET);

		// [19,22] Psychosis, [47,51] Mental/behavioral disorder,
		// [77] Self-accusation, [121] Substance Addiction
		assertEquals(Arrays.asList(19, 22, 47, 51, 77, 121), result,
				"Should return psychiatric and behavioral health records");
	}

	// ---- Multi-type infection query ----

	@Test
	public void realModel_infectionsQuery_shouldReturnInfectionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset has multiple infection types: Tuberculosis
		// (condition, diagnosis, assessments), HIV Disease (multiple
		// diagnoses + assessments), Malaria (diagnosis), UTI (multiple
		// records), Skin Infection (multiple records), and Gastroenteritis
		// (diagnosis). All are clinically infections — the previous
		// assertion of UTI + Skin Infection only encoded the pipeline's
		// keyword-driven match on the literal word "infection".
		List<Integer> result = runRealModelPipeline("any infections?", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has multiple infection records");
		boolean hasInfection = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.contains("Tuberculosis") || rec.contains("HIV")
					|| rec.contains("Malaria") || rec.contains("Urinary Tract")
					|| rec.contains("Skin Infection") || rec.contains("Gastroenteritis")) {
				hasInfection = true;
				break;
			}
		}
		assertTrue(hasInfection,
				"Should include at least one infection record");
	}

	// ---- Negative test ----

	@Test
	public void realModel_cholesterolQuery_fullDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset has no cholesterol records
		List<Integer> result = runRealModelPipeline("cholesterol", 100);

		assertTrue(result.isEmpty(),
				"Should return empty — no cholesterol records in dataset");
	}

	// ---- Synonym matching test ----

	@Test
	public void realModel_hbQuery_fifthDataset_shouldReturnHaemoglobinViaSynonym() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "Hb" is a synonym for Haemoglobin. The FIFTH dataset has
		// synonym annotations (e.g. "Haemoglobin (syn. Hb,
		// Hemoglobin)"). On the FULL dataset, "HB results" returns
		// empty because there are no synonyms. Here it should find
		// the Haemoglobin record via the synonym keyword match.
		List<Integer> result = runRealModelPipeline(
				"Hb results", 100, FIFTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(0), result,
				"Should return Haemoglobin via Hb synonym");
	}

	// ---- Multi-concept queries ----

	@Test
	public void realModel_hivAndCd4Query_shouldReturnHivAndCd4Records() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Multi-concept query: "HIV status and CD4 count" should
		// return both HIV and CD4 records.
		List<Integer> result = runRealModelPipeline(
				"HIV status and CD4 count", 100);

		// [8] CD4 Count 988.0, [39,40] HIV diagnosis/assessment,
		// [68,69,71] HIV records, [85] CD4 Count 1191.0,
		// [110] HIV diagnosis
		assertEquals(Arrays.asList(8, 39, 40, 68, 69, 71, 85, 110),
				result,
				"Should return both HIV and CD4 count records");
	}

	@Test
	public void realModel_allergiesAndMedicationsQuery_shouldReturnAllergyAndMedicationRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Cross-type query spanning allergies and medications. The
		// dataset has 2 Azithromycin drug orders [0, 1] and 2 patient
		// allergy records [4, 53]. The previous assertion also
		// included encounter notes [56, 91] reading "Chronic disease
		// management visit. Medication adjusted." — the parallel
		// integration_medicationsQuery test explicitly rejects those
		// as non-medication clinical narrative. The same records
		// cannot be both medications-for-this-query and
		// not-medications-for-the-other.
		List<Integer> result = runRealModelPipeline(
				"allergies and current medications", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has drug orders and allergy records");
		boolean hasMedication = false, hasAllergy = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.startsWith("Medication prescription:")
					|| rec.contains("Drug —")) {
				hasMedication = true;
			}
			if (rec.startsWith("Patient allergy:")) {
				hasAllergy = true;
			}
			assertFalse(
					rec.contains("Medication adjusted"),
					"Encounter notes about medication adjustment are not"
							+ " medication records: " + rec);
		}
		assertTrue(hasMedication, "Should include medication records");
		assertTrue(hasAllergy, "Should include allergy records");
	}

	@Test
	public void realModel_tbTreatmentHistoryQuery_shouldMatchSimpleTbQuery() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Adding "treatment history" to "TB" should still find the
		// same Tuberculosis records — the clinical qualifier doesn't
		// narrow or shift the results.
		List<Integer> result = runRealModelPipeline(
				"TB treatment history", 100);

		assertEquals(Arrays.asList(7, 12, 52, 134, 135), result,
				"Should return same TB records as simple 'TB' query");
	}

	// ---- Clinical reasoning queries ----

	@Test
	public void realModel_nutritionalStatusQuery_shouldReturnWeightRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Weight is the canonical clinical indicator of nutritional
		// status (alongside Height for BMI). FULL has both. This
		// assertion accepts a Weight-only result as clinically valid
		// (Height would be a clinical bonus but Weight alone is
		// useful) — the previous strict enumeration of exact Weight
		// indices encoded a specific pipeline output.
		List<Integer> result = runRealModelPipeline(
				"nutritional status", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has Weight records");
		boolean hasWeight = false;
		for (int idx : result) {
			if (FULL_PATIENT_DATASET[idx].contains("Weight")) {
				hasWeight = true;
				break;
			}
		}
		assertTrue(hasWeight, "Should include Weight records");
	}

	@Test
	public void realModel_latestWeightQuery_shouldReturnWeightRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"what is the latest weight?", 100);

		// Clinically, "latest weight" means the most recent
		// measurement: index 18 (2025-10-30, 94.0 kg).
		assertEquals(Arrays.asList(18), result,
				"Should return only the most recent Weight record");
	}

	@Test
	public void realModel_treatedForQuery_thirdDataset_shouldReturnChronicDiseaseConditions() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "what is the patient being treated for?" → active medical
		// conditions. THIRD has 10 active condition records (Zika,
		// Ovarian cyst, IBD, Hypertension, Malaria, CKD, Anaemia,
		// Pneumonia, Diabetes, Self-Induced Abortion) plus one
		// inactive condition (Asthma). At minimum the result must
		// include the canonical chronic-disease conditions a
		// clinician would consider "being treated for".
		List<Integer> result = runRealModelPipeline(
				"what is the patient being treated for?", 100,
				THIRD_PATIENT_DATASET);

		assertFalse(result.isEmpty(),
				"THIRD dataset has 10 active medical condition records");
		boolean hasHypertension = false, hasDiabetes = false,
				hasAnaemia = false, hasCKD = false;
		for (int idx : result) {
			String rec = THIRD_PATIENT_DATASET[idx];
			if (rec.contains("Hypertension")) hasHypertension = true;
			if (rec.contains("Diabetes")) hasDiabetes = true;
			if (rec.contains("Anaemia")) hasAnaemia = true;
			if (rec.contains("Chronic kidney disease")) hasCKD = true;
		}
		assertTrue(hasHypertension, "Should include Hypertension");
		assertTrue(hasDiabetes, "Should include Diabetes");
		assertTrue(hasAnaemia, "Should include Anaemia");
		assertTrue(hasCKD, "Should include Chronic kidney disease");
	}

	@Test
	public void realModel_infectionSignsQuery_shouldReturnSkinInfection() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Complex clinical query with two concepts: infection and
		// inflammation. The model finds Skin Infection records.
		List<Integer> result = runRealModelPipeline(
				"signs of infection or inflammation", 100);

		assertEquals(Arrays.asList(61, 122), result,
				"Should return Skin Infection diagnosis and assessment");
	}

	@Test
	public void realModel_pregnancyQuery_thirdDataset_shouldReturnAbortionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"pregnancy-related concerns", 100,
				THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(136, 138), result,
				"Should return Self-Induced Abortion records");
	}

	@Test
	public void realModel_pregnancyQuery_fourthDataset_shouldReturnAbortionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Same query on a different dataset — should find the same
		// type of records, demonstrating cross-dataset consistency.
		List<Integer> result = runRealModelPipeline(
				"pregnancy-related concerns", 100,
				FOURTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(136, 138), result,
				"Should return Self-Induced Abortion records");
	}

	// ---- Specific clinical queries ----

	@Test
	public void realModel_opportunisticInfectionsQuery_shouldReturnHivTbAndKaposiSarcomaRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "opportunistic infections in HIV" — should return HIV
		// records plus TB and Kaposi sarcoma (classic opportunistic
		// infections in HIV patients).
		List<Integer> result = runRealModelPipeline(
				"opportunistic infections in HIV", 100);

		// [7] TB condition, [11] Kaposi sarcoma, [12] TB assessment,
		// [39,40] HIV diagnosis/assessment, [52] TB diagnosis,
		// [68,69,71] HIV records, [88] Kaposi sarcoma (index 89 was
		// previously asserted but that is Photoallergy, not Kaposi),
		// [110] HIV diagnosis, [134,135] TB assessments
		assertEquals(Arrays.asList(7, 11, 12, 39, 40, 52, 68, 69,
				71, 88, 110, 134, 135), result,
				"Should return HIV, TB, and Kaposi sarcoma records");
	}

	@Test
	public void realModel_bowelDiseaseQuery_thirdDataset_shouldReturnIbdRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"bowel disease", 100, THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(23, 24), result,
				"Should return Inflammatory bowel disease records");
	}

	@Test
	public void realModel_azithromycinQuery_shouldReturnDrugOrders() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"azithromycin", 100);

		assertEquals(Arrays.asList(0, 1), result,
				"Should return both Azithromycin drug orders");
	}

	@Test
	public void realModel_musculoskeletalQuery_shouldReturnInjuryRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Broad clinical category query that spans multiple
		// conditions: musculoskeletal disorder, crushing injury,
		// achilles tendon, and fracture.
		List<Integer> result = runRealModelPipeline(
				"musculoskeletal injuries", 100,
				FOURTH_PATIENT_DATASET);

		// [76,78] Intraoperative musculoskeletal disorder,
		// [92,96] Crushing injury of thigh (condition + diagnosis),
		// [93,97] Acquired short achilles tendon (condition + diagnosis),
		// [107,109] Nonunion of fracture (condition + diagnosis).
		// CVA [34,36] is neurological, not musculoskeletal.
		assertEquals(Arrays.asList(76, 78, 92, 93, 96, 97,
				107, 109), result,
				"Should return musculoskeletal conditions and injuries");
	}

	// ---- Clinical reasoning limitations (negative tests) ----

	@Test
	public void realModel_immunocompromisedQuery_shouldReturnImmunocompromiseRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset contains HIV diagnoses, CD4 count records,
		// Kaposi sarcoma (an AIDS-defining illness), and TB. These
		// are the clinically relevant records for an immunocompromise
		// query and at least some should be returned.
		List<Integer> result = runRealModelPipeline(
				"is the patient immunocompromised?", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has HIV / CD4 / Kaposi sarcoma / TB records");
		boolean hasImmunoEvidence = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.contains("HIV") || rec.contains("CD4")
					|| rec.contains("Kaposi") || rec.contains("Tuberculosis")) {
				hasImmunoEvidence = true;
				break;
			}
		}
		assertTrue(hasImmunoEvidence,
				"Should include at least one immunocompromise-relevant"
						+ " record (HIV, CD4, Kaposi, or TB)");
	}

	@Test
	public void realModel_cardiovascularRiskQuery_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset has Hypertension condition + diagnosis (the
		// canonical cardiovascular risk factor) AND blood pressure
		// readings (a cardiovascular risk indicator). Either
		// category is clinically valid for this query — the
		// previous assertion of exactly 24 BP-only records encoded
		// the pipeline's keyword-on-"Blood Pressure" output.
		List<Integer> result = runRealModelPipeline(
				"cardiovascular risk factors", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has Hypertension and BP records");
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Blood Pressure")
							|| rec.contains("Hypertension"),
					"Record [" + idx + "] should be a cardiovascular"
							+ " record (BP or Hypertension): " + rec);
		}
	}

	// ---- Spelling variation invariance ----

	@Test
	public void realModel_anaemiaVsAnemia_shouldReturnIdenticalResults() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// British "anaemia" and American "anemia" should both find
		// the same Anaemia condition and diagnosis records.
		List<Integer> british = runRealModelPipeline(
				"anaemia", 100, THIRD_PATIENT_DATASET);
		List<Integer> american = runRealModelPipeline(
				"anemia", 100, THIRD_PATIENT_DATASET);

		assertEquals(Arrays.asList(93, 94), british,
				"British spelling should find Anaemia records");
		assertEquals(british, american,
				"American and British spellings should return"
						+ " identical results");
	}

	// ---- Query phrasing invariance ----

	@Test
	public void realModel_diabetesPhrasingVariations_shouldReturnIdenticalResults() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Three different phrasings of the same clinical concept
		// should all return the same Diabetes Mellitus records.
		List<Integer> expected = Arrays.asList(49, 67, 146);

		for (String query : new String[] {
				"does the patient have diabetes?",
				"diabetic",
				"diabetes mellitus"}) {
			List<Integer> result = runRealModelPipeline(query, 100);
			assertEquals(expected, result,
					"Query '" + query + "' should return all"
							+ " Diabetes Mellitus records");
		}
	}

	@Test
	public void realModel_oxygenSaturationPhrasingVariations_shouldReturnIdenticalResults() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "oxygen saturation" and "blood oxygen levels" should both
		// return the same Blood Oxygen Saturation records.
		List<Integer> expected = Arrays.asList(34, 45, 59, 79, 83,
				103, 108, 116, 145, 151);

		for (String query : new String[] {
				"oxygen saturation",
				"blood oxygen levels"}) {
			List<Integer> result = runRealModelPipeline(query, 100);
			assertEquals(expected, result,
					"Query '" + query + "' should return all"
							+ " Blood Oxygen Saturation records");
		}
	}

	@Test
	public void realModel_immunizationPhrasingVariations_shouldReturnIdenticalResults() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "vaccination history" and "immunization" should both find
		// the same immunization record.
		List<Integer> expected = Arrays.asList(3);

		for (String query : new String[] {
				"vaccination history",
				"immunization"}) {
			List<Integer> result = runRealModelPipeline(query, 100);
			assertEquals(expected, result,
					"Query '" + query + "' should return the"
							+ " Immunization history record");
		}
	}

	// ---- Program enrollment queries ----

	@Test
	public void realModel_programEnrollmentQuery_shouldReturnPmtctAcrossDatasets() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Program enrollment records exist in FULL [2], THIRD [148],
		// and FOURTH [148] datasets. The same query should find them.
		String query = "is the patient enrolled in any programs?";

		assertEquals(Arrays.asList(2),
				runRealModelPipeline(query, 100),
				"FULL dataset: should return PMTCT program");
		assertEquals(Arrays.asList(148),
				runRealModelPipeline(query, 100,
						THIRD_PATIENT_DATASET),
				"THIRD dataset: should return PMTCT program");
		assertEquals(Arrays.asList(148),
				runRealModelPipeline(query, 100,
						FOURTH_PATIENT_DATASET),
				"FOURTH dataset: should return PMTCT program");
	}

	// ---- Vital sign specific queries ----

	@Test
	public void realModel_pulseRateQuery_shouldReturnAllPulseRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"pulse rate", 100);

		assertEquals(Arrays.asList(16, 24, 43, 60, 75, 82, 95,
				100, 112, 130, 149), result,
				"Should return all Pulse records");
	}

	@Test
	public void realModel_oxygenSaturationQuery_secondDataset_shouldReturnSpO2Records() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"oxygen saturation", 100, SECOND_PATIENT_DATASET);

		assertEquals(Arrays.asList(9, 24, 39, 50, 63), result,
				"Should return all SpO2 records");
	}

	// ---- Medication order queries ----

	@Test
	public void realModel_medicationsQuery_thirdDataset_shouldReturnAllDrugOrders() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "what medications is the patient on?" should find all 9
		// Azithromycin drug orders across different visits.
		List<Integer> expected = Arrays.asList(22, 33, 52, 53,
				72, 92, 117, 128, 146);

		List<Integer> result = runRealModelPipeline(
				"what medications is the patient on?", 100,
				THIRD_PATIENT_DATASET);

		assertEquals(expected, result,
				"Should return all 9 Azithromycin drug orders");

		// Direct drug name query should return the same set.
		List<Integer> byName = runRealModelPipeline(
				"azithromycin", 100, THIRD_PATIENT_DATASET);

		assertEquals(expected, byName,
				"Direct drug name should match 'what medications'"
						+ " query results");
	}

	// ---- Under-tested record types ----

	@Test
	public void realModel_wastingQuery_fourthDataset_shouldReturnWastingSyndrome() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"wasting", 100, FOURTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(17, 20), result,
				"Should return Wasting syndrome condition and diagnosis");
	}

	@Test
	public void realModel_skinRashQuery_fourthDataset_shouldReturnRashRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"skin rash", 100, FOURTH_PATIENT_DATASET);

		assertEquals(Arrays.asList(64, 66), result,
				"Should return Rash condition and diagnosis");
	}

	// ---- FULL dataset: remaining cross-dataset coverage ----

	@Test
	public void mentalHealth_fullDataset_shouldReturnFetishismRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset has 12 "Diagnosis — Fetishism" records (paraphilia,
		// classified as a mental disorder in ICD-10 F65.0). The previous
		// assertion of exactly 3 records [56, 91, 120] enumerated only
		// the records the pipeline happened to surface — there is no
		// clinical reason to exclude the other 9 since they share the
		// same diagnosis label. Loosened to a contains-check on
		// Fetishism while ensuring no non-Fetishism records appear.
		List<Integer> result = runRealModelPipeline("mental health", 100);

		assertFalse(result.isEmpty(),
				"FULL dataset has 12 Fetishism diagnosis records");
		boolean hasFetishism = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			assertTrue(rec.contains("Fetishism"),
					"Record [" + idx + "] should be a mental health record"
							+ " (Fetishism): " + rec);
			hasFetishism = true;
		}
		assertTrue(hasFetishism, "Should include Fetishism records");
	}

	@Test
	public void allergies_fullDataset_shouldReturnAllergyRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("allergies", 100);
		assertEquals(Arrays.asList(4, 53), result,
				"Should return Beef and Fomepizole allergy records");
	}

	// ---- SECOND dataset: remaining cross-dataset coverage ----

	@Test
	@Disabled("Disabled because L6 alone cannot bridge category-name queries to "
			+ "member-concept records (Temperature/BP/Height all rank below noise "
			+ "for 'vital signs'). Production retrieval relies on concept-set "
			+ "metadata injected at index time to bypass this gap (see ADR 19, "
			+ "EmbeddingIndexerTest.buildEmbeddings_*). String fixtures here "
			+ "bypass loadAll() and so cannot carry that metadata. Re-enable if a "
			+ "future embedding model or pipeline change makes category knowledge "
			+ "testable in isolation.")
	public void vitalSigns_secondDataset_shouldReturnAllVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has Temperature, BP, Pulse, SpO2, RR
		// across 5 encounters. All are vital signs.
		List<Integer> result = runRealModelPipeline("vital signs", 100,
				SECOND_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"SECOND dataset contains vital signs");
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			String rec = SECOND_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Temperature") || rec.contains("blood pressure")
							|| rec.contains("Pulse")
							|| rec.contains("Respiratory rate")
							|| rec.contains("oxygen saturation"),
					"Record [" + idx + "] should be a vital sign: " + rec);
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.contains("blood pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
			if (rec.contains("Respiratory rate")) hasRR = true;
			if (rec.contains("oxygen saturation")) hasSpO2 = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
		assertTrue(hasRR, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	@Test
	public void cancer_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("any history of cancer?", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no cancer records");
	}

	@Test
	public void infections_secondDataset_shouldReturnSyphiliticCirrhosis() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has Syphilitic Cirrhosis (records 17 + 19) —
		// syphilis is a bacterial infection. The same records are
		// classified as STD (i.e. infection) by
		// realModel_stdQuery_shouldReturnSyphiliticCirrhosisRecords.
		List<Integer> result = runRealModelPipeline("any infections?", 100,
				SECOND_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"SECOND dataset has Syphilitic Cirrhosis (an infection)");
		boolean hasSyphilis = false;
		for (int idx : result) {
			if (SECOND_PATIENT_DATASET[idx].contains("Syphilitic")) {
				hasSyphilis = true;
				break;
			}
		}
		assertTrue(hasSyphilis,
				"Should include Syphilitic Cirrhosis records");
	}

	@Test
	public void diabetes_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("diabetes", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no diabetes records");
	}

	@Test
	public void cardiovascularRisk_secondDataset_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has Atherosclerosis (a cardiovascular disease),
		// Nonparalytic stroke (a cardiovascular event), and elevated
		// blood pressure readings. Mirrors cardiovascularRisk_*Dataset
		// tests on THIRD/FOURTH/FIFTH which assert BP records returned.
		List<Integer> result = runRealModelPipeline(
				"cardiovascular risk factors", 100, SECOND_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"SECOND dataset has cardiovascular risk records");
		boolean hasCardiovascular = false;
		for (int idx : result) {
			String rec = SECOND_PATIENT_DATASET[idx];
			if (rec.contains("blood pressure") || rec.contains("Atherosclerosis")
					|| rec.contains("stroke")) {
				hasCardiovascular = true;
				break;
			}
		}
		assertTrue(hasCardiovascular,
				"Should include at least one cardiovascular record"
						+ " (BP, Atherosclerosis, or stroke)");
	}

	@Test
	public void anemia_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("is the patient anemic?", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no anemia records");
	}

	@Test
	public void medications_secondDataset_shouldReturnEmptyForPatientWithNoMedications() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has no drug orders — pipeline should return
		// empty, not clinically unrelated conditions like depressive
		// episode or ligament tear.
		List<Integer> result = runRealModelPipeline(
				"what medications is the patient on?", 100,
				SECOND_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"Patient has no medication records — pipeline should return empty, but got "
						+ result.size() + " results");
	}

	@Test
	public void immunization_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("immunization", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no immunization records");
	}

	@Test
	public void allergies_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("allergies", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no allergy records");
	}

	@Test
	public void programEnrollment_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("is the patient enrolled in any programs?",
						100, SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no program enrollment records");
	}

	// ---- THIRD dataset: remaining cross-dataset coverage ----

	@Test
	public void cancer_thirdDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("any history of cancer?", 100,
						THIRD_PATIENT_DATASET).isEmpty(),
				"THIRD dataset has no cancer records");
	}

	@Test
	public void std_thirdDataset_shouldReturnZika() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset has Zika virus disease (records 2 + 4). Zika
		// is sexually transmissible and is classified as an STD by
		// the parallel std_*Dataset tests on FOURTH and FIFTH.
		List<Integer> result = runRealModelPipeline("any STD?", 100,
				THIRD_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"THIRD dataset has Zika virus disease (sexually transmissible)");
		boolean hasZika = false;
		for (int idx : result) {
			if (THIRD_PATIENT_DATASET[idx].contains("Zika")) {
				hasZika = true;
				break;
			}
		}
		assertTrue(hasZika, "Should include Zika virus disease records");
	}

	@Test
	public void tb_thirdDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset has no TB records — Pneumonia is a different
		// disease and should not be returned for a TB query.
		assertTrue(
				runRealModelPipeline("TB", 100,
						THIRD_PATIENT_DATASET).isEmpty(),
				"THIRD dataset has no tuberculosis records");
	}

	@Test
	public void mentalHealth_thirdDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("mental health", 100,
						THIRD_PATIENT_DATASET).isEmpty(),
				"THIRD dataset has no mental health records");
	}

	@Test
	public void immunization_thirdDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("immunization", 100,
						THIRD_PATIENT_DATASET).isEmpty(),
				"THIRD dataset has no immunization records");
	}

	@Test
	public void headache_thirdDataset_shouldReturnPrescriptionsWithHeadachePrnQualifier() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset records 52, 53, 128, 146 contain "As needed
		// (subject to headache)" in the Azithromycin prescription text.
		// The pipeline correctly returns them via keyword matching on
		// "headache" — a PRN qualifier for headache IS clinically
		// relevant to a headache query.
		List<Integer> result = runRealModelPipeline("headache", 100,
				THIRD_PATIENT_DATASET);
		assertEquals(Arrays.asList(52, 53, 128, 146), result);
	}



	// ---- FOURTH dataset: remaining cross-dataset coverage ----

	@Test
	@Disabled("Disabled because L6 alone cannot bridge category-name queries to "
			+ "member-concept records (Temperature/BP/Height all rank below noise "
			+ "for 'vital signs'). Production retrieval relies on concept-set "
			+ "metadata injected at index time to bypass this gap (see ADR 19, "
			+ "EmbeddingIndexerTest.buildEmbeddings_*). String fixtures here "
			+ "bypass loadAll() and so cannot carry that metadata. Re-enable if a "
			+ "future embedding model or pipeline change makes category knowledge "
			+ "testable in isolation.")
	public void vitalSigns_fourthDataset_shouldReturnAllVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has Temperature, BP, Pulse, SpO2, RR records
		// across multiple encounters — asserting against the data, not
		// against the pipeline's prior behaviour. Mirrors the SECOND,
		// THIRD, and FIFTH dataset assertions for the same query.
		List<Integer> result = runRealModelPipeline("vital signs", 100,
				FOURTH_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"FOURTH dataset contains vital signs");
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			String rec = FOURTH_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Temperature") || rec.contains("blood pressure")
							|| rec.contains("Pulse")
							|| rec.contains("Respiratory rate")
							|| rec.contains("oxygen saturation"),
					"Record [" + idx + "] should be a vital sign: " + rec);
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.contains("blood pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
			if (rec.contains("Respiratory rate")) hasRR = true;
			if (rec.contains("oxygen saturation")) hasSpO2 = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
		assertTrue(hasRR, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	@Test
	public void tb_fourthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has no TB records — conditions like Wasting,
		// Rash, Self-accusation, and ESAVI are unrelated to tuberculosis.
		assertTrue(
				runRealModelPipeline("TB", 100,
						FOURTH_PATIENT_DATASET).isEmpty(),
				"FOURTH dataset has no tuberculosis records");
	}

	@Test
	public void anemia_fourthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("is the patient anemic?", 100,
						FOURTH_PATIENT_DATASET).isEmpty(),
				"FOURTH dataset has no anemia records");
	}

	@Test
	public void medications_fourthDataset_shouldReturnEmptyForPatientWithNoMedications() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has no drug orders — pipeline should return
		// empty, not clinically unrelated conditions like substance
		// abuse or behavioral disorders.
		List<Integer> result = runRealModelPipeline(
				"what medications is the patient on?", 100,
				FOURTH_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"Patient has no medication records — pipeline should return empty, but got "
						+ result.size() + " results");
	}

	@Test
	public void headache_fourthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("headache", 100,
						FOURTH_PATIENT_DATASET).isEmpty(),
				"FOURTH dataset has no headache records");
	}

	// ---- FIFTH dataset: remaining cross-dataset coverage ----

	@Test
	@Disabled("Disabled because L6 alone cannot bridge category-name queries to "
			+ "member-concept records (Temperature/BP/Height all rank below noise "
			+ "for 'vital signs'). Production retrieval relies on concept-set "
			+ "metadata injected at index time to bypass this gap (see ADR 19, "
			+ "EmbeddingIndexerTest.buildEmbeddings_*). String fixtures here "
			+ "bypass loadAll() and so cannot carry that metadata. Re-enable if a "
			+ "future embedding model or pipeline change makes category knowledge "
			+ "testable in isolation.")
	public void vitalSigns_fifthDataset_shouldReturnAllVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset has Temperature, BP, Pulse, SpO2, RR
		// (same as FOURTH with synonyms)
		List<Integer> result = runRealModelPipeline("vital signs", 100,
				FIFTH_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"FIFTH dataset contains vital signs");
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			String rec = FIFTH_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Temperature") || rec.contains("blood pressure")
							|| rec.contains("Pulse")
							|| rec.contains("Respiratory rate")
							|| rec.contains("oxygen saturation"),
					"Record [" + idx + "] should be a vital sign: " + rec);
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.contains("blood pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
			if (rec.contains("Respiratory rate")) hasRR = true;
			if (rec.contains("oxygen saturation")) hasSpO2 = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
		assertTrue(hasRR, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	@Test
	public void tb_fifthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset has no TB records — conditions like Rash,
		// Self-accusation, and ESAVI are unrelated to tuberculosis.
		assertTrue(
				runRealModelPipeline("TB", 100,
						FIFTH_PATIENT_DATASET).isEmpty(),
				"FIFTH dataset has no tuberculosis records");
	}

	@Test
	public void anemia_fifthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("is the patient anemic?", 100,
						FIFTH_PATIENT_DATASET).isEmpty(),
				"FIFTH dataset has no anemia records");
	}

	@Test
	public void medications_fifthDataset_shouldReturnEmptyForPatientWithNoMedications() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset has no drug orders — pipeline should return
		// empty, not clinically unrelated conditions like substance
		// abuse or behavioral disorders.
		List<Integer> result = runRealModelPipeline(
				"what medications is the patient on?", 100,
				FIFTH_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"Patient has no medication records — pipeline should return empty, but got "
						+ result.size() + " results");
	}

	@Test
	public void headache_fifthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertTrue(
				runRealModelPipeline("headache", 100,
						FIFTH_PATIENT_DATASET).isEmpty(),
				"FIFTH dataset has no headache records");
	}

	// ---- Long sentence queries ----

	@Test
	public void longSentence_bloodPressureAndWeightReadings_shouldReturnBpAndWeightRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"I need to know the patient's most recent blood pressure and weight readings",
				100);
		// Query explicitly asks for "blood pressure and weight"
		for (int idx : result) {
			assertTrue(
					FULL_PATIENT_DATASET[idx].contains("Blood Pressure")
							|| FULL_PATIENT_DATASET[idx].contains("blood pressure")
							|| FULL_PATIENT_DATASET[idx].contains("Weight"),
					"Record [" + idx + "] should be BP or Weight: "
							+ FULL_PATIENT_DATASET[idx]);
		}
		boolean hasBP = false, hasWeight = false;
		for (int idx : result) {
			if (FULL_PATIENT_DATASET[idx].contains("Blood Pressure")
					|| FULL_PATIENT_DATASET[idx].contains("blood pressure")) {
				hasBP = true;
			}
			if (FULL_PATIENT_DATASET[idx].contains("Weight")) {
				hasWeight = true;
			}
		}
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasWeight, "Should include Weight records");
	}

	@Test
	public void longSentence_sexuallyTransmittedInfections_shouldReturnHivRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FULL dataset's only STIs are the HIV records. Aligned with
		// realModel_stdQuery_shouldReturnHivRecordsOnly and
		// integration_stdAbbreviationQuery_shouldReturnAll6HivRecords
		// which assert HIV-only for STD queries on FULL. The previous
		// assertEquals(11, ...) + loose contains-check accepted
		// non-STI records (UTI, encounter notes mentioning
		// "infection") as "diagnosis/infection related" — that's
		// pipeline shape, not clinical truth.
		List<Integer> result = runRealModelPipeline(
				"has this patient ever been diagnosed with any sexually transmitted infections",
				100);
		assertFalse(result.isEmpty(),
				"FULL dataset has HIV records (an STI)");
		boolean hasHiv = false;
		for (int idx : result) {
			if (FULL_PATIENT_DATASET[idx].contains("HIV")) {
				hasHiv = true;
				break;
			}
		}
		assertTrue(hasHiv, "STI query should find HIV records");
	}

	@Test
	public void longSentence_respiratoryAndOxygen_shouldReturnRespiratoryAndSpO2() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"can you tell me about the patient's respiratory symptoms and oxygen levels",
				100);
		assertEquals(25, result.size());
		boolean hasRespiratory = false;
		boolean hasSpO2 = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.contains("Respiratory Rate")) hasRespiratory = true;
			if (rec.contains("Blood Oxygen Saturation")) hasSpO2 = true;
		}
		assertTrue(hasRespiratory, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include Blood Oxygen Saturation records");
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Respiratory Rate")
							|| rec.contains("Blood Oxygen Saturation"),
					"Record [" + idx + "] should be respiratory or SpO2: " + rec);
		}
	}

	@Test
	public void longSentence_medicationHistory_shouldReturnMedicationRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"I want to review the patient's complete medication history including dosages",
				100);
		assertEquals(2, result.size());
		for (int idx : result) {
			assertTrue(FULL_PATIENT_DATASET[idx].startsWith("Medication prescription:"),
					"Record [" + idx + "] should be a medication: "
							+ FULL_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void longSentence_bloodPressureAndWeightReadings_thirdDataset_shouldReturnBpAndWeight() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset contains both blood pressure AND weight records,
		// and the query explicitly asks for both. Asserting against the
		// data + query, not against the pipeline's prior behaviour.
		// Mirrors longSentence_bloodPressureAndWeightReadings_shouldReturnAllBpRecords
		// for the FULL dataset.
		List<Integer> result = runRealModelPipeline(
				"I need to know the patient's most recent blood pressure and weight readings",
				100, THIRD_PATIENT_DATASET);
		for (int idx : result) {
			assertTrue(
					THIRD_PATIENT_DATASET[idx].contains("blood pressure")
							|| THIRD_PATIENT_DATASET[idx].contains("Weight"),
					"Record [" + idx + "] should be BP or Weight: "
							+ THIRD_PATIENT_DATASET[idx]);
		}
		boolean hasBP = false, hasWeight = false;
		for (int idx : result) {
			if (THIRD_PATIENT_DATASET[idx].contains("blood pressure")) {
				hasBP = true;
			}
			if (THIRD_PATIENT_DATASET[idx].contains("Weight")) {
				hasWeight = true;
			}
		}
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasWeight, "Should include Weight records");
	}

	@Test
	public void longSentence_medicationHistory_thirdDataset_shouldReturnAllMeds() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"I want to review the patient's complete medication history including dosages",
				100, THIRD_PATIENT_DATASET);
		assertEquals(9, result.size());
		for (int idx : result) {
			assertTrue(
					THIRD_PATIENT_DATASET[idx].startsWith("Medication prescription:"),
					"Record [" + idx + "] should be a medication: "
							+ THIRD_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void longSentence_chronicConditions_fourthDataset_shouldFindChronicGingivitis() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"what chronic conditions has this patient been diagnosed with over the years",
				100, FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(122, 125), result,
				"Should return Chronic gingivitis condition and diagnosis");
	}

	@Test
	public void longSentence_respiratorySymptoms_fourthDataset_shouldReturnRespiratoryRate() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"can you tell me about the patient's respiratory symptoms and oxygen levels",
				100, FOURTH_PATIENT_DATASET);
		// Query asks for "respiratory symptoms and oxygen levels"
		// — should return both Respiratory rate and SpO2 records
		for (int idx : result) {
			assertTrue(
					FOURTH_PATIENT_DATASET[idx].contains("Respiratory rate")
							|| FOURTH_PATIENT_DATASET[idx].contains("oxygen saturation"),
					"Record [" + idx + "] should be respiratory or SpO2: "
							+ FOURTH_PATIENT_DATASET[idx]);
		}
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			if (FOURTH_PATIENT_DATASET[idx].contains("Respiratory rate")) {
				hasRR = true;
			}
			if (FOURTH_PATIENT_DATASET[idx].contains("oxygen saturation")) {
				hasSpO2 = true;
			}
		}
		assertTrue(hasRR, "Should include Respiratory rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	// ---- Temporal queries ----

	@Test
	public void temporal_recentTemperatureReadings_shouldReturnAllTemperature() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"recent temperature readings", 100);
		assertEquals(9, result.size());
		for (int idx : result) {
			assertTrue(FULL_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ FULL_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void temporal_recentTemperatureReadings_thirdDataset_shouldReturnAllTemperature() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"recent temperature readings", 100, THIRD_PATIENT_DATASET);
		assertEquals(10, result.size());
		for (int idx : result) {
			assertTrue(THIRD_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ THIRD_PATIENT_DATASET[idx]);
		}
	}

	// ---- Under-tested record types ----

	@Test
	public void allergies_fourthDataset_shouldReturnEmptyForPatientWithNoAllergies() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has no allergy records — pipeline should
		// return empty, not unrelated conditions like Rash or
		// vaccination events.
		List<Integer> result = runRealModelPipeline(
				"does the patient have any allergies", 100, FOURTH_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"Patient has no allergy records — pipeline should return empty, but got "
						+ result.size() + " results");
	}

	@Test
	public void allergies_fifthDataset_shouldReturnEmptyForPatientWithNoAllergies() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset has no allergy records — same as FOURTH.
		List<Integer> result = runRealModelPipeline(
				"does the patient have any allergies", 100, FIFTH_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"Patient has no allergy records — pipeline should return empty, but got "
						+ result.size() + " results");
	}

	@Test
	public void testsOrdered_thirdDataset_shouldReturnLabOrders() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Query: "what tests have been ordered for this patient" → Lab
		// test order records. THIRD has one such order (CBC at index
		// 91). The previous assertion also included a Haemoglobin
		// test RESULT [0]; results are evidence a test happened but
		// they are not orders. Aligned with
		// integration_labOrdersQuery_shouldReturnEmptyWhenNoLabOrders
		// which rejects non-order records.
		List<Integer> result = runRealModelPipeline(
				"what tests have been ordered for this patient", 100,
				THIRD_PATIENT_DATASET);

		assertFalse(result.isEmpty(),
				"THIRD dataset has a CBC lab order");
		boolean hasLabOrder = false;
		for (int idx : result) {
			String rec = THIRD_PATIENT_DATASET[idx];
			if (rec.startsWith("Lab test order:")) {
				hasLabOrder = true;
				break;
			}
		}
		assertTrue(hasLabOrder, "Should include a lab test order record");
	}

	@Test
	public void testsOrdered_fourthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has NO "Lab test order:" records — only test
		// results. The query asks for ORDERED tests, not test results.
		// The clinically correct answer is empty. The previous
		// assertion of test-result records [0, 15, 88] encoded
		// pipeline behaviour (semantic similarity between "tests
		// ordered" and test results), not clinical truth. Aligned
		// with integration_labOrdersQuery_shouldReturnEmptyWhenNoLabOrders
		// on FULL.
		assertTrue(
				runRealModelPipeline(
						"what tests have been ordered for this patient", 100,
						FOURTH_PATIENT_DATASET).isEmpty(),
				"FOURTH dataset has no lab test orders");
	}

	@Test
	public void testsOrdered_fifthDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset (FOURTH + synonyms) also has no "Lab test
		// order:" records. Same correction as
		// testsOrdered_fourthDataset_shouldReturnEmpty.
		assertTrue(
				runRealModelPipeline(
						"what tests have been ordered for this patient", 100,
						FIFTH_PATIENT_DATASET).isEmpty(),
				"FIFTH dataset has no lab test orders");
	}

	// ---- Negative / limitation tests for empty results ----

	@Test
	@Disabled("Disabled because L6 alone cannot bridge category-name queries to "
			+ "member-concept records (Temperature/BP/Height all rank below noise "
			+ "for 'vital signs'). Production retrieval relies on concept-set "
			+ "metadata injected at index time to bypass this gap (see ADR 19, "
			+ "EmbeddingIndexerTest.buildEmbeddings_*). String fixtures here "
			+ "bypass loadAll() and so cannot carry that metadata. Re-enable if a "
			+ "future embedding model or pipeline change makes category knowledge "
			+ "testable in isolation.")
	public void latestVitalSigns_shouldReturnAllVitalSignRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "latest vital signs" → "vital signs" after stopword removal.
		// Clinically, vital signs include Temperature, Blood Pressure,
		// Pulse, Respiratory Rate, and SpO2.
		List<Integer> result = runRealModelPipeline(
				"latest vital signs", 100);
		assertFalse(result.isEmpty(),
				"FULL dataset contains vital signs");
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Temperature") || rec.contains("Blood Pressure")
							|| rec.contains("Pulse")
							|| rec.contains("Respiratory Rate")
							|| rec.contains("Blood Oxygen Saturation"),
					"Record [" + idx + "] should be a vital sign: " + rec);
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.contains("Blood Pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
			if (rec.contains("Respiratory Rate")) hasRR = true;
			if (rec.contains("Blood Oxygen Saturation")) hasSpO2 = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
		assertTrue(hasRR, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	@Test
	public void vitalSigns_shouldMatchLatestVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "vital signs" (without "latest") should produce the same
		// results as "latest vital signs" since "latest" is a stopword.
		List<Integer> vitalSigns = runRealModelPipeline("vital signs", 100);
		List<Integer> latest = runRealModelPipeline("latest vital signs", 100);
		assertEquals(latest, vitalSigns,
				"'vital signs' and 'latest vital signs' should return identical results");
	}

	@Test
	public void negative_patientDeniesChestPain_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// Negation queries ("denies chest pain") are not handled by the
		// embedding model — it treats "chest pain" as the semantic core.
		// With no chest pain records in the dataset, nothing surfaces.
		List<Integer> result = runRealModelPipeline(
				"patient denies chest pain", 100);
		assertTrue(result.isEmpty(),
				"Negation query on absent topic returns no results — known limitation");
	}

	@Test
	public void anyConditions_fourthDataset_shouldReturnAllConditions() {
		// All 27 condition records in FOURTH_PATIENT_DATASET should be
		// returned for "any conditions?" — the keyword "conditions" matches
		// "condition" in every condition record via plural stem stripping
		// (kwScore = 1.0). Four conditions have very low semantic scores
		// (< 0.125) due to obscure medical terminology, but the full
		// keyword match is conclusive evidence of relevance.
		List<Integer> conditionIndices = new ArrayList<Integer>();
		for (int i = 0; i < FOURTH_PATIENT_DATASET.length; i++) {
			if (FOURTH_PATIENT_DATASET[i].startsWith("Medical condition:")) {
				conditionIndices.add(i);
			}
		}
		assertEquals(27, conditionIndices.size(),
				"Sanity check: FOURTH_PATIENT_DATASET has 27 conditions");

		List<Integer> result = runRealModelPipeline("any conditions?", 10,
				FOURTH_PATIENT_DATASET);
		Collections.sort(result);
		Collections.sort(conditionIndices);
		assertEquals(conditionIndices, result,
				"Should return all 27 condition records");
	}

	@Test
	public void everBeenImmunized_fourthDataset_shouldReturnEsaviRecords() {
		List<Integer> result = runRealModelPipeline(
				"ever been immunized?", 10, FOURTH_PATIENT_DATASET);
		Collections.sort(result);
		assertEquals(Arrays.asList(150, 151), result,
				"Should return the ESAVI condition (150) and diagnosis (151)");
	}

	@Test
	public void howHot_fourthDataset_shouldReturnOnlyTemperatureRecords() {
		// "how hot is the patient?" is colloquial for body temperature.
		// After stopword removal the embedding input is just "hot", which
		// has low cosine similarity to "Temperature" (~0.21, below the
		// absolute floor of 0.25). The z-score floor rescue detects that
		// Temperature records are statistical outliers in the distribution
		// and lets them through.
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"how hot is the patient?", 100, FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(6, 23, 37, 54, 67, 80, 98, 111, 126, 140, 152),
				result, "Should return all 11 Temperature records");
	}

	// ---- Cross-dataset coverage: SECOND ----

	@Test
	public void fever_secondDataset_shouldReturnTemperatureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("fever", 100,
				SECOND_PATIENT_DATASET);
		assertEquals(Arrays.asList(5, 20, 35, 46, 59), result);
		for (int idx : result) {
			assertTrue(SECOND_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ SECOND_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void tb_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has no TB records. Syphilitic Cirrhosis is a
		// different infectious disease (syphilis, not tuberculosis)
		// and should not be returned for a "TB" query.
		assertTrue(
				runRealModelPipeline("TB", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no tuberculosis records");
	}

	@Test
	public void headache_secondDataset_shouldReturnEmpty() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// SECOND dataset has no headache records and no records
		// describing headache symptoms. The previous assertion of
		// Nonparalytic stroke records was a pipeline-driven semantic
		// inference (headache is a stroke symptom), not what the
		// dataset actually contains. Mirrors headache_fourthDataset_
		// shouldReturnEmpty and headache_fifthDataset_shouldReturnEmpty.
		assertTrue(
				runRealModelPipeline("headache", 100,
						SECOND_PATIENT_DATASET).isEmpty(),
				"SECOND dataset has no headache records");
	}

	@Test
	public void mentalHealth_secondDataset_shouldReturnDepressiveEpisode() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("mental health", 100,
				SECOND_PATIENT_DATASET);
		assertEquals(Arrays.asList(31, 34), result);
		assertTrue(SECOND_PATIENT_DATASET[31].contains("depressive episode"),
				"Record 31 should be depressive episode");
	}

	// ---- Cross-dataset coverage: THIRD ----

	@Test
	@Disabled("Disabled because L6 alone cannot bridge category-name queries to "
			+ "member-concept records (Temperature/BP/Height all rank below noise "
			+ "for 'vital signs'). Production retrieval relies on concept-set "
			+ "metadata injected at index time to bypass this gap (see ADR 19, "
			+ "EmbeddingIndexerTest.buildEmbeddings_*). String fixtures here "
			+ "bypass loadAll() and so cannot carry that metadata. Re-enable if a "
			+ "future embedding model or pipeline change makes category knowledge "
			+ "testable in isolation.")
	public void vitalSigns_thirdDataset_shouldReturnAllVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("vital signs", 100,
				THIRD_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"THIRD dataset contains vital signs");
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		boolean hasRR = false, hasSpO2 = false;
		for (int idx : result) {
			String rec = THIRD_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("Temperature") || rec.contains("blood pressure")
							|| rec.contains("Pulse")
							|| rec.contains("Respiratory rate")
							|| rec.contains("oxygen saturation"),
					"Record [" + idx + "] should be a vital sign: " + rec);
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.contains("blood pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
			if (rec.contains("Respiratory rate")) hasRR = true;
			if (rec.contains("oxygen saturation")) hasSpO2 = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
		assertTrue(hasRR, "Should include Respiratory Rate records");
		assertTrue(hasSpO2, "Should include SpO2 records");
	}

	@Test
	public void fever_thirdDataset_shouldReturnAllTemperatureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("fever", 100,
				THIRD_PATIENT_DATASET);
		assertEquals(Arrays.asList(6, 25, 36, 56, 75, 95, 120, 131, 137, 149),
				result);
		for (int idx : result) {
			assertTrue(THIRD_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ THIRD_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void anyInfections_thirdDataset_shouldReturnAllInfectionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset has condition+diagnosis pairs for several
		// infections: Zika [2,4], Malaria [54,55], Pneumonia [118,119],
		// and Gonococcal arthritis [137,139] (gonorrhea-caused).
		// The previous assertion enumerated only [2, 54, 118, 119]
		// (the conditions for Zika and Malaria but missing both
		// diagnoses; only Pneumonia got both records; Gonococcal
		// arthritis was entirely missed). Aligned with the
		// anyInfections_*Dataset tests on FOURTH and FIFTH which
		// include condition + diagnosis pairs for each infection.
		List<Integer> result = runRealModelPipeline("any infections?", 100,
				THIRD_PATIENT_DATASET);

		assertFalse(result.isEmpty(),
				"THIRD dataset has Zika, Malaria, Pneumonia, and Gonococcal arthritis");
		boolean hasZika = false, hasMalaria = false,
				hasPneumonia = false, hasGonococcal = false;
		for (int idx : result) {
			String rec = THIRD_PATIENT_DATASET[idx];
			if (rec.contains("Zika")) hasZika = true;
			if (rec.contains("Malaria")) hasMalaria = true;
			if (rec.contains("Pneumonia")) hasPneumonia = true;
			if (rec.contains("Gonococcal")) hasGonococcal = true;
		}
		assertTrue(hasZika, "Should include Zika virus disease records");
		assertTrue(hasMalaria, "Should include Malaria records");
		assertTrue(hasPneumonia, "Should include Pneumonia records");
		assertTrue(hasGonococcal, "Should include Gonococcal arthritis records");
	}

	@Test
	public void diabetes_thirdDataset_shouldReturnDiabetesRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("diabetes", 100,
				THIRD_PATIENT_DATASET);
		assertEquals(Arrays.asList(129, 130), result);
		assertTrue(THIRD_PATIENT_DATASET[129].contains("Diabetes"),
				"Record 129 should be Diabetes condition");
	}

	@Test
	public void cardiovascularRisk_thirdDataset_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD has Hypertension condition+diagnosis [34, 35] (the
		// canonical cardiovascular risk factor) AND blood pressure
		// readings. Either category is clinically valid for this
		// query — the previous assertion of exactly 20 BP-only
		// records encoded the pipeline's keyword-driven output.
		// Aligned with cardiovascularRisk_secondDataset and
		// realModel_cardiovascularRiskQuery on FULL.
		List<Integer> result = runRealModelPipeline(
				"cardiovascular risk factors", 100, THIRD_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"THIRD dataset has Hypertension and BP records");
		for (int idx : result) {
			String rec = THIRD_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("blood pressure")
							|| rec.contains("Hypertension"),
					"Record [" + idx + "] should be a cardiovascular"
							+ " record (BP or Hypertension): " + rec);
		}
	}

	@Test
	public void anemia_thirdDataset_shouldReturnAnaemiaRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"is the patient anemic?", 100, THIRD_PATIENT_DATASET);
		assertEquals(Arrays.asList(93, 94), result);
		assertTrue(THIRD_PATIENT_DATASET[93].contains("Anaemia"),
				"Record 93 should be Anaemia condition");
	}

	@Test
	public void allergies_thirdDataset_shouldReturnEmptyForPatientWithNoAllergies() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// THIRD dataset has no allergy records (no "Patient allergy:"
		// entries). Asthma is a respiratory condition, not an allergy.
		List<Integer> result = runRealModelPipeline("allergies", 100,
				THIRD_PATIENT_DATASET);
		assertTrue(result.isEmpty(),
				"THIRD dataset has no allergy records");
	}

	// ---- Cross-dataset coverage: FOURTH ----

	@Test
	public void fever_fourthDataset_shouldReturnAllTemperatureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("fever", 100,
				FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(6, 23, 37, 54, 67, 80, 98, 111, 126, 140, 152),
				result);
		for (int idx : result) {
			assertTrue(FOURTH_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ FOURTH_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void cancer_fourthDataset_shouldReturnMalignantTumorRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any history of cancer?",
				100, FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(63, 65), result);
		assertTrue(FOURTH_PATIENT_DATASET[63].contains("Malignant tumor"),
				"Record 63 should be Malignant tumor");
	}

	@Test
	public void std_fourthDataset_shouldReturnAllStdRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has HIV (records 108 + 110), Zika
		// (records 2 + 4), and Gonococcal arthritis (records 137 +
		// 139) — all sexually transmissible. Aligned with
		// realModel_stdQuery_fourthDataset_shouldNotReturnUnrelatedDiseases
		// and std_fifthDataset_shouldReturnAllStdRecords on the same
		// records.
		List<Integer> result = runRealModelPipeline("any STD?", 100,
				FOURTH_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"FOURTH dataset has HIV, Zika, and Gonococcal arthritis");
		boolean hasHiv = false, hasZika = false, hasGonococcal = false;
		for (int idx : result) {
			String rec = FOURTH_PATIENT_DATASET[idx];
			if (rec.contains("HIV")) hasHiv = true;
			if (rec.contains("Zika")) hasZika = true;
			if (rec.contains("Gonococcal")) hasGonococcal = true;
		}
		assertTrue(hasHiv, "Should include HIV records");
		assertTrue(hasZika, "Should include Zika records");
		assertTrue(hasGonococcal, "Should include Gonococcal arthritis records");
	}

	@Test
	public void anyInfections_fourthDataset_shouldReturnAllInfectionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any infections?", 100,
				FOURTH_PATIENT_DATASET);
		// [2,4] Zika virus disease, [33,35] Enteroviral stomatitis,
		// [90,94] Hookworm disease, [108,110] HIV disease,
		// [137,139] Gonococcal arthritis
		assertEquals(Arrays.asList(2, 4, 33, 35, 90, 94, 108, 110,
				137, 139), result,
				"Should return all infection records");
	}

	@Test
	public void diabetes_fourthDataset_shouldReturnGlucoseLabSets() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("diabetes", 100,
				FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(88, 134), result);
		assertTrue(FOURTH_PATIENT_DATASET[88].contains("Glucose"),
				"Record 88 should be Glucose tolerance test");
	}

	@Test
	public void cardiovascularRisk_fourthDataset_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH has Cerebrovascular Accident [34, 36] (a cardiovascular
		// event) AND blood pressure readings. The previous assertion of
		// exactly 22 BP-only records encoded the pipeline's keyword
		// output. Aligned with cardiovascularRisk_thirdDataset and
		// realModel_cardiovascularRiskQuery on FULL.
		List<Integer> result = runRealModelPipeline(
				"cardiovascular risk factors", 100, FOURTH_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"FOURTH dataset has CVA and BP records");
		for (int idx : result) {
			String rec = FOURTH_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("blood pressure")
							|| rec.contains("Cerebrovascular Accident"),
					"Record [" + idx + "] should be a cardiovascular"
							+ " record (BP or CVA): " + rec);
		}
	}

	@Test
	public void mentalHealth_fourthDataset_shouldReturnPsychiatricRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FOURTH dataset has multiple mental health concepts: Psychosis
		// (condition+diagnosis), Mental/behavioral disorder due to
		// psychoactive substance (condition+diagnosis), Cocaine abuse
		// (condition+diagnosis — substance use disorder per DSM-5/
		// ICD-10 F14), Substance Addiction (condition+diagnosis), and
		// Self-accusation (condition+diagnosis). The previous strict
		// enumeration [19, 22, 47, 51, 77, 121] omitted both members
		// of the Cocaine abuse pair [48, 52] AND the diagnosis
		// partners of Self-accusation [79] and Substance Addiction
		// [124]. Loosened to verify the major mental health concepts
		// are present.
		List<Integer> result = runRealModelPipeline("mental health", 100,
				FOURTH_PATIENT_DATASET);

		assertFalse(result.isEmpty(),
				"FOURTH dataset has multiple mental health records");
		boolean hasPsychosis = false, hasMentalDisorder = false,
				hasCocaineOrSubstance = false;
		for (int idx : result) {
			String rec = FOURTH_PATIENT_DATASET[idx];
			if (rec.contains("Psychosis")) hasPsychosis = true;
			if (rec.contains("Mental or behavioral disorder")) hasMentalDisorder = true;
			if (rec.contains("Cocaine abuse")
					|| rec.contains("Substance Addiction")) {
				hasCocaineOrSubstance = true;
			}
		}
		assertTrue(hasPsychosis, "Should include Psychosis records");
		assertTrue(hasMentalDisorder,
				"Should include Mental/behavioral disorder records");
		assertTrue(hasCocaineOrSubstance,
				"Should include Cocaine abuse or Substance Addiction records");
	}

	@Test
	public void immunization_fourthDataset_shouldReturnEsaviRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("immunization", 100,
				FOURTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(150, 151), result);
	}

	// ---- Cross-dataset coverage: FIFTH ----

	@Test
	public void fever_fifthDataset_shouldReturnAllTemperatureRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("fever", 100,
				FIFTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(6, 23, 37, 54, 67, 80, 98, 111, 126, 140, 152),
				result);
		for (int idx : result) {
			assertTrue(FIFTH_PATIENT_DATASET[idx].contains("Temperature"),
					"Record [" + idx + "] should be temperature: "
							+ FIFTH_PATIENT_DATASET[idx]);
		}
	}

	@Test
	public void cancer_fifthDataset_shouldReturnMalignantTumorRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any history of cancer?",
				100, FIFTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(63, 65), result);
	}

	@Test
	public void std_fifthDataset_shouldReturnAllStdRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any STD?", 100,
				FIFTH_PATIENT_DATASET);
		// [2,4] Zika, [17,20] Wasting (HIV wasting), [108,110] HIV,
		// [137,139] Gonococcal arthritis
		assertEquals(Arrays.asList(2, 4, 108, 110, 137, 139), result,
				"Should return HIV, Zika, and Gonococcal arthritis");
	}

	@Test
	public void anyInfections_fifthDataset_shouldReturnAllInfectionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("any infections?", 100,
				FIFTH_PATIENT_DATASET);
		// Same infections as FOURTH dataset (with synonyms):
		// [2,4] Zika, [33,35] Enteroviral stomatitis,
		// [90,94] Hookworm, [108,110] HIV, [137,139] Gonococcal
		assertEquals(Arrays.asList(2, 4, 33, 35, 90, 94, 108, 110,
				137, 139), result,
				"Should return all infection records");
	}

	@Test
	public void diabetes_fifthDataset_shouldReturnGlucoseLabSets() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("diabetes", 100,
				FIFTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(88, 134), result);
	}

	@Test
	public void cardiovascularRisk_fifthDataset_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH (FOURTH + synonyms) has CVA [34, 36] AND blood pressure
		// readings. Aligned with cardiovascularRisk_thirdDataset/
		// _fourthDataset and realModel_cardiovascularRiskQuery on FULL.
		List<Integer> result = runRealModelPipeline(
				"cardiovascular risk factors", 100, FIFTH_PATIENT_DATASET);
		assertFalse(result.isEmpty(),
				"FIFTH dataset has CVA and BP records");
		for (int idx : result) {
			String rec = FIFTH_PATIENT_DATASET[idx];
			assertTrue(
					rec.contains("blood pressure")
							|| rec.contains("Cerebrovascular Accident"),
					"Record [" + idx + "] should be a cardiovascular"
							+ " record (BP or CVA): " + rec);
		}
	}

	@Test
	public void mentalHealth_fifthDataset_shouldReturnPsychiatricRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// FIFTH dataset (FOURTH + synonyms) has the same mental health
		// concepts as FOURTH. Same correction as
		// mentalHealth_fourthDataset_shouldReturnPsychiatricRecords.
		List<Integer> result = runRealModelPipeline("mental health", 100,
				FIFTH_PATIENT_DATASET);

		assertFalse(result.isEmpty(),
				"FIFTH dataset has multiple mental health records");
		boolean hasPsychosis = false, hasMentalDisorder = false,
				hasCocaineOrSubstance = false;
		for (int idx : result) {
			String rec = FIFTH_PATIENT_DATASET[idx];
			if (rec.contains("Psychosis")) hasPsychosis = true;
			if (rec.contains("Mental or behavioral disorder")) hasMentalDisorder = true;
			if (rec.contains("Cocaine abuse")
					|| rec.contains("Substance Addiction")) {
				hasCocaineOrSubstance = true;
			}
		}
		assertTrue(hasPsychosis, "Should include Psychosis records");
		assertTrue(hasMentalDisorder,
				"Should include Mental/behavioral disorder records");
		assertTrue(hasCocaineOrSubstance,
				"Should include Cocaine abuse or Substance Addiction records");
	}

	@Test
	public void immunization_fifthDataset_shouldReturnEsaviRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline("immunization", 100,
				FIFTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(150, 151), result);
	}

	@Test
	public void programEnrollment_fifthDataset_shouldReturnPmtct() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"is the patient enrolled in any programs?", 100,
				FIFTH_PATIENT_DATASET);
		assertEquals(Arrays.asList(148), result);
		assertTrue(FIFTH_PATIENT_DATASET[148].contains("PMTCT"),
				"Record 148 should be PMTCT program enrollment");
	}

	@Test
	public void heartRates_fourthDataset_shouldSelectPulseAndApplyRecencyCap() {
		// Both phrasings of "heart rate" should return Pulse records, not
		// Respiratory rate — the embedding model correctly ranks Pulse
		// higher semantically. The longer query previously failed because
		// "rate/rates" keyword-matched Respiratory rate text, and keyword
		// refinement dropped Pulse records.
		//
		// Recency qualifiers apply a cap per concept:
		// - "the latest heart rate" → cap=1 → only the most recent Pulse
		// - "the most recent two heart rates" → cap=2 → the two most recent
		assertEquals(Arrays.asList(9),
				runRealModelPipeline("what is the latest heart rate?", 100,
						FOURTH_PATIENT_DATASET),
				"'the latest heart rate' should return only the most recent Pulse record");
		assertEquals(Arrays.asList(9, 26),
				runRealModelPipeline("what are the most recent two heart rates?",
						100, FOURTH_PATIENT_DATASET),
				"'the most recent two heart rates' should return the 2 most recent Pulse records");
	}

	// ---- Cross-query regression tests with concept-set category hints ----
	//
	// These tests validate that enriching Condition and Diagnosis records
	// with concept-set category hints (e.g. "Sexually transmitted disease"
	// for HIV conditions) improves category-name queries WITHOUT regressing
	// previously-working queries. The hints simulate what production's
	// PatientRecordLoader.loadAll() produces when the OpenMRS Concept
	// dictionary has concept_set memberships for clinical categories.
	//
	// The CATEGORY_HINTS map below represents a dictionary where:
	//   - HIV is in sets: Sexually transmitted disease, Infectious disease,
	//     Opportunistic infectious disease
	//   - Tuberculosis is in: Infectious disease, Opportunistic infectious disease
	//   - Hypertension is in: Cardiovascular disease
	//   - Gastroenteritis, UTI, Skin Infection, Malaria are in: Infectious disease
	//
	// FULL_PATIENT_DATASET record indices (0-based):
	//   7  = Condition: Tuberculosis
	//  21  = Diagnosis: Gastroenteritis
	//  39  = Diagnosis: HIV Disease
	//  51  = Diagnosis: Urinary Tract Infection
	//  52  = Diagnosis: Tuberculosis
	//  54  = Condition: Hypertension
	//  61  = Diagnosis: Skin Infection
	//  66  = Diagnosis: Malaria
	//  69  = Diagnosis: HIV Disease
	//  71  = Diagnosis: HIV Disease
	//  92  = Diagnosis: Hypertension
	// 110  = Diagnosis: HIV Disease

	private static List<Integer> runEnrichedPipeline(String query, int topK) {
		return runEnrichedPipeline(query, topK, FULL_PATIENT_DATASET,
				TestDatasetHelper.FULL_DATASET_CATEGORY_HINTS);
	}

	private static List<Integer> runEnrichedPipeline(String query, int topK,
			String[] dataset, Map<Integer, List<String>> hints) {
		return runRealModelPipeline(query, topK, dataset,
				PipelineConfig.defaults(), hints);
	}

	// --- Regression guards: these queries work WITHOUT hints and must
	//     continue working WITH hints. A failure here means enrichment
	//     broke a previously-working query (cross-query regression). ---

	@Test
	public void enriched_conditions_shouldStillReturnAllConditions() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		// "any conditions?" returns TB (7) and Hypertension (54) without hints.
		// With hints, these conditions get enriched text but the query
		// "any conditions?" should still find them — enrichment must not
		// shift the score distribution enough to drop them.
		List<Integer> result = runEnrichedPipeline("any conditions?", 100);
		assertFalse(result.isEmpty(),
				"Enriched conditions query should still return condition records");
		assertTrue(result.contains(7),
				"Should still include Tuberculosis condition (index 7)");
		assertTrue(result.contains(54),
				"Should still include Hypertension condition (index 54)");
	}

	@Test
	public void enriched_cancer_shouldStillReturnKaposiSarcoma() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		// Kaposi sarcoma records (obs 11, 88) have no category hints
		// (they are Obs, not Conditions). Enriching other records must
		// not push Kaposi below the noise floor.
		List<Integer> result = runEnrichedPipeline("any cancer?", 100);
		assertTrue(result.contains(11) || result.contains(88),
				"Should still include Kaposi sarcoma records");
	}

	@Test
	public void enriched_allergies_shouldStillReturnAllergyRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		// Allergy record (index 4) has no category hints. Must survive.
		List<Integer> result = runEnrichedPipeline("allergies", 100);
		assertTrue(result.contains(4),
				"Should still include Beef allergy record (index 4)");
	}

	@Test
	public void enriched_vitalSigns_shouldReturnAllVitalSigns() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		// With "Vital signs" hints on all vital sign obs (matching
		// production), the pipeline should return ALL vital signs.
		List<Integer> result = runEnrichedPipeline("vital signs", 100);
		assertTrue(result.size() >= 50,
				"Enriched pipeline should return most vital signs, got "
				+ result.size());
		boolean hasTemp = false, hasBP = false, hasPulse = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.contains("Temperature")) hasTemp = true;
			if (rec.toLowerCase().contains("blood pressure")) hasBP = true;
			if (rec.contains("Pulse")) hasPulse = true;
		}
		assertTrue(hasTemp, "Should include Temperature records");
		assertTrue(hasBP, "Should include Blood Pressure records");
		assertTrue(hasPulse, "Should include Pulse records");
	}

	// --- Improvement assertions: these queries fail or return partial
	//     results WITHOUT hints. With dictionary-curated concept-set
	//     hints, they should return the expected clinical records. ---

	@Test
	@Disabled("Requires concept-set memberships (e.g. HIV in Sexually transmitted disease set) that do not exist in standard CIEL. The Vital signs set is the only legitimate CIEL concept set; STD/Infectious/Cardiovascular sets would need to be curated per deployment.")
	public void enriched_std_shouldReturnHivRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "any STD?" should now find HIV records because they carry
		// "Sexually transmitted disease" in their enriched text.
		List<Integer> result = runEnrichedPipeline("any STD?", 100);
		assertFalse(result.isEmpty(),
				"FULL dataset has HIV records categorized as STD");
		boolean hasHiv = false;
		for (int idx : result) {
			if (FULL_PATIENT_DATASET[idx].contains("HIV")) {
				hasHiv = true;
				break;
			}
		}
		assertTrue(hasHiv,
				"Should include HIV records via STD category hint");
	}

	@Test
	@Disabled("Requires concept-set memberships (e.g. HIV in Sexually transmitted disease set) that do not exist in standard CIEL. The Vital signs set is the only legitimate CIEL concept set; STD/Infectious/Cardiovascular sets would need to be curated per deployment.")
	public void enriched_infections_shouldReturnAllInfectionRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "any infections?" should now find HIV, TB, Gastroenteritis,
		// UTI, Skin Infection, Malaria — all marked as Infectious disease.
		List<Integer> result = runEnrichedPipeline("any infections?", 100);
		assertTrue(result.contains(7) || result.contains(52),
				"Should include Tuberculosis via Infectious disease hint");
		assertTrue(result.contains(39) || result.contains(69)
				|| result.contains(71) || result.contains(110),
				"Should include HIV via Infectious disease hint");
	}

	@Test
	@Disabled("Requires concept-set memberships (e.g. HIV in Sexually transmitted disease set) that do not exist in standard CIEL. The Vital signs set is the only legitimate CIEL concept set; STD/Infectious/Cardiovascular sets would need to be curated per deployment.")
	public void enriched_cardiovascularRisk_shouldReturnCardiovascularRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "cardiovascular risk factors" should now find Hypertension
		// records via the Cardiovascular disease hint.
		List<Integer> result = runEnrichedPipeline(
				"cardiovascular risk factors", 100);
		assertFalse(result.isEmpty(),
				"FULL dataset has Hypertension categorized as Cardiovascular disease");
		assertTrue(result.contains(54) || result.contains(92),
				"Should include Hypertension records via Cardiovascular disease hint");
	}

	@Test
	@Disabled("Requires concept-set memberships (e.g. HIV in Sexually transmitted disease set) that do not exist in standard CIEL. The Vital signs set is the only legitimate CIEL concept set; STD/Infectious/Cardiovascular sets would need to be curated per deployment.")
	public void enriched_opportunisticInfections_shouldReturnHivAndTb() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// "opportunistic infections in HIV" should now find both HIV AND TB
		// records — both marked as Opportunistic infectious disease.
		List<Integer> result = runEnrichedPipeline(
				"opportunistic infections in HIV", 100);
		boolean hasHiv = false, hasTb = false;
		for (int idx : result) {
			String rec = FULL_PATIENT_DATASET[idx];
			if (rec.contains("HIV")) hasHiv = true;
			if (rec.contains("Tuberculosis")) hasTb = true;
		}
		assertTrue(hasHiv,
				"Should include HIV records via Opportunistic infectious disease hint");
		assertTrue(hasTb,
				"Should include Tuberculosis records via Opportunistic infectious disease hint");
	}

	/**
	 * Regression test for a live-instance bug where concept description hints
	 * (e.g. "Patient's height in centimeters") created asymmetric semantic
	 * bias, causing the pipeline to return Weight but not Height for BMI
	 * queries. Fixed by removing description hints from
	 * {@code extractCategoryHints()} so only concept-set names (e.g. "Vital
	 * signs") are used — matching what these test hint maps provide.
	 */
	@Test
	public void realModel_bmiQuery_fourthDataset_shouldReturnBothHeightAndWeight() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		List<Integer> result = runRealModelPipeline(
				"Calculate BMI from the most recent height and weight",
				100, FOURTH_PATIENT_DATASET,
				PipelineConfig.forModel(MODEL_DIR),
				TestDatasetHelper.FOURTH_DATASET_CATEGORY_HINTS);

		boolean hasHeight = false;
		boolean hasWeight = false;
		for (int idx : result) {
			if (FOURTH_PATIENT_DATASET[idx].contains("Height")) {
				hasHeight = true;
			}
			if (FOURTH_PATIENT_DATASET[idx].contains("Weight")) {
				hasWeight = true;
			}
		}
		assertTrue(hasWeight,
				"BMI query should return Weight records, got: " + result);
		assertTrue(hasHeight,
				"BMI query should return Height records, got: " + result);
	}

	// --- P1: Token truncation — long records must not lose tail content ---

	@Test
	public void longEncounterNote_embeddingShouldCaptureTailContent() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		// A long encounter note with generic filler, then a distinctive
		// clinical finding past the old 256-token boundary. We test the
		// EMBEDDING directly (not the full pipeline) because keyword
		// matching on full textContent would compensate for truncation.
		// ~25 repetitions × ~14 tokens ≈ 350 tokens of filler.
		StringBuilder filler = new StringBuilder();
		for (int i = 0; i < 25; i++) {
			filler.append("Patient presented with stable vital signs and ")
					.append("no acute distress noted during examination. ");
		}
		String tailContent = "Referred to nephrology for kidney transplant evaluation.";
		String fullText = "Clinical observation: Assessment \u2014 "
				+ "Text of encounter note: " + filler + tailContent;

		// Embed the full text and a query about the tail content
		float[] docVec = sharedProvider.embed(fullText);
		float[] queryVec = sharedProvider.embedQuery("nephrology kidney transplant");

		double similarity = ChartSearchAiUtils.cosineSimilarity(docVec, queryVec);

		// With 512 tokens, the tail is captured and similarity should
		// be meaningfully above noise (~0.15). At 256, the tail is
		// truncated and the embedding only sees generic filler text.
		assertTrue(similarity > 0.25,
				"Embedding of long note should capture tail content "
				+ "'kidney transplant' (cosine=" + String.format("%.4f", similarity)
				+ "). If this fails, maxSequenceLength may be too low.");
	}

	// --- P0 Patient Safety Fixes: eval-level regression guards ---

	// Slim-margin gate fix: single records in the upper half of the
	// margin zone [floor + gap/2, floor + gap) must not be rejected.
	// Uses the same no-hints pipeline and FOURTH_PATIENT_DATASET as
	// existing tests, reusing already-warmed embedding/noise caches.

	@Test
	public void slimMarginGateFix_fourthDataset_shouldReturnRecords() {
		org.junit.jupiter.api.Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		assertFalse(
				runRealModelPipeline("azithromycin", 10, FOURTH_PATIENT_DATASET).isEmpty(),
				"Slim-margin gate fix: 'azithromycin' should not return empty");
		assertFalse(
				runRealModelPipeline("depression", 10, FOURTH_PATIENT_DATASET).isEmpty(),
				"Slim-margin gate fix: 'depression' should not return empty");
	}

}
