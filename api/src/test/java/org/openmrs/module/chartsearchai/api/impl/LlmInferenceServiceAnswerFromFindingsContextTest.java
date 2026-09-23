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
		return serviceWith(provider, DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	private static TestableService serviceWith(RecordingProvider provider, DrugReferenceService reference) {
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
		assertTrue(answer.getAnswer().startsWith("No — this module's drug-safety check found a reason to withhold "
				+ findings.get(0).drug + "."),
				"the lead is the call the finding states, was: " + answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
		assertNoModelProseWasJudged(answer);
		assertTrue(answer.isAnsweredByTheModule(), "and the answer says no model wrote it");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "the chips are produced as before");
		for (org.openmrs.module.chartsearchai.reference.SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(answer.getAnswer().contains(chip.getDetail()),
					"every chip beside a composed answer is a finding it states. Missing: " + chip.getDetail());
		}
		assertNotNull(answer.getPairChipExtent(), "and so is the pair extent");
	}

	/** Every shape of proposal the grammar admits, each answered with the withholding call — delete a
	 *  shape and its question here goes to the model. */
	@Test
	public void aSuitabilityQuestionIsAnsweredFromTheFindingsToo() {
		answerFromFindings(true);
		for (String question : new String[] { "Is ibuprofen safe for her?", "Is ibuprofen appropriate for her?",
				"Can this patient take ibuprofen?", "Is it safe to give her ibuprofen?",
				"Would ibuprofen be appropriate for her?", "Can I give ibuprofen to her?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question);
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		}
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

	/**
	 * A proposed drug no INTERACTION withholds is answered by the model. Only cautioned, the module's
	 * answer would have to be "can be given", a clearance nothing here can establish. Withheld only by
	 * a contraindication — here her recorded allergy to the very drug — the "No" would rest on a curated
	 * rule's token matched against her records' free text, which review found false three ways.
	 */
	@Test
	public void aProposalNoInteractionWithholdsStillAsksTheModel() {
		assertTheModelIsAsked("Can I give her omeprazole?");
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Omeprazole");
		assertTheModelIsAsked("Can I give her omeprazole?");
	}

	/**
	 * A set of findings of different strengths is one answer led by the withholding call, the stronger
	 * finding first: omeprazole relates Moderate to her warfarin (withhold) and Minor to her aspirin
	 * (a caution), and both are stated.
	 */
	@Test
	public void findingsOfDifferentStrengthsAreLedByTheWithholdingCall() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her omeprazole?";
		List<Finding> findings = findingsInThePromptFor(question);
		String withhold = null;
		String caution = null;
		for (Finding finding : findings) {
			String line = answerFacingBody(finding) + " [" + finding.index + "]";
			if (finding.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD)) {
				withhold = line;
			} else if (finding.text.endsWith(DrugReferenceInjector.STRENGTH_CAUTION)) {
				caution = line;
			}
		}
		assertTrue(withhold != null && caution != null, "precondition: both strengths, findings were: " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls);
		String text = answer.getAnswer();
		assertTrue(text.startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Omeprazole"
				+ DrugReferenceInjector.WITHHOLD_LEAD_CLOSING), "the withholding call leads: " + text);
		assertTrue(text.indexOf(withhold) < text.indexOf(caution), "then the stronger finding first: " + text);
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
			"Can I give her ibuprofen and omeprazole?",
			// no proposal at all, in words a proposal is made of
			"Is she on ibuprofen?",
			"Give her ibuprofen?",
			// a proposal cue beside a concern or a negation: "No" would answer it backwards
			"Can I give her ibuprofen, or is it risky?",
			"Can I give her ibuprofen or not?",
			// a second question joined to the proposal, in words a proposal is made of
			"Can I give her ibuprofen, and is she allergic?",
			"Is she allergic, and can I give her ibuprofen?",
			// the speaker, not the patient
			"Can I take ibuprofen?",
			// wh-questions, whose answer is neither yes nor no
			"How should I give her ibuprofen?",
			"When can I start her on ibuprofen?",
			"How long can she take ibuprofen?",
			"What can I give her instead of ibuprofen?",
			// a condition or a purpose the findings may not address
			"Is ibuprofen safe for her kidneys?",
			"Can I give her ibuprofen for her knee pain?",
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
						+ "so it is the gate and not an empty finding list that keeps the call");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(1, provider.calls, "a list question is not a screen");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * A second drug the question names keeps the call. Omeprazole's reference entry is also filed under
	 * a combination name carrying amoxicillin, which is how an earlier form of the gate — removing every
	 * word of every one of the drug's names — once admitted this as a question about omeprazole alone;
	 * no proposal shape carries a second drug, which is what refuses it now.
	 */
	@Test
	public void aSecondDrugInsideTheFirstsCombinationNameStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertTheModelIsAsked("Can I give her omeprazole with amoxicillin?");
	}

	/**
	 * A curated contraindication rule is never what licenses the module's "No": here its token
	 * {@code opium} matched her allergy {@code Tiotropium} by containment alone, a match the finding
	 * itself marks uncorroborated — one of the ways a rule's free-text match can be false.
	 */
	@Test
	public void aCuratedContraindicationRuleAloneStillAsksTheModel() throws Exception {
		DrugReferenceService curated = DrugReferenceTestSupport
				.curatedFixtureService("chartsearchai-test/drug-reference-mid-word-allergy-token.json");
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Tiotropium");
		String question = "Can I give her opium?";

		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder, curated).search(patient, question);
		assertTrue(recorder.lastRecords.contains("could not corroborate"),
				"precondition: the only withholding finding is marked uncorroborated, chart was: "
						+ recorder.lastRecords);

		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, curated).search(patient, question);

		assertEquals(1, provider.calls, "a contraindication alone never licenses the module's No");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * An interaction the data does not RATE is not what licenses the module's "No": on the curated
	 * dataset paracetamol's rule against warfarin carries no severity, which withholds only because an
	 * unrated rule is not a caution (ADR Decision 37) — a rule's author's note, the same objection that
	 * keeps contraindications from deciding the answer.
	 */
	@Test
	public void anUnratedInteractionRuleStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceService curated = DrugReferenceTestSupport.curatedService();
		String question = "Can I give her paracetamol?";

		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder, curated).search(patient, question);
		assertTrue(recorder.lastRecords.contains("Paracetamol interacts with active order")
				&& recorder.lastRecords.contains(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: an unrated interaction withholds paracetamol, chart was: " + recorder.lastRecords);

		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, curated).search(patient, question);

		assertEquals(1, provider.calls, "no rating the data gives supports the No");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * Issue #402's shape where the module cannot SEE it: her warfarin is written as a brand the data does
	 * not carry, so "not already taking it" cannot be asked of it. An order the module read and could
	 * not resolve keeps the call.
	 */
	@Test
	public void aProposalBesideAnOrderTheDataCannotNameStillAsksTheModel() throws Exception {
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		assertTheModelIsAsked("Can I give her warfarin?");
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

	/**
	 * The screening arm keeps running for a screen that names something the dataset does not carry,
	 * and raises her own medications' findings for it; an answer made of those would answer a question
	 * nobody asked. A drug class resolves nothing either, and its note asks for a drug by name.
	 */
	@Test
	public void aScreenNamingSomethingTheDatasetDoesNotResolveStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertFalse(findingsInThePromptFor(SCREEN).isEmpty(), "precondition: her own orders interact");
		for (String question : new String[] { "Does zorblatine interact with any of her medications?",
				"Is grapefruit juice safe with her medications?",
				"Do NSAIDs interact with any of her medications?",
				"Which drugs interact with her medications?",
				"Is there anything that interacts with her medications?",
				"Does this drug interact with any of her medications?",
				"Does the drug interact with her medications?",
				"Do any of her medications interact with another drug?",
				"Is there any drug interacting with her medications?" }) {
			assertFalse(findingsInThePromptFor(question).isEmpty(),
					"precondition: the screening arm raises her findings for " + question);
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(1, provider.calls, "the model must be asked: " + question);
			assertFalse(answer.isAnsweredByTheModule(), question);
		}
	}

	/** Every shape of screen the grammar admits — delete a shape and its question here goes to the
	 *  model — and a screen's answer puts her interactions ahead of any other finding about her own
	 *  medications, since interactions are what it asked about. */
	@Test
	public void everyScreenShapeIsAnsweredWithTheInteractionFirst() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		answerFromFindings(true);
		for (String question : new String[] { SCREEN,
				"Are any of her current medications interacting with each other?",
				"Do her medications interact with each other?",
				"Does she have any drug interactions I should know about?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question);
			assertTrue(answer.getAnswer().startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					"the interaction leads, not the allergy finding: " + answer.getAnswer());
		}
	}

	/** A screen that related nothing is answered by the model: the module never answers with the screen
	 *  note's negative, which is true only of a screen that ran over a fully read, fully resolved list.
	 *  A tripwire — the gate is not asked where no finding was raised — for a change that would give the
	 *  note an answer of its own again. */
	@Test
	public void aScreenThatRelatedNothingStillAsksTheModel() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder).search(patient, SCREEN);
		assertTrue(recorder.lastRecords.contains(DrugReferenceInjector.FINDING_PREFIX + "interaction screen."),
				"precondition: the screen note is injected, chart was: " + recorder.lastRecords);

		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(1, provider.calls);
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** A proposal the module would otherwise answer, over a chart whose allergy list it could not read,
	 *  is answered by the model: the chart-read verdict is a term of the gate. */
	@Test
	public void aProposalOverAChartThatWasNotReadStillAsksTheModel() {
		assertFalse(findingsInThePromptFor(PROPOSAL).isEmpty(), "precondition: ibuprofen is withheld");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = DrugReferenceTestSupport.refusingPrivilege(
				org.openmrs.util.PrivilegeConstants.GET_ALLERGIES,
				() -> serviceWith(provider).search(patient, PROPOSAL));

		assertEquals(Boolean.FALSE, answer.getChartReadForSafety(), "precondition: the allergies were not read");
		assertEquals(1, provider.calls, "the module cannot say what it did not read");
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** With the property on, the model must still be asked, where the module did raise something for
	 *  the question — so it is the gate, and not an empty finding list, that keeps the call. */
	private void assertTheModelIsAsked(String question) {
		assertFalse(findingsInThePromptFor(question).isEmpty(),
				"precondition: the module raised something for " + question);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);
		assertEquals(1, provider.calls, "the model must be asked: " + question);
		assertFalse(answer.isAnsweredByTheModule(), question);
	}

	/**
	 * A screen of her medications is answered by the module only where it relates at least one pair
	 * of them: the order-driven arm also raises a finding about an allergy to something she is
	 * prescribed on a medication question, and an answer that was only that finding would never say
	 * what the screen found — nor could it, with the interaction arms switched off.
	 */
	@Test
	public void aScreenWhoseOnlyFindingIsNotAnInteractionStillAsksTheModel() throws Exception {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		executeDataSet(METFORMIN_ORDER);
		assertFalse(findingsInThePromptFor(SCREEN).isEmpty(),
				"precondition: her aspirin allergy against her aspirin order is a finding");
		assertTheModelIsAsked(SCREEN);
	}

	/** A question about her medications that carries a safety or change word but asks for no screen
	 *  of them against each other. */
	@Test
	public void aMedicationQuestionThatIsNotAScreenStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Is there a change in her medications?",
				"Should I stop all her medications?", "Does she have any safe medications?" }) {
			assertTheModelIsAsked(question);
		}
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

		/** Full-chart mode, so the progressive-reasoning preview would really run — queryScoped mode
		 *  skips it before it reaches the provider, which would make the streaming case's "no preview
		 *  pass" unfalsifiable. */
		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
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
