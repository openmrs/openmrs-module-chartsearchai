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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether a drug is safe with her allergies, or whether she is allergic to it, is answered with what the check found
 * between the drug and her recorded allergies — and "allergies" is never read as a drug name (ADR Decision 327). On the
 * 3.7.1 standalone "Is his clarithromycin safe with his allergies?" was answered for every patient that no interaction
 * check was run for "allergies", "not a drug name the interaction data recognises" — Kevin Brown, recorded allergic to the
 * clarithromycin he takes, among them. Over the shipped knowledge base.
 */
public class DrugAgainstHerAllergiesTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void anAllergyToTheDrugIsStated() {
		for (String question : Arrays.asList("Is his clarithromycin safe with his allergies?",
				"Is he allergic to clarithromycin?", "Is clarithromycin safe given his allergies?")) {
			String answer = ask(question, DrugReferenceTestSupport.set("clarithromycin"),
				DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycine Co 500mg", "Clarithromycin"));
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			assertEquals(DrugReferenceInjector.ABOUT_HER_ALLERGIES_OPENING + "Clarithromycin and this patient's recorded "
					+ "allergies:", lines[0], question + " — was: " + answer);
			assertTrue(lines[1].startsWith("The patient has a recorded allergy to Clarithromycin."), question + " — was: " + answer);
			assertTrue(!answer.contains("\"allergies\""), question + " — allergies is no drug name: " + answer);
		}
	}

	@Test
	public void noAllergyRelatingToTheDrugIsSaid() {
		PatientClinicalContext.ActiveDrugOrder metronidazole = DrugReferenceTestSupport.activeOrder("order-mtz",
			"Metronidazole", "Metronidazole");
		assertEquals("Clarithromycin is not one of this patient's active medications.\n"
				+ "No drug allergies are recorded for this patient.",
			ask("Is his clarithromycin safe with his allergies?", null, metronidazole));
		assertEquals("Clarithromycin is not one of this patient's active medications.\n"
				+ "None of this patient's recorded drug allergies that the drug data identifies relates to Clarithromycin.",
			ask("Is his clarithromycin safe with his allergies?", DrugReferenceTestSupport.set("aspirin"), metronidazole));
	}

	private static String ask(String question, Set<String> allergies, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, allergies, null,
				orders), question).getModuleAnswer();
	}
}
