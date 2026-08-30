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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #229 — the injected reference slice's size travelling from the chart the LLM actually saw to
 * the answer the REST layer files an audit row from.
 *
 * <p>The measurement itself is pinned in {@code InjectedReferenceSliceTest}; what these cases pin is
 * the CARRYING, which is the step #229 names as its own root cause — the number existed inside the
 * injector and nothing brought it out. Two things have to hold, and they are the two that came apart
 * for this row's sibling field in issue #178.
 *
 * <p><b>It is resolved off the POST-inject chart.</b> The strategy here builds a chart with no
 * reference material and the injector seam returns one the REAL injector produced, so a slice
 * resolved before {@code inject()} reports zero and every assertion below fails. That is deliberate:
 * a seam returning the chart unchanged — which is what every other test in this package installs —
 * cannot tell the two orderings apart, so those cases would stay green under exactly the mutation
 * {@code LlmInferenceService}'s own comment guards ("After inject() deliberately: that is the chart
 * the LLM sees").
 *
 * <p><b>The streaming path's two answers carry the SAME slice object.</b> It persists ONE audit row
 * from ONE of two {@link ChartAnswer}s depending on whether async grounding is active, so the two
 * must agree. Identity rather than equality is what pins the mechanism: two independent derivations
 * over one chart are equal, so an equality check on the numbers stays green under a second
 * resolution at the ungrounded site — measured — and would state a guard that is not there.
 */
public class LlmInferenceServiceReferenceSliceTest {

	/** The real injected chart the seam hands back — produced by the real injector over the real
	 *  DDInter excerpt, so the mappings being measured are production's own, not a hand-built
	 *  imitation of them. Resolved once: two runs of one arrangement agree, but only one instance
	 *  makes "the answer states THIS chart's slice" a fact rather than a coincidence. */
	private PatientChart injected;

	private TestableService service;

	@BeforeEach
	public void setUp() {
		injected = DrugReferenceTestSupport.injectedSafetyFindingChart(
				"is it safe to give clarithromycin?", "simvastatin", "C10AA01");
		service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return injected;
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

	/** What the production derivation says about the chart the seam returns — the expected value,
	 *  read through the same entry point production reads it through rather than re-summed here. */
	private ChartSearchAiUtils.ReferenceSlice expected() {
		return ChartSearchAiUtils.referenceSlice(injected.getMappings());
	}

	@Test
	public void search_statesTheSliceOfTheChartTheModelWasGiven() {
		assertTrue(expected().getRecords() > 0,
				"the arrangement must inject reference material or this case asserts nothing");
		assertEquals(0, ChartSearchAiUtils
				.referenceSlice(new StubStrategy().buildChart(patient(), "q").getMappings()).getRecords(),
				"the pre-inject chart must carry none, so a slice resolved too early reads zero");

		ChartAnswer answer = service.search(patient(), "is it safe to give clarithromycin?");

		assertEquals(expected().getRecords(), answer.getReferenceSlice().getRecords());
		assertEquals(expected().getCharacters(), answer.getReferenceSlice().getCharacters());
	}

	@Test
	public void searchStreaming_statesTheSliceOnTheUngroundedAndFinalAnswersIdentically() {
		final List<ChartSearchAiUtils.ReferenceSlice> ungrounded =
				new ArrayList<ChartSearchAiUtils.ReferenceSlice>();

		ChartAnswer answer = service.searchStreaming(patient(), "is it safe to give clarithromycin?",
				token -> { }, reasoning -> { }, citations -> { },
				early -> ungrounded.add(early.getReferenceSlice()));

		assertEquals(1, ungrounded.size(), "the early-done consumer must have fired");
		assertEquals(expected().getRecords(), answer.getReferenceSlice().getRecords());
		assertEquals(expected().getRecords(), ungrounded.get(0).getRecords(),
				"the early-done audit row and the classic one must state the same slice");
		assertSame(answer.getReferenceSlice(), ungrounded.get(0),
				"one resolution reaches both answers; equal numbers from two resolutions pass an "
						+ "equality check and leave the mechanism unpinned");
	}

	@Test
	public void chartAnswer_statesNoSliceRatherThanZero_whenTheProducerStatedNone() {
		// Zero and "not stated" are different readings and the column must be able to tell them
		// apart: zero is a real and common measurement (a question matching no entry), while an
		// answer built by something that never saw a chart has measured nothing at all. This is the
		// distinction getSearchMode() makes for its own field with SEARCH_MODE_UNKNOWN.
		ChartAnswer unstated = new ChartAnswer("A [1].", Collections.<RecordReference> emptyList());

		assertNull(unstated.getReferenceSlice());
	}

	/** Context-free service: GP-backed resolvers overridden so no OpenMRS Context is needed. */
	private static final class TestableService extends LlmInferenceService {

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
	}

	/** Builds a chart with no reference material, so only the post-inject chart can satisfy the
	 *  assertions above. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(1, "obs", "00000000-0000-0000-0000-000000000001", null, "BP 120/80"));
			return new PatientChart("[1] BP 120/80", mappings, Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return new LlmResponse("No interaction is expected.", Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return new LlmResponse("No interaction is expected.", Collections.<Integer> emptyList());
		}
	}
}
