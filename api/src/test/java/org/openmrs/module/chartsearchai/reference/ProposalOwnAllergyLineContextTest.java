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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal's "No" states a recorded allergy to the very drug proposed first, under the lead, and without "No severity
 * is rated for this finding" (ADR Decision 192). Measured on the 3.7.1 standalone: Sarah Taylor's "Is it safe to start
 * her on clarithromycin?" put her recorded clarithromycin allergy third, under two Major interactions, and was the one
 * line saying it had no severity. The lead is still decided by the interaction that licenses it.
 */
public class ProposalOwnAllergyLineContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-proposal-own-allergy.json";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void theAllergyToTheDrugProposedIsTheFirstLineUnderTheNo() throws IOException {
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(),
					"Is it safe to start her on clarithromycin?").getModuleAnswer();

		String[] lines = answer.split("\n");
		assertEquals(4, lines.length, "the lead and three findings: " + answer);
		assertEquals("No — this module's drug-safety check found a reason to withhold Clarithromycin.", lines[0]);
		assertTrue(lines[1].matches("The patient has a recorded allergy to Clarithromycin\\. \\[\\d+\\]"),
			"the allergy first, the finding alone: " + answer);
		assertTrue(lines[2].startsWith("Clarithromycin interacts with active order Methylprednisolone — Major."),
			"then the Major that licenses the No: " + answer);
		assertTrue(lines[3].startsWith("Clarithromycin interacts with active order Prednisone — Moderate."),
			"then the caution: " + answer);
	}

	private static PatientClinicalContext context() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-methylprednisolone", "Methylprednisolone 4mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			DrugReferenceTestSupport.set("clarithromycin"), null, orders);
	}
}
