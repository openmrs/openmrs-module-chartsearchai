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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A name the data does not recognise, asked about, is said to be unrecognised, and his screen is no answer to it (ADR
 * Decision 629, the owner's direction that an answer carries nothing beyond what was asked). On the demo replica "Can I
 * give him vitamin B6?" was answered with his whole screen in 21 of 23 patients — the name's digit kept it from reading as
 * a name — and "Is vitamin C safe with his medications?" with the model's "The records do not address vitamin C safety."
 * beside every chip of his screen, the shape read a drug's place only for a name the data recognises.
 */
public class UnrecognisedVitaminNameTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aNameTheDataDoesNotRecogniseIsSaidSoAndRaisesNoChipOfHisScreen() {
		for (String[] asked : new String[][] { { "Can I give him vitamin B6?", "vitamin B6" },
			{ "Is vitamin C safe with his medications?", "vitamin C" },
			{ "Is zorbaflex safe with his medications?", "zorbaflex" } }) {
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(), asked[0]);

			assertNull(chart.getModuleAnswer(), asked[0] + " — no screen answers it");
			assertEquals("No interaction check was run: \"" + asked[1] + "\" is not a drug name the interaction data "
					+ "recognises.", chart.getUncheckedWordStatement(), asked[0]);
			List<SafetyWarning> chips = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.shippedServiceWithGroups())
					.validate("", asked[0], context());
			assertTrue(chips.isEmpty(), asked[0] + " — no chip of his screen: " + DrugReferenceTestSupport.details(chips));
		}
	}

	/** "Everything" is no drug: whether everything is safe with his medications is still his screen. */
	@Test
	public void everythingIsNoDrugsName() {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(), "Is everything safe with his medications?");

		assertNull(chart.getUncheckedWordStatement(), "was: " + chart.getModuleAnswer());
	}

	private static PatientClinicalContext context() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"));
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, names, null, null, null, orders);
	}
}
