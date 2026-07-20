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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Unit tests for {@link QueryStoreChartBuilder#dominantConceptCompletion} — the data-driven
 * repeated-measure completion. Pure logic: given the similarity-hit uuids and the full chart
 * (whose docs carry {@code concept_uuid}), when one concept dominates the hits it returns that
 * concept's entire chart series. No query text, no fixed slice size.
 */
public class QueryStoreChartBuilderCompletionTest {

	/** A chart doc with a concept_uuid (obs identity). */
	private static QueryDocument doc(String uuid, String conceptUuid, String text) {
		QueryDocument d = new QueryDocument();
		d.setResourceType("obs");
		d.setResourceUuid(uuid);
		d.setText(text);
		if (conceptUuid != null) {
			d.putMetadata(QueryStoreChartBuilder.CONCEPT_UUID_KEY, conceptUuid);
		}
		return d;
	}

	private static Set<String> hits(String... uuids) {
		return new HashSet<String>(Arrays.asList(uuids));
	}

	private static void assertContainsExactly(Set<String> got, String... expected) {
		assertEquals(expected.length, got.size(), "expected " + expected.length + " uuids, got " + got);
		for (String e : expected) {
			assertTrue(got.contains(e), "missing " + e + " in " + got);
		}
	}

	@Test
	public void completesTheWholeSeries_whenOneConceptDominatesTheHits() {
		// Chart: 8 systolic-BP records (concept "bp") + 2 unrelated. Hits surfaced only 4 of the BP.
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 1; i <= 8; i++) {
			chart.add(doc("bp" + i, "bp", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		chart.add(doc("wt1", "wt", "Weight: 70 kg"));
		chart.add(doc("cr1", "cr", "Creatinine: 90 umol/L"));

		Set<String> out = QueryStoreChartBuilder.dominantConceptCompletion(
				hits("bp1", "bp2", "bp3", "bp4"), chart);

		assertContainsExactly(out, "bp1", "bp2", "bp3", "bp4", "bp5", "bp6", "bp7", "bp8");
	}

	@Test
	public void returnsEmpty_whenNoConceptReachesTheDominanceFloor() {
		// Only 3 BP hits (< DOMINANCE_MIN=4): a one-off, not a series.
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 1; i <= 8; i++) {
			chart.add(doc("bp" + i, "bp", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(hits("bp1", "bp2", "bp3"), chart).isEmpty());
	}

	@Test
	public void returnsEmpty_whenHitsAreDiverse() {
		// Four hits, four different concepts — no dominant concept.
		List<QueryDocument> chart = Arrays.asList(
				doc("a1", "a", "A"), doc("b1", "b", "B"), doc("c1", "c", "C"), doc("d1", "d", "D"));
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(hits("a1", "b1", "c1", "d1"), chart).isEmpty());
	}

	@Test
	public void completionIsCappedAndKeepsTheNewest() {
		// 100 BP records in chart, date-desc order bp0 (newest) .. bp99 (oldest). Hits are 4 BP.
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 0; i < 100; i++) {
			chart.add(doc("bp" + i, "bp", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		Set<String> out = QueryStoreChartBuilder.dominantConceptCompletion(hits("bp0", "bp1", "bp2", "bp3"), chart);
		assertEquals(QueryStoreChartBuilder.COMPLETION_CAP, out.size(), "must cap the completion");
		for (int i = 0; i < QueryStoreChartBuilder.COMPLETION_CAP; i++) {
			assertTrue(out.contains("bp" + i), "must keep the newest record bp" + i);
		}
		assertFalse(out.contains("bp" + QueryStoreChartBuilder.COMPLETION_CAP), "must drop records past the cap");
	}

	@Test
	public void concept_isResolvedFromChart_notFromTheHitUuidsThemselves() {
		// The hits are bare uuids; concept identity lives only on the chart docs. A hit uuid absent
		// from the chart (past the chart cap) cannot be resolved and must not count toward dominance.
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 1; i <= 6; i++) {
			chart.add(doc("bp" + i, "bp", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		// 3 resolvable BP hits + 1 hit not in the chart -> only 3 count -> below floor -> empty.
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(
				hits("bp1", "bp2", "bp3", "not-in-chart"), chart).isEmpty());
		// 4 resolvable BP hits -> fires.
		assertFalse(QueryStoreChartBuilder.dominantConceptCompletion(
				hits("bp1", "bp2", "bp3", "bp4"), chart).isEmpty());
	}

	@Test
	public void recordsWithoutConceptUuid_areIgnored() {
		// Records lacking concept_uuid (null metadata) cannot be grouped and never dominate.
		List<QueryDocument> chart = Arrays.asList(
				doc("n1", null, "note 1"), doc("n2", null, "note 2"),
				doc("n3", null, "note 3"), doc("n4", null, "note 4"));
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(hits("n1", "n2", "n3", "n4"), chart).isEmpty());
	}

	@Test
	public void nullOrEmptyInputs_returnEmpty() {
		List<QueryDocument> chart = Arrays.asList(doc("bp1", "bp", "x"));
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(null, chart).isEmpty());
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(hits("bp1"), null).isEmpty());
		assertTrue(QueryStoreChartBuilder.dominantConceptCompletion(new HashSet<String>(), chart).isEmpty());
	}
}
