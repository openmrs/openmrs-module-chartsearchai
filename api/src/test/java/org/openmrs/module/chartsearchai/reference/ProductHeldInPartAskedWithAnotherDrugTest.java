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
 * Whether a drug she is not on interacts with a combination she holds only in part is answered as it is for a patient
 * holding neither, and says which of the combination's substances she takes, in which order (ADR Decision 376). On the
 * 3.7.1 standalone "Does her efavirenz interact with her isoniazid / pyrazinamide / rifampin?" went to the model for
 * Michelle Lewis and Steven White, on isoniazid / rifapentine, and Dora DdiControl, on rifampicin; Steven was told every
 * finding was "a caution to note, not a reason to withhold it". Over the shipped knowledge base.
 */
public class ProductHeldInPartAskedWithAnotherDrugTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Does her efavirenz interact with her isoniazid / pyrazinamide / rifampin?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herIsoniazidRifapentineIsNamedAsTheIsoniazidOfTheCombinationAskedAbout() {
		String answer = ask(Arrays.asList(DrugReferenceTestSupport.activeOrder("order-3hp", "Isoniazid / Rifapentine",
			"Isoniazid", "Rifapentine")));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Yes — Efavirenz and Isoniazid / pyrazinamide / rifampin interact; neither is one of this patient's "
				+ "active medications.", lines[0], answer);
		assertEquals("Isoniazid / pyrazinamide / rifampin carries Isoniazid, which this patient takes in active order "
				+ "Isoniazid / Rifapentine [n].", lines[1], answer);
		assertTrue(answer.contains("\nAgainst this patient's active medications:\n")
				&& answer.contains("\nEfavirenz interacts with active order Isoniazid — Moderate."),
			"what relates efavirenz to her order: " + answer);
		assertTrue(answer.contains("\nPyrazinamide interacts with Efavirenz, also named in the question — Moderate."),
			"what relates the two asked about: " + answer);
		assertTrue(!answer.contains("can be given") && !answer.contains("not a reason to withhold"), answer);
	}

	@Test
	public void herRifampicinIsNamedAsTheRifampicinOfTheCombinationAskedAbout() {
		String answer = ask(Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 300mg", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Yes — Efavirenz and Isoniazid / pyrazinamide / rifampin interact; neither is one of this patient's "
				+ "active medications.", lines[0], answer);
		assertEquals("Isoniazid / pyrazinamide / rifampin carries Rifampicin (rifampin), which this patient takes in active "
				+ "order Rifampicin 300mg [n].", lines[1], answer);
		assertTrue(answer.contains("\nEfavirenz interacts with active order Rifampicin (rifampin) — Moderate."),
			"what relates efavirenz to her rifampicin: " + answer);
	}

	/** Proposed together, the combination she partly takes is given no verdict: it would duplicate what she takes, which
	 *  no finding states — what the check found about it is listed instead. Michelle Lewis was told by the model "The
	 *  records do not address the safety of Efavirenz with isoniazid / pyrazinamide / rifampin". */
	@Test
	public void proposedTogetherTheCombinationSheTakesInPartIsGivenNoVerdict() {
		String answer = ask(Arrays.asList(DrugReferenceTestSupport.activeOrder("order-3hp", "Isoniazid / Rifapentine",
			"Isoniazid", "Rifapentine")), "Is efavirenz safe with isoniazid / pyrazinamide / rifampin?");
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Efavirenz and Isoniazid / pyrazinamide / rifampin, asked about together; neither is one of this "
				+ "patient's active medications.", lines[0], answer);
		assertEquals("Isoniazid / pyrazinamide / rifampin carries Isoniazid, which this patient takes in active order "
				+ "Isoniazid / Rifapentine [n].", lines[1], answer);
		int its = answer.indexOf("\nFor Isoniazid / pyrazinamide / rifampin:\n");
		assertTrue(its > 0, answer);
		String tail = answer.substring(its);
		assertTrue(tail.contains("\n" + DrugReferenceInjector.FOUND_LEAD_OPENING + "Isoniazid / pyrazinamide / rifampin:\n"),
			"what the check found, listed: " + answer);
		assertTrue(tail.contains("\nRifampicin (rifampin) interacts with active order Isoniazid — Minor."), answer);
		assertTrue(!tail.contains("can be given") && !tail.contains("should not be given"),
			"no verdict on a combination that would duplicate what she takes: " + answer);
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders) {
		return ask(orders, QUESTION);
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders, String question) {
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
