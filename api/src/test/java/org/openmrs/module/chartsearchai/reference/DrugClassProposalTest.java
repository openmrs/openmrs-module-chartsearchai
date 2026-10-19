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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A drug class proposed is answered by the module with what it states of the class: that it was not resolved to a
 * substance, her orders in it and her recorded drug allergies in it — never a drug chosen, nor a verdict (ADR Decision
 * 347). On the 3.7.1 standalone "Can I give her a PPI?" went to the model for every patient: James Thompson, on
 * metronidazole, was told "No — Omeprazole / sodium bicarbonate should not be given", and Brian Lopez, on omeprazole,
 * "The records do not address the safety of giving a PPI"; "Can I give him an NSAID for his back pain?" told Sarah
 * Taylor, on three NSAIDs, "The records do not address the suitability of giving an NSAID". Over the shipped knowledge
 * base and drug groups.
 */
public class DrugClassProposalTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aClassAnAtcGroupIsStatesHerOrdersAndAllergiesInIt() {
		for (String question : Arrays.asList("Can I give her a PPI?", "Is it safe to start a proton pump inhibitor?")) {
			String answer = ask(question, DrugReferenceTestSupport.set("pantoprazole"),
				DrugReferenceTestSupport.activeOrder("order-ome", "Omeprazole 20mg", "Omeprazole"),
				DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			assertEquals(3, lines.length, question + " — was: " + answer);
			assertEquals(DrugReferenceInjector.drugClassStatement("proton pump inhibitor"), lines[0], question);
			assertEquals("Already among this patient's active medication orders in that class (ATC group A02BC): Omeprazole "
					+ "20mg.", lines[1], question);
			assertEquals("Recorded drug allergies in that class (ATC group A02BC): pantoprazole.", lines[2].replace("Pantoprazole",
				"pantoprazole"), question);
		}
	}

	@Test
	public void aClassAGroupThisModuleCarriesIsStatesHerOrdersAndAllergiesInIt() {
		String answer = ask("Can I give him an NSAID for his back pain?", DrugReferenceTestSupport.set("aspirin"),
			DrugReferenceTestSupport.activeOrder("order-ibu", "Ibuprofen 400mg", "Ibuprofen"),
			DrugReferenceTestSupport.activeOrder("order-dic", "Diclofenac 50mg", "Diclofenac"),
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(3, lines.length, "was: " + answer);
		assertEquals(DrugReferenceInjector.drugClassStatement("NSAID"), lines[0]);
		assertEquals("Already among this patient's active medication orders in the drug group this module carries as NSAID: "
				+ "Ibuprofen 400mg and Diclofenac 50mg.", lines[1]);
		assertEquals("recorded drug allergies in the drug group this module carries as nsaid: aspirin.", lines[2].toLowerCase());
	}

	@Test
	public void withNothingInTheClassItSaysSo() {
		assertEquals(DrugReferenceInjector.drugClassStatement("statin") + "\n"
				+ "None of this patient's active medication orders is in that class (ATC group C10AA).\n"
				+ "No drug allergies are recorded for this patient.",
			ask("Can I give her a statin?", null, DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole",
				"Metronidazole")));
		assertEquals(DrugReferenceInjector.drugClassStatement("NSAID") + "\n"
				+ "None of this patient's active medication orders is in the drug group this module carries as NSAID.\n"
				+ "None of this patient's recorded drug allergies is in the drug group this module carries as NSAID.",
			ask("Can I give her an NSAID?", DrugReferenceTestSupport.set("clarithromycin"),
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole")));
	}

	/** A dose stated beside the class is said not checked, as one beside a drug is (ADR Decision 322): "Is it safe to
	 *  give her a statin 20 mg?" was answered on the 3.7.1 standalone with no word of the dose. */
	@Test
	public void aDoseStatedBesideTheClassIsMarkedAsStated() {
		org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart = DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
					DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null,
						new LinkedHashSet<String>(Arrays.asList("Metronidazole")), null, null, null,
						Arrays.asList(DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"))),
					"Is it safe to give her a statin 20 mg?");
		assertNotNull(chart.getModuleAnswer(), "the module answers");
		org.junit.jupiter.api.Assertions.assertTrue(chart.statesADose(), "the dose is said not checked");
	}

	/** A question about the class that proposes none is not this answer's: whether she is on one is ADR Decision 298's. */
	@Test
	public void aQuestionProposingNoClassIsNotAnswered() {
		assertNull(ask("When was her last NSAID prescribed?", null,
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole")));
	}

	private static String ask(String question, Set<String> allergies, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, allergies, null,
				orders), question).getModuleAnswer();
	}
}
