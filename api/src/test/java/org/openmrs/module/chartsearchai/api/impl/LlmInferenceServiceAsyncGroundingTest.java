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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Locks the async-grounding seam on {@link LlmInferenceService#searchStreaming}: the
 * 6-arg overload's {@code ungroundedAnswerConsumer} must fire exactly once, BEFORE the
 * grounding verifier runs, carrying the complete answer whose references have no
 * grounding verdicts yet — that is what lets the REST layer emit its {@code done}
 * event without waiting out the Tier-2 grounding tail. The returned {@link ChartAnswer}
 * still carries the verifier's verdicts, exactly as the synchronous path does.
 */
public class LlmInferenceServiceAsyncGroundingTest {

	private TestableService service;

	private RecordingVerifier verifier;

	/** Interleaving log: "consumer" and "verify" entries prove the fire-before-grounding order. */
	private final List<String> sequence = new ArrayList<String>();

	@BeforeEach
	public void setUp() {
		service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		// their searchStreaming injects drug-reference records unconditionally; pass through
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart inject(
					org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart,
					org.openmrs.Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new org.openmrs.module.chartsearchai.reference.DrugSafetyValidator() {

			@Override
			public java.util.List<org.openmrs.module.chartsearchai.reference.SafetyWarning> validate(
					String answer, String question, org.openmrs.Patient patient) {
				return java.util.Collections.emptyList();
			}
		});
		verifier = new RecordingVerifier();
		service.setCitationGroundingVerifier(verifier);
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	@Test
	public void searchStreaming_firesUngroundedConsumerOnceBeforeGrounding() {
		List<ChartAnswer> seen = new ArrayList<ChartAnswer>();

		ChartAnswer finalAnswer = service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { },
				ungrounded -> {
					sequence.add("consumer");
					seen.add(ungrounded);
				});

		assertEquals(1, seen.size(), "ungrounded-answer consumer must fire exactly once");
		assertEquals(Arrays.asList("consumer", "verify"), sequence,
				"the consumer must fire BEFORE the grounding verifier runs — that ordering is "
						+ "the entire point of the async seam");

		ChartAnswer ungrounded = seen.get(0);
		assertEquals("Active Tuberculosis [8]. CD4 988.0 [9].", ungrounded.getAnswer(),
				"consumer must carry the complete answer text");
		assertEquals(2, ungrounded.getReferences().size());
		for (RecordReference ref : ungrounded.getReferences()) {
			assertNull(ref.getGrounded(),
					"references handed to the consumer must carry no verdicts yet");
		}

		assertEquals(2, finalAnswer.getReferences().size());
		for (RecordReference ref : finalAnswer.getReferences()) {
			assertEquals(Boolean.TRUE, ref.getGrounded(),
					"the returned answer must still carry the verifier's verdicts");
		}
	}

	@Test
	public void searchStreaming_fiveArgOverloadStillGroundsSynchronously() {
		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { });

		assertEquals(1, verifier.invocations, "5-arg overload must still ground before returning");
		for (RecordReference ref : answer.getReferences()) {
			assertEquals(Boolean.TRUE, ref.getGrounded());
		}
	}

	@Test
	public void searchStreaming_groundingDisabledStillFiresConsumerAndSkipsVerifier() {
		service.groundingEnabled = false;
		List<ChartAnswer> seen = new ArrayList<ChartAnswer>();

		ChartAnswer finalAnswer = service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { }, seen::add);

		assertEquals(1, seen.size(),
				"consumer contract is 'the answer is complete', not 'grounding will follow'");
		assertEquals(0, verifier.invocations, "grounding disabled -> verifier never runs");
		for (RecordReference ref : finalAnswer.getReferences()) {
			assertNull(ref.getGrounded(), "grounding disabled -> verdicts stay null");
		}
	}

	/** Context-free service: GP-backed resolvers overridden, grounding forced on by default. */
	private final class TestableService extends LlmInferenceService {

		boolean groundingEnabled = true;

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveQueryStoreEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return groundingEnabled;
		}
	}

	private final class RecordingVerifier extends CitationGroundingVerifier {

		int invocations;

		@Override
		public List<RecordReference> verify(String answer, List<RecordReference> references,
				List<RecordMapping> mappings) {
			invocations++;
			sequence.add("verify");
			List<RecordReference> annotated = new ArrayList<RecordReference>();
			for (RecordReference ref : references) {
				annotated.add(ref.withGrounded(Boolean.TRUE));
			}
			return annotated;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(8, "condition", "00000000-0000-0000-0000-000000000008", null),
					new RecordMapping(9, "obs", "00000000-0000-0000-0000-000000000009", null));
			return new PatientChart("8. Tuberculosis\n9. CD4 988.0", mappings,
					Collections.<Integer>emptyList());
		}

		// searchStreaming now reads the pipeline mode (via the same gate as warmup) to decide the
		// query-path KV cache scope; without a Context this stub must answer directly.
		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		// Production calls the scope-aware 6-arg overload. Override it (scope is irrelevant to the
		// event-ordering assertions, so it is ignored).
		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return new LlmResponse("Active Tuberculosis [8]. CD4 988.0 [9].", Arrays.asList(8, 9));
		}
	}
}
