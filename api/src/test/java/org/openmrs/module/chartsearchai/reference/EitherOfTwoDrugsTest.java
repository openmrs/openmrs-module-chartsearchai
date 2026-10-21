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
 * Either of two drugs asked about — whether to give one or the other, or which is safer — is answered for each as if
 * proposed alone, nothing said of the pair, and never which to choose (ADR Decision 393). On the 3.7.1 standalone the model
 * answered "Can I give her ibuprofen or paracetamol?" with "Ibuprofen can be given, with one caution" and a Major the data
 * never rated, and "Which is safer for her, ibuprofen or paracetamol?" with "Acetaminophen is safer for her". Over the
 * shipped knowledge base.
 */
public class EitherOfTwoDrugsTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
		DrugReferenceTestSupport.activeOrder("order-mtx", "Methotrexate 2.5mg", "Methotrexate"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void eitherOfTwoDrugsIsAnsweredForEachAsIfProposedAlone() {
		String answer = ask("Can I give her ibuprofen or paracetamol?");
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Ibuprofen or Acetaminophen, each as if given alone; neither is one of this patient's active "
				+ "medications.", lines[0], answer);
		assertTrue(answer.contains("\nFor Ibuprofen:\n") && answer.contains("\nFor Acetaminophen:\n"), answer);
		assertTrue(!answer.contains("Between them"), "nothing of the pair: " + answer);
	}

	@Test
	public void whichOfTwoDrugsIsSaferIsAnsweredForEachAndNeitherIsChosen() {
		String answer = ask("Which is safer for her, ibuprofen or paracetamol?");
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(DrugReferenceInjector.COMPARES_NO_DRUGS_LEAD, lines[0], answer);
		assertTrue(answer.contains("\nFor Ibuprofen:\n") && answer.contains("\nFor Acetaminophen:\n"), answer);
		assertTrue(!answer.substring(lines[0].length()).toLowerCase().contains("safer"), "neither is chosen: " + answer);
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
