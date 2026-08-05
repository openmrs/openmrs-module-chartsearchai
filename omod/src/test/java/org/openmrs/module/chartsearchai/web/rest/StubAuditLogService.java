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

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

/**
 * Audit-log stand-in for the controller tests in this package, which drive {@code streamAnswer}
 * with no Spring context and so must supply the collaborator themselves. Assigns an id on save
 * (the controller only emits {@code questionId} when one comes back) and returns empty/zero
 * everywhere else — nothing here decides an assertion; it exists so the code under test can run.
 *
 * <p>Shared rather than nested per test class for the same reason {@code CountingQueryStoreStub}
 * is: {@link AuditLogService} has six methods, and with a copy per test file a seventh would have
 * to be added in every copy, letting two files quietly pin different contracts. The rate-limit
 * path in particular reads {@link #getQueryCountByUserSince}, so the zero returned here is what
 * keeps {@code /search}-style flows from tripping the limiter.
 */
class StubAuditLogService implements AuditLogService {

	@Override
	public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
		auditLog.setAuditLogId(42);
		return auditLog;
	}

	@Override
	public ChartSearchAuditLog getAuditLog(Integer auditLogId) {
		return null;
	}

	@Override
	public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user, Date fromDate,
			Date toDate, Integer startIndex, Integer limit) {
		return Collections.emptyList();
	}

	@Override
	public Long getAuditLogCount(Patient patient, User user, Date fromDate, Date toDate) {
		return 0L;
	}

	@Override
	public long getQueryCountByUserSince(User user, Date since) {
		return 0L;
	}

	@Override
	public int deleteAuditLogsBefore(Date before) {
		return 0;
	}
}
