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

import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Abstraction for LLM inference engines. Implementations handle the actual
 * model invocation (local or remote) while prompt construction and response
 * parsing remain in {@link LlmProvider}.
 */
public interface LlmEngine {

	/**
	 * Run inference and return the full generated text.
	 *
	 * @param systemPrompt the system prompt
	 * @param userMessage the user message (patient records + question)
	 * @param timeoutSeconds maximum wall-clock seconds for the request
	 * @return the inference result containing generated text and input/output token counts
	 */
	InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds);

	/**
	 * Run inference constraining the output to a caller-supplied JSON-schema {@code response_format}
	 * instead of the default chart-answer schema. Used by batch citation grounding to apply a
	 * verdict-only schema (see {@link EntailmentBatchResponseFormat}) that omits the per-call
	 * reasoning the chart-answer schema would force.
	 *
	 * <p>The default ignores {@code responseFormat} and falls back to {@link #infer(String, String,
	 * int)}: an engine that has not opted in simply uses its normal schema, and grounding then reads
	 * a different envelope and degrades each verdict to "could not verify" (Tier-1 fallback) — never
	 * a crash. {@link LocalLlmEngine} and {@link RemoteLlmEngine} override it.
	 *
	 * @param responseFormat an OpenAI-style {@code response_format} node, or {@code null} for the default
	 */
	default InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
			ObjectNode responseFormat) {
		return infer(systemPrompt, userMessage, timeoutSeconds);
	}

	/**
	 * Run inference with streaming, calling the consumer for each token fragment.
	 *
	 * @param systemPrompt the system prompt
	 * @param userMessage the user message (patient records + question)
	 * @param timeoutSeconds maximum wall-clock seconds for the request
	 * @param tokenConsumer called with each token fragment as it is generated
	 * @return the inference result containing the full generated text and input/output token counts
	 */
	InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
			Consumer<String> tokenConsumer);

	/**
	 * As {@link #inferStreaming(String, String, int, Consumer)} but participates in the on-disk KV
	 * cache: an engine that persists prefilled chart KV can RESTORE this patient's chart from disk
	 * (I/O-bound, tens of ms) instead of re-running the full prompt prefill (CPU-bound, tens of
	 * seconds on a GPU-less host) when the in-memory prompt cache is cold for it — and SAVE a fresh
	 * cold prefill so the next visit (even after a server restart) is fast. This closes the gap where
	 * KV restore/save happened only in {@link #warmup}, so a query arriving cold (restart, RAM-cache
	 * overflow, or warmup never fired/finished) re-paid the full prefill even with the KV on disk.
	 *
	 * <p>{@code cacheSeed} is the question-INDEPENDENT prompt prefix (the same bytes a warmup sends:
	 * system + records, no question) used to derive the on-disk filename, so a warmup-saved entry and
	 * a query-saved entry share one file per patient+chart and the question's trailing bytes never
	 * change the key. {@code cacheScope} groups a subject's entries (e.g. the patient UUID). When
	 * either is null, or the engine does not persist KV, this degrades to the plain 4-arg form.
	 *
	 * @param cacheScope a stable per-subject key for grouping persisted entries, or null to disable
	 * @param cacheSeed the question-independent prompt prefix to key the entry on, or null to disable
	 */
	default InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
			Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
		return inferStreaming(systemPrompt, userMessage, timeoutSeconds, tokenConsumer);
	}

	/**
	 * Prime the engine's prompt cache with the given prefix. Implementations that
	 * don't benefit from warmup (e.g. remote APIs that manage their own caching)
	 * should return false from {@link #supportsWarmup} so callers can skip the
	 * upstream chart-serialization cost as well.
	 *
	 * @param systemPrompt the system prompt
	 * @param userMessage the user message (patient records + empty question)
	 * @param timeoutSeconds maximum wall-clock seconds for the request
	 */
	void warmup(String systemPrompt, String userMessage, int timeoutSeconds);

	/**
	 * As {@link #warmup(String, String, int)} but with a {@code cacheScope} (e.g. the patient
	 * UUID) that identifies which on-disk KV-cache entries belong together, so an engine that
	 * persists KV to disk can replace a patient's stale entry when their chart changes instead of
	 * leaving orphans. The default ignores the scope and delegates to the 3-arg form, so engines
	 * that don't persist KV (and existing callers/tests) are unaffected.
	 *
	 * @param cacheScope a stable per-subject key for grouping persisted entries, or null
	 */
	default void warmup(String systemPrompt, String userMessage, int timeoutSeconds, String cacheScope) {
		warmup(systemPrompt, userMessage, timeoutSeconds);
	}

	/**
	 * Whether this engine benefits from a warmup call. Used by callers to decide
	 * whether to pay any pre-warmup work (chart serialization, etc.).
	 */
	default boolean supportsWarmup() {
		return true;
	}

	/**
	 * Release resources (model, connections) but allow re-initialization on next call.
	 */
	void close();

	/**
	 * Permanently shut down background threads and release all resources.
	 * After this call, the engine should not be used again.
	 */
	void shutdown();

	/**
	 * Result of an LLM inference call.
	 */
	class InferenceResult {

		private final String text;

		private final int inputTokens;

		private final int outputTokens;

		private final int cachedTokens;

		public InferenceResult(String text, int inputTokens, int outputTokens) {
			this(text, inputTokens, outputTokens, 0);
		}

		public InferenceResult(String text, int inputTokens, int outputTokens, int cachedTokens) {
			this.text = text;
			this.inputTokens = inputTokens;
			this.outputTokens = outputTokens;
			this.cachedTokens = cachedTokens;
		}

		public String getText() {
			return text;
		}

		public int getInputTokens() {
			return inputTokens;
		}

		public int getOutputTokens() {
			return outputTokens;
		}

		public int getCachedTokens() {
			return cachedTokens;
		}
	}
}
