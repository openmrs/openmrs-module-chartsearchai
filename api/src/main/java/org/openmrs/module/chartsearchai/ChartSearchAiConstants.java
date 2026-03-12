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

import java.io.File;
import java.io.IOException;

import org.openmrs.util.OpenmrsUtil;

public class ChartSearchAiConstants {

	public static final String PRIV_QUERY_PATIENT_DATA = "AI Query Patient Data";

	public static final String PRIV_VIEW_AUDIT_LOGS = "View AI Audit Logs";

	public static final String GP_EMBEDDING_MODEL_FILE_PATH = "chartsearchai.embedding.modelFilePath";

	public static final String GP_LLM_MODEL_FILE_PATH = "chartsearchai.llm.modelFilePath";

	public static final String GP_SEARCH_MODE = "chartsearchai.searchMode";

	public static final String SEARCH_MODE_LLM = "llm";

	public static final String SEARCH_MODE_EMBEDDING = "embedding";

	public static final String GP_EMBEDDING_PROVIDER = "chartsearchai.embedding.provider";

	public static final String EMBEDDING_PROVIDER_ONNX = "onnx";

	public static final String EMBEDDING_PROVIDER_TERM_FREQUENCY = "term-frequency";

	public static final int EMBEDDING_DIMENSIONS = 384;

	public static final int DEFAULT_RETRIEVAL_TOP_K = 15;

	/**
	 * Resolves a model path relative to the OpenMRS application data directory.
	 * Rejects paths containing ".." to prevent path traversal and verifies the
	 * resolved path stays within the application data directory.
	 *
	 * @param relativePath the relative path from the global property (e.g. "chartsearchai/model.gguf")
	 * @param globalPropertyName the global property name, used in error messages
	 * @return the absolute path to the model file
	 * @throws IllegalStateException if the path is invalid, traverses outside the data directory,
	 *         or the file does not exist
	 */
	public static String resolveModelPath(String relativePath, String globalPropertyName) {
		if (relativePath.contains("..")) {
			throw new IllegalStateException(
					"Model path must not contain '..': " + globalPropertyName);
		}

		File appDataDir = new File(OpenmrsUtil.getApplicationDataDirectory());
		File modelFile = new File(appDataDir, relativePath);

		try {
			String canonicalPath = modelFile.getCanonicalPath();
			String canonicalDataDir = appDataDir.getCanonicalPath();
			if (!canonicalPath.startsWith(canonicalDataDir + File.separator)) {
				throw new IllegalStateException(
						"Model path must resolve to within the OpenMRS application data directory: "
								+ globalPropertyName);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException(
					"Failed to resolve model path for " + globalPropertyName, e);
		}

		if (!modelFile.exists()) {
			throw new IllegalStateException(
					"Model file not found: " + modelFile.getAbsolutePath()
							+ ". Set the correct relative path in " + globalPropertyName);
		}

		return modelFile.getAbsolutePath();
	}

	private ChartSearchAiConstants() {
	}
}
