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
 * Two drugs asked about together that the interaction data relates in no way, at any rating, are answered by the module
 * with that, and with which of them she takes (ADR Decision 302). On the 3.7.1 standalone "Does his chloroquine interact
 * with his diphenhydramine?" was answered by the model "The records do not address the interaction between Chloroquine
 * and Diphenhydramine." — for Mark Smith, who takes both, and for Sarah Taylor, who takes neither and whose celecoxib
 * chloroquine interacts with. Over the shipped knowledge base.
 */
public class TwoDrugsTheDataDoesNotRelateTest extends BaseModuleContextSensitiveTest {

	private static final String NO_LISTING = "The interaction data lists no interaction between Chloroquine and "
			+ "Diphenhydramine at any rating.";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoOfHisOwnDrugsTheDataDoesNotRelateAreSaidToBeUnrelatedByIt() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-cq", "Chloroquine Co 250mg", "Chloroquine"),
			DrugReferenceTestSupport.activeOrder("order-dph", "Benadryl 25mg", "Diphenhydramine"));
		for (String question : Arrays.asList("Does his chloroquine interact with his diphenhydramine?",
			"Is chloroquine safe with diphenhydramine?")) {
			String answer = ask(question, orders);
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			assertTrue(lines[0].matches("Chloroquine \\(Chloroquine Co 250mg( \\[\\d+\\])?\\) and Diphenhydramine \\(Benadryl "
					+ "25mg( \\[\\d+\\])?\\) are both among this patient's active medications\\."), question + " — was: " + answer);
			assertTrue(lines[1].matches(java.util.regex.Pattern.quote(NO_LISTING) + " \\[\\d+\\] \\[\\d+\\]"),
				question + " — was: " + answer);
			assertEquals(DrugReferenceInjector.SHARED_CLASS_NOT_COMPARED, lines[2], question + " — was: " + answer);
		}
	}

	@Test
	public void twoDrugsNeitherHersTheDataDoesNotRelateAreSaidToBeUnrelatedAndEachStatesItsOwnFindings() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-cel", "Celebrex 200mg", "Celecoxib"));
		String question = "Does his chloroquine interact with his diphenhydramine?";
		String answer = ask(question, orders);
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Neither Chloroquine nor Diphenhydramine is one of this patient's active medications.", lines[0],
			"was: " + answer);
		assertTrue(lines[1].matches(java.util.regex.Pattern.quote(NO_LISTING) + " \\[\\d+\\] \\[\\d+\\]"), "was: " + answer);
		assertEquals("Against this patient's active medications:", lines[2], "was: " + answer);
		assertTrue(lines[3].startsWith("Chloroquine interacts with active order Celecoxib — Moderate."), "was: " + answer);
	}

	private static String ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
