/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.List;
import java.util.Map;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;

/**
 * Multi-turn chat orchestration: session lifecycle plus per-turn LLM call
 * + persistence. Patient-scoped authorization is delegated to the caller
 * (the REST controller).
 */
public interface ChatService {

	/**
	 * Returns the most-recently-active session for the (patient,
	 * currently-authenticated user) pair, creating one if none exists.
	 * Used by REST POST {@code /chat} when the client omits {@code session}.
	 */
	ChatSession openOrLoadActiveSession(Patient patient);

	/**
	 * Loads a session by its client-visible uuid. Returns null when the uuid
	 * is unknown — the controller is expected to fall back to
	 * {@link #openOrLoadActiveSession} in that case.
	 */
	ChatSession loadByUuid(String uuid);

	/**
	 * Closes the patient's current active session (if any) and opens a fresh
	 * one for the authenticated user. Used by REST POST {@code /chat/new}.
	 */
	ChatSession closeAndStartNew(Patient patient);

	/**
	 * Returns prior chat messages for a session in chronological order,
	 * excluding summary rows.
	 */
	List<ChatMessage> getMessages(ChatSession session);

	/**
	 * Hub-relay staged Answer leg: persist the user message and the hub-provided assistant wire
	 * payload without re-running local chart serialization or Java-side grounding.
	 *
	 * @param responseTimeMs wall-clock milliseconds the caller spent on the hub round-trip that
	 *            produced this answer (request sent to answer received) — recorded on the audit row.
	 */
	ChatTurnResult persistHubStagedAnswer(ChatSession session, String question,
			Map<String, Object> answerWire, long responseTimeMs);

	/**
	 * Hub-relay staged update: merge a later hub phase payload into the same assistant row.
	 */
	ChatTurnResult updateHubStagedMessage(ChatSession session, String assistantMessageUuid,
			Map<String, Object> updateWire);

	/**
	 * Prior conversation turns for a hub-relay request: chronological, non-summary rows with
	 * assistant content reduced to prose (never the raw stored JSON envelope) — the shape the
	 * hub's messages array should see. Excludes the current turn; the caller appends that.
	 */
	List<ChatMessage> priorTurnsForRelay(ChatSession session);

	/**
	 * Persistence identifiers returned to the relay. The authoritative answer
	 * remains the hub wire supplied to the persistence method.
	 */
	class ChatTurnResult {

		private final String sessionUuid;

		private final String assistantMessageUuid;

		private final Integer auditLogId;

		public ChatTurnResult(String sessionUuid, String assistantMessageUuid) {
			this(sessionUuid, assistantMessageUuid, null);
		}

		public ChatTurnResult(String sessionUuid, String assistantMessageUuid, Integer auditLogId) {
			this.sessionUuid = sessionUuid;
			this.assistantMessageUuid = assistantMessageUuid;
			this.auditLogId = auditLogId;
		}

		public String getSessionUuid() {
			return sessionUuid;
		}

		public String getAssistantMessageUuid() {
			return assistantMessageUuid;
		}

		public Integer getAuditLogId() {
			return auditLogId;
		}
	}
}
