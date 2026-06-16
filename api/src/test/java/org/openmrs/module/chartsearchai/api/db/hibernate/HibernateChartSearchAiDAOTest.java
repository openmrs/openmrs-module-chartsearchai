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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
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
	public void getAuditLogs_shouldReturnAllLogsWhenNoFilters() {
		createAuditLog("What medications?", "Metformin [1]");
		createAuditLog("Any allergies?", "Penicillin allergy [2]");
		Context.flushSession();

		List<ChartSearchAuditLog> results = dao.getAuditLogs(null, null, null, null, 0, 50);
		assertEquals(2, results.size());
	}

	@Test
	public void getAuditLogs_shouldFilterByPatient() {
		createAuditLog("What medications?", "Metformin [1]");
		Context.flushSession();

		List<ChartSearchAuditLog> results = dao.getAuditLogs(patient, null, null, null, 0, 50);
		assertEquals(1, results.size());

		Patient otherPatient = Context.getPatientService().getPatient(6);
		List<ChartSearchAuditLog> empty = dao.getAuditLogs(otherPatient, null, null, null, 0, 50);
		assertTrue(empty.isEmpty());
	}

	@Test
	public void getAuditLogs_shouldFilterByUser() {
		User user = Context.getAuthenticatedUser();
		createAuditLog("What medications?", "Metformin [1]");
		Context.flushSession();

		List<ChartSearchAuditLog> results = dao.getAuditLogs(null, user, null, null, 0, 50);
		assertEquals(1, results.size());
	}

	@Test
	public void getAuditLogs_shouldFilterByDateRange() {
		createAuditLog("What medications?", "Metformin [1]");
		Context.flushSession();

		Date now = new Date();
		Date oneHourAgo = new Date(now.getTime() - 3600000);
		Date oneHourFromNow = new Date(now.getTime() + 3600000);

		List<ChartSearchAuditLog> results = dao.getAuditLogs(null, null, oneHourAgo, oneHourFromNow, 0, 50);
		assertEquals(1, results.size());

		Date tomorrow = new Date(now.getTime() + 86400000);
		Date dayAfter = new Date(now.getTime() + 172800000);
		List<ChartSearchAuditLog> empty = dao.getAuditLogs(null, null, tomorrow, dayAfter, 0, 50);
		assertTrue(empty.isEmpty());
	}

	@Test
	public void getAuditLogs_shouldRespectPagination() {
		for (int i = 0; i < 5; i++) {
			createAuditLog("Question " + i, "Answer " + i);
		}
		Context.flushSession();

		List<ChartSearchAuditLog> page1 = dao.getAuditLogs(null, null, null, null, 0, 2);
		assertEquals(2, page1.size());

		List<ChartSearchAuditLog> page2 = dao.getAuditLogs(null, null, null, null, 2, 2);
		assertEquals(2, page2.size());

		List<ChartSearchAuditLog> page3 = dao.getAuditLogs(null, null, null, null, 4, 2);
		assertEquals(1, page3.size());
	}

	@Test
	public void deleteAuditLogsBefore_shouldDeleteOldEntries() {
		// Create an old audit log (100 days ago)
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.add(java.util.Calendar.DAY_OF_MONTH, -100);
		ChartSearchAuditLog oldLog = new ChartSearchAuditLog();
		oldLog.setUser(Context.getAuthenticatedUser());
		oldLog.setPatient(patient);
		oldLog.setQuestion("old question");
		oldLog.setAnswer("old answer");
		oldLog.setReferenceCount(0);
		oldLog.setSearchMode("llm");
		oldLog.setResponseTimeMs(100L);
		oldLog.setDateCreated(cal.getTime());
		dao.saveAuditLog(oldLog);

		// Create a recent audit log
		createAuditLog("recent question", "recent answer");
		Context.flushSession();

		// Delete entries older than 90 days
		cal = java.util.Calendar.getInstance();
		cal.add(java.util.Calendar.DAY_OF_MONTH, -90);
		int deleted = dao.deleteAuditLogsBefore(cal.getTime());

		assertEquals(1, deleted);

		Context.flushSession();
		Context.clearSession();

		Long remaining = dao.getAuditLogCount(null, null, null, null);
		assertEquals(Long.valueOf(1), remaining);
	}

	@Test
	public void deleteAuditLogsBefore_shouldReturnZeroWhenNothingToDelete() {
		createAuditLog("recent question", "recent answer");
		Context.flushSession();

		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.add(java.util.Calendar.DAY_OF_MONTH, -90);
		int deleted = dao.deleteAuditLogsBefore(cal.getTime());

		assertEquals(0, deleted);
	}

	@Test
	public void getAuditLogCount_shouldReturnTotalMatchingCount() {
		for (int i = 0; i < 3; i++) {
			createAuditLog("Question " + i, "Answer " + i);
		}
		Context.flushSession();

		Long count = dao.getAuditLogCount(null, null, null, null);
		assertEquals(Long.valueOf(3), count);

		Long patientCount = dao.getAuditLogCount(patient, null, null, null);
		assertEquals(Long.valueOf(3), patientCount);

		Patient otherPatient = Context.getPatientService().getPatient(6);
		Long emptyCount = dao.getAuditLogCount(otherPatient, null, null, null);
		assertEquals(Long.valueOf(0), emptyCount);
	}

	private void createAuditLog(String question, String answer) {
		ChartSearchAuditLog log = new ChartSearchAuditLog();
		log.setUser(Context.getAuthenticatedUser());
		log.setPatient(patient);
		log.setQuestion(question);
		log.setAnswer(answer);
		log.setReferenceCount(1);
		log.setSearchMode("llm");
		log.setResponseTimeMs(500L);
		log.setDateCreated(new Date());
		dao.saveAuditLog(log);
	}

	@Test
	public void getQueryCountByUserSince_shouldCountRecentQueries() {
		User user = Context.getAuthenticatedUser();
		createAuditLog("Question 1", "Answer 1");
		createAuditLog("Question 2", "Answer 2");
		Context.flushSession();

		Date oneHourAgo = new Date(System.currentTimeMillis() - 3600000);
		long count = dao.getQueryCountByUserSince(user, oneHourAgo);
		assertEquals(2, count);
	}

	@Test
	public void getQueryCountByUserSince_shouldReturnZeroWhenNoRecentQueries() {
		User user = Context.getAuthenticatedUser();
		createAuditLog("Question 1", "Answer 1");
		Context.flushSession();

		Date inTheFuture = new Date(System.currentTimeMillis() + 3600000);
		long count = dao.getQueryCountByUserSince(user, inTheFuture);
		assertEquals(0, count);
	}
}
