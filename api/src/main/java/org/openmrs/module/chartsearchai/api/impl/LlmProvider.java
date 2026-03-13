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

import java.util.function.Consumer;

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

	static final String DEFAULT_SYSTEM_PROMPT = "You are a clinical assistant helping a clinician "
			+ "review a patient's chart. Answer ONLY the specific question asked. "
			+ "Use only the patient records below. "
			+ "Cite only the records that directly support your answer by number in brackets (e.g. [1], [3]). "
			+ "Do not list records that are irrelevant to the question. "
			+ "If the records do not contain enough information to answer, say exactly: "
			+ "\"No relevant information was found in the patient's records.\" and nothing else. "
			+ "Do not explain which records you checked or add any other commentary. "
			+ "Keep your answer concise — one to three sentences.\n\n"
			+ "Examples:\n\n"
			+ "Records:\n[1] Diagnosis: Zorblitis (2024-01-15)\n[2] Medication: Xanthuril 50mg daily\n"
			+ "[3] Lab: Flobnar level 12.4\n\n"
			+ "Question: What medications is the patient taking?\n"
			+ "Answer: The patient is currently taking Xanthuril 50mg daily [2].\n\n"
			+ "Question: Does the patient have diabetes?\n"
			+ "Answer: No relevant information was found in the patient's records.";

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
	public synchronized String search(String numberedRecords, String question) {
		LlamaModel llm = getModel();

		String prompt = buildPrompt(numberedRecords, question);
		int timeoutSeconds = getTimeoutSeconds();
		InferenceParameters params = createInferenceParameters(prompt);

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

		return cleanResponse(result.toString());
	}

	/**
	 * Streaming variant of {@link #search}. Calls the tokenConsumer for each token as it is
	 * generated, and returns the full response when complete.
	 *
	 * @param numberedRecords the numbered patient records text
	 * @param question the clinician's natural language question
	 * @param tokenConsumer called with each token fragment as it is generated
	 * @return the complete LLM response
	 * @throws APIException if the request exceeds the configured timeout
	 */
	public synchronized String searchStreaming(String numberedRecords, String question,
			Consumer<String> tokenConsumer) {
		LlamaModel llm = getModel();

		String prompt = buildPrompt(numberedRecords, question);
		int timeoutSeconds = getTimeoutSeconds();
		InferenceParameters params = createInferenceParameters(prompt);

		long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
		StringBuilder result = new StringBuilder();

		for (LlamaOutput output : llm.generate(params)) {
			if (System.currentTimeMillis() > deadline) {
				log.warn("LLM inference timed out after {} seconds", timeoutSeconds);
				throw new APIException("LLM inference timed out after " + timeoutSeconds
						+ " seconds. Try a more specific question or increase the timeout via "
						+ ChartSearchAiConstants.GP_LLM_TIMEOUT_SECONDS);
			}
			String token = output.toString();
			result.append(token);
			tokenConsumer.accept(token);
		}

		return cleanResponse(result.toString());
	}

	static String cleanResponse(String response) {
		String cleaned = response.trim();

		// Strip "Answer:" prefix the model sometimes echoes from few-shot examples
		if (cleaned.startsWith("Answer:")) {
			cleaned = cleaned.substring("Answer:".length()).trim();
		}

		return cleaned;
	}

	public synchronized void close() {
		if (model != null) {
			log.info("Closing LLM model");
			model.close();
			model = null;
		}
	}

	private InferenceParameters createInferenceParameters(String prompt) {
		return new InferenceParameters(prompt)
				.setTemperature(0.1f)
				.setNPredict(ChartSearchAiConstants.DEFAULT_MAX_TOKENS)
				.setRepeatPenalty(1.1f)
				.setRepeatLastN(256)
				.setFrequencyPenalty(0.1f);
	}

	protected String buildPrompt(String numberedRecords, String question) {
		return getSystemPrompt() + "\n\n"
				+ "Patient records:\n" + numberedRecords + "\n"
				+ "Question: " + question;
	}

	protected String getSystemPrompt() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SYSTEM_PROMPT);
		if (value != null && !value.trim().isEmpty()) {
			return value.trim();
		}
		return DEFAULT_SYSTEM_PROMPT;
	}

	protected int getTimeoutSeconds() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_TIMEOUT_SECONDS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
				log.warn("Timeout must be positive, got '{}', using default", parsed);
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
