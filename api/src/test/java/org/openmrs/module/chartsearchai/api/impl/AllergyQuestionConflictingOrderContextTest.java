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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question asking only for the patient's allergies states, in the answer, which of her active orders
 * conflict with them, rather than leaving that to a drug-safety chip beside a list the clinician asked
 * for.
 *
 * <p><b>The case.</b> <em>"any allergies?"</em>, asked of a patient prescribed two drugs she is recorded
 * as allergic to, came back with a complete allergy list and two red contraindication chips repeating it,
 * each tagged "About a current medication", and a note about what the safety checks covered. The one fact
 * the chips carried that the list did not — that two of the allergens are drugs she is prescribed — was
 * stated nowhere a clinician reading the answer would read it.
 *
 * <p><b>Everything but the model is real</b>: patient 7 of the standard dataset, her aspirin order 111
 * read through the real {@code OrderService}, a free-text aspirin allergy recorded on her; the real
 * injector and validator over a verbatim slice of the shipped knowledge base; and the real
 * {@link LlmInferenceService#search} and {@code searchStreaming}. The model is a recorder.
 */
public class AllergyQuestionConflictingOrderContextTest extends BaseModuleContextSensitiveTest {

	private static final String SLICE = "chartsearchai-test/ddi-listed-medications-proposal.json";

	private static final String ALLERGY_QUESTION = "any allergies?";

	private static final String MODEL_ANSWER = "Yes — the patient has a recorded allergy to Aspirin [1].";

	/** Her order 111's display, as the chart spells it — the name a clinician finds in her medication list. */
	private static final String ORDER_DISPLAY = "ASPIRIN";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
	}

	private static TestableService serviceAnswering(String modelAnswer) throws IOException {
		DrugReferenceService references = DrugReferenceTestSupport.ddiFixtureService(SLICE);
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(DrugReferenceTestSupport.obsRecord(1, "BP 120/80")));
		service.setLlmProvider(new Recorder(modelAnswer));
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(references));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(references));
		return service;
	}

	/** The one contraindication chip about her aspirin order, which every case here raises. */
	private static SafetyWarning theAspirinChip(ChartAnswer answer) {
		List<SafetyWarning> about = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()) && chip.isAboutACurrentMedication()) {
				about.add(chip);
			}
		}
		assertEquals(1, about.size(), "precondition: exactly one current-medication contraindication chip, "
				+ "chips were: " + answer.getSafetyWarnings());
		return about.get(0);
	}

	@Test
	public void search_anAllergyQuestionStatesTheConflictingOrderInTheAnswer() throws IOException {
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER + " Currently prescribed: " + ORDER_DISPLAY + ". " + chip.getDetail(),
				answer.getAnswer(),
				"the answer names her order as her chart spells it, then states the chip's own finding");
		assertTrue(chip.isStatedInTheAnswer(),
				"the chip says the answer states it, so a client need not render it a second time");
	}

	@Test
	public void searchStreaming_theFinalAnswerStatesTheConflictingOrderToo() throws IOException {
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).searchStreaming(patient, ALLERGY_QUESTION,
				token -> { }, reasoning -> { }, citations -> { }, early::add);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER + " Currently prescribed: " + ORDER_DISPLAY + ". " + chip.getDetail(),
				answer.getAnswer(), "the streaming path completes the final answer the same way");
		assertTrue(chip.isStatedInTheAnswer(), "and marks the chip the same way");
		assertEquals(1, early.size(), "precondition: the early done fired");
		assertEquals(MODEL_ANSWER, early.get(0).getAnswer(),
				"the early done carries no chips, so it states nothing about them either");
	}

	@Test
	public void aMedicationQuestionKeepsTheFindingAsAChip() throws IOException {
		String question = "Are there any drug interactions with her current medications?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"a question about her medications asked for the drug-safety reading, so the chip stays the carrier");
		assertFalse(chip.isStatedInTheAnswer(), "and says the answer does not state it");
	}

	@Test
	public void anAllergyQuestionThatAlsoAsksAboutHerMedicationsKeepsTheFindingAsAChip() throws IOException {
		String question = "Is she allergic to any of her medications?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"asking whether her medications conflict with her allergies IS the drug-safety question");
		assertFalse(chip.isStatedInTheAnswer(), "so the chip stays the carrier");
	}

	@Test
	public void anAllergyQuestionAskingForASafetyReadingKeepsTheFindingAsAChip() throws IOException {
		String question = "Are any of her allergies a safety concern?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"a question asking whether something is safe asked for the drug-safety reading");
		assertFalse(chip.isStatedInTheAnswer(), "so the chip stays the carrier");
	}

	@Test
	public void aFindingNoOrderWasResolvedForKeepsEveryFindingAsAChip() throws IOException {
		// The question names her allergen, which puts the drug in play: its finding is the drug-in-play
		// arm's, which resolves no order, so the answer cannot name one and states nothing at all.
		String question = "Is she allergic to aspirin?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the question raised a finding, chips "
				+ "were: " + answer.getSafetyWarnings());
		assertEquals(MODEL_ANSWER, answer.getAnswer(), "a finding the answer cannot name an order for is not stated");
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(chip.isStatedInTheAnswer(), "and no chip says it is, was: " + chip);
		}
	}

	/** A recorder standing in for the model: answers {@code answer}. */
	private static final class Recorder extends LlmProvider {

		private final String answer;

		private Recorder(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			tokenConsumer.accept(answer);
			return search(numberedRecords, focusIndices, question, enumerateFindings, referenceRecords);
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

		private final RecordMapping[] records;

		private StubStrategy(RecordMapping... records) {
			this.records = records;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(records);
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
