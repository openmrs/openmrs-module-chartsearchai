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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Locks the progressive-reasoning seam on {@link LlmInferenceService#searchStreaming}
 * (the 6-arg overload). When {@code chartsearchai.progressiveReasoning.enabled} is on, a
 * fast stage-1 "preview" pass runs over only the querystore top-K focused chart and streams
 * its reasoning to the existing "thinking" channel BEFORE the unchanged full-chart answer
 * runs. This cuts perceived time-to-first-reasoning on a GPU-less host (the full chart is a
 * multi-thousand-token prefill; the focused chart is a few hundred) without changing the
 * committed answer.
 *
 * <p>Quality is preserved <em>by construction</em> and these tests pin every leg of that:
 * <ul>
 *   <li>the preview runs on the FOCUSED chart, the answer on the FULL chart;</li>
 *   <li>the preview uses a {@code null} KV-cache scope so it cannot read or overwrite the
 *       patient's full-chart KV entry;</li>
 *   <li>the preview's <em>answer</em> tokens are discarded — only its reasoning reaches the
 *       clinician — so the returned {@link ChartAnswer} is byte-identical to the full-chart
 *       call;</li>
 *   <li>when the gate is off, no focused chart is built and only the full-chart call runs.</li>
 * </ul>
 */
public class LlmInferenceServiceProgressiveReasoningTest {

	private static final String FULL_TEXT = "1. Full chart record A\n2. Full chart record B";

	private static final String FOCUSED_TEXT = "1. Focused top-K record";

	private TestableService service;

	private StubStrategy strategy;

	private StubProvider provider;

	/** Reasoning chunks in arrival order — proves preview reasoning precedes the final reasoning. */
	private final List<String> reasonings = new ArrayList<String>();

	/** Answer tokens the clinician actually sees — must contain ONLY the full-chart answer. */
	private final List<String> answerTokens = new ArrayList<String>();

	@BeforeEach
	public void setUp() {
		strategy = new StubStrategy();
		provider = new StubProvider();
		service = new TestableService();
		service.setChartBuildingStrategy(strategy);
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient) {
				return Collections.emptyList();
			}
		});
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private ChartAnswer run() {
		return service.searchStreaming(patient(), "any infections?",
				answerTokens::add, reasonings::add, citations -> { }, ungrounded -> { });
	}

	@Test
	public void searchStreaming_emitsPreviewReasoningFromFocusedChartBeforeFullAnswer() {
		service.progressiveEnabled = true;

		ChartAnswer answer = run();

		// Two LLM passes: stage-1 preview on the focused chart, then stage-2 on the full chart.
		assertEquals(Arrays.asList(FOCUSED_TEXT, FULL_TEXT), provider.texts,
				"progressive on must run the preview over the FOCUSED chart first, then the FULL chart");
		assertEquals(Arrays.asList(null, "uuid-1"), provider.scopes,
				"the preview must use a null KV scope (so it can't collide with the patient's "
						+ "full-chart KV); the full pass scopes to the patient UUID");
		assertEquals(1, strategy.buildFocusedChartCalls, "the focused chart must be built exactly once");

		assertEquals(Arrays.asList("PREVIEW-REASONING", "FULL-REASONING"), reasonings,
				"preview reasoning must reach the thinking channel BEFORE the final reasoning — "
						+ "that ordering is the entire point of progressive reasoning");

		// Quality preserved by construction: the preview answer is discarded; only the full-chart
		// answer is shown and returned.
		assertEquals(Collections.singletonList("FULL-ANSWER"), answerTokens,
				"the preview answer tokens must NOT reach the clinician — only the full-chart answer");
		assertEquals("FULL-ANSWER [8]", answer.getAnswer(),
				"the committed answer must be the unchanged full-chart result");
	}

	@Test
	public void searchStreaming_runsOnlyFullChart_whenProgressiveDisabled() {
		service.progressiveEnabled = false;

		ChartAnswer answer = run();

		assertEquals(Collections.singletonList(FULL_TEXT), provider.texts,
				"progressive off must run a single full-chart pass — no preview");
		assertEquals(0, strategy.buildFocusedChartCalls,
				"progressive off must not build the focused chart at all");
		assertEquals(Collections.singletonList("FULL-REASONING"), reasonings);
		assertEquals("FULL-ANSWER [8]", answer.getAnswer());
	}

	@Test
	public void searchStreaming_skipsPreview_whenFocusedChartIsEmpty() {
		service.progressiveEnabled = true;
		strategy.focusedChartEmpty = true;

		ChartAnswer answer = run();

		assertTrue(strategy.buildFocusedChartCalls >= 1,
				"the focused chart is attempted when progressive is on");
		assertEquals(Collections.singletonList(FULL_TEXT), provider.texts,
				"an empty focused chart yields no preview LLM call — there is nothing to reason over");
		assertFalse(reasonings.contains("PREVIEW-REASONING"),
				"no preview reasoning when the focused chart is empty");
		assertEquals("FULL-ANSWER [8]", answer.getAnswer());
	}

	@Test
	public void searchStreaming_routesPreviewToPreliminaryChannel_onThe7ArgOverload() {
		// The 7-arg overload splits preview reasoning onto its own "preliminary" channel so the UI can
		// render it as provisional and REPLACE it when the committed full-chart reasoning arrives —
		// the fix for a wrong preview misleading the clinician. The 6-arg overload (above) keeps the
		// merged-into-thinking behavior.
		service.progressiveEnabled = true;
		List<String> preliminary = new ArrayList<String>();

		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				answerTokens::add, reasonings::add, citations -> { }, ungrounded -> { },
				preliminary::add);

		assertEquals(Collections.singletonList("PREVIEW-REASONING"), preliminary,
				"preview reasoning must go to the dedicated preliminary channel, separate from the "
						+ "reasoning channel");
		assertEquals(Collections.singletonList("FULL-REASONING"), reasonings,
				"the committed full-chart reasoning stays on the reasoning channel — NOT merged with "
						+ "the preview");
		assertEquals(Collections.singletonList("FULL-ANSWER"), answerTokens,
				"the preview answer is still discarded; only the full-chart answer reaches the clinician");
		assertEquals("FULL-ANSWER [8]", answer.getAnswer());
	}

	/** Context-free service: GP-backed resolvers overridden so no OpenMRS Context is needed. */
	private final class TestableService extends LlmInferenceService {

		boolean progressiveEnabled;

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
			return progressiveEnabled;
		}
	}

	private final class StubStrategy extends ChartBuildingStrategy {

		int buildFocusedChartCalls = 0;

		boolean focusedChartEmpty = false;

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(8, "condition", "00000000-0000-0000-0000-000000000008", null),
					new RecordMapping(9, "obs", "00000000-0000-0000-0000-000000000009", null));
			return new PatientChart(FULL_TEXT, mappings, Collections.<Integer>emptyList());
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			buildFocusedChartCalls++;
			if (focusedChartEmpty) {
				return new PatientChart("", Collections.<RecordMapping>emptyList(),
						Collections.<Integer>emptyList());
			}
			List<RecordMapping> mappings = Collections.singletonList(
					new RecordMapping(1, "condition", "00000000-0000-0000-0000-000000000008", null));
			return new PatientChart(FOCUSED_TEXT, mappings, Collections.<Integer>emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private final class StubProvider extends LlmProvider {

		/** Chart text passed to each LLM pass, in order. */
		final List<String> texts = new ArrayList<String>();

		/** KV-cache scope passed to each LLM pass, in order. */
		final List<String> scopes = new ArrayList<String>();

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			texts.add(numberedRecords);
			scopes.add(cacheScope);
			// The preview pass is the one with a null KV scope; it emits an answer that MUST be
			// discarded by the caller, plus reasoning that MUST surface on the thinking channel.
			if (cacheScope == null) {
				reasoningConsumer.accept("PREVIEW-REASONING");
				tokenConsumer.accept("PREVIEW-ANSWER-DISCARDED");
				return new LlmResponse("PREVIEW-ANSWER-DISCARDED", Arrays.asList(1));
			}
			reasoningConsumer.accept("FULL-REASONING");
			tokenConsumer.accept("FULL-ANSWER");
			return new LlmResponse("FULL-ANSWER [8]", Arrays.asList(8, 9));
		}
	}
}
