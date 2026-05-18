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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remote LLM engine that calls an OpenAI-compatible chat completions API.
 * Supports any server that implements the OpenAI API format, including
 * self-hosted inference servers (vLLM, Ollama, text-generation-inference)
 * and cloud providers (OpenAI, Azure OpenAI, Google AI, Anthropic).
 */
@Component("chartSearchAi.remoteLlmEngine")
public class RemoteLlmEngine implements LlmEngine {

	private static final Logger log = LoggerFactory.getLogger(RemoteLlmEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private HttpClient httpClient;

	@Override
	public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
		return infer(ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage), timeoutSeconds);
	}

	@Override
	public InferenceResult infer(ArrayNode messages, int timeoutSeconds) {
		String endpointUrl = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		String modelName = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME);

		String requestBody = buildRequestBody(messages, modelName, false);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(endpointUrl))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
		if (apiKey != null) {
			requestBuilder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = requestBuilder.build();

		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.error("Remote LLM API returned HTTP {}: {}", response.statusCode(),
						truncateForLog(response.body()));
				throw new APIException("Remote LLM API returned HTTP " + response.statusCode()
						+ ". Check the endpoint URL and model name in the "
						+ "chartsearchai.llm.remote.* global properties, and the API key "
						+ "in openmrs-runtime.properties.");
			}

			return parseResponse(response.body());
		}
		catch (IOException e) {
			throw new APIException("Failed to call remote LLM API: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Remote LLM API call was interrupted", e);
		}
	}

	@Override
	public InferenceResult inferStreaming(String systemPrompt, String userMessage,
			int timeoutSeconds, Consumer<String> tokenConsumer) {
		return inferStreaming(ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage),
				timeoutSeconds, tokenConsumer);
	}

	@Override
	public InferenceResult inferStreaming(ArrayNode messages, int timeoutSeconds,
			Consumer<String> tokenConsumer) {
		String endpointUrl = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		String modelName = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME);

		String requestBody = buildRequestBody(messages, modelName, true);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(endpointUrl))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
		if (apiKey != null) {
			requestBuilder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = requestBuilder.build();

		try {
			HttpResponse<InputStream> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
				log.error("Remote LLM API returned HTTP {}: {}", response.statusCode(),
						truncateForLog(body));
				throw new APIException("Remote LLM API returned HTTP " + response.statusCode());
			}

			return parseStreamingResponse(response.body(), tokenConsumer);
		}
		catch (IOException e) {
			throw new APIException("Failed to call remote LLM API: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Remote LLM API call was interrupted", e);
		}
	}

	@Override
	public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		// Remote OpenAI-compatible APIs typically manage their own caching. Issuing
		// a no-op call here would just incur cost. Local-engine warmup is what closes
		// the gap; remote engines fall back to whatever caching the provider offers.
	}

	@Override
	public boolean supportsWarmup() {
		return false;
	}

	@Override
	public synchronized void close() {
		httpClient = null;
	}

	@Override
	public void shutdown() {
		close();
	}

	String buildRequestBody(String systemPrompt, String userMessage, String modelName,
			boolean stream) {
		return buildRequestBody(ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage),
				modelName, stream);
	}

	String buildRequestBody(ArrayNode messages, String modelName, boolean stream) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", modelName);
		// Anthropic's compat endpoint rejects temperature/top_p on Opus 4.7; top_k=1 is the only greedy-decoding lever it still accepts.
		if (modelName.startsWith("claude-opus-4-7")) {
			root.put("top_k", 1);
		} else {
			root.put("temperature", 0.0);
		}
		root.put("max_tokens", ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS);
		root.put("stream", stream);
		if (stream) {
			ObjectNode streamOptions = MAPPER.createObjectNode();
			streamOptions.put("include_usage", true);
			root.set("stream_options", streamOptions);
		}

		root.set("response_format", ChartAnswerResponseFormat.build(MAPPER));
		root.set("messages", messages);

		try {
			return MAPPER.writeValueAsString(root);
		}
		catch (IOException e) {
			throw new APIException("Failed to build request body", e);
		}
	}

	InferenceResult parseResponse(String responseBody) throws IOException {
		return LlmResponseParser.parseResponse(responseBody, log);
	}

	InferenceResult parseStreamingResponse(InputStream inputStream,
			Consumer<String> tokenConsumer) throws IOException {
		return LlmResponseParser.parseStreamingResponse(inputStream, tokenConsumer, log);
	}

	private String getRequiredGlobalProperty(String propertyName) {
		String value = Context.getAdministrationService().getGlobalProperty(propertyName);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalStateException(
					"Required global property not configured: " + propertyName);
		}
		return value.trim();
	}

	private String getOptionalRuntimeProperty(String propertyName) {
		Properties props = Context.getRuntimeProperties();
		String value = props != null ? props.getProperty(propertyName) : null;
		return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
	}

	private synchronized HttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(30))
					.build();
		}
		return httpClient;
	}

	private static String truncateForLog(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
