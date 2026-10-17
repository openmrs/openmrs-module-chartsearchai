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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether she is allergic to any of her medications is answered by the module from its allergy findings (ADR Decision
 * 295). Asked of fourteen patients on the 3.7.1 standalone, the model read the question as "does she have drug
 * allergies": George Anderson, on acetaminophen and allergic to NSAIDs, ibuprofen and pantoprazole, was told "Yes";
 * so were Melissa Wright (spironolactone; esomeprazole) and Mark Smith (chloroquine, Benadryl; ciprofloxacin); and Betty
 * Williams, allergic to the lidocaine she takes, was told "No — Lidocaine should not be given".
 */
public class AllergyToHerMedicationsQuestionTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void anAllergyToADrugSheTakesIsAYes() {
		for (String question : Arrays.asList("Is she allergic to any of her medications?",
				"Is she allergic to any of her current medications?", "Does she have an allergy to any of her meds?",
				"Is she on anything she is allergic to?", "Is she taking any medication she's allergic to?",
				// ADR Decision 300: Kevin Brown was told "No — Clarithromycin should not be given" of his own allergy.
				"Are any of her medications contraindicated with her allergies?",
				"Do any of her medications conflict with her allergies?")) {
			String answer = ask(question, DrugReferenceTestSupport.set("lidocaine"),
				DrugReferenceTestSupport.activeOrder("order-lido", "Lidocaine", "Lidocaine"),
				DrugReferenceTestSupport.activeOrder("order-bupi", "Bupivacaine", "Bupivacaine"));
			String[] lines = String.valueOf(answer).split("\n");
			assertEquals("Yes — this patient has a recorded allergy to one of the medications this patient is taking:",
				lines[0], question + " — was: " + answer);
			assertEquals(true, lines[1].startsWith("The patient has a recorded allergy to Lidocaine."),
				question + " — was: " + answer);
		}
	}

	/** An allergy recorded by a drug group's name is an allergy to each member (ADR Decision 231), so her aspirin under a
	 *  recorded "NSAIDs" allergy is a "Yes" — on the 3.7.1 standalone Agnes Adams was first told none of her allergies
	 *  was to her medications. */
	@Test
	public void anAllergyRecordedByTheNameOfHerDrugsGroupIsAYes() {
		String answer = ask("Is she allergic to any of her medications?", DrugReferenceTestSupport.set("NSAIDs"),
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		String[] lines = String.valueOf(answer).split("\n");
		assertEquals("Yes — this patient has a recorded allergy to one of the medications this patient is taking:", lines[0],
			"was: " + answer);
		assertEquals(true, lines[1].contains("is in the drug group (NSAID) the patient has a recorded allergy to."),
			"was: " + answer);
	}

	@Test
	public void aDrugInTheClassOfAnAllergyIsStatedApartFromAnAllergyToItself() {
		String answer = ask("Is he allergic to any of his medications?", DrugReferenceTestSupport.set("dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"));
		String[] lines = String.valueOf(answer).split("\n");
		assertEquals("Yes — this patient has a recorded allergy to one of the medications this patient is taking:", lines[0],
			"was: " + answer);
		assertEquals(true, lines[1].startsWith("The patient has a recorded allergy to Dexamethasone."), "was: " + answer);
		assertEquals("Possible cross-reactivity with a recorded allergy:", lines[2], "was: " + answer);
		assertEquals(true, lines[3].startsWith("Prednisone is in the same ATC class (H02AB) as the patient's allergy to "
				+ "Dexamethasone — possible cross-reactivity."), "was: " + answer);
	}

	@Test
	public void onlyACrossReactivityIsNoAllergyToTheDrugItself() {
		String answer = ask("Is he allergic to any of his medications?", DrugReferenceTestSupport.set("dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"),
			DrugReferenceTestSupport.activeOrder("order-metformin", "Metformin", "Metformin"));
		String[] lines = String.valueOf(answer).split("\n");
		assertEquals("None of this patient's recorded drug allergies that the drug data identifies is to one of the "
				+ "medications this patient is taking. Possible cross-reactivity with a recorded allergy:", lines[0],
			"was: " + answer);
		assertEquals(true, lines[1].startsWith("Prednisone is in the same ATC class (H02AB) as the patient's allergy to "
				+ "Dexamethasone — possible cross-reactivity."), "was: " + answer);
	}

	@Test
	public void noAllergyToADrugSheTakesOrItsClassSaysSo() {
		assertEquals("None of this patient's recorded drug allergies that the drug data identifies is to one of the "
				+ "medications this patient is taking, or to a drug in the same group or class as one of them.",
			ask("Is she allergic to any of her medications?", DrugReferenceTestSupport.set("esomeprazole"),
				DrugReferenceTestSupport.activeOrder("order-spiro", "Spironolactone", "Spironolactone")));
		assertEquals("No drug allergies are recorded for this patient.", ask("Is she allergic to any of her medications?",
			new LinkedHashSet<String>(), DrugReferenceTestSupport.activeOrder("order-spiro", "Spironolactone", "Spironolactone")));
	}

	@Test
	public void aQuestionAboutHerAllergiesAloneIsNotThisOne() {
		assertNull(ask("Does she have any allergies?", DrugReferenceTestSupport.set("esomeprazole"),
			DrugReferenceTestSupport.activeOrder("order-spiro", "Spironolactone", "Spironolactone")));
	}

	private static String ask(String question, Set<String> allergens, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		List<PatientClinicalContext.ActiveDrugOrder> list = new ArrayList<PatientClinicalContext.ActiveDrugOrder>(
				Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null, allergens, null,
				list), question).getModuleAnswer();
	}
}
