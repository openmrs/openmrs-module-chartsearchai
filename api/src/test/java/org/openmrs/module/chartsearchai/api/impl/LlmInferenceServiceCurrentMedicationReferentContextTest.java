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
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The chips an answer carries say which of them the module raised from one of the patient's own active
 * orders, on issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/527">#527</a>'s
 * own shape: two patients, one allergy, one sentence.
 *
 * <p><b>Why this is asked of the answer's chips and not of the injected record.</b> The wire publishes each
 * chip's {@link SafetyWarning#isAboutACurrentMedication()}, and on the model's path the chips an answer
 * carries come from the pass that has read the MODEL's answer — not from the pass the record the model read
 * was rendered from. That pass puts in play every drug the answer names that no record the answer is
 * attributable to names, and the drug-in-play arm raises such a drug's findings as a proposal's (see the
 * accessor). The issue's answer named the very drug its chip was about — <em>"Yes — an allergy is
 * recorded: Ibuprofen (drug allergen) [1]."</em> — so what the first case pins is that an answer naming
 * that drug leaves the published chip raised from her order. Two records the answer is attributable to
 * name it here, the allergy record it cites and the finding's own injected record, and either alone
 * keeps the drug out of play; take both out of the attributable corpus and the case reddens.
 *
 * <p><b>These cases are not the failing specification</b>: the flag was set this way before the issue, and
 * what the issue changed is that the wire carries it, which
 * {@code ChartSearchAiCurrentMedicationReferentTest} in the omod module specifies. They pin the premise that
 * key's meaning rests on, through the real pipeline.
 *
 * <p><b>Everything but the model is real</b>: patients 7 (active order 111, ASPIRIN) and 6 (no order) of
 * the standard dataset, each with a free-text aspirin allergy saved through {@code PatientService}, the
 * real injector and validator over the DDInter excerpt the tests load, and the real
 * {@link LlmInferenceService#search}. The chart the stub strategy returns carries the allergy record and,
 * for patient 7, her order's record. The model is a recorder answering the issue's recorded words.
 */
public class LlmInferenceServiceCurrentMedicationReferentContextTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN), nominated as the {@code allergy.concept.otherNonCoded} placeholder that
	 *  {@code AllergyValidator} requires behind a free-text allergen. */
	private static final int OTHER_NON_CODED_CONCEPT = 88;

	private static final int ALLERGY_RECORD = 1;

	private static final int ORDER_RECORD = 2;

	/** The allergen arm's identity sentence opens with these words, whichever arm reaches it. */
	private static final String IDENTITY_SENTENCE = "The patient has a recorded allergy to ";

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	/** The patient, with a free-text aspirin allergy recorded, and the chart record that allergy is. */
	private static RecordMapping allergicToAspirin(Patient patient) {
		String allergyUuid = DrugReferenceTestSupport.recordFreeTextAllergy(patient, OTHER_NON_CODED_CONCEPT,
			"Aspirin");
		return DrugReferenceTestSupport.allergyRecord(ALLERGY_RECORD, allergyUuid, "Allergy: Aspirin (drug)");
	}

	private static TestableService serviceWith(String modelAnswer, RecordMapping... chartRecords) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(chartRecords));
		service.setLlmProvider(new AnsweringProvider(modelAnswer));
		// One service behind both, as production autowires it (injectorWithSafety's javadoc).
		DrugReferenceService references = DrugReferenceTestSupport.ddinterServiceWithGroups();
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(references));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(references));
		return service;
	}

	/** The one chip carrying the allergen arm's identity sentence, asserting there is exactly one. */
	private static SafetyWarning identityChip(ChartAnswer answer) {
		SafetyWarning found = null;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType())
					&& chip.getDetail().startsWith(IDENTITY_SENTENCE)) {
				assertTrue(found == null, "precondition: one identity chip, chips were: "
						+ answer.getSafetyWarnings());
				found = chip;
			}
		}
		assertTrue(found != null, "precondition: the aspirin allergy raised its identity chip, chips were: "
				+ answer.getSafetyWarnings());
		return found;
	}

	/** Patient 7 on the issue's e30bc8f0 question: allergic to aspirin, and taking it. */
	private ChartAnswer anyAllergiesOfAPatientTakingIt() {
		Patient patient = Context.getPatientService().getPatient(7);
		RecordMapping allergy = allergicToAspirin(patient);
		String modelAnswer = "Yes — an allergy is recorded: Aspirin (drug) [" + ALLERGY_RECORD + "].";
		ChartAnswer answer = serviceWith(modelAnswer, allergy,
			DrugReferenceTestSupport.drugOrderRecord(ORDER_RECORD, "Aspirin 81mg", Boolean.TRUE, null))
				.search(patient, "Any allergies?");
		assertTrue(answer.getAnswer().startsWith(modelAnswer),
			"precondition: the answer names the drug its chip is about, citing the allergy record, as the "
					+ "issue's did: " + answer.getAnswer());
		return answer;
	}

	@Test
	public void aChipRaisedFromHerOwnOrderIsPublishedAsSoWhereTheAnswerNamesItsDrug() {
		SafetyWarning chip = identityChip(anyAllergiesOfAPatientTakingIt());

		assertTrue(chip.isAboutACurrentMedication(),
			"she takes the drug she is allergic to, and the module raised this chip from her order — an "
					+ "answer naming the drug that records it is attributable to already name is no proposal "
					+ "of it: " + chip.getDetail());
	}

	/**
	 * The issue's second row: the same allergy on a patient with no order of the drug, asked whether to
	 * give it. The chip's sentence is the first case's, byte for byte, and of what the wire carries only
	 * the referent differs.
	 */
	@Test
	public void theSameSentenceAboutADrugProposedToAPatientOnNoOrderOfItIsPublishedAsAProposal() {
		SafetyWarning current = identityChip(anyAllergiesOfAPatientTakingIt());

		Patient patient = Context.getPatientService().getPatient(6);
		RecordMapping allergy = allergicToAspirin(patient);
		SafetyWarning proposed = identityChip(serviceWith(
			"No — Aspirin should not be given: an allergy to it is recorded [" + ALLERGY_RECORD + "].", allergy)
				.search(patient, "Can I give her aspirin?"));

		assertEquals(current.getDetail(), proposed.getDetail(), "precondition: one sentence for both, as on "
				+ "the issue's two patients");
		assertEquals(current.getDrug(), proposed.getDrug(), "precondition: and one drug");
		assertFalse(proposed.isAboutACurrentMedication(),
			"a drug the question proposes for a patient on no order of it is not a medication she takes: "
					+ proposed.getDetail());
	}

	private static final class AnsweringProvider extends LlmProvider {

		private final String answer;

		private AnsweringProvider(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(ALLERGY_RECORD)));
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
