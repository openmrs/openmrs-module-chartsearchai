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
 * A proposal beside an allergy the question SUPPOSES — "if she is allergic to aspirin", "to a penicillin-allergic patient" — is
 * answered as the proposal, closing that the check weighs only the allergies her chart records (ADR Decision 410); the drug
 * the supposed allergy names is never a drug in play. On the 3.7.1 standalone Mogan Kamwara, on no aspirin and allergic to
 * nothing, asked "Is ibuprofen ok if she is allergic to aspirin?" and was answered "No — Ibuprofen should not be given:
 * Acetylsalicylic acid (aspirin) interacts with Ibuprofen, a Major problem". Over the shipped knowledge base.
 */
public class SupposedAllergyTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalBesideASupposedAllergyIsTheProposalSayingTheSupposedAllergyWasNotWeighed() {
		String[][] cases = { { "Is ibuprofen ok if she is allergic to aspirin?", "Is ibuprofen ok?", "aspirin" },
			{ "Can I give her ceftriaxone if she is allergic to penicillin?", "Can I give her ceftriaxone?", "penicillin" },
			{ "Is fluconazole safe if she has a sulfa allergy?", "Is fluconazole safe?", "sulfa" },
			{ "Can I give cephalexin to a penicillin-allergic patient?", "Can I give cephalexin?", "penicillin" },
			{ "Is it safe to give ceftriaxone to someone allergic to amoxicillin?", "Is it safe to give ceftriaxone?",
					"amoxicillin" } };
		for (String[] each : cases) {
			String proposal = ask(each[1]);
			assertNotNull(proposal, "precondition: the module answers " + each[1]);
			assertEquals(proposal + "\n" + DrugReferenceInjector.SUPPOSED_ALLERGY_NOT_WEIGHED_OPENING + each[2] + ").",
				ask(each[0]), each[0]);
		}
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
