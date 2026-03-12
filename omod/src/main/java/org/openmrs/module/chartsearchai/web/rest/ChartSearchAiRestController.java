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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
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

	@Autowired
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

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

		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			return new ResponseEntity<Object>(
					errorResponse("Patient not found: " + patientUuid), HttpStatus.NOT_FOUND);
		}

		ChartAnswer chartAnswer = chartSearchService.ask(patient, question);

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("answer", chartAnswer.getAnswer());

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
