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
 * A drug asked about in place of one she is not on says she is not on it, and is answered as its proposal is against her
 * medications (ADR Decision 323). On the 3.7.1 standalone "Can I give her efavirenz instead of nevirapine?" went to the
 * model for every patient on no nevirapine: Joshua Johnson, on lisinopril, was told "Efavirenz can be given, with two
 * cautions" — a clearance, one of them about the nevirapine he does not take. Over the shipped knowledge base.
 */
public class SwitchFromADrugSheIsNotOnTest extends BaseModuleContextSensitiveTest {

	private static final String LEAD = "Nevirapine is not one of this patient's active medications, so this switch replaces "
			+ "no order of it.";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aSwitchFromADrugSheIsNotOnIsAnsweredAsTheNewDrugsProposal() {
		PatientClinicalContext.ActiveDrugOrder lisinopril = DrugReferenceTestSupport.activeOrder("order-lis",
			"Lisinopril 10 mg", "Lisinopril");
		String plain = ask("Can I give her efavirenz?", lisinopril);
		assertNotNull(plain, "precondition: the module answers the new drug's proposal");
		for (String question : Arrays.asList("Can I give her efavirenz instead of nevirapine?",
				"Can she take efavirenz instead of her nevirapine?", "Can I use efavirenz in place of nevirapine?",
				"Can I give him efavirenz instead of nevirapine?")) {
			String answer = ask(question, lisinopril);
			assertNotNull(answer, question + " — the module answers");
			assertEquals(mask(LEAD + "\n" + plain + "\n" + DrugReferenceInjector.BELOW_FLOOR_SCOPE), mask(answer), question);
			assertTrue(!answer.contains("can be given"), question + " — never a clearance: " + answer);
		}
	}

	@Test
	public void withNoActiveOrderItSaysThereIsNoneToRelateTheNewDrugTo() {
		String answer = ask("Can I give her efavirenz instead of nevirapine?");
		assertEquals(LEAD + "\nThis patient has no active medication orders, so the interaction check had none to relate "
				+ "Efavirenz to.\n" + DrugReferenceInjector.BELOW_FLOOR_SCOPE, answer);
	}

	/** Where she already takes the drug asked about and not the one it would replace, both are said: there is nothing to
	 *  switch. On the 3.7.1 standalone Rose, on efavirenz and no nevirapine, was told "Efavirenz can be given". */
	@Test
	public void aSwitchToADrugSheAlreadyTakesFromOneSheDoesNotSaysBoth() {
		String answer = ask("Can I give her efavirenz instead of nevirapine?",
			DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz 600mg", "Efavirenz"),
			DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine 150mg", "Lamivudine"));
		assertNotNull(answer, "the module answers");
		assertEquals(LEAD + "\nEfavirenz is already one of this patient's active medications (Efavirenz 600mg [n]).",
			mask(answer));
	}

	/**
	 * A switch whose new drug a contraindication relates to her, no rating making a reason to withhold it, is answered with
	 * what the check found and no verdict, as its proposal is (ADR Decision 328): on the 3.7.1 standalone George Anderson,
	 * on acetaminophen and recorded allergic to ibuprofen, was told by the model "No — Ibuprofen should not be given".
	 */
	@Test
	public void aSwitchWhoseNewDrugAContraindicationRelatesStatesWhatTheCheckFound() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-apap", "Acetaminophen", "Acetaminophen"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					DrugReferenceTestSupport.set("ibuprofen"), null, orders), "Can I give him ibuprofen instead of paracetamol?")
				.getModuleAnswer();
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Acetaminophen is one of this patient's active medications, and this switch replaces it.", lines[0],
			"was: " + answer);
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Ibuprofen:", lines[1], "was: " + answer);
		assertTrue(answer.contains("\nThe patient has a recorded allergy to Ibuprofen."), "was: " + answer);
		assertTrue(!answer.contains("No —"), "no rating makes a No: " + answer);
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
