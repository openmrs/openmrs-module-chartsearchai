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
 * One of her orders named by its own name, proposed or asked whether it interacts, where no finding relates it to another
 * of her orders, is answered as her medication — adding it would duplicate the order, what the check could check it
 * against, and any allergy finding about it — and never "No" (ADR Decision 336). On the 3.7.1 standalone Kevin Brown, on
 * Clarithromycine Co 500mg alone and recorded allergic to it, was told by the model for "Can I give him clarithromycine?"
 * "No — Clarithromycine should not be given due to a drug allergen", and for "Does clarithromycine interact with his
 * medications?" "No — Clarithromycin should not be given": a drug he already takes, refused as though proposed.
 */
public class HerOrderNamedByItsBrandAloneTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOnlyOrderNamedByItsBrandIsAnsweredAsHers() {
		String proposed = ask("Can I give him clarithromycine?");
		assertNotNull(proposed, "the module answers the proposal");
		String[] lines = proposed.split("\n");
		assertEquals("Clarithromycine is one of this patient's active medications (Clarithromycine Co 500mg [n]), so adding it "
				+ "would duplicate that order, and this patient has no other active medication to check it against.", lines[0],
			proposed);

		String interacts = ask("Does clarithromycine interact with his medications?");
		assertNotNull(interacts, "the module answers whether it interacts");
		lines = interacts.split("\n");
		assertEquals("Clarithromycine is one of this patient's active medications (Clarithromycine Co 500mg [n]), and this "
				+ "patient has no other active medication to check it against.", lines[0], interacts);
		assertTrue(lines[1].startsWith("The patient has a recorded allergy to Clarithromycin"), interacts);
		for (String answer : Arrays.asList(proposed, interacts)) {
			assertTrue(!answer.startsWith("No") && !answer.startsWith("Yes"), answer);
		}
	}

	/**
	 * Whether his drug named by its brand is safe, or what interacts with it, is asked of his order as whether it interacts
	 * is (ADR Decision 374): on the 3.7.1 standalone Kevin Brown, on Clarithromycine Co 500mg, was told by the model "The
	 * records do not address the safety of Clarithromycine".
	 */
	@Test
	public void whetherHisBrandOrderIsSafeIsAskedAsWhetherItInteracts() {
		String interacts = ask("Does clarithromycine interact with his medications?");
		assertNotNull(interacts, "precondition");
		for (String question : Arrays.asList("Is his clarithromycine safe?", "What interacts with his clarithromycine?")) {
			assertEquals(interacts, ask(question), question);
		}
	}

	/**
	 * Of a patient on no order of it, a brand the data does not recognise, named as his, is said to name none of his
	 * orders and not to be checked (ADR Decision 374): the model told every patient but Kevin "The records do not address
	 * clarithromycine safety".
	 */
	@Test
	public void aBrandNamedAsHisThatNoOrderOfHisCarriesIsSaidSo() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(DrugReferenceTestSupport.activeOrder("order-m",
			"Metronidazole", "Metronidazole"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		for (String question : Arrays.asList("Is his klarizom safe?", "What interacts with his klarizom?")) {
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
						null, null, orders), question);
			// No near name in the data, so it may be no drug at all: the chart answer, this after it (ADR Decision 384).
			assertNull(chart.getModuleAnswer(), question + " — the chart answer: " + chart.getModuleAnswer());
			String answer = chart.getUncheckedWordStatement();
			assertNotNull(answer, question + " — stated after the chart answer");
			String[] lines = answer.split("\n");
			assertEquals("None of this patient's active orders is named \"klarizom\".", lines[0], question + " — was: " + answer);
			assertTrue(lines[1].startsWith("No interaction check was run: \"klarizom\" is not a drug name the interaction data "
					+ "recognises."), question + " — was: " + answer);
		}
	}

	private static String ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(DrugReferenceTestSupport.activeOrder("order-k",
			"Clarithromycine Co 500mg", new LinkedHashSet<String>(Arrays.asList("clarithromycine co 500mg", "clarithromycine")),
			new LinkedHashSet<String>(Arrays.asList("J01FA09"))));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					DrugReferenceTestSupport.set("clarithromycin"), null, orders), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
