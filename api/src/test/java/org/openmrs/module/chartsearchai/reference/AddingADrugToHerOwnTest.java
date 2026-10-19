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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A drug added to one of hers is answered as that drug given with hers (ADR Decision 343). On the 3.7.1 standalone "Can
 * I add fluconazole to her cotrimoxazole?" fitted no shape, and the model answered all 45 patients, most with "Fluconazole
 * can be added, with one caution" — a clearance — whether or not she takes cotrimoxazole. Over the shipped knowledge base.
 */
public class AddingADrugToHerOwnTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugAddedToHersIsAnsweredAsGivenWithHers() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : Arrays.asList(
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ctx", "Cotrimoxazole 960mg", "Sulfamethoxazole",
					"Trimethoprim"), DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
				Arrays.<PatientClinicalContext.ActiveDrugOrder> asList())) {
			String expected = ask("Can I give her fluconazole with her cotrimoxazole?", orders);
			assertNotNull(expected, "precondition: the module answers " + orders);
			for (String question : Arrays.asList("Can I add fluconazole to her cotrimoxazole?",
					"Is it safe to add fluconazole to her cotrimoxazole?", "Can I add fluconazole to his cotrimoxazole?")) {
				assertEquals(expected, ask(question, orders), question + " / " + orders);
			}
		}
	}

	private static String ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
