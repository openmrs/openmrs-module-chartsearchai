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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RemoteLlmEngine} request building and response parsing.
 */
public class RemoteLlmEngineTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final RemoteLlmEngine engine = new RemoteLlmEngine();

	@Test
	public void buildRequestBody_shouldContainModelAndMessages() throws IOException {
		String body = engine.buildRequestBody("system prompt", "user message", "gpt-4o", false);
		JsonNode root = MAPPER.readTree(body);

		assertEquals("gpt-4o", root.get("model").asText());
		assertEquals(0.0, root.get("temperature").asDouble());
		assertEquals(false, root.get("stream").asBoolean());

		JsonNode messages = root.get("messages");
		assertEquals(2, messages.size());
		assertEquals("system", messages.get(0).get("role").asText());
		assertEquals("system prompt", messages.get(0).get("content").asText());
		assertEquals("user", messages.get(1).get("role").asText());
		assertEquals("user message", messages.get(1).get("content").asText());
	}

	@Test
	public void buildRequestBody_shouldOmitTemperatureAndUseTopKForClaudeOpus47()
			throws IOException {
		String body = engine.buildRequestBody("sys", "usr", "claude-opus-4-7", false);
		JsonNode root = MAPPER.readTree(body);

		assertTrue(root.path("temperature").isMissingNode(),
				"Anthropic's OpenAI-compat endpoint rejects temperature for Claude Opus 4.7 "
				+ "with HTTP 400. The engine must omit it for this model.");
		assertEquals(1, root.get("top_k").asInt(),
				"top_k=1 is greedy decoding — the only determinism lever Anthropic "
				+ "still accepts on Opus 4.7 via the OpenAI-compat endpoint.");
	}

	@Test
	public void buildRequestBody_shouldKeepTemperatureForOpus45And46() throws IOException {
		for (String model : new String[] { "claude-opus-4-5", "claude-opus-4-6" }) {
			String body = engine.buildRequestBody("sys", "usr", model, false);
			JsonNode root = MAPPER.readTree(body);

			assertEquals(0.0, root.get("temperature").asDouble(),
					"Opus 4.5/4.6 still accept temperature on Anthropic's compat endpoint; "
					+ "only 4.7 deprecates it. Don't strip temperature for these models.");
			assertTrue(root.path("top_k").isMissingNode(),
					"top_k must not be sent for models that accept temperature — "
					+ "OpenAI rejects it.");
		}
	}

	@Test
	public void buildRequestBody_shouldUseJsonSchemaStrictMode() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", "claude-haiku-4-5-20251001", false);
		JsonNode root = MAPPER.readTree(body);

		JsonNode responseFormat = root.get("response_format");
		assertEquals("json_schema", responseFormat.get("type").asText(),
				"Remote engine must use json_schema, not json_object — Anthropic's "
				+ "OpenAI-compatible endpoint rejects json_object with HTTP 400.");

		JsonNode jsonSchema = responseFormat.get("json_schema");
		assertTrue(jsonSchema.get("strict").asBoolean());

		JsonNode schema = jsonSchema.get("schema");
		assertEquals("object", schema.get("type").asText());
		assertFalse(schema.get("additionalProperties").asBoolean());

		JsonNode properties = schema.get("properties");
		assertEquals("string", properties.get("reasoning").get("type").asText());
		assertEquals("string", properties.get("answer").get("type").asText());
		assertEquals("array", properties.get("citations").get("type").asText());
		assertEquals("integer", properties.get("citations").get("items").get("type").asText());

		JsonNode required = schema.get("required");
		assertEquals(3, required.size(), "reasoning, answer and citations are all required");
	}

	@Test
	public void buildRequestBody_shouldNotIncludeStreamOptions() throws IOException {
		String body = engine.buildRequestBody("system prompt", "user message", "gpt-4o", false);
		JsonNode root = MAPPER.readTree(body);

		assertTrue(root.path("stream_options").isMissingNode(),
				"stream_options should not be present when stream is false");
	}

	@Test
	public void buildRequestBody_shouldSetStreamTrueWithUsageOption() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", "model", true);
		JsonNode root = MAPPER.readTree(body);

		assertTrue(root.get("stream").asBoolean());
		assertTrue(root.get("stream_options").get("include_usage").asBoolean(),
				"stream_options.include_usage should be true for streaming requests");
	}

	@Test
	public void parseResponse_shouldExtractContentAndTokenCount() throws IOException {
		String responseBody = "{"
				+ "\"choices\": [{\"message\": {\"content\": \"{\\\"answer\\\": \\\"test\\\", \\\"citations\\\": []}\"}}],"
				+ "\"usage\": {\"prompt_tokens\": 100, \"completion_tokens\": 50}"
				+ "}";

		LlmEngine.InferenceResult result = engine.parseResponse(responseBody);
		assertEquals("{\"answer\": \"test\", \"citations\": []}", result.getText());
		assertEquals(100, result.getInputTokens());
		assertEquals(50, result.getOutputTokens());
	}

	@Test
	public void parseResponse_shouldHandleMissingUsage() throws IOException {
		String responseBody = "{"
				+ "\"choices\": [{\"message\": {\"content\": \"hello\"}}]"
				+ "}";

		LlmEngine.InferenceResult result = engine.parseResponse(responseBody);
		assertEquals("hello", result.getText());
		assertEquals(0, result.getInputTokens());
		assertEquals(0, result.getOutputTokens());
	}

	@Test
	public void parseResponse_shouldHandleEmptyChoices() throws IOException {
		String responseBody = "{\"choices\": []}";

		LlmEngine.InferenceResult result = engine.parseResponse(responseBody);
		assertEquals("", result.getText());
	}

	@Test
	public void parseStreamingResponse_shouldAssembleTokens() throws IOException {
		String sseData = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"answer\\\"\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\": \\\"test\\\"\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\", \\\"citations\\\": []}\"}}]}\n\n"
				+ "data: [DONE]\n\n";

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				sseData.getBytes(StandardCharsets.UTF_8));

		List<String> tokens = new ArrayList<>();
		LlmEngine.InferenceResult result = engine.parseStreamingResponse(inputStream,
				tokens::add);

		assertEquals("{\"answer\": \"test\", \"citations\": []}", result.getText());
		assertEquals(3, tokens.size());
	}

	@Test
	public void parseStreamingResponse_shouldSkipBlankLines() throws IOException {
		String sseData = "\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
				+ "\n"
				+ "data: [DONE]\n\n";

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				sseData.getBytes(StandardCharsets.UTF_8));

		List<String> tokens = new ArrayList<>();
		LlmEngine.InferenceResult result = engine.parseStreamingResponse(inputStream,
				tokens::add);

		assertEquals("hello", result.getText());
		assertEquals(1, tokens.size());
	}

	@Test
	public void parseStreamingResponse_shouldExtractUsageFromFinalChunk() throws IOException {
		String sseData = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{}}], \"usage\":{\"prompt_tokens\":80,\"completion_tokens\":20}}\n\n"
				+ "data: [DONE]\n\n";

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				sseData.getBytes(StandardCharsets.UTF_8));

		LlmEngine.InferenceResult result = engine.parseStreamingResponse(inputStream,
				token -> {});

		assertEquals("hi", result.getText());
		assertEquals(80, result.getInputTokens());
		assertEquals(20, result.getOutputTokens());
	}
}
