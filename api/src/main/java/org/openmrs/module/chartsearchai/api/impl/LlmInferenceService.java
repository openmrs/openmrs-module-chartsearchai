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

import java.util.List;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Answers natural language questions about a patient's chart using direct LLM inference.
 * Serializes the full patient chart, sends it to a local Llama model with the question,
 * and returns the response with source citations that map back to OpenMRS records.
 *
 * <p>Requires a GGUF model file configured via the {@code chartsearchai.llm.modelPath}
 * global property (e.g., {@code llama-3.2-3b-instruct-q4_k_m.gguf}).</p>
 */
@Service
public class LlmInferenceService {

	private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);

	private static final String SYSTEM_PROMPT = "You are a clinical assistant helping a clinician "
			+ "review a patient's chart. Answer the question using only the patient records below. "
			+ "Cite records by number in brackets (e.g. [1], [3]). "
			+ "If the records do not contain enough information to answer, say so.";

	@Autowired
	private PatientChartSerializer chartSerializer;

	private LlamaModel model;

	/**
	 * Ask a question about a patient's chart.
	 *
	 * @param patient the patient whose chart to query
	 * @param question the clinician's natural language question
	 * @return the LLM's answer with source references for citation linking
	 */
	public ChartAnswer ask(Patient patient, String question) {
		LlamaModel llm = getModel();
		PatientChart chart = chartSerializer.serialize(patient);

		String prompt = SYSTEM_PROMPT + "\n\n"
				+ "Patient records:\n" + chart.getText() + "\n"
				+ "Question: " + question;

		log.debug("Sending prompt to LLM ({} records)", chart.getReferences().size());

		InferenceParameters params = new InferenceParameters(prompt)
				.setTemperature(0.1f);

		String response = llm.complete(params);

		return new ChartAnswer(response, chart.getReferences());
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

	/**
	 * The LLM's answer along with record references for linking citations to OpenMRS records.
	 */
	public static class ChartAnswer {

		private final String answer;

		private final List<RecordReference> references;

		public ChartAnswer(String answer, List<RecordReference> references) {
			this.answer = answer;
			this.references = references;
		}

		/**
		 * The LLM's response text, containing citation numbers in brackets (e.g. [1], [3]).
		 */
		public String getAnswer() {
			return answer;
		}

		/**
		 * The ordered list of record references. Use citation numbers from the answer to
		 * look up the corresponding resource type and ID for linking to the source record.
		 */
		public List<RecordReference> getReferences() {
			return references;
		}
	}
}
