/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class HubProfileServiceTest extends BaseModuleContextSensitiveTest {

	@Test
	public void modelsUriDerivesFromTheOneConfiguredHubChatEndpoint() {
		assertEquals("http://hub:8080/v1/models",
				HubProfileService.modelsUri("http://hub:8080/v1/chat/completions").toString());
		assertThrows(IllegalArgumentException.class,
				() -> HubProfileService.modelsUri("http://hub:8080/other"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void listProfilesRelaysHubMetadataWithoutCuratingIt() throws Exception {
		String response = "{\"object\":\"list\",\"data\":[{"
				+ "\"id\":\"single-e4b-checked\",\"label\":\"Fast checked E4B\","
				+ "\"staged\":true,\"validation\":true,\"default\":true}]}";
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/v1/models", exchange -> {
			byte[] body = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		try {
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_HUB_ENDPOINT_URL,
					"http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
			Map<String, Object> payload = new HubProfileService().listProfiles();
			List<Map<String, Object>> profiles = (List<Map<String, Object>>) payload.get("data");
			assertEquals(1, profiles.size());
			assertEquals("Fast checked E4B", profiles.get(0).get("label"));
			assertTrue((Boolean) profiles.get(0).get("default"));
		}
		finally {
			server.stop(0);
		}
	}
}
