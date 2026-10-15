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
 * A combination a question names by one name is one drug asked about (ADR Decision 272). Since the knowledge base names
 * co-trimoxazole under sulfamethoxazole and trimethoprim (ADR Decision 266), "Any interaction between her lopinavir and
 * cotrimoxazole?" put three substances in play, which no two-drug answer takes: on the 3.7.1 standalone Ruth, on both, read
 * the model's "Lopinavir interacts with active order sulfamethoxazole …" with no severity, and Amina, on co-trimoxazole
 * alone, asked "Does her cotrimoxazole interact with her lopinavir?" and was not told she takes no lopinavir. Over the
 * shipped knowledge base.
 */
public class CombinationNamedAsOneDrugTest extends BaseModuleContextSensitiveTest {

	private static final PatientClinicalContext.ActiveDrugOrder LOPINAVIR = DrugReferenceTestSupport.activeOrder("order-lpv",
		"Lopinavir / ritonavir 200mg/50mg", "Lopinavir / ritonavir");

	private static final PatientClinicalContext.ActiveDrugOrder COTRIMOXAZOLE = DrugReferenceTestSupport.activeOrder(
		"order-ctx", "Cotrimoxazole 960mg");

	private static final PatientClinicalContext.ActiveDrugOrder WARFARIN = DrugReferenceTestSupport.activeOrder("order-warf",
		"Warfarin 5mg");

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoOfHerDrugsOneNamedByACombinationsNameAreAnsweredAsTwo() {
		String answer = ask("Any interaction between her lopinavir and cotrimoxazole?", LOPINAVIR, COTRIMOXAZOLE);
		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].matches("Lopinavir \\(Lopinavir / ritonavir 200mg/50mg( \\[\\d+\\])?\\) and "
				+ "Cotrimoxazole \\(Cotrimoxazole 960mg( \\[\\d+\\])?\\)"
				+ java.util.regex.Pattern.quote(DrugReferenceInjector.TWO_OF_HER_OWN_LEAD)), "was: " + answer);
		assertTrue(answer.contains(" — Minor."), "the pair's rating is stated: " + answer);
		assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "never a verdict: " + answer);
	}

	@Test
	public void aDrugNamedAsHersThatSheDoesNotTakeIsSaidNotToBeHersBesideACombinationSheDoes() {
		String answer = ask("Does her cotrimoxazole interact with her lopinavir?", COTRIMOXAZOLE);
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Lopinavir" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n"), "was: " + answer);
		assertTrue(answer.contains("\nCotrimoxazole" + DrugReferenceInjector.OWN_MEDICATION_LEAD + ".\nYes — "), "was: " + answer);
		assertTrue(answer.contains(" — Minor."), "the pair's rating is stated: " + answer);
	}

	@Test
	public void aCombinationProposedBesideHerDrugIsAnsweredAsOneProposal() {
		String answer = ask("Is co-trimoxazole safe with her warfarin?", WARFARIN);
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Warfarin" + DrugReferenceInjector.OWN_MEDICATION_LEAD + ".\nNo — "), "was: " + answer);
		assertTrue(answer.contains("\nSulfamethoxazole interacts with active order Warfarin — Major."), "was: " + answer);
		assertTrue(answer.contains("\nTrimethoprim interacts with active order Warfarin — Moderate."), "was: " + answer);
	}

	@Test
	public void theMechanismBetweenHerDrugAndACombinationIsTheFindingsRelatingThem() {
		String answer = ask("What is the mechanism of the interaction between warfarin and co-trimoxazole?", WARFARIN);
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Warfarin" + DrugReferenceInjector.OWN_MEDICATION_LEAD
				+ ".\nWhat the interaction check relates between Warfarin and Co-trimoxazole:\n"), "was: " + answer);
		assertTrue(answer.contains("\nSulfamethoxazole interacts with active order Warfarin — Major. "), "was: " + answer);
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		List<PatientClinicalContext.ActiveDrugOrder> list = Arrays.asList(orders);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : list) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(38, null, names, null, null, null, list),
			question).getModuleAnswer();
	}
}
