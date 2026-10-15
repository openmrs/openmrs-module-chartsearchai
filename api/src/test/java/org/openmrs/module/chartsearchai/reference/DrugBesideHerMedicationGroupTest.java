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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether one drug is a problem beside a group of her medications is answered as whether it interacts with her medications
 * (ADR Decision 271). On the 3.7.1 standalone a patient on a rifampicin-based TB regimen asked "Is there any problem
 * combining her TB drugs with fluconazole?" and the model opened "No — Fluconazole should not be combined with rifampin",
 * a "No" that reads backwards to "is there any problem?" — where the run before it had read "Yes, there are problems". The
 * group named no drug, so no shape held the question and the model's lead decided it. Over the shipped knowledge base.
 */
public class DrugBesideHerMedicationGroupTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugBesideAGroupOfHerMedicationsIsAnsweredAsWhetherItInteractsWithThem() {
		for (String question : Arrays.asList("Is there any problem combining her TB drugs with fluconazole?",
			"Does fluconazole interact with her TB drugs?", "Are there any issues with giving fluconazole with her TB treatment?")) {
			String answer = ask(question);
			assertNotNull(answer, question + ": the module answers");
			assertTrue(answer.startsWith("Yes — Fluconazole interacts with this patient's active medications."),
				question + " was: " + answer);
			assertTrue(answer.contains("\nFluconazole interacts with active order Rifampicin (rifampin) — Major."),
				question + " was: " + answer);
			assertTrue(!answer.contains("No —"), question + " was: " + answer);
		}
	}

	private static String ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(35, null,
				new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question).getModuleAnswer();
	}
}
