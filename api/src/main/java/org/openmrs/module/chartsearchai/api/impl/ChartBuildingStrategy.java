/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link PatientChart} for a patient query by delegating to the querystore
 * module via {@link QueryStoreChartBuilder}. querystore is a required module and the only
 * retrieval path — the legacy embedding/Lucene/Elasticsearch pipelines and the in-process
 * full-chart fallback were removed in the querystore migration (issue #51). If querystore is
 * unavailable at runtime, {@link QueryStoreChartBuilder} degrades to an empty chart rather
 * than failing chart assembly. The containing {@link LlmInferenceService} delegates here for
 * chart assembly and otherwise focuses on the LLM call and citation handling.
 */
@Service("chartSearchAi.chartBuildingStrategy")
class ChartBuildingStrategy {

	@Autowired
	@Qualifier("chartSearchAi.queryStoreChartBuilder")
	private QueryStoreChartBuilder queryStoreChartBuilder;

	/** Test seam: production wires {@link QueryStoreChartBuilder} via {@link Autowired}. */
	void setQueryStoreChartBuilder(QueryStoreChartBuilder queryStoreChartBuilder) {
		this.queryStoreChartBuilder = queryStoreChartBuilder;
	}

	PatientChart buildChart(Patient patient, String question) {
		// The chartMode dispatch lives here — the single chart-assembly entry point — so every
		// caller (search, searchStreaming, warmup) sees the same mode without re-reading the GP.
		if (queryScopedMode()) {
			return queryStoreChartBuilder.buildScoped(patient, question);
		}
		return queryStoreChartBuilder.build(patient, question);
	}

	/**
	 * Builds a small "focused" chart of only the querystore top-K records most relevant to the
	 * question, for the progressive-reasoning preview pass. Unlike {@link #buildChart}, this is
	 * query-dependent and is never the committed answer's context — see
	 * {@code LlmInferenceService.maybeEmitPreliminaryReasoning}.
	 */
	PatientChart buildFocusedChart(Patient patient, String question) {
		return queryStoreChartBuilder.buildFocused(patient, question);
	}

	/**
	 * The audit-facing label for the chart-assembly mode that produced {@code chart} — one of the
	 * {@code ChartSearchAiConstants.SEARCH_MODE_*} values, and the single derivation of it.
	 *
	 * <p>It lives here because this class is where the chartMode dispatch lives, for the reason
	 * {@link #buildChart} states: so every caller sees the same mode without re-reading the GP. A
	 * second derivation elsewhere is exactly issue #178 — the REST layer branched on the preFilter
	 * GP alone at both of its audit-write sites, and {@code queryScoped}, the shipped default, could
	 * therefore never reach the column.
	 *
	 * <p>Read entirely off the CHART's own stamps, never off a fresh GP read, for the reason
	 * {@code PatientChart.markQueryScoped} exists: a re-read can disagree with the read that built
	 * the chart (a transient read failure, or an operator flip mid-request), and an audit row's whole
	 * purpose is to say what the clinician was actually shown. It also means labelling costs no
	 * {@code Context} access, so the answer can be labelled on every path the pipeline has, not only
	 * the ones holding an OpenMRS session.
	 *
	 * @param chart a chart from {@link #buildChart} (never null), after any rebuild — the caller
	 *        dereferences it either way, so there is no null case to name a mode for
	 */
	String searchModeLabel(PatientChart chart) {
		if (chart.isQueryScoped()) {
			return ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED;
		}
		return chart.isPreFiltered()
				? ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER
				: ChartSearchAiConstants.SEARCH_MODE_FULL_CHART;
	}

	boolean usePreFilter() {
		return PipelineSettings.usePreFilter();
	}

	/** True when {@code chartsearchai.chartMode=queryScoped} — prompts carry a query-scoped slice
	 *  and the full-chart prefill machinery (warmup, KV persistence, preview) disengages. */
	boolean queryScopedMode() {
		return PipelineSettings.queryScopedMode();
	}
}
