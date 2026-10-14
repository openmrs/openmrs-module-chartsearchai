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
 * A question whether one group of her own medications interacts with another — "Does her TB treatment interact with her
 * ARVs?" — is a screen of her medications, which states every pair between the two (ADR Decision 221). On the public
 * demo's data the model answered it "The records do not address whether her TB treatment interacts with her ARVs", for a
 * patient whose nevirapine the data rates Major against her rifampicin regimen. The groups are never resolved to drugs:
 * the screen covers every pair of hers, those between the two groups among them.
 */
public class MedicationGroupsScreenContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aQuestionWhetherTwoGroupsOfHerMedicationsInteractIsAnsweredByTheScreen() {
		for (String question : Arrays.asList("Does her TB treatment interact with her ARVs?",
			"Do her TB drugs interact with her antiretrovirals?", "Does her ARV regimen interact with her TB medications?")) {
			String answer = ask(question).getModuleAnswer();

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.matches("(?s).*\\nNevirapine interacts with active order [^\\n]*[Rr]ifamp[^\\n]* — Major\\..*"),
				question + " states the pair between the two groups: " + answer);
		}
	}

	@Test
	public void aQuestionAboutSomethingThatIsNotOneOfHerMedicationGroupsIsLeftToTheModel() {
		assertNull(ask("Does her diet interact with her ARVs?").getModuleAnswer());
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin", "Isoniazid",
				"Pyrazinamide", "Rifampin"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(58, null,
				new LinkedHashSet<String>(Arrays.asList("Nevirapine", "Isoniazid", "Pyrazinamide", "Rifampin")), null, null,
				null, orders), question);
	}
}
