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
 * Whether a drug interacts with her medications, where the only finding about it is her allergy to it, is not answered
 * "Yes — it interacts" (ADR Decision 398). On the 3.7.1 standalone "Check interactions for amoxicillin" was answered "Yes —
 * Amoxicillin interacts with this patient's active medications" over her recorded allergy to amoxicillin alone, and "Any
 * interaction between her meds and ibuprofen?" alike over George Anderson's allergy to ibuprofen. Over the shipped
 * knowledge base.
 */
public class InteractionQuestionWithOnlyAnAllergyFindingTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-met", "Metformin 500mg", "Metformin"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void anAllergyAloneIsNoInteractionAndIsStatedAsWhatTheCheckFound() {
		for (String question : new String[] { "Check interactions for amoxicillin",
				"Does amoxicillin interact with her medications?" }) {
			String answer = ask(question);
			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertEquals("The interaction check raised no interaction finding about Amoxicillin against this patient's "
					+ "active medications.", lines[0], answer);
			assertEquals("What this module's drug-safety check found about Amoxicillin:", lines[1], answer);
			assertTrue(lines[2].startsWith("The patient has a recorded allergy to Amoxicillin."), answer);
		}
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null,
						new LinkedHashSet<String>(Arrays.asList("amoxicillin")), null, ORDERS), question)
				.getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
