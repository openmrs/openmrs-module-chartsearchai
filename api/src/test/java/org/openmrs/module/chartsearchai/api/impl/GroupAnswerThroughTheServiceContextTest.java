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
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A group answer, through the service, names only the orders of the groups asked about — its lines, its chips, and no
 * sentence after it naming the rest (ADR Decision 633). On the demo replica, patient dd5558ed asked "Any interactions
 * between his ARVs and TB drugs?" was told "Efavirenz interacts with active order Rifampicin isoniazid pyrazinamide and
 * ethambutol (3 orders) and Sulfamethoxazole (5 orders)", and once that line named his TB drugs alone, ADR Decision 100's
 * check appended "Also covered by those findings and not named above: active order Sulfamethoxazole".
 */
public class GroupAnswerThroughTheServiceContextTest extends BaseModuleContextSensitiveTest {

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			"true");
		executeDataSet("GroupAnswerEfavirenzRhzeCotrimoxazoleOrdersTestData.xml");
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void aGroupAnswerNamesNoOrderOutsideTheGroupsInItsTextOrItsChips() {
		ChartAnswer answer = serviceWith(new DrugReferenceService()).search(patient,
			"Any interactions between his ARVs and TB drugs?");

		assertTrue(answer.isAnsweredByTheModule(), "was: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains("Efavirenz interacts with active order"), "precondition: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains("Sulfamethoxazole") || answer.getAnswer().contains("Also covered by those"),
			"was: " + answer.getAnswer());
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(chip.getDetail().contains("Sulfamethoxazole") || chip.getDetail().contains("Aspirin"), chip.getDetail());
		}
		// and the finding it states over his TB drugs alone is published, as stated
		assertTrue(answer.getSafetyWarnings().stream().anyMatch(chip -> chip.getDetail().startsWith(
			"Efavirenz interacts with active order") && chip.getDetail().contains("Pyrazinamide")), "its chips: "
					+ answer.getSafetyWarnings());
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
