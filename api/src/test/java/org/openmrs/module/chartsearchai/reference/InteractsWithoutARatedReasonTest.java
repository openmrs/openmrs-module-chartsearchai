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
 * Whether a drug interacts with her medications, where the only reason to withhold it is one no rating makes, is answered
 * "Yes" with what was found and no claim of a reason to withhold (ADR Decision 314). On the 3.7.1 standalone Mogan
 * Kamwara, on nevirapine, lamivudine and stavudine, asked "Does efavirenz interact with his TB drugs?", and the model
 * answered "Yes, Efavirenz interacts with the patient's TB drugs" — of three antiretrovirals — calling a Moderate rating
 * "a Moderate reason to withhold it". Over the shipped knowledge base.
 */
public class InteractsWithoutARatedReasonTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void anInteractionWithheldByNoRatingIsAnsweredYesWithNoReasonToWithholdClaimed() {
		String answer = ask("Does ketoconazole interact with his medications?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Yes — Ketoconazole interacts with this patient's active medications.", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Ketoconazole interacts with active order Metronidazole — Minor."), "was: " + answer);
		assertTrue(!answer.contains("reason to withhold"), "no rating makes one: " + answer);
	}

	/** A rated reason to withhold still says so. */
	@Test
	public void aRatedReasonToWithholdIsStillStated() {
		String answer = ask("Does dolutegravir interact with his medications?",
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"));
		assertNotNull(answer, "the module answers");
		assertEquals("Yes — Dolutegravir interacts with this patient's active medications. This module's drug-safety check "
				+ "found a reason to withhold it.", answer.split("\n")[0], "was: " + answer);
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
