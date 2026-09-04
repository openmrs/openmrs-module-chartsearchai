/*
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
	 * <p>{@link LocalLlmEngine} and {@link RemoteLlmEngine} both spend this on a JDK
	 * {@code HttpRequest.timeout()}, which stops applying once the inference server's response
	 * headers arrive; the token stream is then read lazily out of
	 * {@code BodyHandlers.ofInputStream()} and is bounded by nothing. So {@code timeoutSeconds} caps
	 * the wait for the FIRST output and not the call. Measured 2026-08-20 against a local
	 * {@code llama-server} on CPU ({@code -ngl 0}, Llama-3.2-3B-Q4_K_M): a cold prefill raised
	 * {@code HttpTimeoutException} at 2.0s and at 8.0s against timeouts of exactly those lengths,
	 * while a cache-warm request under a 20s timeout returned headers at 117ms and then streamed for
	 * 23.8s without raising one. Those figures come from a standalone client issuing the same two
	 * calls both of them make here, {@code HttpRequest.timeout(...)} and
	 * {@code send(request, BodyHandlers.ofInputStream())}, rather than from this method, which needs a
	 * running module: the model and the prompt decide only how long the prefill takes, which is what
	 * the 2.0s and 8.0s runs turn on, and neither reaches the JDK behaviour being measured. The same
	 * behaviour reproduces with no inference server at all, against a socket that sends headers and
	 * then stalls, on Java 11, 17 and 21 alike and with {@code ofString()} as well. It is also a
	 * PER-CALL budget rather than a per-invocation one: {@link LocalLlmEngine} spends it again on
	 * each KV-cache slot call the six-argument form below can make around the completion.</p>
	 *
	 * <p>The non-streaming forms, {@link #infer(String, String, int)} and
	 * {@link #warmup(String, String, int)}, keep the "maximum wall-clock seconds" wording
	 * deliberately: whether a server that does not stream withholds its headers until the answer is
	 * complete was not measured, and if it does then that wording holds there.</p>
	 *
	 * @param systemPrompt the system prompt
	 * @param userMessage the user message (patient records + question)
	 * @param timeoutSeconds seconds to wait for the inference server's first output, NOT a bound on
	 *        the token stream that follows it — see above
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
	 * As {@link #warmup(String, String, int, String)} but, when {@code pin} is true, asks an engine
	 * that persists KV to disk to mark the saved entry as <em>pinned</em>: exempt from the LRU cap
	 * ({@code kvCacheMaxEntries}), so the prewarm bootstrap can build a durable warm corpus for every
	 * patient on hosts with the disk for it, without the ad-hoc warmup/query pool evicting it. The
	 * default ignores {@code pin} and delegates to the 4-arg form, so engines that don't persist KV
	 * (and existing callers/tests) are unaffected.
	 *
	 * @param pin true to exempt the saved entry from cap-based eviction
	 */
	default void warmup(String systemPrompt, String userMessage, int timeoutSeconds, String cacheScope,
			boolean pin) {
		warmup(systemPrompt, userMessage, timeoutSeconds, cacheScope);
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
