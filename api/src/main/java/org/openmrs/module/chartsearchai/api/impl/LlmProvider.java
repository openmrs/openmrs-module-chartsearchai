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

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages the local LLM model lifecycle and provides inference. The model is loaded
 * once on first use and kept in memory for subsequent calls. Shared by both the direct
 * LLM inference and embedding-based RAG search services.
 */
@Component
public class LlmProvider {

	private static final Logger log = LoggerFactory.getLogger(LlmProvider.class);

	private static final String SYSTEM_PROMPT = "You are a clinical assistant helping a clinician "
			+ "review a patient's chart. Answer the question using only the patient records below. "
			+ "Cite records by number in brackets (e.g. [1], [3]). "
			+ "If the records do not contain enough information to answer, say so.";

	private LlamaModel model;

	/**
	 * Send numbered patient records and a question to the LLM for synthesis.
	 * Uses streaming generation with a wall-clock timeout to prevent indefinite blocking.
	 *
	 * @param numberedRecords the numbered patient records text
	 * @param question the clinician's natural language question
	 * @return the LLM's response with inline citations
	 * @throws APIException if the request exceeds the configured timeout
	 */
	public String ask(String numberedRecords, String question) {
		LlamaModel llm = getModel();

		String prompt = SYSTEM_PROMPT + "\n\n"
				+ "Patient records:\n" + numberedRecords + "\n"
				+ "Question: " + question;

		int timeoutSeconds = getTimeoutSeconds();
		InferenceParameters params = new InferenceParameters(prompt)
				.setTemperature(0.1f)
				.setNPredict(ChartSearchAiConstants.DEFAULT_MAX_TOKENS);

		long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
		StringBuilder result = new StringBuilder();

		for (LlamaOutput output : llm.generate(params)) {
			if (System.currentTimeMillis() > deadline) {
				log.warn("LLM inference timed out after {} seconds", timeoutSeconds);
				throw new APIException("LLM inference timed out after " + timeoutSeconds
						+ " seconds. Try a more specific question or increase the timeout via "
						+ ChartSearchAiConstants.GP_LLM_TIMEOUT_SECONDS);
			}
			result.append(output);
		}

		return result.toString();
	}

	public synchronized void close() {
		if (model != null) {
			log.info("Closing LLM model");
			model.close();
			model = null;
		}
	}

	private int getTimeoutSeconds() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_TIMEOUT_SECONDS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Invalid timeout value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_TIMEOUT_SECONDS;
	}

	private synchronized LlamaModel getModel() {
		if (model == null) {
			String configuredPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
			if (configuredPath == null || configuredPath.trim().isEmpty()) {
				throw new IllegalStateException(
						"LLM model path not configured. Set the global property: "
								+ ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
			}
			String modelPath = ChartSearchAiConstants.resolveModelPath(
					configuredPath.trim(), ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
			log.info("Loading LLM from {}", modelPath);
			ModelParameters modelParams = new ModelParameters()
					.setModel(modelPath)
					.setGpuLayers(0);
			model = new LlamaModel(modelParams);
			log.info("LLM loaded successfully");
		}
		return model;
	}
}
