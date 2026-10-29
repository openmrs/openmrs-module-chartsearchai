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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A list the model writes is shown as its own heading and how many items it lists, the items collapsed under it (ADR
 * Decision 528): every word in view is the model's own heading or a count of what the answer lists, so nothing in view
 * can say what the answer does not. Asking the model for a one-sentence summary instead was measured and declined —
 * replayed on the demo's model over 24 captured prompts, at least 5 summaries contradicted their own answer. The answers
 * here are that replay's, word for word.
 */
public class ModelAnswerListCollapseContextTest extends BaseModuleContextSensitiveTest {

	private Patient patient;

	@BeforeEach
	public void patient() {
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void aListOnLinesOfItsOwnIsItsHeadingAndItsCount() {
		assertCollapsed("The patient is on the following medications:\n1. Stavudine (Order status: in force) [1]\n"
				+ "2. Nevirapine (Order status: in force) [1]\n3. Lamivudine (Order status: in force) [1]",
			"The patient is on the following medications — 3 listed.");
	}

	@Test
	public void aNumberedListOnTheHeadingsLineIsItsHeadingAndItsCount() {
		assertCollapsed("The patient has the following conditions: 1. Failure to thrive, status ACTIVE, onset 2006-05-08 [1]. "
				+ "2. Verruca planus, status ACTIVE, onset 2006-05-18 [1]. 3. HIV infected [1].",
			"The patient has the following conditions — 3 listed.");
	}

	@Test
	public void aCommaListAfterItsHeadingIsItsHeadingAndItsCount() {
		assertCollapsed("The patient is on the following medications: Nevirapine [1], Lamivudine [1], Stavudine [1], "
				+ "Nevirapine [1], Stavudine [1], and Lamivudine / nevirapine / stavudine [1].",
			"The patient is on the following medications — 6 listed.");
	}

	@Test
	public void labelledValuesOnLinesOfTheirOwnAreItemsToo() {
		assertCollapsed("Most recent vital signs are:\nSystolic blood pressure: 80 mmHg [1]\nDiastolic blood pressure: 60 mmHg [1]\n"
				+ "Pulse: 80 beats/min [1]\nTemperature: 36.3 C [1]", "Most recent vital signs are — 4 listed.");
	}

	/** One sentence is already what a client shows, a label's value with commas is no list, and a list of one is no
	 *  list to collapse. */
	@Test
	public void aSentenceOrAListOfOneIsShownWhole() {
		for (String answer : new String[] { "The latest CD4 count is 443 cells/uL [1].", "No allergies are recorded.",
				"Blood pressure: 120/80 mmHg, pulse 80 beats/min, temperature 36.5 C [1].",
				"The patient has the following conditions:\n1. Lymphadenopathy [1]." }) {
			ChartAnswer searched = serviceWith(answer).search(patient, "What is in his chart?");
			assertNull(searched.getAnswerSummary(), "was: " + searched.getAnswer());
		}
	}

	private void assertCollapsed(String modelAnswer, String expected) {
		ChartAnswer searched = serviceWith(modelAnswer).search(patient, "What is in his chart?");
		assertFalse(searched.isAnsweredByTheModule(), "precondition: the model wrote it");
		assertEquals(expected, searched.getAnswerSummary(), "was: " + searched.getAnswer());
		assertFalse(searched.answerDetailsStateLimits(), "a model's list states no limits");

		List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer streamed = serviceWith(modelAnswer).searchStreaming(patient, "What is in his chart?", t -> { },
			r -> { }, c -> { }, early::add);
		assertEquals(expected, streamed.getAnswerSummary(), "the final done carries it");
		assertEquals(expected, early.get(0).getAnswerSummary(), "and the early done");
	}

	private static TestableService serviceWith(String answer) {
		DrugReferenceService reference = DrugReferenceTestSupport.ddinterServiceWithGroups();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new FixedAnswerProvider(answer));
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** The model, answering {@code answer} and citing the chart's one record. */
	private static final class FixedAnswerProvider extends LlmProvider {

		private final String answer;

		private FixedAnswerProvider(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			tokenConsumer.accept(answer);
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
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
		protected boolean resolveQueryScopedMode() {
			return false;
		}
	}

	/** One obs record, which the model's answer cites. */
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
