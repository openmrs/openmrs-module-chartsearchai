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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceLoad;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/378">#378</a>'s
 * statement travelling from the dataset that was loaded to the answer the REST layer publishes it
 * from.
 *
 * <p><b>Why the answer carries it at all.</b> Under the shipped {@code sourceFormat=ddinter}
 * default the dataset publishes no hand-authored allergy or condition rule, so the contraindication
 * screen's condition leg has no rule to evaluate and this patient's recorded conditions are put to
 * nothing — while {@code interactionPairs} beside it states a completeness the interaction arm
 * genuinely had. The verdict already existed at load time, on
 * {@code GET /chartsearchai/drugreferencestatus}; what it had was no reader on the surface a
 * clinician's client reads. These cases pin the CARRYING, which is the step issues #178, #229 and
 * #354 each record as their own root cause: the value existed inside the pipeline and nothing
 * brought it out.
 *
 * <p><b>Context-sensitive deliberately, and it is not a shortcut.</b> The verdict is read off
 * {@code DrugReferenceService.getLoadStatus()}, whose load is lazy and gated on
 * {@code chartsearchai.drugReference.enabled} — a global property defaulting to {@code false}. So no
 * plain unit test can reach anything but {@link DrugReferenceLoad.Coverage#UNLOADED} here, and the
 * seams that inject entries directly ({@code DrugReferenceService.setEntries}, and so
 * {@code DrugReferenceTestSupport.ddinterService()}) deliberately pair those entries with
 * {@code DrugReferenceLoad.notLoaded()} — the same value again. The only arrangement that exercises
 * a REAL load is this one: the global property on, and the module's own bundled dataset read by the
 * production loader. {@code ShippedDrugReferenceDefaultTest} is that arrangement one layer down.
 *
 * <p>What these cases do NOT pin is the wire shape; that is
 * {@code ChartSearchAiConditionRuleCoverageTest} in the omod module, which drives the controller.
 */
public class LlmInferenceServiceConditionRuleCoverageContextTest extends BaseModuleContextSensitiveTest {

	/** A question that puts a drug in play, so the screen this key describes is one that would run. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static void setDrugReferenceEnabled(boolean enabled) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, String.valueOf(enabled));
	}

	/**
	 * The production service with its collaborators stubbed EXCEPT the drug-safety validator, which
	 * is the real bean over a real {@link DrugReferenceService} — the object whose load status the
	 * statement under test is read from. Stubbing that one, as the sibling statement tests do, would
	 * leave this seam inert.
	 */
	private static TestableService serviceUnderTest() {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		service.setDrugReferenceInjector(new PassThroughInjector());
		service.setDrugSafetyValidator(
				DrugReferenceTestSupport.validator(new DrugReferenceService()));
		return service;
	}

	/**
	 * The issue's own shape: the shipped default is loaded, it publishes no condition rule, and the
	 * answer says so.
	 *
	 * <p>{@code ABSENT} and not {@code UNLOADED}, which is the distinction the issue asks for in as
	 * many words. The premise is asserted first, so a case that stopped loading the dataset fails as
	 * a premise rather than passing on a coincidence.
	 */
	@Test
	public void search_statesThatThisInstallsScreenHadNoConditionRuleToAsk() {
		setDrugReferenceEnabled(true);
		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();
		assertTrue(status.isLoaded() && !status.isInert(),
				"the premise: the shipped dataset really loaded, so ABSENT below is a reading of it "
						+ "rather than of an empty cache");

		ChartAnswer answer = serviceUnderTest().search(patient(), QUESTION);

		assertEquals(DrugReferenceLoad.Coverage.ABSENT, answer.getConditionRuleCoverage(),
				"the shipped DDInter default publishes no hand-authored condition rule, so the "
						+ "contraindication screen behind this answer put the patient's recorded "
						+ "conditions to nothing — and the answer has to say so, because nothing else "
						+ "a /search consumer reads tells that apart from a screen that asked and "
						+ "found nothing (issue #378)");
		assertNotEquals(DrugReferenceLoad.Coverage.UNLOADED, answer.getConditionRuleCoverage(),
				"and it must not collapse into 'nobody looked', which is the distinction the issue "
						+ "asks for and the one entriesPublishing cannot make");
	}

	/**
	 * Both answers the streaming method produces state it. With {@code chartsearchai.grounding.async}
	 * on, the {@code done} event a user actually sees is emitted from the UNGROUNDED answer, so a
	 * statement set only on the returned one is absent from the event that matters. Like
	 * {@code unresolvedDrugClass} and unlike {@code interactionPairs}, this one is known before the
	 * model is called, so there is no reason for the early event to carry less.
	 */
	@Test
	public void searchStreaming_statesItOnTheUngroundedAnswerTooAndNotOnlyOnTheFinalOne() {
		setDrugReferenceEnabled(true);
		final List<DrugReferenceLoad.Coverage> ungrounded = new ArrayList<DrugReferenceLoad.Coverage>();

		ChartAnswer answer = serviceUnderTest().searchStreaming(patient(), QUESTION,
			token -> { }, reasoning -> { }, citations -> { },
			early -> ungrounded.add(early.getConditionRuleCoverage()));

		assertEquals(1, ungrounded.size(), "the early-done consumer must have fired");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT, ungrounded.get(0),
				"the early done event is emitted from this answer, so the statement has to be on it");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT, answer.getConditionRuleCoverage(),
				"and on the answer the classic shape emits");
	}

	/**
	 * The other direction of the three-valued verdict, and the case that stops {@code ABSENT} above
	 * being read as "what the key always says": with the feature off nothing is loaded, and the
	 * answer states that instead — never {@code ABSENT}, which would claim a dataset was read and
	 * found to carry no condition rule.
	 */
	@Test
	public void search_statesThatNobodyLookedWhereTheFeatureIsOff() {
		setDrugReferenceEnabled(false);

		ChartAnswer answer = serviceUnderTest().search(patient(), QUESTION);

		assertEquals(DrugReferenceLoad.Coverage.UNLOADED, answer.getConditionRuleCoverage(),
				"nothing was read, so nothing is known about the arm — and reading the status must "
						+ "not be what triggers a load on an install that does not use the feature");
	}

	/** Exposes the seams, and keeps warmup out of a test about a statement. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/** A one-record chart, built without touching querystore. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(1, "condition", "00000000-0000-0000-0000-000000000001", null));
			return new PatientChart("1. Ventricular tachycardia", mappings,
					Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** Hands the chart back unchanged: what this statement reads is the LOAD, not the chart. */
	private static final class PassThroughInjector extends DrugReferenceInjector {

		@Override
		public PatientChart inject(PatientChart chart, Patient patient, String question) {
			return chart;
		}
	}

	/** An answer with no citation markers, so nothing downstream depends on the model's wording. */
	private static final class StubProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("Clarithromycin should not be started.",
					Collections.<Integer> emptyList());
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
