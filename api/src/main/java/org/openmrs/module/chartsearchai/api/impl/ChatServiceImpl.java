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
import java.time.LocalDate;
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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
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
		ChartAnswer answer = chartAnswerFromWire(wire);
		ChatMessage assistant = persistAssistantWireTurn(session, wire, answer, nextOrdinal + 1,
				ChatMessage.FINISH_STOP, question, responseTimeMs);
		touchSession(session);
		return new ChatTurnResult(answer, session.getUuid(), assistant.getUuid());
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
		ChartAnswer answer = chartAnswerFromWire(merged);
		touchSession(session);
		return new ChatTurnResult(answer, session.getUuid(), assistant.getUuid());
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
			ChartAnswer answer, int ordinal, String finishReason, String questionForAudit,
			long responseTimeMs) {
		ChartSearchAuditLog audit = buildAuditRow(session, questionForAudit, answer, responseTimeMs);
		auditDAO.saveAuditLog(audit);

		ChatMessage msg = new ChatMessage();
		msg.setSession(session);
		msg.setOrdinal(ordinal);
		msg.setRole(ChatMessage.ROLE_ASSISTANT);
		msg.setContent(serializeWire(wire, "hub staged answer"));
		msg.setCreatedAt(new Date());
		msg.setAuditLog(audit);
		msg.setInputTokens(answer.getInputTokens());
		msg.setOutputTokens(answer.getOutputTokens());
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

	@SuppressWarnings("unchecked")
	private static ChartAnswer chartAnswerFromWire(Map<String, Object> wire) {
		String answer = wire.get("answer") == null ? "" : String.valueOf(wire.get("answer"));
		List<RecordReference> references = referencesFromWire(wire.get("references"));
		Map<String, Object> confidence = wire.get("confidence") instanceof Map
				? new LinkedHashMap<>((Map<String, Object>) wire.get("confidence"))
				: null;
		Map<String, Object> answerValidation = wire.get("answerValidation") instanceof Map
				? new LinkedHashMap<>((Map<String, Object>) wire.get("answerValidation"))
				: null;
		List<Map<String, Object>> safetyWarnings = safetyWarningsFromWire(wire.get("safetyWarnings"));
		return new ChartAnswer(answer, references, Collections.emptyList(),
				confidence, answerValidation, 0, 0, 0, safetyWarnings);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> safetyWarningsFromWire(Object raw) {
		if (!(raw instanceof List)) {
			return Collections.emptyList();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object item : (List<Object>) raw) {
			if (item instanceof Map) {
				out.add(new LinkedHashMap<>((Map<String, Object>) item));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<RecordReference> referencesFromWire(Object raw) {
		if (!(raw instanceof List)) {
			return Collections.emptyList();
		}
		List<RecordReference> out = new ArrayList<>();
		for (Object item : (List<Object>) raw) {
			if (!(item instanceof Map)) {
				continue;
			}
			Map<String, Object> ref = (Map<String, Object>) item;
			Object index = ref.get("index");
			if (!(index instanceof Number)) {
				continue;
			}
			Boolean grounded = ref.get("grounded") instanceof Boolean
					? (Boolean) ref.get("grounded")
					: null;
			out.add(new RecordReference(
					((Number) index).intValue(),
					ref.get("resourceType") == null ? null : String.valueOf(ref.get("resourceType")),
					ref.get("resourceUuid") == null ? null : String.valueOf(ref.get("resourceUuid")),
					parseWireDate(ref.get("date")),
					grounded));
		}
		return out;
	}

	private static Date parseWireDate(Object raw) {
		if (raw instanceof Number) {
			return new Date(((Number) raw).longValue());
		}
		if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
			return null;
		}
		try {
			return DateFormatUtil.toLegacyDate(LocalDate.parse(((String) raw).trim()));
		}
		catch (RuntimeException ignored) {
			return null;
		}
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

	private static List<Map<String, Object>> referencesToWire(List<RecordReference> references) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (references == null) {
			return out;
		}
		for (RecordReference ref : references) {
			Map<String, Object> refMap = new LinkedHashMap<>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceUuid", ref.getResourceUuid());
			refMap.put("date", ref.getDate() == null ? null : DateFormatUtil.formatDate(ref.getDate()));
			out.add(refMap);
		}
		return out;
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

	private static List<Map<String, Object>> blocksToWire(List<ResponseBlock> blocks) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (blocks == null) {
			return out;
		}
		for (ResponseBlock block : blocks) {
			Map<String, Object> blockMap = new LinkedHashMap<>();
			blockMap.put("kind", block.getKind());
			if (block.getTitle() != null) {
				blockMap.put("title", block.getTitle());
			}
			List<Map<String, Object>> columns = new ArrayList<>();
			for (ResponseBlock.Column c : block.getColumns()) {
				Map<String, Object> col = new LinkedHashMap<>();
				col.put("key", c.getKey());
				col.put("label", c.getLabel());
				columns.add(col);
			}
			blockMap.put("columns", columns);
			List<Map<String, Object>> rows = new ArrayList<>();
			for (ResponseBlock.Row row : block.getRows()) {
				Map<String, Object> cellsMap = new LinkedHashMap<>();
				for (Map.Entry<String, ResponseBlock.Cell> entry : row.getCells().entrySet()) {
					Map<String, Object> cellMap = new LinkedHashMap<>();
					cellMap.put("text", entry.getValue().getText());
					cellMap.put("refs", entry.getValue().getRefs());
					cellsMap.put(entry.getKey(), cellMap);
				}
				Map<String, Object> rowMap = new LinkedHashMap<>();
				rowMap.put("cells", cellsMap);
				rows.add(rowMap);
			}
			blockMap.put("rows", rows);
			out.add(blockMap);
		}
		return out;
	}

	/**
	 * Extract the prose-only answer from a stored assistant message content
	 * for LLM-replay. Handles both shapes:
	 * <ul>
	 *   <li>New: {@code {"answer": "<prose>", "blocks": [...]}}</li>
	 *   <li>Legacy: plain string</li>
	 * </ul>
	 * Sending the JSON envelope to the LLM in prior assistant turns confuses
	 * small models; the prose summary is enough context for follow-ups.
	 */
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
			ChartAnswer answer, long responseTimeMs) {
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setUser(session.getUser());
		audit.setPatient(session.getPatient());
		audit.setQuestion(question);
		audit.setAnswer(answer.getAnswer());
		audit.setReferenceCount(answer.getReferences() == null ? 0 : answer.getReferences().size());
		audit.setSearchMode(SEARCH_MODE_CHAT);
		audit.setResponseTimeMs(responseTimeMs);
		audit.setInputTokens(answer.getInputTokens());
		audit.setOutputTokens(answer.getOutputTokens());
		audit.setDateCreated(new Date());
		return audit;
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
