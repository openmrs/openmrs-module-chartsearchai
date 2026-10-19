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
	 * A drug given "to a patient on" another is the two given together (ADR Decision 352): on the 3.7.1 standalone "Can I
	 * give clarithromycin to a patient on simvastatin?" fitted no shape, and the model told Joshua Johnson "No — Simvastatin
	 * should not be given", the other drug's verdict.
	 */
	@Test
	public void aDrugGivenToAPatientOnAnotherIsTheTwoTogether() {
		same("Can I give her clarithromycin with simvastatin?", "Can I give clarithromycin to a patient on simvastatin?",
			"Can I give clarithromycin to a patient taking simvastatin?");
	}

	/**
	 * A drug safe "in combination with" her medications is one safe with them (ADR Decision 353): on the 3.7.1 standalone
	 * "Is clarithromycin safe in combination with her current regimen?" fitted no shape, and the model told Karen Sanchez
	 * "Clarithromycin can be given, with one caution", a clearance.
	 */
	@Test
	public void aDrugSafeInCombinationWithHerMedicationsIsOneSafeWithThem() {
		same("Is ibuprofen safe with her current medications?", "Is ibuprofen safe in combination with her current regimen?",
			"Is ibuprofen safe together with her current medications?");
	}

	/**
	 * A drug "as well as" one of hers is the two given together (ADR Decision 355): on the 3.7.1 standalone "Can he have
	 * ibuprofen as well as his warfarin?" fitted no shape, and the model answered every patient.
	 */
	@Test
	public void aDrugAsWellAsHersIsTheTwoTogether() {
		same("Can he have ibuprofen with his warfarin?", "Can he have ibuprofen as well as his warfarin?",
			"Can I give him ibuprofen as well as his warfarin?");
	}

	/**
	 * Drugs listed with commas are the drugs listed with "and" (ADR Decision 356): on the 3.7.1 standalone "Can I give her
	 * ibuprofen, naproxen and aspirin?" fitted no shape — the tokenizer drops the comma — and the model answered every
	 * patient with its own verdict.
	 */
	@Test
	public void drugsListedWithCommasAreTheDrugsListedWithAnd() {
		same("Can I give her ibuprofen and naproxen and aspirin?", "Can I give her ibuprofen, naproxen and aspirin?",
			"Can I give her ibuprofen, naproxen, and aspirin together?");
	}

	/**
	 * "Fine" and "alright" ask what "safe" asks of a proposal (ADR Decision 357): on the 3.7.1 standalone "Is it fine to
	 * give him paracetamol for a fever?" fitted no shape, and the model answered every patient "The records do not address".
	 */
	@Test
	public void fineToGiveItIsSafeToGiveIt() {
		same("Is it safe to give him paracetamol for a fever?", "Is it fine to give him paracetamol for a fever?",
			"Is it alright to give him paracetamol for a fever?");
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

	/**
	 * A drug proposed beside one of hers, whose findings make no verdict — an unrated contraindication among them — is
	 * answered as its proposal alone is, after her drug is named (ADR Decision 354): on the 3.7.1 standalone "Can I
	 * prescribe ibuprofen alongside her lisinopril?" went to the model for Joshua Johnson, on lisinopril and recorded
	 * allergic to aspirin, which answered "No, Ibuprofen should not be prescribed".
	 */
	@Test
	public void aDrugBesideHersWhoseFindingsMakeNoVerdictIsAnsweredAsItsProposal() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		String alone = askAllergic("Can I give her ibuprofen?", names, orders);
		assertNotNull(alone, "precondition: the module answers the proposal alone");
		for (String question : Arrays.asList("Can I prescribe ibuprofen alongside her lisinopril?",
				"Can I give her ibuprofen with her lisinopril?")) {
			assertEquals("Lisinopril is one of this patient's active medications.\n" + alone,
				askAllergic(question, names, orders), question);
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
