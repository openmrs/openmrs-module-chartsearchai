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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.springframework.stereotype.Component;

/** Relays med-agent-hub's authoritative product-profile metadata without reshaping it. */
@Component("chartSearchAi.hubProfileService")
public class HubProfileService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	public Map<String, Object> listProfiles() {
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
			HttpResponse<String> response = httpClient.send(
					request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new APIException("Hub profile discovery failed: HTTP " + response.statusCode());
			}
			Map<String, Object> payload = MAPPER.readValue(
					response.body(), new TypeReference<Map<String, Object>>() {});
			if (!(payload.get("data") instanceof List)) {
				throw new APIException("Hub profile discovery returned no data list.");
			}
			return payload;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Hub profile discovery was interrupted.", e);
		}
		catch (IOException | IllegalArgumentException e) {
			throw new APIException("Hub profile discovery failed: " + e.getMessage(), e);
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
