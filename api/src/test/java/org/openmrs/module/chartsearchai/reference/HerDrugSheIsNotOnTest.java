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
 * Whether her drug is safe, asked of a drug she is not on, says she is not on it and answers what the check found of it
 * against her medications; asked of a drug she is on, it is answered about her medication (ADR Decision 315). On the 3.7.1
 * standalone "Is her methotrexate safe with her other medications?" went to the model for every patient: those on no
 * methotrexate were told "Methotrexate can be given, with one caution", and Mark Williams, on ondansetron, "No —
 * Methotrexate should not be given with Ondansetron, as there is an Unknown severity interaction". Over the shipped
 * knowledge base.
 */
public class HerDrugSheIsNotOnTest extends BaseModuleContextSensitiveTest {

	private static final List<String> QUESTIONS = Arrays.asList("Is her methotrexate safe with her other medications?",
		"Is her methotrexate safe?");

	private static final String PLAIN = "Is methotrexate safe with her other medications?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOwnDrugIsAnsweredAboutHerMedication() {
		PatientClinicalContext.ActiveDrugOrder[] orders = {
				DrugReferenceTestSupport.activeOrder("order-mtx", "Methotrexate", "Methotrexate"),
				DrugReferenceTestSupport.activeOrder("order-sal", "Salicylic acid", "Salicylic acid") };
		String plain = ask(PLAIN, orders);
		assertNotNull(plain, "precondition: the module answers the drug beside her medications");
		for (String question : QUESTIONS) {
			assertEquals(mask(plain), mask(ask(question, orders)), question);
		}
	}

	@Test
	public void aDrugSheIsNotOnSaysSoBeforeWhatTheCheckFound() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
				DrugReferenceTestSupport.activeOrder("order-ond", "Ondansetron", "Ondansetron"),
				DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril"))) {
			String plain = ask(PLAIN, order);
			assertNotNull(plain, order.getDisplay() + " — precondition: the module answers the drug beside her medications");
			for (String question : QUESTIONS) {
				String answer = ask(question, order);
				assertNotNull(answer, question + " / " + order.getDisplay() + " — the module answers");
				assertEquals("Methotrexate" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n" + mask(plain), mask(answer),
					question + " / " + order.getDisplay());
			}
		}
	}

	/** With no active order at all the answer already says she has none. */
	@Test
	public void noActiveOrderIsSaidOnce() {
		String plain = ask(PLAIN);
		assertNotNull(plain, "precondition: the module answers the drug beside her medications");
		for (String question : QUESTIONS) {
			assertEquals(mask(plain), mask(ask(question)), question);
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
