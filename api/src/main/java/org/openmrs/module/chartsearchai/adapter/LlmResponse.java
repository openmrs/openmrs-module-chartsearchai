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

import java.util.ArrayList;
import java.util.List;

/**
 * Response from an LLM provider, including the generated text, the model that produced it, and any
 * guardrail warnings detected during post-processing.
 */
public class LlmResponse {

	private String text;

	private String modelId;

	private List<String> warnings = new ArrayList<>();

	public LlmResponse() {
	}

	public LlmResponse(String text, String modelId) {
		this.text = text;
		this.modelId = modelId;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getModelId() {
		return modelId;
	}

	public void setModelId(String modelId) {
		this.modelId = modelId;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public void addWarning(String warning) {
		this.warnings.add(warning);
	}
}
