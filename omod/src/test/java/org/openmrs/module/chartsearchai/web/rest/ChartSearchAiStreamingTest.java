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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.ChatService.ChatTurnResult;
import org.openmrs.module.chartsearchai.api.PatientAccessCheck;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Behavioral tests for the SSE chat-streaming endpoint
 * ({@link ChartSearchAiRestController#chatStream}). These drive the real
 * controller method against a {@link MockHttpServletResponse} and assert on the
 * actual Server-Sent-Events bytes written to the response — staged phase events,
 * the terminal {@code done} envelope, and that authorization is enforced
 * before any streaming begins.
 *
 * <p>Only the injected collaborators (ChatService, PatientAccessCheck, ...) and
 * the static {@link Context} are mocked; the controller's own SSE serialization
 * and ordering are exercised for real.</p>
 */
public class ChartSearchAiStreamingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Wires the four collaborators the controller autowires, plus the
	 * common {@link Context} stubs, and returns a ready-to-call controller.
	 */
	private static Fixture newFixture(boolean accessGranted) {
		Fixture f = new Fixture();
		f.patient = mock(Patient.class);
		f.user = mock(User.class);
		f.patientService = mock(PatientService.class);
		f.adminService = mock(AdministrationService.class);
		f.patientAccessCheck = mock(PatientAccessCheck.class);
		f.auditLogService = mock(AuditLogService.class);
		f.chatService = mock(ChatService.class);
		f.session = mock(ChatSession.class);

		when(f.patientService.getPatientByUuid("patient-uuid")).thenReturn(f.patient);
		lenient().when(f.adminService.getGlobalProperty(ChartSearchAiConstants.GP_RATE_LIMIT_PER_MINUTE))
				.thenReturn(null);
		when(f.patientAccessCheck.canAccess(any(), eq(f.patient))).thenReturn(accessGranted);
		lenient().when(f.auditLogService.getQueryCountByUserSince(any(), any())).thenReturn(0L);
		lenient().when(f.chatService.openOrLoadActiveSession(f.patient)).thenReturn(f.session);
		lenient().when(f.session.getUuid()).thenReturn("session-uuid");
		lenient().when(f.chatService.priorTurnsForRelay(any())).thenReturn(Collections.emptyList());

		f.controller = new ChartSearchAiRestController();
		ReflectionTestUtils.setField(f.controller, "patientAccessCheck", f.patientAccessCheck);
		ReflectionTestUtils.setField(f.controller, "auditLogService", f.auditLogService);
		ReflectionTestUtils.setField(f.controller, "chatService", f.chatService);
		return f;
	}

	private static Map<String, String> chatBody() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", "patient-uuid");
		body.put("question", "What medications is this patient taking?");
		return body;
	}

	private static void configureHub(Fixture fixture, String endpoint) {
		when(fixture.adminService.getGlobalProperty(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL))
				.thenReturn(endpoint);
	}

	/**
	 * Chat requires one configured hub endpoint and profile. Missing configuration fails before the
	 * SSE stream opens rather than falling back to any Java inference path.
	 */
	@Test
	public void chatStream_shouldReturnCleanBadRequest_whenHubIsNotConfigured() throws Exception {
		Fixture f = newFixture(true);
		MockHttpServletResponse response = new MockHttpServletResponse();

		try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
			ctx.when(() -> Context.requirePrivilege(
					ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
			ctx.when(Context::getPatientService).thenReturn(f.patientService);
			ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
			ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);

			f.controller.chatStream(chatBody(), response);
		}

		assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
		assertFalse(response.getContentAsString().startsWith("event:"),
				"must fail before the SSE stream opens, got:\n" + response.getContentAsString());
	}

	/**
	 * Authorization is enforced BEFORE streaming: when the user cannot access the
	 * patient's chart the endpoint returns 403 with a JSON error and never calls
	 * the streaming service — no SSE bytes are written.
	 */
	@Test
	public void chatStream_shouldReturn403AndNotStream_whenAccessDenied() throws Exception {
		Fixture f = newFixture(false);

		MockHttpServletResponse response = new MockHttpServletResponse();

		try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
			ctx.when(() -> Context.requirePrivilege(
					ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
			ctx.when(Context::getPatientService).thenReturn(f.patientService);
			ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
			ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);

			f.controller.chatStream(chatBody(), response);
		}

		assertEquals(403, response.getStatus(), "Access-denied must return 403");
		// The genuine "auth before streaming" guarantee: the streaming service is
		// never invoked once access is denied.

		String body = response.getContentAsString();
		assertTrue(body.contains("\"error\""),
				"403 response must carry a JSON error body, got:\n" + body);
		assertTrue(!body.contains("event: answer_done") && !body.contains("event: done"),
				"No SSE events may be written after a 403, got:\n" + body);
		assertTrue(response.getContentType() != null
						&& response.getContentType().startsWith("application/json"),
				"403 error must be JSON, not an event-stream; got " + response.getContentType());
	}

	/**
	 * A failure while resolving the session — e.g. a dangling-FK
	 * {@code FetchNotFoundException} — happens BEFORE the SSE
	 * stream opens. It must be handled as a clean 500 JSON error, NOT propagate
	 * uncaught to the servlet container (which renders an OpenMRS HTML error page the
	 * SPA can't parse — the "blank 500"). The streaming service must never be reached
	 * and the raw exception must not leak to the client.
	 */
	@Test
	public void chatStream_shouldReturnCleanError_whenSessionOrChartBuildFails() throws Exception {
		Fixture f = newFixture(true);
		configureHub(f, "http://hub/v1/chat/completions");
		when(f.adminService.getGlobalProperty(ChartSearchAiConstants.GP_HUB_PROFILE_ID))
				.thenReturn("single-e4b-checked");
		// Simulate session resolution hitting a dangling encounter FK.
		when(f.chatService.openOrLoadActiveSession(f.patient))
				.thenThrow(new RuntimeException(
						"org.hibernate.FetchNotFoundException: Entity Encounter id 958 does not exist"));

		MockHttpServletResponse response = new MockHttpServletResponse();

		try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
			ctx.when(() -> Context.requirePrivilege(
					ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
			ctx.when(Context::getPatientService).thenReturn(f.patientService);
			ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
			ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);

			// Must NOT throw — the controller has to catch the pre-stream failure.
			f.controller.chatStream(chatBody(), response);
		}

		assertEquals(500, response.getStatus(),
				"a pre-stream build failure must be a handled 500, not a propagated exception");

		String body = response.getContentAsString();
		assertTrue(body.contains("\"error\""),
				"500 must carry a JSON error body, got:\n" + body);
		assertTrue(response.getContentType() != null
						&& response.getContentType().startsWith("application/json"),
				"pre-stream failure must be JSON, not an event-stream; got " + response.getContentType());
		assertTrue(!body.contains("FetchNotFoundException") && !body.contains("event: answer_done"),
				"must not leak the raw exception or open the SSE stream, got:\n" + body);
	}

	/**
	 * A team profile routes through the same one-hub-call relay as a single profile. Java forwards
	 * the profile id and never decomposes its stages.
	 */
	@Test
	public void chatStream_stagedTeamModel_relaysOneHubCallNotTheLegacyThreeCallDecomposition()
			throws Exception {
		Fixture f = newFixture(true);
		AtomicReference<String> hubRequestBody = new AtomicReference<String>();
		HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		hub.createContext("/v1/chat/completions", exchange -> {
			hubRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String sse = ""
					+ "event: answer_done\n"
					+ "data: {\"answer\":\"Direct answer [1].\",\"references\":[],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"validating\"},"
					+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n"
					+ "event: answer_validation\n"
					+ "data: {\"answer\":\"Direct answer [1].\",\"references\":[],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"checked\"},"
					+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n"
					+ "event: indepth_pending\ndata: {\"status\":\"pending\",\"answer\":\"\"}\n\n"
					+ "event: indepth_done\ndata: {\"status\":\"complete\",\"answer\":\"Background claim.\"}\n\n"
					+ "event: done\n"
					+ "data: {\"answer\":\"Direct answer [1].\",\"references\":[],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"checked\"},"
					+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"Background claim.\"}}\n\n";
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		hub.start();
		String hubUrl = "http://127.0.0.1:" + hub.getAddress().getPort() + "/v1/chat/completions";
		try {
			Map<String, String> body = chatBody();
			body.put("profile", "med-agent-team-high-validated");
			configureHub(f, hubUrl);
			when(f.chatService.persistHubStagedAnswer(eq(f.session), any(), any(), anyLong()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Direct answer [1].", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));
			when(f.chatService.updateHubStagedMessage(eq(f.session), eq("assistant-msg-uuid"), any()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Direct answer [1].", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));

			MockHttpServletResponse response = new MockHttpServletResponse();

			try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
				ctx.when(() -> Context.requirePrivilege(
						ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
				ctx.when(Context::getPatientService).thenReturn(f.patientService);
				ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
				ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);
				ctx.when(Context::getRuntimeProperties).thenReturn(new Properties());

				f.controller.chatStream(body, response);
			}

			String sse = response.getContentAsString();
			JsonNode done = parseDoneEvent(sse);
			assertEquals("Direct answer [1].", done.get("answer").asText());
			assertEquals("checked", done.get("answerValidation").get("status").asText());
			assertEquals("complete", done.get("inDepth").get("status").asText());

			JsonNode hubRequest = MAPPER.readTree(hubRequestBody.get());
			assertEquals("med-agent-team-high-validated", hubRequest.get("model").asText());
			verify(f.chatService, times(1)).persistHubStagedAnswer(eq(f.session), any(), any(), anyLong());
		}
		finally {
			hub.stop(0);
		}
	}

	/**
	 * Same config-error contract as streaming: sync {@code POST /chat} also requires the hub.
	 */
	@Test
	public void chat_shouldReturnBadRequest_whenHubIsNotConfigured() throws Exception {
		Fixture f = newFixture(true);

		ResponseEntity<Object> response;
		try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
			ctx.when(() -> Context.requirePrivilege(
					ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
			ctx.when(Context::getPatientService).thenReturn(f.patientService);
			ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
			ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);

			response = f.controller.chat(chatBody());
		}

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
	}

	/**
	 * Synchronous clients drain the same selected hub product profile the UI streams.
	 */
	@Test
	public void chat_profileRelaysToTheSameHubEngine() throws Exception {
		Fixture f = newFixture(true);
		AtomicReference<String> hubRequestBody = new AtomicReference<String>();
		HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		hub.createContext("/v1/chat/completions", exchange -> {
			hubRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			// Deliberate delay so the recorded responseTimeMs is deterministically non-zero,
			// proving it reflects real elapsed time rather than the old hardcoded 0.
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			String completion = "{\"choices\":[{\"message\":{\"content\":"
					+ "\"{\\\"answer\\\":\\\"Sync answer [1].\\\",\\\"references\\\":"
					+ "[{\\\"index\\\":1,\\\"resourceType\\\":\\\"Observation\\\",\\\"resourceUuid\\\":\\\"obs-1\\\"}],"
					+ "\\\"blocks\\\":[]}\"}}]}";
			byte[] bytes = completion.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		hub.start();
		String hubUrl = "http://127.0.0.1:" + hub.getAddress().getPort() + "/v1/chat/completions";
		try {
			Map<String, String> body = chatBody();
			body.put("profile", "single-e4b-checked");
			body.put("endpointUrl", "http://client-controlled.invalid/v1/chat/completions");
			body.put("modelName", "client-controlled-model");
			configureHub(f, hubUrl);
			when(f.chatService.persistHubStagedAnswer(eq(f.session), any(), any(), anyLong()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Sync answer [1].", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));

			ResponseEntity<Object> response;
			try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
				ctx.when(() -> Context.requirePrivilege(
						ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
				ctx.when(Context::getPatientService).thenReturn(f.patientService);
				ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
				ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);
				ctx.when(Context::getRuntimeProperties).thenReturn(new Properties());

				response = f.controller.chat(body);
			}

			JsonNode result = MAPPER.valueToTree(response.getBody());
			assertEquals("Sync answer [1].", result.get("answer").asText());
			assertEquals("single-e4b-checked", result.get("model").asText());

			JsonNode hubRequest = MAPPER.readTree(hubRequestBody.get());
			assertEquals("single-e4b-checked", hubRequest.get("model").asText());
			assertEquals("patient-uuid", hubRequest.get("patient").asText());
			assertFalse(hubRequest.get("stream").asBoolean());

			// The PERSISTED wire (not the mocked ChatTurnResult) is what proves the hub's completion
			// body was actually parsed correctly — the response body above only reflects the stub.
			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> wireCaptor = ArgumentCaptor.forClass(Map.class);
			ArgumentCaptor<Long> responseTimeCaptor = ArgumentCaptor.forClass(Long.class);
			verify(f.chatService, times(1)).persistHubStagedAnswer(
					eq(f.session), any(), wireCaptor.capture(), responseTimeCaptor.capture());
			JsonNode persistedWire = MAPPER.valueToTree(wireCaptor.getValue());
			assertEquals("Sync answer [1].", persistedWire.get("answer").asText());
			assertEquals("Observation", persistedWire.get("references").get(0).get("resourceType").asText());
			// Real wall-clock elapsed time for the hub round-trip, not the old hardcoded 0 —
			// the hub handler above sleeps 20ms before responding.
			assertTrue(responseTimeCaptor.getValue() >= 20,
					"responseTimeMs must reflect the real hub round-trip, got " + responseTimeCaptor.getValue());
		}
		finally {
			hub.stop(0);
		}
	}

	@Test
	public void chatStream_hubNativeSingleProfile_relaysOneHubStreamAndUpdatesSameMessage()
			throws Exception {
		Fixture f = newFixture(true);
		AtomicReference<String> hubRequestBody = new AtomicReference<String>();
		AtomicReference<String> hubRequestProtocol = new AtomicReference<String>();
		AtomicReference<String> hubRequestAccept = new AtomicReference<String>();
		AtomicReference<String> hubRequestContentType = new AtomicReference<String>();
		HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		hub.createContext("/v1/chat/completions", exchange -> {
			hubRequestProtocol.set(exchange.getProtocol());
			hubRequestAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
			hubRequestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			hubRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String sse = ""
					+ "event: answer_done\n"
					+ "data: {\"answer\":\"Initial answer [1].\",\"references\":[{\"index\":1,"
					+ "\"resourceType\":\"Observation\",\"resourceUuid\":\"obs-1\","
					+ "\"groundingStatus\":\"checking\",\"grounded\":null}],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"validating\"},"
					+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n"
					+ "event: answer_validation\n"
					+ "data: {\"answer\":\"Edited answer [2].\",\"references\":[{\"index\":2,"
					+ "\"resourceType\":\"Order\",\"resourceUuid\":\"ord-2\","
					+ "\"groundingStatus\":\"checking\",\"grounded\":null}],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"edited\",\"originalAnswer\":\"Initial answer [1].\"},"
					+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n"
					+ "event: indepth_pending\n"
					+ "data: {\"status\":\"pending\",\"answer\":\"\"}\n\n"
					+ "event: indepth_done\n"
					+ "data: {\"status\":\"complete\",\"answer\":\"Deep details.\"}\n\n"
					+ "event: done\n"
					+ "data: {\"answer\":\"Edited answer [2].\",\"references\":[{\"index\":2,"
					+ "\"resourceType\":\"Order\",\"resourceUuid\":\"ord-2\","
					+ "\"groundingStatus\":\"verified\",\"grounded\":true}],\"blocks\":[],"
					+ "\"answerValidation\":{\"status\":\"edited\",\"originalAnswer\":\"Initial answer [1].\"},"
					+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"Deep details.\"}}\n\n";
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		hub.start();
		String hubUrl = "http://127.0.0.1:" + hub.getAddress().getPort() + "/v1/chat/completions";
		try {
			Map<String, String> body = chatBody();
			body.put("profile", "single-12b-checked");
			configureHub(f, hubUrl);
			when(f.chatService.persistHubStagedAnswer(
					eq(f.session), eq("What medications is this patient taking?"), any(), anyLong()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Initial answer [1].",
							Collections.emptyList()), "session-uuid", "assistant-msg-uuid"));
			when(f.chatService.updateHubStagedMessage(eq(f.session), eq("assistant-msg-uuid"), any()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Edited answer [2].",
							Collections.emptyList()), "session-uuid", "assistant-msg-uuid"));

			MockHttpServletResponse response = new MockHttpServletResponse();

			try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
				ctx.when(() -> Context.requirePrivilege(
						ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
				ctx.when(Context::getPatientService).thenReturn(f.patientService);
				ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
				ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);
				ctx.when(Context::getRuntimeProperties).thenReturn(new Properties());

				f.controller.chatStream(body, response);
			}

			String sse = response.getContentAsString();
			assertTrue(sse.indexOf("event: answer_done") >= 0, "answer_done missing:\n" + sse);
			assertTrue(sse.indexOf("event: answer_validation") > sse.indexOf("event: answer_done"),
					"answer_validation must follow answer_done:\n" + sse);
			assertTrue(sse.indexOf("event: indepth_done") > sse.indexOf("event: indepth_pending"),
					"indepth_done must follow pending:\n" + sse);
			assertTrue(sse.indexOf("event: done") > sse.indexOf("event: indepth_done"),
					"done must follow indepth_done:\n" + sse);

			JsonNode answerDone = parseEvent(sse, "answer_done");
			assertEquals("assistant-msg-uuid", answerDone.get("messageId").asText());
			assertEquals("checking", answerDone.get("references").get(0).get("groundingStatus").asText());
			JsonNode done = parseDoneEvent(sse);
			assertEquals("Edited answer [2].", done.get("answer").asText());
			assertEquals("verified", done.get("references").get(0).get("groundingStatus").asText());
			assertEquals("single-12b-checked", done.get("model").asText());

			JsonNode hubRequest = MAPPER.readTree(hubRequestBody.get());
			assertEquals("HTTP/1.1", hubRequestProtocol.get());
			assertEquals("text/event-stream", hubRequestAccept.get());
			assertTrue(hubRequestContentType.get().startsWith("application/json"));
			assertTrue(hubRequestBody.get().length() > 0, "hub request JSON body must not be empty");
			assertEquals("single-12b-checked", hubRequest.get("model").asText());
			assertEquals("patient-uuid", hubRequest.get("patient").asText());
			assertEquals("What medications is this patient taking?",
					hubRequest.get("messages").get(0).get("content").asText());
			verify(f.chatService, times(1)).persistHubStagedAnswer(
					eq(f.session), eq("What medications is this patient taking?"), any(), anyLong());
			verify(f.chatService, times(3)).updateHubStagedMessage(
					eq(f.session), eq("assistant-msg-uuid"), any());
		}
		finally {
			hub.stop(0);
		}
	}

	/**
	 * Gate 5: the hub relay must thread prior conversation turns (prose-only, never the raw stored
	 * JSON envelope) into the hub request, with the CURRENT question last — a follow-up question
	 * ("what was the ISO date in your last answer?") is unanswerable from the chart alone, so
	 * without this the hub-native default path silently loses multi-turn context.
	 */
	@Test
	public void chatStream_hubNativeSingleProfile_threadsPriorConversationTurnsBeforeTheQuestion()
			throws Exception {
		Fixture f = newFixture(true);
		ChatMessage priorUser = new ChatMessage();
		priorUser.setRole(ChatMessage.ROLE_USER);
		priorUser.setContent("What was the most recent visit date?");
		ChatMessage priorAssistant = new ChatMessage();
		priorAssistant.setRole(ChatMessage.ROLE_ASSISTANT);
		priorAssistant.setContent("2026-01-26"); // priorTurnsForRelay's contract: prose, not JSON
		when(f.chatService.priorTurnsForRelay(f.session))
				.thenReturn(java.util.Arrays.asList(priorUser, priorAssistant));

		AtomicReference<String> hubRequestBody = new AtomicReference<String>();
		HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		hub.createContext("/v1/chat/completions", exchange -> {
			hubRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String sse = ""
					+ "event: answer_done\n"
					+ "data: {\"answer\":\"2026-01-26.\",\"references\":[],\"blocks\":[],"
					+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n"
					+ "event: indepth_pending\ndata: {\"status\":\"pending\",\"answer\":\"\"}\n\n"
					+ "event: indepth_done\ndata: {\"status\":\"complete\",\"answer\":\"\"}\n\n"
					+ "event: done\n"
					+ "data: {\"answer\":\"2026-01-26.\",\"references\":[],\"blocks\":[],"
					+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"\"}}\n\n";
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		hub.start();
		String hubUrl = "http://127.0.0.1:" + hub.getAddress().getPort() + "/v1/chat/completions";
		try {
			Map<String, String> body = chatBody();
			body.put("question", "Repeat just the ISO date from your previous answer.");
			body.put("profile", "single-12b-checked");
			configureHub(f, hubUrl);
			when(f.chatService.persistHubStagedAnswer(eq(f.session), any(), any(), anyLong()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("2026-01-26.", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));
			when(f.chatService.updateHubStagedMessage(eq(f.session), eq("assistant-msg-uuid"), any()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("2026-01-26.", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));

			MockHttpServletResponse response = new MockHttpServletResponse();

			try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
				ctx.when(() -> Context.requirePrivilege(
						ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
				ctx.when(Context::getPatientService).thenReturn(f.patientService);
				ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
				ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);
				ctx.when(Context::getRuntimeProperties).thenReturn(new Properties());

				f.controller.chatStream(body, response);
			}

			JsonNode hubRequest = MAPPER.readTree(hubRequestBody.get());
			JsonNode messages = hubRequest.get("messages");
			assertEquals(3, messages.size(), "expected 2 prior turns + the current question");
			assertEquals("user", messages.get(0).get("role").asText());
			assertEquals("What was the most recent visit date?", messages.get(0).get("content").asText());
			assertEquals("assistant", messages.get(1).get("role").asText());
			assertEquals("2026-01-26", messages.get(1).get("content").asText());
			assertEquals("user", messages.get(2).get("role").asText());
			assertEquals("Repeat just the ISO date from your previous answer.",
					messages.get(2).get("content").asText());
		}
		finally {
			hub.stop(0);
		}
	}

	/**
	 * Gate 6: a mid-leg browser disconnect must be detected via a heartbeat-triggered write, not
	 * only discovered on the NEXT real event. The hub answers fast, then goes quiet (as it would
	 * mid in-depth generation) sending only heartbeat comment lines for well over a second before
	 * ever emitting {@code done}; the browser "disconnects" after the first successful write. If
	 * the relay only writes on real events, it blocks reading heartbeats for the whole stall and
	 * this test takes 1200ms+; if it writes (and so notices the disconnect) on each heartbeat, it
	 * must return promptly.
	 */
	@Test
	public void chatStream_hubNativeSingleProfile_abortsPromptlyOnDisconnectDuringHeartbeats()
			throws Exception {
		Fixture f = newFixture(true);
		HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		hub.createContext("/v1/chat/completions", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0); // chunked — arbitrary length, flushed incrementally
			try (OutputStream body = exchange.getResponseBody()) {
				body.write((""
						+ "event: answer_done\n"
						+ "data: {\"answer\":\"Ans.\",\"references\":[],\"blocks\":[],"
						+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"\"}}\n\n")
						.getBytes(StandardCharsets.UTF_8));
				body.flush();
				for (int i = 0; i < 8; i++) {
					body.write(": hb\n\n".getBytes(StandardCharsets.UTF_8));
					body.flush();
					try {
						Thread.sleep(150);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				body.write((""
						+ "event: done\n"
						+ "data: {\"answer\":\"Ans.\",\"references\":[],\"blocks\":[],"
						+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"\"}}\n\n")
						.getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException expectedOnceRelayCloses) {
				// the relay closed its connection to us mid-stream once it noticed the "browser"
				// disconnect — exactly the behavior under test.
			}
		});
		hub.start();
		String hubUrl = "http://127.0.0.1:" + hub.getAddress().getPort() + "/v1/chat/completions";
		try {
			Map<String, String> body = chatBody();
			body.put("profile", "single-12b-checked");
			configureHub(f, hubUrl);
			when(f.chatService.persistHubStagedAnswer(eq(f.session), any(), any(), anyLong()))
					.thenReturn(new ChatTurnResult(new ChartAnswer("Ans.", Collections.emptyList()),
							"session-uuid", "assistant-msg-uuid"));

			// "Browser" that accepts the FIRST write (answer_done) then disconnects.
			DisconnectAfterNWritesResponse response = new DisconnectAfterNWritesResponse(1);

			long startNanos = System.nanoTime();
			try (MockedStatic<Context> ctx = mockStatic(Context.class)) {
				ctx.when(() -> Context.requirePrivilege(
						ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)).then(inv -> null);
				ctx.when(Context::getPatientService).thenReturn(f.patientService);
				ctx.when(Context::getAdministrationService).thenReturn(f.adminService);
				ctx.when(Context::getAuthenticatedUser).thenReturn(f.user);
				ctx.when(Context::getRuntimeProperties).thenReturn(new Properties());

				f.controller.chatStream(body, response);
			}
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

			assertTrue(elapsedMs < 700, "relay must abort promptly on a mid-leg disconnect — took "
					+ elapsedMs + "ms; 8 heartbeats * 150ms = 1200ms of missed opportunities to notice "
					+ "means it only writes (and so only notices disconnects) on real events");
		}
		finally {
			hub.stop(0);
		}
	}

	/**
	 * A response whose output stream accepts the first {@code failAfterWrites} write calls, then
	 * throws {@link IOException} on every subsequent write — simulating a browser that received
	 * the fast answer, then closed the connection. NOT an {@link javax.servlet.http.HttpServletResponseWrapper}:
	 * the controller unwraps those (to bypass buffering wrappers), which would strip this behavior.
	 */
	private static final class DisconnectAfterNWritesResponse extends MockHttpServletResponse {

		private final int failAfterWrites;

		private int writeCalls;

		DisconnectAfterNWritesResponse(int failAfterWrites) {
			this.failAfterWrites = failAfterWrites;
		}

		@Override
		public javax.servlet.ServletOutputStream getOutputStream() {
			return new javax.servlet.ServletOutputStream() {

				@Override
				public void write(int b) throws IOException {
					write(new byte[] { (byte) b }, 0, 1);
				}

				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					writeCalls++;
					if (writeCalls > failAfterWrites) {
						throw new IOException("simulated browser disconnect");
					}
				}
			};
		}
	}

	/**
	 * Extracts and parses the JSON object carried by the terminal {@code done}
	 * SSE event. The controller emits {@code data: <json>} lines after
	 * {@code event: done}; reassemble them and parse.
	 */
	private static JsonNode parseDoneEvent(String sse) throws Exception {
		int doneIdx = sse.indexOf("event: done");
		assertTrue(doneIdx >= 0, "no done event to parse");
		String afterDone = sse.substring(doneIdx);
		StringBuilder json = new StringBuilder();
		for (String line : afterDone.split("\n")) {
			if (line.startsWith("data: ")) {
				json.append(line.substring("data: ".length()));
			} else if (line.startsWith("event: ") && json.length() > 0) {
				break;
			}
		}
		return MAPPER.readTree(json.toString());
	}

	private static JsonNode parseEvent(String sse, String eventName) throws Exception {
		int eventIdx = sse.indexOf("event: " + eventName);
		assertTrue(eventIdx >= 0, "no " + eventName + " event to parse");
		String afterEvent = sse.substring(eventIdx);
		StringBuilder json = new StringBuilder();
		for (String line : afterEvent.split("\n")) {
			if (line.startsWith("data: ")) {
				json.append(line.substring("data: ".length()));
			} else if (line.startsWith("event: ") && json.length() > 0) {
				break;
			}
		}
		return MAPPER.readTree(json.toString());
	}

	private static final class Fixture {

		Patient patient;

		User user;

		PatientService patientService;

		AdministrationService adminService;

		PatientAccessCheck patientAccessCheck;

		AuditLogService auditLogService;

		ChatService chatService;

		ChatSession session;

		ChartSearchAiRestController controller;
	}
}
