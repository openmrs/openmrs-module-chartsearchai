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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProviderRegistry;
import org.openmrs.module.chartsearchai.api.provider.HubClinicalAnswerProvider;
import org.springframework.stereotype.Component;

/** Relays med-agent-hub's authoritative product-profile metadata without reshaping it. */
@Component("chartSearchAi.hubProfileService")
public class HubProfileService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * How long the body may take once the headers have arrived. The request timeout stops applying
	 * at the headers, so a Hub that stalls mid-body would otherwise hold the caller for good.
	 * Package-private so a test can shorten it.
	 */
	Duration bodyDeadline = Duration.ofSeconds(10);

	private static final ExecutorService BODY_READERS = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "chartsearchai-hub-profile-body");
		thread.setDaemon(true);
		return thread;
	});

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	public Map<String, Object> listProfiles() {
		// Profiles of a provider require() refuses would offer the picker choices no turn can use.
		if (!ClinicalAnswerProviderRegistry.enabledProviderIds(Context.getAdministrationService().getGlobalProperty(
				ChartSearchAiConstants.GP_PROVIDERS_ENABLED, ChartSearchAiConstants.DEFAULT_PROVIDER))
				.contains(HubClinicalAnswerProvider.PROVIDER_ID)) {
			throw new APIException("Cannot list profiles: the Hub provider is not in "
					+ ChartSearchAiConstants.GP_PROVIDERS_ENABLED + ".");
		}
		String endpoint = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL);
		if (endpoint == null || endpoint.trim().isEmpty()) {
			throw new APIException("Cannot list profiles: "
					+ ChartSearchAiConstants.GP_HUB_ENDPOINT_URL + " is not set.");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(modelsUri(endpoint))
				.timeout(Duration.ofSeconds(10))
				.GET();
		String apiKey = runtimeApiKey();
		if (apiKey != null && !apiKey.trim().isEmpty()) {
			request.header("Authorization", "Bearer " + apiKey.trim());
		}
		try {
			HttpResponse<InputStream> response = httpClient.send(
					request.build(), HttpResponse.BodyHandlers.ofInputStream());
			Map<String, Object> payload;
			try (InputStream body = new BoundedResponseStream(response.body(), RemoteLlmEngine.MAX_RESPONSE_BYTES)) {
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					throw new APIException("Hub profile discovery failed: HTTP " + response.statusCode());
				}
				payload = readWithinDeadline(body);
			}
			if (!(payload.get("data") instanceof List)) {
				throw new APIException("Hub profile discovery returned no data list.");
			}
			return payload;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Hub profile discovery was interrupted.", e);
		}
		catch (JsonProcessingException e) {
			// A parser quotes what it could not parse and callers log this message, so name the type
			// and leave the Hub's text on the cause (ADR Decision 102).
			throw new APIException("Hub profile discovery returned a body that is not JSON ("
					+ e.getClass().getSimpleName() + ").", e);
		}
		catch (IOException | IllegalArgumentException e) {
			// The exception, not its message: a refused connection's carries none on Java 17 and 21.
			throw new APIException("Hub profile discovery failed: " + e, e);
		}
	}

	/** Parses the body on its own thread and gives up, closing the body, when the deadline passes. */
	private Map<String, Object> readWithinDeadline(InputStream body) throws IOException {
		Future<Map<String, Object>> parsed = BODY_READERS.submit(
				() -> MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {}));
		try {
			return parsed.get(bodyDeadline.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			parsed.cancel(true);
			body.close();
			throw new APIException("Hub profile discovery timed out after " + bodyDeadline.getSeconds()
					+ " seconds waiting for the body.");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Hub profile discovery was interrupted.", e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			}
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new APIException("Hub profile discovery failed: " + cause, cause);
		}
	}

	static URI modelsUri(String chatEndpoint) {
		String normalized = chatEndpoint == null ? "" : chatEndpoint.trim();
		if (!normalized.endsWith("/v1/chat/completions")) {
			throw new IllegalArgumentException(
					"Hub endpoint must end with /v1/chat/completions.");
		}
		return URI.create(normalized.substring(
				0, normalized.length() - "/v1/chat/completions".length()) + "/v1/models");
	}

	private String runtimeApiKey() {
		Properties properties = Context.getRuntimeProperties();
		return properties == null ? null : properties.getProperty(ChartSearchAiConstants.RP_HUB_API_KEY);
	}
}
