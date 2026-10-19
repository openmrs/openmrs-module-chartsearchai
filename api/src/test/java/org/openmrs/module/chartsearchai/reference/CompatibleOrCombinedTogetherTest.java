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
 * Whether one drug is compatible with another, or can be combined with it, is asked as whether it is safe with it (ADR
 * Decision 332). On the 3.7.1 standalone "Is warfarin compatible with his amiodarone?", "Can metronidazole be combined with
 * his warfarin?" and "Is ciprofloxacin compatible with his warfarin?" fitted no shape and went to the model for every
 * patient, mostly on neither drug, which answered "No — Warfarin should not be given". Over the shipped knowledge base.
 */
public class CompatibleOrCombinedTogetherTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void compatibleOrCombinedIsAskedAsSafeWith() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
				DrugReferenceTestSupport.activeOrder("order-warf", "Warfarin", "Warfarin"))) {
			String[][] pairs = { { "Is warfarin compatible with his amiodarone?", "Is warfarin safe with his amiodarone?" },
					{ "Can metronidazole be combined with his warfarin?", "Is metronidazole safe with his warfarin?" },
					{ "Is ciprofloxacin compatible with his warfarin?", "Is ciprofloxacin safe with his warfarin?" },
					{ "Are warfarin and amiodarone compatible?", "Are warfarin and amiodarone safe together?" } };
			for (String[] pair : pairs) {
				String plain = ask(pair[1], order);
				assertNotNull(plain, pair[1] + " / " + order.getDisplay() + " — precondition: the module answers");
				assertEquals(mask(plain), mask(ask(pair[0], order)), pair[0] + " / " + order.getDisplay());
			}
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
