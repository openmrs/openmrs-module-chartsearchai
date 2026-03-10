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

import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.adapter.LlmAdapter;
import org.openmrs.module.chartsearchai.adapter.LlmResponse;
import org.openmrs.module.chartsearchai.api.AiQueryResponse;
import org.openmrs.module.chartsearchai.api.ChartSearchAiService;
import org.openmrs.module.chartsearchai.api.EmbeddingService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.AiQueryLog;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;
import org.openmrs.module.chartsearchai.prompt.ClinicalSystemPrompt;
import org.openmrs.module.chartsearchai.prompt.GuardrailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the chart search AI service. Orchestrates the full RAG pipeline: indexing,
 * retrieval, prompt assembly, LLM call, guardrail validation, and audit logging.
 */
@Service("chartsearchai.ChartSearchAiService")
@Transactional
public class ChartSearchAiServiceImpl extends BaseOpenmrsService implements ChartSearchAiService {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiServiceImpl.class);

	@Autowired
	private EmbeddingService embeddingService;

	@Autowired
	private LlmAdapter llmAdapter;

	@Autowired
	private ChartSearchAiDAO dao;

	@Override
	public AiQueryResponse queryPatientChart(Patient patient, String question) {
		log.info("Processing chart query for patient {}: {}", patient.getUuid(), question);

		// 1. Ensure patient is indexed
		if (!embeddingService.isPatientIndexed(patient)) {
			embeddingService.indexPatient(patient);
		}

		// 2. Retrieve relevant chunks
		int topK = getTopK();
		List<EmbeddingChunk> relevantChunks = embeddingService.retrieveRelevantChunks(patient, question, topK);

		// 3. Assemble prompt with clinical context
		String userPrompt = assembleUserPrompt(patient, relevantChunks, question);

		// 4. Call LLM
		LlmResponse llmResponse = llmAdapter.chat(ClinicalSystemPrompt.SYSTEM_PROMPT, userPrompt);

		// 5. Validate guardrails
		GuardrailValidator.validate(llmResponse);

		// 6. Audit log
		saveAuditLog(patient, question, llmResponse, relevantChunks);

		return new AiQueryResponse(
				llmResponse.getText(),
				relevantChunks,
				llmResponse.getWarnings(),
				llmResponse.getModelId());
	}

	@Override
	public AiQueryResponse generatePatientSummary(Patient patient) {
		return queryPatientChart(patient,
				"Please provide a comprehensive clinical summary of this patient's chart, "
				+ "including active conditions, current medications, recent encounters, "
				+ "allergies, and any notable lab trends.");
	}

	@Override
	@Transactional(readOnly = true)
	public List<AiQueryLog> getQueryAuditLog(Patient patient) {
		return dao.getQueryLogsByPatient(patient);
	}

	private String assembleUserPrompt(Patient patient, List<EmbeddingChunk> chunks, String question) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("## PATIENT CLINICAL DATA\n");
		prompt.append("Patient: ").append(patient.getFamilyName());
		prompt.append(", ").append(patient.getGender());
		if (patient.getBirthdate() != null) {
			prompt.append(", DOB: ").append(patient.getBirthdate());
		}
		prompt.append("\n\n");

		for (EmbeddingChunk chunk : chunks) {
			prompt.append("---\n");
			prompt.append("[").append(chunk.getChunkType());
			if (chunk.getSourceDate() != null) {
				prompt.append(" | ").append(chunk.getSourceDate());
			}
			if (chunk.getSourceUuid() != null) {
				prompt.append(" | ID:").append(chunk.getSourceUuid());
			}
			prompt.append("]\n");
			prompt.append(chunk.getChunkText()).append("\n");
		}

		prompt.append("\n## CLINICIAN QUESTION\n");
		prompt.append(question).append("\n");

		return prompt.toString();
	}

	private void saveAuditLog(Patient patient, String question, LlmResponse response,
			List<EmbeddingChunk> chunks) {
		AiQueryLog auditEntry = new AiQueryLog();
		auditEntry.setPatient(patient);
		auditEntry.setUser(Context.getAuthenticatedUser());
		auditEntry.setQueryText(question);
		auditEntry.setResponseText(response.getText());
		auditEntry.setModelUsed(response.getModelId());
		auditEntry.setDateQueried(new Date());

		StringBuilder chunkIds = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			if (i > 0) {
				chunkIds.append(",");
			}
			chunkIds.append(chunks.get(i).getChunkId());
		}
		auditEntry.setChunksUsed(chunkIds.toString());

		dao.saveQueryLog(auditEntry);
	}

	private int getTopK() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_TOP_K);
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			} catch (NumberFormatException e) {
				log.warn("Invalid topK value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_RETRIEVAL_TOP_K;
	}
}
