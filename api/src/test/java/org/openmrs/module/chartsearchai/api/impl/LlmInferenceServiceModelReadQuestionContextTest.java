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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question the router's closed grammar finds no drug-safety reading in is read by the model, and a YES runs the
 * module's medication check (ADR Decision 489). On the public demo, "Any drugs that I should not give him?" ran no
 * check at all and the model answered "The records do not address any drug contraindications", beside two Major
 * interactions in his chart, while "What medications should he avoid?" ran one. The reading goes through the real
 * {@link LlmProvider#readMedicationSafetyQuestion}; only the engine underneath it is a stub answering YES or NO.
 */
public class LlmInferenceServiceModelReadQuestionContextTest extends BaseModuleContextSensitiveTest {

	/** Patient 7's warfarin order beside her aspirin order: a Major pair her screen relates. */
	private static final String WARFARIN_ORDER = "AnswerFromFindingsWarfarinOrderTestData.xml";

	/** The demo's own wording, which the router reads no drug-safety question in. */
	private static final String UNKNOWN_WORDING = "Any drugs that I should not give her?";

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			"true");
		executeDataSet(WARFARIN_ORDER);
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void aWordingTheRouterDoesNotKnowIsCheckedWhereTheModelReadsItAsAskingAboutHerDrugs() {
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertEquals(1, provider.engine.readings, "the model was asked to read the question once");
		assertEquals(1, provider.engine.avoidReadings, "and, after its YES, whether it asks which drugs to avoid");
		assertEquals(LlmProvider.MEDICATION_SAFETY_QUESTION_SYSTEM_PROMPT, provider.engine.readingPrompt);
		assertEquals(LlmProvider.oneBooleanResponseFormat(LlmProvider.MEDICATION_SAFETY_FIELD), provider.engine.readingFormat,
			"constrained to the format the reading was measured in");
		assertEquals(UNKNOWN_WORDING, provider.engine.readQuestion, "the model reads the question as typed");
		assertTrue(answer.isAnsweredByTheModule(), "the medication check answered it: " + answer.getAnswer());
		assertEquals(0, provider.answers, "no model answer was asked for");
		assertTrue(answer.getAnswer().contains(" interacts with active order "), "was: " + answer.getAnswer());
		assertFalse(answer.getSafetyWarnings().isEmpty(), "the check's findings are published");
	}

	/** Every wording that ran no screen on the demo's patient dd88589e, measured through the real injector on
	 *  2026-10-27 by the findings each put in the prompt. */
	@Test
	public void everyWordingThatRanNoCheckIsCheckedWhereTheModelReadsItAsOne() {
		for (String question : new String[] { "any drugs that i should not give her?",
				"Are there any drugs I should not give her?", "Any meds she can't take?",
				"Anything I should avoid giving this patient?", "Are any of her meds a bad combination?" }) {
			RecordingProvider provider = new RecordingProvider("YES");
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertTrue(answer.isAnsweredByTheModule(), question + " was: " + answer.getAnswer());
			assertFalse(answer.getSafetyWarnings().isEmpty(), question);
		}
	}

	/** A question the model reads as asking which drugs not to give her is answered as the router's own such question,
	 *  which says first that the check lists no drug to avoid (ADR Decision 491). "What shouldn't I prescribe for this
	 *  patient?" was answered as a screen, with no such sentence. */
	@Test
	public void aQuestionReadAsAskingWhichDrugsToAvoidSaysTheCheckListsNone() {
		RecordingProvider provider = new RecordingProvider("AVOID");
		ChartAnswer answer = serviceWith(provider).search(patient, "What shouldn't I prescribe for this patient?");

		assertTrue(answer.isAnsweredByTheModule(), "was: " + answer.getAnswer());
		assertTrue(answer.getAnswer().split("\n")[0].endsWith("This check does not list other drugs to avoid."), "was: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains(" interacts with active order "), "and states the findings: "
				+ answer.getAnswer());
	}

	/** And one it reads as asking about the drugs she takes keeps the screen's own lead. */
	@Test
	public void aQuestionReadAsAskingAboutHerDrugsKeepsTheScreensLead() {
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).search(patient, "Are any of her meds a bad combination?");

		assertTrue(answer.isAnsweredByTheModule(), "was: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains("This check does not list other drugs to avoid."), "was: " + answer.getAnswer());
	}

	@Test
	public void theStreamingPathReadsTheQuestionTheSameWay() {
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).searchStreaming(patient, UNKNOWN_WORDING, token -> { });

		assertEquals(1, provider.engine.readings);
		assertTrue(answer.isAnsweredByTheModule(), "was: " + answer.getAnswer());
		assertEquals(0, provider.answers);
	}

	@Test
	public void aNoLeavesTheQuestionAsTheRouterReadIt() {
		RecordingProvider provider = new RecordingProvider("NO");
		ChartAnswer answer = serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertEquals(1, provider.engine.readings);
		assertEquals(0, provider.engine.avoidReadings, "the second call follows a YES only");
		assertFalse(answer.isAnsweredByTheModule());
		assertEquals(1, provider.answers, "the model answered it, as before");
		assertTrue(answer.getSafetyWarnings().isEmpty(), "and no check ran: " + answer.getSafetyWarnings());
	}

	@Test
	public void anUnreadableReplyLeavesTheQuestionAsTheRouterReadIt() {
		RecordingProvider provider = new RecordingProvider("not json");
		ChartAnswer answer = serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertFalse(answer.isAnsweredByTheModule());
		assertEquals(1, provider.answers);
	}

	@Test
	public void aFailedEngineLeavesTheQuestionAsTheRouterReadIt() {
		RecordingProvider provider = new RecordingProvider(null);
		ChartAnswer answer = serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertFalse(answer.isAnsweredByTheModule());
		assertEquals(1, provider.answers);
	}

	@Test
	public void aQuestionTheRouterReadsAsACheckIsNotReadByTheModel() {
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).search(patient, "Are her current medications safe to take together?");

		assertEquals(0, provider.engine.readings);
		assertTrue(answer.isAnsweredByTheModule());
	}

	/** A question whose screen ran is not read again, though the module does not compose its answer: the model is handed
	 *  the screen's findings, and a second reading would only swap its answer for the module's. */
	@Test
	public void aQuestionWhoseScreenRanIsNotReadByTheModel() {
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).search(patient, "What drugs are dangerous for her?");

		assertEquals(0, provider.engine.readings);
		assertFalse(answer.isAnsweredByTheModule(), "precondition: the module did not compose it");
		assertTrue(provider.lastRecords.contains("interacts with active order"), "the screen's findings reached the model");
	}

	/**
	 * A food, a drink or her diet asked beside her medications asks nothing of her medications' own safety, so the model is
	 * not asked to read it and no screen is run, whatever it would answer (ADR Decision 492). On the 3.7.1 standalone the
	 * model read "Is orange juice ok with her meds?" and "Oranges: any interactions with her meds?" as YES, and the screen
	 * of her orders answered each.
	 */
	@Test
	public void aFoodOrDrinkAskedBesideHerMedicationsIsNotReadByTheModel() {
		for (String question : new String[] { "Is orange juice ok with her meds?", "Oranges: any interactions with her meds?",
				"Can she eat oranges with her ARVs?", "Is coffee bad for her meds?", "Can she have tea with her pills?",
				"Is her diet ok with her medicines?", "Should she avoid any foods?" }) {
			RecordingProvider provider = new RecordingProvider("YES");
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.engine.readings, question + " is not read by the model");
			assertFalse(answer.isAnsweredByTheModule(), question + " is not answered with the screen: " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * A food or an activity asked beside one of her drugs, which the injector reads as a question of how she takes that drug
	 * and nothing of its safety (ADR Decisions 464, 478), is not read again by the model (ADR Decision 500): on the 3.7.1
	 * standalone the model read "Can she eat oranges with her nevirapine?" and "Can she drive on nevirapine?" as YES, and
	 * the screen of her orders answered each with every chip of it.
	 */
	@Test
	public void aFoodOrActivityAskedBesideHerDrugIsNotReadByTheModel() {
		for (String question : new String[] { "Can she eat oranges with her warfarin?", "Can she drive on warfarin?",
				"Can she drink coffee while on her aspirin?" }) {
			RecordingProvider provider = new RecordingProvider("YES");
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.engine.readings, question + " is not read by the model");
			assertFalse(answer.isAnsweredByTheModule(), question + " is not answered with the screen: " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * Continuing her diet or a routine beside her medications asks nothing of their own safety (ADR Decision 510): on the
	 * 3.7.1 standalone "Ok to continue her diet with her meds?" was answered with the screen of her orders, every chip of it,
	 * on all 15 patients.
	 */
	@Test
	public void continuingHerDietOrARoutineBesideHerMedicationsIsNoScreen() {
		for (String question : new String[] { "Ok to continue her diet with her meds?", "Can she continue her exercise with her meds?",
				"Is it ok to keep her diet with her medications?", "Can she stay on her diet while on her ARVs?" }) {
			RecordingProvider provider = new RecordingProvider("YES");
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.engine.readings, question + " is not read by the model");
			assertFalse(answer.isAnsweredByTheModule(), question + " is not answered with the screen: " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * An activity, a meal or her tolerance asked beside her medications as a whole asks nothing of their safety together
	 * (ADR Decision 511): on the 3.7.1 standalone "Can she exercise while on her meds?", "Can she drive on her meds?", "Can
	 * she take her meds with food?", "Can she fast with her meds?" and "Is she tolerating her meds?" were each answered with
	 * the screen of her orders, every chip of it, on all 15 patients.
	 */
	@Test
	public void anActivityOrAMealBesideHerMedicationsIsNoScreen() {
		for (String question : new String[] { "Can she exercise while on her meds?", "Can she drive on her meds?",
				"Can she take her meds with food?", "Can she fast with her meds?", "Is she tolerating her meds?",
				"Can she work while taking her medications?", "Should she take her pills after meals?" }) {
			RecordingProvider provider = new RecordingProvider("YES");
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.engine.readings, question + " is not read by the model");
			assertFalse(answer.isAnsweredByTheModule(), question + " is not answered with the screen: " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	@Test
	public void aQuestionNamingADrugIsNotReadByTheModel() {
		RecordingProvider provider = new RecordingProvider("YES");
		serviceWith(provider).search(patient, "Can I give her ibuprofen?");

		assertEquals(0, provider.engine.readings, "the drug it names is already checked");
	}

	@Test
	public void theModelsOwnAnswerStillReadsTheQuestionAsTyped() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			"false");
		RecordingProvider provider = new RecordingProvider("YES");
		serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertEquals(1, provider.answers);
		assertEquals(UNKNOWN_WORDING, provider.lastQuestion, "the model is asked the clinician's question");
		assertTrue(provider.lastRecords.contains("interacts with active order"),
			"and handed the check's findings: " + provider.lastRecords);
	}

	@Test
	public void switchedOffTheModelIsNotAsked() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_READ_QUESTIONS_WITH_THE_MODEL, "false");
		RecordingProvider provider = new RecordingProvider("YES");
		ChartAnswer answer = serviceWith(provider).search(patient, UNKNOWN_WORDING);

		assertEquals(0, provider.engine.readings);
		assertFalse(answer.isAnsweredByTheModule());
	}

	private static TestableService serviceWith(RecordingProvider provider) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(
			DrugReferenceTestSupport.ddinterServiceWithGroups()));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterServiceWithGroups()));
		return service;
	}

	/** Records the answer calls and hands the reading to a stub engine; the reading itself is production's. */
	private static final class RecordingProvider extends LlmProvider {

		private final ReadingEngine engine;

		private int answers;

		private String lastQuestion;

		private String lastRecords;

		private RecordingProvider(String verdict) {
			this.engine = new ReadingEngine(verdict);
		}

		@Override
		LlmEngine getActiveEngine() {
			return engine;
		}

		private LlmResponse record(String numberedRecords, String question) {
			answers++;
			lastQuestion = question;
			lastRecords = numberedRecords;
			return new LlmResponse("The model's answer [1].", Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return record(numberedRecords, question);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			tokenConsumer.accept("The model's answer [1].");
			return record(numberedRecords, question);
		}
	}

	/** Answers the first reading call with {@code verdict} — YES, NO, or AVOID for a YES whose second call reads it as
	 *  asking which drugs to avoid — or with {@code verdict} verbatim where it is none of those, or fails it where
	 *  {@code verdict} is null; and the second call with whether {@code verdict} is AVOID. */
	private static final class ReadingEngine implements LlmEngine {

		private final String verdict;

		/** The first call's, whether the question asks about drug safety at all. */
		private int readings;

		/** The second call's, made only after a YES. */
		private int avoidReadings;

		private String readingPrompt;

		private String readQuestion;

		private ObjectNode readingFormat;

		private ReadingEngine(String verdict) {
			this.verdict = verdict;
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ObjectNode responseFormat) {
			if (LlmProvider.DRUGS_TO_AVOID_QUESTION_SYSTEM_PROMPT.equals(systemPrompt)) {
				avoidReadings++;
				return new InferenceResult("{\"" + LlmProvider.DRUGS_TO_AVOID_FIELD + "\": " + "AVOID".equals(verdict) + "}",
						1, 1, 0);
			}
			readings++;
			readingPrompt = systemPrompt;
			readQuestion = userMessage;
			readingFormat = responseFormat;
			if (verdict == null) {
				throw new IllegalStateException("engine down");
			}
			String text = "YES".equals(verdict) || "NO".equals(verdict) || "AVOID".equals(verdict)
					? "{\"" + LlmProvider.MEDICATION_SAFETY_FIELD + "\": " + !"NO".equals(verdict) + "}" : verdict;
			return new InferenceResult(text, 1, 1, 0);
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			throw new AssertionError("only the structured reading reaches this engine");
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured reading reaches this engine");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer) {
			throw new AssertionError("only the structured reading reaches this engine");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer, String cacheScope, String cacheSeed, ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured reading reaches this engine");
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
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
