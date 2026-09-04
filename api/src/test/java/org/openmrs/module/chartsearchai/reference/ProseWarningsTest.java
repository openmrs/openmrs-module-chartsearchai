/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Prose {@code warnings} on a curated entry: free-text safety warnings (e.g. the
 * Reye-syndrome caution that motivated them) that render into the injected,
 * citable reference record so the LLM can ground and cite them. Display-only —
 * they carry no matchable token, so the validator does not fire on them; the
 * deterministic checks stay with the structured rule fields.
 *
 * <p>Tests parse a fixture through the real {@link JsonDrugReferenceSource#parse}
 * parser and render through the real {@link DrugReferenceInjector} — the exact
 * production path an operator's extended dataset takes.
 */
public class ProseWarningsTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-with-warnings.json";

	private List<DrugReference> fixtureEntries() throws IOException {
		return DrugReferenceTestSupport.fixtureEntries(FIXTURE);
	}

	private DrugReferenceInjector injector(List<DrugReference> entries) {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.serviceWith(entries));
	}

	private PatientChart oneRecordChart() {
		return DrugReferenceTestSupport.oneRecordChart();
	}

	private PatientClinicalContext ctx(Integer age) {
		return DrugReferenceTestSupport.ctx(age, null, null, null, null, null);
	}

	@Test
	public void warningsParseFromTheJsonSchema() throws IOException {
		DrugReference aspirin = fixtureEntries().get(0);
		assertFalse(aspirin.getWarnings().isEmpty(), "the fixture entry's warnings should parse");
		assertTrue(aspirin.getWarnings().get(0).contains("Reye"),
				"the parsed warning text should survive verbatim");
	}

	@Test
	public void warningsRenderIntoTheInjectedCitableRecord() throws IOException {
		PatientChart result = injector(fixtureEntries()).injectRecords(oneRecordChart(),
				ctx(5), "is aspirin safe for this child?");
		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("Warnings: "), "a warnings section should render: " + injected);
		assertTrue(injected.contains("Reye"), "the warning prose should be in the citable record");
		assertTrue(injected.contains("Contraindicated with:"),
				"structured rule sections must still render alongside the warnings");
		assertFalse(injected.contains("Dosing for ages"),
				"an entry with no age bands must still render no dosing");
	}

	@Test
	public void entriesWithoutWarningsAreUnchanged() {
		// The bundled dataset carries no warnings: parsing must default to empty (never null)
		// and the rendered record must not grow an empty Warnings section.
		DrugReferenceService svc = new DrugReferenceService();
		DrugReference ibuprofen = svc.lookupByToken("ibuprofen");
		assertNotNull(ibuprofen);
		assertTrue(ibuprofen.getWarnings().isEmpty(),
				"a dataset without the warnings field must parse to an empty list");
		DrugReferenceInjector injector = new DrugReferenceInjector();
		injector.setDrugReferenceService(svc);
		PatientChart result = injector.injectRecords(oneRecordChart(), ctx(5), "ibuprofen dose?");
		assertFalse(result.getMappings().get(1).getText().contains("Warnings:"),
				"no warnings in the data -> no Warnings section in the record");
	}
}
