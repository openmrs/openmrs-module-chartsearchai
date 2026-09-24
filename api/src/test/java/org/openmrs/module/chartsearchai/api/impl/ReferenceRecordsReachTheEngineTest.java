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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.impl.LlmEngine.ReferenceRecords;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Whether the chart a prompt is built from carries the module's reference records reaches the
 * engine that decides the repetition penalty — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/512">#512</a>, ADR
 * Decision 117.
 *
 * <p>Everything between the answer path and the engine is production: {@link LlmInferenceService}
 * on a chart the real {@link DrugReferenceInjector} built, and a real {@link LlmProvider} with only
 * its engine lookup and its two Context-backed settings replaced. The engine records the value it
 * was handed and refuses the arities that carry none, so a call site that reached the engine any
 * other way fails here rather than silently keeping the penalty. What {@link LocalLlmEngine} then
 * sends for each value is {@code LocalLlmEngineTest}'s, and that it forwards the value at all is
 * {@code ArchitectureGuardTest.theLocalEngineSendsEachCallsReferenceRecordsToTheBodyBuilder}'s.
 */
public class ReferenceRecordsReachTheEngineTest {

	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static PatientChart chartWithoutReferenceRecords() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Simvastatin 20mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Digoxin 125mcg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static PatientChart chartWithReferenceRecords() {
		return DrugReferenceTestSupport.injectedFindingsOver(chartWithoutReferenceRecords(), QUESTION,
			new LinkedHashSet<String>(Arrays.asList("Simvastatin", "Digoxin")),
			new LinkedHashSet<String>(Arrays.asList("C10AA01", "C01AA05")));
	}

	@Test
	public void aChartCarryingReferenceRecordsReachesTheEngineAsPresentOnEveryAnswerCall() {
		PatientChart injected = chartWithReferenceRecords();
		assertTrue(ChartSearchAiUtils.referenceSlice(injected.getMappings()).getRecords() > 0,
				"the premise: the real injector must have appended reference-group records here, or "
						+ "this case cannot tell PRESENT from ABSENT");
		RecordingEngine engine = new RecordingEngine();

		newService(injected, engine, false).search(new Patient(), QUESTION);
		assertEquals(Arrays.asList(ReferenceRecords.PRESENT, ReferenceRecords.PRESENT), engine.handed,
				"search must hand the engine PRESENT for the answer AND for the #398 repair, which is "
						+ "a second answer over the same chart and restates the same findings");

		engine.handed.clear();
		newService(injected, engine, false).searchStreaming(new Patient(), QUESTION, token -> { });
		assertEquals(Arrays.asList(ReferenceRecords.PRESENT, ReferenceRecords.PRESENT), engine.handed,
				"and so must searchStreaming, which is the path the frontend uses by default");
	}

	@Test
	public void aChartCarryingNoReferenceRecordReachesTheEngineAsAbsent() {
		PatientChart chart = chartWithoutReferenceRecords();
		assertEquals(0, ChartSearchAiUtils.referenceSlice(chart.getMappings()).getRecords(),
				"the premise: this chart carries no reference-group record");
		RecordingEngine engine = new RecordingEngine();

		newService(chart, engine, false).search(new Patient(), QUESTION);
		assertEquals(Collections.singletonList(ReferenceRecords.ABSENT), engine.handed,
				"a chart with no reference record must keep today's request, DRY included");

		engine.handed.clear();
		newService(chart, engine, false).searchStreaming(new Patient(), QUESTION, token -> { });
		assertEquals(Collections.singletonList(ReferenceRecords.ABSENT), engine.handed,
				"and so on the streaming path");
	}

	@Test
	public void theProgressiveReasoningPreviewIsReadOffItsOwnChart() {
		Patient patient = new Patient();
		patient.setUuid("uuid-1");

		RecordingEngine engine = new RecordingEngine();
		newService(chartWithReferenceRecords(), chartWithoutReferenceRecords(), engine, true)
				.searchStreaming(patient, QUESTION, token -> { });
		assertEquals(Arrays.asList(null, "uuid-1", "uuid-1"), engine.scopes,
				"the premise: the null-scoped preview, then the committed answer and its repair");
		assertEquals(Arrays.asList(ReferenceRecords.ABSENT, ReferenceRecords.PRESENT,
			ReferenceRecords.PRESENT), engine.handed,
				"the preview's focused chart never passes the injector, so it carries no reference "
						+ "record and keeps the penalty, whatever the committed answer's chart carries");

		RecordingEngine reversed = new RecordingEngine();
		newService(chartWithoutReferenceRecords(), chartWithReferenceRecords(), reversed, true)
				.searchStreaming(patient, QUESTION, token -> { });
		assertEquals(Arrays.asList(null, "uuid-1"), reversed.scopes,
				"the premise: the preview, then the committed answer, which owes no repair");
		assertEquals(Arrays.asList(ReferenceRecords.PRESENT, ReferenceRecords.ABSENT),
			reversed.handed,
				"and the preview's value is READ off its chart rather than written as a literal: a "
						+ "focused chart carrying a reference record is handed PRESENT");
	}

	private static TestableService newService(PatientChart injected, RecordingEngine engine,
			boolean progressive) {
		return newService(injected, chartWithoutReferenceRecords(), engine, progressive);
	}

	private static TestableService newService(PatientChart injected, PatientChart focused,
			RecordingEngine engine, boolean progressive) {
		TestableService created = new TestableService(progressive);
		created.setChartBuildingStrategy(new StubStrategy(focused));
		created.setLlmProvider(new EngineBackedProvider(engine));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question,
					ChartReadStatus readStatus) {
				return injected;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	private static final class TestableService extends LlmInferenceService {

		private final boolean progressive;

		private TestableService(boolean progressive) {
			this.progressive = progressive;
		}

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
			return progressive;
		}

		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}

		@Override
		protected boolean resolveFindingEnumerationRepair() {
			return true;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart focused;

		private StubStrategy(PatientChart focused) {
			this.focused = focused;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chartWithoutReferenceRecords();
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return focused;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class EngineBackedProvider extends LlmProvider {

		private final LlmEngine engine;

		private EngineBackedProvider(LlmEngine engine) {
			this.engine = engine;
		}

		@Override
		LlmEngine getActiveEngine() {
			return engine;
		}

		@Override
		protected String getSystemPrompt() {
			return DEFAULT_SYSTEM_PROMPT;
		}

		@Override
		protected int getTimeoutSeconds() {
			return 30;
		}
	}

	/**
	 * Records what each answer call was handed. The arities that carry no {@link ReferenceRecords}
	 * throw: an answer call reaching one of them is the defect this class exists to catch.
	 */
	private static final class RecordingEngine implements LlmEngine {

		private static final String ANSWER = "{\"reasoning\": \"r\", \"answer\": \"No.\", \"citations\": []}";

		private final List<ReferenceRecords> handed = new ArrayList<ReferenceRecords>();

		private final List<String> scopes = new ArrayList<String>();

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ReferenceRecords referenceRecords) {
			handed.add(referenceRecords);
			return new InferenceResult(ANSWER, 1, 1, 0);
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed,
				ReferenceRecords referenceRecords) {
			handed.add(referenceRecords);
			scopes.add(cacheScope);
			return new InferenceResult(ANSWER, 1, 1, 0);
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			throw new AssertionError("an answer call must reach the engine with its ReferenceRecords");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer) {
			throw new AssertionError("an answer call must reach the engine with its ReferenceRecords");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
			throw new AssertionError("an answer call must reach the engine with its ReferenceRecords");
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
