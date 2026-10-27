/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen's answer, through the service, counts its cautions and names none of their orders after its last line (ADR
 * Decision 493). On the replica of the public demo, the count's citations reached the check naming every order a cited
 * finding covers that the answer leaves unnamed (ADR Decision 100), which appended "Also covered by those findings and
 * not named above: active order Trimethoprim and sulfamethoxazole and active order Efavirenz." to the line naming the
 * orders the drug data does not identify.
 */
public class BriefScreenAnswerContextTest extends BaseModuleContextSensitiveTest {

	private static final String SCREEN = "Are there any drug interactions with her current medications?";

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			"true");
		executeDataSet("AnswerFromFindingsWarfarinOrderTestData.xml");
		executeDataSet("ListedMedicationsRifampicinOrderTestData.xml");
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void aCountedCautionsOrdersAreNotNamedUnderTheAnswer() {
		ChartAnswer answer = serviceWith(DrugReferenceTestSupport.shippedServiceWithGroups()).search(patient, SCREEN);

		assertTrue(answer.isAnsweredByTheModule(), "was: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains(" a caution (") || answer.getAnswer().contains(" each a caution ("),
			"precondition: the screen counted cautions: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains("Also covered by those findings"), "was: " + answer.getAnswer());
		assertEquals(3, answer.getSafetyWarnings().size(), "and the counted caution's chip is still published");
	}

	private static TestableService serviceWith(DrugReferenceService reference) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new NoModel());
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** The module answers this question itself; a model answer would be a failure of the precondition. */
	private static final class NoModel extends LlmProvider {

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse("The model's answer [1].", Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return search(numberedRecords, focusIndices, question, cacheScope, cacheSeedRecords, enumerateFindings,
				referenceRecords, drugsAlreadyOrdered);
		}

		@Override
		LlmEngine getActiveEngine() {
			return null;
		}
	}

	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return buildChart(patient, question);
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}
}
