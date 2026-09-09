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
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
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
 * The #397 clause reaches the prompt of a chart the real injector gave several safety findings ABOUT
 * ONE DRUG, and does not reach one it gave none, one it gave a single finding, or one whose findings
 * name several drugs. Issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
 *
 * <p><b>Over the real pipeline and its own data</b>, which is what makes it worth having beside
 * {@code LlmProviderUserMessageTest}: that class puts a literal chart string to
 * {@code buildUserMessage} and so pins the RENDERING, while nothing there can tell whether the flag
 * production computes is ever true. This drives
 * {@code DrugReferenceTestSupport.injectedFindingsOver} — {@code validate} then
 * {@code injectRecords} over the bundled knowledge base — and puts the resulting chart to the
 * predicate the two answer paths call, so the two halves of the gate are checked against each other
 * rather than each against a fixture. Every arrangement below asserts its own PREMISE off the
 * injected chart first, so a case cannot start passing because the shipped data stopped raising the
 * findings it is about.
 *
 * <p>Neuter {@code LlmInferenceService.severalFindingsAboutOneDrug} to a constant and read the
 * failures — every case here asserts that predicate directly, so either constant reddens this
 * class. Neither reddens anything in {@code LlmProviderUserMessageTest}, which passes the flag as a
 * literal and never asks the predicate at all.
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
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(findings > 1,
				"the premise: the real pipeline must inject more than one finding here, or the "
						+ "predicate below is satisfied by an arrangement that cannot show the defect. "
						+ "Injected: " + findings);
		assertEquals(1, subjects.size(),
				"and its other half: those findings must all name ONE drug, which is what makes this "
						+ "the arrangement the measured corpus is made of. Named: " + subjects);
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"the predicate the two answer paths hand LlmProvider must be true of a chart the "
						+ "screen gave " + findings + " findings");
		String message = LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			QUESTION, LlmInferenceService.severalFindingsAboutOneDrug(chart));
		assertTrue(message.contains("put every one of them on a line of its own"),
				"so the prompt this chart produces must carry the clause");
		assertTrue(message.indexOf("Clinician's query: ") < message.indexOf("put every one of them"),
				"after the question, which is the position that was measured");
	}

	/**
	 * The FLAG ITSELF REACHES THE PROVIDER, AND IT IS READ OFF THE POST-INJECT CHART — the one link
	 * the cases either side of this one cannot see.
	 *
	 * <p>They pin the predicate and they pin the renderer; nothing between them was pinned, and that
	 * gap is not theoretical — replacing {@code severalFindingsAboutOneDrug(chart)} with a literal
	 * {@code false} at BOTH of {@code LlmInferenceService}'s answer call sites left the entire build
	 * green, which is issue #397's whole payload reverted in silence. This case drives the real
	 * {@code search} over a chart the real injector gave several findings and asserts what the
	 * provider was handed.
	 *
	 * <p><b>The harness serves the UN-injected chart and lets its stub injector be the thing that
	 * adds the findings</b>, which is what makes the WHERE checkable as well as the WHAT. An earlier
	 * version served the already-injected chart from the strategy and had the injector return it
	 * unchanged, so pre- and post-{@code inject()} were the same object and the natural maintainer
	 * mutation — hoisting the flag's local above the {@code inject()} line, beside
	 * {@code searchMode}, {@code referenceSlice} and {@code unresolvedDrugClass} — was invisible:
	 * {@code DrugReferenceInjector} is the sole producer of {@code safety_finding} mappings, so that
	 * hoist makes the flag unconditionally false. Move either assignment above its
	 * {@code inject()} call and read the failures.
	 *
	 * <p>Recorded rather than asserted inside the stub: an assertion thrown from a consumer the
	 * service calls inside its own try/catch would be swallowed into the fail-safe and read as a
	 * pass. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void theFlagTheGateComputesIsWhatTheProviderIsHandedAndItIsReadAfterInjection() {
		PatientChart base = baseChart();
		PatientChart injected = chartWithSeveralFindings();
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(base),
				"the premise this case rests on: the chart the strategy serves must carry no finding, "
						+ "so a flag read before inject() is false and the assertions below can tell "
						+ "the two positions apart");
		RecordingProvider provider = new RecordingProvider();
		TestableService service = newService(base, injected, provider);

		service.search(new Patient(), QUESTION);
		assertEquals(Boolean.TRUE, provider.lastFlag,
				"search must hand the provider the flag the gate computed for the chart the INJECTOR "
						+ "returned, which the case above proves is true of it. A literal false here, "
						+ "or a read of the pre-inject chart, reverts #397 with every test green");

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
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"one finding is not several, so the predicate must be false");
	}

	@Test
	public void aChartTheScreenGaveNoFindingAsksForNothing() {
		// The base chart with no injection over it: the screen raised nothing, so there is no
		// enumeration to shape and the message must be what it was before #397.
		PatientChart chart = baseChart();
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"the premise: an un-injected chart carries no safety finding");
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"and the predicate must be false of it");
		assertFalse(LlmProvider.buildUserMessage(chart.getText(), Collections.<Integer>emptyList(),
			QUESTION, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them"),
				"so its prompt carries no clause — which is what keeps the sentence off the "
						+ "absent-data message AbsentDataEvalTest pins to exact bytes");
	}

	/**
	 * A SCREEN ACROSS HER OWN ORDERS ASKS FOR NOTHING, because its findings name several drugs and
	 * the clause's {@code it} then has no single referent. Issue #113's own population, and issue
	 * #397's first review round found the clause reaching it:
	 * {@code QueryScopeRouter.isInteractionScreening} needs an {@code interact*} cue and a
	 * MEDICATIONS intent and never a named drug, so the question this case asks names no drug at
	 * all.
	 *
	 * <p>The premise is asserted two ways — more than one finding, and more than one SUBJECT —
	 * because the count alone would let this case pass on an arrangement that says nothing about the
	 * subject conjunct. Delete {@code ChartSearchAiUtils.findingSubjects(...).size() == 1} from the
	 * predicate and this case is the one that reddens.
	 */
	@Test
	public void aScreenAcrossHerOwnOrdersAsksForNothing() {
		String screening = "do any of her meds interact?";
		assertTrue(QueryScopeRouter.isInteractionScreening(screening),
				"the premise: this must be the question class issue #113's screening arm runs on, or "
						+ "the case is not about that population");
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), screening,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole", "Clarithromycin", "Amiodarone"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05", "J01FA09", "C01BD01"));
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(findings > 1,
				"the premise: the screen must raise more than one finding, or the record-count "
						+ "conjunct is what withholds the clause and this case says nothing. Injected: "
						+ findings);
		assertTrue(subjects.size() > 1,
				"and the premise this case is actually about: those findings must name several drugs. "
						+ "Named: " + subjects);
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"so the predicate must be false — the sentence asks for every finding that names ONE "
						+ "drug, and this chart offers " + subjects.size() + " candidates for it");
		assertFalse(LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			screening, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them"),
				"and its prompt must carry no clause");
	}

	/**
	 * A SCREENING PHRASING THAT NAMES ITS DRUG STILL ASKS FOR ONE LINE PER FINDING — the other side
	 * of the case above, and what stops the conjunct being replaced by the cheaper-looking
	 * {@code !QueryScopeRouter.isInteractionScreening(question)}.
	 *
	 * <p>Measured over the bundled knowledge base: this question carries the screening cue AND names
	 * a drug, so the screening arm stands down (its gate needs the question to resolve no drug), the
	 * drug-in-play arm runs, and every finding names the one drug the question names. A phrasing gate
	 * withholds the clause here for no reason; the subject gate sends it. The two premises this case
	 * asserts are what make that entailed rather than argued: the phrasing predicate is true here and
	 * the subject count is one, so a predicate resting on the phrasing must fail the assertion below.
	 */
	@Test
	public void aScreeningPhrasingThatNamesItsDrugStillAsksForOneLinePerFinding() {
		String question = "Does clarithromycin interact with any of her current medications?";
		assertTrue(QueryScopeRouter.isInteractionScreening(question),
				"the premise: this phrasing must carry the screening cue, or the case cannot show "
						+ "that the gate is not a phrasing carve-out");
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), question,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05"));
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).size() > 1,
				"the premise: more than one finding");
		assertEquals(1, subjects.size(),
				"and the premise this case is about: they must all name one drug. Named: " + subjects);
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"so the predicate must be TRUE — the drug the clause's `it` refers to is the one the "
						+ "question names, whatever cue words sit around it");
		assertTrue(LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			question, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them on a line of its own"),
				"and its prompt must carry the clause");
	}

	/** A private harness, per the convention this package states — not a shared one. The strategy
	 *  serves {@code built} and the stub injector RETURNS {@code injected} rather than its argument,
	 *  which is how the two positions of the flag's read are told apart — see
	 *  {@link #theFlagTheGateComputesIsWhatTheProviderIsHandedAndItIsReadAfterInjection}. */
	private TestableService newService(PatientChart built, final PatientChart injected,
			RecordingProvider provider) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(built));
		created.setLlmProvider(provider);
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
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
