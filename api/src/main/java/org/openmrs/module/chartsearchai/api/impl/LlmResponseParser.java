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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.openmrs.module.chartsearchai.api.impl.LlmEngine.InferenceResult;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared parser for OpenAI-compatible chat completions responses, used by
 * both {@link LocalLlmEngine} (talking to {@code llama-server}) and
 * {@link RemoteLlmEngine} (talking to remote OpenAI-compatible APIs).
 * Handles both single-shot JSON responses and SSE-streamed chunks.
 *
 * <p>{@code prompt_tokens_details.cached_tokens} is always extracted —
 * llama-server reports it (the prefix-cache reuse signal); remote APIs
 * that don't include the field yield {@code cachedTokens=0}, which is
 * the correct semantics.</p>
 *
 * <p>Callers pass their own SLF4J {@link Logger} so log entries carry the
 * caller's class name. Token-usage logs omit the {@code "(N cached)"}
 * suffix when {@code cachedTokens} is zero to keep output clean for
 * remote responses that don't report cache hits.</p>
 */
final class LlmResponseParser {

	private LlmResponseParser() {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Detects llama-server's distinctive 400 response when the prompt exceeds
	 * the configured context window. The body looks like
	 * {@code {"error":{"type":"exceed_context_size_error",...}}} — match on
	 * the type marker rather than parsing JSON to stay robust to format drift.
	 */
	static boolean isContextOverflowError(String responseBody) {
		return responseBody != null && responseBody.contains("exceed_context_size_error");
	}

	static InferenceResult parseResponse(String responseBody, Logger callerLog) throws IOException {
		JsonNode root = MAPPER.readTree(responseBody);

		String text = "";
		JsonNode choices = root.get("choices");
		if (choices != null && choices.isArray() && !choices.isEmpty()) {
			JsonNode message = choices.get(0).get("message");
			if (message != null && message.has("content")) {
				text = message.get("content").asText("");
			}
		}

		int inputTokens = 0;
		int outputTokens = 0;
		int cachedTokens = 0;
		JsonNode usage = root.get("usage");
		if (usage != null) {
			inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt(0) : 0;
			outputTokens = usage.has("completion_tokens")
					? usage.get("completion_tokens").asInt(0) : 0;
			JsonNode details = usage.get("prompt_tokens_details");
			if (details != null && details.has("cached_tokens")) {
				cachedTokens = details.get("cached_tokens").asInt(0);
			}
		}

		logTokenUsage(callerLog, inputTokens, outputTokens, cachedTokens, true);
		return new InferenceResult(text, inputTokens, outputTokens, cachedTokens);
	}

	static InferenceResult parseStreamingResponse(InputStream inputStream,
			Consumer<String> tokenConsumer, Logger callerLog) throws IOException {
		StringBuilder fullText = new StringBuilder();
		int inputTokens = 0;
		int outputTokens = 0;
		int cachedTokens = 0;

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6).trim();
				if ("[DONE]".equals(data)) {
					break;
				}
				try {
					JsonNode chunk = MAPPER.readTree(data);
					JsonNode choices = chunk.get("choices");
					if (choices == null || !choices.isArray() || choices.isEmpty()) {
						continue;
					}
					JsonNode delta = choices.get(0).get("delta");
					if (delta != null && delta.has("content")) {
						String content = delta.get("content").asText("");
						if (!content.isEmpty()) {
							fullText.append(content);
							tokenConsumer.accept(content);
						}
					}
					JsonNode usage = chunk.get("usage");
					if (usage != null) {
						inputTokens = usage.has("prompt_tokens")
								? usage.get("prompt_tokens").asInt(0) : 0;
						outputTokens = usage.has("completion_tokens")
								? usage.get("completion_tokens").asInt(0) : 0;
						JsonNode details = usage.get("prompt_tokens_details");
						if (details != null && details.has("cached_tokens")) {
							cachedTokens = details.get("cached_tokens").asInt(0);
						}
					}
				}
				catch (IOException e) {
					callerLog.debug("Skipping unparseable SSE chunk: {}", data);
				}
			}
		}

		logTokenUsage(callerLog, inputTokens, outputTokens, cachedTokens,
				inputTokens > 0 || outputTokens > 0);
		return new InferenceResult(fullText.toString(), inputTokens, outputTokens, cachedTokens);
	}

	private static void logTokenUsage(Logger callerLog, int inputTokens, int outputTokens,
			int cachedTokens, boolean shouldLog) {
		if (!shouldLog) {
			return;
		}
		if (cachedTokens > 0) {
			callerLog.info("LLM token usage: {} input ({} cached) + {} output",
					inputTokens, cachedTokens, outputTokens);
		} else {
			callerLog.info("LLM token usage: {} input + {} output",
					inputTokens, outputTokens);
		}
	}
}
