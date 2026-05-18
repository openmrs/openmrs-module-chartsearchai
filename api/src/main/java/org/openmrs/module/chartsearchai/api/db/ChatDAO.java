/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.db;

import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;

/**
 * Persistence for multi-turn chat sessions and messages. Audit log entries
 * (regulatory grain) live in {@link ChartSearchAiDAO}.
 */
public interface ChatDAO {

	ChatSession saveSession(ChatSession session);

	ChatSession getSession(Integer sessionId);

	ChatSession getSessionByUuid(String uuid);

	/**
	 * Returns the most-recently-active session for the (patient, user) pair, or
	 * null if no such session exists. Used for "open chart → reuse latest thread"
	 * lookup.
	 */
	ChatSession getLatestSession(Patient patient, User user);

	ChatMessage saveMessage(ChatMessage message);

	/**
	 * List all non-summary messages for a session in ascending ordinal order.
	 * This is the canonical "rebuild conversation history for the next LLM call"
	 * query.
	 */
	List<ChatMessage> getMessages(ChatSession session);

	/**
	 * Largest ordinal in the session, or -1 if the session has no messages.
	 * Callers use {@code lastOrdinal + 1} when persisting the next turn.
	 */
	int getLastOrdinal(ChatSession session);

	/**
	 * Delete chat_message rows older than the given date, and any chat_session
	 * rows whose newest message is older than the same horizon (orphaned headers).
	 * Returns total rows deleted across both tables.
	 */
	int purgeBefore(Date before);
}
