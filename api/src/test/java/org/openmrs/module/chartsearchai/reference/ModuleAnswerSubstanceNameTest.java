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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The module's answer names a drug it asks about by the row its findings name it by (ADR Decision 267). On the 3.7.1
 * standalone Helen, on methotrexate and salicylic acid, asked "Does her methotrexate interact with her salicylic acid?"
 * and read "Methotrexate (Methotrexate [2]) and Salicylic acid (sodium) (Salicylic acid [1]) are both among this
 * patient's active medications." above "Methotrexate interacts with active order Salicylic acid — Major.": the knowledge
 * base files salicylic acid as three rows, and the lead took the first of them in the data's order — the sodium salt —
 * where the finding names the row her chart names. Over the shipped knowledge base.
 */
public class ModuleAnswerSubstanceNameTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoOfHerDrugsAreNamedInTheLeadAsTheFindingNamesThem() {
		for (String question : Arrays.asList("Does her methotrexate interact with her salicylic acid?",
			"What is the mechanism of the interaction between methotrexate and salicylic acid?")) {
			String answer = ask(question);
			assertNotNull(answer, question + ": the module answers");
			String lead = answer.split("\n")[0];
			assertTrue(lead.matches("Methotrexate( \\(Methotrexate 2\\.5mg[^)]*\\))? and Salicylic acid( \\(Salicylic acid[^)]*\\))?"
					+ " are both among this patient's active medications\\..*"), question + " was: " + answer);
			assertFalse(answer.contains("(sodium)"), question + " was: " + answer);
			assertTrue(Arrays.stream(answer.split("\n")).anyMatch(
				l -> l.startsWith("Methotrexate interacts with active order Salicylic acid — Major.")), question + " was: " + answer);
		}
	}

	private static String ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-mtx", "Methotrexate 2.5mg"),
			DrugReferenceTestSupport.activeOrder("order-sa", "Salicylic acid"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(55, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
