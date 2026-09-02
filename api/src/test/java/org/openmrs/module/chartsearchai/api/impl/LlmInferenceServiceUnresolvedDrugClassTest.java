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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #354's statement travelling from the chart the model was given to the answer the REST layer
 * publishes it from.
 *
 * <p><b>Why the answer carries it at all.</b> The deterministic half of #354 is one injected
 * {@code drug_class_note} record, and an injected record reaches a client only if the model CITES
 * it: the {@code /search} response returns cited references and nothing else. On the issue's own
 * reproduction the model cited nothing and answered that the records did not address the question,
 * so the whole change was invisible to a {@code /search} consumer — the same "the module is silent
 * about what it did" failure issue #336 answered with a deterministic response key. These cases pin
 * the module's own statement, which no answer wording can withhold.
 *
 * <p>What they do NOT pin is the wire shape; that is
 * {@code ChartSearchAiUnresolvedDrugClassTest} in the omod module, which drives the controller.
 * Here the subject is the CARRYING — the step #178 and #229 each record as their own root cause,
 * where the number existed inside the pipeline and nothing brought it out.
 */
public class LlmInferenceServiceUnresolvedDrugClassTest {

	/** The issue's headline question, verbatim. */
	private static final String CLASS_QUESTION = "Can I start this patient on an oral contraceptive?";

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/**
	 * A service whose injector seam hands back {@code injected} — the real production chart in the
	 * cases that use one, so the statement under test is read off mappings the real injector wrote.
	 */
	private static TestableService serviceOver(final PatientChart injected) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return injected;
			}
		});
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls — mappings for echo scoping (issue #105) and the
			// sink since issue #336. Stubbing a narrower one leaves this seam inert.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return service;
	}

	/**
	 * The reviewer's failure mode, as a case: the model relays nothing and cites nothing, and the
	 * class still reaches the answer.
	 */
	@Test
	public void search_statesTheClassEvenThoughTheAnswerCitesNothing() {
		PatientChart injected = DrugReferenceTestSupport.injectedDrugClassNoteChart(CLASS_QUESTION);
		assertNotNull(ChartSearchAiUtils.unresolvedDrugClass(injected.getMappings()),
				"the premise: the real injector put a class note in this chart");

		ChartAnswer answer = serviceOver(injected).search(patient(), CLASS_QUESTION);

		assertTrue(answer.getReferences().isEmpty(),
				"the premise: the answer cites nothing, so the injected note reaches a client through "
						+ "no reference of its own — which is the state the issue's live run measured");
		assertEquals("oral contraceptive", answer.getUnresolvedDrugClass(),
				"the module's own statement must survive an answer that relays none of it");
	}

	/**
	 * Both answers the streaming method produces state it. With {@code grounding.async} on, the
	 * {@code done} event a user actually sees is emitted from the UNGROUNDED answer, so a statement
	 * set only on the returned one is absent from the event that matters.
	 */
	@Test
	public void searchStreaming_statesTheClassOnTheUngroundedAnswerTooAndNotOnlyOnTheFinalOne() {
		PatientChart injected = DrugReferenceTestSupport.injectedDrugClassNoteChart(CLASS_QUESTION);
		final List<String> ungrounded = new ArrayList<String>();

		ChartAnswer answer = serviceOver(injected).searchStreaming(patient(), CLASS_QUESTION,
				token -> { }, reasoning -> { }, citations -> { },
				early -> ungrounded.add(early.getUnresolvedDrugClass()));

		assertEquals(1, ungrounded.size(), "the early-done consumer must have fired");
		assertEquals("oral contraceptive", ungrounded.get(0),
				"the early done event is emitted from this answer, so the statement has to be on it");
		assertEquals("oral contraceptive", answer.getUnresolvedDrugClass(),
				"and on the answer the classic shape emits");
	}

	/**
	 * The statement is read off the CHART and never by asking the question again. This is the one
	 * arrangement that can tell those apart: the question names a recognised class, but the chart the
	 * model was given carries no note — which is what a resolved substance, or a question-driven
	 * injection that is switched off, actually produces. A consumer re-asking
	 * {@code DrugReferenceService.namedDrugClass(question)} answers {@code oral contraceptive} here
	 * and states a class the prompt says nothing about.
	 */
	@Test
	public void aChartWithNoClassNoteStatesNoClassEvenWhereTheQuestionNamesOne() {
		ChartAnswer answer = serviceOver(new StubStrategy().buildChart(patient(), CLASS_QUESTION))
				.search(patient(), CLASS_QUESTION);

		assertNull(answer.getUnresolvedDrugClass(),
				"a chart carrying no class note must state no class, whatever the question said");
	}

	/**
	 * The issue's control: a question the reference data resolves a substance for injects its own
	 * records and states no class, so an ordinary drug question grows no new key value.
	 */
	@Test
	public void aQuestionTheReferenceDataResolvesStatesNoClass() {
		PatientChart injected = DrugReferenceTestSupport.injectedSafetyFindingChart(
				"is it safe to give clarithromycin?", "simvastatin", "C10AA01");
		assertTrue(ChartSearchAiUtils.referenceSlice(injected.getMappings()).getRecords() > 0,
				"the premise: this arrangement injects reference material of its own");

		ChartAnswer answer = serviceOver(injected).search(patient(), "is it safe to give clarithromycin?");

		assertNull(answer.getUnresolvedDrugClass());
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

	/** A chart with no injected record of any kind — what a pre-inject chart looks like, and what
	 *  the "read it off the chart" case above serves as the whole chart. */
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

	/**
	 * Answers what the issue's live run measured, verbatim — an answer that relays no part of the
	 * note and emits no citation marker, which is the state these cases have to hold under.
	 */
	private static final class StubProvider extends LlmProvider {

		private static final String ANSWER =
				"The records do not address starting an oral contraceptive for this patient.";

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return new LlmResponse(ANSWER, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return new LlmResponse(ANSWER, Collections.<Integer> emptyList());
		}
	}
}
