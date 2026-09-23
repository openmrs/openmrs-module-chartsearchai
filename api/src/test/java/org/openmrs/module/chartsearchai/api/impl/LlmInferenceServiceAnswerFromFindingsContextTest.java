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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/469">#469</a>: where
 * the module has itself resolved a drug-safety question, the answer is composed from the module's own
 * findings and the model is not asked to restate them. ADR Decision 108 records the bound and what it
 * reverses.
 *
 * <p><b>Everything but the model is real.</b> Patient 7 of the standard dataset (active order 111,
 * ASPIRIN) read through the real {@code OrderService} by the real injector's
 * {@code PatientClinicalContextBuilder}, the real {@code DrugSafetyValidator} over the DDInter excerpt
 * the tests load, and the real {@link LlmInferenceService#search}/{@code searchStreaming}. The model
 * is a recorder: every case asserts whether it was ASKED, which is the behaviour the issue is about,
 * and the finding records a composed answer must carry are read off the chart that recorder was handed
 * on a run with the property off — so they are the records production wrote, never a copy.
 */
public class LlmInferenceServiceAnswerFromFindingsContextTest extends BaseModuleContextSensitiveTest {

	private static final String WARFARIN_ORDER = "AnswerFromFindingsWarfarinOrderTestData.xml";

	private static final String METFORMIN_ORDER = "AnswerFromFindingsMetforminOrderTestData.xml";

	/** The ticket's own shape: a drug the patient is not on, proposed, related Major to her order. */
	private static final String PROPOSAL = "Can I give her ibuprofen?";

	private static final String SCREEN = "Are there any drug interactions with her current medications?";

	/** One numbered finding line of the chart the model is handed. */
	private static final Pattern FINDING_LINE = Pattern.compile(
			"\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX) + "([^:\\n]+): ([^\\n]*)");

	private static final String[] STRENGTH_CLAUSES = { DrugReferenceInjector.STRENGTH_WITHHOLD,
			DrugReferenceInjector.STRENGTH_CAUTION, DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION,
			DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION };

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	private void answerFromFindings(boolean on) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, String.valueOf(on));
	}

	private static TestableService serviceWith(RecordingProvider provider) {
		DrugReferenceService reference = DrugReferenceTestSupport.ddinterServiceWithGroups();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** The finding records production put in the prompt for {@code question}, read with the property
	 *  off so the model really is handed them. */
	private List<Finding> findingsInThePromptFor(String question) {
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder).search(patient, question);
		assertEquals(1, recorder.calls, "precondition: with the property off the model is asked");
		List<Finding> findings = new ArrayList<Finding>();
		Matcher matcher = FINDING_LINE.matcher(recorder.lastRecords);
		while (matcher.find()) {
			findings.add(new Finding(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.group(3)));
		}
		answerFromFindings(true);
		return findings;
	}

	/** The part of a finding record a composed answer carries: its text after the record's own head,
	 *  with the prompt-facing strength clause taken off (reference/CLAUDE.md: that clause is
	 *  prompt-facing only). */
	private static String answerFacingBody(Finding finding) {
		for (String clause : STRENGTH_CLAUSES) {
			if (finding.text.endsWith(clause)) {
				return finding.text.substring(0, finding.text.length() - clause.length());
			}
		}
		throw new IllegalStateException("every injected finding states one strength clause, this one "
				+ "states none: " + finding.text);
	}

	private static void assertCarriesEveryFinding(ChartAnswer answer, List<Finding> findings) {
		for (Finding finding : findings) {
			assertTrue(answer.getAnswer().contains(answerFacingBody(finding) + " [" + finding.index + "]"),
					"the composed answer must carry finding [" + finding.index + "] in the record's own "
							+ "words, cited by its own number. Finding: " + finding.text + "\nAnswer: "
							+ answer.getAnswer());
			assertTrue(ChartAnswerTestSupport.referenceIndexes(answer).contains(Integer.valueOf(finding.index)),
					"and the finding must be a reference of the answer, was: "
							+ ChartAnswerTestSupport.referenceIndexes(answer));
		}
		for (String clause : STRENGTH_CLAUSES) {
			assertFalse(answer.getAnswer().contains(clause.trim()),
					"a strength clause is prompt-facing only and must not reach the answer: " + answer.getAnswer());
		}
	}

	/** The keys that judge what a MODEL wrote state no measurement where no model wrote the answer. */
	private static void assertNoModelProseWasJudged(ChartAnswer answer) {
		assertNull(answer.getUnfaithfullyRenderedCitations(), "unfaithfullyRenderedCitations");
		assertNull(answer.getMisattributedOrderCitations(), "misattributedOrderCitations");
		assertNull(answer.getActiveOrderClaims(), "activeOrderClaims");
		assertNull(answer.getUnstatedFindingSeverities(), "unstatedFindingSeverities");
		assertNull(answer.getFindingCitationExtent(), "findingCitations");
		assertNull(answer.getUnstatedDosingCeilings(), "unstatedDosingCeilings");
		assertNull(answer.getFindingPartnerCoverage(), "findingPartners");
	}

	@Test
	public void aProposedDrugTheModuleWithholdsIsAnsweredFromItsFindingsWithoutAskingTheModel() {
		List<Finding> findings = findingsInThePromptFor(PROPOSAL);
		assertFalse(findings.isEmpty(), "precondition: ibuprofen x her aspirin order raises a finding");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "the module resolved the question, so the model is not asked");
		assertTrue(answer.getAnswer().startsWith("No — " + findings.get(0).drug
				+ " should not be given: this module's drug-safety check found a reason to withhold it."),
				"the lead is the call the finding states, was: " + answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
		assertNoModelProseWasJudged(answer);
		assertTrue(answer.isAnsweredByTheModule(), "and the answer says no model wrote it");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "the chips are produced as before");
		assertNotNull(answer.getPairChipExtent(), "and so is the pair extent");
	}

	@Test
	public void searchStreaming_takesTheSamePathAndHandsTheComposedAnswerToEverySurface() {
		List<Finding> findings = findingsInThePromptFor(PROPOSAL);
		RecordingProvider provider = new RecordingProvider();
		final StringBuilder streamed = new StringBuilder();
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();

		ChartAnswer answer = serviceWith(provider).searchStreaming(patient, PROPOSAL, streamed::append,
				reasoning -> { }, citations -> { }, early::add);

		assertEquals(0, provider.calls, "neither the answer pass nor a preview pass asks the model");
		assertEquals(answer.getAnswer(), streamed.toString(),
				"a user watching the stream is handed the answer the response carries");
		assertCarriesEveryFinding(answer, findings);
		assertNoModelProseWasJudged(answer);
		assertTrue(answer.isAnsweredByTheModule(), "the returned answer says so");
		assertEquals(1, early.size(), "the early done fired");
		assertTrue(early.get(0).isAnsweredByTheModule(),
				"and so does the early done, which is the event a streaming user sees");
		assertEquals(answer.getAnswer(), early.get(0).getAnswer());
	}

	@Test
	public void aCautionOnlyFindingLeadsWithTheGradedLeadNamingTheCautionInTheSameSentence() {
		String question = "Can I give her omeprazole?";
		List<Finding> findings = findingsInThePromptFor(question);
		assertFalse(findings.isEmpty(), "precondition: omeprazole x her aspirin order raises a Minor finding");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls);
		assertTrue(answer.getAnswer().startsWith(findings.get(0).drug + " can be given, with a caution to note: "
				+ answerFacingBody(findings.get(0)) + " [" + findings.get(0).index + "]"),
				"a caution is never read as a reason to withhold, and it is named in the lead: "
						+ answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
	}

	@Test
	public void withThePropertyOffTheModelIsAskedAsBefore() {
		answerFromFindings(false);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(1, provider.calls, "the property ships off and changes nothing while it is");
		assertTrue(answer.getAnswer().startsWith(RecordingProvider.ANSWER),
				"the model's own answer, which ADR Decision 100 may complete but never replaces: "
						+ answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
		assertNotNull(answer.getFindingCitationExtent(), "the checks judge the model's prose as before");
	}

	/** Questions the module must NOT answer for the model, each for its own reason — the predicate is
	 *  fail-closed, so a question it does not recognise keeps today's path. */
	@Test
	public void aQuestionTheModuleDidNotResolveAsASuitabilityQuestionStillAsksTheModel() {
		answerFromFindings(true);
		String[] questions = {
			// a dose: the injected reference record addresses it, the findings do not
			"Can I give her ibuprofen 400mg?",
			"What dose of ibuprofen can I give her?",
			// inverse polarity: a "No" lead would answer it backwards
			"Is it risky to give her ibuprofen?",
			"Is it wrong to give her ibuprofen?",
			"Is there any reason not to give her ibuprofen?",
			// current use, not a proposal
			"Does she take ibuprofen?",
			// two drugs: each would need its own answer
			"Can I give her ibuprofen or omeprazole?",
		};
		for (String question : questions) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(1, provider.calls, "the model must be asked: " + question);
			assertFalse(answer.isAnsweredByTheModule(), question);
		}
	}

	/** A question about her medication LIST, no drug named and no screen asked for, where the
	 *  order-driven contraindication arm still raises a finding — she is recorded allergic to the
	 *  aspirin she is prescribed. An answer that was only that finding would drop the list. */
	@Test
	public void aListQuestionThatRaisedAnOrderDrivenFindingStillAsksTheModel() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		String question = "What medications is she taking?";
		assertFalse(findingsInThePromptFor(question).isEmpty(),
				"precondition: the allergy to her own prescription raises a finding on a list question, "
						+ "so it is the screening conjunct and not an empty finding list that keeps the call");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(1, provider.calls, "a list question is not a screen");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** A proposed drug that raised NOTHING beside a finding about something else: the allergy
	 *  question widens the order-driven arm to her aspirin allergy and her aspirin prescription, while
	 *  metformin relates to her order only at Unknown, which the shipped floor filters. An answer made
	 *  of that one finding would leave the drug asked about silent. */
	@Test
	public void aProposedDrugThatRaisedNothingOfItsOwnStillAsksTheModel() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		String question = "Can I give her metformin, given her allergies?";
		assertFalse(findingsInThePromptFor(question).isEmpty(),
				"precondition: a finding IS raised, about her own prescription, so it is the missing "
						+ "finding about metformin that keeps the call");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(1, provider.calls, "metformin raised nothing, so the module has no answer for it");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** Issue #402's shape: a question naming a drug she already takes. The drug-in-play arm states a
	 *  proposal clause for it, so composing would make that defect deterministic. */
	@Test
	public void aDrugSheAlreadyTakesStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her warfarin?";
		assertFalse(findingsInThePromptFor(question).isEmpty(),
				"precondition: warfarin x her aspirin order raises a finding, so it is the exclusion and "
						+ "not an empty finding list that keeps the call");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(1, provider.calls, "warfarin is one of her own orders");
		assertFalse(answer.isAnsweredByTheModule());
	}

	@Test
	public void aScreenThatRaisedFindingsOpensWithTheFindingItselfAndChoosesNoDrugToChange() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		List<Finding> findings = findingsInThePromptFor(SCREEN);
		assertFalse(findings.isEmpty(), "precondition: her warfarin and aspirin orders interact Major");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls);
		assertTrue(answer.getAnswer().startsWith(answerFacingBody(findings.get(0))),
				"a finding about her own medications opens with the finding, which names the medication "
						+ "and what it relates it to — never a lead choosing which of the two to change: "
						+ answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
		assertTrue(answer.isAnsweredByTheModule());
	}

	@Test
	public void aScreenThatRelatedNothingIsAnsweredWithTheScreenNote() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder).search(patient, SCREEN);
		Matcher note = Pattern.compile("\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX)
				+ "interaction screen\\. ([^\\n]*)").matcher(recorder.lastRecords);
		assertTrue(note.find(), "precondition: the screen note is injected, chart was: " + recorder.lastRecords);

		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls);
		assertEquals(note.group(2) + " [" + note.group(1) + "]", answer.getAnswer(),
				"the note verbatim, qualifier included, cited by its own number");
		assertTrue(answer.isAnsweredByTheModule());
	}

	private static final class Finding {

		private final int index;

		private final String drug;

		private final String text;

		private Finding(int index, String drug, String text) {
			this.index = index;
			this.drug = drug;
			this.text = text;
		}
	}

	private static final class RecordingProvider extends LlmProvider {

		static final String ANSWER = "The model's answer [1].";

		private int calls;

		private String lastRecords;

		private LlmResponse record(String numberedRecords) {
			calls++;
			lastRecords = numberedRecords;
			return new LlmResponse(ANSWER, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings) {
			return record(numberedRecords);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			tokenConsumer.accept(ANSWER);
			return record(numberedRecords);
		}
	}

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
			return true;
		}
	}

	/** One obs record: the chart this path joins the injected records onto. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return buildChart(patient, question);
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}
}
