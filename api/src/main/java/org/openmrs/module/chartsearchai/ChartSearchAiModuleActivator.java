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

import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexTask;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiModuleActivator extends BaseModuleActivator {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiModuleActivator.class);

	private static final String TASK_NAME = "Chart Search AI - Embedding Backfill";

	@Override
	public void started() {
		log.info("Chart Search AI Module started");
		validateConfiguration();
		registerBackfillTask();
	}

	@Override
	public void stopped() {
		log.info("Chart Search AI Module stopping");
		try {
			Context.getRegisteredComponent("llmProvider", LlmProvider.class).close();
		}
		catch (Exception e) {
			log.warn("Error closing LLM provider", e);
		}
		try {
			Context.getRegisteredComponent("chartSearchAi.onnxEmbeddingProvider",
					OnnxEmbeddingProvider.class).close();
		}
		catch (Exception e) {
			log.warn("Error closing ONNX embedding provider", e);
		}
		log.info("Chart Search AI Module stopped");
	}

	private void validateConfiguration() {
		String searchMode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (searchMode == null || searchMode.trim().isEmpty()) {
			searchMode = ChartSearchAiConstants.SEARCH_MODE_LLM;
		}

		validateModelFile(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH, "LLM");

		if (ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equals(searchMode)) {
			String embeddingProvider = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PROVIDER);
			if (ChartSearchAiConstants.EMBEDDING_PROVIDER_ONNX.equals(embeddingProvider)) {
				validateModelFile(ChartSearchAiConstants.GP_EMBEDDING_MODEL_FILE_PATH,
						"ONNX embedding");
			}
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
			String resolvedPath = ChartSearchAiConstants.resolveModelPath(
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
				+ "with existing patient data. Only runs when search mode is 'embedding'. "
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
}
