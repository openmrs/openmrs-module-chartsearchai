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
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * One drug asked about beside a named group of her medications is answered as it is beside her medications, under a lead
 * saying the check cannot tell which of her orders make up that group and checked the drug against all of them (ADR
 * Decision 313). On the 3.7.1 standalone "Is dolutegravir safe with her TB preventive therapy?" went to the model for every
 * patient: Mogan Kamwara, on nevirapine and no TB drug, was told "No — dolutegravir should not be given: it interacts with
 * active order Nevirapine", a "No" that reads as about TB therapy she does not take; and a patient on no medication at all
 * was told "The records do not address" it. Over the shipped knowledge base.
 */
public class GroupOfHerMedicationsNamedTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugSafeWithANamedGroupIsAnsweredAsBesideHerMedicationsUnderTheGroupLead() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
				DrugReferenceTestSupport.activeOrder("order-3hp", "Isoniazid / Rifapentine", "Isoniazid / Rifapentine"))) {
			String plain = ask("Is dolutegravir safe with her medications?", order);
			assertNotNull(plain, "precondition: the module answers beside her medications");
			for (String question : Arrays.asList("Is dolutegravir safe with her TB preventive therapy?",
					"Is dolutegravir okay to give with her TB preventive therapy?",
					"Can she take dolutegravir and her TB preventive therapy together?",
					"Can I give her dolutegravir with her TB preventive therapy?")) {
				String answer = ask(question, order);
				assertNotNull(answer, question + " — the module answers");
				String[] lines = answer.split("\n", 2);
				assertEquals(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING + "TB preventive therapy"
						+ DrugReferenceInjector.GROUP_NOT_IDENTIFIED_CLOSING + "Dolutegravir against all of them.", lines[0],
					question + " — was: " + answer);
				assertEquals(mask(plain), mask(lines[1]), question + " — answered as beside her medications: " + answer);
			}
		}
	}

	@Test
	public void aNamedGroupAskedFirstIsAnsweredAsWhetherTheDrugInteractsWithHerMedications() {
		PatientClinicalContext.ActiveDrugOrder order = DrugReferenceTestSupport.activeOrder("order-3hp",
			"Isoniazid / Rifapentine", "Isoniazid / Rifapentine");
		String plain = ask("Does efavirenz interact with his medications?", order);
		assertNotNull(plain, "precondition: the module answers whether it interacts with his medications");
		String answer = ask("Will his TB preventive therapy interact with efavirenz?", order);
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n", 2);
		assertEquals(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING + "TB preventive therapy"
				+ DrugReferenceInjector.GROUP_NOT_IDENTIFIED_CLOSING + "Efavirenz against all of them.", lines[0], "was: " + answer);
		assertEquals(mask(plain), mask(lines[1]), "was: " + answer);
	}

	/** "Her regimen", "her current medications" name every medication she takes, no group within them: no lead. */
	@Test
	public void herWholeRegimenIsNoGroup() {
		PatientClinicalContext.ActiveDrugOrder order = DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg",
			"Nevirapine");
		String answer = ask("Is dolutegravir safe with her current regimen?", order);
		assertNotNull(answer, "the module answers");
		assertEquals(mask(ask("Is dolutegravir safe with her medications?", order)), mask(answer), "was: " + answer);
	}

	/** With no active order there is no group to tell apart: the answer says she has none, under no lead. */
	@Test
	public void noActiveOrderNeedsNoLead() {
		String answer = ask("Is dolutegravir safe with her TB preventive therapy?");
		assertNotNull(answer, "the module answers");
		assertEquals(mask(ask("Is dolutegravir safe with her medications?")), mask(answer), "was: " + answer);
	}

	private static String mask(String answer) {
		return answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	/**
	 * Her own drug proposed beside a group of her medications is answered as her own drug is, under the group lead (ADR
	 * Decision 381): on the 3.7.1 standalone "Can I give her paracetamol with her cold medicine?" went to the model for
	 * George Anderson, on acetaminophen alone — "The records do not address the safety of giving paracetamol with cold
	 * medicine" — while "Can she take paracetamol?" was answered as his own medication.
	 */
	@Test
	public void herOwnDrugBesideANamedGroupIsAnsweredAsHerOwnUnderTheGroupLead() {
		String answer = ask("Can I give her paracetamol with her cold medicine?",
			DrugReferenceTestSupport.activeOrder("order-apap", "Acetaminophen", "Acetaminophen"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.replaceAll("\\[\\d+\\]", "[n]").split("\n");
		assertEquals(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING + "cold medicine"
				+ DrugReferenceInjector.GROUP_NOT_IDENTIFIED_CLOSING + "Acetaminophen against all of them.", lines[0], answer);
		assertEquals("Acetaminophen is one of this patient's active medications (Acetaminophen [n]), and this patient has no "
				+ "other active medication to check it against.", lines[1], answer);
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(35, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
