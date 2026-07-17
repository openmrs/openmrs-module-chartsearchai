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
 * Resolves the querystore-retrieval and chart-assembly global properties with validation and
 * per-field defaults. The embedding-pipeline tuning getters were removed with the legacy
 * retrieval pipeline (issue #51); the remaining settings are consulted by the querystore path
 * ({@link QueryStoreChartBuilder}) and the chartMode gates ({@link ChartBuildingStrategy},
 * {@link LlmInferenceService}, {@link PrewarmBootstrapService}, {@link PrewarmRefreshExecutor},
 * {@link WarmupExecutor}).
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

	static boolean dedupGroupLabels() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SERIALIZER_DEDUP_GROUP_LABELS, "false");
		return "true".equalsIgnoreCase(mode.trim());
	}

	/** True when {@code chartsearchai.chartMode=queryScoped}: prompts carry a query-scoped record
	 *  slice instead of the whole chart, and the full-chart prefill machinery (warmup, prewarm,
	 *  per-patient KV persistence, preview) disengages. Any other value — including unset — is the
	 *  fullChart default, so a typo can never silently change what the LLM sees. Fail-safe via the
	 *  shared {@link ChartSearchAiUtils#getStringGlobalProperty} reader: a GP layer that cannot be
	 *  read resolves to fullChart rather than breaking the answer path. Safe for the destructive
	 *  KV decisions too, because those never trust a re-read of this gate — they follow the built
	 *  chart's own {@code PatientChart#isQueryScoped()} stamp. */
	static boolean queryScopedMode() {
		return ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED.equalsIgnoreCase(
				org.openmrs.module.chartsearchai.ChartSearchAiUtils.getStringGlobalProperty(
						ChartSearchAiConstants.GP_CHART_MODE, ChartSearchAiConstants.CHART_MODE_FULL_CHART));
	}

	static int getQueryStoreTopK() {
		return readPositiveInt(ChartSearchAiConstants.GP_QUERYSTORE_TOP_K,
				ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K, "queryStoreTopK");
	}

	static boolean progressiveReasoningEnabled() {
		String mode = Context.getAdministrationService().getGlobalProperty(
				ChartSearchAiConstants.GP_PROGRESSIVE_REASONING_ENABLED,
				String.valueOf(ChartSearchAiConstants.DEFAULT_PROGRESSIVE_REASONING_ENABLED));
		return "true".equalsIgnoreCase(mode.trim());
	}

	static int getProgressiveReasoningTopK() {
		return readPositiveInt(ChartSearchAiConstants.GP_PROGRESSIVE_REASONING_TOP_K,
				ChartSearchAiConstants.DEFAULT_PROGRESSIVE_REASONING_TOP_K, "progressiveReasoningTopK");
	}

	/** Reads a strictly-positive integer global property, or returns {@code defaultValue} when the
	 *  property is unset, blank, non-numeric, or not positive. A non-numeric value is logged at WARN
	 *  ({@code label} names the setting). The parse/validation contract lives here so the two topK
	 *  getters (querystore focus-hint and progressive-reasoning preview) cannot drift apart. */
	private static int readPositiveInt(String gpKey, int defaultValue, String label) {
		String value = Context.getAdministrationService().getGlobalProperty(gpKey);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid {} value '{}', using default", label, value);
			}
		}
		return defaultValue;
	}
}
