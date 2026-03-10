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

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.model.AiQueryLog;

/**
 * Primary service for AI-powered chart search. Provides natural-language querying of patient charts
 * with clinical safety guardrails, citation requirements, and full audit logging.
 */
public interface ChartSearchAiService extends OpenmrsService {

	/**
	 * Ask a natural-language question about a specific patient's chart. The patient's clinical data
	 * is retrieved, relevant chunks are selected via vector similarity, and the question is answered
	 * by an LLM with clinical safety guardrails.
	 *
	 * @param patient the patient whose chart to query
	 * @param question the clinician's natural-language question
	 * @return the AI response with citations and any safety warnings
	 */
	@Authorized(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)
	AiQueryResponse queryPatientChart(Patient patient, String question);

	/**
	 * Generate a clinical summary for a patient based on their complete chart data.
	 *
	 * @param patient the patient to summarize
	 * @return the AI-generated summary with citations
	 */
	@Authorized(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)
	AiQueryResponse generatePatientSummary(Patient patient);

	/**
	 * Retrieve the audit log of all AI queries made for a specific patient.
	 *
	 * @param patient the patient whose query history to retrieve
	 * @return list of query log entries, most recent first
	 */
	@Authorized(ChartSearchAiConstants.PRIV_VIEW_AUDIT_LOG)
	List<AiQueryLog> getQueryAuditLog(Patient patient);
}
