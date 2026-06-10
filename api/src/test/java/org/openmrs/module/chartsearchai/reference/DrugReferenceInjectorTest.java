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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
		DrugReferenceInjector injector = new DrugReferenceInjector();
		injector.setDrugReferenceService(new DrugReferenceService());
		return injector;
	}

	private PatientChart oneRecordChart() {
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();
		mappings.add(new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
				"obs-uuid-1", null, "BP 120/80"));
		return new PatientChart("Patient: 5-year-old Male\n\n[1] BP 120/80\n",
				Collections.unmodifiableList(mappings), Collections.<Integer> emptyList());
	}

	private PatientClinicalContext context(Integer age, Set<String> atc) {
		return new PatientClinicalContext(age, Collections.<String> emptySet(),
				atc == null ? Collections.<String> emptySet() : atc,
				Collections.<String> emptySet(), Collections.<String> emptySet());
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
	public void orderDrivenInjectionMatchesByAtc() {
		Set<String> atc = new LinkedHashSet<String>();
		atc.add("M01AE01");
		// Question does not mention the drug; the active-order ATC drives the match.
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(5, atc), "summarise the plan");
		assertTrue(result.getMappings().stream()
				.anyMatch(m -> "ibuprofen".equals(m.getResourceUuid())),
				"active-order ATC should inject the matching reference even when the question is silent");
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
