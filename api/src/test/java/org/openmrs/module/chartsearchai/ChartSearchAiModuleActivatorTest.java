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
		// (EmbeddingIndexTask) no longer exists. Core's TaskDefinition validator now refuses to SAVE a
		// definition whose class cannot be loaded, so the leftover row is seeded the way it actually
		// comes to exist — as a plain DB row written by an old module version, which no validator ever
		// re-runs on. (saveTaskDefinition can no longer construct this precondition.)
		Context.getAdministrationService().executeSQL(
				"INSERT INTO scheduler_task_config (name, description, schedulable_class, "
						+ "repeat_interval, start_on_startup, started, created_by, date_created, uuid) "
						+ "VALUES ('" + LEGACY_BACKFILL_TASK_NAME + "', "
						+ "'Leftover backfill task from a pre-querystore version', "
						+ "'org.openmrs.module.chartsearchai.api.EmbeddingIndexTask', "
						+ "0, false, false, 1, '2020-01-01 00:00:00', "
						+ "'aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb')",
				false);
		Context.flushSession();
		Context.clearSession();
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
