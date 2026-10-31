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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Scores {@link LlmProvider#readMedicationSafetyQuestion} — its prompt, response format and parsing, all production's —
 * against a real model over a labelled set of questions (ADR Decisions 489, 491). A wording the router does not know is a
 * new row here, not a new rule in the router.
 *
 * <p>Three gates, matching how the reading is used. Every drug-safety question naming no drug must reach the medication
 * check: the router reads a screen in it ({@link QueryScopeRouter#isInteractionScreening} — a proxy for the screen the
 * injector runs, which {@code LlmInferenceServiceModelReadQuestionContextTest} drives) or the model reads it as one. No
 * other question may be read as one, since that replaces the model's answer with the module's check. And no question
 * about the drugs she takes may be read as asking which drugs not to give her, which would open its answer by saying
 * the check lists no drug to avoid. The converse — an avoid question read as one about her drugs — keeps the screen's
 * own lead, as before Decision 491, and is printed rather than failed: on 2026-10-27 it was 2 of 12. Wordings neither
 * the router nor the model reaches, measured so, are in {@link #KNOWN_UNREACHED} and printed rather than failed, so
 * the suite says what it does not cover instead of hiding it. Opt-in: {@code -Dchartsearchai.medication.safety.question.test=true}, against
 * the server {@code -Dchartsearchai.medication.safety.question.endpoint} names.
 */
public class MedicationSafetyQuestionEvalTest {

	private static final String ENABLE_PROPERTY = "chartsearchai.medication.safety.question.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.medication.safety.question.endpoint";

	/** Questions naming no drug that ask which drugs not to give or prescribe her. */
	private static final String[] ASKS_WHICH_DRUGS_TO_AVOID = { "any drugs that i should not give him?",
			"Are there any drugs I should not give him?", "Is there anything I shouldn't prescribe him?",
			"What medications should he avoid?", "Which drugs are contraindicated for him?", "Any meds he can't take?",
			"What drugs are dangerous for him?", "Anything I should avoid giving this patient?",
			"What shouldn't I prescribe for this patient?", "is there anything off limits for him medication-wise",
			"¿Hay algún medicamento que no deba darle?", "Y a-t-il des médicaments à éviter chez lui ?" };

	/** Questions naming no drug that ask whether the drugs she takes are safe, interact, or should be stopped. */
	private static final String[] ASKS_ABOUT_HER_DRUGS = { "Does he have any drug interactions?",
			"Are his current meds safe together?", "Any problems with his medications?",
			"Can his medications be taken together?", "Should I stop any of his drugs?", "Check his meds for problems",
			"Is his drug regimen safe?", "Any red flags in his medication list?", "Do any of his medicines clash?",
			"Are any of his meds a bad combination?", "Can this patient's medicines be given safely at the same time?",
			"Is it ok to keep giving all of these together?" };

	/** Drug-safety questions that read either way, scored for reaching the check alone. */
	private static final String[] ASKS_EITHER = { "Does he have any drug contraindications?",
			"Anything I need to watch out for before prescribing?" };

	/** Drug-safety questions measured on 2026-10-27 to reach neither the router nor the model (ADR Decision 491). */
	private static final java.util.Set<String> KNOWN_UNREACHED = new java.util.HashSet<String>(java.util.Arrays.asList(
		"Is it ok to keep giving all of these together?", "Anything I need to watch out for before prescribing?"));

	/** Questions that ask nothing of a drug's safety, medication questions among them. */
	private static final String[] ASKS_SOMETHING_ELSE = { "What is his blood pressure?", "What medications is he on?",
			"List his active drug orders", "When was nevirapine started?", "What dose of lamivudine is he taking?",
			"Why is he on rifampicin?", "Has he had a CD4 count?", "Does he have TB?", "Who prescribed his ARVs?",
			"How long has he been on Retrovir?", "Is he allergic to anything?", "What vaccines has he had?",
			"Did he miss any appointments?", "How is he interacting with his care team?", "Is his weight going down?",
			// Something else asked beside her medications, which a screen of them does not answer (ADR Decision 549): each
			// was read as a drug-safety question on 2026-11-01, and its answer replaced by the screen. Whether they cause
			// a symptom the model reads as one under any prompt measured, so the router knows those shapes instead.
			"Can she smoke on her meds?", "Can she take her meds at night?", "Can she get the COVID vaccine on her meds?",
			"Can she donate blood on her meds?", "Is she gaining weight on her ARVs?",
			"Can she eat oranges after taking her pills?" };

	@Test
	public void everyDrugSafetyQuestionReachesTheCheckReadAsItAsksAndNoOtherIsReadAsOne() {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint), "Skipping: no LLM at " + endpoint);
		LlmProvider provider = new EndpointProvider(endpoint);

		List<String> missed = new ArrayList<String>();
		List<String> misreadKind = new ArrayList<String>();
		score(provider, ASKS_WHICH_DRUGS_TO_AVOID, LlmProvider.MedicationSafetyReading.DRUGS_TO_AVOID, missed, misreadKind);
		score(provider, ASKS_ABOUT_HER_DRUGS, LlmProvider.MedicationSafetyReading.HER_MEDICATIONS, missed, misreadKind);
		score(provider, ASKS_EITHER, null, missed, misreadKind);
		List<String> misread = new ArrayList<String>();
		for (String question : ASKS_SOMETHING_ELSE) {
			LlmProvider.MedicationSafetyReading model = provider.readMedicationSafetyQuestion(question);
			System.out.println("MEDICATION-SAFETY-EVAL other  | model=" + model + " | " + question);
			if (model != null && model != LlmProvider.MedicationSafetyReading.OTHER) {
				misread.add(question);
			}
		}
		assertTrue(missed.isEmpty(), "drug-safety questions neither the router nor the model read as one: " + missed);
		assertTrue(misread.isEmpty(), "questions the model read as drug-safety questions: " + misread);
		assertTrue(misreadKind.isEmpty(), "questions about her drugs the model read as asking which to avoid: " + misreadKind);
	}

	/** Scores one labelled list; {@code kind} null scores reaching the check alone. */
	private static void score(LlmProvider provider, String[] questions, LlmProvider.MedicationSafetyReading kind,
			List<String> missed, List<String> misreadKind) {
		for (String question : questions) {
			boolean router = QueryScopeRouter.isInteractionScreening(question);
			LlmProvider.MedicationSafetyReading model = provider.readMedicationSafetyQuestion(question);
			System.out.println("MEDICATION-SAFETY-EVAL " + (kind == null ? "either" : kind) + " | router=" + router + " model="
					+ model + " | " + question);
			boolean readAsOne = model == LlmProvider.MedicationSafetyReading.HER_MEDICATIONS
					|| model == LlmProvider.MedicationSafetyReading.DRUGS_TO_AVOID;
			if (!router && !readAsOne) {
				if (KNOWN_UNREACHED.contains(question)) {
					System.out.println("MEDICATION-SAFETY-EVAL known gap, unreached | " + question);
				}
				else {
					missed.add(question);
				}
			}
			if (kind == LlmProvider.MedicationSafetyReading.HER_MEDICATIONS
					&& model == LlmProvider.MedicationSafetyReading.DRUGS_TO_AVOID) {
				misreadKind.add(question);
			}
			if (kind == LlmProvider.MedicationSafetyReading.DRUGS_TO_AVOID
					&& model == LlmProvider.MedicationSafetyReading.HER_MEDICATIONS) {
				System.out.println("MEDICATION-SAFETY-EVAL avoid read as about her drugs, keeps the screen's lead | "
						+ question);
			}
		}
	}

	/** Production's provider over an engine posting to the tester's server, so only the transport is the test's. */
	private static final class EndpointProvider extends LlmProvider {

		private final LlmEngine engine;

		private EndpointProvider(String endpoint) {
			this.engine = new EndpointEngine(endpoint);
		}

		@Override
		LlmEngine getActiveEngine() {
			return engine;
		}

		@Override
		protected int getTimeoutSeconds() {
			return MEDICATION_SAFETY_QUESTION_TIMEOUT_SECONDS;
		}
	}

	private static final class EndpointEngine implements LlmEngine {

		private final String endpoint;

		private EndpointEngine(String endpoint) {
			this.endpoint = endpoint;
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ObjectNode responseFormat) {
			try {
				return new InferenceResult(
						LlmEndpointTestSupport.complete(endpoint, systemPrompt, userMessage, 32, responseFormat), 0, 0, 0);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			throw new AssertionError("only the structured reading is scored here");
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured reading is scored here");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer) {
			throw new AssertionError("only the structured reading is scored here");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer, String cacheScope, String cacheSeed, ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured reading is scored here");
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
}
