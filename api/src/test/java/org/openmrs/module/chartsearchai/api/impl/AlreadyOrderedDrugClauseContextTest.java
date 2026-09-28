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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.impl.LlmEngine.ReferenceRecords;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.PatientClinicalContext;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * The clause telling the model that a drug the question proposes is already in the patient's own active
 * orders reaches the message the ENGINE is sent, on both answer paths — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/548">#548</a>.
 *
 * <p>Over the real pipeline: the chart is the real injector's over the knowledge base the module ships,
 * and the message is built by the real {@code LlmProvider}, with only the engine replaced so the bytes it
 * is handed can be read. {@code LlmProviderUserMessageTest} pins the clause's RENDERING from a literal
 * list; this pins that production hands the provider the list the injector stamped, and reads it off the
 * chart AFTER injection — the strategy serves a chart carrying no stamp.
 */
public class AlreadyOrderedDrugClauseContextTest {

	private static final String PREDNISONE_ORDER = "Prednisone Co 5mg";

	private static final String CLAUSE = " Prednisone is already in the patient's active orders (Prednisone Co 5mg):"
			+ " open by saying so; adding it would duplicate that order; then say what the findings mean for the"
			+ " patient's current Prednisone.";

	/** Cut from {@link #CLAUSE}, so a case asserting its absence tracks the clause production writes. */
	private static final String CLAUSE_MARK = "is already in the patient's active orders";

	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "order-prednisone",
				PREDNISONE_ORDER + " tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "order-warfarin",
				"Warfarin 5mg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static PatientChart injectedFor(String question) {
		DrugReferenceService service = DrugReferenceTestSupport.shippedServiceWithGroups();
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			new PatientClinicalContext.ActiveDrugOrder("order-prednisone", PREDNISONE_ORDER,
					new LinkedHashSet<String>(Arrays.asList(PREDNISONE_ORDER))),
			new PatientClinicalContext.ActiveDrugOrder("order-warfarin", "Warfarin 5mg",
					new LinkedHashSet<String>(Arrays.asList("Warfarin 5mg"))));
		return DrugReferenceTestSupport.injectedFindingsOver(service, baseChart(), question,
			new LinkedHashSet<String>(Arrays.asList(PREDNISONE_ORDER, "Warfarin 5mg")),
			Collections.<String> emptySet(), orders);
	}

	@Test
	public void aProposalOfHerOwnDrugEndsTheMessageWithTheClauseOnBothPaths() {
		String question = "Is it safe to add prednisone for her?";
		PatientChart injected = injectedFor(question);
		assertFalse(injected.getDrugsAlreadyOrdered().isEmpty(),
			"the premise: the real injector stamped the proposed drug as one she already takes");
		assertTrue(baseChart().getDrugsAlreadyOrdered().isEmpty(),
			"the premise: the chart the strategy serves carries no stamp, so a read before inject() finds none");

		RecordingEngine engine = new RecordingEngine();
		newService(injected, engine).search(new Patient(), question);
		assertTrue(engine.messages.get(0).endsWith(CLAUSE), "search: " + engine.messages.get(0));
		assertTrue(engine.messages.get(0).contains("Clinician's query: " + question + " "),
			"the clause follows the question: " + engine.messages.get(0));

		engine.messages.clear();
		newService(injected, engine).searchStreaming(new Patient(), question, token -> { });
		assertTrue(engine.messages.get(0).endsWith(CLAUSE), "searchStreaming: " + engine.messages.get(0));
	}

	@Test
	public void theFindingEnumerationRepairIsNotHandedTheClause() {
		// The repair asks a question of its own about the findings the answer left out, so the clause —
		// about the clinician's question — is not its to carry.
		String question = "Is it safe to add prednisone for her?";
		RecordingEngine engine = new RecordingEngine();
		TestableService service = newService(injectedFor(question), engine);
		service.repair = true;

		service.search(new Patient(), question);
		assertEquals(2, engine.messages.size(), "the premise: the answer and the #398 repair: " + engine.messages);
		assertTrue(engine.messages.get(0).endsWith(CLAUSE));
		assertFalse(engine.messages.get(1).contains(CLAUSE_MARK), "the repair: " + engine.messages.get(1));
	}

	@Test
	public void aProposalOfADrugSheDoesNotTakeCarriesNoClause() {
		String question = "Is it safe to start her on clarithromycin?";
		RecordingEngine engine = new RecordingEngine();

		newService(injectedFor(question), engine).search(new Patient(), question);
		assertFalse(engine.messages.get(0).contains(CLAUSE_MARK), "was: " + engine.messages.get(0));

		engine.messages.clear();
		newService(injectedFor(question), engine).searchStreaming(new Patient(), question, token -> { });
		assertFalse(engine.messages.get(0).contains(CLAUSE_MARK), "was: " + engine.messages.get(0));
	}

	private static TestableService newService(final PatientChart injected, RecordingEngine engine) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(baseChart()));
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

		private boolean repair;

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

		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}

		@Override
		protected boolean resolveFindingEnumerationRepair() {
			return repair;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return chart;
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

		@Override
		protected FindingProse findingProse(boolean enumerateFindings) {
			return enumerateFindings ? FindingProse.ENUMERATED : FindingProse.UNPROMPTED;
		}
	}

	/** Records the user message of each answer call, in order. */
	private static final class RecordingEngine implements LlmEngine {

		private static final String ANSWER = "{\"reasoning\": \"r\", \"answer\": \"No.\", \"citations\": []}";

		private final List<String> messages = new ArrayList<String>();

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ReferenceRecords referenceRecords) {
			messages.add(userMessage);
			return new InferenceResult(ANSWER, 1, 1, 0);
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer, String cacheScope, String cacheSeed,
				ReferenceRecords referenceRecords) {
			messages.add(userMessage);
			return new InferenceResult(ANSWER, 1, 1, 0);
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			throw new AssertionError("an answer call must reach the engine with its ReferenceRecords");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer) {
			throw new AssertionError("an answer call must reach the engine with its ReferenceRecords");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
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
