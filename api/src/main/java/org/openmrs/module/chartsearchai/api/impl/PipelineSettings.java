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

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the querystore-retrieval global properties with validation and
 * per-field defaults. The embedding-pipeline tuning getters were removed with
 * the legacy retrieval pipeline (issue #51); only the settings the querystore
 * path ({@link QueryStoreChartBuilder}) consults remain.
 */
final class PipelineSettings {

	private PipelineSettings() {
	}

	private static final Logger log = LoggerFactory.getLogger(PipelineSettings.class);

	static boolean usePreFilter() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		return !"false".equalsIgnoreCase(mode.trim());
	}

	static int getQueryStoreTopK() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_QUERYSTORE_TOP_K);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid queryStoreTopK value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K;
	}
}
