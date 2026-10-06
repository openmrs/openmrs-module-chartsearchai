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
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReference;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;

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

	/** The uuid of {@link #WARFARIN_ORDER}'s order, as that dataset spells it. */
	private static final String WARFARIN_ORDER_UUID = "9469dddd-0000-4000-8000-00000009469a";

	/** The uuid of patient 7's aspirin order in the standard test dataset. */
	private static final String ASPIRIN_ORDER_UUID = "e1f95924-697a-11e3-bd76-0800271c1b75";

	/** Patient 7's first aspirin order, stopped on 2008-08-15 when order 111 revised it. */
	private static final String FIRST_ASPIRIN_ORDER_UUID = "921de0a3-05c4-444a-be03-e01b4c4b9142";

	private static final String METFORMIN_ORDER = "AnswerFromFindingsMetforminOrderTestData.xml";

	/** Patient 7's aspirin beside two proposals the arm relates to none of her orders by their own rows (issue #592). */
	private static final String ONE_DIRECTION = "chartsearchai-test/drug-reference-no-pair-one-direction.json";

	/** Patient 7's aspirin, known by its ATC code alone, beside a proposal related to it only below the floor
	 *  (issue #592). */
	private static final String BELOW_FLOOR_CODES_ONLY =
			"chartsearchai-test/drug-reference-no-pair-below-floor-codes-only.json";

	/** The ticket's own shape: a drug the patient is not on, proposed, related Major to her order. */
	private static final String PROPOSAL = "Can I give her ibuprofen?";

	/** The brief line a composed "No" states for ibuprofen against her aspirin, up to its own marker (ADR Decision 153):
	 *  the finding's first sentence and the cross-reactivity relationship it folded. */
	private static final String IBUPROFEN_LINE = "Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. "
			+ "Ibuprofen is in the same cross-reactivity group (NSAID) as active order Acetylsalicylic acid (aspirin) — "
			+ "possible additive or duplicate-class therapy. [";

	private static final String SCREEN = "Are there any drug interactions with her current medications?";

	/** One numbered finding line of the chart the model is handed. */
	private static final Pattern FINDING_LINE = Pattern.compile(
			"\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX) + "([^:\\n]+): ([^\\n]*)");

	private static final String[] STRENGTH_CLAUSES = { DrugReferenceInjector.STRENGTH_WITHHOLD,
			DrugReferenceInjector.STRENGTH_CAUTION, DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION,
			DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION };

	/** What a composed contraindication line about a medication she already takes states in place of
	 *  its record's strength clause: the clause's referent, without its call — spelled out as the
	 *  specification rather than read off production. */
	private static final String CURRENT_MEDICATION_REFERENT =
			" This finding is about a medication this patient is already taking.";

	private Patient patient;

	/** The knowledge base the module ships, loaded once for the cases that need a drug the bundled excerpt does
	 *  not carry (issue #592). */
	private static DrugReferenceService shipped;

	private static synchronized DrugReferenceService shipped() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.shippedServiceWithGroups();
		}
		return shipped;
	}

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

	/** {@link #serviceWith(RecordingProvider, DrugReferenceService)} over a chart of {@code records}. */
	private static TestableService serviceWithChart(RecordingProvider provider, DrugReferenceService reference,
			RecordMapping... records) {
		TestableService service = serviceWith(provider, reference);
		service.setChartBuildingStrategy(new StubStrategy(records));
		return service;
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
		return findingsInThePromptFor(question, DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	private List<Finding> findingsInThePromptFor(String question, DrugReferenceService reference) {
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder, reference).search(patient, question);
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

	/**
	 * The line a composed answer states for {@code finding}: its answer-facing body, then — for a
	 * contraindication about a medication she already takes, whose record says so only in the strength
	 * clause the answer leaves out — the referent that clause carried, then its own marker. Whether the
	 * finding is a contraindication is read off the chip production raised for it, never off the prose.
	 */
	private static String expectedLine(ChartAnswer answer, Finding finding) {
		String body = answerFacingBody(finding);
		boolean current = finding.text.endsWith(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION)
				|| finding.text.endsWith(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION);
		return body + (current && isContraindication(answer, finding) ? CURRENT_MEDICATION_REFERENT : "")
				+ " [" + finding.index + "]";
	}

	/**
	 * {@code line} is the composed line for {@code finding} — {@link #expectedLine} — followed by nothing but
	 * the markers of chart records the answer cites as its own, which are the records of the orders the
	 * finding is about (ADR Decision 140; which record is pinned by
	 * {@code .aComposedLineCitesItsOrdersRecordAndTheChipsAreScopedByIt}).
	 */
	private static void assertLineIs(ChartAnswer answer, Finding finding, String line, String message) {
		String expected = expectedLine(answer, finding);
		assertTrue(line.startsWith(expected), message + "\nexpected the line to open: " + expected + "\nwas: " + line);
		Matcher markers = Pattern.compile(" \\[(\\d+)\\]").matcher(line.substring(expected.length()));
		int end = 0;
		while (markers.find() && markers.start() == end) {
			end = markers.end();
			assertTrue(chartRecordsCitedAsTheAnswersOwn(answer).contains(Integer.valueOf(markers.group(1))),
					"a marker after the finding's own cites a chart record of the answer: " + line);
		}
		assertEquals(line.length() - expected.length(), end, "nothing but markers follows the finding's own: " + line);
	}

	private static List<Integer> chartRecordsCitedAsTheAnswersOwn(ChartAnswer answer) {
		List<Integer> indexes = new ArrayList<Integer>();
		for (RecordReference reference : answer.getReferences()) {
			if (ChartSearchAiConstants.REFERENCE_GROUP_CHART.equals(ChartSearchAiUtils.referenceGroup(
					reference.getResourceType())) && !reference.isAttachedByTheModule()) {
				indexes.add(Integer.valueOf(reference.getIndex()));
			}
		}
		return indexes;
	}

	private static boolean isContraindication(ChartAnswer answer, Finding finding) {
		String body = answerFacingBody(finding);
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.getDrug().equals(finding.drug) && body.startsWith(chip.getDetail())) {
				return SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType());
			}
		}
		throw new IllegalStateException("no chip beside the answer is finding [" + finding.index + "]: "
				+ finding.text + "\nChips: " + answer.getSafetyWarnings());
	}

	private static void assertCarriesEveryFinding(ChartAnswer answer, List<Finding> findings) {
		for (Finding finding : findings) {
			assertTrue(answer.getAnswer().contains(expectedLine(answer, finding)),
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
		assertNull(answer.getUnfoundedFindingSeverities(), "unfoundedFindingSeverities");
		assertNull(answer.getFindingCitationExtent(), "findingCitations");
		assertNull(answer.getUnstatedDosingCeilings(), "unstatedDosingCeilings");
		assertNull(answer.getFindingPartnerCoverage(), "findingPartners");
		assertNull(answer.getInteractionClaimPairs(), "interactionClaimPairs");
	}

	@Test
	public void aProposedDrugTheModuleWithholdsIsAnsweredFromItsFindingsWithoutAskingTheModel() {
		List<Finding> findings = findingsInThePromptFor(PROPOSAL);
		assertFalse(findings.isEmpty(), "precondition: ibuprofen x her aspirin order raises a finding");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "the module resolved the question, so the model is not asked");
		// One brief line per finding, as a caution answer's (ADR Decision 153): the finding's first sentence with its
		// rating, and the class relationship it folded, which is what makes it withhold; the mechanism stays on the chip.
		assertEquals(1, findings.size(), "precondition: one finding, was: " + findings);
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ibuprofen.\n" + IBUPROFEN_LINE
				+ findings.get(0).index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", answer.getAnswer());
		assertNoModelProseWasJudged(answer);
		assertTrue(answer.isAnsweredByTheModule(), "and the answer says no model wrote it");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "the chips are produced as before");
		for (org.openmrs.module.chartsearchai.reference.SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(answer.getAnswer().contains("[" + chip.getFindingCitation() + "]"),
					"every chip beside a composed answer is a finding it cites. Missing: " + chip.getDetail());
		}
		assertNotNull(answer.getPairChipExtent(), "and so is the pair extent");
	}

	/**
	 * A chip whose finding the composed answer states is published as stated, so a client does not
	 * draw it a second time in full beneath the answer that just said it. Live on the 3.7.1 standalone,
	 * "Does she have any drug interactions I should know about?" came back composed from five findings,
	 * each line its chip's own detail, and all five chips beside it still published
	 * {@code statedInTheAnswer: false}.
	 */
	@Test
	public void everyChipTheComposedAnswerStatesIsPublishedAsStated() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module composed this answer");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the answer carries chips");
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(answer.getAnswer().contains(chip.getDetail()), "precondition: the answer states " + chip.getDetail());
			assertTrue(chip.isStatedInTheAnswer(), "a chip the composed answer states is published as stated: "
					+ chip.getDetail());
		}
	}

	@Test
	public void searchStreaming_publishesTheChipsTheComposedAnswerStatesAsStatedToo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).searchStreaming(patient, SCREEN, text -> { },
				reasoning -> { }, citations -> { }, early -> { });

		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module composed this answer");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the answer carries chips");
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(chip.isStatedInTheAnswer(), "the streaming path marks them alike: " + chip.getDetail());
		}
	}

	/** A proposal's "No" states brief lines, so no chip beside it is stated in full and each is shown beside it (ADR
	 *  Decision 153), as beside a caution answer. */
	@Test
	public void aChipBesideTheModulesBriefNoIsNotMarkedStated() {
		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, PROPOSAL);
		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module composed this answer");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the answer carries chips");
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(chip.isStatedInTheAnswer(), "a brief line does not state the chip in full: " + chip.getDetail());
		}
	}

	/** The control: where the model writes the answer, nothing marks a chip stated on this path. */
	@Test
	public void aChipBesideTheModelsAnswerIsNotMarkedStated() {
		answerFromFindings(false);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertFalse(answer.isAnsweredByTheModule(), "precondition: the model wrote this answer");
		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the answer carries chips");
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(chip.isStatedInTheAnswer(), "the model's answer states none of them: " + chip.getDetail());
		}
	}

	/** Every shape of proposal the grammar admits, each answered with the withholding call — delete a
	 *  shape and its question here goes to the model. */
	@Test
	public void aSuitabilityQuestionIsAnsweredFromTheFindingsToo() {
		answerFromFindings(true);
		for (String question : new String[] { "Is ibuprofen safe for her?", "Is ibuprofen appropriate for her?",
				"Can this patient take ibuprofen?", "Is it safe to give her ibuprofen?",
				"Would ibuprofen be appropriate for her?", "Can I give ibuprofen to her?",
				// The patient after the drug in the "is it safe to" shape (issue #548).
				"Is it safe to give ibuprofen for her?",
				// The drug before "safe to", and the drug as the passive subject (ADR Decision 134).
				"Is ibuprofen safe to add?", "Is ibuprofen safe to give her?", "Is ibuprofen okay to start?",
				"Can ibuprofen be started?", "Can ibuprofen be given to her?", "Should ibuprofen be added now?" }) {
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
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ibuprofen.\n" + IBUPROFEN_LINE
				+ findings.get(0).index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", answer.getAnswer());
		assertNoModelProseWasJudged(answer);
		assertTrue(answer.isAnsweredByTheModule(), "the returned answer says so");
		assertEquals(1, early.size(), "the early done fired");
		assertTrue(early.get(0).isAnsweredByTheModule(),
				"and so does the early done, which is the event a streaming user sees");
		assertEquals(answer.getAnswer(), early.get(0).getAnswer());
	}

	/**
	 * A proposed drug withheld only by a contraindication — here her recorded allergy to the very drug — is
	 * answered by the model: the "No" would rest on a curated rule's token matched against her records' free
	 * text, which review found false three ways, and a withholding clause beside the cautions leaves no
	 * caution-only answer to compose either (ADR Decision 140).
	 */
	@Test
	public void aProposalNoInteractionWithholdsStillAsksTheModel() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Omeprazole");
		assertTheModelIsAsked("Can I give her omeprazole?");
	}

	/**
	 * A proposed drug whose findings are all interaction cautions about it is answered from them (ADR Decision
	 * 140), briefly: a lead counting them, then one line per finding — its first sentence, which names her order
	 * and the rating, and any sentence saying the interaction's clinical significance is unknown, cited by its
	 * own number and her order's record. Never "can be given", a clearance nothing here can establish. The
	 * mechanism prose stays on the chip, which the answer therefore no longer states. Omeprazole relates
	 * Moderate to her warfarin and Minor to her aspirin, the second qualified.
	 */
	@Test
	public void aProposalWhoseFindingsAreAllCautionsIsAnsweredFromThemWithoutAClearance() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her omeprazole?";
		// The finding the case is about must reach the prompt, as a caution, beside nothing that
		// withholds: without her warfarin order a finding is still raised, so the model is still asked and
		// the assertion below would pass with the Moderate finding it is about absent (issue #479).
		boolean moderateWarfarinCaution = false;
		List<Finding> findings = findingsInThePromptFor(question);
		for (Finding finding : findings) {
			assertFalse(finding.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
					"precondition: no finding withholds omeprazole, was: " + finding.text);
			if (finding.text.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Warfarin — Moderate.")
					&& finding.text.endsWith(DrugReferenceInjector.STRENGTH_CAUTION)) {
				moderateWarfarinCaution = true;
			}
		}
		assertTrue(moderateWarfarinCaution,
				"precondition: the Moderate warfarin finding reached the prompt as a caution, findings were: "
						+ findings);
		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked");
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals(2, findings.size(), "precondition: the two cautions, were: " + findings);
		Finding warfarin = findings.get(0).text.contains("active order Warfarin") ? findings.get(0) : findings.get(1);
		Finding aspirin = warfarin == findings.get(0) ? findings.get(1) : findings.get(0);
		assertEquals("2 interaction cautions for Omeprazole:\n"
				+ "Omeprazole interacts with active order Warfarin — Moderate. [" + warfarin.index + "] ["
				+ recordOf(answer, WARFARIN_ORDER_UUID) + "]\n"
				+ "Omeprazole interacts with active order Acetylsalicylic acid (aspirin) — Minor. The clinical "
				+ "significance of this interaction is unknown. [" + aspirin.index + "] ["
				+ recordOf(answer, ASPIRIN_ORDER_UUID) + "]", answer.getAnswer());
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(chip.isStatedInTheAnswer(),
					"the answer states no chip's detail in full, so each is shown beside it: " + chip);
		}
	}

	/**
	 * A proposed drug whose only relationships to her orders are rows below the severity floor — no finding at all —
	 * is answered by the module with those rows (ADR Decisions 142, 144): a bottom line scoped to the interaction
	 * data, the orders and what the data says of them, citing the drug's reference record and each order's, then
	 * what that bottom line does not cover. Never "should not be given", which the model wrote over four Unknown
	 * rows; never a clearance. Clarithromycin relates to her aspirin only in DDInter's Unknown tier.
	 */
	@Test
	public void aProposalRelatedToHerOrdersOnlyBelowTheFloorIsAnsweredWithThoseRows() {
		String question = "Can I give her clarithromycin?";
		assertTrue(findingsInThePromptFor(question).isEmpty(), "precondition: no finding is raised");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked");
		assertTrue(answer.isAnsweredByTheModule());
		int reference = -1;
		for (RecordReference ref : answer.getReferences()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(ref.getResourceType())) {
				reference = ref.getIndex();
			}
		}
		// Her order by its own display — the standard dataset's drug name, as the chart records it. DDInter says
		// this row carries no mechanism, so the answer may say so (ADR Decision 144).
		assertEquals("The interaction data gives no rated reason to withhold Clarithromycin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + reference + "]\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.", answer.getAnswer());
		assertCitesNoOrderOfHers(answer);
	}

	/**
	 * Below a floor an install raised, a pair carries a rating of its own, and the lead says the rows are
	 * lower-rated rather than of unknown severity (ADR Decision 142): with the floor at Moderate, omeprazole's
	 * Minor row against her aspirin is below it, and is the only relationship.
	 */
	@Test
	public void aPairBelowARaisedFloorIsStatedWithItsOwnRating() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "moderate");
		String question = "Can I give her omeprazole?";
		assertTrue(findingsInThePromptFor(question).isEmpty(), "precondition: the Minor row raises no finding");

		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, question);

		int reference = -1;
		for (RecordReference ref : answer.getReferences()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(ref.getResourceType())) {
				reference = ref.getIndex();
			}
		}
		assertEquals("The interaction data gives no rated reason to withhold Omeprazole: its row against this "
				+ "patient's orders is rated Minor, below the level this module reports as a finding. [" + reference
				+ "]\n" + "Interactions the data does not rate, and anything beyond drug interactions, are not covered.", answer.getAnswer());
	}

	/** A question that names the drug without proposing it keeps the model's answer, below-floor rows or not. */
	@Test
	public void aBelowFloorQuestionThatProposesNothingStillAsksTheModel() {
		String question = "Does clarithromycin interact with her medications?";
		RecordingProvider precondition = new RecordingProvider();
		answerFromFindings(false);
		ChartAnswer off = serviceWith(precondition).search(patient, question);
		answerFromFindings(true);
		assertTrue(off.getPairChipExtent() != null && !off.getPairChipExtent().getBelowFloor().isEmpty(),
				"precondition: the question relates clarithromycin to her aspirin below the floor");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * A pair whose order has not started keeps the model's answer (ADR Decision 142): the line would call it her
	 * active order. Patient 6 holds only an aspirin order scheduled for 2099.
	 */
	@Test
	public void aBelowFloorPairOnAnOrderThatHasNotStartedStillAsksTheModel() {
		executeDataSet("ScheduledAspirinOrderTestData.xml");
		Patient six = Context.getPatientService().getPatient(6);
		String question = "Can I give her clarithromycin?";
		answerFromFindings(false);
		ChartAnswer off = serviceWith(new RecordingProvider()).search(six, question);
		answerFromFindings(true);
		assertTrue(off.getPairChipExtent() != null && !off.getPairChipExtent().getBelowFloor().isEmpty(),
				"precondition: clarithromycin relates to her scheduled aspirin below the floor, was: "
						+ off.getPairChipExtent());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(six, question);

		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * A source that does not say whether a row carries a mechanism — every source but DDInter's — never has the
	 * answer say none is on file (ADR Decision 144): the row here carries one, rated Unknown.
	 */
	@Test
	public void aSourceSilentOnMechanismsIsNotSaidToCarryNone() throws Exception {
		DrugReferenceService reference = DrugReferenceTestSupport.curatedFixtureService(
				"chartsearchai-test/drug-reference-below-floor-source-states-no-mechanism-flag.json");
		ChartAnswer answer = serviceWith(new RecordingProvider(), reference).search(patient,
				"Can I give her clarithromycin?");

		assertTrue(answer.isAnsweredByTheModule(), "precondition: answered by the module: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains("its row against this patient's orders carries no severity. ["),
				answer.getAnswer());
		assertFalse(answer.getAnswer().contains("mechanism"), answer.getAnswer());
	}

	/**
	 * A caution answer states its findings and nothing of the drug's rows below the severity floor (ADR Decision 146):
	 * metformin's Moderate caution against her warfarin is the finding; the data also lists metformin against her
	 * aspirin, rated Unknown with no mechanism, which a closing line once stated and which told a clinician nothing
	 * they could use.
	 */
	@Test
	public void aCautionAnswerStatesItsFindingsAndNotItsRowsBelowTheFloor() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her metformin?";
		List<Finding> findings = findingsInThePromptFor(question);
		assertEquals(1, findings.size(), "precondition: one finding, " + findings);
		assertTrue(findings.get(0).text.endsWith(DrugReferenceInjector.STRENGTH_CAUTION),
				"precondition: a caution, " + findings.get(0).text);

		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, question);

		assertTrue(answer.getPairChipExtent() != null && !answer.getPairChipExtent().getBelowFloor().isEmpty(),
				"precondition: the data does list metformin below the floor, " + answer.getPairChipExtent());
		assertEquals(2, answer.getAnswer().split("\n").length, "the lead and the finding: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains("The interaction data"), answer.getAnswer());
	}

	/**
	 * An order a composed answer states no finding about is not what the answer is about (ADR Decisions 145, 146): her
	 * recorded aspirin allergy against her aspirin order is not raised beside an answer about metformin that states
	 * only its warfarin finding, nor beside one about clarithromycin made only of rows below the floor, which cites the
	 * data and none of her orders. The chips beside a composed answer are scoped by the orders its FINDINGS are about.
	 */
	@Test
	public void aCautionAnswerDoesNotBringTheConflictsOfAnOrderItStatesNoFindingAbout() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertNoAspirinConflictBeside("Can I give her metformin?", false);
	}

	/** {@link #aCautionAnswerDoesNotBringTheConflictsOfAnOrderItStatesNoFindingAbout}, for an answer made only
	 *  of rows below the floor: clarithromycin relates to her aspirin in DDInter's Unknown tier alone. */
	@Test
	public void anOrderListedOnlyOnABelowFloorAnswerDoesNotBringItsConflictsBesideIt() {
		assertNoAspirinConflictBeside("Can I give her clarithromycin?", true);
	}

	private void assertNoAspirinConflictBeside(String question, boolean statesTheRows) {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		ChartAnswer allergies = serviceWith(new RecordingProvider()).search(patient, "What is she allergic to?");
		assertTrue(aspirinConflictIn(allergies), "precondition: her aspirin order against her aspirin allergy is a chip "
				+ "where the response is about it, was: " + allergies.getSafetyWarnings());
		{
			ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, question);

			assertTrue(answer.isAnsweredByTheModule(), "precondition: " + answer.getAnswer());
			assertEquals(statesTheRows, answer.getAnswer().contains("The interaction data "),
					"precondition: whether the answer states the rows below the floor: " + answer.getAnswer());
			assertCitesNoOrderOfHers(answer);
			assertFalse(aspirinConflictIn(answer),
					question + ": her aspirin conflict is not beside this answer: " + answer.getSafetyWarnings());
		}
	}

	/** A line listing rows below the floor cites the data that lists them and none of her orders (ADR Decision 146). */
	private static void assertCitesNoOrderOfHers(ChartAnswer answer) {
		for (RecordReference reference : answer.getReferences()) {
			assertFalse(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER.equals(reference.getResourceType())
					&& ASPIRIN_ORDER_UUID.equals(reference.getResourceUuid()),
					"her aspirin order, listed only below the floor, is not cited: " + answer.getAnswer());
		}
	}

	private static boolean aspirinConflictIn(ChartAnswer answer) {
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType())
					&& chip.getDrug().toLowerCase(java.util.Locale.ROOT).contains("aspirin")) {
				return true;
			}
		}
		return false;
	}

	/** The same answer however the proposal is worded, since no model words it (ADR Decision 142). */
	@Test
	public void aBelowFloorAnswerDoesNotDependOnHowTheProposalIsWorded() {
		ChartAnswer can = serviceWith(new RecordingProvider()).search(patient, "Can I give her clarithromycin?");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer should = serviceWith(provider).search(patient, "Should I give her clarithromycin?");

		assertEquals(0, provider.calls, "no model is asked");
		assertEquals(can.getAnswer(), should.getAnswer());
		assertEquals(ChartAnswerTestSupport.referenceIndexes(can), ChartAnswerTestSupport.referenceIndexes(should));
	}

	/**
	 * A proposed drug the reference data relates to NONE of her orders, at any rating — no finding, the extent stated
	 * with nothing found and nothing below the floor — is answered by the module with what the check established
	 * (issue #592): the drug, and how many of her medications it was compared against, citing the drug's reference
	 * record. Never "can be given" and never "no interactions" as a claim about the patient; the model answered
	 * "The records do not address Mebendazole.", which reads like a drug nobody looked up. The shipped knowledge base
	 * relates mebendazole to her aspirin in no row — the bundled excerpt carries neither drug, so these cases read the
	 * shipped data.
	 */
	@Test
	public void aProposalTheDataRelatesToNoneOfHerOrdersIsAnsweredWithWhatTheCheckEstablished() {
		String question = "Can I give her mebendazole?";
		assertTheCheckRelatedNothing(patient, question, shipped());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("The interaction check relates Mebendazole to none of this patient's 1 active medication. ["
				+ referenceRecordOf(answer, "mebendazole") + "]", answer.getAnswer());
	}

	/** Each of her medications the drug was compared against is counted (issue #592): beside a metformin order she
	 *  has two, and the shipped data relates mebendazole to neither. */
	@Test
	public void anAnswerOfNoPairCountsEveryMedicationTheCheckComparedTheDrugAgainst() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		String question = "Can I give her mebendazole?";
		assertTheCheckRelatedNothing(patient, question, shipped());

		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);

		assertEquals("The interaction check relates Mebendazole to none of this patient's 2 active medications. ["
				+ referenceRecordOf(answer, "mebendazole") + "]", answer.getAnswer());
	}

	/** The count is of her active MEDICATIONS, the prescriptions her medication list shows, and not of the
	 *  substances they resolve to (issue #592): beside one prescription of a lamivudine / stavudine combination
	 *  she has two medications and three substances. */
	@Test
	public void anAnswerOfNoPairCountsACombinationPrescriptionAsOneMedication() throws Exception {
		executeDataSet("AnswerFromFindingsLamivudineStavudineOrderTestData.xml");
		String question = "Can I give her mebendazole?";
		assertTheCheckRelatedNothing(patient, question, shipped());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("The interaction check relates Mebendazole to none of this patient's 2 active medications. ["
				+ referenceRecordOf(answer, "mebendazole") + "]", answer.getAnswer());
	}

	/** A pair below the floor that the below-floor answer cannot state — her order, known by its ATC code alone,
	 *  names no drug a line could print — still relates the drug to her, so the no-pair answer must not say it
	 *  relates the drug to none of her medications: the model answers (issue #592). Her aspirin's entry carries no
	 *  row naming subfloran, so the empty-{@code belowFloor} conjunct is all that refuses it. */
	@Test
	public void aProposalRelatedToHerOrdersOnlyByAPairNoLineCanStateStillAsksTheModel() throws Exception {
		DrugReferenceTestSupport.mapConceptToAtc(88, "B01AC06");
		DrugReferenceTestSupport.makeOrderNameless(111, 88);
		DrugReferenceService reference = DrugReferenceTestSupport.curatedFixtureService(BELOW_FLOOR_CODES_ONLY);
		String question = "Can I give her subfloran?";
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		ChartAnswer off = serviceWith(recorder, reference).search(patient, question);
		answerFromFindings(true);
		assertFalse(FINDING_LINE.matcher(recorder.lastRecords).find(),
				"precondition: no finding is raised: " + recorder.lastRecords);
		PairChipExtent extent = off.getPairChipExtent();
		assertTrue(extent != null && extent.getFound() == 0 && extent.getBelowFloor() != null
				&& extent.getBelowFloor().size() == 1,
				"precondition: one pair below the floor, was: " + extent);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, question);

		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** Beside an order that has not started, the answer would count it among her active medications, so the model
	 *  answers (issue #592) — Decision 142 refuses a line on such an order for the same reason. Patient 6 holds only
	 *  an aspirin order scheduled for 2099. */
	@Test
	public void aProposalOfNoPairBesideAnOrderThatHasNotStartedStillAsksTheModel() throws Exception {
		executeDataSet("ScheduledAspirinOrderTestData.xml");
		Patient six = Context.getPatientService().getPatient(6);
		assertTheModelAnswersWhatTheCheckRelatedToNothing(six, "Can I give her mebendazole?", shipped());
	}

	/** The same answer however the proposal is worded, since no model words it (issue #592). */
	@Test
	public void anAnswerOfNoPairDoesNotDependOnHowTheProposalIsWorded() {
		ChartAnswer can = serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her mebendazole?");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer should = serviceWith(provider, shipped()).search(patient, "Should I give her mebendazole?");

		assertEquals(0, provider.calls, "no model is asked");
		assertEquals(can.getAnswer(), should.getAnswer());
		assertEquals(ChartAnswerTestSupport.referenceIndexes(can), ChartAnswerTestSupport.referenceIndexes(should));
	}

	/** A question that names the drug without proposing it keeps the model's answer (issue #592). */
	@Test
	public void aQuestionOfNoPairThatProposesNothingStillAsksTheModel() {
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Does mebendazole interact with her medications?",
				shipped());
	}

	/** Without the drug's reference record in the chart the answer would cite nothing for it, so the model answers
	 *  (issue #592): the question leg switched off injects no record for a drug she does not take. */
	@Test
	public void aProposalOfNoPairWhoseReferenceRecordIsNotInTheChartStillAsksTheModel() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY, "false");
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her mebendazole?", shipped());
	}

	/** Beside an order the data cannot name, "none of her medications" is not established, so the model answers
	 *  (issue #592) — the read conjunct Decision 142's composition stands on too. */
	@Test
	public void aProposalOfNoPairBesideAnOrderTheDataCannotNameStillAsksTheModel() throws Exception {
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her mebendazole?", shipped());
	}

	/** The arm reads the PROPOSED drug's rows; a curated file need not mirror a pair, so a row only her aspirin's
	 *  entry carries relates zolfixan to her while the arm relates nothing — the model answers (issue #592). */
	@Test
	public void aProposalARowOfHerOwnOrdersNamesStillAsksTheModel() throws Exception {
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her zolfixan?",
				DrugReferenceTestSupport.curatedFixtureService(ONE_DIRECTION));
	}

	/** A drug whose own entry carries no interaction row was compared against nothing, so "relates it to none of
	 *  her medications" would report a check that never ran — the model answers (issue #592). */
	@Test
	public void aProposalOfADrugWithNoInteractionRowsStillAsksTheModel() throws Exception {
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her nointerax?",
				DrugReferenceTestSupport.curatedFixtureService(ONE_DIRECTION));
	}

	/** With the contraindication arms switched off, an allergy to the drug proposed raises nothing, so the module
	 *  cannot answer for a check of her records it did not run — the model answers (issue #592). */
	@Test
	public void aProposalOfNoPairWithTheContraindicationArmsOffStillAsksTheModel() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her mebendazole?", shipped());
	}

	/**
	 * A proposal after a list of drugs the question says she is on is answered by the module where the check raised
	 * nothing about the drug proposed (ADR Decision 149): what the check established against her own orders, as the
	 * same proposal alone is answered (ADR Decision 142), then that its check against the drugs listed raised no
	 * finding, then that her chart holds none of them. Asked of Susan on the 3.7.1 standalone, the model answered
	 * "The records do not address the safety of giving Metformin.". The shipped data relates metformin to her aspirin
	 * and to each drug listed only by rows rated Unknown.
	 */
	@Test
	public void aProposalAfterAListIsAnsweredWithWhatTheCheckEstablishedAgainstHerOrdersAndTheList() {
		String question = "The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to give metformin?";
		assertTrue(findingsInThePromptFor(question, shipped()).isEmpty(), "precondition: no finding is raised");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + referenceRecordOf(answer, "metformin") + "]\n"
				+ "The check of Metformin against Lamivudine, Nevirapine and Stavudine, also named in the question, "
				+ "raised no finding.\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.\n"
				+ "The chart holds no active order for Lamivudine, Nevirapine or Stavudine.", answer.getAnswer());
		assertCitesNoOrderOfHers(answer);
	}

	/**
	 * A CAUTION about a listed drug against her own order is not about the drug proposed, and the answer is still the
	 * module's (ADR Decision 149) — Susan's shape, whose listed nevirapine relates to her lidocaine Minor. Here the
	 * listed atenolol relates to her aspirin Minor, and to metformin only Unknown.
	 */
	@Test
	public void aCautionAboutAListedDrugDoesNotKeepTheModelCall() {
		String question = "The patient is currently on Atenolol, is it safe to give metformin?";
		assertFalse(findingsInThePromptFor(question, shipped()).isEmpty(),
				"precondition: the listed atenolol raises a finding against her aspirin");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + referenceRecordOf(answer, "metformin") + "]\n"
				+ "The check of Metformin against Atenolol, also named in the question, raised no finding.\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.\n"
				+ "The chart holds no active order for Atenolol.", answer.getAnswer());
	}

	/** A proposal after a list that the data relates to none of her orders is answered with Decision 143's sentence,
	 *  then the list's (ADR Decision 149): the shipped data relates mebendazole to neither her aspirin nor lamivudine. */
	@Test
	public void aProposalAfterAListThatRelatesToNoneOfHerOrdersIsAnsweredWithWhatTheCheckEstablished() {
		String question = "The patient is currently on Lamivudine, is it safe to give mebendazole?";
		assertTrue(findingsInThePromptFor(question, shipped()).isEmpty(), "precondition: no finding is raised");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("The interaction check relates Mebendazole to none of this patient's 1 active medication. ["
				+ referenceRecordOf(answer, "mebendazole") + "]\n"
				+ "The check of Mebendazole against Lamivudine, also named in the question, raised no finding.\n"
				+ "The chart holds no active order for Lamivudine.", answer.getAnswer());
	}

	/** A finding about a listed drug alone neither keeps the model call nor reaches the answer, even one that
	 *  withholds (ADR Decision 150): it is not about the drug proposed, and its chip is not published (ADR Decision
	 *  148). Methotrexate relates to her aspirin Major, and to metformin only Unknown. */
	@Test
	public void aWithholdingFindingAboutAListedDrugAloneDoesNotKeepTheModelCall() {
		String question = "The patient is currently on Methotrexate, is it safe to give metformin?";
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		boolean listedMajor = false;
		for (Finding finding : findings) {
			listedMajor |= finding.text.startsWith("Methotrexate") && finding.text.contains("— Major.")
					&& finding.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD);
		}
		assertTrue(listedMajor, "precondition: methotrexate withholds against her aspirin, findings were: " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + referenceRecordOf(answer, "metformin") + "]\n"
				+ "The check of Metformin against Methotrexate, also named in the question, raised no finding.\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.\n"
				+ "The chart holds no active order for Methotrexate.", answer.getAnswer());
	}

	/**
	 * A list question whose findings about the drug proposed withhold it only against a drug the question lists, which
	 * her chart does not hold, is answered "No if she is on" that drug (ADR Decision 150). Susan, asked of fluconazole
	 * after a list carrying efavirenz, was answered "Fluconazole can be given, with two cautions", leaving out the
	 * Major the data rates it with efavirenz. One brief line per finding about the drug proposed, strongest first.
	 */
	@Test
	public void aListQuestionWithholdingOnlyAgainstAListedDrugIsAnsweredNoIfSheIsOnIt() {
		String question = "The patient is currently on Efavirenz, Zidovudine, is it safe to give fluconazole?";
		Finding efavirenz = findingNamed(question, "Fluconazole interacts with Efavirenz");
		Finding zidovudine = findingNamed(question, "Fluconazole interacts with Zidovudine");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("No if she is on Efavirenz — this module's drug-safety check found a reason to withhold "
				+ "Fluconazole against it.\n"
				+ "Fluconazole interacts with Efavirenz, also named in the question — Major. [" + efavirenz.index + "]\n"
				+ "Fluconazole interacts with Zidovudine, also named in the question — Moderate. [" + zidovudine.index
				+ "]\n"
				+ "The chart holds no active order for Efavirenz or Zidovudine.", answer.getAnswer());
	}

	/** A finding withholding the drug proposed against one of her OWN orders leads with the unconditional "No" (ADR
	 *  Decisions 108, 150): ibuprofen relates to her aspirin Major, and to the listed zidovudine Moderate. */
	@Test
	public void aListQuestionWithholdingAgainstHerOwnOrderIsAnsweredNo() {
		String question = "The patient is currently on Zidovudine, is it safe to give ibuprofen?";
		Finding aspirin = findingNamed(question, "Ibuprofen interacts with active order Acetylsalicylic acid (aspirin)");
		Finding zidovudine = findingNamed(question, "Ibuprofen interacts with Zidovudine");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"
				+ "Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. Ibuprofen is in the same "
				+ "cross-reactivity group (NSAID) as active order Acetylsalicylic acid (aspirin) — possible additive or "
				+ "duplicate-class therapy. [" + aspirin.index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]\n"
				+ "Ibuprofen interacts with Zidovudine, also named in the question — Moderate. [" + zidovudine.index
				+ "]\n"
				+ "The chart holds no active order for Zidovudine.", answer.getAnswer());
	}

	/** Cautions alone take Decision 140's lead, a caution against her own order before one against a listed drug (ADR
	 *  Decision 150): amlodipine relates Moderate to her aspirin and to the listed efavirenz. */
	@Test
	public void aListQuestionOfCautionsStatesHerOwnOrdersFirst() {
		String question = "The patient is currently on Efavirenz, is it safe to give amlodipine?";
		Finding aspirin = findingNamed(question, "Amlodipine interacts with active order Acetylsalicylic acid (aspirin)");
		Finding efavirenz = findingNamed(question, "Amlodipine interacts with Efavirenz");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("2 interaction cautions for Amlodipine:\n"
				+ "Amlodipine interacts with active order Acetylsalicylic acid (aspirin) — Moderate. [" + aspirin.index
				+ "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]\n"
				+ "Amlodipine interacts with Efavirenz, also named in the question — Moderate. [" + efavirenz.index
				+ "]\n"
				+ "The chart holds no active order for Efavirenz.", answer.getAnswer());
	}

	/** A contraindication about the drug proposed keeps the model call (ADR Decisions 108, 150): its match against her
	 *  records' free text is not one the module can vouch for. She is recorded allergic to fluconazole. */
	@Test
	public void aListQuestionWithAContraindicationAboutTheDrugProposedStillAsksTheModel() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Fluconazole");
		assertTheModelIsAsked("The patient is currently on Nevirapine, is it safe to give fluconazole?", shipped());
	}

	/** An unrated rule withholding the drug proposed licenses no "No", after a list as alone (ADR Decisions 108, 150):
	 *  the curated paracetamol rule against her warfarin carries no rating. */
	@Test
	public void aListQuestionWhoseFindingIsAnUnratedRuleStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceService curated = DrugReferenceTestSupport
				.curatedFixtureService("chartsearchai-test/drug-reference-answer-from-findings-unrated-rule.json");
		String question = "The patient is currently on Ibuprofen, is it safe to give paracetamol?";
		boolean unrated = false;
		for (Finding finding : findingsInThePromptFor(question, curated)) {
			unrated |= finding.text.startsWith("Paracetamol interacts with active order")
					&& finding.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD);
		}
		assertTrue(unrated, "precondition: an unrated interaction withholds paracetamol");
		assertTheModelIsAsked(question, curated);
	}

	/** A drug she already takes is not proposed, after a list as alone (ADR Decisions 108, 150): her warfarin. */
	@Test
	public void aListQuestionProposingADrugSheAlreadyTakesStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertTheModelIsAsked("The patient is currently on Ibuprofen, is it safe to give warfarin?");
	}

	/**
	 * A question asking whether she has ever taken a drug puts no finding about GIVING it in the prompt (ADR Decision
	 * 151): <em>"has she ever taken panadol?"</em>, asked of Susan, was answered <em>"The records indicate that
	 * Acetaminophen can be given, but there are cautions…"</em> from the two interaction findings the prompt carried.
	 * Ibuprofen relates to her aspirin Major, which a proposal of it carries.
	 */
	@Test
	public void aHistoryQuestionCarriesNoFindingAboutGivingTheDrug() {
		boolean proposalCarriesIt = false;
		for (Finding finding : findingsInThePromptFor("Can I give her ibuprofen?", shipped())) {
			proposalCarriesIt |= finding.text.startsWith("Ibuprofen interacts with active order");
		}
		assertTrue(proposalCarriesIt, "precondition: a proposal of ibuprofen carries its finding against her aspirin");

		for (String question : new String[] { "Has she ever taken ibuprofen?", "Was she ever on ibuprofen?" }) {
			assertEquals(Collections.emptyList(), findingsInThePromptFor(question, shipped()),
					question + " carries no finding about giving ibuprofen");
		}
	}

	/** On a question asking whether she has ever taken a drug she IS taking, that drug's conflicts with her other
	 *  orders stay (ADR Decision 151): they are her chart's, not a proposal's. Her warfarin relates Major to her aspirin. */
	@Test
	public void aHistoryQuestionAboutHerOwnMedicationKeepsItsChips() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		boolean prompted = false;
		for (Finding finding : findingsInThePromptFor("Has she ever taken warfarin?")) {
			prompted |= finding.text.startsWith("Warfarin interacts with active order");
		}
		assertTrue(prompted, "her warfarin's interaction with her aspirin stays in the prompt too");
		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, "Has she ever taken warfarin?");
		boolean kept = false;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			kept |= SafetyWarning.TYPE_INTERACTION.equals(chip.getType()) && chip.getDrug().startsWith("Warfarin")
					&& chip.isAboutACurrentMedication();
		}
		assertTrue(kept, "her warfarin's interaction with her aspirin stays, chips were: " + answer.getSafetyWarnings());
	}

	/** On a question asking whether she has ever taken a drug, her recorded allergy to it stays (ADR Decision 151): it
	 *  is about the drug's history with her, while the interaction chips about giving it come off. */
	@Test
	public void aHistoryQuestionKeepsHerAllergyToTheDrug() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Fluconazole");
		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient,
				"Has she ever taken fluconazole?");
		boolean allergy = false;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			allergy |= SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType());
			assertFalse(SafetyWarning.TYPE_INTERACTION.equals(chip.getType()) && "Fluconazole".equals(chip.getDrug()),
					"no interaction chip about giving fluconazole, was: " + chip);
		}
		assertTrue(allergy, "her recorded allergy to fluconazole stays, chips were: " + answer.getSafetyWarnings());
	}

	/**
	 * A question whose first word lost its leading letters is read as the word it was clipped from, where the rest
	 * then fits a shape exactly (ADR Decision 152): <em>"s it safe to give metformin?"</em>, asked of Susan, was answered
	 * by the model "The records do not address the safety of giving Metformin." while <em>"Is it safe to give
	 * metformin?"</em> got the module's answer. Each clipped proposal gets its full form's answer.
	 */
	@Test
	public void aProposalWhoseFirstWordLostItsLeadingLettersGetsItsFullFormsAnswer() {
		String[][] pairs = { { "s it safe to give her mebendazole?", "Is it safe to give her mebendazole?" },
				{ "an I give her mebendazole?", "Can I give her mebendazole?" },
				{ "hould I give her mebendazole?", "Should I give her mebendazole?" } };
		for (String[] pair : pairs) {
			ChartAnswer full = serviceWith(new RecordingProvider(), shipped()).search(patient, pair[1]);
			assertTrue(full.isAnsweredByTheModule(), "precondition: the module answers " + pair[1]);
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer clipped = serviceWith(provider, shipped()).search(patient, pair[0]);
			assertEquals(0, provider.calls, pair[0] + " asks no model: " + clipped.getAnswer());
			assertEquals(full.getAnswer(), clipped.getAnswer(), pair[0]);
		}
	}

	/** A first word that is not the clipped end of a shape's own leading word keeps the model call (ADR Decision 152):
	 *  a typo inside the word is not guessed at. */
	@Test
	public void aFirstWordMistypedOtherThanByClippingStillAsksTheModel() {
		for (String question : new String[] { "Ts it safe to give her mebendazole?", "Cna I give her mebendazole?",
				"it safe to give her mebendazole?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);
			assertEquals(1, provider.calls, question + " asks the model: " + answer.getAnswer());
		}
	}

	/** The history grammar reads a clipped first word too (ADR Decisions 151, 152): "as she ever taken ibuprofen?"
	 *  carries no finding about giving ibuprofen, as its full form does not. */
	@Test
	public void aHistoryQuestionWhoseFirstWordLostItsLeadingLetterIsStillOne() {
		assertEquals(Collections.emptyList(), findingsInThePromptFor("as she ever taken ibuprofen?", shipped()));
	}

	/**
	 * A module's "No" states one brief line per finding, as its cautions do, and a line whose finding folded a class
	 * relationship onto the rule keeps that class sentence (ADR Decision 153). <em>"Is gentamicin appropriate for this
	 * patient?"</em>, asked of Susan, stated every finding's whole mechanism paragraph under its "No". Clopidogrel relates
	 * Major to her warfarin and Moderate to her aspirin, with which it shares a platelet-inhibitor subgroup.
	 */
	@Test
	public void aModulesNoStatesBriefLinesKeepingAFoldedClassSentence() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her clopidogrel?";
		Finding warfarin = findingNamed(question, "Clopidogrel interacts with active order Warfarin");
		Finding aspirin = findingNamed(question, "Clopidogrel interacts with active order Acetylsalicylic acid (aspirin)");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Clopidogrel.\n"
				+ "Clopidogrel interacts with active order Warfarin — Major. [" + warfarin.index + "] ["
				+ recordOf(answer, WARFARIN_ORDER_UUID) + "]\n"
				+ "Clopidogrel interacts with active order Acetylsalicylic acid (aspirin) — Moderate. Clopidogrel is in the "
				+ "same ATC class (B01AC) as active order Acetylsalicylic acid (aspirin) — possible duplicate therapy. ["
				+ aspirin.index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", answer.getAnswer());
	}

	/**
	 * A question asking whether she has ever taken a drug no order of hers ever carried is answered by the module with
	 * that, scoped to orders (ADR Decision 154). <em>"has she ever taken aspirin?"</em>, asked of Susan, whose chart has
	 * never held it, was answered "The records do not address aspirin." The shipped data's mebendazole is no order of
	 * this patient's, active or ended.
	 */
	@Test
	public void aDrugNoOrderOfHersEverCarriedIsAnsweredWithThatScopedToOrders() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Has she ever taken mebendazole?");

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("This patient's chart records no Mebendazole order, active or ended.\n"
				+ "A drug recorded only in a note, or given outside this chart, is not covered.", answer.getAnswer());
	}

	/** A drug she is taking, where the question's chart carries no record of her order to cite, keeps the model call
	 *  (ADR Decisions 154, 155): her aspirin, beside a chart of one observation. */
	@Test
	public void aHistoryQuestionAboutADrugSheTakesStillAsksTheModel() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Has she ever taken aspirin?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
	}

	/** A drug she had only as an ENDED order is not answered "no order" (ADR Decision 154), and where the question's chart
	 *  carries no record of that order to cite, the model answers (ADR Decision 155): patient 6's one drug order,
	 *  Triomune-30, lapsed in 2008, and lamivudine is one of its substances. */
	@Test
	public void aHistoryQuestionAboutADrugOnlyAnEndedOrderCarriedStillAsksTheModel() throws Exception {
		executeDataSet("DrugOrderCurrencyTestData.xml");
		Patient six = Context.getPatientService().getPatient(6);
		RecordingProvider control = new RecordingProvider();
		ChartAnswer none = serviceWith(control, shipped()).search(six, "Has she ever taken mebendazole?");
		assertEquals(0, control.calls, "positive control, the module answers for a drug no order carried: "
				+ none.getAnswer());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(six, "Has she ever taken lamivudine?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
	}

	/** A chart record naming the drug keeps the model call (ADR Decision 154): it may record the drug given outside an
	 *  order, which the answer's scope would otherwise only disclaim. */
	@Test
	public void aHistoryQuestionWhoseDrugAChartRecordNamesStillAsksTheModel() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWithChart(provider, shipped(),
				DrugReferenceTestSupport.obsRecord(1, "Mebendazole 100 mg given at the clinic")).search(patient,
						"Has she ever taken mebendazole?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
	}

	/** An order the data cannot resolve may be the drug asked about, so the model answers (ADR Decision 154). */
	@Test
	public void aHistoryQuestionBesideAnOrderTheDataCannotNameStillAsksTheModel() throws Exception {
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Has she ever taken mebendazole?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
	}

	/**
	 * A question whether she has ever taken a drug she is ON is answered by the module with every order that carried it
	 * and whether each is in force, by its record's own stamps (ADR Decision 155). <em>"Has she ever taken
	 * Metoclopramide?"</em>, asked of Susan, who is on it, was answered "Yes — Metoclopramide was ordered on 2026-08-03
	 * [8]", which did not say she still is. Patient 7's aspirin is two orders: order 1, stopped on 2008-08-15, and
	 * order 111, its revision, in force — the first of which core hands back as a Hibernate proxy, which the order-history
	 * read once skipped.
	 */
	@Test
	public void aHistoryQuestionAboutADrugSheIsOnStatesEveryOrderAndWhichIsInForce() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWithChart(provider, shipped(), DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				new RecordMapping(2, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, ASPIRIN_ORDER_UUID,
						day("2008-08-15"), "Drug order: Aspirin", null, 0, Boolean.TRUE),
				new RecordMapping(3, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, FIRST_ASPIRIN_ORDER_UUID,
						day("2008-08-08"), "Drug order: Aspirin", null, 0, Boolean.FALSE, day("2008-08-15"))).search(
								patient, "Has she ever taken aspirin?");

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("This patient's chart records 2 Acetylsalicylic acid (aspirin) orders:\n"
				+ "ASPIRIN — ended 2008-08-15, ordered 2008-08-08. [3]\n"
				+ "ASPIRIN — active, ordered 2008-08-15. [2]", answer.getAnswer());
	}

	/** Every order that carried the drug must be citable, or the answer would list some of them as all (ADR Decision
	 *  155): the chart carries her active aspirin order's record and not the stopped one's. */
	@Test
	public void aHistoryQuestionWhoseOrdersAreNotAllCitableStillAsksTheModel() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWithChart(provider, shipped(),
				new RecordMapping(2, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, ASPIRIN_ORDER_UUID,
						day("2008-08-15"), "Drug order: Aspirin", null, 0, Boolean.TRUE)).search(patient,
								"Has she ever taken aspirin?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
	}

	/** An order that ended is stated with the day it ended, by the record's own stamps (ADR Decision 155): patient 6's
	 *  Triomune-30 lapsed in 2008, and lamivudine is one of its substances. */
	@Test
	public void aHistoryQuestionAboutADrugOnlyAnEndedOrderCarriedStatesWhenItEnded() throws Exception {
		executeDataSet("DrugOrderCurrencyTestData.xml");
		Patient six = Context.getPatientService().getPatient(6);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWithChart(provider, shipped(),
				new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
						"9318bbbb-0000-4000-8000-00000000318b", day("2008-01-01"), "Drug order: Triomune-30", null, 0,
						Boolean.FALSE, day("2008-01-09"))).search(six, "Has she ever taken lamivudine?");

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("This patient's chart records 1 Lamivudine order:\n"
				+ "Triomune-30 — ended 2008-01-09, ordered 2008-01-01. [1]", answer.getAnswer());
	}

	/** An order whose record carries no in-force stamp cannot be stated, so the model answers (ADR Decision 155). */
	@Test
	public void aHistoryQuestionWhoseOrderRecordCarriesNoStampStillAsksTheModel() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWithChart(provider, shipped(),
				new RecordMapping(2, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, ASPIRIN_ORDER_UUID,
						day("2025-01-15"), "Drug order: Aspirin", null, 0, null)).search(patient,
								"Has she ever taken aspirin?");
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertTrue(answer.getReferenceSlice() != null && answer.getReferenceSlice().getRecords() > 0,
				"and the injection still ran, so the model reads the reference material: " + answer.getReferenceSlice());
	}

	private static java.util.Date day(String isoDay) {
		try {
			return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(isoDay);
		}
		catch (java.text.ParseException e) {
			throw new IllegalArgumentException(isoDay, e);
		}
	}

	/**
	 * A response to a question whether she has ever taken a drug says so, so a client can draw its chips apart from the
	 * answer (ADR Decision 156): on the model's answer, on the module's, and on the early done a streaming user sees.
	 * A proposal of the same drug says it is not one.
	 */
	@Test
	public void aResponseSaysWhetherTheQuestionAskedIfSheHasEverTakenADrug() {
		ChartAnswer model = serviceWith(new RecordingProvider(), shipped()).search(patient, "Has she ever taken aspirin?");
		assertFalse(model.isAnsweredByTheModule(), "precondition: the model answers this one");
		assertTrue(model.asksWhetherSheHasTakenADrug(), "the model's answer");

		ChartAnswer module = serviceWith(new RecordingProvider(), shipped()).search(patient,
				"Has she ever taken mebendazole?");
		assertTrue(module.isAnsweredByTheModule(), "precondition: the module answers this one");
		assertTrue(module.asksWhetherSheHasTakenADrug(), "the module's answer");

		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		serviceWith(new RecordingProvider(), shipped()).searchStreaming(patient, "Has she ever taken aspirin?", t -> { },
				r -> { }, c -> { }, early::add);
		assertEquals(1, early.size());
		assertTrue(early.get(0).asksWhetherSheHasTakenADrug(), "the early done");

		assertFalse(serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her aspirin?")
				.asksWhetherSheHasTakenADrug(), "a proposal is not one");
	}

	/** The one finding in the prompt for {@code question} whose text opens {@code opening}, failing where there is not
	 *  exactly one. */
	private Finding findingNamed(String question, String opening) {
		Finding found = null;
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		for (Finding finding : findings) {
			if (finding.text.startsWith(opening)) {
				assertNull(found, "precondition: one finding opens " + opening + ", findings were: " + findings);
				found = finding;
			}
		}
		assertNotNull(found, "precondition: a finding opens " + opening + ", findings were: " + findings);
		return found;
	}

	/** A finding about the drug proposed against a listed drug is stated as one, never as "raised no finding" (ADR
	 *  Decisions 149, 150): ibuprofen relates to metformin Moderate, and to her aspirin Major, which is about ibuprofen
	 *  alone and is not stated. */
	@Test
	public void aListQuestionWhoseProposalTheDataRatesAgainstAListedDrugStatesThatFinding() {
		String question = "The patient is currently on Ibuprofen, is it safe to give metformin?";
		Finding ibuprofen = findingNamed(question, "Metformin interacts with Ibuprofen");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertEquals("1 interaction caution for Metformin:\n"
				+ "Metformin interacts with Ibuprofen, also named in the question — Moderate. [" + ibuprofen.index + "]\n"
				+ "The chart holds no active order for Ibuprofen.", answer.getAnswer());
	}

	/**
	 * A listed drug her chart holds is one of her orders, which the first line already checked the drug proposed
	 * against, so the list line names only the listed drugs she does not hold, and is left out where she holds them
	 * all (ADR Decision 149). Asked of Kamwara, who holds all three, it repeated her own orders as "also named in the
	 * question". She holds aspirin here.
	 */
	@Test
	public void theListLineNamesOnlyTheListedDrugsHerChartDoesNotHold() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer all = serviceWith(provider, shipped()).search(patient,
				"The patient is currently on Aspirin, is it safe to give metformin?");
		ChartAnswer some = serviceWith(provider, shipped()).search(patient,
				"The patient is currently on Aspirin, Lamivudine, is it safe to give metformin?");

		assertEquals(0, provider.calls, "no model is asked: " + all.getAnswer() + " / " + some.getAnswer());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + referenceRecordOf(all, "metformin") + "]\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.",
				all.getAnswer());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: its row against this "
				+ "patient's orders carries no severity or mechanism. [" + referenceRecordOf(some, "metformin") + "]\n"
				+ "The check of Metformin against Lamivudine, also named in the question, raised no finding.\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.\n"
				+ "The chart holds no active order for Lamivudine.", some.getAnswer());
	}

	/** Listed drugs related to EACH OTHER above the floor do not keep the model call where nothing withholds and none
	 *  of it is about the drug proposed (ADR Decision 149): nevirapine and fluconazole relate Moderate. */
	@Test
	public void aCautionBetweenTwoListedDrugsDoesNotKeepTheModelCall() {
		String question = "The patient is currently on Nevirapine, Fluconazole, is it safe to give metformin?";
		answerFromFindings(false);
		ChartAnswer off = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
		answerFromFindings(true);
		assertTrue(off.getPairChipExtent() != null && off.getPairChipExtent().getFound() > 0,
				"precondition: the question's own pairs relate two listed drugs, was: " + off.getPairChipExtent());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains(
				"\nThe check of Metformin against Nevirapine and Fluconazole, also named in the question, raised no "
						+ "finding.\n"), answer.getAnswer());
	}

	/** A question-pair list the cap truncated may have withheld a pair of the drug proposed, which then raised no
	 *  finding, so the model answers (ADR Decision 149): three listed drugs relating pairwise Moderate, cap one. */
	@Test
	public void aListQuestionWhosePairsTheCapTruncatedStillAsksTheModel() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS, "1");
		String question = "The patient is currently on Nevirapine, Fluconazole, Amlodipine, is it safe to give metformin?";
		answerFromFindings(false);
		ChartAnswer off = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
		PairChipExtent extent = off.getPairChipExtent();
		assertTrue(extent != null && extent.getFound() > extent.getReported(),
				"precondition: the cap withheld a pair, was: " + extent);

		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** A finding about the drug proposed that counts no pair is stated, and the answer never says the check raised none
	 *  (ADR Decisions 149, 150): vorapaxar shares her aspirin's platelet-inhibitor subgroup, which no data row relates,
	 *  so the drug-in-play arm's statement about it is {@code found == 0} while a caution about it stands. */
	@Test
	public void aListQuestionWithAClassFindingAboutTheDrugProposedStatesIt() {
		String question = "The patient is currently on Lamivudine, is it safe to give vorapaxar?";
		Finding aspirin = findingNamed(question, "Vorapaxar");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith("1 interaction caution for Vorapaxar:\nVorapaxar "), answer.getAnswer());
		assertTrue(answer.getAnswer().contains(" [" + aspirin.index + "]"), answer.getAnswer());
		assertFalse(answer.getAnswer().contains("raised no finding"), answer.getAnswer());
	}

	/** {@link #assertTheCheckRelatedNothing}, then the model is asked for {@code question} with the property on. */
	private void assertTheModelAnswersWhatTheCheckRelatedToNothing(Patient who, String question,
			DrugReferenceService reference) {
		assertTheCheckRelatedNothing(who, question, reference);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(who, question);
		assertEquals(1, provider.calls, "the model is asked: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** With the property off, one search: the model is asked, the prompt carries no finding, and the interaction
	 *  extent says the check ran and related the drug to none of her orders at any rating. */
	private void assertTheCheckRelatedNothing(Patient who, String question, DrugReferenceService reference) {
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		ChartAnswer off = serviceWith(recorder, reference).search(who, question);
		answerFromFindings(true);
		assertEquals(1, recorder.calls, "precondition: with the property off the model is asked");
		assertFalse(FINDING_LINE.matcher(recorder.lastRecords).find(),
				"precondition: no finding is raised: " + recorder.lastRecords);
		PairChipExtent extent = off.getPairChipExtent();
		assertTrue(extent != null && extent.getFound() == 0 && extent.getBelowFloor() != null
				&& extent.getBelowFloor().isEmpty(),
				"precondition: the check ran and related nothing at any rating, was: " + extent);
	}

	/** The index of the answer's reference to the shipped reference record of {@code drug}, failing where the answer
	 *  cites none or cites another drug's. */
	private static int referenceRecordOf(ChartAnswer answer, String drug) {
		List<String> rows = new ArrayList<String>();
		for (DrugReference entry : shipped().findImpliedByQuery(drug)) {
			rows.add(entry.getId());
		}
		for (RecordReference ref : answer.getReferences()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(ref.getResourceType())
					&& rows.contains(ref.getResourceUuid())) {
				return ref.getIndex();
			}
		}
		throw new AssertionError("the answer cites no reference record of " + drug + " " + rows + ": "
				+ answer.getReferences());
	}

	/** The index of the answer's reference to the record of {@code orderUuid}, failing where there is none. */
	private static int recordOf(ChartAnswer answer, String orderUuid) {
		for (RecordReference reference : answer.getReferences()) {
			if (orderUuid.equals(reference.getResourceUuid())) {
				return reference.getIndex();
			}
		}
		throw new AssertionError("no reference to order " + orderUuid + ": " + answer.getAnswer());
	}

	/**
	 * A composed line cites the chart record of the order its finding was matched against, as a model's
	 * answer citing that order does, and the chips beside the composed answer are scoped by those
	 * citations (ADR Decision 140): her warfarin order, which the omeprazole caution is about, conflicts
	 * with her recorded warfarin allergy, and that chip is published beside the module's answer as it is
	 * beside a model's that cites the order. Measured live before this rule: the chip beside the model's
	 * fluconazole answer, absent beside the module's.
	 */
	@Test
	public void aComposedLineCitesItsOrdersRecordAndTheChipsAreScopedByIt() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Warfarin");
		String question = "Can I give her omeprazole?";
		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls, "precondition: the caution-only proposal is answered by the module");
		String warfarinLine = null;
		for (String line : answer.getAnswer().split("\n")) {
			if (line.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Warfarin")) {
				warfarinLine = line;
			}
		}
		assertNotNull(warfarinLine, "precondition: a line states the warfarin caution: " + answer.getAnswer());
		Integer warfarinRecord = null;
		for (RecordReference reference : answer.getReferences()) {
			if (WARFARIN_ORDER_UUID.equals(reference.getResourceUuid())) {
				warfarinRecord = Integer.valueOf(reference.getIndex());
			}
		}
		assertNotNull(warfarinRecord, "her warfarin order's own record is a reference of the answer: "
				+ answer.getAnswer());
		assertTrue(warfarinLine.endsWith(" [" + warfarinRecord + "]")
				&& chartRecordsCitedAsTheAnswersOwn(answer).contains(warfarinRecord),
				"the warfarin line ends citing that record, as the answer's own: " + warfarinLine);
		boolean allergyChip = false;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			allergyChip |= SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType())
					&& chip.getDetail().contains("recorded allergy to Warfarin");
		}
		assertTrue(allergyChip, "her warfarin allergy against her warfarin order is a chip beside the answer, "
				+ "chips were: " + answer.getSafetyWarnings());
	}

	private static int occurrences(String text, String needle) {
		int n = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
			n++;
		}
		return n;
	}

	/**
	 * A set of findings of different strengths is one answer led by the withholding call, the stronger
	 * finding first: ciprofloxacin relates Major to her warfarin (withhold) and Moderate to her aspirin
	 * (a caution), and both are stated.
	 */
	@Test
	public void findingsOfDifferentStrengthsAreLedByTheWithholdingCall() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her ciprofloxacin?";
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
		assertTrue(text.startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ciprofloxacin"
				+ DrugReferenceInjector.WITHHOLD_LEAD_CLOSING), "the withholding call leads: " + text);
		Finding major = findingNamed(question, "Ciprofloxacin interacts with active order Warfarin");
		Finding moderate = findingNamed(question, "Ciprofloxacin interacts with active order Acetylsalicylic acid (aspirin)");
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ciprofloxacin.\n"
				+ "Ciprofloxacin interacts with active order Warfarin — Major. [" + major.index + "] ["
				+ recordOf(answer, WARFARIN_ORDER_UUID) + "]\n"
				+ "Ciprofloxacin interacts with active order Acetylsalicylic acid (aspirin) — Moderate. [" + moderate.index
				+ "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", text, "then the stronger finding first");
	}

	/**
	 * The sentence under the "No" is the finding that licensed it. A recorded allergy to the proposed
	 * drug states the same withholding clause as the Major interaction, and the drug-in-play arm
	 * appends its contraindications first — but a contraindication never decides the module's "No"
	 * (ADR Decision 108), so it must not be what reads as the reason for it. It is still stated, after.
	 */
	@Test
	public void theLineUnderTheNoIsTheInteractionThatLicensedIt() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Ibuprofen");
		List<Finding> findings = findingsInThePromptFor(PROPOSAL);
		Finding interaction = null;
		Finding allergy = null;
		for (Finding finding : findings) {
			if (!"Ibuprofen".equals(finding.drug)) {
				continue;
			}
			if (finding.text.startsWith("Ibuprofen" + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE)) {
				interaction = interaction == null ? finding : interaction;
			} else if (finding.text.startsWith("The patient has a recorded allergy to Ibuprofen.")) {
				allergy = finding;
			}
		}
		assertTrue(interaction != null && allergy != null && allergy.index < interaction.index
				&& allergy.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: the identity allergy finding withholds too, and precedes the interaction in "
						+ "the prompt: " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "the Major interaction still licenses the module's answer");
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		assertEquals("Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. Ibuprofen is in the same "
				+ "cross-reactivity group (NSAID) as active order Acetylsalicylic acid (aspirin) — possible additive or "
				+ "duplicate-class therapy. [" + interaction.index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", lines[1],
				"the sentence under the \"No\" is the interaction that licensed it: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains("\nThe patient has a recorded allergy to Ibuprofen. No severity is rated "
				+ "for this finding. [" + allergy.index + "]"), "and the allergy is still stated: " + answer.getAnswer());
	}

	/**
	 * The same for an interaction rule the data does not rate: it withholds, and
	 * {@code DrugSafetyValidator.FINDING_STRENGTH_DESCENDING} orders it ahead of Major, but only the
	 * rated row licenses the module's "No".
	 */
	@Test
	public void aMajorInteractionLeadsAnUnratedRuleUnderTheNo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceService reference = DrugReferenceTestSupport
				.curatedFixtureService("chartsearchai-test/drug-reference-answer-from-findings-unrated-beside-major.json");
		List<Finding> findings = findingsInThePromptFor(PROPOSAL, reference);
		Finding major = null;
		Finding unrated = null;
		for (Finding finding : findings) {
			if (finding.text.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Warfarin")) {
				major = finding;
			} else if (finding.text.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Aspirin")) {
				unrated = finding;
			}
		}
		assertTrue(major != null && unrated != null && unrated.index < major.index
				&& unrated.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: the unrated rule withholds too, and precedes the Major one in the prompt: "
						+ findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "the Major row licenses the module's answer");
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		assertLineIs(answer, major, lines[1],
				"the sentence under the \"No\" is the rated interaction that licensed it: " + answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
	}

	/**
	 * The same for a rule an operator's dataset rates in a word this module does not recognise: {@code
	 * DrugSafetyValidator.severityRank} reads it as unrated, so it withholds and is ordered ahead of Major,
	 * but unlike an unrated rule it carries a rating. What puts the Major row under the "No" is that its
	 * rating is a reason to withhold, not that it has one.
	 */
	@Test
	public void aMajorInteractionLeadsARuleRatedInAWordTheModuleDoesNotRecogniseUnderTheNo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceService reference = DrugReferenceTestSupport.curatedFixtureService(
				"chartsearchai-test/drug-reference-answer-from-findings-unrecognised-rating-beside-major.json");
		List<Finding> findings = findingsInThePromptFor(PROPOSAL, reference);
		Finding major = null;
		Finding unrecognised = null;
		for (Finding finding : findings) {
			if (finding.text.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Warfarin")) {
				major = finding;
			} else if (finding.text.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "Aspirin")) {
				unrecognised = finding;
			}
		}
		assertTrue(major != null && unrecognised != null && unrecognised.index < major.index
				&& unrecognised.text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: the rule rated in an unrecognised word withholds too, and precedes the Major one "
						+ "in the prompt: " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "the Major row licenses the module's answer");
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		assertLineIs(answer, major, lines[1],
				"the sentence under the \"No\" is the interaction whose rating licensed it: " + answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
	}

	/**
	 * A contraindication about a medication she already takes says so in the composed answer. Its
	 * record said so only in the strength clause, which stays out of the answer; what that clause
	 * carries besides the call — its REFERENT, set by the arm (ADR Decision 72) — is a fact about her
	 * chart, and the line keeps it, without naming a drug the clause never named either.
	 */
	@Test
	public void aContraindicationAboutAMedicationSheAlreadyTakesSaysSo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		List<Finding> findings = findingsInThePromptFor(SCREEN);
		Finding allergy = null;
		for (Finding finding : findings) {
			if (finding.text.startsWith("The patient has a recorded allergy to ")) {
				allergy = finding;
			}
		}
		assertTrue(allergy != null && allergy.text.endsWith(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION),
				"precondition: her aspirin allergy against her aspirin order is a current-medication finding: "
						+ findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls);
		assertTrue(answer.getAnswer().contains(answerFacingBody(allergy) + CURRENT_MEDICATION_REFERENT
				+ " [" + allergy.index + "]"),
				"the allergy line says the drug is one she already takes: " + answer.getAnswer());
		assertCarriesEveryFinding(answer, findings);
	}

	/**
	 * The licensing key is scoped to the withholding CLASS a proposal states, so a screen's lines keep
	 * the order the screening arm raised them in: here an unrated rule and a Major one between her own
	 * orders both state the current-medication withholding clause, and only the Major one is a rating
	 * that licenses a proposal's "No".
	 */
	@Test
	public void aScreensLinesKeepTheOrderTheArmRaisedThemIn() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		executeDataSet(METFORMIN_ORDER);
		DrugReferenceService reference = DrugReferenceTestSupport.curatedFixtureService(
				"chartsearchai-test/drug-reference-answer-from-findings-screen-unrated-beside-major.json");
		List<Finding> findings = findingsInThePromptFor(SCREEN, reference);
		assertEquals(2, findings.size(), "precondition: the screen relates two pairs: " + findings);
		assertTrue(findings.get(0).text.startsWith("Metformin" + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE)
				&& findings.get(1).text.startsWith("Warfarin" + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE),
				"precondition: the arm raises the unrated pair ahead of the Major one: " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, SCREEN);

		assertEquals(0, provider.calls);
		String[] lines = answer.getAnswer().split("\n");
		assertLineIs(answer, findings.get(0), lines[0], answer.getAnswer());
		assertLineIs(answer, findings.get(1), lines[1], answer.getAnswer());
	}

	@Test
	public void withThePropertyOffTheModelIsAskedAsBefore() {
		answerFromFindings(false);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(1, provider.calls, "an install that turns the property off keeps the model's answer");
		assertTrue(answer.getAnswer().startsWith(RecordingProvider.ANSWER),
				"the model's own answer, which ADR Decision 100 may complete but never replaces: "
						+ answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
		assertNotNull(answer.getFindingCitationExtent(), "the checks judge the model's prose as before");
	}

	/**
	 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/562">#562</a>: the
	 * property ships ON, since the gate ADR Decision 108 names has been run (Decision 131). An install that
	 * never wrote the property reads the constant's fallback, so this asks the real {@code search} with no
	 * property row at all. {@code GlobalPropertyDefaultsTest} holds that fallback to what {@code config.xml}
	 * writes into a new install.
	 */
	@Test
	public void anInstallThatNeverSetThePropertyAnswersAWithheldProposalFromTheFindings() {
		AdministrationService administration = Context.getAdministrationService();
		GlobalProperty row = administration
				.getGlobalPropertyObject(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS);
		if (row != null) {
			administration.purgeGlobalProperty(row);
		}
		assertNull(administration.getGlobalPropertyObject(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS),
				"precondition: no property row, so the shipped default decides");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, PROPOSAL);

		assertEquals(0, provider.calls,
				"with the property never set, a withheld proposal is answered from the module's findings");
		assertTrue(answer.isAnsweredByTheModule(), "and says no model wrote the answer");
		assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING),
				"with the withholding call first, was: " + answer.getAnswer());
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
			// the same two, and a second drug, in the drug-first shapes (ADR Decision 134)
			"Is ibuprofen safe to give for her knee pain?",
			"Can ibuprofen be given for her knee pain?",
			"Is ibuprofen safe to add to omeprazole?",
			"Can ibuprofen be given with omeprazole?",
			// inverse polarity in the passive: a "No" lead would answer it backwards
			"Should ibuprofen be stopped?",
			"Can ibuprofen be avoided?",
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
	 * A second drug the question names keeps the call. Clarithromycin's reference entry is also filed
	 * under a combination name carrying amoxicillin, which is how an earlier form of the gate — removing
	 * every word of every one of the drug's names — once admitted such a question as one about the
	 * first drug alone; no proposal shape carries a second drug, which is what refuses it now. The
	 * drug alone IS answered by the module — its Major interaction with her warfarin withholds it — so
	 * it is the second drug, and not a finding too weak to answer from, that keeps the call. (This case
	 * asked about omeprazole until issue #471 made its Moderate interaction a caution, which would have
	 * kept the call whatever the gate did.)
	 */
	@Test
	public void aSecondDrugInsideTheFirstsCombinationNameStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertTheModuleAnswers("Can I give her clarithromycin?", DrugReferenceTestSupport.ddinterServiceWithGroups());
		assertTheModelIsAsked("Can I give her clarithromycin with amoxicillin?");
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
	 * An interaction the data does not RATE is not what licenses the module's "No": paracetamol's rule
	 * against warfarin carries no severity (the curated seed's own rule), which withholds only because
	 * an unrated rule is not a caution (ADR Decision 37) — a rule's author's note, the same objection
	 * that keeps contraindications from deciding the answer.
	 *
	 * <p>Over a dataset that resolves EVERY one of her orders, so the gate is refused by its rating
	 * conjunct and not by one it asks first: the curated seed carries neither aspirin nor warfarin, and
	 * over it this case once passed because her orders did not resolve. The positive control is what
	 * holds that: on the same dataset and the same two orders, ibuprofen's rule against warfarin — rated
	 * Major — is answered by the module, so nothing but the rating separates the two questions.
	 */
	@Test
	public void anUnratedInteractionRuleStillAsksTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceService curated = DrugReferenceTestSupport
				.curatedFixtureService("chartsearchai-test/drug-reference-answer-from-findings-unrated-rule.json");
		assertTheModuleAnswers("Can I give her ibuprofen?", curated);

		String question = "Can I give her paracetamol?";
		List<Finding> findings = findingsInThePromptFor(question, curated);
		assertEquals(1, findings.size(), "precondition: one finding, the unrated rule, was: " + findings);
		assertTrue(findings.get(0).text.startsWith("Paracetamol interacts with active order")
				&& findings.get(0).text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: an unrated interaction withholds paracetamol, finding was: " + findings.get(0).text);

		assertTheModelIsAsked(question, curated);
	}

	/**
	 * Nor is a class relationship FOLDED onto a row the data rates below Moderate: methylphenidate's
	 * DDInter row against her modafinil is rated Minor, and both publish {@code N06BA}, so the drug-in-play
	 * arm appends the class sentence to the rated rule and the finding withholds
	 * ({@code SafetyWarning.carriesUnratedRelationship}) while no rating the data gives says so.
	 *
	 * <p>Over a slice carrying her aspirin too, so every order resolves and the rating conjunct is what
	 * refuses; the positive control is warfarin, which that slice rates Major against her aspirin and
	 * which the module answers over the same two orders.
	 */
	@Test
	public void aClassRelationshipFoldedOntoAMinorRowStillAsksTheModel() throws Exception {
		executeDataSet("AnswerFromFindingsModafinilOrderTestData.xml");
		DrugReferenceService ddinter = DrugReferenceTestSupport
				.ddiFixtureService("chartsearchai-test/ddi-folded-minor-class-pair-every-order-resolved.json");
		assertTheModuleAnswers("Can I give her warfarin?", ddinter);

		String question = "Can I give her methylphenidate?";
		List<Finding> findings = findingsInThePromptFor(question, ddinter);
		assertEquals(1, findings.size(), "precondition: one finding, the folded Minor row, was: " + findings);
		assertTrue(findings.get(0).text.contains("Minor") && findings.get(0).text.contains("N06BA")
				&& findings.get(0).text.endsWith(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"precondition: a Minor row carrying the N06BA class sentence withholds methylphenidate, finding "
						+ "was: " + findings.get(0).text);

		assertTheModelIsAsked(question, ddinter);
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
		assertTheModelIsAsked(question, DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	private void assertTheModelIsAsked(String question, DrugReferenceService reference) {
		assertFalse(findingsInThePromptFor(question, reference).isEmpty(),
				"precondition: the module raised something for " + question);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, question);
		assertEquals(1, provider.calls, "the model must be asked: " + question);
		assertFalse(answer.isAnsweredByTheModule(), question);
	}

	/** The positive control: over {@code reference} and this patient's orders, {@code question} is answered
	 *  by the module with the withholding call and no model call. */
	private void assertTheModuleAnswers(String question, DrugReferenceService reference) {
		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, question);
		assertEquals(0, provider.calls, "positive control, the module answers: " + question);
		assertTrue(answer.isAnsweredByTheModule(), question);
		assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
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

		/** The record's number and text, so a precondition failing over a list reads as one. */
		@Override
		public String toString() {
			return "[" + index + "] " + text;
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
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return record(numberedRecords);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
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

		private final RecordMapping[] records;

		private StubStrategy(RecordMapping... records) {
			this.records = records.length == 0
					? new RecordMapping[] { DrugReferenceTestSupport.obsRecord(1, "BP 120/80") }
					: records;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(records);
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
