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
import org.openmrs.GlobalProperty;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils.ReferenceSlice;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
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
 * Hibernate-created H2 tables, and audit DAO. This does not execute Liquibase. The tests deliberately use an unknown nested provider
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
		payload.put("answerValidation", Collections.singletonMap("status", "needs_review"));
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

		ChartSearchAuditLog audit = reloaded.getAuditLog();
		assertNotNull(audit, "every accepted turn is independently auditable");
		assertEquals("hub", audit.getProviderId());
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN, audit.getSearchMode());
		assertNull(audit.getReferenceSliceRecords());
		assertNull(audit.getReferenceSliceChars());
		assertEquals(ProviderMode.QUERY_SCOPED.getWireName(), audit.getProviderMode());
		assertEquals(conversation.getUuid(), audit.getConversationUuid());
		assertEquals("request-1", audit.getRequestId());
		assertEquals("Use caution [1].", audit.getAnswer());
		assertEquals(1, audit.getReferenceCount());

		List<PriorClinicalTurn> prior = conversationService.priorClinicalTurns(
				reloaded.getConversation());
		assertEquals(0, prior.size(),
				"a needs-review terminal answer remains auditable but cannot become clinical context");
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
	public void editedCheckedAnswerReplacesTheDraftInFollowUpContext() throws Exception {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "request-edited",
				"What was the most recent visit date?");

		Map<String, Object> draft = new LinkedHashMap<>();
		draft.put("answer", "2026-01-27");
		draft.put("answerValidation", Collections.singletonMap("status", "checked"));
		assertTrue(conversationService.recordCheckedAnswer(turn, AnswerEnvelope.fromPayload(draft)));

		Map<String, Object> validation = new LinkedHashMap<>();
		validation.put("status", "edited");
		validation.put("originalAnswer", "2026-01-27");
		Map<String, Object> edited = new LinkedHashMap<>();
		edited.put("answer", "2026-01-26");
		edited.put("answerValidation", validation);
		assertTrue(conversationService.recordCheckedAnswer(turn, AnswerEnvelope.fromPayload(edited)));

		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn reloaded = conversationDAO.getTurnByUuid(turn.getUuid());
		List<PriorClinicalTurn> prior = conversationService.priorClinicalTurns(
				reloaded.getConversation());
		assertEquals(1, prior.size());
		assertEquals("2026-01-26", prior.get(0).getAnswer(),
				"follow-up context must use the post-review answer, never the superseded draft");
		assertEquals("2026-01-27",
				MAPPER.readTree(reloaded.getProviderPayload()).get("answerValidation")
						.get("originalAnswer").asText(),
				"the original remains inspectable in the persisted provider payload");
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
		assertEquals(1, conversationService.priorClinicalTurns(survivor.getConversation()).size(),
				"providers without an answer-review lifecycle remain valid clinical history");
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
	@Test
	public void bundledAuditPreservesMeasuredSearchModeAndReferenceSlice() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "bundled",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "bundled-audit", "Question");
		ChartAnswer source = new ChartAnswer("Answer", Collections.emptyList(), 12, 3, 0,
				Collections.emptyList(), "fullChart", new ReferenceSlice(7, 320));
		conversationService.finishTurn(turn, TurnResult.done("bundled", ProviderMode.QUERY_SCOPED,
				AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Answer"), source)), 5L);
		Context.flushSession();
		Context.clearSession();
		ChartSearchAuditLog audit = conversationDAO.getTurnByUuid(turn.getUuid()).getAuditLog();
		assertEquals("fullChart", audit.getSearchMode());
		assertEquals(7, audit.getReferenceSliceRecords());
		assertEquals(320, audit.getReferenceSliceChars());
	}

	@Test
	public void failedOptionalTailAuditsTheCheckedAnswerAlreadyDisplayed() {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "interrupted-tail", "Question");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "The checked answer already shown");
		payload.put("answerValidation", Collections.singletonMap("status", "checked"));
		payload.put("references", Collections.singletonList(Collections.singletonMap("label", "Visit")));
		payload.put("inputTokens", 23);
		payload.put("outputTokens", 8);
		assertTrue(conversationService.recordCheckedAnswer(turn, AnswerEnvelope.fromPayload(payload)));
		conversationService.finishTurn(turn, TurnResult.error("hub", ProviderMode.QUERY_SCOPED,
				"provider_stream_interrupted"), 50L);
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn stored = conversationDAO.getTurnByUuid(turn.getUuid());
		assertEquals("turn_error", stored.getTerminalState());
		assertEquals(stored.getAnswerText(), stored.getAuditLog().getAnswer());
		assertEquals(1, stored.getAuditLog().getReferenceCount());
		assertEquals(23, stored.getAuditLog().getInputTokens());
		assertEquals(8, stored.getAuditLog().getOutputTokens());
	}

	@Test
	public void finishingAnInflightTurnDoesNotReopenTheClosedConversation() {
		assertClosedConversationSurvivesInflightWrite(false);
	}

	@Test
	public void recordingACheckedAnswerDoesNotReopenTheClosedConversation() {
		assertClosedConversationSurvivesInflightWrite(true);
	}

	private void assertClosedConversationSurvivesInflightWrite(boolean checked) {
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub",
				ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn inflight = conversationService.startTurn(conversation, "inflight", "Question");
		Context.flushSession();
		Context.clearSession();
		// A later request closes the conversation while the stream retains its earlier entity.
		ClinicalConversation fresh = conversationService.startNew(patient, "hub", ProviderMode.QUERY_SCOPED);
		Date closedAt = conversationDAO.getConversationByUuid(conversation.getUuid()).getEndedAt();
		Context.flushSession();
		Context.clearSession();
		assertEquals(ClinicalConversation.STATUS_ACTIVE, conversation.getStatus());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Checked answer");
		payload.put("answerValidation", Collections.singletonMap("status", "checked"));
		AnswerEnvelope answer = AnswerEnvelope.fromPayload(payload);
		if (checked) {
			assertTrue(conversationService.recordCheckedAnswer(inflight, answer));
		} else {
			conversationService.finishTurn(inflight, TurnResult.done("hub", ProviderMode.QUERY_SCOPED, answer), 5L);
		}
		Context.flushSession();
		Context.clearSession();
		ClinicalConversation stored = conversationDAO.getConversationByUuid(conversation.getUuid());
		assertEquals(ClinicalConversation.STATUS_CLOSED, stored.getStatus());
		assertEquals(closedAt, stored.getEndedAt());
		assertEquals(fresh.getUuid(), conversationService.getLatestActiveConversation(patient).getUuid());
		assertEquals("Checked answer", conversationDAO.getTurnByUuid(inflight.getUuid()).getAnswerText());
	}

	@Test
	public void scheduledRetentionPurgesOldCompletedAndAbandonedTurnsEvenWithAuditPurgeDisabled() {
		setProperty("chartsearchai.auditLogRetentionDays", "0");
		setProperty("chartsearchai.chat.retentionDays", "90");
		ClinicalConversation old = conversationService.openOrCreate(patient, "hub", ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn abandoned = conversationService.startTurn(old, "abandoned", "Old question");
		ClinicalConversationTurn completed = conversationService.startTurn(old, "completed", "Old question");
		conversationService.finishTurn(completed, TurnResult.done("hub", ProviderMode.QUERY_SCOPED,
				AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Old answer"))), 5L);
		Date before = new Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000);
		abandoned.setStartedAt(before);
		completed.setStartedAt(before);
		completed.setCompletedAt(before);
		completed.getAuditLog().setDateCreated(before);
		old.setLastActivityAt(before);
		Integer auditId = completed.getAuditLog().getAuditLogId();
		Context.flushSession();
		Context.clearSession();
		new AuditLogPurgeTask().execute();
		Context.flushSession();
		Context.clearSession();
		assertNull(conversationDAO.getTurnByUuid(abandoned.getUuid()));
		assertNull(conversationDAO.getTurnByUuid(completed.getUuid()));
		assertNull(conversationDAO.getConversationByUuid(old.getUuid()));
		assertNotNull(auditLogService.getAuditLog(auditId), "audit retention is independent of chat retention");
	}

	@Test
	public void scheduledRetentionUsesCompletionTimeAndRetainsARecentUnfinishedTurn() {
		setProperty("chartsearchai.chat.retentionDays", "90");
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub", ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn unfinished = conversationService.startTurn(conversation, "recent", "Question");
		ClinicalConversationTurn completed = conversationService.startTurn(conversation, "just-completed", "Question");
		conversationService.finishTurn(completed, TurnResult.done("hub", ProviderMode.QUERY_SCOPED,
				AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Answer"))), 5L);
		completed.setStartedAt(new Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000));
		Context.flushSession();
		Context.clearSession();
		new AuditLogPurgeTask().execute();
		Context.flushSession();
		Context.clearSession();
		assertNotNull(conversationDAO.getTurnByUuid(unfinished.getUuid()));
		assertNotNull(conversationDAO.getTurnByUuid(completed.getUuid()));
	}

	@Test
	public void disablingChatRetentionKeepsOldTurnsWhileAuditRetentionStillRuns() {
		setProperty("chartsearchai.chat.retentionDays", "0");
		setProperty("chartsearchai.auditLogRetentionDays", "90");
		ClinicalConversation conversation = conversationService.openOrCreate(patient, "hub", ProviderMode.QUERY_SCOPED);
		ClinicalConversationTurn turn = conversationService.startTurn(conversation, "retained", "Question");
		conversationService.finishTurn(turn, TurnResult.done("hub", ProviderMode.QUERY_SCOPED,
				AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Answer"))), 5L);
		Date before = new Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000);
		turn.setStartedAt(before);
		turn.setCompletedAt(before);
		turn.getAuditLog().setDateCreated(before);
		Context.flushSession();
		Context.clearSession();
		new AuditLogPurgeTask().execute();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn stored = conversationDAO.getTurnByUuid(turn.getUuid());
		assertNotNull(stored);
		assertNull(stored.getAuditLog());
	}

	private void setProperty(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
	}

}
