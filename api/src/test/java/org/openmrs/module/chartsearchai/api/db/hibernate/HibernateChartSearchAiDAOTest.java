/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.db.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class HibernateChartSearchAiDAOTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	@Autowired
	private ChartSearchAiDAO dao;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
		patient = Context.getPatientService().getPatient(2);
	}

	@Test
	public void saveChartEmbedding_shouldSaveAndReturnEmbedding() {
		ChartEmbedding ce = createEmbedding("obs", 1001, "Test text");
		ChartEmbedding saved = dao.saveChartEmbedding(ce);

		assertNotNull(saved.getEmbeddingId());
		assertEquals("obs", saved.getResourceType());
		assertEquals(Integer.valueOf(1001), saved.getResourceId());
		assertEquals("Test text", saved.getTextContent());
	}

	@Test
	public void getByResource_shouldReturnEmbeddingByTypeAndId() {
		dao.saveChartEmbedding(createEmbedding("obs", 1001, "Blood pressure"));
		Context.flushSession();

		ChartEmbedding result = dao.getByResource("obs", 1001);
		assertNotNull(result);
		assertEquals("Blood pressure", result.getTextContent());
	}

	@Test
	public void getByResource_shouldReturnNullForNonexistent() {
		assertNull(dao.getByResource("obs", 9999));
	}

	@Test
	public void getByPatient_shouldReturnAllEmbeddingsForPatient() {
		dao.saveChartEmbedding(createEmbedding("obs", 1001, "BP reading"));
		dao.saveChartEmbedding(createEmbedding("obs", 1002, "Weight reading"));
		Context.flushSession();

		List<ChartEmbedding> results = dao.getByPatient(patient);
		assertEquals(2, results.size());
	}

	@Test
	public void getByPatient_shouldReturnEmptyForPatientWithNoEmbeddings() {
		List<ChartEmbedding> results = dao.getByPatient(patient);
		assertTrue(results.isEmpty());
	}

	@Test
	public void deleteByPatient_shouldRemoveAllEmbeddingsForPatient() {
		dao.saveChartEmbedding(createEmbedding("obs", 1001, "BP reading"));
		dao.saveChartEmbedding(createEmbedding("obs", 1002, "Weight reading"));
		Context.flushSession();

		assertEquals(2, dao.getByPatient(patient).size());

		dao.deleteByPatient(patient);
		Context.flushSession();

		assertTrue(dao.getByPatient(patient).isEmpty());
	}

	@Test
	public void saveAuditLog_shouldPersistAuditRecord() {
		User user = Context.getAuthenticatedUser();

		ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
		auditLog.setUser(user);
		auditLog.setPatient(patient);
		auditLog.setQuestion("What medications?");
		auditLog.setAnswer("The patient is on Metformin [1]");
		auditLog.setReferenceCount(1);
		auditLog.setSearchMode("llm");
		auditLog.setResponseTimeMs(1500L);
		auditLog.setDateCreated(new Date());

		ChartSearchAuditLog saved = dao.saveAuditLog(auditLog);
		assertNotNull(saved.getAuditLogId());
	}

	@Test
	public void embeddingVector_shouldRoundTripCorrectly() {
		float[] original = { 0.1f, 0.2f, 0.3f, -0.5f };
		ChartEmbedding ce = createEmbedding("obs", 1001, "Test");
		ce.setEmbeddingVector(original);
		dao.saveChartEmbedding(ce);
		Context.flushSession();
		Context.clearSession();

		ChartEmbedding loaded = dao.getByResource("obs", 1001);
		float[] loaded_vector = loaded.getEmbeddingVector();

		assertEquals(original.length, loaded_vector.length);
		for (int i = 0; i < original.length; i++) {
			assertEquals(original[i], loaded_vector[i], 0.0001f);
		}
	}

	private ChartEmbedding createEmbedding(String resourceType, Integer resourceId, String text) {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setPatient(patient);
		ce.setResourceType(resourceType);
		ce.setResourceId(resourceId);
		ce.setTextContent(text);
		ce.setEmbeddingVector(new float[] { 0.1f, 0.2f, 0.3f });
		ce.setDateCreated(new Date());
		return ce;
	}
}
