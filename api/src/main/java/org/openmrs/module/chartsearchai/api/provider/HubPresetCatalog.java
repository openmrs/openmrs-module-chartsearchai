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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses and owns the OpenMRS clinician Hub preset catalog configured via
 * {@link org.openmrs.module.chartsearchai.ChartSearchAiConstants#GP_HUB_PRESETS}, falling back to
 * the validated three-preset baseline ({@code quick}, {@code standard}, {@code verified}) when the
 * global property is unset or empty.
 */
public final class HubPresetCatalog {

	public static final String DEFAULT_BACKING_MODEL = "gemma-4-12b";

	public static final String DEFAULT_VERIFIER_MODEL = "qwen3.5-9b";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HubPresetCatalog DEFAULT_CATALOG = buildDefaultCatalog();

	private final List<HubPreset> presets;

	private final Map<String, HubPreset> byId;

	private HubPresetCatalog(List<HubPreset> presets) {
		if (presets == null || presets.isEmpty()) {
			throw new IllegalArgumentException("Hub preset catalog must define at least one preset");
		}
		List<HubPreset> ordered = new ArrayList<>();
		Map<String, HubPreset> lookup = new LinkedHashMap<>();
		int defaultsCount = 0;
		for (HubPreset preset : presets) {
			if (lookup.containsKey(preset.getId())) {
				throw new IllegalArgumentException("Duplicate Hub preset id: " + preset.getId());
			}
			if (preset.isDefaultPreset()) {
				defaultsCount++;
				if (defaultsCount > 1) {
					throw new IllegalArgumentException("Hub preset catalog may mark at most one preset as default");
				}
			}
			ordered.add(preset);
			lookup.put(preset.getId(), preset);
		}
		this.presets = Collections.unmodifiableList(ordered);
		this.byId = Collections.unmodifiableMap(lookup);
	}

	public static HubPresetCatalog defaultCatalog() {
		return DEFAULT_CATALOG;
	}

	public static HubPresetCatalog load(String rawJson) {
		if (rawJson == null || rawJson.trim().isEmpty()) {
			return DEFAULT_CATALOG;
		}
		List<Map<String, Object>> rawList;
		try {
			rawList = MAPPER.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() {});
		}
		catch (JsonProcessingException e) {
			throw new IllegalArgumentException(
					"Invalid chartsearchai.hub.presets JSON (" + e.getClass().getSimpleName() + ")", e);
		}
		if (rawList == null || rawList.isEmpty()) {
			throw new IllegalArgumentException("chartsearchai.hub.presets must be a non-empty JSON array");
		}
		List<HubPreset> parsed = new ArrayList<>(rawList.size());
		for (Map<String, Object> item : rawList) {
			if (item == null) {
				throw new IllegalArgumentException("Hub preset entries must be JSON objects");
			}
			String id = asTrimmedString(item.get("id"));
			String label = asTrimmedString(item.get("label"));
			String description = asTrimmedString(item.get("description"));
			boolean isDefault = Boolean.TRUE.equals(item.get("default"));
			String model = asTrimmedString(item.get("model"));
			Object execObj = item.get("execution");
			if (!(execObj instanceof Map)) {
				throw new IllegalArgumentException("Hub preset execution must be a JSON object");
			}
			Map<String, Object> execution = normalizeExecution((Map<?, ?>) execObj, id);
			parsed.add(new HubPreset(id, label, description, isDefault, model, execution));
		}
		return new HubPresetCatalog(parsed);
	}

	public List<HubPreset> getPresets() {
		return presets;
	}

	public HubPreset findById(String presetId) {
		if (presetId == null) {
			return null;
		}
		return byId.get(presetId.trim());
	}

	private static Map<String, Object> normalizeExecution(Map<?, ?> raw, String presetId) {
		Map<String, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (entry.getKey() != null) {
				copy.put(String.valueOf(entry.getKey()), entry.getValue());
			}
		}
		if (!copy.containsKey("preset_id") && presetId != null && !presetId.isEmpty()) {
			copy.put("preset_id", presetId);
		}
		return copy;
	}

	private static String asTrimmedString(Object value) {
		return value instanceof String ? ((String) value).trim() : null;
	}

	private static HubPresetCatalog buildDefaultCatalog() {
		List<HubPreset> defaults = new ArrayList<>(3);
		defaults.add(new HubPreset("quick", "Quick answer", "Single fast pass over the chart", false,
				DEFAULT_BACKING_MODEL, executionSpec("quick", "quick", null, false)));
		defaults.add(new HubPreset("standard", "Standard answer", "Fast answer plus detailed background explanation",
				true, DEFAULT_BACKING_MODEL, executionSpec("standard", "standard", null, false)));
		defaults.add(new HubPreset("verified", "Cross-checked answer",
				"Fast answer, detailed view, and independent clinical cross-check", false, DEFAULT_BACKING_MODEL,
				executionSpec("verified", "verified", DEFAULT_VERIFIER_MODEL, true)));
		return new HubPresetCatalog(defaults);
	}

	private static Map<String, Object> executionSpec(String mode, String presetId, String verifierModel,
			boolean foldedGrounding) {
		Map<String, Object> checks = new LinkedHashMap<>();
		checks.put("temporal", "enforce");
		if (foldedGrounding) {
			checks.put("grounding", "folded");
		}
		checks.put("confidence", "levels");

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("mode", mode);
		spec.put("preset_id", presetId);
		if (verifierModel != null) {
			spec.put("verifier_model", verifierModel);
		}
		spec.put("checks", checks);
		return spec;
	}
}
