/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * Pins the med-agent-hub request envelope and SSE framing used by
 * {@link HttpHubStreamTransport}. Network I/O is not exercised here — only the pure request and
 * parse seams the production transport shares with the old relay.
 */
public class HttpHubStreamTransportTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@SuppressWarnings("unchecked")
	public void requestJsonCarriesProfilePatientPriorsAndProductContext() throws Exception {
		HubCallRequest request = new HubCallRequest(
				"http://hub.example/v1/chat/completions", "product-a", "patient-1",
				"conversation-1", "request-1", "current question",
				Collections.singletonList(new PriorClinicalTurn("earlier q", "earlier a")));

		String json = HttpHubStreamTransport.requestJson(request);
		Map<String, Object> root = MAPPER.readValue(json, Map.class);

		assertEquals("product-a", root.get("model"));
		assertEquals(Boolean.TRUE, root.get("stream"));
		assertEquals("patient-1", root.get("patient"));
		List<Map<String, Object>> messages = (List<Map<String, Object>>) root.get("messages");
		assertEquals(3, messages.size());
		assertEquals("user", messages.get(0).get("role"));
		assertEquals("earlier q", messages.get(0).get("content"));
		assertEquals("assistant", messages.get(1).get("role"));
		assertEquals("earlier a", messages.get(1).get("content"));
		assertEquals("user", messages.get(2).get("role"));
		assertEquals("current question", messages.get(2).get("content"));

		Map<String, Object> context = (Map<String, Object>) root.get("context");
		assertEquals(Boolean.TRUE, context.get("require_product_profile"));
		assertEquals("conversation-1", context.get("session"));
		assertEquals("request-1", context.get("request_id"));
	}

	@Test
	public void sseParserEmitsEventsAndIgnoresHeartbeats() throws Exception {
		String sse = ""
				+ ": hb\n\n"
				+ "event: answer_done\n"
				+ "data: {\"answer\":\"Aspirin\",\"references\":[]}\n"
				+ "\n"
				+ "event: done\n"
				+ "data: {\"answer\":\"Aspirin\",\"references\":[]}\n"
				+ "\n";
		List<HubWireEvent> events = new ArrayList<>();
		try (InputStream in = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8))) {
			HttpHubStreamTransport.parseSse(in, events::add);
		}
		assertEquals(2, events.size());
		assertEquals("answer_done", events.get(0).getEvent());
		assertEquals("Aspirin", events.get(0).getPayload().get("answer"));
		assertEquals("done", events.get(1).getEvent());
	}

	@Test
	public void nonSuccessStatusThrowsHubTransportExceptionWithBody() {
		HubTransportException failure = assertThrows(HubTransportException.class,
				() -> HttpHubStreamTransport.requireSuccess(422, "{\"detail\":{\"code\":\"x\"}}"));
		assertEquals(422, failure.getStatusCode());
		assertTrue(failure.getBody().contains("\"code\":\"x\""));
	}
}
