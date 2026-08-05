/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.AuditLogPurgeTask;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnEventType;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Provider-neutral conversation persistence through the real Spring service, Hibernate mappings,
 * Liquibase-created tables, and audit DAO. The tests deliberately use an unknown nested provider
 * extension to prove Java stores the payload without taking ownership of its schema.
 */
public class ConversationServicePersistenceTest extends BaseModuleContextSensitiveTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private ConversationDAO conversationDAO;

	@Autowired
	private AuditLogService auditLogService;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(2);
		assertNotNull(patient, "standard test patient 2 must exist");
	}

	@Test
	public void reusesOnlyAnActiveConversationWithTheSameProviderAndMode() {
		ClinicalConversation first = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversation same = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		assertEquals(first.getUuid(), same.getUuid());

		ClinicalConversation switchedProvider = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		assertNotEquals(first.getUuid(), switchedProvider.getUuid(),
				"provider switching must start a new conversation");
		assertEquals(ClinicalConversation.STATUS_CLOSED, first.getStatus());
		assertEquals("hub", switchedProvider.getProviderId());
		assertEquals(ProviderMode.QUERY_SCOPED.getWireName(), switchedProvider.getProviderMode());

		ClinicalConversation switchedMode = conversationService.openOrCreate(patient, "hub",
				ProviderMode.FULL_CHART_STABLE);
		assertNotEquals(switchedProvider.getUuid(), switchedMode.getUuid(),
				"changing context semantics must not silently reuse prior conversation state");
		assertEquals(ClinicalConversation.STATUS_CLOSED, switchedProvider.getStatus());
	}

	@Test
	public void startNewAlwaysClosesActiveConversationEvenWhenProviderAndModeMatch() {
		ClinicalConversation first = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversation fresh = conversationService.startNew(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		assertNotEquals(first.getUuid(), fresh.getUuid());
		assertEquals(ClinicalConversation.STATUS_CLOSED, first.getStatus());
		assertEquals(ClinicalConversation.STATUS_ACTIVE, fresh.getStatus());
		assertEquals("bundled", fresh.getProviderId());
	}

	@Test
	public void completedTurnPersistsOpaquePayloadAndIndependentAuditAttribution() throws Exception {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-1",
				"Can ibuprofen be used?");

		Map<String, Object> providerExtension = new LinkedHashMap<>();
		providerExtension.put("opaque", true);
		providerExtension.put("futureValue", Collections.singletonMap("score", 0.73));
		Map<String, Object> reference = new LinkedHashMap<>();
		reference.put("resourceType", "obs");
		reference.put("resourceUuid", "obs-1");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Use caution [1].");
		payload.put("references", Collections.singletonList(reference));
		Map<String, Object> tableBlock = new LinkedHashMap<>();
		tableBlock.put("type", "table");
		tableBlock.put("title", "Medication summary");
		tableBlock.put("columns", java.util.Arrays.asList("Medication", "Status"));
		tableBlock.put("rows", Collections.singletonList(
				java.util.Arrays.asList("Ibuprofen", "Use caution")));
		payload.put("blocks", Collections.singletonList(tableBlock));
		payload.put("answerValidation", Collections.singletonMap("status", "needs_review"));
		payload.put("safetyStatus", "limited");
		Map<String, Object> warning = new LinkedHashMap<>();
		warning.put("type", "contraindication");
		warning.put("drug", "Ibuprofen");
		warning.put("detail", "Recorded allergy requires review.");
		payload.put("safetyWarnings", Collections.singletonList(warning));
		Map<String, Object> safetyCheck = new LinkedHashMap<>();
		safetyCheck.put("status", "limited");
		safetyCheck.put("issues", Collections.singletonList("mapping_incomplete"));
		payload.put("safetyCheck", safetyCheck);
		payload.put("providerExtension", providerExtension);

		conversationService.finishTurn(turn,
				TurnResult.done("hub", ProviderMode.QUERY_SCOPED,
						AnswerEnvelope.fromPayload(payload)),
				125L);
		String turnUuid = turn.getUuid();
		Context.flushSession();
		Context.clearSession();

		ClinicalConversationTurn reloaded = conversationDAO.getTurnByUuid(turnUuid);
		assertNotNull(reloaded);
		assertEquals(TurnEventType.TURN_DONE.getWireName(), reloaded.getTerminalState());
		assertEquals("Use caution [1].", reloaded.getAnswerText());
		JsonNode stored = MAPPER.readTree(reloaded.getProviderPayload());
		assertEquals(true, stored.get("providerExtension").get("opaque").asBoolean());
		assertEquals(0.73,
				stored.get("providerExtension").get("futureValue").get("score").asDouble());
		assertEquals("needs_review",
				stored.get("answerValidation").get("status").asText());
		assertEquals("table", stored.get("blocks").get(0).get("type").asText(),
				"structured answer blocks must survive the real database round-trip");
		assertEquals("Ibuprofen",
				stored.get("blocks").get(0).get("rows").get(0).get(0).asText());
		assertEquals("limited", stored.get("safetyStatus").asText());
		assertEquals("contraindication",
				stored.get("safetyWarnings").get(0).get("type").asText());
		assertEquals("mapping_incomplete",
				stored.get("safetyCheck").get("issues").get(0).asText());

		ChartSearchAuditLog audit = reloaded.getAuditLog();
		assertNotNull(audit, "every accepted turn is independently auditable");
		assertEquals("hub", audit.getProviderId());
		assertEquals(ProviderMode.QUERY_SCOPED.getWireName(), audit.getProviderMode());
		assertEquals(conversation.getUuid(), audit.getConversationUuid());
		assertEquals("request-1", audit.getRequestId());
		assertEquals("Use caution [1].", audit.getAnswer());
		assertEquals(1, audit.getReferenceCount());

		List<PriorClinicalTurn> prior = conversationService.priorClinicalTurns(
				reloaded.getConversation());
		assertEquals(1, prior.size());
		assertEquals("Can ibuprofen be used?", prior.get(0).getQuestion());
		assertEquals("Use caution [1].", prior.get(0).getAnswer());
	}

	@Test
	public void failedTurnIsAuditedButNeverReplayedAsClinicalHistory() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-failed",
				"What medications is this patient on?");

		conversationService.finishTurn(turn,
				TurnResult.error("hub", ProviderMode.QUERY_SCOPED, "provider_timeout"), 5000L);

		assertEquals(TurnEventType.TURN_ERROR.getWireName(), turn.getTerminalState());
		assertEquals("provider_timeout", turn.getProblemCode());
		assertNull(turn.getAnswerText());
		assertNull(turn.getProviderPayload());
		assertNotNull(turn.getAuditLog());
		assertEquals("", turn.getAuditLog().getAnswer());
		assertEquals(0, conversationService.priorClinicalTurns(conversation).size(),
				"failed output cannot become context for a later clinical answer");
	}

	@Test
	public void checkedAnswerIsAvailableForFollowUpBeforeTheInDepthTailCompletes() throws Exception {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-checked",
				"What was the most recent visit date?");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "2026-01-26");
		payload.put("answerValidation", Collections.singletonMap("status", "checked"));
		payload.put("inDepth", Collections.singletonMap("status", "pending"));
		assertTrue(conversationService.recordCheckedAnswer(turn, AnswerEnvelope.fromPayload(payload)));
		assertNull(turn.getTerminalState(), "the In-Depth tail has not completed yet");
		assertNull(turn.getAuditLog(), "only the terminal turn creates the immutable audit row");
		Context.flushSession();
		Context.clearSession();

		List<PriorClinicalTurn> prior = conversationService.priorClinicalTurns(
				conversationDAO.getTurnByUuid(turn.getUuid()).getConversation());
		assertEquals(1, prior.size(),
				"a checked answer must be usable as history while its optional In-Depth tail runs");
		assertEquals("What was the most recent visit date?", prior.get(0).getQuestion());
		assertEquals("2026-01-26", prior.get(0).getAnswer());
	}

	@Test
	public void needsReviewAnswerNeverBecomesFollowUpContext() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-needs-review",
				"What was the most recent visit date?");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Unreviewed answer");
		payload.put("answerValidation", Collections.singletonMap("status", "needs_review"));

		assertFalse(conversationService.recordCheckedAnswer(turn, AnswerEnvelope.fromPayload(payload)));
		assertNull(turn.getAnswerText());
		assertEquals(0, conversationService.priorClinicalTurns(conversation).size());
	}

	@Test
	public void auditRetentionDoesNotDeleteAYoungerConversationTurn() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-retention",
				"What medications is this patient on?");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Lisinopril 10mg.");
		payload.put("references", Collections.emptyList());
		conversationService.finishTurn(turn,
				TurnResult.done("bundled", ProviderMode.QUERY_SCOPED,
						AnswerEnvelope.fromPayload(payload)),
				10L);

		turn.getAuditLog().setDateCreated(
				new Date(System.currentTimeMillis() - 200L * 24L * 60L * 60L * 1000L));
		auditLogService.saveAuditLog(turn.getAuditLog());
		String turnUuid = turn.getUuid();
		Context.flushSession();

		new AuditLogPurgeTask().execute();
		Context.flushSession();
		Context.clearSession();

		ClinicalConversationTurn survivor = conversationDAO.getTurnByUuid(turnUuid);
		assertNotNull(survivor, "conversation history has an independent retention horizon");
		assertNull(survivor.getAuditLog(),
				"the audit link is nulled when its independently retained row is purged");
		assertEquals("Lisinopril 10mg.", survivor.getAnswerText());
	}

	@Test
	public void finishTurnRejectsProviderOrModeDriftFromTheConversation() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn providerDrift = conversationService.startTurn(conversation, "request-2", "q");
		assertThrows(IllegalArgumentException.class,
				() -> conversationService.finishTurn(providerDrift,
						TurnResult.error("hub", ProviderMode.QUERY_SCOPED, "provider_failure"), 1L));

		ClinicalConversationTurn modeDrift = conversationService.startTurn(conversation, "request-3", "q");
		assertThrows(IllegalArgumentException.class,
				() -> conversationService.finishTurn(modeDrift,
						TurnResult.error("bundled", ProviderMode.FULL_CHART_STABLE, "provider_failure"), 1L));
	}
}
