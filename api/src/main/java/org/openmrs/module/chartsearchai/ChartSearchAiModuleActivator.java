/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.DaemonTokenAware;
import org.openmrs.module.chartsearchai.api.AuditLogPurgeTask;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexTask;
import org.openmrs.module.chartsearchai.api.ElasticsearchIndexer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider;
import org.openmrs.module.chartsearchai.api.impl.WarmupExecutor;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiModuleActivator extends BaseModuleActivator implements DaemonTokenAware {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiModuleActivator.class);

	private static final String TASK_NAME = "Chart Search AI - Embedding Backfill";

	private static final String PURGE_TASK_NAME = "Chart Search AI - Audit Log Purge";

	private static final long DAILY_INTERVAL_SECONDS = 86400L;

	@Override
	public void setDaemonToken(DaemonToken token) {
		try {
			Context.getRegisteredComponent("chartSearchAi.warmupExecutor", WarmupExecutor.class)
					.setDaemonToken(token);
		}
		catch (APIException e) {
			log.warn("Could not propagate DaemonToken to WarmupExecutor", e);
		}
	}

	@Override
	public void started() {
		log.info("Chart Search AI Module started");
		validateConfiguration();
		provisionPrivilegesAndRoles();
		registerBackfillTask();
		registerAuditLogPurgeTask();
	}

	/**
	 * Standard OpenMRS auto-creates module-declared privileges from
	 * {@code config.xml} only on initial install. If the {@code privilege}
	 * table is later wiped (e.g. by reseeding from a SQL dump that predates
	 * the module, or any DB restore that doesn't carry module metadata),
	 * subsequent module restarts will NOT re-create those privileges — and
	 * gated extensions disappear from the SPA. Run on every startup so the
	 * privilege set is the source of truth and resilient to DB resets.
	 *
	 * <p>Also binds the privileges to the standard admin roles. Without an
	 * explicit binding, {@code System Developer} (a super-role with backend
	 * bypass) does not enumerate them in the REST {@code /session} response,
	 * which means {@code userHasAccess()} in the SPA returns false and the
	 * AI button never renders.
	 */
	void provisionPrivilegesAndRoles() {
		UserService userService;
		try {
			userService = Context.getUserService();
		}
		catch (Exception e) {
			log.warn("UserService unavailable; skipping privilege provisioning", e);
			return;
		}

		List<String[]> privileges = Arrays.asList(
				new String[] { ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA,
						"Allows querying patient charts using the AI-powered chart search" },
				new String[] { ChartSearchAiConstants.PRIV_VIEW_AUDIT_LOGS,
						"Allows viewing the audit log of AI chart search queries" });

		for (String[] entry : privileges) {
			ensurePrivilege(userService, entry[0], entry[1]);
		}

		List<String> adminRoles = Arrays.asList("System Developer", "Privilege Level: Full",
				"Organizational: System Administrator");
		for (String roleName : adminRoles) {
			for (String[] entry : privileges) {
				bindPrivilegeToRole(userService, roleName, entry[0]);
			}
		}
	}

	private void ensurePrivilege(UserService userService, String name, String description) {
		try {
			Privilege existing = userService.getPrivilege(name);
			if (existing != null) {
				return;
			}
			Privilege priv = new Privilege(name, description);
			userService.savePrivilege(priv);
			log.info("Provisioned privilege '{}'", name);
		}
		catch (Exception e) {
			log.warn("Failed to provision privilege '{}'", name, e);
		}
	}

	private void bindPrivilegeToRole(UserService userService, String roleName, String privilege) {
		try {
			Role role = userService.getRole(roleName);
			if (role == null) {
				// Role doesn't exist on this distro (e.g. fresh OpenMRS without the
				// reference roles); skip silently — the priv still exists for any
				// site-defined role to pick up.
				return;
			}
			if (role.hasPrivilege(privilege)) {
				return;
			}
			Privilege priv = userService.getPrivilege(privilege);
			if (priv == null) {
				return;
			}
			role.addPrivilege(priv);
			userService.saveRole(role);
			log.info("Bound privilege '{}' to role '{}'", privilege, roleName);
		}
		catch (Exception e) {
			log.warn("Failed to bind privilege '{}' to role '{}'", privilege, roleName, e);
		}
	}

	@Override
	public void stopped() {
		log.info("Chart Search AI Module stopping");
		try {
			LlmProvider llmProvider = Context.getRegisteredComponent("llmProvider", LlmProvider.class);
			if (llmProvider != null) {
				llmProvider.shutdown();
			}
		}
		catch (Exception e) {
			log.warn("Error closing LLM provider", e);
		}
		try {
			OnnxEmbeddingProvider embeddingProvider = Context.getRegisteredComponent(
					"chartSearchAi.embeddingProvider", OnnxEmbeddingProvider.class);
			if (embeddingProvider != null) {
				embeddingProvider.close();
			}
		}
		catch (Exception e) {
			log.warn("Error closing ONNX embedding provider", e);
		}
		try {
			ElasticsearchIndexer esIndexer = Context.getRegisteredComponent(
					"elasticsearchIndexer", ElasticsearchIndexer.class);
			if (esIndexer != null) {
				esIndexer.close();
			}
		}
		catch (Exception e) {
			log.warn("Error closing Elasticsearch indexer", e);
		}
		try {
			org.openmrs.module.chartsearchai.api.LuceneIndexer luceneIndexer =
					Context.getRegisteredComponent("luceneIndexer",
							org.openmrs.module.chartsearchai.api.LuceneIndexer.class);
			if (luceneIndexer != null) {
				luceneIndexer.close();
			}
		}
		catch (Exception e) {
			log.warn("Error closing Lucene indexer", e);
		}
		log.info("Chart Search AI Module stopped");
	}

	private void validateConfiguration() {
		String engineType = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE);
		boolean isRemote = ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(
				engineType != null ? engineType.trim() : "");

		if (!isRemote) {
			validateModelFile(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH, "LLM");
		}

		String preFilter = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		if (!"false".equalsIgnoreCase(preFilter.trim())) {
			validateModelFile(ChartSearchAiConstants.GP_EMBEDDING_MODEL_FILE_PATH,
					"ONNX embedding");
			validateModelFile(ChartSearchAiConstants.GP_EMBEDDING_VOCAB_FILE_PATH,
					"WordPiece vocabulary");
		}

	}

	private void validateModelFile(String globalProperty, String label) {
		String configuredPath = Context.getAdministrationService()
				.getGlobalProperty(globalProperty);
		if (configuredPath == null || configuredPath.trim().isEmpty()) {
			log.warn("Chart Search AI: {} model path not configured. "
					+ "Set '{}' before using the module.", label, globalProperty);
			return;
		}

		try {
			String resolvedPath = ChartSearchAiUtils.resolveModelPath(
					configuredPath.trim(), globalProperty);
			File modelFile = new File(resolvedPath);
			if (!modelFile.canRead()) {
				log.warn("Chart Search AI: {} model file is not readable: {}",
						label, resolvedPath);
			} else {
				log.info("Chart Search AI: {} model file validated: {}", label, resolvedPath);
			}
		}
		catch (IllegalStateException e) {
			log.warn("Chart Search AI: {} model file validation failed: {}",
					label, e.getMessage());
		}
	}

	private void registerBackfillTask() {
		SchedulerService schedulerService = Context.getSchedulerService();

		TaskDefinition existing = schedulerService.getTaskByName(TASK_NAME);
		if (existing != null) {
			log.debug("Embedding backfill task already registered");
			return;
		}

		TaskDefinition task = new TaskDefinition();
		task.setName(TASK_NAME);
		task.setDescription("Indexes patients that do not yet have embeddings. "
				+ "Handles initial population when the module is installed on a system "
				+ "with existing patient data. Only runs when embedding pre-filter is enabled. "
				+ "Can be disabled from the scheduler UI once backfill is complete.");
		task.setTaskClass(EmbeddingIndexTask.class.getName());
		task.setRepeatInterval(0L);
		task.setStartOnStartup(false);

		try {
			schedulerService.saveTaskDefinition(task);
			log.info("Registered embedding backfill task");
		}
		catch (Exception e) {
			log.error("Failed to register embedding backfill task", e);
		}
	}

	private void registerAuditLogPurgeTask() {
		SchedulerService schedulerService = Context.getSchedulerService();

		TaskDefinition existing = schedulerService.getTaskByName(PURGE_TASK_NAME);
		if (existing != null) {
			log.debug("Audit log purge task already registered");
			return;
		}

		TaskDefinition task = new TaskDefinition();
		task.setName(PURGE_TASK_NAME);
		task.setDescription("Deletes AI chart search audit log entries older than the "
				+ "configured retention period (chartsearchai.auditLogRetentionDays, "
				+ "default 90 days). Set retention to 0 to disable purging.");
		task.setTaskClass(AuditLogPurgeTask.class.getName());
		task.setRepeatInterval(DAILY_INTERVAL_SECONDS);
		task.setStartOnStartup(true);

		try {
			schedulerService.saveTaskDefinition(task);
			log.info("Registered audit log purge task");
		}
		catch (Exception e) {
			log.error("Failed to register audit log purge task", e);
		}
	}
}
