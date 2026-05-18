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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Verifies that the activator idempotently provisions the chartsearchai
 * privileges and binds them to admin roles. This is the safety net for the
 * scenario where a demo-data SQL dump (or any DB restore that predates the
 * module) wipes the privilege table — without this fix, the AI button
 * silently disappears from the SPA because the SPA's privilege gate has no
 * privilege to find.
 */
public class ChartSearchAiModuleActivatorTest extends BaseModuleContextSensitiveTest {

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
}
