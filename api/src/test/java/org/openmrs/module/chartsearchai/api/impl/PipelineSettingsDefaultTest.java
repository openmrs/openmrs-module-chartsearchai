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
 * <p>fullChart is the default since 2026-10, by the maintainer's decision recorded in
 * ADR Decision 28, which also carries the measurements that had made queryScoped the
 * default from 2026-07. This test fails on that earlier default and documents the
 * fail-safe direction: an absent or unreadable GP resolves to fullChart.
 */
public class PipelineSettingsDefaultTest {

	@Test
	public void queryScopedMode_defaultsToFullChart_whenGpUnsetOrUnreadable() {
		assertFalse(PipelineSettings.queryScopedMode(),
				"chartMode default must be fullChart when chartsearchai.chartMode is unset");
	}

	@Test
	public void chartModeDefault_constant_isFullChart() {
		// Single source of truth both readers point at; guards against a silent revert.
		assertTrue(ChartSearchAiConstants.CHART_MODE_FULL_CHART
				.equals(ChartSearchAiConstants.CHART_MODE_DEFAULT),
				"CHART_MODE_DEFAULT must be fullChart");
	}

	@Test
	public void queryStoreTopKDefault_isTwelve() {
		// Tuned default for the queryScoped slice (2026-07 topK sweep: knee ~12-15). Guards against
		// a silent revert to 30, which restored the abstention/drift/latency regression on CPU.
		assertEquals(12, ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K,
				"DEFAULT_QUERYSTORE_TOP_K must be 12");
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
