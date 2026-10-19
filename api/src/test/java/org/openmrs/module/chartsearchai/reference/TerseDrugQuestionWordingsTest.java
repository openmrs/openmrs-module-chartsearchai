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
 * Terse wordings of three questions the module answers, each answered as its fuller form is (ADR Decision 350). On the
 * 3.7.1 standalone each fitted no shape and the model answered all 45 patients: "Check interactions for amoxicillin"
 * listed, for patients on no medication, amoxicillin's Major rows against drugs they do not take; "Any contraindications
 * to giving her metformin?" told Karen Sanchez "Metformin can be given, with one caution"; and "Safe to give
 * ceftriaxone?" told Brian Lopez "Ceftriaxone can be given, with one caution". Over the shipped knowledge base.
 */
public class TerseDrugQuestionWordingsTest extends BaseModuleContextSensitiveTest {

	private static final List<List<PatientClinicalContext.ActiveDrugOrder>> PATIENTS = Arrays.asList(
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin")),
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-war", "Warfarin 5mg", "Warfarin")),
		Arrays.<PatientClinicalContext.ActiveDrugOrder> asList());

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void checkInteractionsForADrugIsWhetherItInteracts() {
		same("Does ibuprofen interact with her medications?", "Check interactions for ibuprofen",
			"Check for drug interactions with ibuprofen", "Please check interactions for ibuprofen");
	}

	@Test
	public void anyContraindicationsToGivingItIsWhetherThereIsOne() {
		same("Is there a contraindication to giving her ibuprofen?", "Any contraindications to giving her ibuprofen?",
			"Are there any contraindications to giving her ibuprofen?");
	}

	@Test
	public void safeToGiveItIsWhetherItIsSafeToGiveIt() {
		same("Is it safe to give ibuprofen?", "Safe to give ibuprofen?", "Safe to start ibuprofen?");
	}

	/**
	 * A question whose "No" would read backwards, about a drug only an unrated contraindication relates to her, is answered
	 * with what the check found and no verdict, as its proposal is (ADR Decision 351): on the 3.7.1 standalone "Is there a
	 * problem with giving aspirin to this patient?" went to the model for the four patients recorded allergic to aspirin
	 * or to its group, which answered "No — aspirin should not be given", a "No" that reads as "no problem".
	 */
	@Test
	public void aNegatedQuestionAboutAnUnratedContraindicationStatesWhatTheCheckFound() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		String expected = askAllergic("Can I give her aspirin?", names, orders);
		assertNotNull(expected, "precondition: the module answers the proposal");
		org.junit.jupiter.api.Assertions.assertTrue(expected.startsWith(DrugReferenceInjector.FOUND_LEAD_OPENING),
			"precondition: with no verdict — " + expected);
		for (String question : Arrays.asList("Is there a problem with giving aspirin to this patient?",
				"Is there any harm in giving her aspirin?", "Is aspirin dangerous for her?")) {
			assertEquals(expected, askAllergic(question, names, orders), question);
		}
	}

	private static String askAllergic(String question, LinkedHashSet<String> names,
			List<PatientClinicalContext.ActiveDrugOrder> orders) {
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					DrugReferenceTestSupport.set("aspirin"), null, orders), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static void same(String fuller, String... terse) {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : PATIENTS) {
			String expected = ask(fuller, orders);
			assertNotNull(expected, "precondition: the module answers " + fuller + " / " + orders);
			for (String question : terse) {
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
