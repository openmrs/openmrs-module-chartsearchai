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
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Unit tests for the generic dominant-concept slice expansion — the fix for the "repeated-measure
 * gets arbitrarily truncated by a fixed top-K" problem (e.g. blood-pressure readings). Pure logic,
 * no context: {@link QueryStoreChartBuilder#conceptKey} and
 * {@link QueryStoreChartBuilder#dominantConceptExpansion}.
 */
public class QueryStoreChartBuilderConceptExpansionTest {

	private static QueryDocument doc(String uuid, String type, String text) {
		QueryDocument d = new QueryDocument();
		d.setResourceUuid(uuid);
		d.setResourceType(type);
		d.setText(text);
		return d;
	}

	private static Set<String> keyMatches(Set<String> got, String... expected) {
		assertEquals(expected.length, got.size(), "expected " + expected.length + " uuids, got " + got);
		for (String e : expected) {
			assertTrue(got.contains(e), "missing " + e + " in " + got);
		}
		return got;
	}

	@Test
	public void conceptKey_collapsesSameConceptDifferentValues_keepsDistinctConceptsSeparate() {
		String k1 = QueryStoreChartBuilder.conceptKey("Systolic blood pressure: 145 mmHg");
		String k2 = QueryStoreChartBuilder.conceptKey("Systolic blood pressure: 159 mmHg");
		String k3 = QueryStoreChartBuilder.conceptKey("Diastolic blood pressure: 76 mmHg");
		assertEquals(k1, k2, "same concept, different values must share a key");
		assertTrue(!k1.equals(k3), "systolic vs diastolic must be distinct keys");
	}

	@Test
	public void conceptKey_collapsesNegativeAndPositiveValuesOfOneConcept() {
		// A lab that swings negative (base excess, a balance delta) must collapse across the sign —
		// otherwise a genuine series splits into "-#" and "#" keys and the recurrence gate misses it.
		String neg = QueryStoreChartBuilder.conceptKey("Base excess: -2.5 mmol/L");
		String pos = QueryStoreChartBuilder.conceptKey("Base excess: 3.0 mmol/L");
		assertEquals(neg, pos, "negative and positive readings of one concept must share a key");
	}

	@Test
	public void dominantExpansion_bpQuery_returnsEveryBpRecord() {
		// Ranked hits dominated by systolic BP (top hit is a BP reading; 5 of 6 are BP).
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		hits.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		hits.add(doc("bp5", "obs", "Systolic blood pressure: 123 mmHg"));
		hits.add(doc("wt1", "obs", "Weight: 70 kg"));
		// Full chart has MORE BP readings than the top-K surfaced (the ones a fixed K truncates).
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 1; i <= 8; i++) {
			chart.add(doc("bp" + i, "obs", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		chart.add(doc("wt1", "obs", "Weight: 70 kg"));
		chart.add(doc("cond1", "condition", "Tuberculosis"));

		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		keyMatches(exp, "bp1", "bp2", "bp3", "bp4", "bp5", "bp6", "bp7", "bp8"); // ALL 8 BP, not just top-K's 5
	}

	@Test
	public void dominantExpansion_firesOnPlurality_notRankOne() {
		// Production reality for "is she hypertensive?": retrieval ranks a spurious singleton FIRST
		// (a glucose reading), with the blood-pressure run recurring below it. Expansion must key on
		// the recurring plurality (BP), NOT the rank-1 hit — a refactor to rankedHits.get(0) would
		// pass every other test but silently break this case.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("glu", "obs", "Glucose, serum: 92 mg/dL"));      // rank-1, singleton, NOT the answer
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		hits.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>(hits);
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		keyMatches(exp, "bp1", "bp2", "bp3", "bp4"); // BP, not the rank-1 glucose
	}

	@Test
	public void dominantExpansion_diverseConditionQuery_returnsEmpty() {
		// "any heart problems?" shape: top hit is a UNIQUE cardiac condition; vitals are numerous
		// but each is a lower-ranked, different-concept record and the top hit's concept count is 1.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("mi", "condition", "Myocardial infarction"));           // top = unique condition
		hits.add(doc("af", "condition", "Atrial flutter"));
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 120 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 118 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 121 mmHg"));
		hits.add(doc("pu1", "obs", "Pulse: 80 bpm"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>(hits);
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		assertTrue(exp.isEmpty(), "diverse ranking (unique top condition) must not expand; got " + exp);
	}

	@Test
	public void dominantExpansion_conditionGuard_suppressesEvenWhenMeasureRecurs() {
		// A recurring measure (5 systolic BP, plurality >= DOMINANCE_MIN) that WOULD fire on the
		// recurrence gate alone, but the top hits also carry two distinct conditions — so the query
		// is condition-oriented ("any heart problems?" shape) and expansion must be suppressed.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		hits.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		hits.add(doc("bp5", "obs", "Systolic blood pressure: 123 mmHg"));
		hits.add(doc("mi", "condition", "Myocardial infarction"));
		hits.add(doc("af", "diagnosis", "Atrial flutter"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>(hits);
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		assertTrue(exp.isEmpty(), "condition-oriented query (>=2 distinct conditions) must not expand; got " + exp);
	}

	@Test
	public void dominantExpansion_conditionTypeResolvedFromChartWhenHitTypeMissing() {
		// Production reality: similarity-search hits are ranking payloads that may not carry
		// resourceType, so the guard must resolve each hit's type from the chart docs (authoritative)
		// by UUID. Here the two conditions arrive on the hits with a NULL type; without the chart
		// fallback the guard sees zero conditions and wrongly expands the recurring BP.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		hits.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		hits.add(doc("mi", null, "condition: myocardial infarction. status: active"));
		hits.add(doc("af", null, "diagnosis: atrial flutter. certainty: confirmed"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		chart.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		chart.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		chart.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		chart.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		chart.add(doc("mi", "condition", "condition: myocardial infarction. status: active"));
		chart.add(doc("af", "diagnosis", "diagnosis: atrial flutter. certainty: confirmed"));
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		assertTrue(exp.isEmpty(), "condition types must be resolved from the chart and suppress; got " + exp);
	}

	@Test
	public void dominantExpansion_singleIncidentalCondition_stillExpands() {
		// One incidental comorbidity alongside a genuine measure run (< CONDITION_GUARD=2 distinct
		// conditions) must NOT suppress: the BP enumeration still expands.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 159 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 145 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 112 mmHg"));
		hits.add(doc("bp4", "obs", "Systolic blood pressure: 156 mmHg"));
		hits.add(doc("dm", "condition", "Diabetes mellitus"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>(hits);
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		keyMatches(exp, "bp1", "bp2", "bp3", "bp4");
	}

	@Test
	public void dominantExpansion_belowMinCount_returnsEmpty() {
		// Only 3 BP in the top hits (< DOMINANCE_MIN=4): not enough to call it an enumeration.
		// Filler hits are distinct obs (not conditions) so this isolates the recurrence gate — the
		// condition-guard must NOT be what suppresses here.
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		hits.add(doc("bp1", "obs", "Systolic blood pressure: 120 mmHg"));
		hits.add(doc("bp2", "obs", "Systolic blood pressure: 118 mmHg"));
		hits.add(doc("bp3", "obs", "Systolic blood pressure: 121 mmHg"));
		hits.add(doc("wt", "obs", "Weight: 70 kg"));
		hits.add(doc("ht", "obs", "Height: 170 cm"));
		hits.add(doc("tmp", "obs", "Temperature: 37 C"));
		List<QueryDocument> chart = new ArrayList<QueryDocument>(hits);
		assertTrue(QueryStoreChartBuilder.dominantConceptExpansion(hits, chart).isEmpty());
	}

	@Test
	public void dominantExpansion_capsHugeSeries_keepingTheNewest() {
		List<QueryDocument> hits = new ArrayList<QueryDocument>();
		for (int i = 0; i < 6; i++) {
			hits.add(doc("h" + i, "obs", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		// Chart is date-desc (production contract), so bp0 is the newest and bp99 the oldest.
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		for (int i = 0; i < 100; i++) {
			chart.add(doc("bp" + i, "obs", "Systolic blood pressure: " + (100 + i) + " mmHg"));
		}
		Set<String> exp = QueryStoreChartBuilder.dominantConceptExpansion(hits, chart);
		assertEquals(QueryStoreChartBuilder.EXPANSION_CAP, exp.size(), "must cap the expansion");
		// Pin the ordering contract in EXPANSION_CAP's javadoc: keep the NEWEST 40 (head of the
		// date-desc chart), not just any 40. A reverse scan would keep bp60..bp99 at the same size.
		for (int i = 0; i < QueryStoreChartBuilder.EXPANSION_CAP; i++) {
			assertTrue(exp.contains("bp" + i), "must keep the newest record bp" + i + "; got " + exp);
		}
		assertFalse(exp.contains("bp" + QueryStoreChartBuilder.EXPANSION_CAP),
				"must drop records older than the newest " + QueryStoreChartBuilder.EXPANSION_CAP
						+ " (bp" + QueryStoreChartBuilder.EXPANSION_CAP + " is past the cap)");
	}
}
