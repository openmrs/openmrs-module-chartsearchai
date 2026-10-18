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
 * Whether her medications carry drug interactions or allergy conflicts is a screen of her medications, answered as "Are her
 * medications safe to take together?" is (ADR Decision 318). On the 3.7.1 standalone "Any drug interactions or allergy
 * conflicts with her current medications?" went to the model for every patient: Carol Brown was told "No — Simvastatin
 * should be changed", Agnes Adams that her allergy is "a reason to change a medication this patient is already taking",
 * and patients whose screen found nothing "The records do not address drug interactions or allergy conflicts". Over the
 * shipped knowledge base.
 */
public class InteractionsOrAllergyConflictsScreenTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void interactionsOrAllergyConflictsAreAnsweredAsTheSafetyScreen() {
		PatientClinicalContext.ActiveDrugOrder[][] cases = {
				{ DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
						DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone") },
				{ DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin Co 20mg", "Simvastatin"),
						DrugReferenceTestSupport.activeOrder("order-amlo", "Amlodipine 5mg", "Amlodipine") },
				{ DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril") } };
		for (PatientClinicalContext.ActiveDrugOrder[] orders : cases) {
			String plain = screen("Are her medications safe to take together?", orders);
			assertNotNull(plain, orders[0].getDisplay() + " — precondition: the module answers the safety screen");
			for (String question : Arrays.asList("Any drug interactions or allergy conflicts with her current medications?",
					"Are there any interactions or allergy conflicts with her medications?")) {
				assertEquals(plain.replaceAll("\\[\\d+\\]", "[n]"),
					String.valueOf(screen(question, orders)).replaceAll("\\[\\d+\\]", "[n]"), question + " / " + orders[0].getDisplay());
			}
		}
	}

	private static String screen(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null,
				DrugReferenceTestSupport.set("dexamethasone"), null, orders), question).getModuleAnswer();
	}
}
