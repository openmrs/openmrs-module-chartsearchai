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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Context-sensitive tests for {@link ChartSearchAiModuleActivator}. Covers two startup
 * responsibilities:
 * <ul>
 *   <li>idempotent provisioning of the chartsearchai privileges + admin-role bindings — the safety
 *       net for when a demo-data SQL dump (or any DB restore predating the module) wipes the
 *       privilege table, which would otherwise make the AI button silently disappear from the SPA;</li>
 *   <li>removal of the legacy "Embedding Backfill" scheduled task, whose task class no
 *       longer exists.</li>
 * </ul>
 */
public class ChartSearchAiModuleActivatorTest extends BaseModuleContextSensitiveTest {

	private static final String LEGACY_BACKFILL_TASK_NAME =
			ChartSearchAiModuleActivator.LEGACY_BACKFILL_TASK_NAME;

	private final ChartSearchAiModuleActivator activator = new ChartSearchAiModuleActivator();

	@Test
	public void provisionPrivilegesAndRoles_createsPrivilegesIfMissing() {
		UserService userService = Context.getUserService();
		// Strip any pre-existing state to simulate the wiped-by-demo-data scenario.
		Privilege existing = userService.getPrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		if (existing != null) {
			userService.purgePrivilege(existing);
		}
		Privilege existingAudit = userService.getPrivilege(ChartSearchAiConstants.PRIV_VIEW_AUDIT_LOGS);
		if (existingAudit != null) {
			userService.purgePrivilege(existingAudit);
		}

		activator.provisionPrivilegesAndRoles();

		Privilege query = userService.getPrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		assertNotNull(query, "AI Query Patient Data privilege must be re-created after wipe");
		Privilege audit = userService.getPrivilege(ChartSearchAiConstants.PRIV_VIEW_AUDIT_LOGS);
		assertNotNull(audit, "View AI Audit Logs privilege must be re-created after wipe");
	}

	@Test
	public void provisionPrivilegesAndRoles_bindsPrivilegeToSystemDeveloperRole() {
		// System Developer ships with the OpenMRS reference application; without
		// this binding, the SPA's userHasAccess() returns false for SD admins
		// even though backend Context.requirePrivilege() bypasses for SD.
		activator.provisionPrivilegesAndRoles();

		Role role = Context.getUserService().getRole("System Developer");
		assertNotNull(role, "expected System Developer role to exist in the reference application");
		assertTrue(role.hasPrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA),
				"System Developer must include AI Query Patient Data so the SPA gate passes for admin");
	}

	@Test
	public void provisionPrivilegesAndRoles_isIdempotent() {
		// Run twice; second call must not duplicate-key or throw.
		activator.provisionPrivilegesAndRoles();
		activator.provisionPrivilegesAndRoles();

		assertNotNull(Context.getUserService().getPrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA));
	}

	@Test
	public void removeLegacyBackfillTask_shouldDeleteLeftoverTaskAndBeIdempotent() {
		SchedulerService scheduler = Context.getSchedulerService();

		// Simulate an upgraded deployment: a pre-querystore version persisted this task, whose class
		// (EmbeddingIndexTask) no longer exists. Current Core refuses to save a task whose class cannot
		// be loaded, so seed the legacy row directly as it would exist after an old-module upgrade.
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
