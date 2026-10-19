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
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether to change the dose of her drug is answered as whether it is safe to continue, the dose said not checked (ADR
 * Decision 346). On the 3.7.1 standalone "Is it safe to increase her simvastatin dose?" fitted no shape, and the model
 * answered every patient: Rita, on nevirapine and no simvastatin, was told "Simvastatin can be given, with one caution",
 * a clearance of a drug she does not take, and Sarah Taylor "No — Simvastatin should not be increased: it has unknown
 * severity interactions with prednisone, celecoxib …", unrated rows made a reason. Over the shipped knowledge base.
 */
public class ChangingHerDrugsDoseTest extends BaseModuleContextSensitiveTest {

	private static final List<String> QUESTIONS = Arrays.asList("Is it safe to increase her simvastatin dose?",
		"Is it safe to increase her simvastatin to 40 mg?", "Can I double his simvastatin dose?",
		"Should I reduce her simvastatin dose?", "Can I increase her simvastatin?");

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void ofHerDrugItIsAnsweredAsWhetherItIsSafeToContinueTheDoseNotChecked() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin 20mg", "Simvastatin"),
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg", "Clarithromycin"));
		String expected = mask(ask("Is her simvastatin safe to continue?", orders).getModuleAnswer());
		assertNotNull(expected, "precondition: the module answers");
		for (String question : QUESTIONS) {
			PatientChartSerializer.PatientChart chart = ask(question, orders);
			assertEquals(expected, mask(chart.getModuleAnswer()), question);
			assertTrue(chart.statesADose(), question + " — the dose is said not checked");
		}
	}

	@Test
	public void ofADrugSheIsNotOnThereIsNoDoseToChange() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : Arrays.asList(
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
				Arrays.<PatientClinicalContext.ActiveDrugOrder> asList())) {
			for (String question : QUESTIONS) {
				assertEquals("Simvastatin is not one of this patient's active medications, so there is no dose of it to change.",
					ask(question, orders).getModuleAnswer(), question + " / " + orders);
			}
		}
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static PatientChartSerializer.PatientChart ask(String question,
			List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), question);
	}
}
