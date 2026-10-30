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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A composed line about an order her chart holds several prescriptions of cites each of them (ADR Decision 539). On the
 * public demo patient dd74dc61, on Lamivudine, Nevirapine and Stavudine, three orders each, "Can I give him codeine for
 * pain?" stated "Codeine interacts with active order Nevirapine — Moderate. [114]" and cited no nevirapine order: one
 * display three prescriptions spell named no one record, so the line named none, and the clinician could not reach the
 * prescription it is about.
 */
public class EveryOrderOfThePartnerIsCitedTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aLineAboutAPartnerOfThreePrescriptionsCitesEachOfThem() {
		PatientChart chart = ask("Can I give him codeine for pain?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String line = null;
		for (String each : answer.split("\n")) {
			if (each.startsWith("Codeine" + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Nevirapine")) {
				line = each;
			}
		}
		assertNotNull(line, "precondition: a line states the nevirapine caution: " + answer);
		Set<Integer> nevirapineRecords = new TreeSet<Integer>();
		Set<String> nevirapineOrders = new HashSet<String>(Arrays.asList("order-Nevirapine-1", "order-Nevirapine-2",
			"order-Nevirapine-3"));
		for (RecordMapping mapping : chart.getMappings()) {
			if (nevirapineOrders.contains(mapping.getResourceUuid())) {
				nevirapineRecords.add(mapping.getIndex());
			}
		}
		assertEquals(3, nevirapineRecords.size(), "precondition: each prescription is a record of the chart: " + chart.getMappings());
		Set<Integer> cited = ChartSearchAiUtils.citedIndexes(line);
		assertTrue(cited.containsAll(nevirapineRecords), "each nevirapine prescription is cited: " + line + " — records "
				+ nevirapineRecords);
		assertEquals(nevirapineRecords.size() + 1, cited.size(), "the finding and those prescriptions, nothing else: " + line);
		assertTrue(line.matches("Codeine interacts with active order Nevirapine — Moderate\\. \\[\\d+\\]( \\[\\d+\\]){3}"),
			"its markers after its words: " + line);
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			for (String drug : new String[] { "Lamivudine", "Nevirapine", "Stavudine" }) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug + "-" + n, drug, drug));
			}
		}
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(70, null, DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null,
				null, null, orders),
			question);
	}
}
