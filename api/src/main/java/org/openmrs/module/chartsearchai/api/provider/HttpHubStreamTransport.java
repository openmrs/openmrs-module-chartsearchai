/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.springframework.stereotype.Component;

/**
 * Production {@link HubStreamTransport}: one HTTP POST to the configured hub chat-completions
 * endpoint with {@code Accept: text/event-stream}, parsed into {@link HubWireEvent}s. There is no
 * whole-profile wall-clock timeout — product profiles may continue through review and In-Depth;
 * browser disconnects cancel the hub connection.
 */
@Component("chartSearchAi.hubStreamTransport")
public class HttpHubStreamTransport implements HubStreamTransport {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	/** Runtime-property API key seam, overridable in tests. */
	protected String apiKey() {
		Properties properties = Context.getRuntimeProperties();
		return properties == null ? null
				: properties.getProperty(ChartSearchAiConstants.RP_HUB_API_KEY);
	}

	@Override
	public void stream(HubCallRequest request, Consumer<HubWireEvent> sink, CancellationSignal cancellation) {
		try {
			String body = requestJson(request);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(request.getEndpointUrl()))
					.version(HttpClient.Version.HTTP_1_1)
					.header("Content-Type", "application/json")
					.header("Accept", "text/event-stream")
					.POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
			String key = apiKey();
			if (key != null && !key.trim().isEmpty()) {
				builder.header("Authorization", "Bearer " + key.trim());
			}
			HttpResponse<InputStream> response = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String errorBody = readErrorBody(response.body());
				requireSuccess(response.statusCode(), errorBody);
			}
			// Bind the open response body so a preempting turn can force it closed from another
			// thread, unblocking parseSse's readLine() below with an IOException instead of letting
			// the hub keep generating an abandoned turn to completion (see TurnCancellation).
			if (cancellation instanceof TurnCancellation) {
				((TurnCancellation) cancellation).bindCloseable(response.body());
			}
			parseSse(response.body(), sink);
		}
		catch (HubTransportException e) {
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Hub stream interrupted", e);
		}
		catch (IOException e) {
			throw new RuntimeException("Hub stream failed: " + e.getMessage(), e);
		}
	}

	static void requireSuccess(int statusCode, String body) {
		if (statusCode < 200 || statusCode >= 300) {
			throw new HubTransportException(statusCode, body);
		}
	}

	static String readErrorBody(InputStream body) throws IOException {
		try (InputStream errorBody = body) {
			return new String(errorBody.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Builds the hub request envelope. Prior turns are prose-only user/assistant pairs; the hub
	 * owns chart retrieval and the system prompt.
	 */
	static String requestJson(HubCallRequest request) throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("model", request.getProfileId());
		root.put("stream", Boolean.TRUE);
		root.put("patient", request.getPatientUuid());
		List<Map<String, Object>> messages = new ArrayList<>();
		for (PriorClinicalTurn prior : request.getPriorTurns()) {
			messages.add(message("user", prior.getQuestion()));
			messages.add(message("assistant", prior.getAnswer()));
		}
		messages.add(message("user", request.getQuestion()));
		root.put("messages", messages);
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("require_product_profile", Boolean.TRUE);
		context.put("session", request.getConversationId());
		context.put("request_id", request.getRequestId());
		root.put("context", context);
		return MAPPER.writeValueAsString(root);
	}

	private static Map<String, Object> message(String role, String content) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", role);
		message.put("content", content);
		return message;
	}

	static void parseSse(InputStream body, Consumer<HubWireEvent> sink) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(body, StandardCharsets.UTF_8))) {
			String event = "";
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					emit(event, data.toString(), sink);
					event = "";
					data.setLength(0);
				} else if (line.startsWith("event:")) {
					event = line.substring("event:".length()).trim();
				} else if (line.startsWith("data:")) {
					if (data.length() > 0) {
						data.append('\n');
					}
					String raw = line.substring("data:".length());
					data.append(raw.startsWith(" ") ? raw.substring(1) : raw);
				}
				// Comment/heartbeat lines (": ...") are ignored; the REST layer may still write them
				// downstream to detect client disconnect.
			}
			if (data.length() > 0) {
				emit(event, data.toString(), sink);
			}
		}
	}

	private static void emit(String event, String data, Consumer<HubWireEvent> sink)
			throws IOException {
		if (event == null || event.isEmpty() || data == null || data.isEmpty()) {
			return;
		}
		Map<String, Object> payload = MAPPER.readValue(data,
				new TypeReference<Map<String, Object>>() {});
		sink.accept(new HubWireEvent(event, payload));
	}
}
