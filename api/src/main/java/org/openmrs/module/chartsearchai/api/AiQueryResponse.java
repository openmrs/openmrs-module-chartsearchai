/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.List;

import org.openmrs.module.chartsearchai.model.EmbeddingChunk;

/**
 * Result object returned from a chart search AI query. Contains the generated response text, the
 * clinical data chunks that were used as context, and any safety warnings from guardrail validation.
 */
public class AiQueryResponse {

	private String responseText;

	private List<EmbeddingChunk> sourceChunks;

	private List<String> warnings;

	private String modelUsed;

	public AiQueryResponse() {
	}

	public AiQueryResponse(String responseText, List<EmbeddingChunk> sourceChunks, List<String> warnings,
			String modelUsed) {
		this.responseText = responseText;
		this.sourceChunks = sourceChunks;
		this.warnings = warnings;
		this.modelUsed = modelUsed;
	}

	public String getResponseText() {
		return responseText;
	}

	public void setResponseText(String responseText) {
		this.responseText = responseText;
	}

	public List<EmbeddingChunk> getSourceChunks() {
		return sourceChunks;
	}

	public void setSourceChunks(List<EmbeddingChunk> sourceChunks) {
		this.sourceChunks = sourceChunks;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = warnings;
	}

	public String getModelUsed() {
		return modelUsed;
	}

	public void setModelUsed(String modelUsed) {
		this.modelUsed = modelUsed;
	}
}
