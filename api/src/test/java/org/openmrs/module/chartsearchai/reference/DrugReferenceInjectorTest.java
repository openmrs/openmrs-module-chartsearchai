/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Exercises the real {@link DrugReferenceInjector} over the real bundled dataset
 * via {@link DrugReferenceInjector#injectRecords}, the pure (no OpenMRS context)
 * seam. The injectFromQuery/injectFromOrders toggles fall back to their {@code true}
 * defaults when no context is available, matching production defaults.
 */
public class DrugReferenceInjectorTest {

	private DrugReferenceInjector injector() {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.bundledService());
	}

	/** Injector backed by the real WHO ATC sample (parsed by the real source), which — unlike the
	 *  bundled JSON — contains two drugs in the same ATC subgroup (ibuprofen/naproxen, both M01AE),
	 *  needed to exercise the "related active order" path. */
	private DrugReferenceInjector atcInjector() throws IOException {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.atcService(false));
	}

	private Set<String> set(String... values) {
		return DrugReferenceTestSupport.set(values);
	}

	private PatientChart oneRecordChart() {
		return DrugReferenceTestSupport.oneRecordChart();
	}

	private PatientClinicalContext context(Integer age, Set<String> atc) {
		return DrugReferenceTestSupport.ctx(age, null, null, atc, null, null);
	}

	@Test
	public void questionDrivenInjectionAppendsCitableRecord() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(5, null), "what is the safe dose of ibuprofen?");

		assertEquals(2, result.getMappings().size(), "one reference record should be appended");
		RecordMapping injected = result.getMappings().get(1);
		assertEquals(2, injected.getIndex(), "numbering continues from the chart records");
		assertEquals(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE, injected.getResourceType());
		assertEquals("ibuprofen", injected.getResourceUuid());
		assertTrue(result.getText().contains("[2] Drug reference — Ibuprofen"),
				"injected record should be a numbered, citable chart line");
	}

	@Test
	public void injectionPreservesQueryScopedStamp() {
		// A query-scoped slice that gains a drug-reference record MUST stay stamped query-scoped:
		// LlmInferenceService.searchStreaming derives the KV-cache decision from
		// PatientChart.isQueryScoped() (not a re-read of the chartMode GP, deliberately). If
		// injection drops the stamp, a question-dependent slice can be persisted under the
		// patient's KV scope during a mode-flip/GP-read race, evicting their real full-chart
		// (pinned) entry. Regression: injectRecords rebuilt the chart via a fresh PatientChart,
		// which reset the flag to false.
		PatientChart scoped = oneRecordChart();
		scoped.markQueryScoped();

		PatientChart result = injector().injectRecords(scoped,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > scoped.getMappings().size(),
				"precondition: a reference record must actually be injected, else the rebuild path is not exercised");
		assertTrue(result.isQueryScoped(),
				"the injected chart must carry forward the query-scoped stamp");
	}

	@Test
	public void injectionPreservesConceptUuidOnTheChartsOwnRecords() {
		// Second field this reconstructor has to carry across, and the same failure shape as the
		// query-scoped stamp above: RecordMapping.getConceptUuid() is what pairs an OpenMRS
		// condition with its identical encounter_diagnosis in LlmInferenceService's twin
		// co-citation. If injection rebuilt the mappings instead of copying them, every citation
		// would quietly lose its twin on any deployment with drugReference enabled — no error, no
		// log line, just half the reference chips. Cheap guard on an expensive silent failure.
		PatientChart chart = new PatientChart("Patient\n\n[1] Condition: Hordeolum. Status: ACTIVE\n",
				Collections.unmodifiableList(Collections.singletonList(
						new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
								"cond-uuid-1", null, "Condition: Hordeolum. Status: ACTIVE",
								"concept-hordeolum"))),
				Collections.<Integer> emptyList());

		PatientChart result = injector().injectRecords(chart,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > chart.getMappings().size(),
				"precondition: a reference record must actually be injected, else the rebuild path is not exercised");
		assertEquals("concept-hordeolum", result.getMappings().get(0).getConceptUuid(),
				"the chart's own record must keep its concept identity through injection");
		assertNull(result.getMappings().get(1).getConceptUuid(),
				"and the injected drug-reference record must have none — reference data is not a "
						+ "patient problem, so it must never pair with one");
	}

	@Test
	public void injectionLeavesFullChartUnstamped() {
		// The mirror guard: injection must never ADD the stamp to a full chart, which would wrongly
		// suppress the patient KV scope for the mode whose whole design depends on it.
		PatientChart full = oneRecordChart();

		PatientChart result = injector().injectRecords(full,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > full.getMappings().size(),
				"precondition: a reference record must actually be injected");
		assertFalse(result.isQueryScoped(),
				"a full chart must never acquire the query-scoped stamp through injection");
	}

	@Test
	public void dosingIsRenderedForMatchingAgeBand() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(5, null), "ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("ages 2-11"), "should render the matching pediatric band");
		assertTrue(injected.contains("1200 mg/day"), "should render the band's daily maximum");
	}

	@Test
	public void dosingIsOmittedWhenAgeUnknown() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(null, null), "ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertFalse(injected.contains("Dosing for ages"),
				"no numeric dosing when no age band matches; contraindication/interaction facts still render");
		assertTrue(injected.contains("Contraindicated with:"));
	}

	@Test
	public void noMatchReturnsChartUnchanged() {
		PatientChart chart = oneRecordChart();
		PatientChart result = injector().injectRecords(chart, context(5, null),
				"how is the patient doing?");
		assertSame(chart, result, "no reference match -> the same chart instance is returned");
	}

	@Test
	public void silentQuestionDoesNotInjectActiveOrders() {
		// A question that names no specific drug has no relevance anchor, so active-order references are
		// NOT injected — an active medication is noise for such a question. (The model still sees the
		// active-order records in the chart, and the safety validator reads active orders directly.)
		PatientChart chart = oneRecordChart();
		PatientChart result = injector().injectRecords(chart, context(5, set("M01AE01")), "summarise the plan");
		assertSame(chart, result,
				"a question naming no specific drug must not inject active-order references");
	}

	@Test
	public void unrelatedActiveOrderIsNotInjectedForADrugSpecificQuestion() {
		// The question is about gentamicin (J01GB); the active order is ibuprofen (M01AE) — a different
		// ATC class. The unrelated active-order reference must NOT be injected: it is noise for this
		// question and helps the clinician in no way.
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(40, set("M01AE01")), "is gentamicin safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Gentamicin"),
				"the question's own drug should still be injected");
		assertFalse(result.getText().contains("Drug reference — Ibuprofen"),
				"an active order unrelated to the question's drug must not be injected");
	}

	@Test
	public void relatedActiveOrderIsStillInjectedForADrugSpecificQuestion() throws IOException {
		// The question is about naproxen (M01AE02); the active order is ibuprofen (M01AE01) — the same
		// ATC subgroup M01AE. That active order IS relevant (duplicate-therapy concern), so its
		// reference is still injected.
		PatientChart result = atcInjector().injectRecords(oneRecordChart(),
				context(40, set("M01AE01")), "is naproxen safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Naproxen"),
				"the question's own drug should be injected");
		assertTrue(result.getText().contains("Drug reference — Ibuprofen"),
				"an active order in the same ATC subgroup as the question's drug should be injected");
	}

	@Test
	public void rendersAtcClassificationEntryWithNoRuleSections() {
		// An ATC-sourced entry carries class + ATC code but no dosing/interaction/contraindication
		// rules; the injected line must render cleanly (class + ATC) with none of the rule sections.
		DrugReference atc = new DrugReference();
		atc.setId("M01AE01");
		atc.setName("Ibuprofen");
		atc.setAliases(Collections.singletonList("ibuprofen"));
		atc.setAtcCodes(Collections.singletonList("M01AE01"));
		atc.setDrugClass("Propionic acid derivatives");
		DrugReferenceService svc = new DrugReferenceService();
		svc.setEntries(Collections.singletonList(atc));
		DrugReferenceInjector inj = new DrugReferenceInjector();
		inj.setDrugReferenceService(svc);

		PatientChart result = inj.injectRecords(oneRecordChart(), context(5, null), "what is the ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("Drug reference — Ibuprofen"));
		assertTrue(injected.contains("Propionic acid derivatives"));
		assertTrue(injected.contains("ATC M01AE01"));
		assertFalse(injected.contains("Dosing for ages"), "ATC entry has no age bands -> no dosing line");
		assertFalse(injected.contains("Contraindicated with:"), "ATC entry has no contraindication rules");
		assertFalse(injected.contains("Interactions:"), "ATC entry has no interaction rules");
	}
}
