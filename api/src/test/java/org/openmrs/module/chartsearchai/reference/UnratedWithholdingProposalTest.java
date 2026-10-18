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
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal whose only reason to withhold is an interaction folded with a shared class, no rating making it, is answered
 * with what the check found and no verdict (ADR Decision 312, widening Decision 311 past contraindications). On the 3.7.1
 * standalone "Can he take ketoconazole?" was answered by the model, for Karen Sanchez on metronidazole, "No —
 * Ketoconazole should not be given: it interacts with active order Metronidazole, a Minor problem" — a "No" over a Minor
 * rating. Over the shipped knowledge base.
 */
public class UnratedWithholdingProposalTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalWithheldOnlyByAFoldedClassIsAnsweredWithWhatTheCheckFound() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					null, null, orders), "Can he take ketoconazole?").getModuleAnswer();
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Ketoconazole:", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Ketoconazole interacts with active order Metronidazole — Minor."), "was: " + answer);
		assertTrue(!answer.startsWith("No"), "no verdict, was: " + answer);
	}

	/**
	 * Her own orders sharing a substance are about her medications, not the drug proposed, so they are stated under their
	 * own lead after what was found about it (ADR Decision 312). On the 3.7.1 standalone James Thompson, on two metronidazole
	 * orders, was told "What this module's drug-safety check found about Ketoconazole:" over "Metronidazole is in active
	 * orders Metronidazole (2 orders) — possible duplicate therapy".
	 */
	@Test
	public void herOwnOrdersSharingASubstanceAreStatedApartFromWhatWasFoundAboutTheDrug() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
			DrugReferenceTestSupport.activeOrder("order-mtz2", "Metronidazole", "Metronidazole"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					null, null, orders), "Can he take ketoconazole?").getModuleAnswer();
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(4, lines.length, "was: " + answer);
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Ketoconazole:", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Ketoconazole interacts with active order Metronidazole — Minor."), "was: " + answer);
		assertEquals(DrugReferenceInjector.ABOUT_HER_OTHER_MEDICATIONS_LEAD, lines[2], "was: " + answer);
		assertTrue(lines[3].startsWith("Metronidazole is in active orders Metronidazole (2 orders)"), "was: " + answer);
	}
}
