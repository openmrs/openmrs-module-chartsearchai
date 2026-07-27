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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;

/**
 * The prompt must tell the model what day it is.
 *
 * <p>Every record carries an absolute {@code (yyyy-MM-dd)} date, but nothing in the prompt said
 * what "now" is — so a question whose answer depends on the current date ("seen recently?",
 * "still on treatment?", "anything this year?", "how long ago?") could only be answered by
 * guessing today from the newest record or from the model's training cutoff.
 *
 * <p>This fixture pins the WIRING only — that each answer path reaches the engine with a date and
 * that warmup does not. The message layout and the byte-prefix constraint behind the date's
 * placement belong to {@link LlmProviderUserMessageTest}, which owns the chart fixture and the
 * warmup contract; keeping both here meant two files asserting one invariant, either of which
 * could go stale alone.
 */
public class LlmProviderTodayContextTest {

	/** Deliberately minimal and local: these tests assert only that the answer paths REACH the
	 *  engine with a date, never the message layout — that contract, and the shared chart fixture
	 *  it needs, belong to {@link LlmProviderUserMessageTest}. */
	private static final String CHART = "[1] (2025-01-15) Clinical observation: BP 120/80";

	@Test
	public void searchStreaming_shouldSendTodaysDateToTheEngine() {
		// Wiring test: the answer path must actually pass the date. Building the message correctly
		// is useless if search()/searchStreaming() call the date-free overload.
		CapturingEngineProvider provider = new CapturingEngineProvider();
		String dayBefore = DateFormatUtil.today();
		provider.searchStreaming(CHART, Collections.<Integer>emptyList(), "Has she been seen recently?",
				token -> { }, chunk -> { }, null);
		assertTrue(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"searchStreaming must include the date line: " + provider.engine.userMessage);
		// Read on both sides of the production call so the one run per day that straddles midnight
		// does not fail: either reading is the correct answer for when the message was built.
		assertTrue(provider.engine.userMessage.contains(dayBefore)
				|| provider.engine.userMessage.contains(DateFormatUtil.today()),
				"…and it must be TODAY: " + provider.engine.userMessage);
	}

	@Test
	public void search_shouldSendTodaysDateToTheEngine() {
		CapturingEngineProvider provider = new CapturingEngineProvider();
		String dayBefore = DateFormatUtil.today();
		provider.search(CHART, Collections.<Integer>emptyList(), "Has she been seen recently?");
		assertTrue(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"search must include the date line: " + provider.engine.userMessage);
		// The label alone would pass a search() that sent a hardcoded or newest-record date, which
		// is the mistake this whole change exists to fix — so pin the VALUE here too.
		assertTrue(provider.engine.userMessage.contains(dayBefore)
				|| provider.engine.userMessage.contains(DateFormatUtil.today()),
				"…and it must be TODAY: " + provider.engine.userMessage);
	}

	@Test
	public void nonTemporalQuestionsMustNotCarryTheDateAnchor() {
		// QueryScopeRouter.isTemporal's javadoc — written for Decision 28's RECENCY anchor, on a
		// class this change edits — already records where this goes: "NON-temporal questions get no
		// anchor, which is what keeps recent vitals out of an absent-topic slice where they bait
		// enumeration (measured: a non-temporal 'any heart problems?' cell drifting to 39 vitals
		// citations back when the anchor was unconditional)."
		//
		// An unconditional DATE anchor reproduced that at the prompt level. Measured on the 8
		// absent-topic heart cells, 5 runs each, with the system prompt in every arm already saying
		// "cite nothing after a no-record verdict — do not list vital signs": main violated it in
		// 1 of 40 runs, this branch with the date line in 15 of 40. Removing the sentence that
		// EXPLAINS the date changed nothing (also ~15 of 40) — the bare line is what baits it.
		CapturingEngineProvider provider = new CapturingEngineProvider();
		provider.search(CHART, Collections.<Integer>emptyList(),
				"Does the patient have any heart or cardiac problems?");
		assertFalse(provider.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"a non-temporal question must not carry the date anchor: " + provider.engine.userMessage);

		CapturingEngineProvider streaming = new CapturingEngineProvider();
		streaming.searchStreaming(CHART, Collections.<Integer>emptyList(),
				"Has the patient had any fractures or broken bones?", token -> { }, chunk -> { }, null);
		assertFalse(streaming.engine.userMessage.contains(LlmProvider.TODAY_LABEL),
				"…on the streaming path too: " + streaming.engine.userMessage);
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
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer) {
			// Matches LlmProviderTest's stub: the provider must always take the scope-aware
			// overload, so reaching this one is a regression rather than a fallback.
			throw new UnsupportedOperationException(
					"searchStreaming must call the scope-aware inferStreaming");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
			this.userMessage = userMessage;
			return new InferenceResult("{\"reasoning\":\"\",\"answer\":\"ok\",\"citations\":[]}", 0, 0);
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
			this.userMessage = userMessage;
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
		}
	}
}
