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

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Locks {@link ChartBuildingStrategy#buildChart}'s chartMode dispatch — the single point that
 * decides whether a query's context is the full chart or a query-scoped slice. Every unit test
 * of the surrounding pipeline stubs either the strategy or the builder, so without this test an
 * inverted or dropped dispatch would pass the entire suite and only surface in live captures.
 */
public class ChartBuildingStrategyDispatchTest {

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** Counts which builder entry point the strategy dispatched to, and stamps its charts the way
	 *  the real builder does, so the stamps' journey through {@code buildChart} is observable. */
	private static final class CountingBuilder extends QueryStoreChartBuilder {

		int buildCalls = 0;

		int buildScopedCalls = 0;

		boolean preFilter = false;

		@Override
		PatientChart build(Patient patient, String question) {
			buildCalls++;
			PatientChart chart = new PatientChart("", Collections.emptyList());
			if (preFilter) {
				chart.markPreFiltered();
			}
			return chart;
		}

		@Override
		PatientChart buildScoped(Patient patient, String question) {
			buildScopedCalls++;
			PatientChart chart = new PatientChart("", Collections.emptyList());
			chart.markQueryScoped();
			return chart;
		}
	}

	private static final class TestableStrategy extends ChartBuildingStrategy {

		private final boolean queryScoped;

		TestableStrategy(QueryStoreChartBuilder builder, boolean queryScoped) {
			this.queryScoped = queryScoped;
			setQueryStoreChartBuilder(builder);
		}

		@Override
		boolean queryScopedMode() {
			return queryScoped;
		}
	}

	@Test
	public void buildChart_shouldDispatchToScopedBuilder_inQueryScopedMode() {
		CountingBuilder builder = new CountingBuilder();

		new TestableStrategy(builder, true).buildChart(patient(), "any allergies?");

		assertEquals(1, builder.buildScopedCalls, "queryScoped mode must build the slice chart");
		assertEquals(0, builder.buildCalls, "queryScoped mode must not build the full chart");
	}

	@Test
	public void buildChart_shouldDispatchToFullChartBuilder_whenNotScoped() {
		CountingBuilder builder = new CountingBuilder();

		// queryScopedMode() == false selects fullChart (which is no longer the GP default, but
		// remains the mode this dispatch branch serves).
		new TestableStrategy(builder, false).buildChart(patient(), "any allergies?");

		assertEquals(1, builder.buildCalls, "fullChart mode keeps today's full-chart build");
		assertEquals(0, builder.buildScopedCalls);
	}

	@Test
	public void searchModeLabel_shouldNameTheModeTheDispatchChose_onTheChartReturnedByBuildChart() {
		// Issue #178: the audit row's mode is read off the chart's stamps, so buildChart has to hand
		// the builder's chart back untouched. It does today — but this class exists precisely because
		// a change here (a defensive copy, a rewrap) would pass every other test and only surface in
		// a live capture, and for the label that surfaces as every row silently reading full-chart
		// again, which is the defect #178 fixed.
		CountingBuilder scopedBuilder = new CountingBuilder();
		TestableStrategy scoped = new TestableStrategy(scopedBuilder, true);
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				scoped.searchModeLabel(scoped.buildChart(patient(), "any allergies?")),
				"a scoped dispatch must label the row scoped");

		CountingBuilder plainBuilder = new CountingBuilder();
		TestableStrategy plain = new TestableStrategy(plainBuilder, false);
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_FULL_CHART,
				plain.searchModeLabel(plain.buildChart(patient(), "any allergies?")),
				"a fullChart dispatch with no focus hint must label the row full-chart");

		CountingBuilder hintedBuilder = new CountingBuilder();
		hintedBuilder.preFilter = true;
		TestableStrategy hinted = new TestableStrategy(hintedBuilder, false);
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER,
				hinted.searchModeLabel(hinted.buildChart(patient(), "any allergies?")),
				"a fullChart dispatch carrying a focus hint must label the row pre-filter");
	}
}
