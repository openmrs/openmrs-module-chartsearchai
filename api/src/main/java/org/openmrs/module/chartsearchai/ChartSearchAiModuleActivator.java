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

import java.util.Arrays;
import java.util.List;

import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.chartsearchai.api.AuditLogPurgeTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchAiModuleActivator extends BaseModuleActivator {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiModuleActivator.class);

	private static final String PURGE_TASK_NAME = "Chart Search AI - Audit Log Purge";

	/** Name of the scheduled task registered by pre-querystore versions, whose task class
	 *  ({@code EmbeddingIndexTask}) no longer exists. Removed on startup so upgraded deployments do
	 *  not retain a TaskDefinition pointing at a deleted class. Package-private so the activator's
	 *  test references the same literal. */
	static final String LEGACY_BACKFILL_TASK_NAME = "Chart Search AI - Embedding Backfill";

	private static final long DAILY_INTERVAL_SECONDS = 86400L;

	@Override
	public void started() {
		log.info("Chart Search AI Module started");
		provisionPrivilegesAndRoles();
		removeLegacyBackfillTask();
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

	/**
	 * Removes the "Chart Search AI - Embedding Backfill" scheduled task left behind by
	 * pre-querystore versions. Its task class ({@code EmbeddingIndexTask}) was deleted when
	 * chartsearchai stopped maintaining its own embedding store, so an upgraded deployment would
	 * otherwise keep a {@link TaskDefinition} pointing at a class that no longer loads.
	 *
	 * <p>Idempotent and best-effort: a no-op on fresh installs, and if the scheduler cannot remove
	 * the task (e.g. a JobRunr job left stuck in a {@code DELETED} state), it logs a WARN and leaves
	 * the harmless task in place rather than failing module startup.
	 */
	void removeLegacyBackfillTask() {
		SchedulerService schedulerService = Context.getSchedulerService();
		TaskDefinition existing = schedulerService.getTaskByName(LEGACY_BACKFILL_TASK_NAME);
		if (existing == null) {
			return;
		}
		try {
			// deleteTask both stops the task and removes its definition. Do NOT call shutdownTask
			// first: on the platform-2.9 JobRunr scheduler, deleteTask internally shuts the task
			// down, so a prior shutdownTask leaves the underlying job already DELETED and the
			// internal shutdown then throws IllegalJobStateChangeException (DELETED -> DELETED),
			// aborting the delete and leaving the legacy task behind.
			schedulerService.deleteTask(existing.getId());
			log.info("Removed legacy embedding backfill task (its EmbeddingIndexTask class no longer exists)");
		}
		catch (Exception e) {
			// Non-fatal: the leftover task is harmless (it cannot run — its class is gone), so a
			// failed auto-removal must not noise up startup with an ERROR/stack. WARN with an
			// actionable next step. (Platform-2.9's JobRunr scheduler can leave a job stuck in a
			// DELETED state that deleteTask cannot re-delete; the task can be removed by hand.)
			log.warn("Could not auto-remove the legacy '{}' scheduled task (its EmbeddingIndexTask "
					+ "class no longer exists). It is harmless and cannot run; delete it manually from "
					+ "Manage Scheduler if desired. Cause: {}", LEGACY_BACKFILL_TASK_NAME, e.toString());
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
