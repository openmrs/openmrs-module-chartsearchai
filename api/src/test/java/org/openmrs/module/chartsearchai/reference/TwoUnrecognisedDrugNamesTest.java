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
 * Two words in a two-drug question's drug places, neither a name the data recognises nor one of her orders', are said not
 * to have been checked, the pair named (ADR Decision 382). On the 3.7.1 standalone "Does his klarizom interact with his
 * zolvimix?" and "Is Zolvimix safe with Klarizom?" went to the model for every patient but Michael Turner, on both — "The
 * records do not address Zolvimix and Klarizom" — while one such word beside a drug the data does recognise was answered.
 * Over the shipped knowledge base.
 */
public class TwoUnrecognisedDrugNamesTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole 500mg", "Metronidazole"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoWordsNamedAsHisThatNoOrderOfHisCarriesAreSaidNotChecked() {
		String answer = ask("Does his klarizom interact with his zolvimix?");
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("None of this patient's active orders is named \"klarizom\" or \"zolvimix\".", lines[0], answer);
		assertTrue(lines[1].startsWith("No interaction check was run for \"klarizom\" and \"zolvimix\": neither is a drug "
				+ "name the interaction data recognises."), answer);
	}

	@Test
	public void twoWordsInATwoDrugQuestionAreSaidNotChecked() {
		for (String question : Arrays.asList("Is Zolvimix safe with Klarizom?", "Is klarizom safe with zolvimix?")) {
			String answer = ask(question);
			assertNotNull(answer, question + " — the module answers");
			assertTrue(answer.toLowerCase().startsWith("no interaction check was run for \"")
					&& answer.contains("\": neither is a drug name the interaction data recognises."), question + " — was: "
							+ answer);
			assertTrue(answer.toLowerCase().contains("\"zolvimix\"") && answer.toLowerCase().contains("\"klarizom\""), answer);
		}
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question).getModuleAnswer();
	}
}
