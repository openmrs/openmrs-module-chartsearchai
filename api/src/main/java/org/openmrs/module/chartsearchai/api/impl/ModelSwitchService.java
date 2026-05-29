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
	 * One model entry as surfaced to the picker. When the source is LM Studio's
	 * /api/v1/models, all fields are populated; when the source is the
	 * OpenAI-compat /v1/models fallback, only {@code id}/{@code displayName}
	 * are meaningful, {@code type} defaults to "llm", {@code loaded} to false,
	 * and {@code maxContextLength} to null.
	 */
	public static final class ModelEntry {

		private final String id;

		private final String displayName;

		private final String type;

		private final boolean loaded;

		private final Long maxContextLength;

		public ModelEntry(String id, String displayName, String type, boolean loaded,
				Long maxContextLength) {
			this.id = id;
			this.displayName = displayName;
			this.type = type;
			this.loaded = loaded;
			this.maxContextLength = maxContextLength;
		}

		/**
		 * Hydrate a {@link ModelEntry} from just an OpenAI-compat /v1/models
		 * `id` string, with safe defaults for unknown fields.
		 */
		public static ModelEntry fromOpenAiId(String id) {
			return new ModelEntry(id, id, "llm", false, null);
		}

		public String getId() {
			return id;
		}

		public String getDisplayName() {
			return displayName;
		}

		public String getType() {
			return type;
		}

		public boolean isLoaded() {
			return loaded;
		}

		public Long getMaxContextLength() {
			return maxContextLength;
		}
	}

	/**
	 * Result of the /v1/models probe-and-fallback dispatch. Carries the
	 * provider tag the REST response surfaces to the picker (used for
	 * sub-category grouping) and the per-entry list.
	 */
	public static final class AvailableModels {

		private final String provider;

		private final List<ModelEntry> entries;

		public AvailableModels(String provider, List<ModelEntry> entries) {
			this.provider = provider;
			this.entries = entries == null ? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(entries));
		}

		public String getProvider() {
			return provider;
		}

		public List<ModelEntry> getEntries() {
			return entries;
		}
	}

	/**
	 * Snapshot of available models + current selection. Returned as an
	 * immutable value object; the REST controller maps it to JSON.
	 *
	 * <p>The {@code available} field is the legacy List&lt;String&gt; view
	 * (back-compat for older consumers + the existing parseModelIds test
	 * contract). The {@code entries} + {@code provider} fields carry the
	 * richer per-model info (loaded state, display name, max context length)
	 * needed by the picker to render a grouped view with per-entry state.
	 */
	public static final class ModelListResponse {

		private final String engine;

		private final String current;

		private final List<String> available;

		private final String endpointUrl;

		private final String provider;

		private final List<ModelEntry> entries;

		public ModelListResponse(String engine, String current, List<String> available,
				String endpointUrl) {
			this(engine, current, available, endpointUrl, null, null);
		}

		public ModelListResponse(String engine, String current, List<String> available,
				String endpointUrl, String provider, List<ModelEntry> entries) {
			this.engine = engine;
			this.current = current;
			this.available = available == null ? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(available));
			this.endpointUrl = endpointUrl;
			this.provider = provider;
			this.entries = entries == null ? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(entries));
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

		public String getProvider() {
			return provider;
		}

		public List<ModelEntry> getEntries() {
			return entries;
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

		// Probe LM Studio /api/v1/models first, fall back to OpenAI-compat
		// /v1/models. Picker reads `provider` for sub-category grouping and
		// per-entry `loaded` for the "(not loaded)" affix.
		AvailableModels probed = fetchAvailable(chatUrl);
		List<String> ids = new ArrayList<>();
		for (ModelEntry entry : probed.getEntries()) {
			ids.add(entry.getId());
		}
		return new ModelListResponse(engine, current, ids, chatUrl,
				probed.getProvider(), probed.getEntries());
	}

	/**
	 * Request that the upstream LM Studio server pre-load the named model
	 * into memory. Used by the picker's "select-not-loaded" flow so the user
	 * pays the load latency at pick-time (with a spinner) rather than on
	 * their first chat turn (with an opaque pause).
	 *
	 * <p>Only meaningful when the active provider is LM Studio (the OpenAI-
	 * compat /v1/chat/completions has no equivalent load endpoint; servers
	 * either JIT-load on first chat call or require pre-load via their own
	 * REST API). Returns silently if the active provider is not LM Studio —
	 * the next /v1/chat/completions will JIT-load anyway.
	 *
	 * @throws APIException when the LM Studio load endpoint returns non-2xx
	 */
	public void requestModelLoad(String modelName) {
		if (modelName == null || modelName.trim().isEmpty()) {
			throw new IllegalArgumentException("modelName is required");
		}
		String engine = getGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE);
		if (!ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(engine)) {
			throw new APIException("Model load is only supported for the remote engine; "
					+ "active engine is '" + engine + "'.");
		}
		String chatUrl = getGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		if (chatUrl == null || chatUrl.isEmpty()) {
			throw new APIException("Cannot load model: "
					+ ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL + " is not set.");
		}
		// Re-derive against the same host the picker probed. If we can't reach
		// /api/v1, the provider isn't LM Studio — return silently rather than
		// erroring (the next chat completion will JIT-load anyway).
		AvailableModels probed = fetchAvailable(chatUrl);
		if (!"lm-studio".equals(probed.getProvider())) {
			log.info("Pre-load requested but active provider is {} ({}); skipping",
					probed.getProvider(), chatUrl);
			return;
		}
		String loadUrl = deriveLmStudioV1ModelsUrl(chatUrl) + "/load";
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		String body = "{\"model\":\"" + modelName.trim().replace("\"", "\\\"") + "\"}";
		try {
			httpPostJson(loadUrl, body, apiKey);
		}
		catch (RuntimeException e) {
			throw new APIException("Failed to pre-load model '" + modelName + "' via "
					+ loadUrl + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Test-seam: execute an HTTP POST with a JSON body. Throws
	 * {@link RuntimeException} on non-2xx or transport error. Tests override
	 * to assert the call shape without spinning up an HTTP server.
	 */
	protected String httpPostJson(String url, String body, String apiKey) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMinutes(2)) // model loads can take a minute on slow disks
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (apiKey != null) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = builder.build();
		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new RuntimeException("HTTP " + response.statusCode() + ": "
						+ truncateForLog(response.body()));
			}
			return response.body();
		}
		catch (IOException e) {
			throw new RuntimeException("IO error: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted", e);
		}
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
	 * Derive LM Studio's {@code /api/v1/models} URL from the configured
	 * chat-completions URL. Strips the OpenAI-compat path suffix (the
	 * {@code /v1/...} part) and appends {@code /api/v1/models} on the host.
	 *
	 * <p>Examples:
	 * <pre>
	 *   http://host.docker.internal:1234/v1/chat/completions
	 *     → http://host.docker.internal:1234/api/v1/models
	 *   http://localhost:1234
	 *     → http://localhost:1234/api/v1/models
	 *   http://host:1234/v1/
	 *     → http://host:1234/api/v1/models
	 * </pre>
	 */
	static String deriveLmStudioV1ModelsUrl(String chatUrl) {
		String stripped = chatUrl;
		// Strip the OpenAI-compat path (anything from /v1 onward).
		int v1Idx = stripped.indexOf("/v1");
		if (v1Idx >= 0) {
			stripped = stripped.substring(0, v1Idx);
		}
		// Tolerate trailing slash.
		while (stripped.endsWith("/")) {
			stripped = stripped.substring(0, stripped.length() - 1);
		}
		return stripped + "/api/v1/models";
	}

	/**
	 * Heuristic: does this response body look like LM Studio's
	 * {@code /api/v1/models} shape (top-level {@code models} array)? The
	 * OpenAI-compat shape uses {@code data} instead, and arbitrary HTML / 404
	 * bodies have neither.
	 */
	static boolean looksLikeLmStudioV1Response(String body) {
		if (body == null || body.isEmpty()) {
			return false;
		}
		try {
			JsonNode root = MAPPER.readTree(body);
			JsonNode models = root.get("models");
			return models != null && models.isArray();
		}
		catch (IOException e) {
			return false;
		}
	}

	/**
	 * Parse LM Studio v1's {@code GET /api/v1/models} response into a list
	 * of {@link ModelEntry}. Filters to {@code type=="llm"} (embedding-only
	 * models are excluded — selecting one for chat would 400). Skips entries
	 * missing a textual {@code key} field.
	 *
	 * <p>Loaded vs not-loaded comes from the {@code loaded_instances} array
	 * (empty → not loaded; populated → loaded).
	 */
	static List<ModelEntry> parseLmStudioV1Models(String body) {
		List<ModelEntry> entries = new ArrayList<>();
		try {
			JsonNode root = MAPPER.readTree(body);
			JsonNode models = root.get("models");
			if (models != null && models.isArray()) {
				for (JsonNode entry : models) {
					JsonNode keyNode = entry.get("key");
					if (keyNode == null || !keyNode.isTextual()) {
						continue;
					}
					String id = keyNode.asText().trim();
					if (id.isEmpty()) {
						continue;
					}
					String type = textOrNull(entry.get("type"));
					if (!"llm".equalsIgnoreCase(type)) {
						continue;
					}
					String displayName = textOrNull(entry.get("display_name"));
					if (displayName == null || displayName.isEmpty()) {
						displayName = id;
					}
					JsonNode loadedInstances = entry.get("loaded_instances");
					boolean loaded = loadedInstances != null
							&& loadedInstances.isArray() && loadedInstances.size() > 0;
					JsonNode mctx = entry.get("max_context_length");
					Long maxContext = (mctx != null && mctx.isIntegralNumber())
							? Long.valueOf(mctx.asLong()) : null;
					entries.add(new ModelEntry(id, displayName, type, loaded, maxContext));
				}
			}
		}
		catch (IOException e) {
			throw new APIException("Failed to parse /api/v1/models response: " + e.getMessage(), e);
		}
		return entries;
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || !node.isTextual()) {
			return null;
		}
		return node.asText();
	}

	/**
	 * Probe-and-fallback dispatch. Tries LM Studio's {@code /api/v1/models}
	 * first; if the response is a recognizable v1 shape, returns the enriched
	 * entries with {@code provider="lm-studio"}. Otherwise falls back to the
	 * OpenAI-compat {@code /v1/models} list and returns entries hydrated from
	 * just IDs with {@code provider="generic-openai-compat"}.
	 *
	 * <p>HTTP errors on the v1 probe (404, connection refused, malformed JSON)
	 * are non-fatal — they trigger the fallback. Errors on the OpenAI-compat
	 * fallback propagate as {@link APIException} matching today's behavior.
	 *
	 * @param chatUrl the configured chat-completions URL (the same value the
	 *                {@code chartsearchai.llm.remote.endpointUrl} GP holds).
	 */
	public AvailableModels fetchAvailable(String chatUrl) {
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);

		// 1. Try LM Studio v1.
		String v1Url = deriveLmStudioV1ModelsUrl(chatUrl);
		try {
			String v1Body = httpGet(v1Url, apiKey);
			if (looksLikeLmStudioV1Response(v1Body)) {
				return new AvailableModels("lm-studio", parseLmStudioV1Models(v1Body));
			}
		}
		catch (RuntimeException e) {
			// Probe failure (404, conn refused, etc.) is non-fatal — fall back.
			log.debug("LM Studio /api/v1/models probe failed for {}: {}; falling back to /v1/models",
					v1Url, e.getMessage());
		}

		// 2. Fall back to OpenAI-compat /v1/models.
		String openaiUrl = deriveModelsUrl(chatUrl);
		String openaiBody;
		try {
			openaiBody = httpGet(openaiUrl, apiKey);
		}
		catch (RuntimeException e) {
			throw new APIException("Failed to fetch model list from " + openaiUrl + ": "
					+ e.getMessage(), e);
		}
		List<String> ids = parseModelIds(openaiBody);
		List<ModelEntry> entries = new ArrayList<>();
		for (String id : ids) {
			entries.add(ModelEntry.fromOpenAiId(id));
		}
		return new AvailableModels("generic-openai-compat", entries);
	}

	/**
	 * Test-seam: execute an HTTP GET and return the response body. Throws a
	 * {@link RuntimeException} on non-2xx, connection failures, or interrupt.
	 * Tests override this to inject canned responses without spinning up an
	 * actual HTTP server.
	 */
	protected String httpGet(String url, String apiKey) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
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
				throw new RuntimeException("HTTP " + response.statusCode() + ": "
						+ truncateForLog(response.body()));
			}
			return response.body();
		}
		catch (IOException e) {
			throw new RuntimeException("IO error: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted", e);
		}
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
