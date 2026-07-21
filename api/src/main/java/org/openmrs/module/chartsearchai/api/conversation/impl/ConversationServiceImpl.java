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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.conversation.ConversationDAO;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
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
	private ChartSearchAiDAO auditDAO;

	@Override
	public ClinicalConversation openOrCreate(Patient patient, String providerId, ProviderMode mode) {
		require(patient != null, "patient is required");
		require(providerId != null && !providerId.trim().isEmpty(), "providerId is required");
		User user = Context.getAuthenticatedUser();
		require(user != null, "an authenticated user is required");

		String modeName = mode == null ? null : mode.getWireName();
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
		require(ClinicalConversation.STATUS_ACTIVE.equals(conversation.getStatus()),
				"conversation is not active");
		require(requestId != null && !requestId.trim().isEmpty(), "requestId is required");
		require(question != null && !question.trim().isEmpty(), "question is required");

		Date now = new Date();
		ClinicalConversationTurn turn = new ClinicalConversationTurn();
		turn.setConversation(conversation);
		turn.setOrdinal(conversationDAO.getLastOrdinal(conversation) + 1);
		turn.setRequestId(requestId);
		turn.setQuestion(question);
		turn.setStartedAt(now);
		conversation.setLastActivityAt(now);
		conversationDAO.saveConversation(conversation);
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
		auditDAO.saveAuditLog(audit);
		turn.setAuditLog(audit);
		conversation.setLastActivityAt(now);
		conversationDAO.saveConversation(conversation);
		return conversationDAO.saveTurn(turn);
	}

	@Override
	public List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation) {
		List<PriorClinicalTurn> prior = new ArrayList<>();
		for (ClinicalConversationTurn turn : conversationDAO.getTurns(conversation)) {
			if (TurnEventType.TURN_DONE.getWireName().equals(turn.getTerminalState())
					&& turn.getAnswerText() != null) {
				prior.add(new PriorClinicalTurn(turn.getQuestion(), turn.getAnswerText()));
			}
		}
		return prior;
	}

	private ChartSearchAuditLog buildAudit(ClinicalConversationTurn turn, TurnResult result,
			long responseTimeMs, Date now) {
		ClinicalConversation conversation = turn.getConversation();
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setUser(conversation.getUser());
		audit.setPatient(conversation.getPatient());
		audit.setQuestion(turn.getQuestion());
		audit.setAnswer(result.getAnswer() == null ? "" : result.getAnswer().getText());
		audit.setReferenceCount(referenceCount(result.getAnswer()));
		audit.setSearchMode(conversation.getProviderMode() == null
				? conversation.getProviderId() : conversation.getProviderMode());
		audit.setResponseTimeMs(responseTimeMs);
		audit.setInputTokens(number(result.getAnswer(), "inputTokens"));
		audit.setOutputTokens(number(result.getAnswer(), "outputTokens"));
		audit.setProviderId(conversation.getProviderId());
		audit.setProviderMode(conversation.getProviderMode());
		audit.setConversationUuid(conversation.getUuid());
		audit.setRequestId(turn.getRequestId());
		audit.setDateCreated(now);
		return audit;
	}

	private static int referenceCount(AnswerEnvelope answer) {
		if (answer == null) {
			return 0;
		}
		Object references = answer.getPayload().get("references");
		return references instanceof List ? ((List<?>) references).size() : 0;
	}

	private static Integer number(AnswerEnvelope answer, String field) {
		if (answer == null) {
			return null;
		}
		Object value = answer.getPayload().get(field);
		return value instanceof Number ? ((Number) value).intValue() : null;
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
