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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Context-sensitive tests for {@link ChartSearchAiModuleActivator}'s upgrade-cleanup behavior.
 */
public class ChartSearchAiModuleActivatorTest extends BaseModuleContextSensitiveTest {

	private static final String LEGACY_BACKFILL_TASK_NAME =
			ChartSearchAiModuleActivator.LEGACY_BACKFILL_TASK_NAME;

	@Test
	public void removeLegacyBackfillTask_shouldDeleteLeftoverTaskAndBeIdempotent() {
		SchedulerService scheduler = Context.getSchedulerService();
		ChartSearchAiModuleActivator activator = new ChartSearchAiModuleActivator();

		// Simulate an upgraded deployment: a pre-querystore version persisted this task, whose class
		// (EmbeddingIndexTask) no longer exists. saveTaskDefinition only persists the row — it does
		// not instantiate the class — so a now-deleted class name is fine to seed.
		TaskDefinition legacy = new TaskDefinition();
		legacy.setName(LEGACY_BACKFILL_TASK_NAME);
		legacy.setDescription("Leftover backfill task from a pre-querystore version");
		legacy.setTaskClass("org.openmrs.module.chartsearchai.api.EmbeddingIndexTask");
		legacy.setStartOnStartup(false);
		legacy.setRepeatInterval(0L);
		scheduler.saveTaskDefinition(legacy);
		assertNotNull(scheduler.getTaskByName(LEGACY_BACKFILL_TASK_NAME),
				"precondition: legacy task should be registered");

		activator.removeLegacyBackfillTask();
		assertNull(scheduler.getTaskByName(LEGACY_BACKFILL_TASK_NAME),
				"legacy backfill task should be deleted on startup");

		// Idempotent: on a fresh install (task absent) the call is a no-op, not an error.
		activator.removeLegacyBackfillTask();
		assertNull(scheduler.getTaskByName(LEGACY_BACKFILL_TASK_NAME));
	}
}
