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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A drug a clinician names by the name the knowledge base files only under its salt or its prodrugs is checked as those
 * (ADR Decision 284): "Can I give her lithium?" and "Can I give her tenofovir?" read "No interaction check was run: … Did you
 * mean …?" — of the one lithium the data carries, Lithium carbonate, and of the two forms of tenofovir it carries apart,
 * Tenofovir disoproxil and Tenofovir alafenamide. Over the shipped knowledge base.
 */
public class MoietyAskedAboutTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void lithiumIsCheckedAsTheOneLithiumTheDataCarries() {
		String answer = answer("Can I give her lithium?", "Ibuprofen 400mg");
		assertTrue(answer.startsWith("No — this module's drug-safety check found a reason to withhold Lithium carbonate.\n"),
			"was: " + answer);
		assertTrue(answer.contains("\nLithium carbonate interacts with active order Ibuprofen — Major."), "was: " + answer);
	}

	/** Both forms are checked, and a finding the data states alike of the two is stated once (ADR Decision 291): the answer
	 *  printed "Tenofovir disoproxil interacts with active order Rifampicin (rifampin) — Major." and the same line of
	 *  Tenofovir alafenamide, under one mechanism. */
	@Test
	public void tenofovirProposedIsCheckedAsBothFormsTheDataCarriesApart() {
		String answer = answer("Can I give her tenofovir?", "Rifampicin 300mg");
		assertTrue(answer.startsWith("No — this module's drug-safety check found a reason to withhold Tenofovir.\n"),
			"was: " + answer);
		assertTrue(answer.contains("\nTenofovir disoproxil interacts with active order Rifampicin (rifampin) — Major."
				+ DrugReferenceInjector.SAME_OF_FORMS_OPENING + "Tenofovir alafenamide. ["), "was: " + answer);
		assertFalse(answer.contains("\nTenofovir alafenamide interacts with active order"), "stated once: " + answer);
	}

	/** And where the two forms are what the question pairs with a drug she is not on either (ADR Decision 291). */
	@Test
	public void twoDrugsOneOfThemTenofovirStateEachFindingOfBothFormsOnce() {
		String answer = answer("Does tenofovir interact with rifampicin?", "Didanosine 400mg");
		assertTrue(answer.contains("\nRifampicin (rifampin) interacts with Tenofovir disoproxil, also named in the question — "
				+ "Major." + DrugReferenceInjector.SAME_OF_FORMS_OPENING + "Tenofovir alafenamide. ["), "was: " + answer);
		assertTrue(answer.contains("\nTenofovir disoproxil interacts with active order Didanosine — Major."), "was: " + answer);
		assertFalse(answer.contains("\nTenofovir alafenamide interacts with active order Didanosine"), "stated once: " + answer);
	}

	/** Her tenofovir is the form she is on. */
	@Test
	public void herTenofovirIsTheFormSheIsOn() {
		String answer = answer("Does her tenofovir interact with didanosine?", "Tenofovir disoproxil fumarate 300mg");
		assertTrue(answer.contains("Tenofovir disoproxil"), "was: " + answer);
		assertFalse(answer.contains("alafenamide"), "she is on the disoproxil: " + answer);
	}

	/** A form the question names in full is that form alone. */
	@Test
	public void aFormNamedInFullIsThatFormAlone() {
		String answer = answer("Can I give her tenofovir alafenamide?", "Rifampicin 300mg");
		assertTrue(answer.contains("\nTenofovir alafenamide interacts with active order Rifampicin (rifampin) — Major."), "was: " + answer);
		assertFalse(answer.contains("disoproxil"), "was: " + answer);
	}

	private static String answer(String question, String order) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-1", order));
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null,
					new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question)
				.getModuleAnswer();
		assertNotNull(answer, question + ": the module answers");
		assertFalse(answer.contains("No interaction check was run"), question + " was: " + answer);
		return answer;
	}
}
