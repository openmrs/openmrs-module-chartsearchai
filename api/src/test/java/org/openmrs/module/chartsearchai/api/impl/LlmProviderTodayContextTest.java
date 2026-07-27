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
import java.util.Date;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The prompt must tell the model what day it is.
 *
 * <p>Every record carries an absolute {@code (yyyy-MM-dd)} date, but nothing in the prompt said
 * what "now" is — so a question whose answer depends on the current date ("seen recently?",
 * "still on treatment?", "anything this year?", "how long ago?") could only be answered by
 * guessing today from the newest record or from the model's training cutoff. This fixture pins
 * both the content and the PLACEMENT of the date line.
 *
 * <p>Placement is a hard constraint, not a style choice: the date changes daily, and llama-server's
 * KV cache is keyed on the question-independent prefix {@code buildUserMessage(records, "")}. The
 * date therefore has to live AFTER the question, in the per-request tail — anywhere earlier and
 * every patient's persisted KV entry (and the pinned prewarm corpus) would be invalidated once a
 * day.
 */
public class LlmProviderTodayContextTest {

	private static final String CHART = "[1] (2025-01-15) Clinical observation: BP 120/80\n"
			+ "[2] (2025-01-10) Diagnosis: Hypertension";

	@Test
	public void buildUserMessage_shouldAppendTodaysDateAfterTheQuestion() {
		String message = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"Has she been seen recently?", "2026-07-27");

		assertTrue(message.contains(LlmProvider.TODAY_LABEL + "2026-07-27"),
				"the prompt must carry today's date: " + message);
		assertTrue(message.indexOf("Clinician's query:") < message.indexOf(LlmProvider.TODAY_LABEL),
				"the date line must come AFTER the query so the warmup/KV byte-prefix survives: "
						+ message);
	}

	@Test
	public void buildUserMessage_withTodayShouldStillStartWithTheWarmupPrefix() {
		// The whole point of the trailing placement: warmup (and the KV cache seed) send
		// buildUserMessage(records, "") and must remain a byte-prefix of the real query, or
		// llama-server re-prefills the entire chart on every query.
		String warmup = LlmProvider.buildUserMessage(CHART, "");
		String query = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"Has she been seen recently?", "2026-07-27");
		assertTrue(query.startsWith(warmup),
				"adding the date must not break the warmup byte-prefix contract.\n"
						+ "  warmup: " + warmup + "\n  query:  " + query);
	}

	@Test
	public void buildUserMessage_withoutTodayShouldBeByteIdenticalToTheThreeArgForm() {
		// The warmup / KV-seed path passes no date, so its bytes — and therefore every existing
		// on-disk KV filename — are provably unchanged by this feature.
		String withoutDate = LlmProvider.buildUserMessage(CHART, Arrays.asList(1),
				"Has she been seen recently?", null);
		String threeArg = LlmProvider.buildUserMessage(CHART, Arrays.asList(1),
				"Has she been seen recently?");
		assertEquals(threeArg, withoutDate);
		assertEquals(threeArg, LlmProvider.buildUserMessage(CHART, Arrays.asList(1),
				"Has she been seen recently?", "   "), "a blank date must also be a no-op");
	}

	@Test
	public void searchStreaming_shouldSendTodaysDateToTheEngine() {
		// Wiring test: the answer path must actually pass the date. Building the message correctly
		// is useless if search()/searchStreaming() call the date-free overload.
		CapturingEngineProvider provider = new CapturingEngineProvider();
		provider.searchStreaming(CHART, Collections.<Integer>emptyList(), "Has she been seen recently?",
				token -> { }, chunk -> { }, null);
		assertTrue(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"searchStreaming must include the date line: " + provider.engine.userMessage);
		assertTrue(provider.engine.userMessage.contains(DateFormatUtil.today()),
				"…and it must be TODAY: " + provider.engine.userMessage);
	}

	@Test
	public void search_shouldSendTodaysDateToTheEngine() {
		CapturingEngineProvider provider = new CapturingEngineProvider();
		provider.search(CHART, Collections.<Integer>emptyList(), "Has she been seen recently?");
		assertTrue(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"search must include the date line: " + provider.engine.userMessage);
	}

	@Test
	public void warmup_shouldNotSendTodaysDate() {
		// A warmup that carried the date would change the KV filename daily and orphan every
		// patient's persisted prefill (pinned prewarm corpus included).
		CapturingEngineProvider provider = new CapturingEngineProvider();
		provider.warmup(CHART);
		assertFalse(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"warmup must stay date-free: " + provider.engine.userMessage);
	}

	@Test
	public void today_shouldRenderInTheSameFormatAsEveryRecordDate() {
		// The model compares the date line against record dates, so both must be rendered by the
		// one formatter — a different shape ("27 Jul 2026") would make the comparison guesswork.
		assertEquals(DateFormatUtil.formatDate(new Date()), DateFormatUtil.today());
		assertTrue(DateFormatUtil.today().matches("\\d{4}-\\d{2}-\\d{2}"), DateFormatUtil.today());
	}

	@Test
	public void systemPrompt_shouldTellTheModelWhereTodaysDateIs() {
		// Without this the small model has a date it was never told to use.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains(LlmProvider.TODAY_LABEL.trim()),
				"the system prompt must name the date line by its exact label so the model can "
						+ "find it: " + LlmProvider.DEFAULT_SYSTEM_PROMPT);
	}

	/** An {@link LlmProvider} whose engine, system prompt and timeout need no OpenMRS context,
	 *  so the real {@code search}/{@code searchStreaming}/{@code warmup} code paths run and the
	 *  user message they build can be inspected. */
	private static final class CapturingEngineProvider extends LlmProvider {

		private final CapturingEngine engine = new CapturingEngine();

		@Override
		LlmEngine getActiveEngine() {
			return engine;
		}

		@Override
		protected String getSystemPrompt() {
			return DEFAULT_SYSTEM_PROMPT;
		}

		@Override
		protected int getTimeoutSeconds() {
			return 30;
		}
	}

	private static final class CapturingEngine implements LlmEngine {

		private String userMessage = "";

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			this.userMessage = userMessage;
			return new InferenceResult("{\"reasoning\":\"\",\"answer\":\"ok\",\"citations\":[]}", 0, 0);
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ObjectNode responseFormat) {
			return infer(systemPrompt, userMessage, timeoutSeconds);
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer) {
			this.userMessage = userMessage;
			return new InferenceResult("{\"reasoning\":\"\",\"answer\":\"ok\",\"citations\":[]}", 0, 0);
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
			return inferStreaming(systemPrompt, userMessage, timeoutSeconds, tokenConsumer);
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
			this.userMessage = userMessage;
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds,
				String cacheScope, boolean pin) {
			warmup(systemPrompt, userMessage, timeoutSeconds);
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
		}
	}
}
