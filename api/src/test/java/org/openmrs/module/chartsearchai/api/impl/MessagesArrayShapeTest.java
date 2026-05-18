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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.model.ChatMessage;

/**
 * Contract tests for {@link ChatMessages#assembleChat} — the new multi-turn
 * message-array builder that puts the patient chart in a stable prefix
 * position (between the system message and the variable conversation tail).
 *
 * <p>The load-bearing property under test: the first two messages of the
 * produced array are byte-identical across all calls with the same system
 * prompt + chart envelope, regardless of priors or current question. That
 * byte-identity is what makes the LLM server's prompt cache hit on
 * follow-up turns (Anthropic, OpenAI, llama.cpp all converge on this
 * static-at-top / variable-at-end rule). If this regresses, every chat
 * follow-up will re-process the entire chart from scratch (~8s observed
 * on a 32K-context Gemma) and the cache layer is wasted.
 */
public class MessagesArrayShapeTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SYSTEM = "You are a clinical assistant.";

	private static final String CHART = "Patient records (most recent first):\n"
			+ "Patient: 47-year-old Female\n\n"
			+ "[1] (2026-04-05) Diagnosis: Hypertension\n"
			+ "[2] (2026-04-05) Medication: Lisinopril 10mg\n"
			+ "[3] (2026-03-20) Allergy: Penicillin";

	private static final int LARGE_BUDGET = 100_000;

	private static final int RESPONSE_RESERVE = 1024;

	@Test
	public void emptyPriorsShouldProduceSystemChartCurrentUserOnly() {
		ArrayNode out = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, Collections.emptyList(),
				"What meds is she on?", LARGE_BUDGET, RESPONSE_RESERVE);

		assertEquals(3, out.size(),
				"expected [system, user(chart), user(question)]; got " + out);
		assertRole(out, 0, "system", SYSTEM);
		assertRole(out, 1, "user", CHART);
		assertRole(out, 2, "user", "What meds is she on?");
	}

	@Test
	public void priorsShouldSitBetweenChartAndCurrentQuestion() {
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "What meds is she on?"),
				turn(ChatMessage.ROLE_ASSISTANT, "Lisinopril 10mg [2]"));

		ArrayNode out = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, prior,
				"And her allergies?", LARGE_BUDGET, RESPONSE_RESERVE);

		assertEquals(5, out.size(),
				"expected [system, user(chart), user(Q1), assistant(A1), user(Q2)]; got " + out);
		assertRole(out, 0, "system", SYSTEM);
		assertRole(out, 1, "user", CHART);
		assertRole(out, 2, "user", "What meds is she on?");
		assertRole(out, 3, "assistant", "Lisinopril 10mg [2]");
		assertRole(out, 4, "user", "And her allergies?");
	}

	@Test
	public void firstTwoMessagesByteIdenticalAcrossTurns_loadBearingForCacheHits() {
		// Turn 1 — no priors yet
		ArrayNode turn1 = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, Collections.emptyList(),
				"first question", LARGE_BUDGET, RESPONSE_RESERVE);

		// Turn 2 — one prior pair, different question
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "first question"),
				turn(ChatMessage.ROLE_ASSISTANT, "first answer"));
		ArrayNode turn2 = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, prior,
				"second question entirely different", LARGE_BUDGET, RESPONSE_RESERVE);

		// Turn 3 — two prior pairs, a third question
		List<ChatMessage> prior2 = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "first question"),
				turn(ChatMessage.ROLE_ASSISTANT, "first answer"),
				turn(ChatMessage.ROLE_USER, "second question entirely different"),
				turn(ChatMessage.ROLE_ASSISTANT, "second answer"));
		ArrayNode turn3 = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, prior2,
				"third", LARGE_BUDGET, RESPONSE_RESERVE);

		// The cache-eligible prefix: system + chart-user-message. Bytes must
		// match exactly across all three turns; if this fails, the LLM
		// server's prompt cache will never hit.
		String prefix1 = turn1.get(0).toString() + turn1.get(1).toString();
		String prefix2 = turn2.get(0).toString() + turn2.get(1).toString();
		String prefix3 = turn3.get(0).toString() + turn3.get(1).toString();

		assertEquals(prefix1, prefix2,
				"turn 2's [system, chart] prefix must match turn 1's byte-for-byte");
		assertEquals(prefix2, prefix3,
				"turn 3's [system, chart] prefix must match turn 2's byte-for-byte");
	}

	@Test
	public void chartShouldNeverAppearInsidePriorMessages() {
		// Adversarial: a prior message that contains chart-like text. The
		// produced array's index-1 entry must be the CHART parameter, exact.
		// Priors (index 2+) must NOT contain "Lisinopril 10mg" (which is in
		// the chart) UNLESS the prior content itself contains that text.
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "tell me about her diabetes"),
				turn(ChatMessage.ROLE_ASSISTANT, "There is no diabetes diagnosis in the chart."));

		ArrayNode out = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, prior, "what about hypertension?", LARGE_BUDGET, RESPONSE_RESERVE);

		assertEquals(CHART, out.get(1).get("content").asText(),
				"chart envelope must appear verbatim at index 1");

		// Walk priors (index 2..size-2): no chart content
		for (int i = 2; i < out.size() - 1; i++) {
			String content = out.get(i).get("content").asText();
			assertFalse(content.contains("Lisinopril 10mg"),
					"prior at index " + i + " must not contain chart bytes; got: " + content);
			assertFalse(content.startsWith("Patient records"),
					"prior at index " + i + " must not contain chart envelope prefix");
		}
	}

	@Test
	public void systemMessageAlwaysAtIndexZero_evenUnderTightBudget() {
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "long prior question " + "x".repeat(2000)),
				turn(ChatMessage.ROLE_ASSISTANT, "long prior answer " + "y".repeat(2000)));

		// Budget=0 forces every prior to be dropped, but system must survive.
		ArrayNode out = ChatMessages.assembleChat(
				MAPPER, SYSTEM, CHART, prior, "current", 0, 0);

		assertTrue(out.size() >= 3, "at minimum [system, chart, current_user]");
		assertRole(out, 0, "system", SYSTEM);
		assertRole(out, 1, "user", CHART);
		assertRole(out, out.size() - 1, "user", "current");
	}

	@Test
	public void overBudgetShouldDropOldestPairsKeepingNewest() {
		// Build N pairs of small priors plus a small current and a tiny chart;
		// configure budget so only ~2 pairs fit.
		String filler = "x".repeat(200); // ~50 tokens per node
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "OLDEST-user-" + filler),
				turn(ChatMessage.ROLE_ASSISTANT, "OLDEST-assist-" + filler),
				turn(ChatMessage.ROLE_USER, "MID-user-" + filler),
				turn(ChatMessage.ROLE_ASSISTANT, "MID-assist-" + filler),
				turn(ChatMessage.ROLE_USER, "NEWEST-user-" + filler),
				turn(ChatMessage.ROLE_ASSISTANT, "NEWEST-assist-" + filler));

		// Budget: system (~6 tokens, 24 chars) + tiny-chart (~6) + current (~2)
		// + ~120 tokens = enough for 1 pair (~108 tokens). Drops OLDEST and MID.
		String tinyChart = "Patient: test";
		ArrayNode out = ChatMessages.assembleChat(
				MAPPER, SYSTEM, tinyChart, prior, "current", 130, 0);

		String body = out.toString();
		assertTrue(body.contains("NEWEST-user-") && body.contains("NEWEST-assist-"),
				"newest pair must be retained; got " + body);
		assertFalse(body.contains("OLDEST-user-") || body.contains("OLDEST-assist-"),
				"oldest pair must be dropped under budget pressure; got " + body);

		// System at top, chart at index 1, current question at tail
		assertEquals("system", out.get(0).get("role").asText());
		assertEquals(tinyChart, out.get(1).get("content").asText());
		assertEquals("current", out.get(out.size() - 1).get("content").asText());
		assertEquals("user", out.get(out.size() - 1).get("role").asText());
	}

	@Test
	public void responseReserveShouldShrinkAvailablePriorBudget() {
		String pad = "x".repeat(400); // ~100 tokens each
		List<ChatMessage> prior = Arrays.asList(
				turn(ChatMessage.ROLE_USER, "OLDEST " + pad),
				turn(ChatMessage.ROLE_ASSISTANT, "old-ans " + pad),
				turn(ChatMessage.ROLE_USER, "NEWEST " + pad),
				turn(ChatMessage.ROLE_ASSISTANT, "new-ans " + pad));

		// Without reserve: large budget, both pairs fit
		ArrayNode withoutReserve = ChatMessages.assembleChat(
				MAPPER, SYSTEM, "tiny", prior, "q", 1000, 0);

		// With reserve: same budget minus 800 reserved for response → only one pair fits
		ArrayNode withReserve = ChatMessages.assembleChat(
				MAPPER, SYSTEM, "tiny", prior, "q", 1000, 800);

		assertTrue(withoutReserve.size() > withReserve.size(),
				"response reserve must reduce prior count; got "
						+ withoutReserve.size() + " vs " + withReserve.size());
	}

	private static ChatMessage turn(String role, String content) {
		ChatMessage m = new ChatMessage();
		m.setRole(role);
		m.setContent(content);
		return m;
	}

	private static void assertRole(ArrayNode arr, int index, String role, String content) {
		JsonNode node = arr.get(index);
		assertEquals(role, node.get("role").asText(),
				"role mismatch at index " + index + " in " + arr);
		assertEquals(content, node.get("content").asText(),
				"content mismatch at index " + index + " in " + arr);
	}
}
