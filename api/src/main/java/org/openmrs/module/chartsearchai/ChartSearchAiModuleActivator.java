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

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.DaemonTokenAware;
import org.openmrs.module.chartsearchai.api.AuditLogPurgeTask;
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

	private static final String PURGE_TASK_NAME = "Chart Search AI - Audit Log Purge";

	/** Name of the scheduled task registered by pre-querystore versions, whose task class
	 *  ({@code EmbeddingIndexTask}) no longer exists. Removed on startup so upgraded deployments do
	 *  not retain a TaskDefinition pointing at a deleted class. Package-private so the activator's
	 *  test references the same literal. */
	static final String LEGACY_BACKFILL_TASK_NAME = "Chart Search AI - Embedding Backfill";

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
		removeLegacyBackfillTask();
		registerAuditLogPurgeTask();
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

	/**
	 * Removes the "Chart Search AI - Embedding Backfill" scheduled task left behind by
	 * pre-querystore versions. Its task class ({@code EmbeddingIndexTask}) was deleted when
	 * chartsearchai stopped maintaining its own embedding store, so an upgraded deployment would
	 * otherwise keep a {@link TaskDefinition} pointing at a class that no longer loads. Idempotent:
	 * a no-op on fresh installs.
	 */
	void removeLegacyBackfillTask() {
		SchedulerService schedulerService = Context.getSchedulerService();
		TaskDefinition existing = schedulerService.getTaskByName(LEGACY_BACKFILL_TASK_NAME);
		if (existing == null) {
			return;
		}
		try {
			schedulerService.shutdownTask(existing);
		}
		catch (Exception e) {
			// A non-running task (the backfill never auto-started) can throw here; deletion below
			// still proceeds. Logged at debug because it is expected on the common path.
			log.debug("Legacy backfill task was not running at shutdown", e);
		}
		try {
			schedulerService.deleteTask(existing.getId());
			log.info("Removed legacy embedding backfill task (its EmbeddingIndexTask class no longer exists)");
		}
		catch (Exception e) {
			log.error("Failed to delete legacy embedding backfill task", e);
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
