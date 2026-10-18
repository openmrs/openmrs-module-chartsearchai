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
 * A proposal stating a dose raises the findings the proposal does (ADR Decision 269). On the 3.7.1 standalone George, on
 * acetaminophen, asked "Is 1 g of paracetamol safe for him?" and read "The records do not address the safety of 1 g of
 * paracetamol.": the dose kept the question from being read as a proposal, so nothing said that he already takes it —
 * which "Is paracetamol safe for him?" does say. The findings weigh no dose, and the shipped knowledge base publishes no
 * dose ceiling, so the module answers it as the proposal without the dose (ADR Decision 322, superseding this decision's
 * model call); the service says the dose was not checked. Over the shipped knowledge base.
 */
public class StatedDoseProposalTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalStatingADoseRaisesTheProposalsFindingsAndIsAnsweredAsTheProposal() {
		PatientChart without = ask("Can I give him amoxicillin?", "Amoxicillin 250mg");
		assertTrue(without.getText().contains("Amoxicillin is already in active order Amoxicillin 250mg"),
			"precondition: the proposal finds the drug in his order — " + without.getText());
		for (String question : Arrays.asList("Can I give him amoxicillin 500 mg three times a day?",
			"Can I give him amoxicillin 500mg tds?", "Can I give him 500 mg of amoxicillin?")) {
			PatientChart chart = ask(question, "Amoxicillin 250mg");
			assertEquals(DrugReferenceTestSupport.findingTexts(without), DrugReferenceTestSupport.findingTexts(chart), question);
			assertEquals(without.getModuleAnswer(), chart.getModuleAnswer(), question + " is answered as the proposal without its dose");
		}
	}

	@Test
	public void aDoseOfADrugHeAlreadyTakesStillSaysHeTakesIt() {
		PatientChart chart = ask("Is 1 g of paracetamol safe for him?", "Acetaminophen 500mg");
		assertTrue(chart.getText().contains("Acetaminophen is already in active order Acetaminophen 500mg"),
			"the duplicate is found: " + chart.getText());
	}

	private static PatientChart ask(String question, String order) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-1", order));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(9, 28.0,
				new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question);
	}
}
