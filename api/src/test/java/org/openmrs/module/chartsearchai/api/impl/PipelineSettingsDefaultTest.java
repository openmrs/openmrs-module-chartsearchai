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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

/**
 * Locks the chart-mode default. In a plain unit test there is no OpenMRS
 * {@code Context}, so {@code ChartSearchAiUtils.getStringGlobalProperty} hits its
 * catch clause and returns the supplied default — the same value the pipeline
 * resolves to when {@code chartsearchai.chartMode} is unset. So
 * {@link PipelineSettings#queryScopedMode()} run here exercises the real default
 * decision without a context.
 *
 * <p>queryScoped became the default in 2026-07 (a 22-patient drift-metric A/B:
 * scoped beat fullChart on meanF1 0.748 vs 0.668, abstention 0.86 vs 0.74, and
 * off-topic drift 181 vs 477). This test fails on the pre-change default
 * (fullChart) and documents the deliberate fail-safe direction: an absent or
 * unreadable GP resolves to queryScoped.
 */
public class PipelineSettingsDefaultTest {

	@Test
	public void queryScopedMode_defaultsToQueryScoped_whenGpUnsetOrUnreadable() {
		assertTrue(PipelineSettings.queryScopedMode(),
				"chartMode default must be queryScoped when chartsearchai.chartMode is unset");
	}

	@Test
	public void chartModeDefault_constant_isQueryScoped() {
		// Single source of truth both readers point at; guards against a silent revert.
		assertTrue(ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED
				.equals(ChartSearchAiConstants.CHART_MODE_DEFAULT),
				"CHART_MODE_DEFAULT must be queryScoped");
	}

	@Test
	public void queryStoreTopKDefault_isTwelve() {
		// Tuned default for the queryScoped slice (2026-07 topK sweep: knee ~12-15). Guards against
		// a silent revert to 30, which restored the abstention/drift/latency regression on CPU.
		assertEquals(12, ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K,
				"DEFAULT_QUERYSTORE_TOP_K must be 12");
	}

	@Test
	public void conceptExpansionEnabled_defaultsToTrue_whenGpUnsetOrUnreadable() {
		// Same no-Context mechanism as queryScopedMode: getStringGlobalProperty hits its catch clause
		// and returns the "true" default. Locks the shipped default-ON contract — a silent revert to
		// off would disable repeated-measure expansion (the BP-series fix) for everyone without a
		// failing test. The !"false" opt-out also means only an explicit false (or a Context that
		// returns it) can turn it off; a typo stays on.
		assertTrue(PipelineSettings.conceptExpansionEnabled(),
				"conceptExpansion must default to true when chartsearchai.slice.conceptExpansion is unset");
	}

	@Test
	public void isQueryScoped_optOutAndTypoSafety() {
		// Locks the documented contract: flipping the default did NOT change how a set value is
		// read — scoped requires an exact (case-insensitive) queryScoped match, so an operator can
		// still opt back into fullChart, and a typo fails toward the whole chart rather than
		// silently enabling the slice.
		assertTrue(PipelineSettings.isQueryScoped("queryScoped"), "exact match enables scoped");
		assertTrue(PipelineSettings.isQueryScoped("QueryScoped"), "match is case-insensitive");
		assertFalse(PipelineSettings.isQueryScoped(ChartSearchAiConstants.CHART_MODE_FULL_CHART),
				"an explicit fullChart must stay fullChart (opt-out works)");
		assertFalse(PipelineSettings.isQueryScoped("queryscopd"),
				"a typo must resolve to fullChart, not silently enable the slice");
		assertFalse(PipelineSettings.isQueryScoped(null), "null resolves to fullChart");
	}
}
