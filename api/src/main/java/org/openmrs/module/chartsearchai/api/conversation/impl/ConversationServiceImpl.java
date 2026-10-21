/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.conversation.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils.ReferenceSlice;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.conversation.ConversationDAO;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnEventType;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional owner of provider-neutral conversation history and audit attribution. Provider
 * payloads are serialized as complete opaque envelopes; only canonical answer text, references
 * count, and token accounting are read for shared display/audit fields.
 */
@Service("chartSearchAi.conversationService")
@Transactional
public class ConversationServiceImpl implements ConversationService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private ConversationDAO conversationDAO;

	@Autowired
	private AuditLogService auditLogService;

	@Override
	public ClinicalConversation openOrCreate(Patient patient, String providerId, ProviderMode mode) {
		require(patient != null, "patient is required");
		require(providerId != null && !providerId.trim().isEmpty(), "providerId is required");
		User user = Context.getAuthenticatedUser();
		require(user != null, "an authenticated user is required");

		String modeName = mode == null ? null : mode.getWireName();
		// Serialized on the patient's row: nothing in the mapping or the schema keeps two concurrent
		// opens from each finding no active conversation and creating one, which splits the history.
		conversationDAO.lockConversationsOf(patient);
		ClinicalConversation current = conversationDAO.getLatestActiveConversation(patient, user);
		if (current != null && providerId.equals(current.getProviderId())
				&& Objects.equals(modeName, current.getProviderMode())) {
			return current;
		}
		return closeAndCreate(patient, user, providerId, modeName, current);
	}

	@Override
	public ClinicalConversation startNew(Patient patient, String providerId, ProviderMode mode) {
		require(patient != null, "patient is required");
		require(providerId != null && !providerId.trim().isEmpty(), "providerId is required");
		User user = Context.getAuthenticatedUser();
		require(user != null, "an authenticated user is required");
		String modeName = mode == null ? null : mode.getWireName();
		conversationDAO.lockConversationsOf(patient);
		ClinicalConversation current = conversationDAO.getLatestActiveConversation(patient, user);
		return closeAndCreate(patient, user, providerId, modeName, current);
	}

	private ClinicalConversation closeAndCreate(Patient patient, User user, String providerId,
			String modeName, ClinicalConversation current) {
		Date now = new Date();
		if (current != null) {
			current.setStatus(ClinicalConversation.STATUS_CLOSED);
			current.setEndedAt(now);
			conversationDAO.saveConversation(current);
		}

		ClinicalConversation conversation = new ClinicalConversation();
		conversation.setPatient(patient);
		conversation.setUser(user);
		conversation.setProviderId(providerId);
		conversation.setProviderMode(modeName);
		conversation.setStartedAt(now);
		conversation.setLastActivityAt(now);
		conversation.setStatus(ClinicalConversation.STATUS_ACTIVE);
		return conversationDAO.saveConversation(conversation);
	}

	@Override
	public ClinicalConversation getLatestActiveConversation(Patient patient) {
		require(patient != null, "patient is required");
		User user = Context.getAuthenticatedUser();
		require(user != null, "an authenticated user is required");
		return conversationDAO.getLatestActiveConversation(patient, user);
	}

	@Override
	public ClinicalConversation getByUuid(String uuid) {
		if (uuid == null || uuid.trim().isEmpty()) {
			return null;
		}
		ClinicalConversation conversation = conversationDAO.getConversationByUuid(uuid.trim());
		User user = Context.getAuthenticatedUser();
		return conversation != null && user != null && user.equals(conversation.getUser())
				? conversation : null;
	}

	@Override
	public List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation) {
		return conversationDAO.getTurns(conversation);
	}

	@Override
	public ClinicalConversationTurn startTurn(ClinicalConversation conversation, String requestId,
			String question) {
		require(conversation != null, "conversation is required");
		require(requestId != null && !requestId.trim().isEmpty(), "requestId is required");
		require(question != null && !question.trim().isEmpty(), "question is required");

		// Lock the parent row before reading max(ordinal). The unique database constraint catches
		// duplicate ordinals, but without this lock two concurrent requests can both read the same
		// maximum and one valid turn is rejected instead of cleanly preempting the other.
		ClinicalConversation locked = conversationDAO
				.getConversationForUpdate(conversation.getConversationId());
		require(locked != null, "conversation does not exist");
		require(ClinicalConversation.STATUS_ACTIVE.equals(locked.getStatus()),
				"conversation is not active");

		Date now = new Date();
		ClinicalConversationTurn turn = new ClinicalConversationTurn();
		turn.setConversation(locked);
		turn.setOrdinal(conversationDAO.getLastOrdinal(locked) + 1);
		turn.setRequestId(requestId);
		turn.setQuestion(question);
		turn.setStartedAt(now);
		locked.setLastActivityAt(now);
		conversationDAO.saveConversation(locked);
		return conversationDAO.saveTurn(turn);
	}

	@Override
	public ClinicalConversationTurn finishTurn(ClinicalConversationTurn turn, TurnResult result,
			long responseTimeMs) {
		require(turn != null && turn.getConversation() != null, "turn and conversation are required");
		require(result != null, "turn result is required");
		ClinicalConversation conversation = turn.getConversation();
		require(conversation.getProviderId().equals(result.getProviderId()),
				"turn provider does not match its conversation");
		String resultMode = result.getMode() == null ? null : result.getMode().getWireName();
		require(Objects.equals(conversation.getProviderMode(), resultMode),
				"turn mode does not match its conversation");
		require(result.getTerminalState() == TurnEventType.TURN_DONE
				|| result.getTerminalState() == TurnEventType.TURN_ERROR,
				"turn result must be terminal");
		// One outcome and at most one audit row per turn: a second settlement is refused, as
		// recordCheckedAnswer refuses a settled turn. The stored row decides, read under a lock: the
		// caller's object stays marked finished when a settlement fails after marking it and rolls back.
		ClinicalConversationTurn stored = conversationDAO.getTurnForUpdate(turn.getTurnId());
		require(stored != null && stored.getTerminalState() == null, "turn is already finished");
		turn = stored;

		Date now = new Date();
		turn.setTerminalState(result.getTerminalState().getWireName());
		turn.setProblemCode(result.getProblemCode());
		turn.setCompletedAt(now);

		AnswerEnvelope answer = result.getAnswer();
		if (result.getTerminalState() == TurnEventType.TURN_DONE) {
			require(answer != null, "turn_done requires an answer");
			turn.setAnswerText(answer.getText());
			turn.setProviderPayload(serialize(answer.getPayload()));
			turn.setPayloadMediaType(ClinicalConversationTurn.MEDIA_TYPE_JSON);
		} else {
			require(answer == null, "turn_error cannot carry a final answer");
		}

		ChartSearchAuditLog audit = buildAudit(turn, result, responseTimeMs, now);
		// In this transaction, so a turn is never settled without its audit row: a failure to write the
		// row rolls the settlement back with it, where /search logs the failure and lets its answer stand.
		auditLogService.saveAuditLog(audit);
		turn.setAuditLog(audit);
		conversationDAO.touchConversation(conversation.getConversationId(), now);
		return conversationDAO.saveTurn(turn);
	}

	@Override
	public boolean recordCheckedAnswer(ClinicalConversationTurn turn, AnswerEnvelope answer) {
		require(turn != null && turn.getConversation() != null, "turn and conversation are required");
		require(answer != null, "answer is required");
		if (!isChecked(answer)) {
			return false;
		}
		// The stored row decides whether the turn is still open, as finishTurn reads it: the caller's
		// copy can predate a settlement made elsewhere, and saving it would clear that settlement's
		// terminal state and audit link.
		ClinicalConversationTurn stored = conversationDAO.getTurnForUpdate(turn.getTurnId());
		require(stored != null, "turn does not exist");
		if (stored.getTerminalState() != null) {
			return false;
		}

		stored.setAnswerText(answer.getText());
		stored.setProviderPayload(serialize(retainedPayload(answer)));
		stored.setPayloadMediaType(ClinicalConversationTurn.MEDIA_TYPE_JSON);
		Date now = new Date();
		conversationDAO.touchConversation(stored.getConversation().getConversationId(), now);
		conversationDAO.saveTurn(stored);
		if (turn != stored) {
			turn.setAnswerText(stored.getAnswerText());
			turn.setProviderPayload(stored.getProviderPayload());
			turn.setPayloadMediaType(stored.getPayloadMediaType());
		}
		return true;
	}

	/**
	 * The payload a checked answer is retained with. A bundled answer carries its measurements on the
	 * {@link ChartAnswer} it was built from, not in its wire payload; they are written alongside it
	 * here so the audit of a turn settled from the retained answer (an optional tail that failed) keeps
	 * the search mode, the reference slice and the token counts the delivered answer was measured with.
	 */
	private static Map<String, Object> retainedPayload(AnswerEnvelope answer) {
		ChartAnswer source = answer.getSource();
		if (source == null) {
			return answer.getPayload();
		}
		Map<String, Object> payload = new LinkedHashMap<>(answer.getPayload());
		payload.put("searchMode", source.getSearchMode());
		ReferenceSlice slice = source.getReferenceSlice();
		if (slice != null) {
			payload.put("referenceSliceRecords", slice.getRecords());
			payload.put("referenceSliceChars", slice.getCharacters());
		}
		if (measured(source.getInputTokens()) != null) {
			payload.put("inputTokens", source.getInputTokens());
		}
		if (measured(source.getOutputTokens()) != null) {
			payload.put("outputTokens", source.getOutputTokens());
		}
		return payload;
	}

	@Override
	public List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation) {
		List<PriorClinicalTurn> prior = new ArrayList<>();
		for (ClinicalConversationTurn turn : conversationDAO.getTurns(conversation)) {
			boolean completedAnswer = TurnEventType.TURN_DONE.getWireName().equals(turn.getTerminalState())
					&& isStoredAnswerReplayable(turn);
			boolean checkedAnswerStillRunning = turn.getTerminalState() == null && isStoredChecked(turn);
			if ((completedAnswer || checkedAnswerStillRunning) && turn.getAnswerText() != null) {
				prior.add(new PriorClinicalTurn(turn.getQuestion(), turn.getAnswerText()));
			}
		}
		return prior;
	}

	@Override
	public int purgeBefore(Date before) {
		require(before != null, "retention cutoff is required");
		return conversationDAO.purgeBefore(before);
	}

	private ChartSearchAuditLog buildAudit(ClinicalConversationTurn turn, TurnResult result,
			long responseTimeMs, Date now) {
		ClinicalConversation conversation = turn.getConversation();
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setUser(conversation.getUser());
		audit.setPatient(conversation.getPatient());
		audit.setQuestion(turn.getQuestion());
		// A failed turn audits what its client was last sent, else the checked answer the turn retained.
		AnswerEnvelope retained = result.getAnswer() == null && result.getDeliveredAnswer() == null
				? retainedAnswer(turn) : null;
		AnswerEnvelope answer = result.getAnswer() != null ? result.getAnswer()
				: result.getDeliveredAnswer() != null ? result.getDeliveredAnswer() : retained;
		audit.setAnswer(answer == null ? "" : answer.getText());
		audit.setReferenceCount(referenceCount(answer));
		ChartAnswer source = answer == null ? null : answer.getSource();
		// A retained bundled answer states its measurements in its payload (retainedPayload); a relayed
		// payload's own fields are the provider's and are never read as this module's measurements.
		String retainedMode = text(retained, "searchMode");
		audit.setSearchMode(source != null ? source.getSearchMode()
				: retainedMode != null ? retainedMode : ChartSearchAiConstants.SEARCH_MODE_UNKNOWN);
		ReferenceSlice slice = source == null ? null : source.getReferenceSlice();
		audit.setReferenceSliceRecords(slice != null ? Integer.valueOf(slice.getRecords())
				: count(retained, "referenceSliceRecords"));
		audit.setReferenceSliceChars(slice != null ? Integer.valueOf(slice.getCharacters())
				: count(retained, "referenceSliceChars"));
		audit.setResponseTimeMs(responseTimeMs);
		// The bundled answer's counts are on the ChartAnswer it was built from, as saveAuditLog reads
		// them; only a relayed answer states them in its payload.
		audit.setInputTokens(source != null ? measured(source.getInputTokens()) : number(answer, "inputTokens"));
		audit.setOutputTokens(source != null ? measured(source.getOutputTokens()) : number(answer, "outputTokens"));
		audit.setProviderId(conversation.getProviderId());
		audit.setProviderMode(conversation.getProviderMode());
		audit.setConversationUuid(conversation.getUuid());
		audit.setRequestId(turn.getRequestId());
		audit.setDateCreated(now);
		return audit;
	}

	@SuppressWarnings("unchecked")
	private static AnswerEnvelope retainedAnswer(ClinicalConversationTurn turn) {
		if (turn.getAnswerText() == null) {
			return null;
		}
		if (turn.getProviderPayload() == null) {
			return AnswerEnvelope.fromPayload(Collections.singletonMap("answer", turn.getAnswerText()));
		}
		try {
			return AnswerEnvelope.fromPayload(MAPPER.readValue(turn.getProviderPayload(), Map.class));
		}
		catch (IOException e) {
			throw new APIException("Could not read the retained answer for audit", e);
		}
	}

	private static int referenceCount(AnswerEnvelope answer) {
		if (answer == null) {
			return 0;
		}
		Object references = answer.getPayload().get("references");
		return references instanceof List ? ((List<?>) references).size() : 0;
	}

	private static String text(AnswerEnvelope answer, String field) {
		Object value = answer == null ? null : answer.getPayload().get(field);
		return value instanceof String ? (String) value : null;
	}

	private static Integer number(AnswerEnvelope answer, String field) {
		if (answer == null) {
			return null;
		}
		Object value = answer.getPayload().get(field);
		return value instanceof Number ? measured(((Number) value).intValue()) : null;
	}

	/** A slice count as stated: zero is a measurement here, unlike a token count. */
	private static Integer count(AnswerEnvelope answer, String field) {
		Object value = answer == null ? null : answer.getPayload().get(field);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	/** Zero is no measurement, as saveAuditLog files it: a module-composed answer reports 0/0. */
	private static Integer measured(int tokens) {
		return tokens > 0 ? tokens : null;
	}

	private static boolean isChecked(AnswerEnvelope answer) {
		Object validation = answer.getPayload().get("answerValidation");
		if (!(validation instanceof Map)) {
			return false;
		}
		Object status = ((Map<?, ?>) validation).get("status");
		return "checked".equals(status) || "edited".equals(status);
	}

	private static boolean isStoredChecked(ClinicalConversationTurn turn) {
		if (turn.getProviderPayload() == null) {
			return false;
		}
		try {
			JsonNode status = MAPPER.readTree(turn.getProviderPayload()).path("answerValidation")
					.path("status");
			return "checked".equals(status.asText()) || "edited".equals(status.asText());
		}
		catch (IOException ignored) {
			return false;
		}
	}

	private static boolean isStoredAnswerReplayable(ClinicalConversationTurn turn) {
		if (turn.getProviderPayload() == null) {
			return true;
		}
		try {
			JsonNode validation = MAPPER.readTree(turn.getProviderPayload()).path("answerValidation");
			if (validation.isMissingNode() || validation.isNull()) {
				return true;
			}
			String status = validation.path("status").asText();
			return "checked".equals(status) || "edited".equals(status);
		}
		catch (IOException ignored) {
			return false;
		}
	}

	private static String serialize(Map<String, Object> payload) {
		try {
			return MAPPER.writeValueAsString(payload);
		}
		catch (IOException e) {
			throw new APIException("Could not serialize provider answer payload", e);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
