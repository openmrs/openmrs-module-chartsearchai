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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A combination proposed by one name raising no finding is answered as a single drug is (ADR Decision 304): what the
 * check established, both substances' records cited, the product named as asked. On the 3.7.1 standalone "Can I
 * prescribe trimethoprim-sulfamethoxazole for her UTI?" went to the model for every such patient: "The records do not
 * address" it, an unrated pair called "one caution", and Mary Smith "No — Trimethoprim interacts with simvastatin" over a
 * pair the data does not rate. Over the shipped knowledge base.
 */
public class CombinationProposalNoFindingTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Can I prescribe trimethoprim-sulfamethoxazole for her UTI?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aCombinationTheDataListsAgainstHerOrderWithoutARatingIsSaidSo() {
		String answer = ask(Arrays.asList(DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin")));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		// The bottom line opens the answer, and the rest of its sentence follows it (ADR Decision 534).
		assertEquals("The interaction data gives no rated reason to withhold Trimethoprim-sulfamethoxazole.", lines[0],
			"was: " + answer);
		assertTrue(lines[1].matches("The data lists Trimethoprim-sulfamethoxazole against one of this patient's medications, with no "
				+ "severity( or mechanism)? on file\\. \\[\\d+\\] \\[\\d+\\]"), "was: " + answer);
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[2], "was: " + answer);
		assertEquals("This check did not weigh what the drug is for (her UTI).", lines[lines.length - 1], "was: " + answer);
	}

	@Test
	public void aCombinationForAPatientWithNoOrdersIsSaidToHaveNothingToRelateTo() {
		String answer = ask(Collections.<PatientClinicalContext.ActiveDrugOrder> emptyList());
		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].matches("This patient has no active medication orders, so the interaction check had "
				+ "none to relate Trimethoprim-sulfamethoxazole to\\. \\[\\d+\\] \\[\\d+\\]"), "was: " + answer);
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			QUESTION).getModuleAnswer();
	}
}
