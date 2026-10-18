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
 * A combination product proposed that carries a substance she already takes is answered with what the check found about
 * the product — that substance already in her order among it — and no verdict (ADR Decision 330). On the 3.7.1 standalone
 * "Can I give her Bactrim?" went to the model for patients on cotrimoxazole, which wrote "For Sulfamethoxazole, the drug can
 * be given, with one caution", and "Is Augmentin safe for her?" told Priya, on amoxicillin, "The drug can be given, with one
 * caution: adding it would duplicate that order". Over the shipped knowledge base.
 */
public class ProductCarryingHerOwnDrugTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProductCarryingHerDrugStatesWhatTheCheckFound() {
		PatientClinicalContext.ActiveDrugOrder cotrimoxazole = DrugReferenceTestSupport.activeOrder("order-ctx",
			"Cotrimoxazole 960mg", "Cotrimoxazole");
		String alone = ask("Can I give her Bactrim?", cotrimoxazole);
		assertNotNull(alone, "the module answers");
		String[] lines = alone.split("\n");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Bactrim:", lines[0], alone);
		assertTrue(alone.contains("\nSulfamethoxazole is already in active order Cotrimoxazole 960mg"), alone);
		assertTrue(alone.contains("\nTrimethoprim is already in active order Cotrimoxazole 960mg"), alone);

		String beside = ask("Can I give her Bactrim?", cotrimoxazole, DrugReferenceTestSupport.activeOrder("order-lpv",
			"Lopinavir / ritonavir 200mg/50mg", "Lopinavir / ritonavir"));
		assertNotNull(beside, "the module answers beside her other orders");
		assertTrue(beside.contains("\nSulfamethoxazole interacts with active order Ritonavir — Moderate."), beside);

		String augmentin = ask("Is Augmentin safe for her?", DrugReferenceTestSupport.activeOrder("order-amox",
			"Amoxicillin 500mg", "Amoxicillin"));
		assertNotNull(augmentin, "the module answers Augmentin");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Augmentin:", augmentin.split("\n")[0], augmentin);
		assertTrue(augmentin.contains("\nAmoxicillin is already in active order Amoxicillin 500mg"), augmentin);
		for (String answer : Arrays.asList(alone, beside, augmentin)) {
			assertTrue(!answer.contains("can be given") && !answer.startsWith("No"), "no verdict: " + answer);
		}
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
