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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * That the loaded drug data carries no drug-condition rule is a limit of the knowledge base, stated in the module's
 * documentation and on the wire as {@code conditionRuleCoverage}, and never repeated in an answer (ADR Decision 562). On
 * the demo replica, "Not checked: this patient's conditions (no drug-condition rules are loaded)." closed 150 of 151
 * proposal answers word for word, and was the only line under "Show details" in 22 of them. And no answer says its check
 * covered "contraindications" where no condition rule could weigh one: it names what the pass screened, her recorded
 * allergies. Over the shipped data, as the demo runs it, for Kamwara (dd74dc61): Lamivudine, Nevirapine and Stavudine,
 * three orders each.
 */
public class ConditionsLimitIsNotRepeatedInAnswersTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	/** The demo's "Is it safe to start her on clarithromycin?": its caution, its finding, and nothing about the data. */
	@Test
	public void aProposalIsItsCautionAndItsFindingAlone() {
		PatientChart chart = ask("Is it safe to start her on clarithromycin?", Collections.<String> emptySet());
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(2, lines.length, "was: " + answer);
		assertEquals("1 interaction caution for Clarithromycin — Moderate, with Nevirapine.", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Nevirapine (3 orders) — Moderate. ["),
			"was: " + answer);
		assertEquals(lines[0], chart.getModuleAnswerSummary());
		assertFalse(chart.moduleAnswerDetailsStateLimits(), "nothing under it says what was not checked");
	}

	/** Every shape that carried the line, a contraindications question included, carries it no more. */
	@Test
	public void noAnswerRepeatsThatTheDataCarriesNoConditionRule() {
		String[] questions = { "Is it safe to start her on clarithromycin?", "Can I give her amoxicillin?",
				"Is it safe to start her on amoxicillin?", "Can I give her amoxicillin and ibuprofen together?",
				"Does he have any drug contraindications?", "Is metformin safe given her conditions?",
				"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to give Amlodipine?" };
		for (String question : questions) {
			String answer = ask(question, Collections.<String> emptySet()).getModuleAnswer();

			assertNotNull(answer, "precondition: the module answers " + question);
			assertFalse(answer.contains("drug-condition rule"), question + " — was: " + answer);
			assertFalse(answer.contains("this patient's conditions"), question + " — was: " + answer);
			assertFalse(answer.contains("drug interactions and contraindications"),
				"no condition was weighed, so contraindications were not covered: " + question + " — was: " + answer);
		}
	}

	private static PatientChart ask(String question, Set<String> allergies) {
		DrugReferenceService service = new DrugReferenceService();
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
			service.getLoadStatus().coverageOf(DrugReferenceLoad.Arm.CONDITION_RULES),
			"premise: the shipped dataset loaded, and publishes no drug-condition rule");
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			for (String drug : new String[] { "Lamivudine", "Nevirapine", "Stavudine" }) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug + "-" + n, drug, drug));
			}
		}
		return DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(66, null, DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null,
				allergies, DrugReferenceTestSupport.set("Dental caries"), orders),
			question);
	}
}
