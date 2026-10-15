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

	/**
	 * "Swap" is a switch (ADR Decision 253). On the 3.7.1 standalone Donald Thompson's "Can I swap his atorvastatin for
	 * rosuvastatin?" fitted no shape, and the model answered "No — Rosuvastatin should not be given … as Rosuvastatin is in
	 * the same ATC class (C10AA) as active order Atorvastatin": a duplicate of the very order the swap stops.
	 */
	@Test
	public void aSwapIsASwitch() {
		for (String question : Arrays.asList("Can I swap his nevirapine for efavirenz?", "Can I swap his nevirapine to efavirenz?",
			"Is it safe to swap nevirapine for efavirenz?")) {
			String answer = ask(question).getModuleAnswer();

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.split("\n")[0].equals("Nevirapine is one of this patient's active medications, and this switch "
					+ "replaces it."), question + " was: " + answer);
			assertTrue(!answer.contains("No —"), "the order the swap stops is no reason to withhold: " + answer);
		}
	}

	@Test
	public void aSwitchToADrugSheAlreadyTakesOrFromOneSheDoesNotIsLeftToTheModel() {
		assertNull(ask("Can I switch his lamivudine to nevirapine?").getModuleAnswer(), "the new drug is already his");
		assertNull(ask("Can I switch his rifampicin to efavirenz?").getModuleAnswer(), "the old drug is not his");
	}

	/**
	 * A switch naming the drug switched TO first, "instead of" or "in place of" the one it replaces (ADR Decision 230).
	 * On the 3.7.1 standalone Mogan Kamwara's "Can I give her efavirenz instead of nevirapine?" was answered by the model
	 * "No — Efavirenz should not be given because it interacts with active order Nevirapine": the withholding reason was
	 * the very order the switch stops, the defect Decision 219 fixed for the shapes naming the old drug first.
	 */
	@Test
	public void aSwitchNamingTheNewDrugFirstIsAnsweredTheSameWay() {
		String switched = ask("Can I switch his nevirapine to efavirenz?").getModuleAnswer();
		assertNotNull(switched, "precondition: the module answers the switch naming the old drug first");
		for (String question : Arrays.asList("Can I give him efavirenz instead of nevirapine?",
			"Can he take efavirenz instead of his nevirapine?", "Can I use efavirenz in place of nevirapine?",
			"Is it safe to give efavirenz instead of nevirapine?")) {
			assertEquals(switched, ask(question).getModuleAnswer(), question);
		}
	}

	@Test
	public void aDrugGivenInsteadOfOneHeDoesNotTakeIsLeftToTheModel() {
		assertNull(ask("Can I give him efavirenz instead of rifampicin?").getModuleAnswer(), "the old drug is not his");
		assertNull(ask("Can I give him nevirapine instead of lamivudine?").getModuleAnswer(), "the new drug is already his");
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
