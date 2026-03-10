/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.AiQueryResponse;
import org.openmrs.module.chartsearchai.api.ChartSearchAiService;
import org.openmrs.module.chartsearchai.api.EmbeddingService;
import org.openmrs.module.chartsearchai.model.AiQueryLog;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * REST controller for AI chart search operations. Exposes endpoints for querying patient charts,
 * generating summaries, managing the embedding index, and viewing audit logs.
 */
@Controller
@RequestMapping("/rest/v1/chartsearchai")
public class ChartSearchAiRestController {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiRestController.class);

	/**
	 * Query a patient's chart with a natural-language question.
	 *
	 * POST /rest/v1/chartsearchai/query/{patientUuid}
	 * Body: { "question": "Does this patient have any drug allergies?" }
	 */
	@RequestMapping(value = "/query/{patientUuid}", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> queryPatientChart(
			@PathVariable("patientUuid") String patientUuid,
			@RequestBody Map<String, String> requestBody) {

		String question = requestBody.get("question");
		if (question == null || question.trim().isEmpty()) {
			return badRequest("'question' is required in the request body");
		}

		Patient patient = getPatientOrNull(patientUuid);
		if (patient == null) {
			return notFound("Patient not found: " + patientUuid);
		}

		ChartSearchAiService service = Context.getService(ChartSearchAiService.class);
		AiQueryResponse response = service.queryPatientChart(patient, question);

		Map<String, Object> result = new HashMap<>();
		result.put("response", response.getResponseText());
		result.put("model", response.getModelUsed());
		result.put("warnings", response.getWarnings());
		result.put("sourceChunkCount", response.getSourceChunks() != null ? response.getSourceChunks().size() : 0);

		List<Map<String, Object>> sources = new ArrayList<>();
		if (response.getSourceChunks() != null) {
			for (EmbeddingChunk chunk : response.getSourceChunks()) {
				Map<String, Object> source = new HashMap<>();
				source.put("type", chunk.getChunkType());
				source.put("sourceUuid", chunk.getSourceUuid());
				source.put("sourceDate", chunk.getSourceDate());
				sources.add(source);
			}
		}
		result.put("sources", sources);

		return new ResponseEntity<>(result, HttpStatus.OK);
	}

	/**
	 * Generate a clinical summary for a patient.
	 *
	 * POST /rest/v1/chartsearchai/summary/{patientUuid}
	 */
	@RequestMapping(value = "/summary/{patientUuid}", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> generateSummary(
			@PathVariable("patientUuid") String patientUuid) {

		Patient patient = getPatientOrNull(patientUuid);
		if (patient == null) {
			return notFound("Patient not found: " + patientUuid);
		}

		ChartSearchAiService service = Context.getService(ChartSearchAiService.class);
		AiQueryResponse response = service.generatePatientSummary(patient);

		Map<String, Object> result = new HashMap<>();
		result.put("summary", response.getResponseText());
		result.put("model", response.getModelUsed());
		result.put("warnings", response.getWarnings());

		return new ResponseEntity<>(result, HttpStatus.OK);
	}

	/**
	 * Trigger re-indexing of a patient's clinical data.
	 *
	 * POST /rest/v1/chartsearchai/index/{patientUuid}
	 */
	@RequestMapping(value = "/index/{patientUuid}", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> indexPatient(
			@PathVariable("patientUuid") String patientUuid) {

		Patient patient = getPatientOrNull(patientUuid);
		if (patient == null) {
			return notFound("Patient not found: " + patientUuid);
		}

		EmbeddingService embeddingService = Context.getService(EmbeddingService.class);
		embeddingService.indexPatient(patient);

		Map<String, Object> result = new HashMap<>();
		result.put("status", "indexed");
		result.put("patientUuid", patientUuid);

		return new ResponseEntity<>(result, HttpStatus.OK);
	}

	/**
	 * Get the AI query audit log for a patient.
	 *
	 * GET /rest/v1/chartsearchai/audit/{patientUuid}
	 */
	@RequestMapping(value = "/audit/{patientUuid}", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getAuditLog(
			@PathVariable("patientUuid") String patientUuid) {

		Patient patient = getPatientOrNull(patientUuid);
		if (patient == null) {
			return notFound("Patient not found: " + patientUuid);
		}

		ChartSearchAiService service = Context.getService(ChartSearchAiService.class);
		List<AiQueryLog> logs = service.getQueryAuditLog(patient);

		List<Map<String, Object>> entries = new ArrayList<>();
		for (AiQueryLog entry : logs) {
			Map<String, Object> logEntry = new HashMap<>();
			logEntry.put("query", entry.getQueryText());
			logEntry.put("response", entry.getResponseText());
			logEntry.put("model", entry.getModelUsed());
			logEntry.put("date", entry.getDateQueried());
			if (entry.getUser() != null) {
				logEntry.put("user", entry.getUser().getUsername());
			}
			entries.add(logEntry);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("auditLog", entries);

		return new ResponseEntity<>(result, HttpStatus.OK);
	}

	private Patient getPatientOrNull(String uuid) {
		PatientService patientService = Context.getPatientService();
		return patientService.getPatientByUuid(uuid);
	}

	private ResponseEntity<Map<String, Object>> badRequest(String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("error", message);
		return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
	}

	private ResponseEntity<Map<String, Object>> notFound(String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("error", message);
		return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
	}
}
