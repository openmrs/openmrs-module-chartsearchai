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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether to continue her drug is answered as "Is her X safe to continue?" is (ADR Decision 341). On the 3.7.1 standalone
 * "Can I continue her Advil?" and "Is it okay to continue her aspirin?" fitted no shape, and the model answered every
 * patient: Kevin Brown, on clarithromycin and no ibuprofen, was told "Ibuprofen can be given, with one caution: it has an
 * Unknown severity interaction with clarithromycin", and Edward Robinson, on nevirapine and no aspirin, "No — aspirin
 * should not be continued: it has an Unknown severity interaction with Nevirapine". Over the shipped knowledge base.
 */
public class ContinuingHerDrugQuestionTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	private static final List<String> QUESTIONS = Arrays.asList("Can I continue her aspirin?",
		"Is it okay to continue her aspirin?", "Is it safe to continue his aspirin?", "Should I continue her aspirin?",
		"Can she keep taking her aspirin?", "Can I keep giving her aspirin?");

	@Test
	public void whetherToContinueHerDrugIsAnsweredAsWhetherItIsSafeToContinue() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
			DrugReferenceTestSupport.activeOrder("order-ibu", "Ibuprofen 400mg", "Ibuprofen"));
		String expected = ask("Is her aspirin safe to continue?", orders);
		assertNotNull(expected, "precondition: the module answers");
		for (String question : QUESTIONS) {
			assertEquals(expected, ask(question, orders), question);
		}
	}

	/** Of a drug she is not on, there is no order of it to continue — said, as Decision 335 says there is none to stop,
	 *  whether or not she has other orders, and of "Is her aspirin safe to continue?" too. */
	@Test
	public void ofADrugSheIsNotOnThereIsNoOrderToContinue() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : Arrays.asList(
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
				Arrays.<PatientClinicalContext.ActiveDrugOrder> asList())) {
			List<String> questions = new ArrayList<String>(QUESTIONS);
			questions.add("Is her aspirin safe to continue?");
			for (String question : questions) {
				assertEquals("Acetylsalicylic acid (aspirin) is not one of this patient's active medications, so there is no "
						+ "order of it to continue.", ask(question, orders), question + " / " + orders);
			}
		}
	}

	private static final String KEEP_BOTH = "Should she keep taking both aspirin and Advil?";

	/**
	 * Whether she should keep taking both of two drugs is answered as two of her own asked about together where both are
	 * hers, and otherwise by saying which she is not on — no order of it to continue — then what relates the two, never a
	 * verdict (ADR Decision 380). On the 3.7.1 standalone "Should she keep taking both aspirin and Advil?" went to the model
	 * for every patient, which told patients on neither "No — she should not take both aspirin and Advil".
	 */
	@Test
	public void keepingBothOfTwoDrugsSheTakesIsAnsweredAsHerTwoTogether() {
		String answer = ask(KEEP_BOTH, Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
			DrugReferenceTestSupport.activeOrder("order-ibu", "Advil 400mg", "Ibuprofen")));
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Acetylsalicylic acid (aspirin) (Aspirin 81mg [n]) and Ibuprofen (Advil 400mg [n])"
				+ DrugReferenceInjector.TWO_OF_HER_OWN_LEAD + "\n"), "was: " + answer);
		assertTrue(answer.contains("— Major."), "what relates them: " + answer);
		assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "no verdict: " + answer);
	}

	@Test
	public void keepingBothOfTwoDrugsSheIsNotOnBothOfSaysWhichSheIsNotOn() {
		String one = ask(KEEP_BOTH, Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin")));
		assertNotNull(one, "the module answers");
		String[] lines = one.split("\n");
		assertEquals("Acetylsalicylic acid (aspirin) is one of this patient's active medications (Aspirin 81mg [n]).", lines[0],
			one);
		assertEquals("Ibuprofen is not one of this patient's active medications, so there is no order of it to continue.",
			lines[1], one);
		assertEquals("What the interaction check relates between them:", lines[2], one);
		assertTrue(lines[3].contains("— Major."), one);

		String neither = ask(KEEP_BOTH, Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")));
		assertNotNull(neither, "the module answers");
		lines = neither.split("\n");
		assertEquals("Acetylsalicylic acid (aspirin) is not one of this patient's active medications, so there is no order of "
				+ "it to continue.", lines[0], neither);
		assertEquals("Ibuprofen is not one of this patient's active medications, so there is no order of it to continue.",
			lines[1], neither);
		assertEquals("What the interaction check relates between them:", lines[2], neither);
		assertTrue(lines[3].contains("— Major."), neither);
		for (String answer : Arrays.asList(one, neither)) {
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —") && !answer.contains("can be given"),
				"no verdict: " + answer);
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
