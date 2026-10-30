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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A bottom line cut from the answer's first sentence opens the answer, and the rest of that sentence follows it, so what
 * a client collapses under the bottom line does not repeat it (ADR Decision 534). On the public demo patient dd74dc61, on
 * Lamivudine, Nevirapine and Stavudine, three orders each, "Can I give her ibuprofen?" showed "The interaction data gives
 * no rated reason to withhold Ibuprofen." and, under "Show details and limits", the whole answer again from those words;
 * and it counted his three drugs as "3 of this patient's orders", where he has nine.
 */
public class BottomLineOpensTheAnswerTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aBottomLineCutFromTheFirstSentenceOpensTheAnswerAndIsNotRepeatedUnderIt() {
		PatientChart chart = ask("Can I give her ibuprofen?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String summary = chart.getModuleAnswerSummary();
		assertEquals("The interaction data gives no rated reason to withhold Ibuprofen.", summary, "was: " + answer);
		// A client splits the details off where the answer opens with the bottom line and a line break.
		assertTrue(answer.startsWith(summary + "\n"), "the bottom line opens the answer: " + answer);
		String[] lines = answer.split("\n");
		assertEquals(3, lines.length, "was: " + answer);
		// His three drugs are nine orders: what is counted is his medications.
		assertTrue(lines[1].startsWith("The data lists Ibuprofen against 3 of this patient's medications, none with a severity "
				+ "or mechanism on file. ["), "the rest of the sentence, and not its first words again: " + answer);
		assertTrue(lines[2].startsWith("Not checked: "), "was: " + answer);
		assertTrue(chart.moduleAnswerDetailsStateLimits(), "was: " + answer);
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
