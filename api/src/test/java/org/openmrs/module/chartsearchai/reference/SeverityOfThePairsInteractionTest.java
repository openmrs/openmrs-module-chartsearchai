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
 * How severe the interaction between two drugs is, is asked of the interaction itself and answered as what it is (ADR
 * Decision 331): the findings relating the two, each with its rating, and neither "Yes" nor "No". On the 3.7.1 standalone
 * "How severe is the interaction between her rifampicin and nevirapine?" went to the model for every patient, which mostly
 * answered "No — Nevirapine should not be given", a "No" to how severe it is. Over the shipped knowledge base.
 */
public class SeverityOfThePairsInteractionTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void howSevereTheInteractionIsIsAnsweredAsWhatItIs() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"))) {
			String what = ask("What is the interaction between her rifampicin and nevirapine?", order);
			assertNotNull(what, order.getDisplay() + " — precondition: the module answers what the interaction is");
			for (String question : Arrays.asList("How severe is the interaction between her rifampicin and nevirapine?",
					"How serious is the interaction between rifampicin and her nevirapine?",
					"What is the severity of the interaction between rifampicin and nevirapine?")) {
				String answer = ask(question, order);
				assertNotNull(answer, question + " / " + order.getDisplay());
				assertEquals(mask(ask(question.contains("severity") ? "What is the interaction between rifampicin and nevirapine?"
						: question.contains("her rifampicin") ? "What is the interaction between her rifampicin and nevirapine?"
								: "What is the interaction between rifampicin and her nevirapine?", order)), mask(answer),
					question + " / " + order.getDisplay());
				assertTrue(!answer.startsWith("No") && !answer.startsWith("Yes"), answer);
			}
		}
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
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
