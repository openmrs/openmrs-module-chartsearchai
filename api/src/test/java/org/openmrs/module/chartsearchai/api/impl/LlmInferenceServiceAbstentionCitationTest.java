/*
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
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Reproduces the exact demo failure behind "Does he have cancer?": a small local
 * model returns an abstention answer that cites NOTHING inline (no {@code [N]}
 * markers in the prose) yet dumps its whole reviewed record set into the
 * structured {@code citations} array. The pipeline used to surface all of those
 * as clickable references, so a "no cancer found" answer arrived with 30
 * unrelated conditions attached.
 *
 * <p>The contract is set by the system prompt's own few-shot: an abstention
 * answer ("There are no records of banana deliveries.") carries
 * {@code "citations": []}, and every real citation is written inline in brackets.
 * References must therefore be anchored by an inline {@code [N]} marker; a
 * structured array with no inline anchor in the answer is spurious.</p>
 *
 * <p>Drives the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration (buildChart -&gt; generate -&gt; extract citations -&gt; ground)
 * with the chart/LLM collaborators stubbed, mirroring
 * {@link LlmInferenceServiceCitationWiringTest}.</p>
 */
public class LlmInferenceServiceAbstentionCitationTest {

	private TestableService service;

	@BeforeEach
	public void setUp() {
		service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing the four-argument one instead leaves this
			// stub INERT — production would not reach it — which is why it names both parameters.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
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

	@Test
	public void search_shouldReturnNoReferencesForAnAnswerThatCitesNothingInline() {
		ChartAnswer answer = service.search(patient(), "Does he have cancer?");
		assertNoReferences(answer);
	}

	@Test
	public void searchStreaming_shouldReturnNoReferencesForAnAnswerThatCitesNothingInline() {
		ChartAnswer answer = service.searchStreaming(patient(), "Does he have cancer?",
				token -> { });
		assertNoReferences(answer);
	}

	/**
	 * Guards the {@code .trim()} in the drop condition. The abstention-dump drop
	 * fires only on real prose that anchors no citation; a BLANK answer is a
	 * distinct degenerate output (the model returned no text) where the structured
	 * array is still allowed to resolve — the same contract the citation eval
	 * dataset locks for an empty answer. Without {@code .trim()}, a whitespace-only
	 * answer would be treated as prose and silently drop its array, diverging from
	 * that spec with no other test to catch it.
	 */
	@Test
	public void search_shouldStillResolveTheArrayForABlankAnswer() {
		service.setLlmProvider(new BlankAnswerProvider());
		ChartAnswer answer = service.search(patient(), "What conditions does he have?");
		assertFalse(answer.getReferences().isEmpty(),
				"A blank (whitespace-only) answer is degenerate, not an abstention: its structured "
						+ "citations array must still resolve; got " + answer.getReferences());
	}

	private static void assertNoReferences(ChartAnswer answer) {
		assertTrue(answer.getReferences().isEmpty(),
				"An abstention answer with no inline [N] markers must carry no references; got "
						+ answer.getReferences());
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(8, "condition", "00000000-0000-0000-0000-000000000008", null),
					new RecordMapping(9, "diagnosis", "00000000-0000-0000-0000-000000000009", null));
			return new PatientChart("8. Tuberculosis\n9. Persistent vomiting", mappings,
					Collections.<Integer>emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/**
	 * The abstention shape: prose names no citation in brackets, but the structured
	 * array dumps every reviewed record.
	 */
	private static final class StubProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse(
					"There is no explicit diagnosis of cancer in the patient records.",
					Arrays.asList(8, 9));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}

	/**
	 * Returns a whitespace-only answer with a non-empty citations array — the blank
	 * degenerate case, distinct from a real abstention sentence.
	 */
	private static final class BlankAnswerProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("   ", Arrays.asList(8, 9));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}
}
