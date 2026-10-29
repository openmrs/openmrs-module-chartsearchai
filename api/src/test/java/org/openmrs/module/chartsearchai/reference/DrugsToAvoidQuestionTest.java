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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Which drugs to avoid in her is answered with her recorded drug allergies and the screen of her own medications, under a
 * lead saying the check lists no drug to avoid (ADR Decision 324). On the 3.7.1 standalone "What drugs should I avoid in
 * this patient?" went to the model for every patient: Rhoda, on seven antiretrovirals the screen relates thirteen times,
 * was told "The records do not address which drugs should be avoided", and James Martinez "Yes — the patient has an
 * allergy to Aspirin, which is a Moderate severity drug allergen causing Anaemia". Over the shipped knowledge base.
 */
public class DrugsToAvoidQuestionTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin Co 20mg", "Simvastatin"),
		DrugReferenceTestSupport.activeOrder("order-amlo", "Amlodipine 5mg", "Amlodipine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void drugsToAvoidAreAnsweredWithHerAllergiesAndTheScreen() {
		for (Set<String> allergies : Arrays.asList(DrugReferenceTestSupport.set("aspirin"), null)) {
			String screen = ask("Are her medications safe to take together?", allergies);
			assertNotNull(screen, "precondition: the module answers the screen");
			for (String question : Arrays.asList("What drugs should I avoid in this patient?",
					"What should I avoid prescribing for her?", "Which drugs should I avoid with his current medications?")) {
				String answer = ask(question, allergies);
				assertNotNull(answer, question + " — the module answers");
				// Her allergies as the answer to which allergies she has states them, then that the check lists no other
				// drug to avoid, then the screen (ADR Decisions 496, 509); the one sentence a client shows of it is apart
				// from these words (ADR Decision 519).
				assertEquals((allergies == null ? "No drug allergies are recorded for this patient."
						: "Recorded drug allergies: aspirin.") + " This check does not list other drugs to avoid."
						+ "\n" + screen.replaceAll("\\[\\d+\\]", "[n]"), answer.replaceAll("\\[\\d+\\]", "[n]"), question);
			}
		}
	}

	private static String ask(String question, Set<String> allergies) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null, allergies, null,
				ORDERS), question).getModuleAnswer();
	}
}
