/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LLM adapter for the Claude API (Anthropic Messages API). Sends clinical queries to Claude and
 * parses the response. Uses standard HttpURLConnection to avoid additional HTTP client dependencies.
 */
@Component("chartsearchai.claudeAdapter")
public class ClaudeAdapter implements LlmAdapter {

	private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);

	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private static final int MAX_TOKENS = 4096;

	private static final int CONNECT_TIMEOUT_MS = 10000;

	private static final int READ_TIMEOUT_MS = 60000;

	@Override
	public LlmResponse chat(String systemPrompt, String userMessage) {
		String apiKey = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_API_KEY);
		if (apiKey == null || apiKey.trim().isEmpty()) {
			throw new APIException("chartsearchai.error.noApiKey", (Object[]) null);
		}

		String model = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL, ChartSearchAiConstants.DEFAULT_LLM_MODEL);
		String apiUrl = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_API_URL, ChartSearchAiConstants.DEFAULT_LLM_API_URL);

		JsonObject requestBody = buildRequestBody(systemPrompt, userMessage, model);
		String responseJson = sendRequest(apiUrl, apiKey, requestBody.toString());
		return parseResponse(responseJson);
	}

	private JsonObject buildRequestBody(String systemPrompt, String userMessage, String model) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", MAX_TOKENS);
		body.addProperty("system", systemPrompt);

		JsonArray messages = new JsonArray();
		JsonObject userMsg = new JsonObject();
		userMsg.addProperty("role", "user");
		userMsg.addProperty("content", userMessage);
		messages.add(userMsg);

		body.add("messages", messages);
		return body;
	}

	private String sendRequest(String apiUrl, String apiKey, String body) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(apiUrl);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("x-api-key", apiKey);
			connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setDoOutput(true);

			try (OutputStream os = connection.getOutputStream()) {
				os.write(body.getBytes(StandardCharsets.UTF_8));
			}

			int responseCode = connection.getResponseCode();
			if (responseCode != 200) {
				String errorBody = readStream(connection.getErrorStream());
				log.error("Claude API returned HTTP {}: {}", responseCode, errorBody);
				throw new APIException("chartsearchai.error.llmRequestFailed",
						new Object[] { responseCode, errorBody });
			}

			return readStream(connection.getInputStream());
		}
		catch (IOException e) {
			throw new APIException("chartsearchai.error.llmConnectionFailed", new Object[] { e.getMessage() }, e);
		}
		finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private String readStream(java.io.InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	private LlmResponse parseResponse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		String model = root.has("model") ? root.get("model").getAsString() : "unknown";

		JsonArray content = root.getAsJsonArray("content");
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < content.size(); i++) {
			JsonObject block = content.get(i).getAsJsonObject();
			if ("text".equals(block.get("type").getAsString())) {
				text.append(block.get("text").getAsString());
			}
		}

		return new LlmResponse(text.toString(), model);
	}
}
