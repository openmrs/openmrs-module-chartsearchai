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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /chat} reload/history recovery. A fresh page load has no client-side session to
 * send — only the patient it's looking at — so {@code session} must be optional, falling back to
 * the caller's own active conversation, and each restored assistant turn must carry its full
 * persisted answer envelope (references, In-Depth, etc.), not just bare text — otherwise a reload
 * silently drops the evidence and validation state that was on screen a moment before.
 */
public class ChatHistoryEndpointTest {

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-1");
		patient.setPatientId(1);
		return patient;
	}

	private static ClinicalConversation conversation(String uuid, Patient patient) {
		ClinicalConversation conversation = new ClinicalConversation();
		conversation.setUuid(uuid);
		conversation.setPatient(patient);
		conversation.setProviderId("hub");
		conversation.setProviderMode("query_scoped");
		conversation.setStatus(ClinicalConversation.STATUS_ACTIVE);
		return conversation;
	}

	@Test
	public void withNoSessionAndNoActiveConversationReturnsAnEmptyHistoryNotAnError() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setConversationService(new StubConversationService());

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), null);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertNull(body.get("session"));
		assertEquals(Collections.emptyList(), body.get("messages"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void withNoSessionRecoversTheCallersActiveConversation() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		ClinicalConversation active = conversation("conversation-uuid-1", patient());
		conversations.activeConversation = active;
		controller.setConversationService(conversations);

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), null);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertEquals("conversation-uuid-1", body.get("session"));
		assertEquals("hub", body.get("provider"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void restoresTheFullAnswerEnvelopeFromThePersistedPayloadNotJustBareText() {
		// The whole point of surviving reload: citations, safety warnings, and the In-Depth
		// verdict must come back exactly as they were, not collapse to plain text.
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		ClinicalConversation active = conversation("conversation-uuid-1", patient());
		conversations.activeConversation = active;

		ClinicalConversationTurn turn = new ClinicalConversationTurn();
		turn.setUuid("turn-uuid-1");
		turn.setQuestion("What medications is this patient on?");
		turn.setRequestId("request-1");
		turn.setAnswerText("Aspirin 81mg [1].");
		turn.setProviderPayload("{\"answer\":\"Aspirin 81mg [1].\","
				+ "\"references\":[{\"index\":1,\"resourceType\":\"drugOrder\"}],"
				+ "\"blocks\":[{\"type\":\"table\",\"title\":\"Medications\","
				+ "\"columns\":[\"Medication\"],\"rows\":[[\"Aspirin 81mg\"]]}],"
				+ "\"answerValidation\":{\"status\":\"checked\"},"
				+ "\"safetyStatus\":\"limited\","
				+ "\"safetyWarnings\":[{\"type\":\"interaction\",\"drug\":\"Aspirin\","
				+ "\"detail\":\"Review interacting medicines.\"}],"
				+ "\"safetyCheck\":{\"status\":\"limited\","
				+ "\"issues\":[\"exposure_incomplete\"]},"
					+ "\"inDepth\":{\"status\":\"needs_review\","
					+ "\"validation\":{\"status\":\"needs_review\","
					+ "\"summary\":\"One claim is not supported by its cited source.\"}}}");
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setAuditLogId(42);
		turn.setAuditLog(audit);
		conversations.turns.put("conversation-uuid-1", Collections.singletonList(turn));
		controller.setConversationService(conversations);

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), null);

		Map<String, Object> body = (Map<String, Object>) response.getBody();
		List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
		assertEquals(2, messages.size());
		Map<String, Object> assistant = messages.get(1);
		assertEquals("assistant", assistant.get("role"));
		assertEquals("Aspirin 81mg [1].", assistant.get("content"));
		assertEquals(42, assistant.get("auditLogId"));
		assertTrue(assistant.get("references") instanceof List, "references must survive reload");
		assertEquals(1, ((List<?>) assistant.get("references")).size());
		Map<String, Object> validation = (Map<String, Object>) assistant.get("answerValidation");
		assertEquals("checked", validation.get("status"));
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) assistant.get("blocks");
		assertEquals("table", blocks.get(0).get("type"));
		assertEquals("Aspirin 81mg", ((List<List<String>>) blocks.get(0).get("rows")).get(0).get(0));
		assertEquals("limited", assistant.get("safetyStatus"));
		assertEquals(1, ((List<?>) assistant.get("safetyWarnings")).size());
		Map<String, Object> safetyCheck = (Map<String, Object>) assistant.get("safetyCheck");
		assertEquals("exposure_incomplete", ((List<?>) safetyCheck.get("issues")).get(0));
		Map<String, Object> inDepth = (Map<String, Object>) assistant.get("inDepth");
		assertEquals("needs_review", inDepth.get("status"));
		Map<String, Object> inDepthValidation = (Map<String, Object>) inDepth.get("validation");
		assertEquals("One claim is not supported by its cited source.",
				inDepthValidation.get("summary"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void restoresFailedTurnsWithStableIdsAndTerminalMetadata() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		ClinicalConversation active = conversation("conversation-uuid-1", patient());
		conversations.activeConversation = active;
		ClinicalConversationTurn turn = new ClinicalConversationTurn();
		turn.setUuid("turn-uuid-1");
		turn.setQuestion("What medications is this patient on?");
		turn.setRequestId("request-1");
		turn.setTerminalState("turn_error");
		turn.setProblemCode("provider_failure");
		conversations.turns.put("conversation-uuid-1", Collections.singletonList(turn));
		controller.setConversationService(conversations);

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), null);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");

		assertEquals(2, messages.size());
		assertEquals("request-1", messages.get(0).get("messageId"));
		assertEquals("turn-uuid-1", messages.get(1).get("messageId"));
		assertEquals("", messages.get(1).get("content"));
		assertEquals("turn_error", messages.get(1).get("terminalState"));
		assertEquals("provider_failure", messages.get(1).get("problemCode"));
	}

	@Test
	public void anExplicitSessionIsPreferredOverTheCallersActiveConversation() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		conversations.activeConversation = conversation("active-but-not-requested", patient());
		ClinicalConversation requested = conversation("explicitly-requested", patient());
		conversations.byUuid.put("explicitly-requested", requested);
		controller.setConversationService(conversations);

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), "explicitly-requested");

		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertEquals("explicitly-requested", body.get("session"));
	}

	@Test
	public void anExplicitSessionThatDoesNotExistIs404() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setConversationService(new StubConversationService());

		ResponseEntity<Object> response = controller.buildChatHistoryResponse(patient(), "does-not-exist");

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
	}

	private static final class StubConversationService implements ConversationService {

		ClinicalConversation activeConversation;

		final Map<String, ClinicalConversation> byUuid = new HashMap<>();

		final Map<String, List<ClinicalConversationTurn>> turns = new HashMap<>();

		@Override
		public ClinicalConversation openOrCreate(Patient patient, String providerId, ProviderMode mode) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClinicalConversation startNew(Patient patient, String providerId, ProviderMode mode) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClinicalConversation getByUuid(String uuid) {
			return byUuid.get(uuid);
		}

		@Override
		public ClinicalConversation getLatestActiveConversation(Patient patient) {
			return activeConversation;
		}

		@Override
		public List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation) {
			return turns.getOrDefault(conversation.getUuid(), new ArrayList<ClinicalConversationTurn>());
		}

		@Override
		public ClinicalConversationTurn startTurn(ClinicalConversation conversation, String requestId,
				String question) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClinicalConversationTurn finishTurn(ClinicalConversationTurn turn, TurnResult result,
				long responseTimeMs) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean recordCheckedAnswer(ClinicalConversationTurn turn,
				org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope answer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation) {
			throw new UnsupportedOperationException();
		}
	}
}
