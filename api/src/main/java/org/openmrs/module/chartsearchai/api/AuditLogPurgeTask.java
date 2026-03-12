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

import java.util.Date;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled task that purges audit log entries older than the configured retention
 * period. Runs on a configurable schedule (default: daily) to prevent unbounded
 * growth of the {@code chartsearchai_audit_log} table.
 *
 * <p>Retention period is controlled by the {@code chartsearchai.auditLogRetentionDays}
 * global property (default 90 days). Set to 0 to disable purging and retain all logs.</p>
 */
public class AuditLogPurgeTask extends AbstractTask {

	private static final Logger log = LoggerFactory.getLogger(AuditLogPurgeTask.class);

	@Override
	public void execute() {
		int retentionDays = getRetentionDays();
		if (retentionDays <= 0) {
			log.info("Audit log purge disabled (retention days is 0)");
			return;
		}

		long cutoffMs = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
		Date cutoffDate = new Date(cutoffMs);

		ChartSearchAiDAO dao = Context.getRegisteredComponent(
				"hibernateChartSearchAiDAO", ChartSearchAiDAO.class);

		int deleted = dao.deleteAuditLogsBefore(cutoffDate);
		log.info("Audit log purge completed: deleted {} entries older than {} days",
				deleted, retentionDays);
	}

	private int getRetentionDays() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_AUDIT_LOG_RETENTION_DAYS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Invalid audit log retention value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_AUDIT_LOG_RETENTION_DAYS;
	}
}
