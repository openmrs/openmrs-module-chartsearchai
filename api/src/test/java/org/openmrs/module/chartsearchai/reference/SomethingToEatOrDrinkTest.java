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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Something she would eat or drink that the drug data does not carry takes the chart answer, the unchecked statement after
 * it, and is offered no drug's name (ADR Decision 390): on the 3.7.1 standalone "Can she drink milk?" was answered by the
 * module alone, "No interaction check was run: "milk" is not a drug name the interaction data recognises. Did you mean
 * Milk thistle?". Over the shipped knowledge base.
 */
public class SomethingToEatOrDrinkTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-cq", "Chloroquine Co 250mg", "Chloroquine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void somethingToDrinkIsOfferedNoDrugsName() {
		PatientChart chart = ask("Can she drink milk?");
		assertNull(chart.getModuleAnswer(), "the chart answer: " + chart.getModuleAnswer());
		assertEquals("No interaction check was run: \"milk\" is not a drug name the interaction data recognises.",
			chart.getUncheckedWordStatement());
	}

	private static PatientChart ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>(ORDERS.get(0).getNames());
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question);
	}
}
