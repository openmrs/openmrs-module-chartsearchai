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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The one OpenAI-compatible-endpoint client for the opt-in LLM test suites — resolving the endpoint,
 * probing it, deciding whether the suite was opted into, and posting one completion. Shared by
 * {@link LlmAnswerQualityTest}, {@link PromptInjectionEvalTest}, {@link AbsentDataEvalTest} and
 * {@link EndedOrderAnswerRuleTest} so the request shape cannot drift between them, which is the
 * rule CLAUDE.md states for {@code TestDatasetHelper} and {@code DrugReferenceTestSupport}.
 *
 * <p>Extracted when {@link AbsentDataEvalTest} became the third suite to need it (issue #203); the
 * first two had a copy each. It matters more here than the line count suggests, because all three
 * suites are <b>skipped unless opted into</b>: a divergence between three copies of the request shape
 * would not turn CI red, so the copies could disagree indefinitely about what "the same call" means —
 * and comparing results across suites would then be comparing different requests.
 *
 * <p>This is a transport helper, not a pipeline stand-in. The prompt bytes, the response parsing and
 * the assertions all stay in the suites and go through real production code ({@link
 * LlmProvider#DEFAULT_SYSTEM_PROMPT}, {@link LlmProvider#buildUserMessage}, {@code extractResponse}).
 */
final class LlmEndpointTestSupport {

	/** Where a locally-run llama-server listens by default; every suite may override it by property. */
	static final String DEFAULT_ENDPOINT = "http://localhost:18085/v1/chat/completions";

	/**
	 * How long one completion may take. The two earlier copies of this client each said 120s, which is
	 * below what this module's own answers cost: a full chart at the production output ceiling
	 * ({@code DEFAULT_LLM_MAX_OUTPUT_TOKENS}) is minutes of decoding on a local engine, and a cold start
	 * is worse. Measured on the bundled Gemma over a 150-record chart: single answers past 1400 decoded
	 * tokens at ~22 tokens/s, i.e. over a minute of generation alone before prefill. A timeout that fires
	 * mid-generation surfaces as an ERRORED case, which reads like a defect in the answer rather than in
	 * the clock.
	 */
	private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(300);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LlmEndpointTestSupport() {
	}

	/**
	 * Skips the calling suite unless {@code enableProperty} is set to {@code true}.
	 *
	 * <p>Here rather than copied per suite for the reason the class javadoc gives about the request
	 * shape, and it binds harder for this one: every suite that uses it is SKIPPED in CI, so a suite
	 * whose gate has drifted — a different spelling, a laxer truth test — stops running and looks
	 * exactly like a suite that ran and passed. Nothing turns red either way.
	 */
	static void assumeOptedIn(String enableProperty) {
		Assumptions.assumeTrue("true".equalsIgnoreCase(System.getProperty(enableProperty)),
				"Skipping: set -D" + enableProperty + "=true to run");
	}

	/** The endpoint {@code endpointProperty} names, or {@link #DEFAULT_ENDPOINT} when it is unset. */
	static String endpoint(String endpointProperty) {
		String explicit = System.getProperty(endpointProperty);
		return (explicit != null && !explicit.isEmpty()) ? explicit : DEFAULT_ENDPOINT;
	}

	/**
	 * Whether {@code endpoint}'s server answers its {@code /health} probe. Any failure — refused
	 * connection, timeout, non-200 — reads as "not reachable", because the only caller is an
	 * {@code Assumptions.assumeTrue} that must skip rather than error when no server is running.
	 */
	static boolean isReachable(String endpoint) {
		try {
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder().uri(URI.create(endpoint.replace("/v1/chat/completions", "/health")))
							.timeout(Duration.ofSeconds(5)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200;
		}
		catch (Exception e) {
			return false;
		}
	}

	/**
	 * One completion: {@code systemPrompt} and {@code userMessage} posted to {@code endpoint} at
	 * temperature 0 with a JSON-object response format — the request shape production uses, so the
	 * answer a suite asserts on is the answer the module would have received.
	 *
	 * @return the assistant message's raw content, or an empty string when the response carries no
	 *         choice; this is the string {@code LlmProvider.extractResponse} is designed to parse, so
	 *         a malformed or empty completion is a case for the caller to assert about rather than
	 *         an error to raise here
	 * @throws IOException on a non-200 status, which is a broken endpoint rather than a bad answer
	 */
	static String complete(String endpoint, String systemPrompt, String userMessage, int maxTokens)
			throws Exception {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("temperature", 0.0);
		root.put("max_tokens", maxTokens);
		root.put("stream", false);

		ObjectNode responseFormat = MAPPER.createObjectNode();
		responseFormat.put("type", "json_object");
		root.set("response_format", responseFormat);

		ArrayNode messages = MAPPER.createArrayNode();
		ObjectNode sysMsg = MAPPER.createObjectNode();
		sysMsg.put("role", "system");
		sysMsg.put("content", systemPrompt);
		messages.add(sysMsg);

		ObjectNode userMsg = MAPPER.createObjectNode();
		userMsg.put("role", "user");
		userMsg.put("content", userMessage);
		messages.add(userMsg);
		root.set("messages", messages);

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder()
						.uri(URI.create(endpoint))
						.timeout(COMPLETION_TIMEOUT)
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(
								MAPPER.writeValueAsString(root), StandardCharsets.UTF_8))
						.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (response.statusCode() != 200) {
			throw new IOException("LLM endpoint returned HTTP " + response.statusCode());
		}

		JsonNode respRoot = MAPPER.readTree(response.body());
		JsonNode choices = respRoot.get("choices");
		if (choices != null && choices.isArray() && !choices.isEmpty()) {
			JsonNode message = choices.get(0).get("message");
			if (message != null && message.has("content")) {
				return message.get("content").asText("");
			}
		}
		return "";
	}
}
