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

	/** Whether queryScoped dominant-concept slice expansion is enabled
	 *  ({@code chartsearchai.slice.conceptExpansion}). Default-true opt-out (mirrors
	 *  {@link #usePreFilter}'s {@code !"false"} idiom): only an explicit {@code false} disables it,
	 *  so a typo'd value still leaves the feature on. */
	static boolean conceptExpansionEnabled() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_CONCEPT_EXPANSION, "true");
		return !"false".equalsIgnoreCase(mode.trim());
	}

	static boolean dedupGroupLabels() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SERIALIZER_DEDUP_GROUP_LABELS, "false");
		return "true".equalsIgnoreCase(mode.trim());
	}

	/** True when {@code chartsearchai.chartMode} selects the query-scoped slice (the
	 *  {@link ChartSearchAiConstants#CHART_MODE_DEFAULT default} since 2026-07): prompts carry a
	 *  query-scoped record slice instead of the whole chart, and the full-chart prefill machinery
	 *  (warmup, prewarm, per-patient KV persistence, preview) disengages. Resolution: an absent or
	 *  unreadable GP takes {@code CHART_MODE_DEFAULT} (= queryScoped) via the fail-safe
	 *  {@link ChartSearchAiUtils#getStringGlobalProperty} reader; a GP explicitly set to
	 *  {@code fullChart} — or to any typo that is not an exact (case-insensitive) {@code queryScoped}
	 *  — resolves to fullChart, so a mistyped value still fails toward the whole chart. Safe for the
	 *  destructive KV decisions too, because those never trust a re-read of this gate — they follow
	 *  the built chart's own {@code PatientChart#isQueryScoped()} stamp. */
	static boolean queryScopedMode() {
		return isQueryScoped(org.openmrs.module.chartsearchai.ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_CHART_MODE, ChartSearchAiConstants.CHART_MODE_DEFAULT));
	}

	/** The pure resolved-value → scoped decision, split out so the opt-out/typo-safety contract is
	 *  unit-testable without a Context: scoped requires an exact (case-insensitive) {@code queryScoped}
	 *  match, so {@code fullChart}, any typo, and null all resolve to fullChart. The
	 *  {@link ChartSearchAiConstants#CHART_MODE_DEFAULT default} for unset/unreadable is applied by the
	 *  caller (the {@code getStringGlobalProperty} fallback), not here. */
	static boolean isQueryScoped(String resolvedMode) {
		return ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED.equalsIgnoreCase(resolvedMode);
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
	 *  getters (querystore slice/focus-hint and progressive-reasoning preview) cannot drift apart. */
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
