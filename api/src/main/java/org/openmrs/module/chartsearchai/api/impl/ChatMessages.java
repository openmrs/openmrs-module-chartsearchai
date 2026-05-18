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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.module.chartsearchai.model.ChatMessage;

final class ChatMessages {

	private ChatMessages() {
	}

	static ArrayNode systemAndUser(ObjectMapper mapper, String system, String user) {
		ArrayNode messages = mapper.createArrayNode();
		appendMessage(messages, mapper, "system", system);
		appendMessage(messages, mapper, "user", user);
		return messages;
	}

	/**
	 * Assemble a multi-turn OpenAI-compatible {@code messages[]} array with
	 * the patient chart sitting in the <b>stable prefix position</b>:
	 *
	 * <pre>
	 *   [system, user(chart), ...prior user/assistant pairs (no chart), user(current question)]
	 * </pre>
	 *
	 * <p>This is the cache-friendly shape per Anthropic / OpenAI / llama.cpp
	 * prompt-caching docs: static content at the top, variable content at
	 * the end. Provided the caller passes a byte-identical {@code chartText}
	 * across all turns of a session ({@code chat_session.chart_snapshot}),
	 * the LLM server's prompt cache will skip re-processing the entire
	 * chart on follow-ups — only the new question and the (small) prior
	 * conversation tail get processed fresh.
	 *
	 * <p>Trim path: priors are walked newest-backward and dropped in
	 * user/assistant pairs only when the conversation tail would push past
	 * the budget. Chart and system are reserved up front; a response budget
	 * is also reserved so the LLM has room to generate. Token estimation is
	 * chars/4 — adequate for the budget envelope, since under this design
	 * the trim path is exercised only by very long conversations.
	 */
	static ArrayNode assembleChat(ObjectMapper mapper, String system, String chartText,
			List<ChatMessage> priorTurns, String currentQuestion, int maxContextTokens,
			int responseReserveTokens) {
		ArrayNode messages = mapper.createArrayNode();
		appendMessage(messages, mapper, "system", system);
		appendMessage(messages, mapper, "user", chartText);

		int reserved = estimateTokens(system) + estimateTokens(chartText)
				+ estimateTokens(currentQuestion) + Math.max(0, responseReserveTokens);
		int remaining = maxContextTokens - reserved;

		List<ChatMessage> kept = new ArrayList<>();
		int i = priorTurns == null ? -1 : priorTurns.size() - 1;
		while (i >= 0 && remaining > 0) {
			ChatMessage last = priorTurns.get(i);
			ChatMessage secondLast = i >= 1 ? priorTurns.get(i - 1) : null;

			int pairCost;
			int step;
			if (secondLast != null
					&& ChatMessage.ROLE_ASSISTANT.equals(last.getRole())
					&& ChatMessage.ROLE_USER.equals(secondLast.getRole())) {
				pairCost = estimateTokens(last.getContent()) + estimateTokens(secondLast.getContent());
				step = 2;
			} else {
				pairCost = estimateTokens(last.getContent());
				step = 1;
			}

			if (pairCost > remaining) {
				break;
			}

			kept.add(last);
			if (step == 2) {
				kept.add(secondLast);
			}
			i -= step;
			remaining -= pairCost;
		}

		Collections.reverse(kept);
		for (ChatMessage m : kept) {
			appendMessage(messages, mapper, m.getRole(), m.getContent());
		}
		appendMessage(messages, mapper, "user", currentQuestion);
		return messages;
	}

	private static void appendMessage(ArrayNode messages, ObjectMapper mapper, String role, String content) {
		ObjectNode node = mapper.createObjectNode();
		node.put("role", role);
		node.put("content", content);
		messages.add(node);
	}

	private static int estimateTokens(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		return (int) Math.ceil(s.length() / 4.0);
	}
}
