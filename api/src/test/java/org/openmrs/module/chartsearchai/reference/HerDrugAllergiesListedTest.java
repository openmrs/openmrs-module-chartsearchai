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
 * Her drug allergies, asked for, are listed from her allergy records by the module (ADR Decision 366): on the 3.7.1
 * standalone "What are her drug allergies?" was the model's, which left Sarah Taylor's dexamethasone allergy out of a list
 * of nine and wrote Barbara Miller's as "an allergy to Ibuprofen and.". Over the shipped knowledge base.
 */
public class HerDrugAllergiesListedTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herDrugAllergiesAreListedFromHerRecords() {
		PatientClinicalContext.ActiveDrugOrder metronidazole = DrugReferenceTestSupport.activeOrder("order-mtz",
			"Metronidazole", "Metronidazole");
		String expected = ask("What drugs should she avoid?", DrugReferenceTestSupport.set("dexamethasone", "clarithromycin"),
			metronidazole).split("\n")[1];
		assertTrue(expected.startsWith("Recorded drug allergies: ") && expected.toLowerCase().contains("dexamethasone")
				&& expected.toLowerCase().contains("clarithromycin"), "precondition: the allergy line — " + expected);
		for (String question : Arrays.asList("What are her drug allergies?", "What are his drug allergies?",
				"Does she have any drug allergies?", "List her medication allergies", "What drugs is she allergic to?")) {
			String answer = ask(question, DrugReferenceTestSupport.set("dexamethasone", "clarithromycin"), metronidazole);
			assertNotNull(answer, question + " — the module answers");
			assertEquals(expected, answer, question);
			assertEquals("No drug allergies are recorded for this patient.", ask(question, null, metronidazole),
				question + " / none recorded");
		}
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
