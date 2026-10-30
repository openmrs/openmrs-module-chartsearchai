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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen's line saying its cautions are in the safety warnings names the drugs each caution is about (ADR Decision
 * 542). On the public demo patient dd55a928, on Lamivudine / zidovudine, Lamivudine, Stavudine and Nevirapine, "Does he
 * have any drug interactions?" said "Also in the safety warnings beside this answer: 1 interaction, a caution (1
 * Moderate). [138]": zidovudine with stavudine, two drugs that work against each other, was named only in the chip.
 */
public class CautionsLineNamesItsDrugsTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void theCautionsLineNamesTheDrugsOfEachCaution() {
		PatientChart chart = ask("Does he have any drug interactions?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String line = null;
		for (String each : answer.split("\n")) {
			if (each.startsWith(DrugReferenceInjector.ALSO_IN_THE_WARNINGS)) {
				line = each;
			}
		}
		assertNotNull(line, "precondition: the caution is counted on its own line: " + answer);
		assertTrue(line.matches("Also in the safety warnings beside this answer: 1 interaction, a caution \\(1 Moderate\\) — "
				+ "Zidovudine with Stavudine\\. \\[\\d+\\]"), "the line names the caution's drugs: " + line);
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-lamzid-" + n, "Lamivudine / zidovudine", "Lamivudine",
				"Zidovudine"));
		}
		orders.add(DrugReferenceTestSupport.activeOrder("order-lam", "Lamivudine", "Lamivudine"));
		orders.add(DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine", "Stavudine"));
		for (int n = 1; n <= 4; n++) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-nvp-" + n, "Nevirapine", "Nevirapine"));
		}
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Lamivudine", "Zidovudine", "Stavudine",
				"Nevirapine"), null, null, null, orders),
			question);
	}
}
