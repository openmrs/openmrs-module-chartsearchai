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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * One drug asked about beside a group of his medications the module resolves — "Does fluconazole interact with his TB
 * drugs?" — is answered over that drug and that group, its chips what the answer states (ADR Decision 632). On the demo
 * replica each such question was answered under Decision 313's lead, "cannot tell which … are the "TB drugs" … so it
 * checked Fluconazole against all of them", with every finding of fluconazole against all his medications: for 12 of 12
 * patients the chips of "Does fluconazole interact with his medications?".
 */
public class DrugBesideAResolvedGroupTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aDrugBesideAGroupOfHisIsAnsweredOverThatGroup() {
		for (String question : Arrays.asList("Does fluconazole interact with his TB drugs?", "Is fluconazole safe with his TB "
				+ "drugs?", "Can I give him fluconazole with his TB treatment?")) {
			PatientChart chart = ask(question);
			String answer = chart.getModuleAnswer();

			assertNotNull(answer, question);
			assertEquals("Fluconazole and this patient's TB drugs (Rifampicin): what the interaction check relates between them:",
				answer.split("\n")[0], question);
			assertTrue(answer.contains("Rifampicin"), question + ": " + answer);
			assertFalse(answer.contains("Nevirapine") || answer.contains("Simvastatin"), question
					+ " — nothing outside his TB drugs: " + answer);
			assertTrue(!chart.getFindingsBearingOnTheQuestion().isEmpty() && chart.getFindingsBearingOnTheQuestion().stream()
					.allMatch(d -> d.contains("Rifampicin") || d.contains("Rifampin") || d.contains("rifampin")),
				question + " — its chips are what it states: " + chart.getFindingsBearingOnTheQuestion());
		}
	}

	@Test
	public void aDrugBesideAClassOfHisIsAnsweredOverThatClass() {
		PatientChart chart = ask("Is ketoconazole safe with his statins?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer);
		// and "statins" is no drug name the data does not recognise (ADR Decision 250's statement)
		assertEquals(null, chart.getUncheckedWordStatement(), "no statement that none of his orders is named \"statins\"");
		assertEquals("Ketoconazole and this patient's statins in ATC group C10AA (Simvastatin): what the interaction check "
				+ "relates between them:", answer.split("\n")[0]);
		assertFalse(answer.contains("Nevirapine") || answer.contains("Rifampicin"), answer);
	}

	@Test
	public void aDrugBesideAGroupNoneOfHisOrdersIsInIsSaidToBeAbsent() {
		PatientChart chart = ask("Is fluconazole safe with his blood thinners?");

		assertEquals("No blood thinner in ATC group B01A is among this patient's active medications the drug data identifies.",
			chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion().isEmpty(), String.valueOf(chart.getFindingsBearingOnTheQuestion()));
	}

	@Test
	public void aDrugBesideAGroupItIsNotRelatedToIsSaidSo() {
		PatientChart chart = ask("Does amoxicillin interact with his TB drugs?");

		assertEquals("The interaction check relates Amoxicillin to none of this patient's TB drugs (Rifampicin).",
			chart.getModuleAnswer().split("\n")[0]);
		assertTrue(chart.getFindingsBearingOnTheQuestion().isEmpty(), String.valueOf(chart.getFindingsBearingOnTheQuestion()));
	}

	/** His rifampicin is among his antibiotics (ADR Decision 634): the drug is answered over them, the Major finding asked
	 *  about stated. Under ADR Decision 632 alone "No antibiotic is among …" dropped it. */
	@Test
	public void aDrugBesideHisAntibioticsIsAnsweredOverHisTbDrugsAmongThem() {
		String answer = ask("Is warfarin safe with his antibiotics?").getModuleAnswer();

		assertNotNull(answer);
		assertTrue(answer.startsWith("Warfarin and this patient's antibiotics (Rifampicin): what the interaction check relates "
				+ "between them:"), answer);
		assertTrue(answer.contains("Warfarin interacts with active order Rifampicin") && answer.contains("— Major."), answer);
	}

	/** What else the question names beside the group keeps the drug's answer over all his medications, as does a group of
	 *  Decision 630's he has no member of (ADR Decision 632). */
	@Test
	public void aDrugBesideMoreThanAResolvedGroupKeepsItsAnswerOverAllHisMedications() {
		for (String question : Arrays.asList("Is fluconazole safe with his TB drugs and his other medications?",
			"Is fluconazole safe with his TB drugs and BP meds?",
			"Is fluconazole safe with his TB drugs and other drugs?")) {
			String answer = ask(question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.startsWith(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING)
					|| answer.contains("this patient's active medications"), question + ": " + answer);
			assertFalse(answer.startsWith("No antibiotic") || answer.contains("and this patient's TB drugs"), question + ": "
					+ answer);
		}
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Nevirapine", "Rifampicin", "Simvastatin")), null, null, null, orders), question);
	}
}
