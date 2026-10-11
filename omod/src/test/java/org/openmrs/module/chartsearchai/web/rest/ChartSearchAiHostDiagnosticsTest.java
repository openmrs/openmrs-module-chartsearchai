/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.UserContext;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Privilege gate and wire shape of {@code GET /chartsearchai/hostdiagnostics}. The body itself is
 * {@code HostDiagnostics.read}, pinned field by field in {@code HostDiagnosticsTest}; what only this
 * test can see is that the controller serves that body, and only to a caller holding core's
 * {@code View Administration Functions} — the privilege core keeps the server's absolute layout behind,
 * which the {@code llamaServer} section's process details belong with.
 */
public class ChartSearchAiHostDiagnosticsTest {

	@AfterEach
	public void restoreContext() {
		Context.clearUserContext();
	}

	@Test
	public void hostDiagnostics_servesTheHostReadingToAnAdministrator() {
		grantOnly(PrivilegeConstants.VIEW_ADMIN_FUNCTIONS);

		ResponseEntity<Object> response = new ChartSearchAiRestController().hostDiagnostics();

		assertEquals(HttpStatus.OK, response.getStatusCode());
		Map<?, ?> body = (Map<?, ?>) response.getBody();
		assertNotNull(body);
		assertEquals(Arrays.asList("sampledAtMillis", "jvmAvailableProcessors", "loadAverage", "cpuJiffies",
				"memoryKb", "cgroupCpu", "llamaServer"), new ArrayList<Object>(body.keySet()));
	}

	/**
	 * {@code Get Global Properties}, which {@code /drugreferencestatus} accepts, sits on the
	 * {@code Authenticated} role of a stock install; it must not be enough here. With the test above,
	 * which grants {@code View Administration Functions} and nothing else, this pins which privilege the
	 * gate asks for.
	 */
	@Test
	public void hostDiagnostics_refusesACallerWithoutViewAdministrationFunctions() {
		grantOnly(PrivilegeConstants.GET_GLOBAL_PROPERTIES);

		assertThrows(ContextAuthenticationException.class, () -> new ChartSearchAiRestController().hostDiagnostics());
	}

	private static void grantOnly(final String privilege) {
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String requested) {
				return privilege.equals(requested);
			}
		});
	}
}
