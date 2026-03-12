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

	public static final String GP_EMBEDDING_MODEL_PATH = "chartsearchai.embedding.modelPath";

	public static final String GP_LLM_MODEL_PATH = "chartsearchai.llm.modelPath";

	public static final String GP_SEARCH_MODE = "chartsearchai.searchMode";

	public static final String SEARCH_MODE_LLM = "llm";

	public static final String SEARCH_MODE_EMBEDDING = "embedding";

	public static final String GP_EMBEDDING_PROVIDER = "chartsearchai.embedding.provider";

	public static final String EMBEDDING_PROVIDER_ONNX = "onnx";

	public static final String EMBEDDING_PROVIDER_TERM_FREQUENCY = "term-frequency";

	public static final int EMBEDDING_DIMENSIONS = 384;

	public static final int DEFAULT_RETRIEVAL_TOP_K = 15;

	private ChartSearchAiConstants() {
	}
}
