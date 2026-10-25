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
 * Something she would eat or drink that the drug data does not carry takes the chart answer, the unchecked statement after
 * it, and is offered no drug's name (ADR Decision 390): on the 3.7.1 standalone "Can she drink milk?" was answered by the
 * module alone, "No interaction check was run: "milk" is not a drug name the interaction data recognises. Did you mean
 * Milk thistle?". Over the shipped knowledge base.
 */
public class SomethingToEatOrDrinkTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-cq", "Chloroquine Co 250mg", "Chloroquine"));

	private static final List<PatientClinicalContext.ActiveDrugOrder> NSAIDS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-ibu", "Ibuprofen 400mg", "Ibuprofen"),
		DrugReferenceTestSupport.activeOrder("order-dic", "Diclofenac 50mg", "Diclofenac"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void somethingToDrinkIsOfferedNoDrugsName() {
		PatientChart chart = ask("Can she drink milk?");
		assertNull(chart.getModuleAnswer(), "the chart answer: " + chart.getModuleAnswer());
		assertEquals("No interaction check was run: \"milk\" is not a drug name the interaction data recognises.",
			chart.getUncheckedWordStatement());
	}

	/**
	 * A word whose only near names in the data are things it classifies as no medicine — Milk thistle, Green tea leaf, the
	 * Peanut allergen extract — takes the chart answer, the statement after it with no "Did you mean" (ADR Decision 435).
	 * On the 3.7.1 standalone "Can I give her milk?" was answered by the module alone, "No interaction check was run:
	 * "milk" is not a drug name the interaction data recognises. Did you mean Milk thistle?", for every patient.
	 */
	@Test
	public void somethingToHaveWhoseNearNamesAreNoMedicineTakesTheChartAnswer() {
		String[][] cases = { { "Can I give her milk?", "milk" }, { "Can she have peanuts?", "peanuts" },
			{ "Can she have green tea?", "green tea" } };
		for (String[] c : cases) {
			PatientChart chart = ask(c[0]);
			assertNull(chart.getModuleAnswer(), c[0] + " — the chart answer: " + chart.getModuleAnswer());
			assertEquals("No interaction check was run: \"" + c[1] + "\" is not a drug name the interaction data recognises.",
				chart.getUncheckedWordStatement(), c[0]);
		}
		// A misspelt medicine keeps the module's answer and its suggestion.
		assertTrue(ask("Can I give her rifampcin?").getModuleAnswer().contains("Did you mean Rifampicin"));
	}

	/**
	 * A food the data carries only as its allergen extract, named by the food's word, raises nothing of the extract — as a
	 * finding before the answer or a chip after it (ADR Decision 437). On the 3.7.1 standalone "Can she have peanut
	 * butter?" was answered for a patient on diclofenac and ibuprofen "No — peanut butter should not be consumed: it
	 * interacts with active order Diclofenac and Ibuprofen, a Major problem", the rating of peanut allergen immunotherapy.
	 */
	@Test
	public void aFoodTheDataCarriesOnlyAsAnAllergenExtractRaisesNothingOfTheExtract() {
		for (String question : Arrays.asList("Can she have peanut butter?", "Can she have peanut?", "Is peanut ok for her?")) {
			PatientChart chart = ask(question, NSAIDS);
			assertTrue(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				question + " — " + DrugReferenceTestSupport.injectedFindings(chart));
			assertNull(chart.getModuleAnswer(), question + " — the chart answer: " + chart.getModuleAnswer());
		}
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : NSAIDS) {
			names.addAll(order.getNames());
		}
		List<SafetyWarning> chips = DrugReferenceTestSupport.chipsOverAnswerFromDataset(
			DrugReferenceTestSupport.shippedServiceWithGroups(), "No — peanut butter should not be consumed.",
			"Can she have peanut butter?", names, null, NSAIDS);
		assertTrue(chips.isEmpty(), "no chip of the extract after the answer: " + chips);
		// Named as the product, it is the extract, and is checked.
		assertTrue(ask("Is Palforzia safe with her medications?", NSAIDS).getModuleAnswer().contains("Peanut"));
	}

	private static PatientChart ask(String question) {
		return ask(question, ORDERS);
	}

	private static PatientChart ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), question);
	}
}
