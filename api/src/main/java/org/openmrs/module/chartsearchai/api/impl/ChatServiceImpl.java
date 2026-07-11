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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("chartSearchAi.chatService")
@Transactional
public class ChatServiceImpl implements ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

	private static final String SEARCH_MODE_CHAT = "chat";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private ChatDAO chatDAO;

	@Autowired
	private ChartSearchAiDAO auditDAO;

	@Override
	public ChatSession openOrLoadActiveSession(Patient patient) {
		User user = Context.getAuthenticatedUser();
		ChatSession existing = chatDAO.getLatestSession(patient, user);
		if (existing != null) {
			return existing;
		}
		return createSession(patient, user);
	}

	@Override
	public ChatSession loadByUuid(String uuid) {
		if (uuid == null || uuid.isEmpty()) {
			return null;
		}
		return chatDAO.getSessionByUuid(uuid);
	}

	@Override
	public ChatSession closeAndStartNew(Patient patient) {
		User user = Context.getAuthenticatedUser();
		ChatSession existing = chatDAO.getLatestSession(patient, user);
		if (existing != null) {
			existing.setStatus(ChatSession.STATUS_CLOSED);
			existing.setEndedAt(new Date());
			chatDAO.saveSession(existing);
		}
		return createSession(patient, user);
	}

	@Override
	public List<ChatMessage> getMessages(ChatSession session) {
		return chatDAO.getMessages(session);
	}

	@Override
	public ChatTurnResult persistHubStagedAnswer(ChatSession session, String question,
			Map<String, Object> answerWire, long responseTimeMs) {
		int nextOrdinal = chatDAO.getLastOrdinal(session) + 1;
		persistUserMessage(session, question, nextOrdinal);

		Map<String, Object> wire = normalizedHubWire(answerWire);
		ChatMessage assistant = persistAssistantWireTurn(session, wire, nextOrdinal + 1,
				ChatMessage.FINISH_STOP, question, responseTimeMs);
		touchSession(session);
		return new ChatTurnResult(session.getUuid(), assistant.getUuid(),
				assistant.getAuditLog().getAuditLogId());
	}

	@Override
	public ChatTurnResult updateHubStagedMessage(ChatSession session, String assistantMessageUuid,
			Map<String, Object> updateWire) {
		ChatMessage assistant = chatDAO.getMessageByUuid(assistantMessageUuid);
		requireAssistantInSession(session, assistant, "Hub staged update");

		Map<String, Object> merged = assistantWire(assistant.getContent());
		if (updateWire != null) {
			merged.putAll(updateWire);
		}
		saveAssistantWire(assistant, merged, "hub staged update");
		updateAuditFromWire(assistant.getAuditLog(), merged);
		touchSession(session);
		return new ChatTurnResult(session.getUuid(), assistant.getUuid(),
				assistant.getAuditLog() == null ? null : assistant.getAuditLog().getAuditLogId());
	}

	@Override
	public List<ChatMessage> priorTurnsForRelay(ChatSession session) {
		return priorsForLlm(chatDAO.getMessages(session));
	}

	protected ChatSession createSession(Patient patient, User user) {
		ChatSession session = new ChatSession();
		session.setPatient(patient);
		session.setUser(user);
		Date now = new Date();
		session.setStartedAt(now);
		session.setLastActivityAt(now);
		session.setStatus(ChatSession.STATUS_ACTIVE);
		return chatDAO.saveSession(session);
	}

	protected ChatMessage persistUserMessage(ChatSession session, String content, int ordinal) {
		ChatMessage msg = new ChatMessage();
		msg.setSession(session);
		msg.setOrdinal(ordinal);
		msg.setRole(ChatMessage.ROLE_USER);
		msg.setContent(content);
		msg.setCreatedAt(new Date());
		return chatDAO.saveMessage(msg);
	}

	private ChatMessage persistAssistantWireTurn(ChatSession session, Map<String, Object> wire,
			int ordinal, String finishReason, String questionForAudit,
			long responseTimeMs) {
		ChartSearchAuditLog audit = buildAuditRow(session, questionForAudit, wire, responseTimeMs);
		auditDAO.saveAuditLog(audit);

		ChatMessage msg = new ChatMessage();
		msg.setSession(session);
		msg.setOrdinal(ordinal);
		msg.setRole(ChatMessage.ROLE_ASSISTANT);
		msg.setContent(serializeWire(wire, "hub staged answer"));
		msg.setCreatedAt(new Date());
		msg.setAuditLog(audit);
		msg.setInputTokens(0);
		msg.setOutputTokens(0);
		msg.setFinishReason(finishReason);
		return chatDAO.saveMessage(msg);
	}

	private static String serializeWire(Map<String, Object> wire, String context) {
		try {
			return MAPPER.writeValueAsString(wire);
		}
		catch (IOException ioe) {
			throw new APIException("Failed to serialize " + context + ": " + ioe.getMessage(), ioe);
		}
	}

	private static Map<String, Object> normalizedHubWire(Map<String, Object> wire) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (wire != null) {
			out.putAll(wire);
		}
		if (!out.containsKey("answer")) {
			out.put("answer", "");
		}
		if (!out.containsKey("references")) {
			out.put("references", Collections.emptyList());
		}
		if (!out.containsKey("blocks")) {
			out.put("blocks", Collections.emptyList());
		}
		return out;
	}

	private ChatMessage saveAssistantWire(ChatMessage assistant, Map<String, Object> wire,
			String context) {
		try {
			assistant.setContent(MAPPER.writeValueAsString(wire));
		}
		catch (IOException ioe) {
			throw new APIException("Failed to serialize " + context + ": " + ioe.getMessage(), ioe);
		}
		return chatDAO.saveMessage(assistant);
	}

	private static Map<String, Object> assistantWire(String stored) {
		Map<String, Object> wire = new LinkedHashMap<>();
		if (stored != null && stored.trim().startsWith("{")) {
			try {
				wire.putAll(MAPPER.readValue(stored,
						new TypeReference<Map<String, Object>>() {}));
			}
			catch (IOException ignored) {
				wire.clear();
				wire.put("answer", stored);
				wire.put("blocks", Collections.emptyList());
			}
		} else {
			wire.put("answer", stored == null ? "" : stored);
			wire.put("blocks", Collections.emptyList());
		}
		if (!wire.containsKey("blocks")) {
			wire.put("blocks", Collections.emptyList());
		}
		if (!wire.containsKey("references")) {
			wire.put("references", Collections.emptyList());
		}
		return wire;
	}

	private void requireAssistantInSession(ChatSession session, ChatMessage assistant, String operation) {
		requireOk(assistant != null, "Assistant message not found");
		requireOk(ChatMessage.ROLE_ASSISTANT.equals(assistant.getRole()),
				operation + " can only be attached to an assistant message");
		requireOk(assistant.getSession() != null
				&& session.getSessionId() != null
				&& session.getSessionId().equals(assistant.getSession().getSessionId()),
				"Assistant message does not belong to this chat session");
	}

	/**
	 * Project the persisted prior turns into the shape the LLM should see.
	 * For assistant rows, replaces the stored JSON envelope with just the
	 * prose answer (see {@link #extractProseAnswer}). User rows pass
	 * through unchanged. Returns new transient {@link ChatMessage}
	 * instances rather than mutating the Hibernate-managed entities — the
	 * loaded rows are still in the session and a content change would
	 * persist on flush.
	 */
	private static List<ChatMessage> priorsForLlm(List<ChatMessage> raw) {
		List<ChatMessage> out = new ArrayList<>(raw.size());
		for (ChatMessage m : raw) {
			ChatMessage view = new ChatMessage();
			view.setRole(m.getRole());
			view.setContent(ChatMessage.ROLE_ASSISTANT.equals(m.getRole())
					? extractProseAnswer(m.getContent())
					: m.getContent());
			view.setOrdinal(m.getOrdinal());
			out.add(view);
		}
		return out;
	}

	static String extractProseAnswer(String storedContent) {
		if (storedContent == null || storedContent.isEmpty()) {
			return storedContent;
		}
		String trimmed = storedContent.trim();
		if (!trimmed.startsWith("{")) {
			return storedContent;
		}
		try {
			com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(trimmed);
			com.fasterxml.jackson.databind.JsonNode answer = root.get("answer");
			if (answer != null && answer.isTextual()) {
				return answer.asText();
			}
		}
		catch (IOException ignored) {
			// Not JSON or malformed — treat as plaintext.
		}
		return storedContent;
	}

	protected ChatSession touchSession(ChatSession session) {
		session.setLastActivityAt(new Date());
		return chatDAO.saveSession(session);
	}

	private ChartSearchAuditLog buildAuditRow(ChatSession session, String question,
			Map<String, Object> wire, long responseTimeMs) {
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setUser(session.getUser());
		audit.setPatient(session.getPatient());
		audit.setQuestion(question);
		applyWireToAudit(audit, wire);
		audit.setSearchMode(SEARCH_MODE_CHAT);
		audit.setResponseTimeMs(responseTimeMs);
		audit.setInputTokens(0);
		audit.setOutputTokens(0);
		audit.setDateCreated(new Date());
		return audit;
	}

	private void updateAuditFromWire(ChartSearchAuditLog audit, Map<String, Object> wire) {
		if (audit == null) {
			return;
		}
		applyWireToAudit(audit, wire);
		auditDAO.saveAuditLog(audit);
	}

	private static void applyWireToAudit(ChartSearchAuditLog audit, Map<String, Object> wire) {
		Object answer = wire == null ? null : wire.get("answer");
		audit.setAnswer(answer == null ? "" : String.valueOf(answer));
		Object references = wire == null ? null : wire.get("references");
		audit.setReferenceCount(references instanceof List ? ((List<?>) references).size() : 0);
	}

	/**
	 * Retention horizon for chat content rows. The {@link AuditLogPurgeTask}
	 * reads this to drive purgeBefore.
	 */
	public static int getChatRetentionDays() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_CHAT_RETENTION_DAYS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid {} value '{}', using default",
						ChartSearchAiConstants.GP_CHAT_RETENTION_DAYS, value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_CHAT_RETENTION_DAYS;
	}

	void requireOk(boolean ok, String msg) {
		if (!ok) {
			throw new APIException(msg);
		}
	}
}
