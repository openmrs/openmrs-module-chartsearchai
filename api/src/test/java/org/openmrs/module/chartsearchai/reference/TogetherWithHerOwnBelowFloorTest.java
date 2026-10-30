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
 * A drug proposed beside one of hers, which the data lists against her orders only without a rating, is answered as its
 * proposal alone is, after her drug is named (ADR Decision 306). On the 3.7.1 standalone "Can I give her fluconazole with
 * her aspirin?" went to the model, which told Agnes Adams "Fluconazole can be given, with one caution: it has an Unknown
 * severity interaction with aspirin" — an unrated relationship called a caution — and Barbara Miller "The records do not
 * address" it. Over the shipped knowledge base.
 */
public class TogetherWithHerOwnBelowFloorTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugTheDataListsAgainstHerOwnWithoutARatingIsAnsweredAsItsProposalIs() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		String alone = ask("Can I give her fluconazole?", orders);
		assertNotNull(alone, "precondition: the module answers the proposal alone");
		for (String question : Arrays.asList("Can I give her fluconazole with her aspirin?",
			"Is it safe to give her fluconazole with her aspirin?")) {
			String answer = ask(question, orders);
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n", 2);
			assertEquals("Acetylsalicylic acid (aspirin) is one of this patient's active medications.", lines[0],
				question + " — was: " + answer);
			// Word for word; the record numbers differ, the question naming her drug injecting its record too. Asked alone, the
			// answer opens with its bottom line cut from that sentence; here its bottom line is several statements, so the
			// sentence stays whole (ADR Decision 534).
			assertEquals(alone.replaceFirst("\\.\nThe data lists ", ": the data lists ").replaceAll("\\[\\d+\\]", "[n]"),
				lines[1].replaceAll("\\[\\d+\\]", "[n]"),
				question + " — the proposal's own answer follows: " + answer);
		}
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
