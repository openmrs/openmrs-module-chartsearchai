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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * An INTERACTION finding names the chart record of the active order it is about, so citing the finding
 * publishes that record too (ADR Decision 554).
 *
 * <p><b>The defect, as the public demo showed it.</b> <em>"can i give Samuel Panadol?"</em> answered
 * <em>"Acetaminophen can be given, with one caution: it interacts with active order Isoniazid, a Moderate
 * problem [296]"</em>, and the response carried the finding alone: no record of the Isoniazid prescription
 * the sentence is about. ADR Decision 77 numbers an order only inside a finding's bridge clause, which an
 * order displaying the substance's own name never states, and ADR Decision 80 attached the chart records
 * of contraindication findings alone — so an order like this one reached neither.
 *
 * <p>Over the shipped knowledge base, with the patient's order carried as structure and the chart's own
 * {@code drug_order} record for it, under the shipped {@code citeOrderRecords} default: nothing here reaches
 * the prompt, so the flag gating Decision 77's rendering does not gate it.
 */
public class InteractionFindingOrderProvenanceTest {

	private static final String ISONIAZID_ORDER = "order-isoniazid";

	private static PatientChart injected(PatientChart base) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Collections.singletonList(
				DrugReferenceTestSupport.activeOrder(ISONIAZID_ORDER, "Isoniazid 300mg tablet", "Isoniazid"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(base, DrugReferenceTestSupport.ctx(60, null,
						new LinkedHashSet<String>(Arrays.asList("Isoniazid")), new LinkedHashSet<String>(), null, null,
						orders), "can i give Samuel Panadol?");
	}

	private static List<RecordMapping> interactionFindings(PatientChart chart) {
		List<RecordMapping> out = new ArrayList<RecordMapping>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains("interacts with active order Isoniazid")) {
				out.add(finding);
			}
		}
		return out;
	}

	@Test
	public void aCitedInteractionFindingNamesTheRecordOfTheOrderItsSentenceIsAbout() {
		PatientChart chart = injected(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				DrugReferenceTestSupport.drugOrderRecord(2, ISONIAZID_ORDER, "Isoniazid 300mg tablet")));

		List<RecordMapping> findings = interactionFindings(chart);
		assertEquals(1, findings.size(), "the premise: Acetaminophen against her Isoniazid order raises one "
				+ "interaction finding over the shipped data, as on the demo. Findings were: "
				+ DrugReferenceTestSupport.injectedFindings(chart));
		assertTrue(findings.get(0).getText().contains("Moderate"),
				"the premise: it is the demo's Moderate finding. Was: " + findings.get(0).getText());
		assertEquals(Collections.singletonList(Integer.valueOf(2)), findings.get(0).getDerivedFrom(),
				"record [2] is her Isoniazid order's own record, and the finding is about that order, so "
						+ "citing the finding must reach it. Finding was: " + findings.get(0).getText());
	}

	/** The refusal that keeps it additive: a chart carrying no record of the order names nothing, and the
	 *  finding publishes exactly what it did before. */
	@Test
	public void aChartCarryingNoRecordOfTheOrderNamesNothing() {
		PatientChart chart = injected(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80")));

		List<RecordMapping> findings = interactionFindings(chart);
		assertEquals(1, findings.size(), "the premise, as above. Findings were: "
				+ DrugReferenceTestSupport.injectedFindings(chart));
		for (RecordMapping record : chart.getMappings()) {
			if (!findings.get(0).getDerivedFrom().contains(Integer.valueOf(record.getIndex()))) {
				continue;
			}
			assertEquals(ISONIAZID_ORDER, record.getResourceUuid(),
					"a record the finding names must be her Isoniazid order's own — the obs is not. Named: "
							+ record.getResourceType() + " " + record.getText());
		}
	}
}
