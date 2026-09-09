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
import java.util.Set;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * The #397 clause reaches the prompt of a chart the real injector gave several safety findings, and
 * does not reach one it gave none. Issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
 *
 * <p><b>Over the real pipeline and its own data</b>, which is what makes it worth having beside
 * {@code LlmProviderUserMessageTest}: that class puts a literal chart string to
 * {@code buildUserMessage} and so pins the RENDERING, while nothing there can tell whether the flag
 * production computes is ever true. This drives
 * {@code DrugReferenceTestSupport.injectedFindingsOver} — {@code validate} then
 * {@code injectRecords} over the bundled knowledge base — and puts the resulting chart to the
 * predicate the two answer paths call, so the two halves of the gate are checked against each other
 * rather than each against a fixture.
 *
 * <p>Neuter {@code LlmInferenceService.severalInjectedFindings} to a constant and read the failures:
 * {@code false} reddens the first case and {@code true} the second, and neither reddens anything in
 * {@code LlmProviderUserMessageTest}.
 */
public class FindingEnumerationClauseContextTest {

	/** The arrangement {@code SafetyFindingCitationExtentTest} uses, for the reason it uses it: one
	 *  question that puts one drug in play against four of the patient's active orders, so the real
	 *  screen raises several findings about one subject. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static Set<String> setOf(String... values) {
		// LinkedHashSet, matching the sibling this file says it copies: the premise assertions
		// below count the findings one partner list raises, and a hash order would let the two
		// files raise them differently while both claiming to build the same chart.
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** The same two-order chart {@code SafetyFindingCitationExtentTest} builds, through the real
	 *  serializer — a private harness rather than a shared one, for the reason that file gives. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Simvastatin 20mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Digoxin 125mcg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static PatientChart chartWithSeveralFindings() {
		return DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05"));
	}

	@Test
	public void aChartTheScreenGaveSeveralFindingsAsksForOneLinePerFinding() {
		PatientChart chart = chartWithSeveralFindings();
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		assertTrue(findings > 1,
				"the premise: the real pipeline must inject more than one finding here, or the "
						+ "predicate below is satisfied by an arrangement that cannot show the defect. "
						+ "Injected: " + findings);
		assertTrue(LlmInferenceService.severalInjectedFindings(chart),
				"the predicate the two answer paths hand LlmProvider must be true of a chart the "
						+ "screen gave " + findings + " findings");
		String message = LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			QUESTION, LlmInferenceService.severalInjectedFindings(chart));
		assertTrue(message.contains("put every one of them on a line of its own"),
				"so the prompt this chart produces must carry the clause");
		assertTrue(message.indexOf("Clinician's query: ") < message.indexOf("put every one of them"),
				"after the question, which is the position that was measured");
	}

	/**
	 * The FLAG ITSELF REACHES THE PROVIDER, which is the one link the two cases below cannot see.
	 *
	 * <p>They pin the predicate and they pin the renderer; nothing between them was pinned, and that
	 * gap is not theoretical — replacing {@code severalInjectedFindings(chart)} with a literal
	 * {@code false} at BOTH of {@code LlmInferenceService}'s answer call sites left the entire build
	 * green, 2188 tests, which is issue #397's whole payload reverted in silence. This case drives
	 * the real {@code search} over a chart the real injector gave several findings and asserts what
	 * the provider was handed.
	 *
	 * <p>Recorded rather than asserted inside the stub: an assertion thrown from a consumer the
	 * service calls inside its own try/catch would be swallowed into the fail-safe and read as a
	 * pass. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void theFlagTheGateComputesIsWhatTheProviderIsHanded() {
		PatientChart chart = chartWithSeveralFindings();
		RecordingProvider provider = new RecordingProvider();
		TestableService service = newService(chart, provider);

		service.search(new Patient(), QUESTION);
		assertEquals(Boolean.TRUE, provider.lastFlag,
				"search must hand the provider the flag the gate computed for this chart, which the "
						+ "case above proves is true of it. A literal false here reverts #397 with "
						+ "every test green");

		provider.lastFlag = null;
		service.searchStreaming(new Patient(), QUESTION, token -> { });
		assertEquals(Boolean.TRUE, provider.lastFlag,
				"and so must searchStreaming, which is the path the frontend uses by default");
	}

	@Test
	public void aChartTheScreenGaveExactlyOneFindingAsksForNothingEither() {
		// The THRESHOLD, and it is pinned because nothing else reaches it: mutating `> 1` to `> 0`
		// leaves both cases either side of this one green (measured). One finding is not an
		// enumeration, so the clause has nothing to shape and its own antecedent is false — the cost
		// of sending it anyway is only the sentence, which is why this is a judgement rather than a
		// correctness property, and why it is pinned here rather than argued in a comment.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
			setOf("Simvastatin"), setOf("C10AA01"));
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		assertTrue(findings == 1,
				"the premise: this arrangement must raise exactly one finding, or the threshold is "
						+ "not what is being tested. Injected: " + findings);
		assertFalse(LlmInferenceService.severalInjectedFindings(chart),
				"one finding is not several, so the predicate must be false");
	}

	@Test
	public void aChartTheScreenGaveNoFindingAsksForNothing() {
		// The base chart with no injection over it: the screen raised nothing, so there is no
		// enumeration to shape and the message must be what it was before #397.
		PatientChart chart = baseChart();
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"the premise: an un-injected chart carries no safety finding");
		assertFalse(LlmInferenceService.severalInjectedFindings(chart),
				"and the predicate must be false of it");
		assertFalse(LlmProvider.buildUserMessage(chart.getText(), Collections.<Integer>emptyList(),
			QUESTION, LlmInferenceService.severalInjectedFindings(chart))
				.contains("put every one of them"),
				"so its prompt carries no clause — which is what keeps the sentence off the "
						+ "absent-data message AbsentDataEvalTest pins to exact bytes");
	}

	/** A private harness, per the convention this package states — not a shared one. The strategy
	 *  serves the already-injected chart and the injector is a no-op, so what reaches the provider
	 *  is the chart this file built. */
	private TestableService newService(PatientChart served, RecordingProvider provider) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setLlmProvider(provider);
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
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

	/** No-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
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
		boolean usePreFilter() {
			return false;
		}
	}

	/** Records the flag rather than asserting on it — see the case's javadoc for why. */
	private static final class RecordingProvider extends LlmProvider {

		private Boolean lastFlag;

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			lastFlag = Boolean.valueOf(enumerateFindings);
			return new LlmResponse("No.", Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			lastFlag = Boolean.valueOf(enumerateFindings);
			return new LlmResponse("No.", Collections.<Integer> emptyList());
		}
	}
}
