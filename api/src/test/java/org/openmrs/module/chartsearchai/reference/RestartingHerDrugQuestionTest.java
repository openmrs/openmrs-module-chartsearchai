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
 * Whether to restart her drug is answered as its proposal is (ADR Decision 342): restarting it is giving it again. On
 * the 3.7.1 standalone "Can I restart her simvastatin?" fitted no shape, and the model answered every patient — Karen
 * Sanchez, on metronidazole and no simvastatin, was told "Simvastatin can be given, with one caution", a clearance, and
 * Margaret King "No — Simvastatin should not be restarted: it has an Unknown severity interaction with Dexamethasone".
 * Over the shipped knowledge base.
 */
public class RestartingHerDrugQuestionTest extends BaseModuleContextSensitiveTest {

	private static final List<String> QUESTIONS = Arrays.asList("Can I restart her simvastatin?",
		"Should I restart his simvastatin?", "Can I resume her simvastatin?", "Is it safe to restart her simvastatin?");

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void ofADrugSheIsNotOnItIsSaidAndTheProposalAnswered() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg", "Clarithromycin"));
		String proposal = ask("Can I give her simvastatin?", orders);
		assertNotNull(proposal, "precondition: the module answers the proposal");
		for (String question : QUESTIONS) {
			assertEquals("Simvastatin" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n" + proposal, ask(question, orders),
				question);
		}
	}

	@Test
	public void ofHerOwnDrugItIsAnsweredAsItsProposal() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin 20mg", "Simvastatin"),
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg", "Clarithromycin"));
		String proposal = ask("Can I give her simvastatin?", orders);
		assertNotNull(proposal, "precondition: the module answers the proposal");
		for (String question : QUESTIONS) {
			assertEquals(proposal, ask(question, orders), question);
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
