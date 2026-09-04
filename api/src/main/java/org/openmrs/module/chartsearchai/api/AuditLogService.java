/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

/**
 * Service for managing chart search audit log entries.
 */
public interface AuditLogService {

	ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog);

	ChartSearchAuditLog getAuditLog(Integer auditLogId);

	List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user, Date fromDate, Date toDate,
			Integer startIndex, Integer limit);

	Long getAuditLogCount(Patient patient, User user, Date fromDate, Date toDate);

	long getQueryCountByUserSince(User user, Date since);

	int deleteAuditLogsBefore(Date before);
}
