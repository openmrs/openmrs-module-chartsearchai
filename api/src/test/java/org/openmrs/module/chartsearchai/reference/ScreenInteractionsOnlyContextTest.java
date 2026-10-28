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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
 * A question asking only about her drug INTERACTIONS is answered with them, and her contraindications are drawn apart
 * rather than stated (ADR Decision 190). Measured on the 3.7.1 standalone: Sarah Taylor's "does she have any drug
 * interactions?" listed ten allergy findings under ten interactions, eight of them "possible cross-reactivity". A
 * question about whether her medications are SAFE asks for both, and keeps both. Over the slice of
 * {@link ScreenSharedMechanismContextTest}, with an ibuprofen allergy recorded beside her ibuprofen order.
 */
public class ScreenInteractionsOnlyContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-screen-shared-mechanism.json";

	private static final String INTERACTIONS_ONLY = "Does she have any drug interactions?";

	private static final String SAFETY = "Are her medications safe to take together?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aQuestionAboutInteractionsOnlyStatesNoContraindication() throws IOException {
		assertTrue(contraindications(INTERACTIONS_ONLY).size() > 0, "precondition: her ibuprofen allergy raises one");

		String answer = answer(INTERACTIONS_ONLY);

		assertEquals("Also in the safety warnings beside this answer: 5 interactions, each a caution (5 Moderate). [7] [9] "
				+ "[8]", answer.split("\n")[2], "its five interactions, and no contraindication: " + answer);
		assertFalse(answer.contains("allergy"), "no contraindication line: " + answer);
		assertTrue(answer.endsWith("\n" + DrugReferenceInjector.BELOW_FLOOR_SCOPE),
			"and the scope says the answer covers interactions: " + answer);
	}

	@Test
	public void itsContraindicationsAreDrawnApartFromTheAnswer() throws IOException {
		for (SafetyWarning chip : contraindications(INTERACTIONS_ONLY)) {
			assertTrue(chip.isAboutAnotherOfHerMedications(), "drawn apart: " + chip.getDetail());
		}
	}

	@Test
	public void aQuestionAboutSafetyStillStatesThem() throws IOException {
		String answer = answer(SAFETY);

		// What is true of every contraindication line is said once, in the count (ADR Decision 191).
		// No count opens a screen's answer (ADR Decision 506): her allergy, a reason to change a medication, is its first
		// line, and says for itself that the drug is one she already takes.
		assertTrue(answer.matches("No Major interaction found\\. To review: 1 contraindication, 1 possible duplicate "
				+ "therapy, 5 cautions\\. See details for what was not checked\\.\n"
				+ "The patient has a recorded allergy to Ibuprofen\\. This finding is about a medication "
				+ "this patient is already taking\\. \\[\\d+\\]\n(?s).*"), "was: " + answer);
		for (SafetyWarning chip : contraindications(SAFETY)) {
			assertFalse(chip.isAboutAnotherOfHerMedications(), "what was asked: " + chip.getDetail());
		}
	}

	private static List<SafetyWarning> contraindications(String question) throws IOException {
		return DrugReferenceTestSupport.contraindications(DrugReferenceTestSupport.validator(service())
				.validate("", question, context()));
	}

	private static String answer(String question) throws IOException {
		return DrugReferenceTestSupport.injectorWithSafety(service())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(), question).getModuleAnswer();
	}

	private static DrugReferenceService service() throws IOException {
		return DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
	}

	private static PatientClinicalContext context() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"),
			DrugReferenceTestSupport.activeOrder("order-dexamethasone", "Dexamethasone 8mg"));
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			DrugReferenceTestSupport.set("ibuprofen"), null, orders);
	}
}
