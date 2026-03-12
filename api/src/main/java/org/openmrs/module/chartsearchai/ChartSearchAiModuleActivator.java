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

import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexTask;
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
		registerBackfillTask();
	}

	@Override
	public void stopped() {
		log.info("Chart Search AI Module stopped");
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
