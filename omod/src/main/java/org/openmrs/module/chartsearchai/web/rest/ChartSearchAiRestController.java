/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * REST endpoint for AI-powered chart search.
 *
 * <pre>
 * POST /ws/rest/v1/chartsearchai/search
 * {
 *   "patientUuid": "patient-uuid-here",
 *   "question": "What medications is this patient on?"
 * }
 * </pre>
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/chartsearchai")
public class ChartSearchAiRestController {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiRestController.class);

	private static final int MAX_QUESTION_LENGTH = 1000;

	private static final String DISCLAIMER = "This response is AI-generated and may not be "
			+ "accurate. It is not a substitute for clinical judgment. Always verify against "
			+ "the patient's medical records.";

	@Autowired
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

	@Autowired
	private ChartSearchAiDAO dao;

	@Transactional
	@RequestMapping(value = "/search", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> search(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String patientUuid = body.get("patientUuid");
		String question = body.get("question");

		if (patientUuid == null || patientUuid.trim().isEmpty()) {
			return new ResponseEntity<Object>(
					errorResponse("patientUuid is required"), HttpStatus.BAD_REQUEST);
		}
		if (question == null || question.trim().isEmpty()) {
			return new ResponseEntity<Object>(
					errorResponse("question is required"), HttpStatus.BAD_REQUEST);
		}
		if (question.length() > MAX_QUESTION_LENGTH) {
			return new ResponseEntity<Object>(
					errorResponse("question exceeds maximum length of "
							+ MAX_QUESTION_LENGTH + " characters"),
					HttpStatus.BAD_REQUEST);
		}

		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			return new ResponseEntity<Object>(
					errorResponse("Patient not found: " + patientUuid), HttpStatus.NOT_FOUND);
		}

		User user = Context.getAuthenticatedUser();

		String searchMode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (searchMode == null || searchMode.trim().isEmpty()) {
			searchMode = ChartSearchAiConstants.SEARCH_MODE_LLM;
		}

		ChartAnswer chartAnswer;
		long responseTimeMs;
		try {
			long startTime = System.currentTimeMillis();
			chartAnswer = chartSearchService.ask(patient, question);
			responseTimeMs = System.currentTimeMillis() - startTime;
		}
		catch (IllegalStateException e) {
			log.error("Chart search configuration error", e);
			return new ResponseEntity<Object>(
					errorResponse(e.getMessage()), HttpStatus.SERVICE_UNAVAILABLE);
		}
		catch (Exception e) {
			log.error("Chart search failed for patient {}", patient.getUuid(), e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search failed. Please try again or contact your administrator."),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}

		ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
		auditLog.setUser(user);
		auditLog.setPatient(patient);
		auditLog.setQuestion(question);
		auditLog.setAnswer(chartAnswer.getAnswer());
		auditLog.setReferenceCount(chartAnswer.getReferences().size());
		auditLog.setSearchMode(searchMode);
		auditLog.setResponseTimeMs(responseTimeMs);
		auditLog.setDateCreated(new Date());
		dao.saveAuditLog(auditLog);

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("answer", chartAnswer.getAnswer());
		response.put("disclaimer", DISCLAIMER);

		List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
		for (RecordReference ref : chartAnswer.getReferences()) {
			Map<String, Object> refMap = new HashMap<String, Object>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceId", ref.getResourceId());
			refs.add(refMap);
		}
		response.put("references", refs);

		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	private Map<String, String> errorResponse(String message) {
		Map<String, String> error = new HashMap<String, String>();
		error.put("error", message);
		return error;
	}
}
