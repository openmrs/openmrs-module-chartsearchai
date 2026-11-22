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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * ADR Decision 676: a proposal's cautions are counted by her ORDERS (ADR Decision 545), so one combination order the
 * drug relates to is one caution, however many of its substances the knowledge base names. On the demo a patient whose
 * one co-trimoxazole order rifampicin relates to was told "2 interaction cautions for Rifampicin (rifampin) — Moderate,
 * with Sulfamethoxazole and Trimethoprim" above the one finding.
 */
public class CombinationOrderCautionCountTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void oneCombinationOrderIsOneCaution() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Collections.singletonList(
			DrugReferenceTestSupport.activeOrder("order-cotrimoxazole", "Trimethoprim and sulfamethoxazole"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(orders.get(0).getNames());
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), "Can I give her rifampicin?")
				.getModuleAnswer();

		assertNotNull(answer, "the module answers the proposal");
		assertEquals("1 interaction caution for Rifampicin (rifampin) — Moderate, with Sulfamethoxazole and Trimethoprim.",
			answer.split("\n")[0], "was: " + answer);
	}

	@Test
	public void aScreenCountsOneCombinationOrderAsOneCaution() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = java.util.Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-rifampicin", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-cotrimoxazole", "Trimethoprim and sulfamethoxazole"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
					"Does she have any drug interactions?")
				.getModuleAnswer();

		assertNotNull(answer, "the module answers the screen");
		assertEquals("No Major interactions; 1 caution.", answer.split("\n")[0], "was: " + answer);
	}
}
