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

import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class AuditLogPurgeTaskTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	@Autowired
	private ChartSearchAiDAO dao;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
	}

	@Test
	public void execute_shouldDeleteOldAuditLogs() {
		Patient patient = Context.getPatientService().getPatient(2);
		User user = Context.getUserService().getUser(1);

		// Create an audit log from 100 days ago
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.DAY_OF_MONTH, -100);
		Date oldDate = cal.getTime();

		ChartSearchAuditLog oldLog = new ChartSearchAuditLog();
		oldLog.setPatient(patient);
		oldLog.setUser(user);
		oldLog.setQuestion("old question");
		oldLog.setAnswer("old answer");
		oldLog.setReferenceCount(0);
		oldLog.setSearchMode("llm");
		oldLog.setResponseTimeMs(100L);
		oldLog.setDateCreated(oldDate);
		dao.saveAuditLog(oldLog);

		// Create a recent audit log
		ChartSearchAuditLog recentLog = new ChartSearchAuditLog();
		recentLog.setPatient(patient);
		recentLog.setUser(user);
		recentLog.setQuestion("recent question");
		recentLog.setAnswer("recent answer");
		recentLog.setReferenceCount(0);
		recentLog.setSearchMode("llm");
		recentLog.setResponseTimeMs(100L);
		recentLog.setDateCreated(new Date());
		dao.saveAuditLog(recentLog);

		Context.flushSession();

		// Set retention to 90 days
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_AUDIT_LOG_RETENTION_DAYS, "90");

		AuditLogPurgeTask task = new AuditLogPurgeTask();
		task.execute();

		Context.flushSession();
		Context.clearSession();

		// Only the recent log should remain
		Long count = dao.getAuditLogCount(null, null, null, null);
		assertEquals(Long.valueOf(1), count);
	}

	@Test
	public void execute_shouldNotDeleteWhenRetentionIsZero() {
		Patient patient = Context.getPatientService().getPatient(2);
		User user = Context.getUserService().getUser(1);

		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.DAY_OF_MONTH, -200);

		ChartSearchAuditLog oldLog = new ChartSearchAuditLog();
		oldLog.setPatient(patient);
		oldLog.setUser(user);
		oldLog.setQuestion("old question");
		oldLog.setAnswer("old answer");
		oldLog.setReferenceCount(0);
		oldLog.setSearchMode("llm");
		oldLog.setResponseTimeMs(100L);
		oldLog.setDateCreated(cal.getTime());
		dao.saveAuditLog(oldLog);

		Context.flushSession();

		// Disable purging
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_AUDIT_LOG_RETENTION_DAYS, "0");

		AuditLogPurgeTask task = new AuditLogPurgeTask();
		task.execute();

		Context.flushSession();
		Context.clearSession();

		Long count = dao.getAuditLogCount(null, null, null, null);
		assertEquals(Long.valueOf(1), count);
	}
}
