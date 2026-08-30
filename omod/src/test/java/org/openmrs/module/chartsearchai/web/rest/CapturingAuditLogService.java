/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

/**
 * {@link StubAuditLogService} that RETAINS what the controller wrote, for the test classes whose
 * assertions are about the audit row itself.
 *
 * <p>Shared rather than nested per test class, for the reason {@code StubAuditLogService}'s own
 * javadoc gives about itself and {@code RestControllerContext}'s repeats: {@code AuditLogService} has
 * six methods, and with a copy per test file a seventh would have to be added in every copy, letting
 * two files quietly pin different answers to the same question. It had grown to two copies — one
 * capturing saves only, one additionally serving a listing back — which is exactly the drift those
 * two javadocs were written against.
 *
 * <p>{@link #saved} is what {@code saveAuditLog} was called with, in order. {@link #listed} is what
 * the listing endpoint is served; it is empty by default, so a class that only cares about saves gets
 * the base class's behaviour unchanged.
 */
class CapturingAuditLogService extends StubAuditLogService {

	final List<ChartSearchAuditLog> saved = new ArrayList<ChartSearchAuditLog>();

	final List<ChartSearchAuditLog> listed = new ArrayList<ChartSearchAuditLog>();

	@Override
	public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
		saved.add(auditLog);
		return super.saveAuditLog(auditLog);
	}

	@Override
	public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user, Date fromDate,
			Date toDate, Integer startIndex, Integer limit) {
		return listed;
	}

	@Override
	public Long getAuditLogCount(Patient patient, User user, Date fromDate, Date toDate) {
		return Long.valueOf(listed.size());
	}
}
