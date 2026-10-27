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
 * Scores {@link LlmProvider#asksAboutMedicationSafety} — its prompt, response format and verdict parsing, all
 * production's — against a real model over a labelled set of questions (ADR Decision 489). A wording the router does
 * not know is a new row here, not a new rule in the router.
 *
 * <p>Two gates, matching how the reading is used. Every drug-safety question must reach the medication check: the
 * router reads it as a screen ({@link QueryScopeRouter#isInteractionScreening}, what runs the check for a question naming
 * no drug) or the model answers YES. And no other question may be read as one by the model, since a YES replaces the model's
 * answer with the module's check. Opt-in: {@code -Dchartsearchai.medication.safety.question.test=true}, against the
 * server {@code -Dchartsearchai.medication.safety.question.endpoint} names.
 */
public class MedicationSafetyQuestionEvalTest {

	private static final String ENABLE_PROPERTY = "chartsearchai.medication.safety.question.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.medication.safety.question.endpoint";

	/** Questions naming no drug that ask whether her drugs are safe, interact, or should be avoided, stopped or not given —
	 *  the population the reading serves: a question naming a drug is checked for that drug whatever its wording. */
	private static final String[] ASKS_ABOUT_DRUG_SAFETY = { "Does he have any drug contraindications?",
			"Does he have any drug interactions?", "any drugs that i should not give him?",
			"Are there any drugs I should not give him?", "Is there anything I shouldn't prescribe him?",
			"What medications should he avoid?", "Which drugs are contraindicated for him?", "Any meds he can't take?",
			"Are his current meds safe together?", "Any problems with his medications?", "What drugs are dangerous for him?",
			"Anything I should avoid giving this patient?", "Can his medications be taken together?",
			"Should I stop any of his drugs?", "Check his meds for problems", "Is his drug regimen safe?",
			"Any red flags in his medication list?", "Do any of his medicines clash?", "Are any of his meds a bad combination?",
			"is there anything off limits for him medication-wise",
			"¿Hay algún medicamento que no deba darle?", "Y a-t-il des médicaments à éviter chez lui ?" };

	/** Questions that ask nothing of a drug's safety, medication questions among them. */
	private static final String[] ASKS_SOMETHING_ELSE = { "What is his blood pressure?", "What medications is he on?",
			"List his active drug orders", "When was nevirapine started?", "What dose of lamivudine is he taking?",
			"Why is he on rifampicin?", "Has he had a CD4 count?", "Does he have TB?", "Who prescribed his ARVs?",
			"How long has he been on Retrovir?", "Is he allergic to anything?", "What vaccines has he had?",
			"Did he miss any appointments?", "How is he interacting with his care team?", "Is his weight going down?" };

	@Test
	public void everyDrugSafetyQuestionReachesTheCheckAndNoOtherIsReadAsOne() {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint), "Skipping: no LLM at " + endpoint);
		LlmProvider provider = new EndpointProvider(endpoint);

		List<String> missed = new ArrayList<String>();
		for (String question : ASKS_ABOUT_DRUG_SAFETY) {
			boolean router = QueryScopeRouter.isInteractionScreening(question);
			Boolean model = provider.asksAboutMedicationSafety(question);
			System.out.println("MEDICATION-SAFETY-EVAL safety | router=" + router + " model=" + model + " | " + question);
			if (!router && !Boolean.TRUE.equals(model)) {
				missed.add(question);
			}
		}
		List<String> misread = new ArrayList<String>();
		for (String question : ASKS_SOMETHING_ELSE) {
			Boolean model = provider.asksAboutMedicationSafety(question);
			System.out.println("MEDICATION-SAFETY-EVAL other  | model=" + model + " | " + question);
			if (Boolean.TRUE.equals(model)) {
				misread.add(question);
			}
		}
		assertTrue(missed.isEmpty(), "drug-safety questions neither the router nor the model read as one: " + missed);
		assertTrue(misread.isEmpty(), "questions the model read as drug-safety questions: " + misread);
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
