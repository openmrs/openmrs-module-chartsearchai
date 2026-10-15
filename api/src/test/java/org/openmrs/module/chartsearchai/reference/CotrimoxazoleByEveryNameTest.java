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
 * Co-trimoxazole asked about by any of its names is checked as both of its ingredients (ADR Decision 266). On the 3.7.1
 * standalone Donald Thompson, on warfarin, asked "Can I give him Bactrim?" and read "2 interaction cautions for
 * Trimethoprim": the Major interaction between sulfamethoxazole and warfarin was never checked, because the knowledge base
 * filed its Sulfamethoxazole row under sulfamethazine's RxNorm concept, so the combination's brands reached trimethoprim
 * alone. "Can I give him co-trimoxazole?" read "No interaction check was run". Over the shipped knowledge base.
 */
public class CotrimoxazoleByEveryNameTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aBrandOfTheCombinationIsCheckedAsBothOfItsIngredients() {
		assertWithholdsForHisWarfarinOnBothIngredients(ask("Can I give him Bactrim?"), "Bactrim", "Bactrim");
	}

	@Test
	public void theCombinationsOwnNameIsCheckedAsBothOfItsIngredients() {
		for (String name : Arrays.asList("co-trimoxazole", "cotrimoxazole", "Co-trimoxazole")) {
			String question = "Can I give him " + name + "?";
			assertWithholdsForHisWarfarinOnBothIngredients(ask(question), question,
				Character.toUpperCase(name.charAt(0)) + name.substring(1));
		}
	}

	/** {@code asked}: the combination as the question names it, which the lead names (ADR Decision 282). */
	private static void assertWithholdsForHisWarfarinOnBothIngredients(String answer, String question, String asked) {
		assertNotNull(answer, question + ": the module answers");
		String[] lines = answer.split("\n");
		assertEquals("No — this module's drug-safety check found a reason to withhold " + asked + ".", lines[0],
			question + " was: " + answer);
		assertTrue(Arrays.stream(lines).anyMatch(
			l -> l.matches("Sulfamethoxazole interacts with active order Warfarin — Major\\.( \\[\\d+\\])+")),
			question + " checks sulfamethoxazole: " + answer);
		assertTrue(Arrays.stream(lines).anyMatch(
			l -> l.matches("Trimethoprim interacts with active order Warfarin — Moderate\\.( \\[\\d+\\])+")),
			question + " checks trimethoprim: " + answer);
	}

	private static String ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-warfarin", "Warfarin 5mg"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null,
				new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question).getModuleAnswer();
	}
}
