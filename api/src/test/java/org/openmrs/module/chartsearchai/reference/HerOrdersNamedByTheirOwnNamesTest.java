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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question naming his orders by their own names, which the reference data does not carry, is about those orders (ADR
 * Decision 265). On the 3.7.1 standalone Michael Turner, on "Zolvimix" — resolved through its ATC codes to simvastatin and
 * clarithromycin — and "Klarizom" — clarithromycin — asked "Is Zolvimix safe with Klarizom?" and read "The records do not
 * address the safety of Zolvimix with Klarizom.": no check ran, though a screen of his orders finds a Major interaction
 * between them. Over the shipped knowledge base.
 */
public class HerOrdersNamedByTheirOwnNamesTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoOfHisOrdersNamedByTheirOwnNamesAreAnsweredAsHisOwnDrugsTogether() {
		for (String question : Arrays.asList("Is Zolvimix safe with Klarizom?", "Does Zolvimix interact with Klarizom?",
			"Can he take Zolvimix and Klarizom together?")) {
			String answer = ask(question).getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Zolvimix and Klarizom are both among this patient's active "
					+ "medications. What the interaction check relates between them:\n"), question + " was: " + answer);
			assertTrue(answer.contains("\nSimvastatin interacts with active order Clarithromycin — Major."),
				question + " states the pair: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "never a verdict: " + answer);
		}
	}

	/** One of his orders named by its own name is never said to be a name the data does not recognise: it was checked
	 *  through the order. */
	@Test
	public void oneOfHisOrdersNamedByItsOwnNameIsNeverSaidToBeUnrecognised() {
		for (String question : Arrays.asList("Can I give him Zolvimix?", "Does Zolvimix interact with his medications?")) {
			String answer = ask(question).getModuleAnswer();
			assertTrue(answer == null || !answer.contains("No interaction check was run"), question + " was: " + answer);
		}
	}

	/**
	 * One of his orders named alone by its own name is answered as his medication, with what the screen relates it to (ADR
	 * Decision 273): on the 3.7.1 standalone Michael Turner asked "Can I give him Zolvimix?" and read "The records do not
	 * address the safety of Zolvimix." — of a drug he takes, which the check relates to his Klarizom by a Major interaction.
	 */
	@Test
	public void oneOfHisOrdersNamedAloneIsAnsweredAsHisMedication() {
		String proposal = ask("Can I give him Zolvimix?").getModuleAnswer();
		assertTrue(proposal != null && proposal.startsWith("Zolvimix" + DrugReferenceInjector.OWN_MEDICATION_LEAD + " (Zolvimix"),
			"was: " + proposal);
		assertTrue(proposal.contains("adding it would duplicate that order."), "was: " + proposal);
		assertTrue(proposal.contains("\nSimvastatin interacts with active order Clarithromycin — Major."), "was: " + proposal);
		assertTrue(!proposal.contains("No —") && !proposal.contains("Yes —"), "was: " + proposal);

		String interacts = ask("Does Zolvimix interact with his medications?").getModuleAnswer();
		assertTrue(interacts != null && interacts.startsWith("Yes — Zolvimix interacts with this patient's other active "
				+ "medications.\n"), "was: " + interacts);
		assertTrue(interacts.contains("\nSimvastatin interacts with active order Clarithromycin — Major."), "was: " + interacts);
	}

	/**
	 * Whether one of his orders, named by its own name, is safe, or what interacts with it, is answered as his medication and
	 * what the check relates it to — never "Yes —", which beside "Is his Klarizom safe?" reads as a clearance (ADR Decision
	 * 378). On the 3.7.1 standalone Michael Turner was told "Yes — Klarizom interacts with this patient's other active
	 * medications."
	 */
	@Test
	public void whetherOneOfHisOrdersNamedByItsOwnNameIsSafeIsAnsweredAsHisMedication() {
		for (String question : Arrays.asList("Is his Klarizom safe?", "What interacts with his Klarizom?")) {
			String answer = ask(question).getModuleAnswer();
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.replaceAll("\\[\\d+\\]", "[n]").split("\n");
			assertEquals("Klarizom is one of this patient's active medications (Klarizom [n]). What the interaction check "
					+ "relates it to:", lines[0], question + " — was: " + answer);
			assertTrue(answer.contains("\nSimvastatin interacts with active order Clarithromycin — Major."),
				question + " — was: " + answer);
			assertTrue(!answer.contains("Yes —") && !answer.contains("No —"), question + " — was: " + answer);
		}
	}

	private static PatientChart ask(String question) {
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"), DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null,
				null, Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix", DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01", "J01FA09")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom", DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			question);
	}
}
