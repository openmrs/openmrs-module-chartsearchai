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
 * Three groups of his medications asked about together are answered over what the check relates among them, as two are
 * between them (ADR Decision 636). Decisions 630 and 631 answered one group or two; three fell back to his whole screen,
 * naming his fluconazole and amlodipine in an answer about his ARVs, TB drugs and statins.
 */
public class ThreeGroupsOfHisTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void threeGroupsOfHisAreAnsweredOverWhatTheCheckRelatesAmongThem() {
		for (String question : Arrays.asList("Do his ARVs interact with his TB drugs and his statins?",
			"Any interactions between his ARVs, TB drugs and statins?")) {
			PatientChart chart = ask(question);
			String answer = chart.getModuleAnswer();

			assertNotNull(answer, question);
			assertEquals("This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir), TB drugs (Rifampicin) and statins "
					+ "in ATC group C10AA (Simvastatin): what the interaction check relates among them:", answer.split("\n")[0],
				question);
			assertTrue(answer.contains("\nNevirapine interacts with active order Rifampicin"), question + ": " + answer);
			assertFalse(answer.contains("Fluconazole") || answer.contains("Amlodipine"), question + ": " + answer);
			assertTrue(chart.getFindingsBearingOnTheQuestion().stream()
					.noneMatch(d -> d.contains("Fluconazole") || d.contains("Amlodipine")),
				question + ": " + chart.getFindingsBearingOnTheQuestion());
		}
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin"),
			DrugReferenceTestSupport.activeOrder("order-flu", "Fluconazole"),
			DrugReferenceTestSupport.activeOrder("order-aml", "Amlodipine"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir", "Rifampicin", "Simvastatin", "Fluconazole", "Amlodipine")),
				null, null, null, orders), question);
	}
}
