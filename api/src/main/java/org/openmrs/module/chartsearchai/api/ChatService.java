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
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
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
	 * Synchronous chat turn: persists the user message, calls the LLM,
	 * persists the assistant message + audit row in one transaction, and
	 * returns the ChatAnswer + the session + assistant-message uuids the
	 * controller surfaces to the client.
	 */
	ChatTurnResult chat(ChatSession session, String question);

	/**
	 * Streaming chat turn — same semantics as {@link #chat} but tokens stream
	 * to {@code tokenConsumer}. The assistant row is persisted only when the
	 * upstream stream closes successfully; aborts persist a partial row with
	 * {@code finish_reason='aborted'} so the next request resumes at the
	 * correct ordinal.
	 */
	ChatTurnResult chatStreaming(ChatSession session, String question,
			Consumer<String> tokenConsumer);

	/**
	 * Container for the controller's response: the ChartAnswer (text +
	 * references + tokens), plus the session uuid and the newly-created
	 * assistant message uuid for client-side threading.
	 */
	class ChatTurnResult {

		private final ChartAnswer answer;

		private final String sessionUuid;

		private final String assistantMessageUuid;

		public ChatTurnResult(ChartAnswer answer, String sessionUuid, String assistantMessageUuid) {
			this.answer = answer;
			this.sessionUuid = sessionUuid;
			this.assistantMessageUuid = assistantMessageUuid;
		}

		public ChartAnswer getAnswer() {
			return answer;
		}

		public String getSessionUuid() {
			return sessionUuid;
		}

		public String getAssistantMessageUuid() {
			return assistantMessageUuid;
		}
	}
}
