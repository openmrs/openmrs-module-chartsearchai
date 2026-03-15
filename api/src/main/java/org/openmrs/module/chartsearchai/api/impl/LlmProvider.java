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
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
			+ "Include the relevant details from the records in your answer and cite them by their label in brackets exactly as shown (e.g. [Obs #1], [Allergy #2]). "
			+ "Do not list records that are irrelevant to the question. "
			+ "If the records do not contain enough information to answer, respond with: "
			+ "\"No relevant information was found in the patient's records.\" "
			+ "Keep your answer concise — one to three sentences. "
			+ "Respond with ONLY a JSON object in this exact format: {\"answer\": \"your answer here\"}\n\n"
			+ "Examples:\n\n"
			+ "Records:\n[Condition #1] Diagnosis: Zorblitis (2024-01-15)\n"
			+ "[Order #1] Medication: Xanthuril 50mg daily\n"
			+ "[Obs #1] Lab: Flobnar level 12.4\n\n"
			+ "Question: What medications is the patient taking?\n"
			+ "{\"answer\": \"The patient is currently taking Xanthuril 50mg daily [Order #1].\"}\n\n"
			+ "Question: Does the patient have diabetes?\n"
			+ "{\"answer\": \"No relevant information was found in the patient's records.\"}";

	// GBNF grammar that constrains output to: {"answer": "..."}
	// Allows any characters inside the string value including escaped quotes
	private static final String JSON_ANSWER_GRAMMAR =
			"root   ::= \"{\" ws \"\\\"answer\\\"\" ws \":\" ws string ws \"}\"\n"
			+ "string ::= \"\\\"\" chars \"\\\"\"\n"
			+ "chars  ::= char*\n"
			+ "char   ::= [^\"\\\\] | \"\\\\\" escape\n"
			+ "escape ::= [\"\\\\/bfnrt] | \"u\" hex hex hex hex\n"
			+ "hex    ::= [0-9a-fA-F]\n"
			+ "ws     ::= [ \\t\\n]*\n";

	private LlamaModel model;

	private String loadedModelPath;

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

		int timeoutSeconds = getTimeoutSeconds();
		InferenceParameters params = createInferenceParameters(numberedRecords, question);

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

		return extractAnswer(result.toString());
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

		int timeoutSeconds = getTimeoutSeconds();
		InferenceParameters params = createInferenceParameters(numberedRecords, question);

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

		return extractAnswer(result.toString());
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Extracts the answer from the JSON response produced by the grammar-constrained LLM.
	 * Expected format: {"answer": "..."}
	 * Falls back to returning the raw response if JSON parsing fails.
	 */
	static String extractAnswer(String response) {
		String trimmed = response.trim();
		if (trimmed.isEmpty()) {
			return trimmed;
		}

		try {
			Map<String, String> parsed = MAPPER.readValue(trimmed,
					new TypeReference<Map<String, String>>() {});
			String answer = parsed.get("answer");
			if (answer != null) {
				return answer.trim();
			}
		}
		catch (IOException e) {
			log.warn("LLM response did not contain expected JSON format, returning raw response");
		}
		return trimmed;
	}

	public synchronized void close() {
		if (model != null) {
			log.info("Closing LLM model");
			model.close();
			model = null;
		}
		loadedModelPath = null;
	}

	private InferenceParameters createInferenceParameters(String numberedRecords, String question) {
		String systemPrompt = getSystemPrompt();
		String userMessage = "Patient records:\n" + numberedRecords + "\n\nQuestion: " + question;

		String prompt = "<|begin_of_text|>"
				+ "<|start_header_id|>system<|end_header_id|>\n\n"
				+ systemPrompt + "<|eot_id|>"
				+ "<|start_header_id|>user<|end_header_id|>\n\n"
				+ userMessage + "<|eot_id|>"
				+ "<|start_header_id|>assistant<|end_header_id|>\n\n";

		return new InferenceParameters(prompt)
				.setTemperature(0.1f)
				.setNPredict(ChartSearchAiConstants.DEFAULT_MAX_TOKENS)
				.setGrammar(JSON_ANSWER_GRAMMAR)
				.setRepeatPenalty(1.1f)
				.setRepeatLastN(256)
				.setFrequencyPenalty(0.1f)
				.setStopStrings("<|eot_id|>", "<|end_of_text|>");
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
		String configuredPath = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		if (configuredPath == null || configuredPath.trim().isEmpty()) {
			throw new IllegalStateException(
					"LLM model path not configured. Set the global property: "
							+ ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		}
		String modelPath = ChartSearchAiConstants.resolveModelPath(
				configuredPath.trim(), ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);

		if (model != null && !modelPath.equals(loadedModelPath)) {
			log.info("LLM model path changed from {} to {}, reloading", loadedModelPath, modelPath);
			close();
		}

		if (model == null) {
			log.info("Loading LLM from {}", modelPath);
			ModelParameters modelParams = new ModelParameters()
					.setModel(modelPath)
					.setGpuLayers(0);
			model = new LlamaModel(modelParams);
			loadedModelPath = modelPath;
			log.info("LLM loaded successfully");
		}
		return model;
	}
}
