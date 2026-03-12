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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
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
}
