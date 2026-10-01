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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the configured provider, real HTTP transport and terminal answer mapping together.
 * The peer serves protocol fixtures; this verifies transport/lifecycle behavior, not model quality.
 */
public class HubClinicalAnswerProviderHttpTest extends BaseModuleContextSensitiveTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private HubClinicalAnswerProvider provider;

	private HttpServer server;

	private final AtomicReference<JsonNode> receivedRequest = new AtomicReference<>();

	@AfterEach
	public void closePeer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private TurnResult executeOverHttp(String body, List<TurnEvent> events) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat", exchange -> {
			receivedRequest.set(MAPPER.readTree(exchange.getRequestBody()));
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL,
				"http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
		Patient patient = Context.getPatientService().getPatient(7);
		TurnRequest request = new TurnRequest(patient, "What is recorded?", "conversation-1", "request-1",
				ProviderMode.QUERY_SCOPED, "product-profile-a", Collections.emptyList());
		TurnResult result = provider.execute(request, events::add, CancellationSignal.NONE)
				.toCompletableFuture().get();
		assertEquals(patient.getUuid(), receivedRequest.get().path("patient").asText());
		assertEquals("product-profile-a", receivedRequest.get().path("model").asText());
		assertTrue(receivedRequest.get().path("context").path("require_product_profile").asBoolean());
		List<TurnEventType> types = events.stream().map(TurnEvent::getType).collect(Collectors.toList());
		assertTrue(TurnLifecycleValidator.violations(provider.descriptor().getCapabilities(), types).isEmpty());
		assertEquals(1, types.stream().filter(TurnEventType::isTerminal).count());
		return result;
	}

	@Test
	public void terminalHttpAnswerSettlesUnfinishedOptionalStagesWithoutLosingTheAnswer() throws Exception {
		String payload = "{\"answer\":\"Supported answer.\",\"references\":[],"
				+ "\"originalAnswer\":\"Inspectable draft.\","
				+ "\"answerValidation\":{\"status\":\"checking\"},"
				+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"Partial elaboration.\"}}";
		List<TurnEvent> events = new ArrayList<>();
		TurnResult result = executeOverHttp("event: answer_done\ndata: " + payload + "\n\n"
				+ "event: indepth_pending\ndata: " + payload + "\n\n"
				+ "event: done\ndata: " + payload + "\n\n", events);
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		Map<String, Object> answer = result.getAnswer().getPayload();
		assertEquals("Supported answer.", answer.get("answer"));
		assertEquals("Inspectable draft.", answer.get("originalAnswer"));
		assertEquals("unavailable", ((Map<?, ?>) answer.get("answerValidation")).get("status"));
		assertEquals("failed", ((Map<?, ?>) answer.get("inDepth")).get("status"));
		assertEquals("Partial elaboration.", ((Map<?, ?>) answer.get("inDepth")).get("answer"));
		assertEquals(answer, events.get(events.size() - 1).getAnswer().getPayload());
	}

	@Test
	public void endOfStreamAfterCheckedAnswerSettlesItsOptionalTail() throws Exception {
		String payload = "{\"answer\":\"Retained answer.\",\"originalAnswer\":\"Inspectable draft.\","
				+ "\"answerValidation\":{\"status\":\"checked\"},"
				+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"Partial detail.\"},\"references\":[]}";
		List<TurnEvent> events = new ArrayList<>();
		TurnResult result = executeOverHttp("event: answer_done\ndata: " + payload + "\n\n"
				+ "event: answer_validation\ndata: " + payload + "\n\n"
				+ "event: indepth_pending\ndata: " + payload + "\n\n", events);
		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("hub_stream_incomplete", result.getProblemCode());
		assertNull(result.getAnswer());
		Map<String, Object> answer = events.stream()
				.filter(event -> event.getType() == TurnEventType.INDEPTH_ERROR)
				.findFirst().orElseThrow(AssertionError::new).getAnswer().getPayload();
		assertEquals("Retained answer.", answer.get("answer"));
		assertEquals("Inspectable draft.", answer.get("originalAnswer"));
		assertEquals("checked", ((Map<?, ?>) answer.get("answerValidation")).get("status"));
		assertEquals("failed", ((Map<?, ?>) answer.get("inDepth")).get("status"));
		assertEquals("Partial detail.", ((Map<?, ?>) answer.get("inDepth")).get("answer"));
		assertEquals(TurnEventType.TURN_ERROR, events.get(events.size() - 1).getType());
	}

	@Test
	public void aPlainHttpAnswerWithoutOptionalCapabilitiesStillCompletes() throws Exception {
		List<TurnEvent> events = new ArrayList<>();
		TurnResult result = executeOverHttp("event: done\ndata: {\"answer\":\"Plain answer.\","
				+ "\"references\":[]}\n\n", events);
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Plain answer.", result.getAnswer().getText());
		assertFalse(result.getAnswer().getPayload().containsKey("answerValidation"));
		assertFalse(result.getAnswer().getPayload().containsKey("inDepth"));
		assertNull(result.getProblemCode());
	}
}
