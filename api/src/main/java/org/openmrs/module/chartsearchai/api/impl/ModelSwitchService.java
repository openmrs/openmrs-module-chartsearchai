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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Discovers which models the active remote LLM endpoint reports via
 * {@code GET /v1/models}, and switches the active model by updating the
 * {@code chartsearchai.llm.remote.modelName} global property.
 *
 * <p>POC scope: lists raw model IDs from the active endpoint. An operator-
 * curated allowlist with display names / per-model context sizes is a
 * follow-up iteration (see plan).
 *
 * <p>This service does NOT touch LM Studio's model loading state — switching
 * models on the GP is purely a routing change. If the newly selected model is
 * loaded with a smaller context than the chart envelope needs, the next chat
 * call returns HTTP 400. Operators reload via the harness's
 * {@code chartsearch-warmup.sh} script (which runs {@code lms load -c 32768}
 * on the host running LM Studio).
 */
@Component("chartSearchAi.modelSwitchService")
public class ModelSwitchService {

	private static final Logger log = LoggerFactory.getLogger(ModelSwitchService.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private HttpClient httpClient;

	/**
	 * Snapshot of available models + current selection. Returned as an
	 * immutable value object; the REST controller maps it to JSON.
	 */
	public static final class ModelListResponse {

		private final String engine;

		private final String current;

		private final List<String> available;

		private final String endpointUrl;

		public ModelListResponse(String engine, String current, List<String> available,
				String endpointUrl) {
			this.engine = engine;
			this.current = current;
			this.available = available == null ? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(available));
			this.endpointUrl = endpointUrl;
		}

		public String getEngine() {
			return engine;
		}

		public String getCurrent() {
			return current;
		}

		public List<String> getAvailable() {
			return available;
		}

		public String getEndpointUrl() {
			return endpointUrl;
		}
	}

	/**
	 * Look up the active engine + endpoint, fetch the model list from
	 * {@code <endpoint>/v1/models} (derived by stripping the trailing
	 * {@code /chat/completions} from the configured chat URL), and return
	 * it alongside the currently-selected model name from the GP.
	 *
	 * <p>For {@code engine=local}, returns an empty list with engine="local"
	 * so the SPA can hide the picker — model-switching only applies to the
	 * remote engine in this iteration.
	 *
	 * @throws APIException if the endpoint URL is misconfigured or the
	 *         {@code /v1/models} call fails
	 */
	public ModelListResponse listAvailable() {
		String engine = getGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE);
		String current = getGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME);

		if (!ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(engine)) {
			return new ModelListResponse(engine != null ? engine : "", current,
					Collections.emptyList(), null);
		}

		String chatUrl = getGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		if (chatUrl == null || chatUrl.isEmpty()) {
			throw new APIException("Cannot list models: "
					+ ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL + " is not set.");
		}
		String modelsUrl = deriveModelsUrl(chatUrl);

		List<String> available = fetchModelIds(modelsUrl);
		return new ModelListResponse(engine, current, available, chatUrl);
	}

	/**
	 * Update the active model GP after asserting the requested name is in
	 * the live list of available models. Re-fetches the list to avoid
	 * persisting a stale/invalid choice.
	 *
	 * @return the new current model name (echoed for the caller)
	 * @throws IllegalArgumentException when {@code modelName} is blank or not
	 *         in the available list
	 * @throws APIException when the engine is {@code local} or the endpoint
	 *         is misconfigured
	 */
	public String setCurrent(String modelName) {
		if (modelName == null || modelName.trim().isEmpty()) {
			throw new IllegalArgumentException("modelName is required");
		}
		String trimmed = modelName.trim();

		ModelListResponse snapshot = listAvailable();
		if (!ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(snapshot.getEngine())) {
			throw new APIException("Model switching is only supported for the remote engine; "
					+ "active engine is '" + snapshot.getEngine() + "'.");
		}
		if (!snapshot.getAvailable().contains(trimmed)) {
			throw new IllegalArgumentException("Model '" + trimmed
					+ "' is not in the active endpoint's /v1/models list. Available: "
					+ snapshot.getAvailable());
		}

		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME, trimmed);
		log.info("Active LLM model set to '{}'", trimmed);
		return trimmed;
	}

	/**
	 * Derive the OpenAI-compat {@code /v1/models} URL from the configured
	 * chat-completions URL. Mirrors {@code chartsearch-configure.sh}'s
	 * {@code ENDPOINT%/chat/completions/models} bash derivation.
	 */
	static String deriveModelsUrl(String chatUrl) {
		String stripped = chatUrl.endsWith("/chat/completions")
				? chatUrl.substring(0, chatUrl.length() - "/chat/completions".length())
				: chatUrl;
		// Tolerate trailing slash from poorly-edited configs.
		while (stripped.endsWith("/")) {
			stripped = stripped.substring(0, stripped.length() - 1);
		}
		return stripped + "/models";
	}

	List<String> fetchModelIds(String modelsUrl) {
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(modelsUrl))
				.timeout(Duration.ofSeconds(30))
				.GET();
		if (apiKey != null) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = builder.build();
		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("Models endpoint {} returned HTTP {}: {}", modelsUrl,
						response.statusCode(), truncateForLog(response.body()));
				throw new APIException("Models endpoint returned HTTP " + response.statusCode());
			}
			return parseModelIds(response.body());
		}
		catch (IOException e) {
			throw new APIException("Failed to fetch model list from " + modelsUrl + ": "
					+ e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Model list request was interrupted", e);
		}
	}

	static List<String> parseModelIds(String body) {
		List<String> ids = new ArrayList<>();
		try {
			JsonNode root = MAPPER.readTree(body);
			JsonNode data = root.get("data");
			if (data != null && data.isArray()) {
				for (JsonNode entry : data) {
					JsonNode id = entry.get("id");
					if (id != null && id.isTextual()) {
						String value = id.asText().trim();
						if (!value.isEmpty()) {
							ids.add(value);
						}
					}
				}
			}
		}
		catch (IOException e) {
			throw new APIException("Failed to parse /v1/models response: " + e.getMessage(), e);
		}
		return ids;
	}

	private synchronized HttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.version(HttpClient.Version.HTTP_1_1)
					.connectTimeout(Duration.ofSeconds(10))
					.build();
		}
		return httpClient;
	}

	private static String getGlobalProperty(String name) {
		String value = Context.getAdministrationService().getGlobalProperty(name);
		return value == null ? null : value.trim();
	}

	private static String getOptionalRuntimeProperty(String name) {
		Properties props = Context.getRuntimeProperties();
		String value = props != null ? props.getProperty(name) : null;
		return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
	}

	private static String truncateForLog(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
