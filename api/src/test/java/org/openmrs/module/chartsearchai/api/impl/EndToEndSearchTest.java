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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexer;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Full end-to-end test that exercises the complete search pipeline with zero mocks:
 * DB record loading → serialization → embedding indexing → query embedding →
 * filtering → chart serialization → LLM inference → answer with references.
 *
 * <p>Uses a dedicated test patient (id=999) with a 67-record dataset matching the
 * production SECOND_PATIENT_DATASET composition: 40 vitals obs, 5 encounter notes,
 * 1 lab result, 1 test order, 10 conditions, 10 diagnoses.</p>
 *
 * <p>Requires both the ONNX embedding model and a GGUF LLM model to be
 * available on the local filesystem. Skipped automatically otherwise.</p>
 *
 * <p><strong>Note on embedding model sensitivity:</strong> The filtering pipeline's
 * floor gate ({@link ChartSearchAiConstants#ABSOLUTE_SIMILARITY_FLOOR}) requires
 * the top cosine similarity to exceed 0.25. General-purpose embedding models like
 * all-MiniLM-L6-v2 may produce lower scores for medical queries (e.g. "vitals"
 * vs "Systolic Blood Pressure" → cosine ≈ 0.12), causing the pipeline to correctly
 * reject all candidates. A medical-domain embedding model will produce higher
 * scores and pass the floor gate.</p>
 */
@Tag("eval")
public class EndToEndSearchTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	private static final int TEST_PATIENT_ID = 999;

	@Autowired
	private ChartSearchAiDAO dao;

	@Autowired
	@Qualifier("chartSearchAi.llmInferenceService")
	private LlmInferenceService service;

	@Autowired
	private EmbeddingIndexer embeddingIndexer;

	@Autowired
	private PatientRecordLoader recordLoader;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
	}

	private static final String MODEL_PATH = TestDatasetHelper.MODEL_PATH;

	private static final String VOCAB_PATH = TestDatasetHelper.VOCAB_PATH;

	/**
	 * Path to the GGUF LLM model file. Configure via the
	 * {@code chartsearchai.llm.model.path} system property.
	 * When not set, searches for a GGUF file under sibling {@code models/}
	 * directories relative to both the project root and the api/ module
	 * directory (covers Maven CLI and IDE working directories).
	 */
	private static final String LLM_MODEL_PATH = resolveLlmModelPath();

	private static String resolveLlmModelPath() {
		String explicit = System.getProperty("chartsearchai.llm.model.path");
		if (explicit != null) {
			return explicit;
		}
		// Search sibling models/ dirs at both possible working directory levels
		String[] baseDirs = { "../models", "../../models" };
		for (String base : baseDirs) {
			java.io.File dir = new java.io.File(base);
			if (!dir.isDirectory()) {
				continue;
			}
			java.io.File[] subdirs = dir.listFiles(java.io.File::isDirectory);
			if (subdirs == null) {
				continue;
			}
			for (java.io.File subdir : subdirs) {
				java.io.File[] ggufFiles = subdir.listFiles(
						(d, name) -> name.endsWith(".gguf"));
				if (ggufFiles != null && ggufFiles.length > 0) {
					// Prefer the smallest model for faster tests
					java.io.File smallest = ggufFiles[0];
					for (java.io.File f : ggufFiles) {
						if (f.length() < smallest.length()) {
							smallest = f;
						}
					}
					return smallest.getPath();
				}
			}
		}
		return "../models/model.gguf"; // will fail the assumption check
	}

	private static boolean embeddingModelFilesExist() {
		return TestDatasetHelper.modelFilesExist();
	}

	private static boolean llmModelFileExists() {
		return new java.io.File(LLM_MODEL_PATH).exists();
	}

	/**
	 * Creates a hard link inside the OpenMRS application data directory pointing
	 * to the actual LLM model file, and sets the corresponding global property.
	 * A hard link is used (rather than a symlink) because
	 * {@link ChartSearchAiConstants#resolveModelPath} validates the canonical
	 * path stays within the app data directory — symlinks resolve to the
	 * target's canonical path which would be outside the app data dir.
	 * Returns the link path for cleanup.
	 */
	private static java.nio.file.Path setupLlmModelPath() throws Exception {
		java.io.File appDataDir = new java.io.File(
				org.openmrs.util.OpenmrsUtil.getApplicationDataDirectory());
		java.io.File moduleDir = new java.io.File(appDataDir, "chartsearchai");
		moduleDir.mkdirs();

		java.nio.file.Path link = moduleDir.toPath().resolve("model.gguf");
		java.nio.file.Path target = new java.io.File(LLM_MODEL_PATH)
				.getCanonicalFile().toPath();
		if (java.nio.file.Files.exists(link)) {
			java.nio.file.Files.delete(link);
		}
		java.nio.file.Files.createLink(link, target);

		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH,
				"chartsearchai/model.gguf");

		return link;
	}

	/**
	 * Swaps the {@code embeddingProvider} field on the retrieval pipeline and the
	 * indexer so that the real ONNX model is used for both query embedding and
	 * patient indexing. Returns the original providers for restoration.
	 *
	 * <p>Query embedding moved out of {@link LlmInferenceService} into
	 * {@link ChartBuildingStrategy} (which {@code service.findSimilar} now delegates
	 * to), so the provider is swapped there rather than on the service.</p>
	 */
	private Object[] swapEmbeddingProviders(Object realProvider) throws Exception {
		Object strategyTarget = chartBuildingStrategyTarget();
		java.lang.reflect.Field strategyField =
				ChartBuildingStrategy.class.getDeclaredField("embeddingProvider");
		strategyField.setAccessible(true);
		Object originalStrategyProvider = strategyField.get(strategyTarget);
		strategyField.set(strategyTarget, realProvider);

		Object indexerTarget = unwrapProxy(embeddingIndexer);
		java.lang.reflect.Field indexerField =
				EmbeddingIndexer.class.getDeclaredField("embeddingProvider");
		indexerField.setAccessible(true);
		Object originalIndexerProvider = indexerField.get(indexerTarget);
		indexerField.set(indexerTarget, realProvider);

		return new Object[] { originalStrategyProvider, originalIndexerProvider };
	}

	private void restoreEmbeddingProviders(Object[] originals) throws Exception {
		Object strategyTarget = chartBuildingStrategyTarget();
		java.lang.reflect.Field strategyField =
				ChartBuildingStrategy.class.getDeclaredField("embeddingProvider");
		strategyField.setAccessible(true);
		strategyField.set(strategyTarget, originals[0]);

		Object indexerTarget = unwrapProxy(embeddingIndexer);
		java.lang.reflect.Field indexerField =
				EmbeddingIndexer.class.getDeclaredField("embeddingProvider");
		indexerField.setAccessible(true);
		indexerField.set(indexerTarget, originals[1]);
	}

	/**
	 * Resolves the real {@link ChartBuildingStrategy} bean that {@code service}
	 * delegates retrieval to, unwrapping AOP proxies so reflective field writes
	 * land on the actual target the production search path uses.
	 */
	private Object chartBuildingStrategyTarget() throws Exception {
		Object serviceTarget = unwrapProxy(service);
		java.lang.reflect.Field strategyRef =
				LlmInferenceService.class.getDeclaredField("chartBuildingStrategy");
		strategyRef.setAccessible(true);
		return unwrapProxy(strategyRef.get(serviceTarget));
	}

	/**
	 * Sanity check: verify the embedding model produces cosine similarities
	 * above the floor gate for vitals queries against vitals records.
	 */
	@Test
	public void embeddingModel_shouldProduceReasonableSimilarities() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(embeddingModelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider provider =
				new org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider(
						MODEL_PATH, VOCAB_PATH);
		try {
			float[] query = provider.embed("how do the vitals look like");
			String spo2Text = "Finding — Arterial blood oxygen saturation (pulse oximeter): 96.6 %";
			float[] spo2 = provider.embed(ChartSearchAiUtils.buildPrefixedText(
					ChartSearchAiConstants.RESOURCE_TYPE_OBS, spo2Text));
			String conditionText = "Condition: Nonparalytic stroke. Status: ACTIVE";
			float[] condition = provider.embed(ChartSearchAiUtils.buildPrefixedText(
					ChartSearchAiConstants.RESOURCE_TYPE_CONDITION, conditionText));

			double vitalsScore = cos(query, spo2);
			double conditionScore = cos(query, condition);

			// Vitals should score above the floor gate
			assert vitalsScore > ChartSearchAiConstants.ABSOLUTE_SIMILARITY_FLOOR
					: "Vitals similarity " + vitalsScore + " should exceed floor "
					+ ChartSearchAiConstants.ABSOLUTE_SIMILARITY_FLOOR;
			// Vitals should score higher than unrelated conditions
			assert vitalsScore > conditionScore
					: "Vitals score " + vitalsScore
					+ " should exceed condition score " + conditionScore;
		} finally {
			provider.close();
		}
	}

	/**
	 * Unwraps a Spring AOP proxy to get the actual target object.
	 * Required because setting fields via reflection on a CGLIB proxy
	 * only sets them on the proxy, not the real target bean.
	 */
	private static Object unwrapProxy(Object proxy) throws Exception {
		if (org.springframework.aop.support.AopUtils.isAopProxy(proxy)) {
			return ((org.springframework.aop.framework.Advised) proxy)
					.getTargetSource().getTarget();
		}
		return proxy;
	}

	private static double cos(float[] a, float[] b) {
		return ChartSearchAiUtils.cosineSimilarity(a, b);
	}

	/**
	 * Verifies that embedding vectors survive the DB round-trip without
	 * corruption.
	 */
	@Test
	public void embeddingStorage_shouldPreserveVectorPrecision() throws Exception {
		Patient patient = Context.getPatientService().getPatient(2);

		float[] original = new float[384];
		for (int i = 0; i < original.length; i++) {
			original[i] = (float) Math.sin(i * 0.1);
		}

		ChartEmbedding ce = new ChartEmbedding();
		ce.setPatient(patient);
		ce.setResourceType("obs");
		String uuid99999 = "00000000-0000-0000-0000-000000099999";
		ce.setResourceUuid(uuid99999);
		ce.setTextContent("test");
		ce.setEmbeddingVector(original);
		ce.setDateCreated(new java.util.Date());

		dao.saveChartEmbedding(ce);
		Context.flushSession();
		Context.clearSession();

		ChartEmbedding loaded = dao.getByResource("obs", uuid99999);
		assertNotNull(loaded, "Should find saved embedding");

		float[] fromDb = loaded.getEmbeddingVector();
		assertEquals(original.length, fromDb.length, "Dimensions should match");
		for (int i = 0; i < original.length; i++) {
			assertEquals(original[i], fromDb[i], 1e-7f,
					"Vector element " + i + " should be preserved");
		}
	}

	/**
	 * Verifies the EmbeddingIndexer stores non-zero vectors that are identical
	 * to fresh embeddings from the same model (i.e. the DB round-trip through
	 * indexPatient is lossless and uses the correct provider).
	 */
	@Test
	public void embeddingIndexer_shouldStoreNonZeroVectors() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(embeddingModelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);

		executeDataSet("EndToEndFullDataset.xml");
		Patient patient = Context.getPatientService().getPatient(TEST_PATIENT_ID);

		org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider provider =
				new org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider(
						MODEL_PATH, VOCAB_PATH);
		try {
			Object[] originals = swapEmbeddingProviders(provider);
			try {
				dao.deleteByPatient(patient);
				Context.flushSession();

				embeddingIndexer.indexPatient(patient);
				Context.flushSession();
				Context.clearSession();

				List<ChartEmbedding> stored = dao.getByPatient(patient);
				assertEquals(67, stored.size(), "All 67 records should be embedded");

				// No vectors should be all-zeros
				for (ChartEmbedding ce : stored) {
					float[] vec = ce.getEmbeddingVector();
					boolean allZero = true;
					for (float v : vec) {
						if (v != 0.0f) { allZero = false; break; }
					}
					assertFalse(allZero, "Embedding for " + ce.getResourceType()
							+ ":" + ce.getResourceUuid() + " should not be all-zeros");
				}

				// Best match for vitals query should exceed the floor gate
				float[] queryVec = provider.embed("how do the vitals look like");
				double maxCos = 0;
				for (ChartEmbedding ce : stored) {
					double c = cos(queryVec, ce.getEmbeddingVector());
					if (c > maxCos) { maxCos = c; }
				}
				assert maxCos > ChartSearchAiConstants.ABSOLUTE_SIMILARITY_FLOOR
						: "Max cosine " + maxCos + " should exceed floor "
						+ ChartSearchAiConstants.ABSOLUTE_SIMILARITY_FLOOR;

				// Stored vectors should be identical to fresh embeddings
				ChartEmbedding sample = stored.get(0);
				String sampleText = ChartSearchAiUtils.buildPrefixedText(
						sample.getResourceType(), sample.getTextContent());
				float[] fresh = provider.embed(sampleText);
				double roundTripCos = cos(fresh, sample.getEmbeddingVector());
				assertEquals(1.0, roundTripCos, 1e-6,
						"Stored vector should be identical to fresh embedding");
			} finally {
				restoreEmbeddingProviders(originals);
			}
		} finally {
			provider.close();
		}
	}

	/**
	 * Full end-to-end test: loads real clinical records into the H2 database,
	 * lets the production {@link EmbeddingIndexer} build embeddings from
	 * the actual serialized records, hard-links a real GGUF model into
	 * the app data directory, then calls {@code service.search(patient, question)}
	 * — the exact same code path that the REST controller uses. Zero mocks.
	 */
	@Test
	public void search_vitalsQuery_shouldReturnVitalsAnswer() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(embeddingModelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);
		org.junit.jupiter.api.Assumptions.assumeTrue(llmModelFileExists(),
				"Skipping: LLM model file not found at " + LLM_MODEL_PATH);

		// Load the full dataset (vitals + conditions + diagnoses + encounter
		// notes + lab + order) so the embedding space matches production
		executeDataSet("EndToEndFullDataset.xml");

		Patient patient = Context.getPatientService().getPatient(TEST_PATIENT_ID);

		// Verify the dataset loaded correctly: should have exactly 67 records
		List<SerializedRecord> records = recordLoader.loadAll(patient);
		assertEquals(67, records.size(),
				"Dataset should contain exactly 67 records (matching production)");

		org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider embeddingProvider =
				new org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider(
						MODEL_PATH, VOCAB_PATH);
		java.nio.file.Path llmLink = null;
		try {
			// Clear any existing embeddings so search() triggers auto-indexing
			dao.deleteByPatient(patient);
			Context.flushSession();

			// Hard-link LLM model into app data dir and configure the GP
			llmLink = setupLlmModelPath();

			// Swap both service and indexer to real ONNX provider so
			// auto-indexing and query embedding use the same real model
			Object[] originals = swapEmbeddingProviders(embeddingProvider);

			try {
				// Trigger indexing explicitly so we can verify embeddings
				embeddingIndexer.indexPatient(patient);
				Context.flushSession();

				List<ChartEmbedding> embeddings = dao.getByPatient(patient);
				assertEquals(67, embeddings.size(),
						"All 67 records should be embedded");
				// Verify the filtering pipeline returns vitals obs.
				// Use specific vital sign terms so keyword matching activates.
				String question = "What are the blood pressure, weight, "
						+ "temperature, and pulse readings?";
				List<ChartEmbedding> filtered = service.findSimilar(
						patient, question);
				assertFalse(filtered.isEmpty(),
						"Filtering should return vitals obs");
				// The pipeline should return vitals (obs records),
				// not conditions or other record types
				for (ChartEmbedding ce : filtered) {
					assertEquals(ChartSearchAiConstants.RESOURCE_TYPE_OBS,
							ce.getResourceType(),
							"Filtered results should be obs records");
				}

				// Call the exact production code path: service.search()
				// With embeddings already indexed, this calls: findSimilar →
				// filterAndSerialize → llmProvider.search → ChartAnswer
				ChartAnswer answer = service.search(patient, question);

				// The answer must be non-null and non-empty
				assertNotNull(answer.getAnswer(), "Answer should not be null");
				assertFalse(answer.getAnswer().trim().isEmpty(),
						"LLM should produce a non-empty answer");

				// References should point to obs records
				List<RecordReference> refs = answer.getReferences();
				assertFalse(refs.isEmpty(),
						"Answer should include record references, got: "
						+ answer.getAnswer());

				// All references should be obs type (vitals are obs records)
				for (RecordReference ref : refs) {
					assertEquals(ChartSearchAiConstants.RESOURCE_TYPE_OBS,
							ref.getResourceType(),
							"All references should be obs, got: "
							+ ref.getResourceType()
							+ " for resourceUuid " + ref.getResourceUuid());
				}

				// Query about data absent from this dataset should still
				// reach the LLM for reasoning (no short-circuit).
				// The second dataset has no family planning records.
				String absentQuestion =
						"does the patient use any method of family planning?";
				ChartAnswer absentAnswer = service.search(
						patient, absentQuestion);

				assertNotNull(absentAnswer.getAnswer(),
						"LLM should still produce an answer for absent data");
				assertFalse(absentAnswer.getAnswer().trim().isEmpty(),
						"LLM answer for absent data should not be empty");
			} finally {
				restoreEmbeddingProviders(originals);
			}
		} finally {
			embeddingProvider.close();
			if (llmLink != null) {
				java.nio.file.Files.deleteIfExists(llmLink);
			}
		}
	}
}
