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
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * An amount stated in words before the drug — "more lidocaine", "a second dose of neomycin" — is a dose the question
 * states, read past as a number is (ADR Decision 326). On the 3.7.1 standalone "Can I give her more lidocaine?" fitted no
 * shape and went to the model for every patient: Karen Sanchez was told "No — Lidocaine should not be given due to an
 * Unknown severity interaction with Metronidazole", and Joshua Johnson "Lidocaine can be given, with one caution". Over the
 * shipped knowledge base.
 */
public class AmountInWordsDoseTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void anAmountInWordsIsAStatedDoseReadPastAsANumberIs() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
				DrugReferenceTestSupport.activeOrder("order-lido", "Lidocaine", "Lidocaine"))) {
			String plain = ask("Can I give her lidocaine?", order);
			assertNotNull(plain, order.getDisplay() + " — precondition: the module answers the proposal");
			for (String question : Arrays.asList("Can I give her more lidocaine?", "Can I give her another dose of lidocaine?",
					"Can I give her a second dose of lidocaine?", "Can I give her an extra dose of lidocaine?")) {
				assertEquals(plain, ask(question, order), question + " / " + order.getDisplay());
				assertTrue(QueryScopeRouter.statesADose(QueryScopeRouter.words(question.replace("lidocaine",
					QueryScopeRouter.DRUG_NAME))), question + " states a dose");
			}
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
