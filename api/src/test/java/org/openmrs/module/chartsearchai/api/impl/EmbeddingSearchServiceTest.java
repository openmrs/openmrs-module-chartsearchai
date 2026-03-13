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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.embedding.TermFrequencyEmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

public class EmbeddingSearchServiceTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	private EmbeddingSearchService searchService;

	@Autowired
	private ChartSearchAiDAO dao;

	private Patient patient;

	private TermFrequencyEmbeddingProvider embeddingProvider;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
		patient = Context.getPatientService().getPatient(2);
		embeddingProvider = new TermFrequencyEmbeddingProvider();

		searchService = new EmbeddingSearchService();
		ReflectionTestUtils.setField(searchService, "embeddingProvider", embeddingProvider);
		ReflectionTestUtils.setField(searchService, "dao", dao);

		// Index some test records
		indexRecord("obs", 1001, "Systolic Blood Pressure 140 mmHg high");
		indexRecord("obs", 1002, "Weight 72.5 kg normal");
		indexRecord("condition", 2001, "Condition Type 2 Diabetes Mellitus active");
		indexRecord("allergy", 3001, "Allergy Penicillin drug severe rash");
		indexRecord("order", 4001, "Order Metformin new routine diabetes");
	}

	@Test
	public void findSimilar_shouldReturnResultsOrderedBySimilarity() {
		List<ChartEmbedding> results = searchService.findSimilar(patient, "blood pressure", 5);

		assertTrue(results.size() > 0);
		// The blood pressure record should rank highest
		assertEquals("obs", results.get(0).getResourceType());
		assertEquals(Integer.valueOf(1001), results.get(0).getResourceId());
	}

	@Test
	public void findSimilar_shouldRespectTopKLimit() {
		List<ChartEmbedding> results = searchService.findSimilar(patient, "patient records", 2);
		assertTrue(results.size() <= 2);
	}

	@Test
	public void findSimilar_shouldReturnEmptyForPatientWithNoEmbeddings() {
		dao.deleteByPatient(patient);
		Context.flushSession();

		List<ChartEmbedding> results = searchService.findSimilar(patient, "blood pressure", 5);
		assertTrue(results.isEmpty());
	}

	@Test
	public void findSimilar_shouldFindDiabetesRelatedRecords() {
		List<ChartEmbedding> results = searchService.findSimilar(patient, "diabetes", 3);
		assertTrue(results.size() > 0);

		// Diabetes condition or metformin order should rank high
		boolean foundDiabetesRelated = false;
		for (ChartEmbedding ce : results) {
			if (ce.getTextContent().toLowerCase().contains("diabetes")
					|| ce.getTextContent().toLowerCase().contains("metformin")) {
				foundDiabetesRelated = true;
				break;
			}
		}
		assertTrue(foundDiabetesRelated, "Should find diabetes-related records");
	}

	@Test
	public void findSimilar_shouldFindAllergyRecords() {
		List<ChartEmbedding> results = searchService.findSimilar(patient, "penicillin allergy", 3);
		assertTrue(results.size() > 0);

		boolean foundAllergy = false;
		for (ChartEmbedding ce : results) {
			if (ce.getTextContent().toLowerCase().contains("penicillin")) {
				foundAllergy = true;
				break;
			}
		}
		assertTrue(foundAllergy, "Should find penicillin allergy record");
	}

	private void indexRecord(String resourceType, Integer resourceId, String text) {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setPatient(patient);
		ce.setResourceType(resourceType);
		ce.setResourceId(resourceId);
		ce.setTextContent(text);
		ce.setEmbeddingVector(embeddingProvider.embed(text));
		ce.setDateCreated(new Date());
		dao.saveChartEmbedding(ce);
	}

}
