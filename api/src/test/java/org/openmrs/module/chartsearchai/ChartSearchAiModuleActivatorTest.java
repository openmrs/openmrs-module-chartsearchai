/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.Level;
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

	/**
	 * Issue #315. A custom system prompt REPLACES the built-in one, so a deployment that set the
	 * property before this rule existed silently keeps the defect the rule fixes.
	 *
	 * <p>The reason this is a LOG line and not left to the property's own description: OpenMRS writes
	 * a module's {@code config.xml} description onto a {@code global_property} row only when creating
	 * it, or when it exists with a NULL description ({@code Context.checkCoreDataset}). The row is
	 * created by this module's own first startup with the description from {@code config.xml}, so
	 * every install that has ever run chartsearchai has a non-null description and never receives a
	 * revised one. The description reaches only installs that have never run the module, which cannot
	 * have the problem.
	 */
	@Test
	public void aCustomSystemPromptIsWarnedAboutAtStartupBecauseItDropsTheBuiltInRules() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SYSTEM_PROMPT, "You are a clinical assistant. Be brief.");
		try (LogCapture capture = LogCapture.on(ChartSearchAiModuleActivator.class.getName())) {
			new ChartSearchAiModuleActivator().started();

			List<String> warnings = capture.messagesAt(Level.WARN);
			boolean named = false;
			for (String message : warnings) {
				if (message.contains(ChartSearchAiConstants.GP_SYSTEM_PROMPT)
						&& message.contains("no longer in force")) {
					named = true;
				}
			}
			assertTrue(named, "startup must WARN that a custom system prompt drops the built-in rules, "
					+ "naming the property and the #315 rule it drops — the property's own description "
					+ "cannot reach an install that already has the row. Warnings were: " + warnings);
		}
	}

	/**
	 * The converse, and the half that matters for noise: the default install sets no custom prompt and
	 * must say nothing. A warning on every startup of every deployment is the noise this module's own
	 * loudness rule is written against.
	 */
	@Test
	public void theDefaultInstallIsSilentAboutTheSystemPrompt() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_SYSTEM_PROMPT, "");
		try (LogCapture capture = LogCapture.on(ChartSearchAiModuleActivator.class.getName())) {
			new ChartSearchAiModuleActivator().started();

			for (String message : capture.messagesAt(Level.WARN)) {
				assertFalse(message.contains(ChartSearchAiConstants.GP_SYSTEM_PROMPT),
						"an install that has not overridden the prompt must not be warned about it: "
								+ message);
			}
		}
	}

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
