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
 * Locks the WIRING that both {@link LlmInferenceService#search} and
 * {@link LlmInferenceService#searchStreaming} feed the answer prose into
 * {@code extractCitedReferences}, so an inline {@code [N]} marker the LLM
 * omitted from its structured {@code citations} array still resolves to a
 * clickable reference.
 *
 * <p>The reconciliation logic itself is unit-tested in
 * {@code LlmInferenceServiceTest}; this test guards the two call sites. The
 * bundled compatibility endpoint ({@code /search/stream}) still consumes these callbacks,
 * so a refactor that passed {@code null} for the answer there would silently
 * drop the fix on that supported path while the logic-only unit test still passed.
 * The stub LLM reproduces the
 * exact demo failure: it cites {@code [8]} inline but lists only {@code [9]}
 * in its structured array.</p>
 *
 * <p>The same call-site concern covers the safety validator: the mappings-passthrough tests
 * below pin that both paths hand the chart's mappings to {@code DrugSafetyValidator} — echo
 * scoping (issue #105) is silently inert without them.</p>
 */
public class LlmInferenceServiceCitationWiringTest {

	private TestableService service;

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
		// Production calls the status-aware, mappings-carrying overload (echo scoping, issue #105).
		// Override that exact path so the test cannot pass through an obsolete list-only seam.
		recordingValidator = new RecordingValidator();
		service.setDrugSafetyValidator(recordingValidator);
	}

	/** Recording seam over the production status-aware overload. */
	private static final class RecordingValidator
			extends org.openmrs.module.chartsearchai.reference.DrugSafetyValidator {

		java.util.List<RecordMapping> mappingsSeen;

		@Override
		public SafetyCheckResult validateWithStatus(
				String answer, String question, org.openmrs.Patient patient,
				java.util.List<RecordMapping> mappings) {
			this.mappingsSeen = mappings;
			return super.validateWithStatus(answer, question, patient, mappings);
		}
	}

	private RecordingValidator recordingValidator;

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** The measured rc.2 incident shape (bc4ba445|heart, 2026-07-21): compact comma
	 *  shorthand corroborated by the structured citations array. Shared by the
	 *  search/searchStreaming twins so the two paths always assert the same input. */
	private static final String COMMA_SHORTHAND_FIXTURE =
			"{\"reasoning\": \"r\", \"answer\": \"Persistent fetal circulation [6, 7].\", "
					+ "\"citations\": [6, 7]}";

	@Test
	public void search_shouldResolveInlineOnlyCitationToReference() {
		ChartAnswer answer = service.search(patient(), "any infections?");
		assertReferencesInclude(answer, 8);
	}

	@Test
	public void searchStreaming_shouldResolveInlineOnlyCitationToReference() {
		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				token -> { });
		assertReferencesInclude(answer, 8);
	}

	@Test
	public void search_shouldPassChartMappingsToTheSafetyValidator() {
		// Echo scoping (issue #105) is inert without the chart's mappings: a refactor that
		// reverted to the mappings-less validate() would silently re-enable the recited-mention
		// chip cascade on the blocking path, and every logic-level test would still pass.
		service.search(patient(), "any infections?");
		assertMappingsSeenIncludeIndex(8);
	}

	@Test
	public void searchStreaming_shouldPassChartMappingsToTheSafetyValidator() {
		// Twin on the PRIMARY production path (see class javadoc) — the streaming call site is
		// where a silently-dropped mappings argument would actually reach users.
		service.searchStreaming(patient(), "any infections?", token -> { });
		assertMappingsSeenIncludeIndex(8);
	}

	private void assertMappingsSeenIncludeIndex(int index) {
		assertTrue(recordingValidator.mappingsSeen != null && !recordingValidator.mappingsSeen.isEmpty(),
				"the validator must receive the chart's record mappings for echo scoping");
		boolean found = false;
		for (RecordMapping mapping : recordingValidator.mappingsSeen) {
			if (mapping.getIndex() == index) {
				found = true;
			}
		}
		assertTrue(found, "the mappings handed to the validator must be the chart's; expected index "
				+ index + " in " + recordingValidator.mappingsSeen);
	}

	@Test
	public void search_shouldResolveCorroboratedCommaShorthandFromRawEngineOutput() {
		// The measured rc.2 failure shape (bc4ba445|heart, 2026-07-21) end-to-end through the
		// REAL provider: raw engine JSON citing compact "[6, 7]" corroborated by the structured
		// array must be normalized to "[6], [7]" and resolve to references. (That capture's
		// resolved refs [6, 7, ...] prove the model populates the array for comma-cited
		// records — corroboration, not pattern-widening, is what fixes the incident.)
		service.setChartBuildingStrategy(new HeartRecordsStubStrategy());
		service.setLlmProvider(new RawJsonEngineProvider(COMMA_SHORTHAND_FIXTURE));
		ChartAnswer answer = service.search(patient(), "any heart problems?");
		assertReferencesInclude(answer, 6);
		assertReferencesInclude(answer, 7);
		assertTrue(answer.getAnswer().contains("[6], [7]"),
				"corroborated shorthand must be normalized in the display answer; got: "
						+ answer.getAnswer());
	}

	@Test
	public void searchStreaming_shouldResolveCorroboratedCommaShorthandFromRawEngineOutput() {
		// Twin of the search() test on the PRIMARY production path (see class javadoc): the
		// streaming LlmResponse must also be built from extractResponse(result.getText()) —
		// a refactor that assembled it from the AnswerExtractingConsumer's accumulated tokens
		// would silently drop shorthand normalization exactly where users hit it.
		service.setChartBuildingStrategy(new HeartRecordsStubStrategy());
		service.setLlmProvider(new RawJsonEngineProvider(COMMA_SHORTHAND_FIXTURE));
		ChartAnswer answer = service.searchStreaming(patient(), "any heart problems?", token -> { });
		assertReferencesInclude(answer, 6);
		assertReferencesInclude(answer, 7);
		assertTrue(answer.getAnswer().contains("[6], [7]"),
				"corroborated shorthand must be normalized on the streaming path; got: "
						+ answer.getAnswer());
	}

	@Test
	public void search_shouldNotFabricateReferencesFromCommaBracketValues() {
		// A comma bracket reaching this layer is NOT citation shorthand: corroborated groups
		// were already normalized to single-index markers upstream (LlmAnswerExtractor), so a
		// survivor is a clinical value. The stub chart deliberately maps records 6 and 7 —
		// a parser that treated "[6, 7]" as citations would attach both; the #76 guard must
		// instead see an answer with no inline markers and surface nothing.
		service.setChartBuildingStrategy(new HeartRecordsStubStrategy());
		service.setLlmProvider(new UncorroboratedCommaBracketProvider());
		ChartAnswer answer = service.search(patient(), "any heart problems?");
		assertTrue(answer.getReferences().isEmpty(),
				"comma-bracket values must not resolve to references; got " + answer.getReferences());
	}

	private static void assertReferencesInclude(ChartAnswer answer, int index) {
		boolean found = false;
		for (RecordReference ref : answer.getReferences()) {
			if (ref.getIndex() == index) {
				found = true;
			}
		}
		assertTrue(found, "Inline-only citation [" + index
				+ "] must resolve to a reference; got " + answer.getReferences());
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

	private static final class HeartRecordsStubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(6, "obs", "00000000-0000-0000-0000-000000000006", null),
					new RecordMapping(7, "condition", "00000000-0000-0000-0000-000000000007", null));
			return new PatientChart("6. Persistent fetal circulation\n7. Congestive cardiomyopathy",
					mappings, Collections.<Integer>emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** A REAL LlmProvider whose engine returns canned raw JSON — exercises the full composed
	 *  parse → normalize → extract chain that provider-level canned-LlmResponse stubs bypass.
	 *  Context-backed prompt/timeout resolvers are overridden so no OpenMRS runtime is needed. */
	private static final class RawJsonEngineProvider extends LlmProvider {

		private final String rawJson;

		RawJsonEngineProvider(String rawJson) {
			this.rawJson = rawJson;
		}

		@Override
		LlmEngine getActiveEngine() {
			return new LlmEngine() {

				@Override
				public InferenceResult infer(String systemPrompt, String userMessage,
						int timeoutSeconds) {
					return new InferenceResult(rawJson, 0, 0);
				}

				@Override
				public InferenceResult inferStreaming(String systemPrompt, String userMessage,
						int timeoutSeconds, Consumer<String> tokenConsumer) {
					// Real engines stream every token through the consumer; mirroring that keeps
					// this stub honest — a refactor that assembled the streaming answer from the
					// accumulated token buffer instead of extractResponse(result.getText()) must
					// fail the reference assertions with the true incident signature, not slip
					// through on an accidentally-empty buffer.
					tokenConsumer.accept(rawJson);
					return new InferenceResult(rawJson, 0, 0);
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
			};
		}

		@Override
		protected String getSystemPrompt() {
			return DEFAULT_SYSTEM_PROMPT;
		}

		@Override
		protected int getTimeoutSeconds() {
			return 5;
		}
	}

	/** Emits an UNCORROBORATED comma bracket (empty structured array by construction): per the
	 *  normalization contract this is a value, not citation shorthand — nothing must resolve. */
	private static final class UncorroboratedCommaBracketProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("Persistent fetal circulation [6, 7].",
					Collections.<Integer>emptyList());
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

	/** Cites [8] inline but lists only [9] in the structured citations array. */
	private static final class StubProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("Active Tuberculosis [8]. CD4 988.0 [9].", Arrays.asList(9));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question) {
			return canned();
		}

		// Production calls the scope-aware 6-arg overload; scope is irrelevant to this wiring test.
		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}
}
