/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("chartSearchAi.auditLogService")
@Transactional
public class AuditLogServiceImpl implements AuditLogService {

	@Autowired
	private ChartSearchAiDAO dao;

	@Override
	public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
		return dao.saveAuditLog(auditLog);
	}

	@Override
	@Transactional(readOnly = true)
	public ChartSearchAuditLog getAuditLog(Integer auditLogId) {
		return dao.getAuditLog(auditLogId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user, Date fromDate, Date toDate,
			Integer startIndex, Integer limit) {
		return dao.getAuditLogs(patient, user, fromDate, toDate, startIndex, limit);
	}

	@Override
	@Transactional(readOnly = true)
	public Long getAuditLogCount(Patient patient, User user, Date fromDate, Date toDate) {
		return dao.getAuditLogCount(patient, user, fromDate, toDate);
	}

	@Override
	@Transactional(readOnly = true)
	public long getQueryCountByUserSince(User user, Date since) {
		return dao.getQueryCountByUserSince(user, since);
	}

	@Override
	public int deleteAuditLogsBefore(Date before) {
		return dao.deleteAuditLogsBefore(before);
	}
}
