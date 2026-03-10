/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

public class ChartSearchAiConstants {

	public static final String PRIV_QUERY_PATIENT_DATA = "AI Query Patient Data";

	public static final String PRIV_VIEW_AUDIT_LOG = "View AI Audit Log";

	public static final String GP_LLM_PROVIDER = "chartsearchai.llm.provider";

	public static final String GP_LLM_API_KEY = "chartsearchai.llm.apiKey";

	public static final String GP_LLM_MODEL = "chartsearchai.llm.model";

	public static final String GP_LLM_API_URL = "chartsearchai.llm.apiUrl";

	public static final String GP_RETRIEVAL_TOP_K = "chartsearchai.retrieval.topK";

	public static final String GP_EMBEDDING_MODEL_PATH = "chartsearchai.embedding.modelPath";

	public static final String GP_EMBEDDING_PROVIDER = "chartsearchai.embedding.provider";

	public static final String DEFAULT_LLM_PROVIDER = "claude";

	public static final String DEFAULT_LLM_MODEL = "claude-sonnet-4-6";

	public static final String DEFAULT_LLM_API_URL = "https://api.anthropic.com/v1/messages";

	public static final int DEFAULT_RETRIEVAL_TOP_K = 20;

	public static final int EMBEDDING_DIMENSIONS = 384;

	private ChartSearchAiConstants() {
	}
}
