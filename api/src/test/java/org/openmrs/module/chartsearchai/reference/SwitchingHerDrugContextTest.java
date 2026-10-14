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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question whether to SWITCH one of her drugs to another is about the new drug against her medications other than the
 * one it replaces (ADR Decision 219). On the public demo's data, "Can I switch his nevirapine to efavirenz?" was answered
 * by the model "No — Efavirenz should not be used because it interacts with the active order Nevirapine": the very order
 * the switch stops. Over the shipped knowledge base, his lamivudine, nevirapine and stavudine.
 */
public class SwitchingHerDrugContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aSwitchIsAnsweredForTheNewDrugAgainstHerOtherMedications() {
		for (String question : Arrays.asList("Can I switch his nevirapine to efavirenz?",
			"Can I switch him from nevirapine to efavirenz?", "Can I replace his nevirapine with efavirenz?",
			"Is it safe to switch nevirapine to efavirenz?")) {
			String answer = ask(question).getModuleAnswer();

			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertTrue(lines[0].equals("Nevirapine is one of this patient's active medications, and this switch replaces it."),
				question + " was: " + answer);
			assertTrue(!answer.contains("No —"), "the order the switch stops is no reason to withhold: " + answer);
			assertTrue(answer.contains("Efavirenz interacts with active order Lamivudine and Stavudine — Moderate."),
				"the new drug against her other medications: " + answer);
			int replaced = answer.indexOf("\nAbout the order the switch replaces:\n");
			assertTrue(replaced > 0 && answer.indexOf("active order Nevirapine", replaced) > replaced,
				"what relates it to the order it replaces is still stated, under its own heading: " + answer);
		}
	}

	@Test
	public void aSwitchToADrugSheAlreadyTakesOrFromOneSheDoesNotIsLeftToTheModel() {
		assertNull(ask("Can I switch his lamivudine to nevirapine?").getModuleAnswer(), "the new drug is already his");
		assertNull(ask("Can I switch his rifampicin to efavirenz?").getModuleAnswer(), "the old drug is not his");
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine"),
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(66, null,
				new LinkedHashSet<String>(Arrays.asList("Lamivudine", "Nevirapine", "Stavudine")), null, null, null, orders),
			question);
	}
}
