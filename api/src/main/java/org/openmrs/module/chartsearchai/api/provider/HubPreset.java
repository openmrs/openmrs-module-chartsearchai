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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One client-owned clinician preset mapping an OpenMRS preset id to a {@code med-agent-hub}
 * backing model and structured execution spec.
 */
public final class HubPreset {

	private final String id;

	private final String label;

	private final String description;

	private final boolean defaultPreset;

	private final String model;

	private final Map<String, Object> execution;

	public HubPreset(String id, String label, String description, boolean defaultPreset, String model,
			Map<String, Object> execution) {
		if (id == null || id.trim().isEmpty()) {
			throw new IllegalArgumentException("Hub preset id must be non-empty");
		}
		if (model == null || model.trim().isEmpty()) {
			throw new IllegalArgumentException("Hub preset model must be non-empty");
		}
		if (execution == null) {
			throw new IllegalArgumentException("Hub preset execution must be non-null");
		}
		Object modeObj = execution.get("mode");
		if (!(modeObj instanceof String) || ((String) modeObj).trim().isEmpty()) {
			throw new IllegalArgumentException("Hub preset execution.mode must be non-empty");
		}
		this.id = id.trim();
		this.label = (label == null || label.trim().isEmpty()) ? this.id : label.trim();
		this.description = description == null ? "" : description.trim();
		this.defaultPreset = defaultPreset;
		this.model = model.trim();
		this.execution = Collections.unmodifiableMap(copyMap(execution));
	}

	public String getId() {
		return id;
	}

	public String getLabel() {
		return label;
	}

	public String getDescription() {
		return description;
	}

	public boolean isDefaultPreset() {
		return defaultPreset;
	}

	public String getModel() {
		return model;
	}

	public Map<String, Object> getExecution() {
		return execution;
	}

	public String getMode() {
		return ((String) execution.get("mode")).trim();
	}

	public String getVerifierModel() {
		Object verifier = execution.get("verifier_model");
		if (verifier instanceof String && !((String) verifier).trim().isEmpty()) {
			return ((String) verifier).trim();
		}
		return null;
	}

	public Map<String, Object> toDiscoveryEntry(int selectionPriority,
			Map<String, Map<String, Object>> backingModelsById, Map<String, Object> executionModes) {
		List<String> unavailableReasons = new ArrayList<>();
		Map<String, Object> primaryMeta = backingModelsById.get(model);
		if (primaryMeta == null || Boolean.FALSE.equals(primaryMeta.get("available"))) {
			unavailableReasons.add("model_unavailable:" + model);
		}
		String verifierModel = getVerifierModel();
		if (verifierModel != null) {
			Map<String, Object> verifierMeta = backingModelsById.get(verifierModel);
			if (verifierMeta == null || Boolean.FALSE.equals(verifierMeta.get("available"))) {
				unavailableReasons.add("verifier_model_unavailable:" + verifierModel);
			}
		}
		String mode = getMode();
		if (executionModes != null && !executionModes.containsKey(mode)) {
			unavailableReasons.add("execution_mode_unavailable:" + mode);
		}

		boolean indepth = isIndepth(executionModes, mode);
		List<String> requiredModels = new ArrayList<>();
		requiredModels.add(model);
		if (verifierModel != null && !verifierModel.equals(model)) {
			requiredModels.add(verifierModel);
		}

		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("id", id);
		entry.put("label", label);
		entry.put("description", description);
		entry.put("model", model);
		entry.put("mode", mode);
		entry.put("execution", execution);
		entry.put("available", unavailableReasons.isEmpty());
		entry.put("default", defaultPreset);
		entry.put("visibility", "product");
		entry.put("selection_priority", selectionPriority);
		entry.put("topology", "team".equals(mode) ? "team" : "single");
		entry.put("staged", indepth || verifierModel != null || "fast_checked".equals(mode) || "verified".equals(mode));
		entry.put("validation",
				verifierModel != null || "fast_checked".equals(mode) || "verified".equals(mode)
						|| "agree".equals(mode) || "team".equals(mode) || "standard".equals(mode));
		entry.put("indepth", indepth);
		entry.put("temporal_enforcement", temporalEnforcement());
		entry.put("stages", stagesForMode(executionModes, mode, indepth));
		entry.put("required_models", Collections.unmodifiableList(requiredModels));
		entry.put("context_window", contextWindow(primaryMeta));
		entry.put("exact_tokenizer", Boolean.FALSE);
		entry.put("unavailable_reasons", Collections.unmodifiableList(unavailableReasons));
		return Collections.unmodifiableMap(entry);
	}

	private boolean isIndepth(Map<String, Object> executionModes, String mode) {
		Object explicit = execution.get("indepth");
		if (explicit instanceof Boolean) {
			return (Boolean) explicit;
		}
		if (executionModes != null && executionModes.get(mode) instanceof Map) {
			Object modeDefault = ((Map<?, ?>) executionModes.get(mode)).get("indepth");
			if (modeDefault instanceof Boolean) {
				return (Boolean) modeDefault;
			}
		}
		return !"quick".equals(mode) && !"bare_eval".equals(mode);
	}

	private String temporalEnforcement() {
		Object checksObj = execution.get("checks");
		if (checksObj instanceof Map) {
			Object temporal = ((Map<?, ?>) checksObj).get("temporal");
			if (temporal instanceof String && !((String) temporal).trim().isEmpty()) {
				return ((String) temporal).trim();
			}
		}
		return "enforce";
	}

	private List<String> stagesForMode(Map<String, Object> executionModes, String mode, boolean indepth) {
		if (executionModes != null && executionModes.get(mode) instanceof Map) {
			Object stagesObj = ((Map<?, ?>) executionModes.get(mode)).get("stages");
			if (stagesObj instanceof List) {
				List<String> stages = new ArrayList<>();
				for (Object stage : (List<?>) stagesObj) {
					if (stage instanceof String && !((String) stage).trim().isEmpty()) {
						stages.add(((String) stage).trim());
					}
				}
				if (!stages.isEmpty()) {
					return Collections.unmodifiableList(stages);
				}
			}
		}
		return indepth ? Collections.unmodifiableList(Arrays.asList("answer", "indepth"))
				: Collections.singletonList("answer");
	}

	private static Integer contextWindow(Map<String, Object> primaryMeta) {
		if (primaryMeta == null) {
			return null;
		}
		Object backendMetaObj = primaryMeta.get("backend_metadata");
		if (!(backendMetaObj instanceof Map)) {
			return null;
		}
		Map<?, ?> backendMeta = (Map<?, ?>) backendMetaObj;
		for (String key : Arrays.asList("context_window", "n_ctx", "max_model_len")) {
			Object value = backendMeta.get(key);
			if (value instanceof Number) {
				return ((Number) value).intValue();
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> copyMap(Map<String, Object> source) {
		Map<String, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			Object val = entry.getValue();
			if (val instanceof Map) {
				copy.put(entry.getKey(), Collections.unmodifiableMap(copyMap((Map<String, Object>) val)));
			} else if (val instanceof List) {
				copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>((List<Object>) val)));
			} else {
				copy.put(entry.getKey(), val);
			}
		}
		return copy;
	}
}
