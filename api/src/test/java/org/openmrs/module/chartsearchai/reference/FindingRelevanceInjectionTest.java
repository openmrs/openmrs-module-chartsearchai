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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question asking what one drug does to her, whose findings the model judges none to bear on, is still the chart the
 * injector built for it (ADR Decision 613): it names the drug asked about and says it was judged. On the demo replica, a
 * patient whose orders all had their own records, "Should I stop his nevirapine because of the rash?" was handed back as
 * the chart before injection — naming no drug — so the model's reading ran the screen of all his medications instead.
 */
public class FindingRelevanceInjectionTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Should I stop his nevirapine because of the rash?";

	@Test
	public void aJudgedQuestionWhoseFindingsNoneBearOnItIsStillTheChartBuiltForIt() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o1", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("o2", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("o3", "Lamivudine 150mg", "Lamivudine"),
			DrugReferenceTestSupport.activeOrder("o4", "Stavudine 30mg", "Stavudine"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		DrugReferenceInjector injector = DrugReferenceTestSupport.injectorWithSafety(
			DrugReferenceTestSupport.shippedServiceWithGroups());
		// Her orders each with a record of their own, as her chart has them.
		PatientChart hers = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), "");

		PatientChart judged = injector.injectRecords(hers, DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			QUESTION, null, (question, findings) -> Collections.<Integer> emptySet());

		assertTrue(judged.asksWhatADrugDoesToHer(), "it says the question asks what the drug does to her");
		assertEquals(Collections.emptySet(), judged.getFindingsBearingOnTheQuestion(), "judged, none bearing on it");
		assertFalse(judged.getQuestionSubstances().isEmpty(), "it names the drug the question asks about");
		assertNull(judged.getModuleAnswer(), "the model answers it");
		for (RecordMapping mapping : judged.getMappings()) {
			assertFalse(ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())
					|| ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(mapping.getResourceType()),
				"no finding and no reference record: " + mapping.getText());
		}
	}
}
