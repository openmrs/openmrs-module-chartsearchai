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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
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
	private LlmInferenceService llmInferenceService;

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
	public ChatTurnResult chat(ChatSession session, String question) {
		ensureChartSnapshot(session);
		String chartEnvelope = session.getChartSnapshot();
		List<RecordMapping> mappings = deserializeMappings(session.getChartMappingsJson());

		List<ChatMessage> priorTurns = priorsForLlm(chatDAO.getMessages(session));
		int nextOrdinal = chatDAO.getLastOrdinal(session) + 1;
		persistUserMessage(session, question, nextOrdinal);

		long startedMs = System.currentTimeMillis();
		ChartAnswer answer;
		try {
			answer = llmInferenceService.chat(chartEnvelope, mappings, priorTurns, question);
		}
		catch (RuntimeException e) {
			touchSession(session);
			throw e;
		}
		long elapsedMs = System.currentTimeMillis() - startedMs;

		ChatMessage assistant = persistAssistantTurn(session, answer, nextOrdinal + 1,
				ChatMessage.FINISH_STOP, question, elapsedMs);
		touchSession(session);

		return new ChatTurnResult(answer, session.getUuid(), assistant.getUuid());
	}

	@Override
	public ChatTurnResult chatStreaming(ChatSession session, String question,
			Consumer<String> tokenConsumer) {
		ensureChartSnapshot(session);
		String chartEnvelope = session.getChartSnapshot();
		List<RecordMapping> mappings = deserializeMappings(session.getChartMappingsJson());

		List<ChatMessage> priorTurns = priorsForLlm(chatDAO.getMessages(session));
		int nextOrdinal = chatDAO.getLastOrdinal(session) + 1;
		persistUserMessage(session, question, nextOrdinal);

		long startedMs = System.currentTimeMillis();
		ChartAnswer answer;
		String finishReason = ChatMessage.FINISH_STOP;
		try {
			answer = llmInferenceService.chatStreaming(
					chartEnvelope, mappings, priorTurns, question, tokenConsumer);
		}
		catch (RuntimeException e) {
			finishReason = ChatMessage.FINISH_ABORTED;
			touchSession(session);
			throw e;
		}
		long elapsedMs = System.currentTimeMillis() - startedMs;

		ChatMessage assistant = persistAssistantTurn(session, answer, nextOrdinal + 1,
				finishReason, question, elapsedMs);
		touchSession(session);

		return new ChatTurnResult(answer, session.getUuid(), assistant.getUuid());
	}

	protected ChatSession createSession(Patient patient, User user) {
		ChatSession session = new ChatSession();
		session.setPatient(patient);
		session.setUser(user);
		Date now = new Date();
		session.setStartedAt(now);
		session.setLastActivityAt(now);
		session.setStatus(ChatSession.STATUS_ACTIVE);
		populateChartSnapshot(session, patient);
		return chatDAO.saveSession(session);
	}

	/**
	 * Backfill chart snapshot for legacy sessions (created before
	 * chartsearchai-008) on first chat() call. Idempotent.
	 */
	protected void ensureChartSnapshot(ChatSession session) {
		if (session.getChartSnapshot() != null) {
			return;
		}
		populateChartSnapshot(session, session.getPatient());
		chatDAO.saveSession(session);
	}

	/**
	 * Build the full chart for the session's patient (bypassing pre-filter)
	 * and store envelope + mappings on the session row. The byte-stability
	 * of envelope across all turns is the load-bearing invariant of the
	 * chat design — the LLM's prompt cache hits on this prefix.
	 */
	protected void populateChartSnapshot(ChatSession session, Patient patient) {
		PatientChart chart = llmInferenceService.buildSessionChart(patient);
		session.setChartSnapshot(chart.getText());
		session.setChartMappingsJson(serializeMappings(chart.getMappings()));
		session.setChartBuiltAt(new Date());
	}

	/**
	 * Serialize {@link RecordMapping} list as JSON with epoch-ms dates so
	 * the round-trip is locale-free and deterministic. {@code RecordMapping}
	 * lacks a no-arg ctor so we ser/des via plain Map.
	 */
	private static String serializeMappings(List<RecordMapping> mappings) {
		if (mappings == null || mappings.isEmpty()) {
			return "[]";
		}
		List<Map<String, Object>> wire = new ArrayList<>(mappings.size());
		for (RecordMapping m : mappings) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("index", m.getIndex());
			e.put("resourceType", m.getResourceType());
			e.put("resourceUuid", m.getResourceUuid());
			e.put("date", m.getDate() == null ? null : m.getDate().getTime());
			wire.add(e);
		}
		try {
			return MAPPER.writeValueAsString(wire);
		}
		catch (IOException ioe) {
			throw new APIException("Failed to serialize chart mappings: " + ioe.getMessage(), ioe);
		}
	}

	static List<RecordMapping> deserializeMappings(String json) {
		if (json == null || json.isEmpty() || "[]".equals(json)) {
			return Collections.emptyList();
		}
		try {
			List<Map<String, Object>> wire = MAPPER.readValue(
					json, new TypeReference<List<Map<String, Object>>>() {});
			List<RecordMapping> out = new ArrayList<>(wire.size());
			for (Map<String, Object> e : wire) {
				int index = ((Number) e.get("index")).intValue();
				String type = (String) e.get("resourceType");
				String uuid = (String) e.get("resourceUuid");
				Number dateMs = (Number) e.get("date");
				Date d = dateMs == null ? null : new Date(dateMs.longValue());
				out.add(new RecordMapping(index, type, uuid, d));
			}
			return out;
		}
		catch (IOException ioe) {
			throw new APIException("Failed to deserialize chart mappings: " + ioe.getMessage(), ioe);
		}
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

	protected ChatMessage persistAssistantTurn(ChatSession session, ChartAnswer answer, int ordinal,
			String finishReason, String questionForAudit, long responseTimeMs) {
		ChartSearchAuditLog audit = buildAuditRow(session, questionForAudit, answer, responseTimeMs);
		auditDAO.saveAuditLog(audit);

		ChatMessage msg = new ChatMessage();
		msg.setSession(session);
		msg.setOrdinal(ordinal);
		msg.setRole(ChatMessage.ROLE_ASSISTANT);
		msg.setContent(serializeAssistantContent(answer));
		msg.setCreatedAt(new Date());
		msg.setAuditLog(audit);
		msg.setInputTokens(answer.getInputTokens());
		msg.setOutputTokens(answer.getOutputTokens());
		msg.setFinishReason(finishReason);
		return chatDAO.saveMessage(msg);
	}

	/**
	 * Serialize the assistant response (prose + citations + blocks) to JSON
	 * for storage on {@code chat_message.content}. The SPA hydration parses
	 * this JSON back into {@code {answer, blocks}}; LLM replay extracts just
	 * the prose answer via {@link #extractProseAnswer}.
	 *
	 * <p>When blocks is empty, we still store JSON (not plaintext) so the
	 * hydration parser only has to handle one canonical shape on newly-
	 * created rows. Legacy plaintext rows from before this iteration are
	 * detected and handled separately in {@link #extractProseAnswer}.
	 */
	private static String serializeAssistantContent(ChartAnswer answer) {
		Map<String, Object> wire = new LinkedHashMap<>();
		wire.put("answer", answer.getAnswer());
		// blocks rendered via the same helper used by the REST controller —
		// keep wire format identical across persistence and live response so
		// SPA hydration and SSE done events parse the same way.
		wire.put("blocks", blocksToWire(answer.getBlocks()));
		try {
			return MAPPER.writeValueAsString(wire);
		}
		catch (IOException ioe) {
			// Fail soft: if serialization breaks, fall back to plain prose so
			// chat continues to function (just without blocks rendering on
			// hydration). The thrown error would otherwise abort the entire
			// assistant-message persistence and lose the answer.
			log.warn("Failed to serialize assistant content to JSON; storing prose only: {}",
					ioe.getMessage());
			return answer.getAnswer();
		}
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
	 *   <li>Legacy: plain string (rows persisted before this iteration)</li>
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
