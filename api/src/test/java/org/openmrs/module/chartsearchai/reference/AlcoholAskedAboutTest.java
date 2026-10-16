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
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Alcohol a clinician asks about is checked as the knowledge base's Ethanol (ADR Decision 268). On the 3.7.1 standalone
 * Karen, on metronidazole, asked "Can she drink alcohol while on metronidazole?" and read "The records do not address
 * alcohol consumption while on Metronidazole." beside a knowledge base rating ethanol with metronidazole Major; Donald, on
 * warfarin, asked "Does alcohol interact with his warfarin?" and read "No interaction check was run". Over the shipped
 * knowledge base.
 */
public class AlcoholAskedAboutTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void drinkingAlcoholBesideHerMetronidazoleIsCheckedAsEthanol() {
		for (String question : Arrays.asList("Can she drink alcohol while on metronidazole?", "Can she drink alcohol?",
			"Is alcohol safe with her metronidazole?", "Can she have alcohol with her metronidazole?")) {
			String answer = answer(ask(question, "Metronidazole 500mg"), question);
			assertTrue(answer.contains("No — this module's drug-safety check found a reason to withhold Ethanol."),
				question + " was: " + answer);
			assertTrue(Arrays.stream(answer.split("\n")).anyMatch(
				l -> l.matches("Ethanol interacts with active order Metronidazole — Major\\.( \\[\\d+\\])+")),
				question + " was: " + answer);
		}
	}

	@Test
	public void whetherAlcoholInteractsWithHisWarfarinIsCheckedAsEthanol() {
		String question = "Does alcohol interact with his warfarin?";
		String answer = answer(ask(question, "Warfarin 5mg"), question);
		assertFalse(answer.contains("No interaction check was run"), answer);
		assertTrue(Arrays.stream(answer.split("\n")).anyMatch(
			l -> l.matches("Ethanol interacts with active order Warfarin — Moderate\\.( \\[\\d+\\])+")), answer);
	}

	/** "Alcohol" naming another substance, or a condition or a product rather than drinking it, is never Ethanol. */
	@Test
	public void alcoholNamingSomethingElseIsNotEthanol() {
		for (String question : Arrays.asList("Can I give her polyvinyl alcohol eye drops?",
			"Can I give her diazepam for alcohol withdrawal?", "Can I use alcohol swabs on her?")) {
			PatientChart chart = ask(question, "Metronidazole 500mg");
			assertFalse(chart.getText().contains("Ethanol"), question + " put ethanol in play: " + chart.getText());
			assertTrue(chart.getModuleAnswer() == null || !chart.getModuleAnswer().contains("Ethanol"),
				question + " was: " + chart.getModuleAnswer());
		}
	}

	/** A data guard over the shipped knowledge base: each term names a row it carries, and is none of its names — a term
	 *  the knowledge base came to carry would be read there, in every prose and order name, and belongs out of the table. */
	@Test
	public void everyTermNamesARowOfTheShippedKnowledgeBaseAndIsNoneOfItsNames() {
		DrugReferenceService service = DrugReferenceTestSupport.shippedServiceWithGroups();
		for (java.util.Map.Entry<String, List<String>> term : QuestionSubstanceTerms.terms().entrySet()) {
			for (String row : term.getValue()) {
				assertTrue(service.getAll().stream().anyMatch(e -> e.getName().equals(row)),
					term + ": the knowledge base carries a row named " + row);
			}
			assertTrue(service.findImpliedByQuery("Can I give her " + term.getKey() + "?").isEmpty(),
				term + " is a name the knowledge base already carries");
		}
	}

	private static String answer(PatientChart chart, String question) {
		assertNotNull(chart.getModuleAnswer(), question + ": the module answers — " + chart.getText());
		return chart.getModuleAnswer();
	}

	private static PatientChart ask(String question, String order) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-1", order));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null,
				new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question);
	}
}
