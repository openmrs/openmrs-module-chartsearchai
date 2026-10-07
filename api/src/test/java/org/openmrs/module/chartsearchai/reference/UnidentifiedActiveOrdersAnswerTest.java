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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The closing line a composed answer carries beside active orders the drug data does not identify (ADR Decision 161),
 * over the shapes the database-backed cases cannot build: several such orders, a display several of them share, and
 * an order recorded only by its codes. On the demo a patient's vaccine and infant-formula orders took every drug
 * proposal away from the module; this is that shape, beside an aspirin order the data identifies.
 */
public class UnidentifiedActiveOrdersAnswerTest extends BaseModuleContextSensitiveTest {

	private static DrugReferenceService shipped;

	private static synchronized DrugReferenceService shipped() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.shippedServiceWithGroups();
		}
		return shipped;
	}

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalBesideSeveralUnidentifiedOrdersNamesEachOnceAndCountsEveryOrder() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-aspirin", "Aspirin", DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("B01AC06")),
				new PatientClinicalContext.ActiveDrugOrder("order-formula-1", "Infant formula",
						DrugReferenceTestSupport.set("Infant formula")),
				new PatientClinicalContext.ActiveDrugOrder("order-formula-2", "Infant formula",
						DrugReferenceTestSupport.set("Infant formula")),
				new PatientClinicalContext.ActiveDrugOrder("order-polio", "Polio vaccination, oral",
						DrugReferenceTestSupport.set("Polio vaccination, oral")),
				PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-coded", "J07BC01",
						DrugReferenceTestSupport.set("J07BC01")));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("B01AC06"), null, null, orders),
				"Can I give her ibuprofen?");

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "the module answers beside the orders it cannot identify: " + chart.getText());
		String[] lines = answer.split("\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING),
				"ibuprofen beside her aspirin is a reason to withhold it: " + answer);
		// Semicolons between orders: a display can carry a comma of its own, as the demo's "Polio vaccination, oral".
		assertEquals("Not checked: 4 active orders the drug data does not identify — Infant formula (2 orders); "
				+ "Polio vaccination, oral; an order recorded only by its codes. Whether one of them is the drug asked "
				+ "about is not established.", lines[lines.length - 1]);
	}
}
