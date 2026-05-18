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
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
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

		AuditLogService service = Context.getRegisteredComponent(
				"chartSearchAi.auditLogService", AuditLogService.class);
		if (service == null) {
			log.error("AuditLogService not available, skipping audit log purge");
			return;
		}

		int deleted = service.deleteAuditLogsBefore(cutoffDate);
		log.info("Audit log purge completed: deleted {} entries older than {} days",
				deleted, retentionDays);

		// Chat content has its own (typically shorter) retention horizon — it's
		// clinical-utility, not regulatory audit-of-record. The audit row above
		// retains the question + answer text for HIPAA-required ~6y retention;
		// the chat tables drop the conversation thread so the FHIR PHI in free
		// text doesn't outlive its useful life.
		int chatRetentionDays = getChatRetentionDays();
		if (chatRetentionDays <= 0) {
			log.info("Chat history purge disabled (chat retention days is 0)");
			return;
		}
		ChatDAO chatDAO = Context.getRegisteredComponent(
				"chartSearchAi.chatDAO", ChatDAO.class);
		if (chatDAO == null) {
			// HibernateChatDAO is @Repository-annotated; absence here would mean
			// a wiring problem, not a degraded mode. Log and skip rather than
			// crash the scheduler.
			log.warn("ChatDAO not available, skipping chat history purge");
			return;
		}
		long chatCutoffMs = System.currentTimeMillis()
				- (chatRetentionDays * 24L * 60L * 60L * 1000L);
		int chatDeleted = chatDAO.purgeBefore(new Date(chatCutoffMs));
		log.info("Chat history purge completed: deleted {} rows older than {} days",
				chatDeleted, chatRetentionDays);
	}

	int getChatRetentionDays() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_CHAT_RETENTION_DAYS);
		return parseChatRetentionDays(value);
	}

	static int parseChatRetentionDays(String value) {
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Invalid chat history retention value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_CHAT_RETENTION_DAYS;
	}

	int getRetentionDays() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_AUDIT_LOG_RETENTION_DAYS);
		return parseRetentionDays(value);
	}

	/**
	 * Parses the retention days from the global property value. Returns the default
	 * if the value is null, empty, or not a valid integer.
	 */
	static int parseRetentionDays(String value) {
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
