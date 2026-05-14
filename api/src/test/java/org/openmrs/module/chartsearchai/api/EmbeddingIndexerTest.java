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

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class EmbeddingIndexerTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	@Autowired
	private EmbeddingIndexer indexer;

	@Autowired
	private ChartSearchAiDAO dao;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
		patient = Context.getPatientService().getPatient(2);
	}

	@Test
	public void indexPatient_shouldCreateEmbeddingsForPatientRecords() {
		indexer.indexPatient(patient);
		Context.flushSession();

		List<ChartEmbedding> embeddings = dao.getByPatient(patient);
		assertFalse(embeddings.isEmpty(), "Should have created embeddings");
	}

	@Test
	public void indexPatient_shouldStoreTextContent() {
		indexer.indexPatient(patient);
		Context.flushSession();

		List<ChartEmbedding> embeddings = dao.getByPatient(patient);
		for (ChartEmbedding ce : embeddings) {
			assertNotNull(ce.getTextContent());
			assertFalse(ce.getTextContent().trim().isEmpty());
		}
	}

	@Test
	public void indexPatient_shouldStoreEmbeddingVectors() {
		indexer.indexPatient(patient);
		Context.flushSession();

		List<ChartEmbedding> embeddings = dao.getByPatient(patient);
		for (ChartEmbedding ce : embeddings) {
			float[] vector = ce.getEmbeddingVector();
			assertTrue(vector.length > 0, "Embedding vector should not be empty");
		}
	}

	@Test
	public void indexPatient_shouldDeleteExistingEmbeddingsBeforeReindexing() {
		indexer.indexPatient(patient);
		Context.flushSession();
		int firstCount = dao.getByPatient(patient).size();

		indexer.indexPatient(patient);
		Context.flushSession();
		int secondCount = dao.getByPatient(patient).size();

		assertEquals(firstCount, secondCount,
				"Re-indexing should produce the same number of embeddings");
	}

	@Test
	public void indexEncounter_shouldCreateEmbeddingsForEncounterObs() {
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		indexer.indexEncounter(encounter);
		Context.flushSession();

		List<ChartEmbedding> embeddings = dao.getByPatient(patient);
		assertFalse(embeddings.isEmpty(), "Should have created embeddings for encounter obs");

		boolean foundObs = false;
		for (ChartEmbedding ce : embeddings) {
			if ("obs".equals(ce.getResourceType())) {
				foundObs = true;
				break;
			}
		}
		assertTrue(foundObs, "Should have obs embeddings from encounter");
	}

	@Test
	public void firstSentence_shouldExtractTextBeforeFirstPeriodSpace() {
		assertEquals("Allergy: Beef (food allergen)",
				EmbeddingIndexer.firstSentence(
						"Allergy: Beef (food allergen). Severity: Severe. Reactions: Diarrhea"));
	}

	@Test
	public void firstSentence_shouldReturnFullTextWhenNoPeriodSpace() {
		assertEquals("Test — Hemoglobin: 12.4 g/dL (HIGH)",
				EmbeddingIndexer.firstSentence("Test — Hemoglobin: 12.4 g/dL (HIGH)"));
	}

	@Test
	public void firstSentence_shouldHandleNull() {
		assertEquals("", EmbeddingIndexer.firstSentence(null));
	}


	@Test
	public void indexEncounter_shouldUpsertExistingEmbeddings() {
		Encounter encounter = Context.getEncounterService().getEncounter(100);

		indexer.indexEncounter(encounter);
		Context.flushSession();
		int firstCount = dao.getByPatient(patient).size();

		indexer.indexEncounter(encounter);
		Context.flushSession();
		int secondCount = dao.getByPatient(patient).size();

		assertEquals(firstCount, secondCount,
				"Upsert should not create duplicate embeddings");
	}

	// --- Category-hint enrichment ---
	// Guards the production fix that injects concept-set names (e.g.
	// "Vital signs") into both the stored text_content and the embedding
	// input so category-name queries match. Without these tests, a
	// refactor that drops categoryHints from the SerializedRecord →
	// buildEmbeddings flow would silently regress production retrieval.

	private static final String MODEL_DIR = System.getProperty(
			"chartsearchai.embedding.model.dir", "../models/all-MiniLM-L6-v2");
	private static final String MODEL_PATH = MODEL_DIR + "/model.onnx";
	private static final String VOCAB_PATH = MODEL_DIR + "/vocab.txt";

	private static boolean modelFilesExist() {
		return new java.io.File(MODEL_PATH).exists()
				&& new java.io.File(VOCAB_PATH).exists();
	}

	@Test
	public void buildEmbeddings_withCategoryHints_shouldStoreHintInjectedTextContent() {
		Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		OnnxEmbeddingProvider provider =
				new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
		try {
			SerializedRecord recordWithHints = new SerializedRecord(
					"obs", "00000000-0000-0000-0000-000000000001",
					"Finding — Temperature: 36.7", new Date(),
					Arrays.asList("Vital signs"));

			List<ChartEmbedding> embeddings = EmbeddingIndexer.buildEmbeddings(
					Collections.singletonList(recordWithHints), provider);

			assertEquals(1, embeddings.size());
			assertEquals(
					"Vital signs / Finding — Temperature: 36.7",
					embeddings.get(0).getTextContent(),
					"text_content must include the hint-injected body so the "
					+ "keyword-scoring path sees the same enrichment as the "
					+ "embedding vector");
		} finally {
			provider.close();
		}
	}

	@Test
	public void buildEmbeddings_withEmptyHints_shouldStoreRawTextContent() {
		Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		OnnxEmbeddingProvider provider =
				new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
		try {
			SerializedRecord recordNoHints = new SerializedRecord(
					"obs", "00000000-0000-0000-0000-000000000001",
					"Finding — Temperature: 36.7", new Date());

			List<ChartEmbedding> embeddings = EmbeddingIndexer.buildEmbeddings(
					Collections.singletonList(recordNoHints), provider);

			assertEquals(1, embeddings.size());
			assertEquals("Finding — Temperature: 36.7",
					embeddings.get(0).getTextContent(),
					"text_content must equal the raw record text when "
					+ "no category hints are provided");
		} finally {
			provider.close();
		}
	}

	@Test
	public void buildEmbeddings_withCategoryHints_shouldShiftCosineForCategoryQuery() {
		// Empirical guard: hint enrichment must measurably improve
		// retrieval for category-name queries against the L6 model. With
		// "Vital signs" hint, cosine to query "vital signs" should jump
		// substantially (~0.30+ in ADR 19's measurements). This test is
		// conservative — only requires the enriched cosine to clearly
		// exceed the unenriched one.
		Assumptions.assumeTrue(modelFilesExist(),
				"Skipping: ONNX model not found at " + MODEL_PATH);

		OnnxEmbeddingProvider provider =
				new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
		try {
			String body = "Finding — Temperature: 36.7";
			SerializedRecord noHints = new SerializedRecord(
					"obs", "00000000-0000-0000-0000-000000000001", body, new Date());
			SerializedRecord withHints = new SerializedRecord(
					"obs", "00000000-0000-0000-0000-000000000002", body, new Date(),
					Arrays.asList("Vital signs"));

			List<ChartEmbedding> embeddings = EmbeddingIndexer.buildEmbeddings(
					Arrays.asList(noHints, withHints), provider);

			float[] queryEmb = provider.embed("vital signs");
			double cosWithoutHints = ChartSearchAiUtils.cosineSimilarity(
					queryEmb, embeddings.get(0).getEmbeddingVector());
			double cosWithHints = ChartSearchAiUtils.cosineSimilarity(
					queryEmb, embeddings.get(1).getEmbeddingVector());

			assertTrue(cosWithHints > cosWithoutHints + 0.10,
					"Hint enrichment must measurably raise cosine to category "
					+ "query (got " + cosWithHints + " enriched vs "
					+ cosWithoutHints + " unenriched)");
		} finally {
			provider.close();
		}
	}
}
