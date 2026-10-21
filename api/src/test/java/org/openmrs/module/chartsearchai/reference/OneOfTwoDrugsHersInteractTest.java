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
 * Whether two drugs interact, one of them hers, is answered where a finding relates them, whatever else the check found
 * about the other (ADR Decision 404). On the 3.7.1 standalone Dora, on rifampicin and nevirapine, asked "Does rifampicin
 * interact with her efavirenz?", and the model answered "Efavirenz can be given, with one caution": efavirenz's finding of
 * one class with her nevirapine made no verdict, so the module left the question to it. Over the shipped knowledge base.
 */
public class OneOfTwoDrugsHersInteractTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin"),
		DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void whetherADrugInteractsWithOneOfHersIsAnsweredWhereAFindingRelatesThem() {
		for (String question : new String[] { "Does rifampicin interact with efavirenz?",
				"Does efavirenz interact with rifampicin?" }) {
			String answer = ask(question);
			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.startsWith("Rifampicin (rifampin) is one of this patient's active medications"), answer);
			assertTrue(answer.contains("\nYes — Efavirenz interacts with this patient's active medications."), answer);
			assertTrue(answer.contains("Efavirenz interacts with active order Rifampicin"), answer);
			assertTrue(!answer.contains("can be given"), "no clearance: " + answer);
		}
	}

	/** And both hers: "Should I change her nevirapine if I start rifampicin?" of Dora, on both, went to the model. */
	@Test
	public void whetherToChangeOneOfHersIfStartingAnotherOfHersIsWhatRelatesThem() {
		String answer = ask("Should I change her nevirapine if I start rifampicin?");
		assertNotNull(answer, "the module answers");
		assertTrue(answer.equals(ask("Does her nevirapine interact with her rifampicin?")), answer);
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
