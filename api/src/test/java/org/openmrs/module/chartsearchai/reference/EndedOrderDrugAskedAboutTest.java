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

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether a drug her chart holds only as an ended order interacts with her medications, or which of them it interacts
 * with, is answered by the module from its findings, as it is of a drug she never took (ADR Decision 321). On the 3.7.1
 * standalone Rita, whose rifampicin order ended on 2026-09-23, asked "Does rifampicin interact with her medications?" and
 * the model answered "No — Rifampicin should not be proposed again", a "No" to whether it interacts: the module refused
 * every finding carrying the ended-order clause. Over a verbatim DDInter excerpt (simvastatin × clarithromycin, Major).
 */
public class EndedOrderDrugAskedAboutTest extends BaseModuleContextSensitiveTest {

	private static final Date STOPPED = new Date(1767225600000L);

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void whetherADrugHeldOnlyAsAnEndedOrderInteractsIsAnsweredFromTheFindings() throws IOException {
		String interacts = ask("Does clarithromycin interact with her medications?");
		assertNotNull(interacts, "the module answers");
		String[] lines = interacts.split("\n");
		assertEquals("Yes — Clarithromycin interacts with this patient's active medications. This module's drug-safety "
				+ "check found a reason to withhold it.", lines[0], "was: " + interacts);
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Simvastatin — Major."), "was: " + interacts);

		String which = ask("Which of her medications interact with clarithromycin?");
		assertNotNull(which, "the module answers which");
		assertTrue(!which.startsWith("Yes") && !which.startsWith("No"), "neither Yes nor No: " + which);
		assertTrue(which.contains("\nClarithromycin interacts with active order Simvastatin — Major."), "was: " + which);
	}

	private static String ask(String question) throws IOException {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin 20mg", "Simvastatin"));
		return DrugReferenceTestSupport.injectorWithSafety(
			DrugReferenceTestSupport.ddiFixtureService("chartsearchai-test/ddi-alias-drug-names.json")).injectRecords(
				DrugReferenceTestSupport.chartOf(DrugReferenceTestSupport.drugOrderRecord(1, "Clarithromycin 500mg",
					Boolean.FALSE, STOPPED)),
				DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin"), null, null, null, orders),
				question).getModuleAnswer();
	}
}
