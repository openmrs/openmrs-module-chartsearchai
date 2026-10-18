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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Three or more drugs asked about together are answered by the module: which she takes and which she does not, what
 * relates them to each other, then each drug she does not take as its proposal alone is answered (ADR Decision 309). On
 * the 3.7.1 standalone "Can I give her fluconazole with her nevirapine and lopinavir?" went to the model for every patient
 * not on both, which answered "No — Lopinavir should not be given with Nevirapine" and never spoke of fluconazole. Over the
 * shipped knowledge base.
 */
public class SeveralDrugsTogetherTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Can I give her fluconazole with her nevirapine and lopinavir?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void threeDrugsNoneOfThemHersAreEachAnswered() {
		String answer = ask(Arrays.asList(DrugReferenceTestSupport.activeOrder("o-asa", "Aspirin 81mg", "Aspirin")));
		assertNotNull(answer, "the module answers");
		List<String> lines = Arrays.asList(answer.split("\n"));
		assertEquals("Fluconazole, Nevirapine and Lopinavir, asked about together; none is one of this patient's active "
				+ "medications.", lines.get(0), "was: " + answer);
		assertEquals("Between them:", lines.get(1), "was: " + answer);
		assertTrue(answer.contains("Lopinavir") && answer.matches("(?s).*Nevirapine.*— Major.*"), "the pair is stated: " + answer);
		for (String drug : Arrays.asList("Fluconazole", "Nevirapine", "Lopinavir")) {
			assertTrue(lines.contains("For " + drug + ":"), drug + " is answered, was: " + answer);
		}
		assertTrue(!answer.contains("can be given"), "no clearance, was: " + answer);
	}

	@Test
	public void threeDrugsOneOfThemHersNameHersAndAnswerTheOthers() {
		String answer = ask(Arrays.asList(DrugReferenceTestSupport.activeOrder("o-nvp", "Nevirapine 200mg", "Nevirapine")));
		assertNotNull(answer, "the module answers");
		List<String> lines = Arrays.asList(answer.split("\n"));
		assertTrue(lines.get(0).startsWith("Nevirapine is one of this patient's active medications; Fluconazole and Lopinavir are"
				+ " not."), "was: " + answer);
		assertTrue(lines.contains("For Fluconazole:") && lines.contains("For Lopinavir:"), "was: " + answer);
		assertTrue(!lines.contains("For Nevirapine:"), "her own drug is not proposed, was: " + answer);
		// Fluconazole against her nevirapine is fluconazole's own finding, never "no finding about Fluconazole".
		int fluconazole = lines.indexOf("For Fluconazole:");
		assertTrue(lines.get(fluconazole + 1).contains("Fluconazole")
				&& !lines.get(fluconazole + 1).startsWith("The interaction check raised no finding"), "was: " + answer);
		assertTrue(answer.contains("Fluconazole interacts with active order Nevirapine"), "was: " + answer);
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			QUESTION).getModuleAnswer();
	}
}
