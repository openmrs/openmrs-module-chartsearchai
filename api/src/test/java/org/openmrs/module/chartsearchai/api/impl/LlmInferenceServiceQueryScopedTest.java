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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Locks the {@code chartsearchai.chartMode=queryScoped} gating on {@link LlmInferenceService}:
 * in scoped mode the committed answer is built from a small query-scoped slice, so the
 * full-chart prefill machinery must be fully disengaged —
 * <ul>
 *   <li>{@code warmup} is a no-op (there is no stable full-chart prefix to prime),</li>
 *   <li>{@code kvCacheScopeFor} is null (the slice is question-dependent; persisting or
 *       restoring per-patient KV entries would never match and would churn disk),</li>
 *   <li>the progressive-reasoning preview is skipped (the scoped answer itself starts after a
 *       small prefill; a preview pass would occupy the single slot for no benefit).</li>
 * </ul>
 * fullChart mode (the non-default alternative since 2026-07) must keep today's behavior
 * byte-for-byte — the negative controls assert that.
 *
 * <p>Since issue #178 it also locks the other thing the chart mode decides: which mode the answer
 * REPORTS, as {@code ChartAnswer.getSearchMode()} — the value the audit row records. Those cases sit
 * here because they are decided by the same chartMode dispatch, and because the race they must
 * survive is the one the KV cases above already pin: the label follows the chart that was built, not
 * a later read of the GP.
 */
public class LlmInferenceServiceQueryScopedTest {

	private TestableService service;

	private StubStrategy strategy;

	private StubProvider provider;

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

			// overrides the mappings-carrying overload production actually calls (issue #105)
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings) {
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
	public void warmup_shouldBeNoOp_inQueryScopedMode() {
		service.queryScoped = true;
		service.warmupEnabled = true;
		provider.supportsWarmupStub = true;

		service.warmup(patient());

		assertFalse(strategy.buildChartCalled,
				"scoped mode has no stable full-chart prefix to prime — the chart build must not run");
		assertFalse(provider.warmupCalled,
				"scoped mode must never issue a full-chart prefill warmup");
	}

	@Test
	public void warmup_shouldStillFire_inFullChartMode() {
		service.queryScoped = false;
		service.warmupEnabled = true;
		provider.supportsWarmupStub = true;

		service.warmup(patient());

		assertTrue(provider.warmupCalled, "fullChart mode keeps today's warmup behavior");
	}

	@Test
	public void kvCacheScopeFor_shouldBeNull_inQueryScopedMode() {
		service.queryScoped = true;

		assertNull(service.kvCacheScopeFor(patient()),
				"scoped prompts are question-dependent; a per-patient KV entry would never match "
				+ "and its save would churn the disk corpus");
	}

	@Test
	public void kvCacheScopeFor_shouldStayPatientUuid_inFullChartMode() {
		service.queryScoped = false;

		assertEquals("uuid-1", service.kvCacheScopeFor(patient()));
	}

	@Test
	public void searchStreaming_shouldSkipPreview_inQueryScopedMode() {
		service.queryScoped = true;
		service.progressiveEnabled = true;

		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { }, ungrounded -> { });

		assertEquals(0, strategy.buildFocusedChartCalls,
				"the scoped answer already starts after a small prefill — a preview pass would "
				+ "only occupy the single llama-server slot");
		assertEquals(1, provider.searchStreamingCalls, "exactly one LLM pass in scoped mode");
		assertEquals("SCOPED-ANSWER [8]", answer.getAnswer());
	}

	@Test
	public void searchStreaming_shouldKeepPreview_inFullChartMode() {
		service.queryScoped = false;
		service.progressiveEnabled = true;

		service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { }, ungrounded -> { });

		assertEquals(1, strategy.buildFocusedChartCalls,
				"fullChart mode keeps the progressive-reasoning preview exactly as today");
		assertEquals(2, provider.searchStreamingCalls, "preview pass + committed full-chart pass");
	}

	@Test
	public void searchStreaming_shouldPassNullKvScope_whenTheChartItselfIsScoped_evenIfTheModeReadSaysFullChart() {
		// The corruption race: the chartMode read that built the chart said queryScoped, but the
		// later re-read (transient GP failure fail-safing to fullChart, or an operator flip
		// mid-request) says fullChart. The KV scope must follow the CHART — persisting a slice
		// prompt under the patient scope would purge their real full-chart entry, pin included.
		service.queryScoped = false;
		service.progressiveEnabled = false;
		strategy.returnScopedChart = true;

		service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { }, ungrounded -> { });

		assertEquals(Arrays.asList((String) null), provider.scopes,
				"a scoped chart must force a null KV scope regardless of the GP re-read");
	}

	@Test
	public void warmup_shouldNotPersist_whenTheBuiltChartIsScoped_evenIfGatesReadFullChart() {
		// Same race on the warmup path (chart-open and prewarm/refresh all funnel here): gates
		// read fullChart, but the build dispatched scoped. A skipped warmup is always safe; a
		// slice prompt persisted (and possibly PINNED) under the patient scope is not.
		service.queryScoped = false;
		service.warmupEnabled = true;
		provider.supportsWarmupStub = true;
		strategy.returnScopedChart = true;

		service.warmup(patient());

		assertFalse(provider.warmupCalled,
				"a scoped chart must never reach llmProvider.warmup, whatever the gates read");
	}

	@Test
	public void searchStreaming_shouldPassNullKvScopeToProvider_inQueryScopedMode() {
		service.queryScoped = true;
		service.progressiveEnabled = false;

		service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { }, ungrounded -> { });

		assertEquals(Arrays.asList((String) null), provider.scopes,
				"the engine must do no disk KV I/O for question-dependent slice prompts");
	}

	@Test
	public void search_shouldLabelTheAnswerWithTheModeThatBuiltItsChart() {
		// Issue #178. The audit row's searchMode was derived at the REST layer from the preFilter GP
		// alone, so queryScoped — the shipped default — could never appear in it: every row on a
		// default install said full-chart while the prompt carried a slice. The label now comes off
		// the answer the pipeline produced, so there is nothing at the REST layer left to derive.
		service.queryScoped = true;
		strategy.returnScopedChart = true;

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				service.search(patient(), "any infections?").getSearchMode());
	}

	@Test
	public void search_shouldLabelTheAnswerFullChart_whenTheChartIsWholeAndUnranked() {
		service.queryScoped = false;
		strategy.returnScopedChart = false;
		strategy.returnPreFilteredChart = false;

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_FULL_CHART,
				service.search(patient(), "any infections?").getSearchMode());
	}

	@Test
	public void search_shouldLabelTheAnswerPreFilter_whenTheFullChartCarriesAFocusHint() {
		// The two labels that existed before #178 keep their exact meanings and their exact
		// spellings — an audit row is read by things outside this repo, so the fix ADDS a third
		// value rather than re-spelling the two.
		service.queryScoped = false;
		strategy.returnScopedChart = false;
		strategy.returnPreFilteredChart = true;

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER,
				service.search(patient(), "any infections?").getSearchMode());
	}

	@Test
	public void search_shouldLabelFromTheBuiltChart_evenWhenTheModeReReadDisagrees() {
		// Same race the KV-scope guard above exists for, applied to the label: the read that built
		// the chart said queryScoped, a later re-read says fullChart. An audit row exists to
		// reconstruct what the clinician was actually shown, so it must follow the CHART. Deriving
		// the label from a GP re-read is what #178 was, one layer up.
		//
		// BOTH mode seams are set to disagree with the chart on purpose. Overriding only the
		// service's leaves the strategy's falling through to the real GP reader, whose fail-safe
		// default is queryScoped — so a searchModeLabel rewritten to consult the mode instead of the
		// chart would return the right answer here for the wrong reason, and this case would pass
		// while claiming to rule that out.
		service.queryScoped = false;
		strategy.queryScopedMode = false;
		strategy.returnScopedChart = true;

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				service.search(patient(), "any infections?").getSearchMode(),
				"a scoped chart must label the row scoped whatever a later GP read says");
	}

	@Test
	public void searchStreaming_shouldLabelTheUngroundedAndFinalAnswersIdentically() {
		// The streaming path persists ONE audit row from ONE of two ChartAnswers, depending on
		// whether async grounding is active — the ungrounded one handed to the consumer, or the
		// returned one. Two audit-write sites disagreeing is half of what #178 was, so the two
		// answers must carry the same label by construction, not by two matching derivations.
		service.queryScoped = true;
		service.progressiveEnabled = false;
		strategy.returnScopedChart = true;
		final List<String> ungroundedModes = new java.util.ArrayList<String>();

		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				token -> { }, reasoning -> { }, citations -> { },
				ungrounded -> ungroundedModes.add(ungrounded.getSearchMode()));

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, answer.getSearchMode());
		assertEquals(Arrays.asList(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED), ungroundedModes,
				"the early-done audit row and the classic one must name the same mode");
	}

	@Test
	public void chartAnswer_shouldSayUnknownRatherThanGuess_whenNoModeWasStated() {
		// The column is NOT NULL, so an answer built by something that states no mode still has to
		// write a value. It must not be one of the three real modes: defaulting to full-chart is
		// precisely the defect #178 fixed, a wrong signal being indistinguishable from a right one.
		ChartAnswer stated = new ChartAnswer("A [1].", Collections.<RecordReference> emptyList());

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN, stated.getSearchMode());
	}

	/** Context-free service: GP-backed resolvers overridden so no OpenMRS Context is needed. */
	private final class TestableService extends LlmInferenceService {

		boolean queryScoped;

		boolean progressiveEnabled;

		boolean warmupEnabled;

		@Override
		protected boolean resolveWarmupEnabled() {
			return warmupEnabled;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return progressiveEnabled;
		}

		@Override
		protected boolean resolveQueryScopedMode() {
			return queryScoped;
		}
	}

	private final class StubStrategy extends ChartBuildingStrategy {

		boolean buildChartCalled = false;

		int buildFocusedChartCalls = 0;

		/** When true, the returned chart is stamped query-scoped — simulating buildScoped's
		 *  output regardless of what the service's mode SEAM reports, which is exactly the
		 *  disagreement the chart-derived KV guard exists for. */
		boolean returnScopedChart = false;

		/** When true, the returned chart is stamped preFiltered — simulating what build() does on
		 *  the {@code embedding.preFilter} dispatch. Defaults to false, the shipped default, so
		 *  every test written before #178 sees exactly the behaviour it was written against. */
		boolean returnPreFilteredChart = false;

		/** What the STRATEGY's own chartMode gate reports. Overridden (rather than left falling
		 *  through to the real GP reader, whose fail-safe default is queryScoped) so a case can put
		 *  the gate and the built chart in deliberate disagreement and have the disagreement be the
		 *  thing under test. */
		boolean queryScopedMode = false;

		@Override
		PatientChart buildChart(Patient patient, String question) {
			buildChartCalled = true;
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(8, "condition", "00000000-0000-0000-0000-000000000008", null));
			PatientChart chart = new PatientChart("1. Scoped record", mappings, Collections.<Integer>emptyList());
			if (returnScopedChart) {
				chart.markQueryScoped();
			}
			if (returnPreFilteredChart) {
				chart.markPreFiltered();
			}
			return chart;
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			buildFocusedChartCalls++;
			List<RecordMapping> mappings = Collections.singletonList(
					new RecordMapping(1, "condition", "00000000-0000-0000-0000-000000000008", null));
			return new PatientChart("1. Focused record", mappings, Collections.<Integer>emptyList());
		}

		@Override
		boolean queryScopedMode() {
			return queryScopedMode;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private final class StubProvider extends LlmProvider {

		int searchStreamingCalls = 0;

		boolean warmupCalled = false;

		boolean supportsWarmupStub = true;

		/** KV-cache scope passed to each committed LLM pass, in order. */
		final List<String> scopes = new java.util.ArrayList<String>();

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			searchStreamingCalls++;
			// The preview pass discards its answer; only non-preview calls record the scope so the
			// scoped-mode assertion sees exactly the committed pass's KV contract.
			if (searchStreamingCalls == 1 && buildFocusedWasPreview()) {
				return new LlmResponse("PREVIEW-DISCARDED", Arrays.asList(1));
			}
			scopes.add(cacheScope);
			return new LlmResponse("SCOPED-ANSWER [8]", Arrays.asList(8));
		}

		private boolean buildFocusedWasPreview() {
			return strategy.buildFocusedChartCalls > 0;
		}

		@Override
		public void warmup(String numberedRecords, String cacheScope, boolean pin) {
			warmupCalled = true;
		}

		@Override
		public void warmup(String numberedRecords, String cacheScope) {
			warmupCalled = true;
		}

		@Override
		public void warmup(String numberedRecords) {
			warmupCalled = true;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question) {
			return new LlmResponse("SCOPED-ANSWER [8]", Arrays.asList(8));
		}

		@Override
		public boolean supportsWarmup() {
			return supportsWarmupStub;
		}
	}
}
