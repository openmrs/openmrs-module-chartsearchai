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
 * A drug the question names as his that he is not on is what the clinician believes he takes, never a drug proposed: it is
 * given no verdict of its own against his other medications (ADR Decision 655). Through the service, "Is rifampicin safe
 * with his nevirapine?" of a patient on rifampicin, efavirenz and warfarin and no nevirapine was answered "No — … withhold
 * Nevirapine" with nevirapine against his warfarin and efavirenz, where "Does rifampicin interact with his nevirapine?" was
 * answered with the pair alone.
 */
public class DrugNamedAsHisHeIsNotOnTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void hisDrugAndOneNamedAsHisHeIsNotOnAreAnsweredAsWhetherTheyInteract() {
		PatientChart canonical = ask("Does rifampicin interact with his nevirapine?");
		assertNotNull(canonical.getModuleAnswer(), "precondition: the module answers the interact wording");
		assertFalse(canonical.getModuleAnswer().contains("Warfarin"), canonical.getModuleAnswer());
		for (String question : new String[] { "Is rifampicin safe with his nevirapine?",
				"Can he take rifampicin with his nevirapine?", "Is his rifampicin safe with his nevirapine?",
				"Is it safe to give rifampicin with his nevirapine?" }) {
			PatientChart chart = ask(question);
			assertEquals(canonical.getModuleAnswer(), chart.getModuleAnswer(), question);
			assertEquals(canonical.getFindingsBearingOnTheQuestion(), chart.getFindingsBearingOnTheQuestion(), question);
		}
	}

	/** Whether one changes the other keeps Decision 249's answer, with neither a "Yes" nor a "No": the data relates the pair,
	 *  never the effect asked about. */
	@Test
	public void whetherHisDrugChangesOneNamedAsHisKeepsItsOwnLead() {
		String answer = ask("Does rifampicin reduce his nevirapine levels?").getModuleAnswer();
		assertNotNull(answer);
		assertFalse(answer.contains("Yes —"), answer);
		assertFalse(answer.contains("No —"), answer);
		assertFalse(answer.contains("Warfarin"), answer);
	}

	@Test
	public void aDrugProposedBesideHisKeepsItsProposal() {
		for (String question : new String[] { "Can I give him nevirapine with his rifampicin?",
				"Is nevirapine safe with his rifampicin?", "Can I add nevirapine to his rifampicin?" }) {
			String answer = ask(question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.contains("Nevirapine interacts with active order Warfarin"), question + ": " + answer);
		}
	}

	/** A possessive the punctuation parts from the drug names nothing as hers: "for her, nevirapine or fluconazole". */
	@Test
	public void aPossessiveBeforeACommaNamesNoDrugAsHers() {
		String answer = ask("Which is safer for her, nevirapine or fluconazole?").getModuleAnswer();
		assertNotNull(answer);
		assertTrue(answer.contains("For Nevirapine:") && answer.contains("For Fluconazole:"), answer);
		assertFalse(answer.contains("is not one of this patient's active medications.\n"), answer);
	}

	@Test
	public void oneNamedAsHisBesideAWordTheDataDoesNotKnowIsGivenNoVerdict() {
		PatientChart chart = ask("Is RHZE safe with his nevirapine?");
		assertEquals("Nevirapine is not one of this patient's active medications.\nNo interaction check was run for \"RHZE\" "
				+ "and Nevirapine: \"RHZE\" is not a drug name the interaction data recognises.", chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
			"no chip: " + chart.getFindingsBearingOnTheQuestion());
	}

	@Test
	public void oneNamedAsHisBesideAnotherProposedIsGivenNoVerdictOfItsOwn() {
		PatientChart chart = ask("Is fluconazole safe with his nevirapine?");
		String answer = chart.getModuleAnswer();
		assertNotNull(answer);
		assertTrue(answer.contains("Nevirapine interacts with Fluconazole"), "the pair is stated: " + answer);
		assertTrue(answer.contains("For Fluconazole:"), "the drug proposed keeps its answer: " + answer);
		assertFalse(answer.contains("For Nevirapine:"), answer);
		assertFalse(answer.contains("Nevirapine interacts with active order"), answer);
		assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().stream()
				.noneMatch(d -> d.startsWith("Nevirapine interacts with active order")),
			"no chip of nevirapine against his orders: " + chart.getFindingsBearingOnTheQuestion());
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz"),
			DrugReferenceTestSupport.activeOrder("order-war", "Warfarin"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Rifampicin", "Efavirenz", "Warfarin")), null, null, null, orders),
			question);
	}
}
