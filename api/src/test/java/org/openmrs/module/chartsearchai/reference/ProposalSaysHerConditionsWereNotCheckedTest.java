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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A drug proposed beside her medications is answered saying her conditions were not checked where no drug-condition rule
 * is loaded (ADR Decision 533). On the public demo patient dd74dc61, on Lamivudine, Nevirapine and Stavudine, three
 * orders each, "Is it safe to start her on clarithromycin?" was answered with its one Moderate caution with nevirapine
 * and nothing else, though the shipped data carries no drug-condition rule and none of his conditions was weighed.
 */
public class ProposalSaysHerConditionsWereNotCheckedTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aProposalSaysHerConditionsWereNotChecked() {
		PatientChart chart = ask("Is it safe to start her on clarithromycin?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(3, lines.length, "was: " + answer);
		assertEquals("1 interaction caution for Clarithromycin — Moderate, with Nevirapine.", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Nevirapine — Moderate. ["),
			"was: " + answer);
		// Not the pairs the data lists it in unrated, which a caution answer leaves out (ADR Decision 146).
		assertEquals(DrugReferenceInjector.CONDITIONS_NOT_CHECKED, lines[2], "was: " + answer);
		assertEquals(lines[0], chart.getModuleAnswerSummary(), "the bottom line stays the one sentence in view");
		assertTrue(chart.moduleAnswerDetailsStateLimits(), "a client says the details state what was not checked");
	}

	@Test
	public void aQuestionOnlyWhetherItInteractsDoesNotSpeakOfHerConditions() {
		String answer = ask("Does clarithromycin interact with her medications?").getModuleAnswer();

		assertNotNull(answer, "the module answers");
		assertFalse(answer.contains(DrugReferenceInjector.CONDITIONS_NOT_CHECKED), "was: " + answer);
	}

	private static PatientChart ask(String question) {
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
			DrugReferenceTestSupport.ctx(70, null, DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null,
				null, null, orders),
			question);
	}
}
