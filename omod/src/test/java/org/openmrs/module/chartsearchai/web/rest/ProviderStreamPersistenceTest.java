/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletOutputStream;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.openmrs.module.chartsearchai.api.impl.DefaultPatientAccessCheck;
import org.openmrs.module.chartsearchai.api.provider.HubClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.HttpHubStreamTransport;
import org.springframework.mock.web.MockHttpServletResponse;
import org.openmrs.Patient;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.UserContext;
import org.springframework.http.ResponseEntity;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.conversation.ConversationDAO;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope;
import org.openmrs.module.chartsearchai.api.provider.BundledClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProviderRegistry;
import org.openmrs.module.chartsearchai.api.provider.ProviderCapability;
import org.openmrs.module.chartsearchai.api.provider.ProviderDescriptor;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnEvent;
import org.openmrs.module.chartsearchai.api.provider.TurnEventSink;
import org.openmrs.module.chartsearchai.api.provider.TurnEventType;
import org.openmrs.module.chartsearchai.api.provider.TurnRequest;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/** Real controller-to-Hibernate/audit join. Inference alone is replaced by deterministic events. */
public class ProviderStreamPersistenceTest extends BaseModuleWebContextSensitiveTest {

	@Autowired
	private ConversationService conversations;

	@Autowired
	private ConversationDAO conversationDAO;

	@Autowired
	private AuditLogService auditLogs;

	@Test
	public void terminalAnswerNamesTheAuditActuallyPersistedForThatTurn() throws Exception {
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(null) {
			@Override
			public ProviderDescriptor descriptor() {
				return readyDescriptor();
			}

			@Override
			public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
					CancellationSignal cancellation) {
				AnswerEnvelope answer = AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Persisted answer"));
				events.accept(TurnEvent.of(TurnEventType.TURN_STARTED, 0, "bundled"));
				events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_DONE, 1, "bundled", answer));
				events.accept(TurnEvent.withAnswer(TurnEventType.TURN_DONE, 2, "bundled", answer));
				return CompletableFuture.completedFuture(TurnResult.done("bundled", request.getMode(), answer));
			}
		};
		ByteArrayOutputStream output = stream(provider, ProviderMode.QUERY_SCOPED);
		List<JsonNode> terminal = terminalPayloads(output);
		assertEquals(1, terminal.size());
		JsonNode payload = terminal.get(0);
		assertTrue(payload.path("auditLogId").isIntegralNumber(), "live feedback needs the persisted audit identifier");
		int auditId = payload.path("auditLogId").asInt();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn turn = conversationDAO.getTurnByUuid(payload.path("messageId").asText());
		assertNotNull(turn);
		assertEquals("turn_done", turn.getTerminalState());
		assertEquals(auditId, turn.getAuditLog().getAuditLogId());
		assertEquals("Persisted answer", auditLogs.getAuditLog(auditId).getAnswer());
	}

	@Test
	public void actualBundledModeRejectionPersistsOneTerminalError() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CHART_MODE, "queryScoped");
		// Readiness is not under test; execute is the real bundled rejection, before inference.
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(null) {
			@Override
			public ProviderDescriptor descriptor() {
				return readyDescriptor();
			}
		};
		ByteArrayOutputStream output = stream(provider, ProviderMode.FULL_CHART_STABLE);
		List<JsonNode> terminal = terminalPayloads(output);
		assertEquals(1, terminal.size(), "rejection must not be followed by a second persistence failure");
		assertEquals("unsupported_mode", terminal.get(0).path("problemCode").asText());
		Patient patient = Context.getPatientService().getPatient(2);
		ClinicalConversation conversation = conversations.getLatestActiveConversation(patient);
		List<ClinicalConversationTurn> turns = conversations.getTurns(conversation);
		assertEquals(1, turns.size());
		String uuid = turns.get(0).getUuid();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn reloaded = conversationDAO.getTurnByUuid(uuid);
		assertEquals("turn_error", reloaded.getTerminalState());
		assertEquals("unsupported_mode", reloaded.getProblemCode());
		assertNotNull(reloaded.getAuditLog());
	}

	private HttpServer peer;

	private volatile String peerBody = "";

	private volatile int peerStatus = 503;

	private volatile JsonNode receivedRequest;

	private final AtomicInteger peerRequests = new AtomicInteger();

	@AfterEach
	public void stopPeer() {
		if (peer != null) {
			peer.stop(0);
		}
	}

	@Test
	public void disconnectAtTheFirstProviderEventSettlesTheStoredTurn() throws Exception {
		assertFirstEventFailureIsStored(false);
	}

	@Test
	public void unexpectedResponseFailureAtTheFirstProviderEventSettlesTheStoredTurn() throws Exception {
		assertFirstEventFailureIsStored(true);
	}

	private void assertFirstEventFailureIsStored(boolean runtimeFailure) throws Exception {
		ChartSearchAiRestController controller = requestController();
		Patient patient = Context.getPatientService().getPatient(2);
		MockHttpServletResponse response = new MockHttpServletResponse() {
			@Override
			public ServletOutputStream getOutputStream() {
				return new ServletOutputStream() {
					@Override public void write(int value) throws IOException {
						if (runtimeFailure) {
							throw new IllegalStateException("Response output failed");
						}
						throw new IOException("Client disconnected");
					}
				};
			}
		};
		controller.chatStream(requestBody(patient), response);
		assertEquals(0, peerRequests.get(), "a failed first event must stop before the Hub request");
		ClinicalConversation conversation = conversations.getLatestActiveConversation(patient);
		List<ClinicalConversationTurn> turns = conversations.getTurns(conversation);
		assertEquals(1, turns.size());
		String turnUuid = turns.get(0).getUuid();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn turn = conversationDAO.getTurnByUuid(turnUuid);
		assertEquals("turn_error", turn.getTerminalState());
		assertEquals("cancelled", turn.getProblemCode());
		assertNotNull(turn.getCompletedAt());
		assertNotNull(turn.getAuditLog());
		assertEquals("hub", turn.getAuditLog().getProviderId());
		assertTrue(conversations.priorClinicalTurns(turn.getConversation()).isEmpty());
	}

	private ChartSearchAiRestController requestController() throws IOException {
		peer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		peer.createContext("/chat", exchange -> {
			peerRequests.incrementAndGet();
			receivedRequest = new ObjectMapper().readTree(exchange.getRequestBody());
			byte[] bytes = peerBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(peerStatus, bytes.length == 0 ? -1 : bytes.length);
			try {
				exchange.getResponseBody().write(bytes);
			} finally {
				exchange.close();
			}
		});
		peer.start();
		Context.getAdministrationService().setGlobalProperty(ClinicalAnswerProviderRegistry.GP_PROVIDERS_ENABLED, "hub");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL,
				"http://" + peer.getAddress().getAddress().getHostAddress() + ":" + peer.getAddress().getPort() + "/chat");
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setConversationService(conversations);
		controller.setAuditLogService(auditLogs);
		controller.setPatientAccessCheck(new DefaultPatientAccessCheck());
		controller.setProviderRegistry(new ClinicalAnswerProviderRegistry(Collections.singletonList(
				new HubClinicalAnswerProvider(new HttpHubStreamTransport()))));
		return controller;
	}

	@Test
	public void actualHttpProviderFailureIsTerminalInDatabaseAndHistory() throws Exception {
		ChartSearchAiRestController controller = requestController();
		Patient patient = Context.getPatientService().getPatient(2);
		MockHttpServletResponse response = new MockHttpServletResponse();
		controller.chatStream(requestBody(patient), response);
		assertEquals(1, peerRequests.get());
		assertEquals(patient.getUuid(), receivedRequest.path("patient").asText());
		assertEquals("product-profile-a", receivedRequest.path("model").asText());
		assertTrue(response.getContentAsString().contains("event: turn_error"));
		Context.flushSession();
		Context.clearSession();
		JsonNode history = history(controller, patient);
		JsonNode assistant = history.path("messages").get(1);
		assertEquals("turn_error", assistant.path("terminalState").asText());
		assertEquals("hub_rejected_request", assistant.path("problemCode").asText());
		assertTrue(assistant.path("auditLogId").isIntegralNumber());
		assertTrue(assistant.path("messageId").isTextual());
	}

	@Test
	public void interruptedHttpReviewTailRetainsTheCheckedAnswerInHistory() throws Exception {
		ChartSearchAiRestController controller = requestController();
		Patient patient = Context.getPatientService().getPatient(2);
		peerStatus = 200;
		String payload = "{\"answer\":\"Retained answer.\",\"originalAnswer\":\"Inspectable draft.\","
				+ "\"answerValidation\":{\"status\":\"checked\"},"
				+ "\"inDepth\":{\"status\":\"pending\",\"answer\":\"Partial detail.\"},\"references\":[]}";
		peerBody = "event: answer_done\ndata: " + payload + "\n\n"
				+ "event: answer_validation\ndata: " + payload + "\n\n"
				+ "event: indepth_pending\ndata: " + payload + "\n\n";
		MockHttpServletResponse response = new MockHttpServletResponse();
		controller.chatStream(requestBody(patient), response);
		assertEquals(1, peerRequests.get());
		assertTrue(response.getContentAsString().contains("Retained answer."));
		Context.flushSession();
		Context.clearSession();
		JsonNode restored = history(controller, patient);
		JsonNode assistant = restored.path("messages").get(1);
		assertEquals("Retained answer.", assistant.path("content").asText());
		assertEquals("Inspectable draft.", assistant.path("originalAnswer").asText());
		assertEquals("checked", assistant.path("answerValidation").path("status").asText());
		assertEquals("failed", assistant.path("inDepth").path("status").asText());
		assertEquals("Partial detail.", assistant.path("inDepth").path("answer").asText());
		assertEquals("hub", restored.path("provider").asText());
		assertTrue(assistant.path("auditLogId").isIntegralNumber());
		ClinicalConversation active = conversations.getLatestActiveConversation(patient);
		assertEquals("turn_error", assistant.path("terminalState").asText());
		assertEquals("hub_stream_incomplete", assistant.path("problemCode").asText());
		assertTrue(conversations.priorClinicalTurns(active).isEmpty(),
				"an incomplete turn remains inspectable but is not replayed as completed context");
	}

	@Test
	public void unauthenticatedRequestCannotStartATurnOrReadHistory() throws Exception {
		ChartSearchAiRestController controller = requestController();
		Patient patient = Context.getPatientService().getPatient(2);
		UserContext previous = Context.getUserContext();
		try {
			Context.setUserContext(new UserContext(null));
			assertThrows(ContextAuthenticationException.class,
					() -> controller.chatStream(requestBody(patient), new MockHttpServletResponse()));
			assertThrows(ContextAuthenticationException.class, () -> controller.getChat(patient.getUuid(), null));
		} finally {
			Context.setUserContext(previous);
		}
		assertEquals(0, peerRequests.get());
		assertEquals(null, conversations.getLatestActiveConversation(patient));
	}

	private JsonNode history(ChartSearchAiRestController controller, Patient patient) {
		ResponseEntity<Object> response = controller.getChat(patient.getUuid(), null);
		assertEquals(200, response.getStatusCode().value());
		return new ObjectMapper().valueToTree(response.getBody());
	}

	private Map<String, String> requestBody(Patient patient) {
		Map<String, String> body = new HashMap<>();
		body.put("patient", patient.getUuid());
		body.put("question", "What is recorded?");
		body.put("provider", "hub");
		body.put("mode", "query_scoped");
		body.put("profile", "product-profile-a");
		return body;
	}

	private ByteArrayOutputStream stream(ClinicalAnswerProvider provider, ProviderMode mode) {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setConversationService(conversations);
		controller.setProviderRegistry(new ClinicalAnswerProviderRegistry(Collections.singletonList(provider)));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		controller.streamProviderTurn(output, Context.getPatientService().getPatient(2), "Question",
				"bundled", mode, null, null);
		return output;
	}

	private static ProviderDescriptor readyDescriptor() {
		return new ProviderDescriptor("bundled", "Bundled", true, true, true,
				Collections.singletonList(ProviderMode.QUERY_SCOPED),
				Collections.singleton(ProviderCapability.ANSWER), null);
	}

	private static List<JsonNode> terminalPayloads(ByteArrayOutputStream output) throws Exception {
		List<JsonNode> result = new ArrayList<>();
		for (String frame : output.toString(StandardCharsets.UTF_8.name()).split("\n\n")) {
			if (frame.startsWith("event: turn_done\n") || frame.startsWith("event: turn_error\n")) {
				result.add(new ObjectMapper().readTree(frame.substring(frame.indexOf("data: ") + 6).trim()));
			}
		}
		return result;
	}
}
