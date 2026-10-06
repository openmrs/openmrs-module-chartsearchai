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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The module's answer to a proposal for a patient with no active medication orders (ADR Decision 158) states that the
 * check had nothing to relate the drug to — and so must be composed only where the context carries no active drug in
 * ANY form. A context carrying the flattened names and codes without per-order structure (issue #118's shape) still
 * records medications, so the sentence would be false of it. The database-backed cases in
 * {@code LlmInferenceServiceAnswerFromFindingsContextTest} cannot build that shape; this drives the real injector over
 * the shipped knowledge base with it.
 */
public class NoActiveOrdersProposalAnswerTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Is warfarin safe for her?";

	private static final String OPENING = "This patient has no active medication orders, so the interaction check had "
			+ "none to relate Warfarin to. [";

	private static DrugReferenceService shipped;

	private static synchronized DrugReferenceService shipped() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.shippedServiceWithGroups();
		}
		return shipped;
	}

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aContextCarryingNoActiveDrugIsAnsweredThatTheCheckHadNothingToRelateTheDrugTo() {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null), QUESTION);

		String answer = chart.getModuleAnswer();
		assertTrue(answer != null && answer.startsWith(OPENING), "the premise the case below rests on: " + answer);
	}

	@Test
	public void aContextCarryingFlattenedActiveDrugsWithNoOrderIsNotAnsweredThatItHasNone() {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Nystatin"),
						DrugReferenceTestSupport.set("A07AA02"), null, null),
				QUESTION);

		String answer = chart.getModuleAnswer();
		assertFalse(answer != null && answer.startsWith(OPENING),
				"she is on nystatin, recorded without per-order structure, so the check had something to relate "
						+ "warfarin to: " + answer);
	}
}
