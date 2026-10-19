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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Which interactions stopping her drug would end is answered with what relates that drug to her other medications; of a
 * drug she is not on, that there is no order of it to stop (ADR Decision 333). On the 3.7.1 standalone "If I stop her
 * rifampicin, which interactions go away?" went to the model for every patient: Priya, on no rifampicin, was told
 * "Stopping Rifampicin removes the Major interaction with Simvastatin". Over the shipped knowledge base.
 */
public class StoppingADrugWhichInteractionsEndTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	private static final List<String> QUESTIONS = Arrays.asList("If I stop her rifampicin, which interactions go away?",
		"Which interactions go away if I stop her rifampicin?", "What interactions would stopping her rifampicin remove?");

	@Test
	public void ofHerDrugItIsWhatInteractsWithIt() {
		PatientClinicalContext.ActiveDrugOrder[] orders = {
				DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin"),
				DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine") };
		String what = ask("What interacts with her rifampicin?", orders);
		assertNotNull(what, "precondition: the module answers what interacts with her drug");
		for (String question : QUESTIONS) {
			assertEquals(mask(what), mask(ask(question, orders)), question);
		}
	}

	@Test
	public void ofADrugSheIsNotOnThereIsNothingToStop() {
		for (String question : QUESTIONS) {
			assertEquals("Rifampicin (rifampin) is not one of this patient's active medications, so there is no order of it to "
					+ "stop, and no interaction among this patient's medications that stopping it would end.",
				ask(question, DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin Co 20mg", "Simvastatin")),
				question);
		}
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
