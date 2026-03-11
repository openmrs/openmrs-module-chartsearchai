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
import de.kherud.llama.ModelParameters;

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
	 *
	 * @param numberedRecords the numbered patient records text
	 * @param question the clinician's natural language question
	 * @return the LLM's response with inline citations
	 */
	public String ask(String numberedRecords, String question) {
		LlamaModel llm = getModel();

		String prompt = SYSTEM_PROMPT + "\n\n"
				+ "Patient records:\n" + numberedRecords + "\n"
				+ "Question: " + question;

		InferenceParameters params = new InferenceParameters(prompt)
				.setTemperature(0.1f);

		return llm.complete(params);
	}

	private synchronized LlamaModel getModel() {
		if (model == null) {
			String modelPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_PATH);
			if (modelPath == null || modelPath.trim().isEmpty()) {
				throw new IllegalStateException(
						"LLM model path not configured. Set the global property: "
								+ ChartSearchAiConstants.GP_LLM_MODEL_PATH);
			}
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
