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
import java.util.Arrays;
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
import org.openmrs.module.chartsearchai.reference.DrugReferenceLoad;
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

	private static final String RIFAMPICIN_ORDER = "ListedMedicationsRifampicinOrderTestData.xml";

	private static final String SECOND_RIFAMPICIN_ORDER = "ListedMedicationsSecondRifampicinOrderTestData.xml";

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

	/** A screen asking whether her medications are SAFE, which states her contraindications beside her interactions; a
	 *  question asking only about interactions draws them apart (ADR Decision 190). */
	private static final String SAFETY_SCREEN = "Are her current medications safe to take together?";

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

	/** The first sentence of {@code finding}'s answer-facing body — what a brief line opens with (ADR Decisions 140, 171). */
	private static String firstSentence(Finding finding) {
		String body = answerFacingBody(finding);
		int end = body.indexOf(". ");
		return end < 0 ? body : body.substring(0, end + 1);
	}

	/**
	 * A screen's answer states each finding as a brief line (ADR Decision 171): the line opening with the record's first
	 * sentence, cited by its own number, and the finding a reference of the answer.
	 */
	private static void assertStatesEveryFindingBriefly(ChartAnswer answer, List<Finding> findings) {
		List<String> lines = Arrays.asList(answer.getAnswer().split("\n"));
		for (Finding finding : findings) {
			boolean found = false;
			for (String line : lines) {
				found |= line.startsWith(firstSentence(finding)) && line.contains(" [" + finding.index + "]");
			}
			assertTrue(found, "a line opens with finding [" + finding.index + "]'s first sentence and cites it. Finding: "
					+ finding.text + "\nAnswer: " + answer.getAnswer());
			assertTrue(ChartAnswerTestSupport.referenceIndexes(answer).contains(Integer.valueOf(finding.index)),
					"and the finding is a reference of the answer");
		}
		for (String clause : STRENGTH_CLAUSES) {
			assertFalse(answer.getAnswer().contains(clause.trim()),
					"a strength clause is prompt-facing only and must not reach the answer: " + answer.getAnswer());
		}
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
	 * A chip whose finding the composed answer states whole is published as stated, so a client does not
	 * draw it a second time in full beneath the answer that just said it — and only such a chip: a screen's
	 * brief line leaves an interaction's mechanism on its chip (ADR Decision 171). Live on the 3.7.1 standalone,
	 * "Does she have any drug interactions I should know about?" came back composed from five findings,
	 * each line its chip's own detail, and all five chips beside it still published
	 * {@code statedInTheAnswer: false}.
	 */
	@Test
	public void everyChipTheComposedAnswerStatesIsPublishedAsStated() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SAFETY_SCREEN);

		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module composed this answer");
		assertStatedExactlyWhereTheAnswerCarriesTheDetail(answer);
	}

	/** Each chip is published stated exactly where the composed answer carries its detail whole — and the answer
	 *  carries one such chip and one it does not, so neither direction passes vacuously. */
	private static void assertStatedExactlyWhereTheAnswerCarriesTheDetail(ChartAnswer answer) {
		int whole = 0;
		int brief = 0;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			boolean carried = answer.getAnswer().contains(chip.getDetail());
			if (carried) {
				whole++;
			} else {
				brief++;
			}
			assertEquals(carried, chip.isStatedInTheAnswer(), "stated exactly where the answer carries the detail: "
					+ chip.getDetail());
		}
		assertTrue(whole > 0 && brief > 0, "precondition: one chip stated whole and one briefly, chips were: "
				+ answer.getSafetyWarnings());
	}

	@Test
	public void searchStreaming_publishesTheChipsTheComposedAnswerStatesAsStatedToo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).searchStreaming(patient, SAFETY_SCREEN, text -> { },
				reasoning -> { }, citations -> { }, early -> { });

		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module composed this answer");
		assertStatedExactlyWhereTheAnswerCarriesTheDetail(answer);
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

	/** More ways of proposing one drug, measured on the 3.7.1 standalone reaching the model (ADR Decision 180): each is
	 *  answered with the withholding call, as the shapes above are. */
	@Test
	public void moreProposalPhrasingsAreAnsweredFromTheFindings() {
		for (String question : new String[] { "Can she have ibuprofen?", "Can she use ibuprofen?",
				"Can I prescribe ibuprofen for her?", "Would it be safe to start ibuprofen?", "OK to give ibuprofen?",
				"Is it ok to give her ibuprofen?", "Can I add ibuprofen to her medications?",
				"Is it safe to add ibuprofen to her regimen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		}
	}

	/**
	 * A question asking whether NOT to give a drug inverts "No" (ADR Decision 181). These were left to the model so that a
	 * "No" lead could not answer them backwards, and on the 3.7.1 standalone the model answered them backwards anyway —
	 * <em>"Is rifampicin contraindicated?"</em> and <em>"Is there any reason not to give her rifampicin?"</em> opened
	 * <em>"No — Rifampicin should not be given …"</em> on five of six patients. The module answers them, opening with the
	 * finding and neither "Yes" nor "No".
	 */
	@Test
	public void aQuestionWhetherNotToGiveADrugIsAnsweredWithoutAYesOrNo() {
		for (String question : new String[] { "Is there any reason not to give her ibuprofen?", "Is ibuprofen contraindicated?",
				"Is ibuprofen contraindicated for her?", "Any problem giving her ibuprofen?", "Is it risky to give her ibuprofen?",
				"Is it wrong to give her ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * The same question asked of what WOULD follow (ADR Decision 243). On the 3.7.1 standalone Mogan Kamwara's "Would
	 * rifampicin be contraindicated for her?" reached the model, which opened "No — Rifampicin should not be given": a "No"
	 * to whether it would be contraindicated, over a Major interaction with her nevirapine.
	 */
	@Test
	public void aQuestionWhetherADrugWouldBeContraindicatedIsAnsweredWithoutAYesOrNo() {
		for (String question : new String[] { "Would ibuprofen be contraindicated?", "Would ibuprofen be contraindicated for her?",
				"Will ibuprofen be contraindicated for this patient?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * Whether to AVOID a drug inverts "No" as whether NOT to give it does (ADR Decision 247). On the 3.7.1 standalone Carol
	 * Brown's "Should I avoid clarithromycin for her?" and Donald Thompson's "Do I need to avoid ibuprofen in him?" reached
	 * the model, which opened "No, Clarithromycin should be avoided" and "No — Ibuprofen should be avoided due to a Major
	 * interaction with Warfarin": a "No" to whether to avoid it.
	 */
	@Test
	public void aQuestionWhetherToAvoidADrugIsAnsweredWithoutAYesOrNo() {
		for (String question : new String[] { "Should I avoid ibuprofen?", "Should I avoid ibuprofen for her?",
				"Should ibuprofen be avoided?", "Should ibuprofen be avoided in this patient?", "Do I need to avoid ibuprofen?",
				"Do we need to avoid ibuprofen in her?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * More questions about giving a drug whose "No" would answer them backwards (ADR Decision 251). On the 3.7.1 standalone
	 * Donald Thompson's "Does he need INR monitoring if I add metronidazole?" and Brian Lopez's "Should he avoid codeine
	 * with his tramadol?" reached the model, which opened "No — Metronidazole should not be added" and "No — Codeine should
	 * not be used with Tramadol": a "No" to whether monitoring is needed, and to whether to avoid it.
	 */
	@Test
	public void moreQuestionsWhoseNoWouldReadBackwardsAreAnsweredWithoutAYesOrNo() {
		for (String question : new String[] { "Should she avoid ibuprofen?", "Does she need monitoring if I give her ibuprofen?",
				"Does she need INR monitoring if I add ibuprofen?", "Is she at risk of bleeding if I add ibuprofen?",
				"What happens if I give her ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * Whether a drug she is not on is safe with her medications is a proposal, and whether there are problems if she is
	 * started on it inverts "No" (ADR Decision 299). On the 3.7.1 standalone both went to the model for every patient
	 * asked: Dorothy Odinga's fluconazole was "No — should not be started" over an unrated interaction, and Richard Jones's
	 * ibuprofen was answered with his own dexamethasone allergy.
	 */
	@Test
	public void whetherADrugIsSafeWithHerMedicationsOrCausesProblemsIfStartedIsAnsweredFromTheFindings() {
		for (String question : new String[] { "Is ibuprofen safe with her current medications?",
				"Is ibuprofen okay with her meds?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		}
		for (String question : new String[] { "Any problems if I start her on ibuprofen?",
				"Are there any issues if I give her ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * Whether a drug is DANGEROUS, harmful or a cause of problems inverts "No" as whether it is contraindicated does (ADR
	 * Decision 255): "No" would say it is not.
	 */
	@Test
	public void whetherADrugIsDangerousIsAnsweredWithoutAYesOrNo() {
		for (String question : new String[] { "Is ibuprofen dangerous for her?", "Is ibuprofen risky?",
				"Could ibuprofen cause problems for her?", "Will giving her ibuprofen cause problems?",
				"Is there any harm in giving her ibuprofen?", "Is there a contraindication to giving her ibuprofen?",
				"Any reason to avoid ibuprofen in her?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * A risk in giving it asked with no preposition, or with "of", is the same question (ADR Decision 348): on the 3.7.1
	 * standalone "Is there a risk giving her erythromycin?" fitted no shape, and the model told Karen Sanchez, among others,
	 * "Erythromycin can be given, with one caution" — a clearance.
	 */
	@Test
	public void aRiskInGivingItAskedWithoutAPrepositionIsTheSameQuestion() {
		for (String question : new String[] { "Is there a risk giving her ibuprofen?", "Is there any risk of giving her "
				+ "ibuprofen?", "Is there any harm giving her ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen.\n"),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * "As well", "too" and "also" propose the drug beside her medications, which is what every proposal is screened against
	 * (ADR Decision 244). On the 3.7.1 standalone Carol Brown, on lisinopril among six orders, asked "Can I start her on
	 * enalapril as well?", reached the model, and read "Enalapril can be started, with several cautions" — a second ACE
	 * inhibitor, whose same-class finding the module states as "possible duplicate therapy" when it answers.
	 */
	@Test
	public void aProposalOfTheDrugAsWellAsHerMedicationsIsAProposal() {
		for (String question : new String[] { "Can I give her ibuprofen as well?", "Can I start her on ibuprofen too?",
				"Can I also give her ibuprofen?", "Can she take ibuprofen as well?", "Is it safe to give her ibuprofen too?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), question + " — "
					+ answer.getAnswer());
		}
	}

	/**
	 * "Does X interact with her medications?" asks whether there is an interaction, and the model answered it <em>"No —
	 * X should not be given: it interacts with …"</em> (ADR Decision 181). Where the check relates it, the module answers
	 * "Yes", with the findings.
	 */
	@Test
	public void aQuestionWhetherADrugInteractsWithHerMedicationsIsAnsweredYesWithTheFindings() {
		List<Finding> findings = findingsInThePromptFor(PROPOSAL);
		for (String question : new String[] { "Does ibuprofen interact with her medications?",
				"Does ibuprofen interact with any of her current medications?", "Will ibuprofen interact with her meds?",
				// "Any interactions if I give her X?" — measured answered "No — X should not be given: it interacts with …"
				"Any interactions if I give her ibuprofen?", "Are there any interactions if I start her on ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("Yes — Ibuprofen interacts with this patient's active medications."),
					question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().contains("[" + findings.get(0).index + "]"), answer.getAnswer());
		}
	}

	/**
	 * "Is there an interaction if I give her X?" asks whether there is one (ADR Decision 256). On the 3.7.1 standalone Carol
	 * Brown's "Is there an interaction if I give her clarithromycin?" reached the model, which opened "No — Clarithromycin
	 * should not be given: it interacts with active order Simvastatin": a "No" to whether there is an interaction.
	 */
	@Test
	public void whetherThereIsAnInteractionIfSheIsGivenADrugIsAnsweredYesWithTheFindings() {
		for (String question : new String[] { "Is there an interaction if I give her ibuprofen?",
				"Is there any interaction if I start her on ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("Yes — Ibuprofen interacts with this patient's active medications."),
					question + " — " + answer.getAnswer());
		}
	}

	/**
	 * A question naming as hers a drug she is not on says she is not on it, then answers it (ADR Decision 260). On the 3.7.1
	 * standalone Mary Smith, on simvastatin alone, asked "Any interactions with her warfarin?" and the model answered
	 * "Simvastatin can be given, with one caution: Simvastatin may slightly increase the anticoagulant response to
	 * warfarin": her own drug read as proposed, and the premise left standing.
	 */
	@Test
	public void aQuestionNamingAsHersADrugSheIsNotOnSaysSo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		// "Does her ibuprofen interact with her other medications?" since ADR Decision 292, its "her" read as the others'.
		for (String question : new String[] { "Any interactions with her ibuprofen?", "Are there any problems with her ibuprofen?",
			"Does her ibuprofen interact with her other medications?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("Ibuprofen is not one of this patient's active medications.\n"
					+ "Yes — Ibuprofen interacts with this patient's active medications."), question + " — " + answer.getAnswer());
		}
	}

	/** A list question asking whether NOT to give its drug is never answered "No if the patient is on …", which would
	 *  answer it backwards. */
	@Test
	public void aListQuestionAskingWhetherNotToGiveTheDrugIsNotAnsweredNoIfSheIsOnIt() {
		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient,
				"The patient is currently on Efavirenz, is there any reason not to give fluconazole?");
		assertFalse(answer.getAnswer().startsWith("No"), answer.getAnswer());
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
	 * A proposed drug withheld only by a contraindication — here her recorded allergy to the very drug — is answered by
	 * the module with what the check found and no verdict (ADR Decision 311, superseding the model call this case
	 * pinned): a "No" would rest on a curated rule's token matched against her records' free text, which review found
	 * false three ways (ADR Decision 140), and stating the finding asserts no "No". On the 3.7.1 standalone the model
	 * answered such a question "No — … the patient's allergy to Ketoconazole, which is a Major problem", a severity no
	 * finding carries.
	 */
	@Test
	public void aProposalNoInteractionWithholdsIsAnsweredWithWhatTheCheckFound() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Omeprazole");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, "Can I give her omeprazole?");
		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\\n");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Omeprazole:", lines[0], answer.getAnswer());
		assertTrue(answer.getAnswer().contains("recorded allergy to Omeprazole"), answer.getAnswer());
		assertFalse(answer.getAnswer().startsWith("No") || answer.getAnswer().contains("can be given"), answer.getAnswer());
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
		assertEquals("The interaction data gives no rated reason to withhold Clarithromycin: it lists Clarithromycin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + reference + "]\n"
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
		assertEquals("The interaction data gives no rated reason to withhold Omeprazole: it lists Omeprazole against one of this "
				+ "patient's orders, rated Minor, below the level this module reports as a finding. [" + reference
				+ "]\n" + "Interactions the data does not rate, and anything beyond drug interactions, are not covered.", answer.getAnswer());
	}

	/**
	 * A question whether the drug interacts with her medications, over rows below the floor, is answered with which of her
	 * orders the data lists it against and how rated, and neither "Yes", "No" nor a word on withholding (ADR Decision 320,
	 * superseding the model call Decision 181 kept: its sentence was about withholding, and the model answered David
	 * Williams's "Which of her medications interact with rifampicin?" with a drug he does not take).
	 */
	@Test
	public void aBelowFloorQuestionWhetherItInteractsStatesTheRowsAndNoVerdict() {
		String question = "Does clarithromycin interact with her medications?";
		RecordingProvider precondition = new RecordingProvider();
		answerFromFindings(false);
		ChartAnswer off = serviceWith(precondition).search(patient, question);
		answerFromFindings(true);
		assertTrue(off.getPairChipExtent() != null && !off.getPairChipExtent().getBelowFloor().isEmpty(),
				"precondition: the question relates clarithromycin to her aspirin below the floor");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);
		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith("The interaction data lists Clarithromycin against "), answer.getAnswer());
		assertFalse(answer.getAnswer().startsWith("Yes") || answer.getAnswer().startsWith("No")
				|| answer.getAnswer().contains("withhold"), "no verdict: " + answer.getAnswer());
	}

	/**
	 * A pair whose order has not started is stated with that order said to be scheduled (ADR Decision 307, superseding the
	 * model call Decision 142 kept): the answer's sentence counts her orders and never calls one active, so nothing it says
	 * is false of a scheduled order once it says which it is. On the 3.7.1 standalone the model called the unrated pair
	 * beside Dora DdiScheduled's scheduled rifampicin "one caution". Patient 6 holds only an aspirin order scheduled for
	 * 2099.
	 */
	@Test
	public void aBelowFloorPairOnAnOrderThatHasNotStartedIsStatedAsScheduled() {
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

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.getAnswer().split("\n")[0].matches("The interaction data gives no rated reason to withhold "
				+ "Clarithromycin: it lists Clarithromycin against one of this patient's orders, scheduled and not yet started, "
				+ ".*\\[\\d+\\]"), answer.getAnswer());
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
		assertTrue(answer.getAnswer().contains("one of this patient's orders, with no severity on file. ["),
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

	/**
	 * A proposal for a patient with NO active medication orders is answered by the module with what the check
	 * established (ADR Decision 158): there was nothing for the interaction check to relate the drug to. On the demo
	 * (2026-10-06) "Is warfarin safe for her?", asked of a patient with no orders, was answered by the model "No —
	 * Warfarin has major interactions with ketoprofen, ketorolac, lepirudin, levofloxacin, and lomefloxacin [87]" —
	 * the drug's dataset-wide partners, none of them hers, read as hers. Decision 143's sentence declined there, its
	 * orders resolving no substance. Patient 6 holds no active order.
	 */
	@Test
	public void aProposalForAPatientWithNoActiveOrdersIsAnsweredWithWhatTheCheckEstablished() {
		Patient withNoOrders = Context.getPatientService().getPatient(6);
		String question = "Is warfarin safe for her?";

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(withNoOrders, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("This patient has no active medication orders, so the interaction check had none to relate "
				+ "Warfarin to. [" + referenceRecordOf(answer, "warfarin") + "]", answer.getAnswer());
		assertEquals(1, answer.getReferences().size(), "it cites the drug's record alone: " + answer.getReferences());
	}

	/** The same answer on the streaming path, the one the UI takes: the module's answer is served on both. */
	@Test
	public void aProposalForAPatientWithNoActiveOrdersIsAnsweredByTheModuleOnTheStreamingPathToo() {
		Patient withNoOrders = Context.getPatientService().getPatient(6);
		RecordingProvider provider = new RecordingProvider();
		StringBuilder streamed = new StringBuilder();

		ChartAnswer answer = serviceWith(provider, shipped()).searchStreaming(withNoOrders, "Is warfarin safe for her?",
				streamed::append);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertTrue(answer.getAnswer().startsWith("This patient has no active medication orders, so the interaction "
				+ "check had none to relate Warfarin to. ["), answer.getAnswer());
	}

	/** A question naming the drug without PROPOSING it is not answered so (fail-closed): the model answers. */
	@Test
	public void aQuestionNamingADrugWithoutProposingItForAPatientWithNoOrdersStillAsksTheModel() {
		Patient withNoOrders = Context.getPatientService().getPatient(6);
		RecordingProvider provider = new RecordingProvider();

		ChartAnswer answer = serviceWith(provider, shipped()).search(withNoOrders, "When was warfarin last mentioned?");

		assertEquals(1, provider.calls, "the model answers: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/** With the contraindication arms off, an allergy to the drug raises nothing, so "no finding" would not include
	 *  her allergy records: the model answers (fail-closed, as Decision 143's sentence is). */
	@Test
	public void aProposalForAPatientWithNoOrdersAsksTheModelWhereContraindicationsAreNotChecked() {
		Patient withNoOrders = Context.getPatientService().getPatient(6);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		RecordingProvider provider = new RecordingProvider();

		ChartAnswer answer = serviceWith(provider, shipped()).search(withNoOrders, "Is warfarin safe for her?");

		assertEquals(1, provider.calls, "the model answers: " + answer.getAnswer());
		assertFalse(answer.isAnsweredByTheModule());
	}

	/**
	 * A finding that two of her own orders carry one substance (issue #477) says nothing about the drug proposed, so it
	 * does not stop the module answering the proposal with what the interaction check established (ADR Decision 159):
	 * the no-pair sentence, then the finding as a line of its own, as a "No" states it after the drug's own findings
	 * (ADR Decision 116). On the demo (2026-10-07) a patient whose four drugs were each ordered twice was answered
	 * "The records do not address the safety of giving ibuprofen." beside four duplicate-therapy chips: every such
	 * proposal reached the model. Here her rifampicin is ordered twice.
	 */
	@Test
	public void aProposalTheCheckRelatesToNoneOfHerOrdersIsAnsweredBesideHerOwnOrdersSharingASubstance()
			throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		String question = "Can I give her mebendazole?";
		Finding duplicate = onlyFindingIsHerOwnOrdersSharingASubstance(question);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		String[] lines = answer.getAnswer().split("\n");
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertEquals(1, lines.length, answer.getAnswer());
		assertEquals("The interaction check relates Mebendazole to none of this patient's 3 active medications. ["
				+ referenceRecordOf(answer, "mebendazole") + "]", lines[0]);
	}

	/**
	 * A proposal that says what the drug is FOR is still a proposal (ADR Decision 213). On the public demo's data, "Can I
	 * prescribe ibuprofen for pain?" went to the model for every patient and was answered "The records do not address
	 * whether it is safe to prescribe ibuprofen for pain", where "Can I prescribe ibuprofen?" was the module's.
	 */
	@Test
	public void aProposalNamingWhatTheDrugIsForIsAnsweredAsTheProposal() throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		onlyFindingIsHerOwnOrdersSharingASubstance("Can I give her mebendazole?");
		String[][] questions = { { "Can I give her mebendazole for worms?", "worms" },
			{ "Can I prescribe mebendazole for her worms?", "her worms" },
			{ "Is it safe to give her mebendazole for a worm infection?", "a worm infection" } };
		for (String[] question : questions) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question[0]);

			assertEquals(0, provider.calls, "no model is asked for " + question[0] + ": " + answer.getAnswer());
			String[] lines = answer.getAnswer().split("\n");
			assertEquals("The interaction check relates Mebendazole to none of this patient's 3 active medications. ["
					+ referenceRecordOf(answer, "mebendazole") + "]", lines[0], question[0]);
			// The check weighed nothing of what the drug is for, and the answer must not read as a word on that use.
			assertEquals("This check did not weigh what the drug is for (" + question[1] + ").", lines[lines.length - 1],
				question[0]);
		}
	}

	/** Two drugs proposed together, neither hers: each given its own answer, which is what kept them with the model (ADR
	 *  Decision 222). */
	@Test
	public void twoDrugsProposedTogetherAreEachAnsweredAsTheirOwnProposal() {
		answerFromFindings(true);
		for (String question : Arrays.asList("Can I give her ibuprofen and omeprazole?",
			"Can ibuprofen be given with omeprazole?")) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, "no model is asked for " + question + ": " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("Ibuprofen and Omeprazole, asked about together; neither is one of this "
					+ "patient's active medications."), question + " was: " + answer.getAnswer());
			assertTrue(answer.getAnswer().contains("\nFor Ibuprofen:\n" + DrugReferenceInjector.WITHHOLD_LEAD_OPENING),
				"ibuprofen beside her aspirin is still withheld, under its own heading: " + answer.getAnswer());
		}
	}

	/**
	 * Each reference-group citation publishes what its record SAYS, so a clinician can read what an answer like "The
	 * interaction check relates Mebendazole to none of this patient's medications" was checked against; a chart citation
	 * publishes none, its record being the patient's own and opened in the chart (ADR Decision 224). On the public demo a
	 * "Drug reference" citation was a label with nothing behind it: not a link, and no text on the wire.
	 */
	@Test
	public void aReferenceGroupCitationPublishesItsRecordsTextAndAChartCitationNone() throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her mebendazole?");

		int mebendazole = referenceRecordOf(answer, "mebendazole");
		boolean sawReference = false;
		for (RecordReference ref : answer.getReferences()) {
			if (ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(ChartSearchAiUtils.referenceGroup(ref.getResourceType()))) {
				sawReference = true;
				assertTrue(ref.getReferenceText() != null && !ref.getReferenceText().trim().isEmpty(),
					"a reference-group citation carries its record's text: " + ref);
				if (ref.getIndex() == mebendazole) {
					assertTrue(ref.getReferenceText().contains("Mebendazole"), "the drug's own entry: " + ref.getReferenceText());
				}
			} else {
				assertNull(ref.getReferenceText(), "a chart citation carries none: " + ref);
			}
		}
		assertTrue(sawReference, "precondition: the answer cites reference material");
	}

	/** The question ADR Decision 108 named as its grammar's first miss to read, answered as its proposal is. */
	@Test
	public void theKneePainProposalIsAnsweredWithItsFindingsAndSaysThePurposeWasNotWeighed() {
		answerFromFindings(true);
		for (String question : Arrays.asList("Can I give her ibuprofen for her knee pain?",
			"Is ibuprofen safe to give for her knee pain?", "Can ibuprofen be given for her knee pain?")) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, "no model is asked for " + question + ": " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), question + " was: "
					+ answer.getAnswer());
			assertTrue(answer.getAnswer().endsWith("\nThis check did not weigh what the drug is for (her knee pain)."),
				question + " was: " + answer.getAnswer());
		}
	}

	/** The purpose as the question wrote it (ADR Decision 235): "her UTI" was echoed back as "her uti" on the standalone. */
	@Test
	public void thePurposeIsEchoedAsTheQuestionWroteIt() {
		answerFromFindings(true);
		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, "Can I give her ibuprofen for her Knee Pain?");

		assertTrue(answer.getAnswer().endsWith("\nThis check did not weigh what the drug is for (her Knee Pain)."),
			"was: " + answer.getAnswer());
	}

	/** What a purpose may not carry: a second question, a factor the findings do not weigh, or a question of its own. */
	@Test
	public void aPurposeCarryingSomethingTheFindingsDoNotAnswerStillAsksTheModel() throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		for (String question : Arrays.asList("Can I give her mebendazole for her kidneys?",
			"Can I give her mebendazole for how long?", "Can I give her mebendazole for worms and what dose?",
			"Can I give her mebendazole for worms during pregnancy?")) {
			RecordingProvider provider = new RecordingProvider();
			serviceWith(provider, shipped()).search(patient, question);

			assertEquals(1, provider.calls, "the model is asked " + question);
		}
	}

	/** The same beside a proposal the check relates to her orders only below the severity floor (ADR Decisions 142,
	 *  159): the below-floor answer, then the finding about her own orders. */
	@Test
	public void aProposalRelatedToHerOrdersOnlyBelowTheFloorIsAnsweredBesideHerOwnOrdersSharingASubstance()
			throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		String question = "Can I give her nystatin?";
		Finding duplicate = onlyFindingIsHerOwnOrdersSharingASubstance(question);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith("The interaction data gives no rated reason to withhold Nystatin"),
				answer.getAnswer());
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertFalse(answer.getAnswer().contains(" are in active orders "), answer.getAnswer());
	}

	/**
	 * A proposal whose own findings are all interaction cautions is answered with Decision 140's count of cautions
	 * beside a finding that two of her own orders share a substance, as it is without one (ADR Decision 160): the
	 * cautions, counted alone — the duplicate is not a caution about the drug proposed — then the duplicate as a line
	 * of its own. Measured 2026-10-07, "Can I give her clarithromycin?" beside her two rifampicin orders reached the
	 * model, the module appending the caution it left unstated.
	 */
	@Test
	public void aCautionOnlyProposalIsAnsweredBesideHerOwnOrdersSharingASubstance() throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		String question = "Can I give her clarithromycin?";
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		assertEquals(2, findings.size(), "premise: a caution and the duplicate, " + findings);
		Finding caution = findings.get(0).text.startsWith("Clarithromycin interacts with") ? findings.get(0) : findings.get(1);
		Finding duplicate = caution == findings.get(0) ? findings.get(1) : findings.get(0);
		assertTrue(caution.text.endsWith(DrugReferenceInjector.STRENGTH_CAUTION), "premise: a caution, " + caution.text);
		assertTrue(duplicate.text.startsWith(duplicate.drug + " are in active orders "), "premise: " + duplicate.text);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		String[] lines = answer.getAnswer().split("\\n");
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertEquals(2, lines.length, answer.getAnswer());
		assertEquals("1 interaction caution for Clarithromycin:", lines[0],
				"the count is of the cautions about the drug, not of her own orders' duplicate");
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Rifampicin (rifampin) — Moderate.")
				&& lines[1].contains(" [" + caution.index + "]"), lines[1]);
		assertFalse(answer.getAnswer().contains(" are in active orders "), answer.getAnswer());
	}

	/** And a withholding finding still leads with the module's "No", the duplicate stated last — what the "No" did
	 *  before ADR Decision 160, pinned beside the change to the caution path. */
	@Test
	public void aWithholdingProposalIsAnsweredBesideHerOwnOrdersSharingASubstance() throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, PROPOSAL);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertFalse(answer.getAnswer().contains("(2 orders) — possible duplicate therapy"), answer.getAnswer());
	}

	/**
	 * A proposal after a list is answered beside a finding that two of her own orders share a substance, as it is
	 * without one (ADR Decision 165): what the check established about the drug proposed, the list line, then the
	 * duplicate as a line of its own, before the statement of the listed drugs her chart does not hold. On the demo
	 * (2026-10-07) "The patient is currently on lamivudine and nevirapine, is it safe to give fluconazole?" was
	 * answered by the module for the two patients without such a finding and by the model for the three with one.
	 */
	@Test
	public void aListQuestionTheCheckRelatesToNoneOfHerOrdersIsAnsweredBesideHerOwnOrdersSharingASubstance()
			throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		String question = "The patient is currently on Zidovudine, is it safe to give mebendazole?";
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		assertEquals(2, findings.size(), "premise: the listed drug's own finding and the duplicate, " + findings);
		Finding listed = findings.get(0).text.startsWith("Zidovudine interacts with ") ? findings.get(0) : findings.get(1);
		Finding duplicate = listed == findings.get(0) ? findings.get(1) : findings.get(0);
		assertTrue(listed.text.startsWith("Zidovudine interacts with "), "premise: about the listed drug, " + findings);
		assertTrue(duplicate.text.startsWith(duplicate.drug + " are in active orders "), "premise: " + duplicate.text);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		String[] lines = answer.getAnswer().split("\n");
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertEquals(3, lines.length, answer.getAnswer());
		assertEquals("The interaction check relates Mebendazole to none of this patient's 3 active medications. ["
				+ referenceRecordOf(answer, "mebendazole") + "]", lines[0]);
		assertEquals("The check of Mebendazole against Zidovudine, also named in the question, raised no finding.",
				lines[1]);
		assertEquals("The chart holds no active order for Zidovudine.", lines[2]);
	}

	/** The same where a finding about the drug proposed answers the list question (ADR Decisions 150, 165): its lines,
	 *  counted alone under Decision 140's lead, then the duplicate. Ibuprofen relates to her aspirin Major. */
	@Test
	public void aListQuestionWhoseFindingsAboutTheDrugAnswerItIsAnsweredBesideHerOwnOrdersSharingASubstance()
			throws Exception {
		executeDataSet(RIFAMPICIN_ORDER);
		executeDataSet(SECOND_RIFAMPICIN_ORDER);
		String question = "The patient is currently on Zidovudine, is it safe to give ibuprofen?";
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		Finding duplicate = null;
		for (Finding finding : findings) {
			if (finding.text.startsWith(finding.drug + " are in active orders ")) {
				assertNull(duplicate, "premise: one duplicate, " + findings);
				duplicate = finding;
			}
		}
		assertNotNull(duplicate, "premise: her two rifampicin orders raise the duplicate, " + findings);

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(DrugReferenceInjector.WITHHOLD_LEAD_OPENING + "Ibuprofen.", lines[0], answer.getAnswer());
		assertTrue(lines[1].startsWith("Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major."),
				answer.getAnswer());
		assertEquals("The chart holds no active order for Zidovudine.", lines[lines.length - 1], answer.getAnswer());
		// Her own orders sharing a substance the question names none of are no answer to it (ADR Decision 387).
		assertFalse(answer.getAnswer().contains(" are in active orders "), answer.getAnswer());
		for (int i = 1; i < lines.length - 1; i++) {
			assertTrue(lines[i].startsWith("Ibuprofen interacts with "), "only ibuprofen's own findings above it: "
					+ answer.getAnswer());
		}
	}

	/** The premise both cases rest on: with the property off, the prompt carries one finding, and it is that two of
	 *  her own orders share a substance — none about the drug proposed. */
	private Finding onlyFindingIsHerOwnOrdersSharingASubstance(String question) {
		List<Finding> findings = findingsInThePromptFor(question, shipped());
		assertEquals(1, findings.size(), "premise: one finding, " + findings);
		Finding only = findings.get(0);
		assertTrue(only.text.startsWith(only.drug + " are in active orders "),
				"premise: the finding is her own orders sharing a substance, " + only.text);
		return only;
	}

	/** A pair below the floor on an order known by its ATC code alone is one of her orders all the same, and the
	 *  below-floor answer, which counts her orders and names none of them (ADR Decision 144), states it: before ADR
	 *  Decision 189 the module refused, and the model answered — on the 3.7.1 standalone, "Can I give her Tylenol?"
	 *  drew "No — Acetaminophen should not be given" over nine unrated rows, one of them on a code-only heparin order.
	 *  The order's stand-in display is counted and never printed. */
	@Test
	public void aProposalRelatedToHerOrdersOnlyBelowTheFloorOnACodeOnlyOrderIsAnsweredByTheModule() throws Exception {
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

		assertEquals(0, provider.calls, "no model is asked: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		int cited = -1;
		for (RecordReference ref : answer.getReferences()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(ref.getResourceType())) {
				cited = ref.getIndex();
			}
		}
		assertEquals("The interaction data gives no rated reason to withhold Subfloran: it lists Subfloran against one of "
				+ "this patient's orders, with no severity on file. [" + cited + "]\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.",
				answer.getAnswer());
		assertFalse(answer.getAnswer().contains("ATC"), "the code-only stand-in is counted, never printed");
	}

	/** Beside an order that has not started, the answer counts her medication orders and says which is scheduled (ADR
	 *  Decision 310), never counting it among her active medications — which is why the model was asked until then
	 *  (issue #592). Patient 6 holds only an aspirin order scheduled for 2099. */
	@Test
	public void aProposalOfNoPairBesideAnOrderThatHasNotStartedSaysItIsScheduled() throws Exception {
		executeDataSet("ScheduledAspirinOrderTestData.xml");
		Patient six = Context.getPatientService().getPatient(6);
		assertTheCheckRelatedNothing(six, "Can I give her mebendazole?", shipped());
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(six, "Can I give her mebendazole?");
		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.getAnswer().matches("The interaction check relates Mebendazole to none of this patient's 1 "
				+ "medication order, scheduled and not yet started\\. \\[\\d+\\]"), answer.getAnswer());
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

	/** A question whether a drug the check relates to none of her medications interacts with them is answered with what
	 *  the check established (ADR Decision 181, amending issue #592's model call for this shape); a question naming the drug
	 *  any other way keeps the model. */
	@Test
	public void aQuestionWhetherADrugOfNoPairInteractsIsAnsweredThatTheCheckRelatesNone() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Does mebendazole interact with her medications?");
		assertEquals(0, provider.calls, answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith("The interaction check relates Mebendazole to none of this patient's 1 active "
				+ "medication. ["), answer.getAnswer());
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "What does mebendazole do to her medications?", shipped());
	}

	/** Without the drug's reference record in the chart the answer would cite nothing for it, so the model answers
	 *  (issue #592): the question leg switched off injects no record for a drug she does not take. */
	@Test
	public void aProposalOfNoPairWhoseReferenceRecordIsNotInTheChartStillAsksTheModel() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY, "false");
		assertTheModelAnswersWhatTheCheckRelatedToNothing(patient, "Can I give her mebendazole?", shipped());
	}

	/** Beside an order the data cannot name, "none of her medications" is not established, so the sentence counts
	 *  only the medications the data identifies, and the answer names the one it could not (ADR Decision 161) —
	 *  until which the model answered. */
	@Test
	public void aProposalOfNoPairBesideAnOrderTheDataCannotNameCountsOnlyWhatTheDataIdentifies() throws Exception {
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		String question = "Can I give her mebendazole?";
		assertTheCheckRelatedNothing(patient, question, shipped());

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertEquals("The interaction check relates Mebendazole to none of the 1 active medication the drug data "
				+ "identifies for this patient. [" + referenceRecordOf(answer, "mebendazole") + "]\n"
				+ "Not checked: 1 active order the drug data does not identify — Marevan. Whether it is the drug "
				+ "asked about is not established.", answer.getAnswer());
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
		assertEquals("The interaction data gives no rated reason to withhold Metformin: it lists Metformin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + referenceRecordOf(answer, "metformin") + "]\n"
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
		assertEquals("The interaction data gives no rated reason to withhold Metformin: it lists Metformin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + referenceRecordOf(answer, "metformin") + "]\n"
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
		assertEquals("The interaction data gives no rated reason to withhold Metformin: it lists Metformin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + referenceRecordOf(answer, "metformin") + "]\n"
				+ "The check of Metformin against Methotrexate, also named in the question, raised no finding.\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.\n"
				+ "The chart holds no active order for Methotrexate.", answer.getAnswer());
	}

	/**
	 * A list question whose findings about the drug proposed withhold it only against a drug the question lists, which
	 * her chart does not hold, is answered "No if the patient is on" that drug (ADR Decision 150). Susan, asked of fluconazole
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
		assertEquals("No if the patient is on Efavirenz — this module's drug-safety check found a reason to withhold "
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

	/** A contraindication about the drug proposed after a list is stated with what else the check found, and no verdict
	 *  (ADR Decision 389), as Decision 311 answers the drug proposed alone. She is recorded allergic to fluconazole. */
	@Test
	public void aListQuestionWithAContraindicationAboutTheDrugProposedIsStatedWithNoVerdict() {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Fluconazole");
		String question = "The patient is currently on Nevirapine, is it safe to give fluconazole?";
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);
		assertEquals(0, provider.calls, "the module states what it found: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.FOUND_LEAD_OPENING + "Fluconazole:\n"),
			answer.getAnswer());
		assertTrue(answer.getAnswer().contains("recorded allergy to Fluconazole"), "her allergy: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains("No —") || answer.getAnswer().contains("No if")
				|| answer.getAnswer().contains("can be given"), "no verdict: " + answer.getAnswer());
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

	/**
	 * A question asking whether she was given two drugs together is her history too, and puts no finding about GIVING the
	 * one she is not on in the prompt (ADR Decision 468). On the 3.7.1 standalone <em>"Was fluconazole given with
	 * nevirapine?"</em> was answered "Fluconazole can be given with Nevirapine, with one caution" on 6 of 15 patients.
	 */
	@Test
	public void aHistoryQuestionAboutTwoDrugsCarriesNoFindingAboutGivingTheOneSheIsNotOn() {
		boolean proposalCarriesIt = false;
		for (Finding finding : findingsInThePromptFor("Can I give her ibuprofen with aspirin?", shipped())) {
			proposalCarriesIt |= finding.text.startsWith("Ibuprofen interacts with active order");
		}
		assertTrue(proposalCarriesIt, "precondition: a proposal of the two carries ibuprofen's finding against her aspirin");

		for (String question : new String[] { "Was ibuprofen given with aspirin?", "Has she taken ibuprofen and aspirin together?",
				"Did she take ibuprofen with aspirin before?", "Was she ever on ibuprofen and aspirin?" }) {
			for (Finding finding : findingsInThePromptFor(question, shipped())) {
				assertFalse(finding.text.startsWith("Ibuprofen interacts with active order"),
						question + " carries no finding about giving ibuprofen: " + finding.text);
			}
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
		assertEquals("The interaction data gives no rated reason to withhold Metformin: it lists Metformin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + referenceRecordOf(all, "metformin") + "]\n"
				+ "Interactions the data does not rate, and anything beyond drug interactions, are not covered.",
				all.getAnswer());
		assertEquals("The interaction data gives no rated reason to withhold Metformin: it lists Metformin against one of this "
				+ "patient's orders, with no severity or mechanism on file. [" + referenceRecordOf(some, "metformin") + "]\n"
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
		// Never that the check raised nothing about vorapaxar; the listed lamivudine it relates to nothing is named so (ADR
		// Decision 389).
		assertFalse(answer.getAnswer().contains("relates Vorapaxar to none")
				|| answer.getAnswer().contains("raised no finding about Vorapaxar"), answer.getAnswer());
		assertTrue(answer.getAnswer().contains("\nThe check of Vorapaxar against Lamivudine, also named in the question, raised "
				+ "no finding."), answer.getAnswer());
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
	/** The module answers with what its check found and no verdict (ADR Decisions 311, 312): a reason to withhold no rating
	 *  makes never licenses the module's "No", and since Decision 312 it is stated rather than handed to the model. */
	private void assertTheModuleStatesWhatItFoundWithNoNo(String question, DrugReferenceService reference) {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, reference).search(patient, question);
		assertEquals(0, provider.calls, "the module states what it found: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.FOUND_LEAD_OPENING), answer.getAnswer());
		assertFalse(answer.getAnswer().startsWith("No"), "no rating licenses a No: " + answer.getAnswer());
	}

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
		// Her warfarin allergy against her warfarin order is about warfarin, which the question does not name: no chip of it
		// beside an answer about omeprazole (ADR Decision 387).
		assertFalse(allergyChip, "no chip about her warfarin allergy beside an answer about omeprazole, chips were: "
				+ answer.getSafetyWarnings());
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
	 * A contraindication never decides the module's "No" (ADR Decision 108): the Major interaction licenses it, and
	 * without one the model answers. But a recorded allergy to the very drug proposed is the most direct reason, so the
	 * line under the "No" is that allergy, without "No severity is rated", and the interaction that licensed the "No"
	 * follows it (ADR Decision 192, on the owner's decision 2026-10-13, which reversed this case's earlier order).
	 */
	@Test
	public void theLineUnderTheNoIsTheAllergyToTheDrugThenTheInteractionThatLicensedIt() {
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
		assertEquals("The patient has a recorded allergy to Ibuprofen. [" + allergy.index + "]", lines[1],
				"the sentence under the \"No\" is the allergy to the drug proposed: " + answer.getAnswer());
		assertEquals("Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. Ibuprofen is in the same "
				+ "cross-reactivity group (NSAID) as active order Acetylsalicylic acid (aspirin) — possible additive or "
				+ "duplicate-class therapy. [" + interaction.index + "] [" + recordOf(answer, ASPIRIN_ORDER_UUID) + "]", lines[2],
				"then the interaction that licensed it: " + answer.getAnswer());
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
	 * chart, and the answer keeps it, without naming a drug the clause never named either: on a screen,
	 * where every contraindication is one, in the count (ADR Decision 191).
	 */
	@Test
	public void aContraindicationAboutAMedicationSheAlreadyTakesSaysSo() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		List<Finding> findings = findingsInThePromptFor(SAFETY_SCREEN);
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
		ChartAnswer answer = serviceWith(provider).search(patient, SAFETY_SCREEN);

		assertEquals(0, provider.calls);
		// On a screen every contraindication is about a medication she already takes, so the count says it once rather
		// than every line (ADR Decision 191); the line is the finding.
		assertTrue(answer.getAnswer().split("\n")[0].endsWith(", and 1 unrated contraindication about a medication this "
				+ "patient is already taking:"),
				"the count says the drug is one she already takes: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains("\n" + answerFacingBody(allergy).replace(
				" No severity is rated for this finding.", "") + " [" + allergy.index + "]"),
				"and the allergy line is the finding alone: " + answer.getAnswer());
		assertStatesEveryFindingBriefly(answer, findings);
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
		assertTrue(lines[1].startsWith(firstSentence(findings.get(0))) && lines[1].contains(" [" + findings.get(0).index
				+ "]"), "after its count, the unrated pair the arm raised first: " + answer.getAnswer());
		assertTrue(lines[2].startsWith(firstSentence(findings.get(1))) && lines[2].contains(" [" + findings.get(1).index
				+ "]"), "then the Major one: " + answer.getAnswer());
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
			// what dose to give: a question no finding answers (a proposal stating a dose is the module's where the data
			// carries no dosing, ADR Decision 322: aDoseTheDataCannotCheckIsAnsweredAsTheProposalWithTheDoseSaidNotChecked)
			"What dose of ibuprofen can I give her?",
			// current use, not a proposal — of a drug she does not take, answered by the module since ADR Decision 287
			// (whetherSheTakesADrugSheDoesNotIsAnsweredThatSheDoesNotThenWithTheFindings)
			// two drugs: each would need its own answer — which, neither of them hers, the module now gives each
			// (twoDrugsProposedTogetherAreEachAnsweredAsTheirOwnProposal, ADR Decision 222)
			// no proposal at all, in words a proposal is made of
			"Give her ibuprofen?",
			// a proposal cue beside a concern or a negation: "No" would answer it backwards
			"Can I give her ibuprofen, or is it risky?",
			// ("Can I give her ibuprofen or not?" asks the proposal either way, which "No" answers the right way round: the
			// module's since ADR Decision 457, aDrugAskedOfEitherWayOrOfSideEffectsWithHerMedicationsIsTheQuestionItSpells)
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
			// a condition the findings may not address. A purpose the answer can say it did not weigh is admitted
			// (theKneePainProposalIsAnsweredWithItsFindingsAndSaysThePurposeWasNotWeighed, ADR Decision 213); one naming
			// a factor the findings do not weigh is not.
			"Is ibuprofen safe for her kidneys?",
			"Can I give her ibuprofen for her kidney pain?",
			// (a second drug added to the first, "Is ibuprofen safe to add to omeprazole?", is the pair proposal it spells since
			// ADR Decision 469, anyIssuesAskedOfTwoDrugsAndADrugStartedAfterAnotherAreThePairQuestionsTheySpell)
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

	/**
	 * Whether she takes a drug she does not take is answered by the module: that she does not, then what the check found
	 * under a lead that is neither "Yes" nor "No" (ADR Decision 287). Left to the model, "Is she on efavirenz?" was
	 * answered "The drug can be given, with two cautions" on the 3.7.1 standalone.
	 */
	@Test
	public void whetherSheTakesADrugSheDoesNotIsAnsweredThatSheDoesNotThenWithTheFindings() {
		answerFromFindings(true);
		for (String question : new String[] { "Does she take ibuprofen?", "Is she on ibuprofen?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question);
			assertTrue(answer.isAnsweredByTheModule(), question);
			assertTrue(answer.getAnswer().startsWith("Ibuprofen is not one of this patient's active medications.\n"
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Ibuprofen" + DrugReferenceInjector.WITHHOLD_LEAD_CLOSING
					+ "\n"), question + " — " + answer.getAnswer());
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
	 * itself marks uncorroborated — one of the ways a rule's free-text match can be false. Since ADR Decision 311 the
	 * module states the finding, hedge and all, under no verdict, which the model was asked for until then.
	 */
	@Test
	public void aCuratedContraindicationRuleAloneLicensesNoNo() throws Exception {
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

		assertEquals(0, provider.calls, "the module states what it found: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith(DrugReferenceInjector.FOUND_LEAD_OPENING), answer.getAnswer());
		assertTrue(answer.getAnswer().contains("could not corroborate"), "the hedge stands: " + answer.getAnswer());
		assertFalse(answer.getAnswer().startsWith("No"), "a contraindication alone never licenses the module's No");
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
	public void anUnratedInteractionRuleLicensesNoNo() throws Exception {
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

		assertTheModuleStatesWhatItFoundWithNoNo(question, curated);
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
	public void aClassRelationshipFoldedOntoAMinorRowLicensesNoNo() throws Exception {
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

		assertTheModuleStatesWhatItFoundWithNoNo(question, ddinter);
	}

	/**
	 * Issue #402's shape where the module cannot SEE it: her warfarin is written as a brand the data does not carry
	 * ("Marevan"), so "not already taking it" cannot be asked of it. Until ADR Decision 161 that kept the model call,
	 * and with it every proposal for a patient holding ANY order the data cannot identify — on the demo a vaccine or
	 * infant formula, where the model then answered warfarin beside co-trimoxazole "should not be given with
	 * Sulfamethoxazole … can be given with Trimethoprim". The module now answers from what it found and closes with
	 * what it could not check: the order, by name, and that whether it is the drug asked about is not established.
	 */
	@Test
	public void aProposalBesideAnOrderTheDataCannotNameIsAnsweredNamingThatOrder() throws Exception {
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, "Can I give her warfarin?");

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		String[] lines = answer.getAnswer().split("\\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), answer.getAnswer());
		assertEquals("Not checked: 1 active order the drug data does not identify — Marevan. Whether it is the drug "
				+ "asked about is not established.", lines[lines.length - 1]);
	}

	/** Issue #402's shape: a question naming a drug she already takes. The drug-in-play arm states a proposal clause
	 *  for it, which a "No" composed from it would make deterministic — so it is not composed as a proposal. Since ADR
	 *  Decision 193 the module answers it as her own medication instead: what the drug is to her, then its findings in
	 *  brief lines, which carry no strength clause — never "withhold" and never "adding it". */
	@Test
	public void aDrugSheAlreadyTakesIsAnsweredAsHerOwnMedicationAndNeverAsAProposal() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String question = "Can I give her warfarin?";
		assertFalse(findingsInThePromptFor(question).isEmpty(),
				"precondition: warfarin x her aspirin order raises a finding, so it is the exclusion and "
						+ "not an empty finding list that keeps the call");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, question);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertTrue(answer.getAnswer().startsWith("Warfarin is one of this patient's active medications"),
				"what the drug is to her, first: " + answer.getAnswer());
		assertFalse(answer.getAnswer().contains(DrugReferenceInjector.WITHHOLD_LEAD_OPENING.trim())
				|| answer.getAnswer().contains("withhold") || answer.getAnswer().contains("adding it"),
				"never a proposal's clause about her own drug (issue #402): " + answer.getAnswer());
		// The finding that the drug is already in her order is what the lead states, so its chip — "adding it would
		// duplicate that order" — is published as stated, and drawn as read rather than beside the answer (ADR Decision
		// 194).
		boolean sawIt = false;
		for (org.openmrs.module.chartsearchai.reference.SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.getDetail().contains("is already in active order")) {
				sawIt = true;
				assertTrue(chip.isStatedInTheAnswer(), "the lead states it: " + chip.getDetail());
			}
		}
		assertTrue(sawIt, "precondition: the already-ordered chip is raised: " + answer.getSafetyWarnings());
	}

	/** A screen beside an order the data cannot identify is answered from the pairs it related and says that order
	 *  was not screened (ADR Decision 161) — until which the model answered. */
	@Test
	public void aScreenBesideAnOrderTheDataCannotNameIsAnsweredNamingThatOrder() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		List<Finding> findings = findingsInThePromptFor(SCREEN);
		assertFalse(findings.isEmpty(), "precondition: her warfarin and aspirin orders interact Major");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\\n");
		assertTrue(lines[1].startsWith(firstSentence(findings.get(0))),
				"after its count, the pair it related, as a screen does: " + answer.getAnswer());
		// One line saying what the screen did not check, the order last (ADR Decisions 493, 496).
		assertEquals("Not checked: interactions the data does not rate, anything beyond drug interactions, and 1 active order "
				+ "the drug data does not identify: Marevan.", lines[lines.length - 1]);
	}

	/**
	 * ADR Decision 171: a screen's answer opens with what it found, counted — never a choice of which medication to
	 * change — states each finding's brief line as a proposal's answer does, and closes with what it does not cover.
	 * Her warfarin and aspirin orders interact Major, and she is recorded as allergic to the aspirin she takes.
	 */
	@Test
	public void aScreensAnswerCountsItsFindingsStatesBriefLinesAndSaysWhatItDoesNotCover() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		answerFromFindings(true);
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SAFETY_SCREEN);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(4, lines.length, "a count, two findings and the scope: " + answer.getAnswer());
		assertEquals("1 interaction among this patient's active medications, and 1 unrated contraindication about a "
				+ "medication this patient is already taking:", lines[0]);
		assertTrue(lines[1].startsWith("Acetylsalicylic acid (aspirin) interacts with active order Warfarin — Major. ["),
				"the interaction's brief line — its first sentence, then its markers: " + lines[1]);
		assertTrue(lines[2].startsWith("The patient has a recorded allergy to Acetylsalicylic acid (aspirin)."),
				"then the allergy: " + lines[2]);
		assertEquals("Interactions the data does not rate, and anything beyond drug interactions and contraindications, "
				+ "are not covered.", lines[3]);
		for (org.openmrs.module.chartsearchai.reference.SafetyWarning chip : answer.getSafetyWarnings()) {
			boolean interaction = org.openmrs.module.chartsearchai.reference.SafetyWarning.TYPE_INTERACTION.equals(
					chip.getType());
			assertEquals(!interaction, chip.isStatedInTheAnswer(), "the interaction's mechanism stays on its chip, "
					+ "so only the allergy, stated whole, is stated: " + chip.getDetail());
			assertTrue(answer.getAnswer().contains("[" + chip.getFindingCitation() + "]"),
					"every chip is a finding the answer cites: " + chip.getDetail());
		}
	}

	@Test
	public void aScreenThatRaisedFindingsOpensWithItsCountAndChoosesNoDrugToChange() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		List<Finding> findings = findingsInThePromptFor(SCREEN);
		assertFalse(findings.isEmpty(), "precondition: her warfarin and aspirin orders interact Major");

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls);
		String[] lines = answer.getAnswer().split("\n");
		assertEquals("1 interaction among this patient's active medications:", lines[0],
				"a screen opens with what it found, counted — never a lead choosing which of the two to change "
						+ "(ADR Decision 171): " + answer.getAnswer());
		assertTrue(lines[1].startsWith(firstSentence(findings.get(0))),
				"then the finding, which names the medication and what it relates it to: " + answer.getAnswer());
		assertStatesEveryFindingBriefly(answer, findings);
		assertTrue(answer.isAnsweredByTheModule());
	}

	/**
	 * Ways a clinician asks for a screen of her medications, each measured on the 3.7.1 standalone over a patient whose
	 * orders the module relates in thirteen pairs, two Major (ADR Decisions 176, 177). <em>"Any DDIs?"</em>, <em>"What
	 * interactions does she have?"</em> and <em>"Any clinically significant interactions?"</em> ran no screen at all —
	 * none carries both an interaction or safety word and a medication word — and the model answered <em>"No
	 * clinically significant interactions are recorded."</em>; the rest ran the screen but fitted no screen shape, or
	 * carried a cue no family had, so the model answered them, opening a screen with <em>"No —"</em>. Each is now the
	 * module's counted answer, as "Does she have any drug interactions?" is.
	 */
	@Test
	public void everyWayOfAskingForAScreenIsAnsweredWithTheScreensCount() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Any DDIs?", "Any DDIs with her current medications?",
				"Any drug interactions I should worry about?", "Check her medications for interactions",
				"Any interactions?", "What interactions does she have?", "Any clinically significant interactions?",
				"Do her drugs interact?", "Is there any interaction among her current drugs?", "List her drug interactions",
				"Review her medications for interactions", "Are her medicines okay together?",
				"Are her medications compatible with each other?", "drug interactions?",
				"Please screen her current medications for drug interactions.", "Please check her meds for interactions",
				"Any medication interactions?", "Check for drug interactions", "Screen for DDIs", "Interaction check",
				"Any harmful drug combinations?", "Review her drugs for interactions please",
				"Any interactions I need to know about?", "What drug interactions should I watch for?",
				"Is there a problem with her medication combination?", "Is her regimen safe?",
				"Are there any contraindications among her medicines?", "Is it safe to continue all her current medications?",
				// A second sweep round (ADR Decision 180's amendment)
				"Any DDI?", "Are there drug-drug interactions?", "Do her meds clash?", "Any interactions between the drugs she is on?",
				"Is she on any interacting drugs?", "Any drug interaction alerts?", "Medication interaction review",
				"Are any of her drugs contraindicated together?", "Can her medications be taken together?",
				"Any interactions among the medicines she takes?", "Are there significant drug interactions?", "Any major interactions?",
				"Is anything she takes interacting?", "Drug interaction screen", "Any adverse drug interactions?",
				"Check her prescriptions for interactions", "Are there interactions with her current treatment?",
				"What are her drug interactions?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("1 interaction among this patient's active medications:\n"),
					question + " — the screen's count leads: " + answer.getAnswer());
		}
	}

	/**
	 * The plural abbreviation asks for a screen in any case (ADR Decision 340): on the 3.7.1 standalone <em>"any
	 * ddis"</em> ran no screen, the cue being case-sensitive for didanosine's "ddI", and the model told Brian Lopez, for whom
	 * "Interactions?" states seven interactions, one Major, <em>"No records of ddis are recorded."</em> No drug is named
	 * "ddIs".
	 */
	@Test
	public void theAbbreviationsPluralAsksForAScreenInAnyCase() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "any ddis", "Any ddis?", "any Ddis with her meds?", "check for ddis" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("1 interaction among this patient's active medications:\n"),
					question + " — the screen's count leads: " + answer.getAnswer());
		}
	}

	/** A question about her SOCIAL interactions is not a request to screen her medications, though it names the noun a
	 *  bare screen request does. */
	@Test
	public void aQuestionAboutHerSocialInteractionsAsksForNoScreen() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "How are her social interactions?",
				"Any concerning interactions with staff or family?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, question);
			assertNull(answer.getPairChipExtent(), question + " — no screen ran: " + answer.getPairChipExtent());
		}
	}

	/**
	 * Every question that asks for a screen of her medications and nothing else is the module's, whatever its words (ADR
	 * Decision 182): the closed grammar left each phrasing it had not listed to the model, and two sweeps on the 3.7.1
	 * standalone kept finding more — one-order patients told "The records do not address …", screens opened "No —".
	 * None of these is a shape of the grammar.
	 */
	@Test
	public void aScreenRequestOutsideTheGrammarIsAnsweredWithTheScreensCount() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Which of her medications interact?", "Could any of her meds be interacting?",
				"Do her medications have interactions between them?", "Should I stop any of her medications?",
				"Is anything wrong with her medications?", "Any interaction concerns about her current meds?",
				"Her medications, any interactions?", "Do any of her current prescriptions conflict?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("1 interaction among this patient's active medications:\n"),
					question + " — the screen's count leads: " + answer.getAnswer());
		}
	}

	/**
	 * Whether a group of her medications is safe together is a screen of them (ADR Decision 254). On the 3.7.1 standalone
	 * Rhoda Ritonavirb's "Is it safe to continue all her ARVs together?" ran no screen — "ARVs" is none of the medication
	 * words — and the model answered "The records do not address the safety of continuing all her ARVs together." beside
	 * two Major interactions among them.
	 */
	@Test
	public void whetherAGroupOfHerMedicationsIsSafeTogetherIsAScreen() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Is it safe to continue all her ARVs together?", "Are her ARVs safe together?",
				"Is it safe to give her TB drugs together?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.getAnswer().startsWith("1 interaction among this patient's active medications:\n"),
					question + " — the screen's count leads: " + answer.getAnswer());
		}
	}

	/** A screen request that also asks for something the screen does not answer keeps the model (ADR Decision 182). */
	@Test
	public void aScreenRequestAskingForSomethingElseToo_keepsTheModel() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "What medications is she on and do any interact?",
				"List her medications and any interactions", "Is her medication dose safe?",
				"Are her medications safe in pregnancy?", "Any interactions? What is her blood pressure?",
				"Are her medications safe with alcohol?", "Do any of her medications interact with her kidney function?",
				"Are her medications and allergies safe together?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);
			assertEquals(1, provider.calls, "the model must be asked: " + question + " — was: " + answer.getAnswer());
		}
	}

	/**
	 * A drug name the interaction data does not recognise, in the place a question names the drug it asks about (ADR
	 * Decision 183). <em>"Can I give her rifampcin?"</em> resolved nothing, the model answered <em>"The records do not
	 * address Rifampicin."</em>, and the post-answer pass, reading the model's corrected spelling, raised a Major
	 * Rifampicin chip beside it. The module now says no check ran, names the word, and suggests what the data does carry —
	 * never guessing which drug was meant, since a wrong guess screens the wrong drug.
	 */
	@Test
	public void aDrugNameTheDataDoesNotRecogniseIsSaidToBeUnrecognisedWithItsNearestNames() {
		String[][] cases = {
			{ "Can I give her rifampcin?", "\"rifampcin\"", "Did you mean Rifampicin" },
			// The insulins the data carries apart, none named "insulin" (tenofovir and lithium are named since ADR
			// Decision 284)
			{ "Can I give her insulin?", "\"insulin\"", "Did you mean Insulin" },
			{ "Does zorblatine interact with any of her medications?", "\"zorblatine\"", null },
			// "ddI", as the clinician writes it: the knowledge base leaves didanosine's abbreviation out on purpose
			{ "Can I give her ddI?", "\"ddI\"", null } };
		for (String[] c : cases) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider, shipped()).search(patient, c[0]);
			String statement = "No interaction check was run: " + c[1] + " is not a drug name the interaction data recognises.";
			if (c[2] == null) {
				// No near name: perhaps no drug at all, so the chart answer, the statement after it (ADR Decision 384).
				assertEquals(1, provider.calls, c[0] + " — the chart answer: " + answer.getAnswer());
				assertTrue(answer.getAnswer().endsWith("\n" + statement), c[0] + " — " + answer.getAnswer());
				assertFalse(answer.getAnswer().contains("Did you mean"), answer.getAnswer());
			} else {
				assertEquals(0, provider.calls, c[0] + " — " + answer.getAnswer());
				assertTrue(answer.getAnswer().startsWith(statement), c[0] + " — " + answer.getAnswer());
				assertTrue(answer.getAnswer().contains(c[2]), answer.getAnswer());
			}
			assertTrue(answer.getSafetyWarnings().isEmpty(), "no check ran, so no chip: " + answer.getSafetyWarnings());
		}
		ChartAnswer several = serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her insulin?");
		assertTrue(several.getAnswer().contains("Did you mean Insulin aspart")
				&& several.getAnswer().contains(", Insulin degludec, Insulin detemir or Insulin glargine? Ask again with the name "
						+ "it carries."), "every substance the word begins, up to four: " + several.getAnswer());
	}

	/**
	 * A two-drug question whose one drug the data recognises and whose other it does not says so of the pair (ADR Decision
	 * 250). On the 3.7.1 standalone Helen Roberts' "Is penicillin safe with her methotrexate?" was answered by the model
	 * "The records do not address the safety of penicillin with methotrexate." — though every penicillin the data carries is
	 * rated Major with methotrexate: the word resolved to nothing, so nothing was checked, and nothing said so.
	 */
	@Test
	public void aTwoDrugQuestionWithOneUnrecognisedNameSaysThePairWasNotChecked() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String[][] cases = {
			{ "Is penicillin safe with her warfarin?", "penicillin", "Did you mean Phenoxymethylpenicillin" },
			{ "Can I give her penicillin with her warfarin?", "penicillin", "Benzylpenicillin" },
			{ "Is it OK to co-prescribe penicillin with her warfarin?", "penicillin", "Benzylpenicillin" },
			{ "Does zorblatine interact with her warfarin?", "zorblatine", null } };
		for (String[] c : cases) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider, shipped()).search(patient, c[0]);
			String statement = "No interaction check was run for \"" + c[1] + "\" and Warfarin: \"" + c[1]
					+ "\" is not a drug name the interaction data recognises.";
			if (c[2] == null) {
				// No near name: the chart answer, the statement after it (ADR Decision 384).
				assertEquals(1, provider.calls, c[0] + " — the chart answer: " + answer.getAnswer());
				assertTrue(answer.getAnswer().endsWith("\n" + statement), c[0] + " — " + answer.getAnswer());
				assertFalse(answer.getAnswer().contains("Did you mean"), answer.getAnswer());
			} else {
				assertEquals(0, provider.calls, c[0] + " — " + answer.getAnswer());
				assertTrue(answer.getAnswer().startsWith(statement), c[0] + " — " + answer.getAnswer());
				assertTrue(answer.getAnswer().contains(c[2]), answer.getAnswer());
			}
		}
	}

	/**
	 * Something else's interactions with her medications ask nothing of her medications among themselves, so no screen of
	 * them is attached (ADR Decision 473). On the 3.7.1 standalone "Oranges: any interactions with her meds?" carried the
	 * screen of her own orders as chips on 9 of 15 patients, beside an answer about oranges.
	 */
	@Test
	public void somethingElsesInteractionsWithHerMedicationsCarryNoScreenOfThem() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		// Her recorded allergy to a drug she is ordered: a contraindication chip about her own medication.
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Warfarin");
		ChartAnswer screen = serviceWith(new RecordingProvider(), shipped()).search(patient, "Any interactions with her meds?");
		assertFalse(screen.getSafetyWarnings().isEmpty(), "precondition: the screen of her orders carries chips");
		for (String question : new String[] { "Oranges: any interactions with her meds?",
				"Any interactions between oranges and her medications?", "Do oranges interact with her medications?",
				"Orange juice - any issues with her medicines?", "Any food interactions with her meds?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no screen: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * A lab value named as hers is her result: no screen of her medications and no chip of their contraindications is
	 * attached (ADR Decision 484). On the 3.7.1 standalone "Is her potassium ok?" carried her orders' allergy
	 * contraindications as chips on 5 of 15 patients, the question read as one about a drug named as hers.
	 */
	@Test
	public void aLabValueNamedAsHersCarriesNoChip() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Warfarin");
		ChartAnswer screen = serviceWith(new RecordingProvider(), shipped()).search(patient, "Is her warfarin ok?");
		assertFalse(screen.getSafetyWarnings().isEmpty(), "precondition: her drug named as hers carries its chips");
		for (String question : new String[] { "Is her potassium ok?", "Is her glucose ok?", "Is his creatinine fine?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * A word with no near drug name beside her drug leaves the pair asked about unchecked, so no chip about that drug's
	 * other interactions is attached to the answer that says so (ADR Decision 479). On the 3.7.1 standalone "Can oranges be
	 * given to a patient on nevirapine?" said no check was run for oranges and nevirapine, yet carried nevirapine's own
	 * interactions as chips.
	 */
	@Test
	public void aWordWithNoNearDrugNameBesideHerDrugCarriesNoChipAboutThatDrug() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		ChartAnswer proposal = serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her warfarin?");
		assertFalse(proposal.getSafetyWarnings().isEmpty(), "precondition: warfarin's own findings are chips of its proposal");
		for (String question : new String[] { "Can oranges be given to a patient on warfarin?", "Is oranges safe with her warfarin?",
				"Does zorblatine interact with her warfarin?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
			assertTrue(answer.getAnswer().contains("is not a drug name the interaction data recognises"),
					"precondition: the answer says the pair was not checked: " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " carries no chip: " + answer.getSafetyWarnings());
		}
	}

	/**
	 * A word in a drug question's drug place that the drug data neither recognises nor finds a near name for may be no drug
	 * at all, so the question takes the chart answer, and the module states after it that no interaction check was run
	 * (ADR Decision 384). On the 3.7.1 standalone "Can i give her oranges?" was answered by the module alone, "No interaction
	 * check was run: "oranges" is not a drug name the interaction data recognises.", with no answer from her chart.
	 */
	@Test
	public void aWordWithNoNearDrugNameTakesTheChartAnswerWithTheUncheckedStatementAfterIt() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Can i give her oranges?");
		assertEquals(1, provider.calls, "the chart answer: " + answer.getAnswer());
		assertEquals("The model's answer [1].\nNo interaction check was run: \"oranges\" is not a drug name the interaction "
				+ "data recognises.", answer.getAnswer().replaceAll("\\[\\d+\\]", "[1]"));
		assertFalse(answer.isAnsweredByTheModule(), "the model's answer, the module's line after it");
		assertTrue(answer.getSafetyWarnings().isEmpty(), "no check ran, so no chip: " + answer.getSafetyWarnings());
	}

	/** On the streaming path too: the answer it returns and the early done a streaming user sees first both carry the
	 *  statement after the chart answer (ADR Decision 384). */
	@Test
	public void searchStreaming_aWordWithNoNearDrugNameCarriesTheUncheckedStatementOnBothAnswers() {
		String statement = "No interaction check was run: \"oranges\" is not a drug name the interaction data recognises.";
		RecordingProvider provider = new RecordingProvider();
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceWith(provider, shipped()).searchStreaming(patient, "Can i give her oranges?", t -> { },
				r -> { }, c -> { }, early::add);
		assertTrue(provider.calls >= 1, "the chart answer: " + answer.getAnswer());
		assertTrue(answer.getAnswer().endsWith("\n" + statement), "the returned answer: " + answer.getAnswer());
		assertEquals(1, early.size(), "the early done fired");
		assertTrue(early.get(0).getAnswer().endsWith("\n" + statement), "the early done: " + early.get(0).getAnswer());
	}

	/** The unrecognised name is quoted as the question wrote it, hyphen and all (ADR Decision 245): on the 3.7.1 standalone
	 *  "Can I give him co-trimoxazole?" was told that "co trimoxazole" is not a name the data recognises — a spelling the
	 *  clinician never wrote, which reads as the module having misread the question. The data has recognised
	 *  co-trimoxazole since ADR Decision 266, so the case asks about a hyphenated name it does not carry. */
	@Test
	public void anUnrecognisedNameIsQuotedAsTheQuestionWroteItHyphenIncluded() {
		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, "Can I give her co-zorblatine?");
		// No near name, so after the chart answer (ADR Decision 384), quoted as written.
		assertTrue(answer.getAnswer().endsWith("\nNo interaction check was run: \"co-zorblatine\" is not a drug name the "
				+ "interaction data recognises."), answer.getAnswer());
	}

	/**
	 * A proposal stating a dose the dataset publishes no ceiling to check is answered by the module as the same proposal
	 * without the dose, and the dose said, on a line of its own, not to have been checked (ADR Decision 322, superseding
	 * Decision 269's model call and Decision 274's sentence after the model's answer): on the 3.7.1 standalone the model
	 * answered "Can I give her 1 g of paracetamol?" for Karen Sanchez, on metronidazole, "Paracetamol can be given, with
	 * one caution" — a clearance no finding makes. Where the data publishes dose ceilings the model still answers it.
	 */
	@Test
	public void aDoseTheDataCannotCheckIsAnsweredAsTheProposalWithTheDoseSaidNotChecked() {
		answerFromFindings(true);
		RecordingProvider plain = new RecordingProvider();
		ChartAnswer proposal = serviceWith(plain).search(patient, PROPOSAL);
		assertEquals(0, plain.calls, "precondition: the module answers the proposal without a dose");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, "Can I give her ibuprofen 400mg?");
		assertEquals(0, provider.calls, "the module answers the dose too: " + answer.getAnswer());
		assertEquals(proposal.getAnswer().replaceAll("\\[\\d+\\]", "[n]") + "\n" + DoseStatement.NOT_CHECKED,
			answer.getAnswer().replaceAll("\\[\\d+\\]", "[n]"));

		DrugReferenceService ceilings = DrugReferenceTestSupport.curatedService();
		assertEquals(DrugReferenceLoad.Coverage.PUBLISHED, DrugReferenceTestSupport.validator(ceilings).doseCeilingCoverage(),
			"precondition: this dataset publishes dose ceilings");
		RecordingProvider checked = new RecordingProvider();
		serviceWith(checked, ceilings).search(patient, "Can I give her ibuprofen 400mg?");
		assertEquals(1, checked.calls, "a dose the data can weigh is the model's, with the dosing record injected");

		RecordingProvider noDose = new RecordingProvider();
		ChartAnswer without = serviceWith(noDose).search(patient, "Is ibuprofen safe for her kidneys?");
		assertEquals(1, noDose.calls, "precondition: the model answers it too");
		assertFalse(without.getAnswer().contains(DoseStatement.NOT_CHECKED), "no dose, no sentence: " + without.getAnswer());
	}

	/** An unrecognised name in an interaction question raises no screen of her own orders beside "no check was run": on the
	 *  3.7.1 standalone "Does metformn interact with her medications?" carried a Major chip about two of her own orders. */
	@Test
	public void anUnrecognisedNameRaisesNoScreenOfHerOwnOrders() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		assertFalse(findingsInThePromptFor(SCREEN).isEmpty(), "precondition: her own orders interact");
		for (String question : new String[] { "Does metformn interact with her medications?", "Can I give her ddI?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
			// "metformn" has a near name and is the module's answer; "ddI" has none, so it follows the chart answer (ADR
			// Decision 384). Either way, no screen of her own orders.
			assertTrue(answer.getAnswer().contains("No interaction check was run: "), question + " — " + answer.getAnswer());
			assertTrue(answer.getSafetyWarnings().isEmpty(), question + " — no chip: " + answer.getSafetyWarnings());
		}
	}

	/** A combination's name is not a suggestion for one of its ingredients: "Hyoscine butylbromide / Paracetamol" is a
	 *  name acetaminophen's row carries, and "Did you mean Acetaminophen?" for hyoscine butylbromide names the wrong drug
	 *  (ADR Decision 183). */
	@Test
	public void aCombinationNameIsNeverSuggestedForAWordItBeginsWith() {
		for (String question : new String[] { "Can I give her hyoscine butylbromide?", "Can I give her hyoscine?" }) {
			ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, question);
			// "hyoscine butylbromide" has no near name, so it follows the chart answer (ADR Decision 384); "hyoscine" has
			// one, Scopolamine, and is the module's answer. Neither is ever given the combination's ingredient.
			assertTrue(answer.getAnswer().contains("No interaction check was run: \"" + question.substring(15,
				question.length() - 1) + "\""), question + " — " + answer.getAnswer());
			assertFalse(answer.getAnswer().contains("Acetaminophen"), question + " — " + answer.getAnswer());
		}
	}

	/** A question naming no drug in a drug's place is not answered so: "this drug" is not a name. */
	@Test
	public void aQuestionNamingNoDrugIsNotSaidToNameAnUnrecognisedOne() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(patient, "Can I give her this drug?");
		assertFalse(answer.getAnswer().startsWith("No interaction check was run"), answer.getAnswer());
	}

	/** "ddI" is didanosine's own abbreviation, so only the capitalised "DDI" asks for a screen: a question about her
	 *  ddI dose is not one. */
	@Test
	public void didanosinesAbbreviationDoesNotAskForAScreen() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		ChartAnswer answer = serviceWith(new RecordingProvider()).search(patient, "Is her ddI dose correct?");

		assertNull(answer.getPairChipExtent(), "no screen ran, so none is stated: " + answer.getPairChipExtent());
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
		for (String question : new String[] { "Is grapefruit juice safe with her medications?",
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
			assertTrue(answer.getAnswer().split("\n")[1].startsWith(
					"Acetylsalicylic acid (aspirin) interacts with active order"),
					"after its count, the interaction leads, not the allergy finding: " + answer.getAnswer());
		}
	}

	/**
	 * Asking which of her medications to stop or change because of interactions asks for what a screen informs (ADR
	 * Decision 207), not for her medication list. Measured on the :8081 rig: Sarah Taylor's "Which of her medications
	 * should be stopped because of interactions?" went to the model, which answered "The records do not indicate any
	 * medication that should be stopped" beside 21 interactions the module had found.
	 */
	@Test
	public void askingWhichOfHerMedicationsToStopBecauseOfInteractionsIsAScreen() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Which of her medications should be stopped because of interactions?",
				"Which of her medications should be changed because of drug interactions?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.isAnsweredByTheModule(), question);
			assertTrue(answer.getAnswer().split("\n")[1].startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					question + " — was: " + answer.getAnswer());
		}
		RecordingProvider provider = new RecordingProvider();
		serviceWith(provider).search(patient, "Which of her medications should be taken with food?");
		assertEquals(1, provider.calls, "a question about taking them with food is still the model's");
	}

	/**
	 * The same question in the active voice (ADR Decision 241). Measured on the 3.7.1 standalone: Rhoda Ritonavirb's
	 * "Which of her medications should I stop?" went to the model, which answered "Nevirapine should be changed because it
	 * interacts with Lopinavir / ritonavir" — a choice of drug the data does not make, over one of thirteen interactions.
	 */
	@Test
	public void askingWhichOfHerMedicationsIShouldStopIsAScreen() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Which of her medications should I stop?",
				"Which of her meds should we change?", "Which medications should I discontinue?",
				"Which of her medications should be stopped?",
				// ADR Decision 300: Brian Lopez was told "No — Diazepam should be changed".
				"Does she need any of her medications adjusted?", "Do any of her medications need to be changed?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.isAnsweredByTheModule(), question);
			assertTrue(answer.getAnswer().split("\n")[1].startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					question + " — was: " + answer.getAnswer());
		}
		RecordingProvider provider = new RecordingProvider();
		serviceWith(provider).search(patient, "Which of her medications should I give with food?");
		assertEquals(1, provider.calls, "a question about giving them with food is still the model's");
	}

	/**
	 * A question asking the module to CHOOSE a drug for her — "Which pain medication is safe for her?" — is answered by
	 * the module, which chooses none, with the screen of what she already takes (ADR Decision 293). Measured on the 3.7.1
	 * standalone: Sarah Taylor, on three NSAIDs, five corticosteroids and heparin, was answered by the model with the
	 * NSAID–corticosteroid caution alone, leaving out the heparin bleeding risk and the three NSAIDs taken together; and
	 * "What painkiller can I safely give her?" screened nothing and was told "The records do not contain any safety
	 * findings regarding painkillers".
	 */
	@Test
	public void askingWhichDrugIsSafeForHerIsAnsweredWithTheScreenAndNoChoice() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		String lead = "This check does not choose a medication to give this patient, and states none as safe: it screens"
				+ " only the medications this patient is already taking.";
		for (String question : new String[] { "Which pain medication is safe for her?",
				"What painkiller can I safely give her?", "Which antibiotic is safest for her?",
				"What is the safest painkiller for her?", "What can she safely take?",
				"Which of her medications is safe?", "Which blood pressure medicine would be best for her?",
				// ADR Decision 299: Agnes Adams was told "No — Aspirin should not be continued".
				"Is there a safer alternative to her current medications?", "Are there any better options for her meds?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.isAnsweredByTheModule(), question);
			String[] lines = answer.getAnswer().split("\n");
			assertEquals(lead, lines[0], question + " — was: " + answer.getAnswer());
			assertTrue(lines[2].startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					question + " — was: " + answer.getAnswer());
			assertFalse(answer.getSafetyWarnings().isEmpty(), question + " — the screen's chips are published");
		}
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, "What can I give her for pain?");
		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(lead, lines[0], answer.getAnswer());
		assertEquals("This check did not weigh what the drug is for (pain).", lines[lines.length - 1], answer.getAnswer());

		for (String question : new String[] { "Which diet is best for her?", "Which treatment option is best for her?",
				"What is the safest dose for her?",
				"What safe medications can I give her in pregnancy?", "Which pain medication is safe for her kidneys?" }) {
			RecordingProvider model = new RecordingProvider();
			serviceWith(model).search(patient, question);
			assertEquals(1, model.calls, question + " is still the model's");
		}
	}

	/**
	 * What to monitor with her medications is answered by the module with the screen, under a lead saying it sets no
	 * monitoring (ADR Decision 297). On the 3.7.1 standalone the model answered "No — Diazepam should not be taken with
	 * Tramadol", "Simvastatin should be changed", and, for Sarah Taylor's 21 interactions, "The patient can continue with
	 * her current medications".
	 */
	@Test
	public void whatToMonitorWithHerMedicationsIsTheScreenUnderALeadSettingNoMonitoring() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "What should I monitor with her current medications?",
				"What should we monitor for her medications?", "What monitoring does she need for her medications?",
				"What do I need to monitor with her meds?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			String[] lines = answer.getAnswer().split("\n");
			assertEquals("This check does not say what to monitor: it states what it found among the medications this "
					+ "patient is already taking.", lines[0], question + " — was: " + answer.getAnswer());
			assertTrue(lines[2].startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					question + " — was: " + answer.getAnswer());
		}
		RecordingProvider provider = new RecordingProvider();
		serviceWith(provider).search(patient, "What should I monitor for her blood pressure?");
		assertEquals(1, provider.calls, "monitoring of something other than her medications is still the model's");
	}

	/**
	 * Which of her medications are high risk is answered by the module with the screen, under a lead saying it rates no
	 * medication's risk (ADR Decision 300). On the 3.7.1 standalone the model named Kevin Brown's recorded allergies as his
	 * high-risk medications.
	 */
	@Test
	public void whichOfHerMedicationsAreHighRiskIsTheScreenUnderALeadRatingNoRisk() throws Exception {
		executeDataSet(WARFARIN_ORDER);
		for (String question : new String[] { "Which of her medications are high risk?",
				"Are any of her medications high risk?", "Which of her meds are dangerous?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			String[] lines = answer.getAnswer().split("\n");
			assertEquals("This check does not rate a medication's risk: it states what it found among the medications this "
					+ "patient is already taking.", lines[0], question + " — was: " + answer.getAnswer());
			assertTrue(lines[2].startsWith("Acetylsalicylic acid (aspirin) interacts with active order"),
					question + " — was: " + answer.getAnswer());
		}
	}

	/**
	 * A screen that related nothing is answered by the module with its own screen note, word for word, citing it (ADR
	 * Decision 162). Until then the model answered from the note and dropped its scope — on the demo (2026-10-07)
	 * "No interactions were found among this patient's active medications [46]", beside six orders that were never
	 * checked. The note's own words keep both of its limits: the configured severity floor, and that a relationship
	 * resting only on a shared drug class is not part of the screen.
	 */
	@Test
	public void aScreenThatRelatedNothingIsAnsweredWithTheScreenNotesOwnWords() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		int note = screenNoteIndex();

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals(SCREEN_NOTE_WORDS + " [" + note + "]", answer.getAnswer());
	}

	/** The same beside an order the data cannot identify: the note's words, then the order named as not screened
	 *  (ADR Decisions 161, 162). */
	@Test
	public void aScreenThatRelatedNothingBesideAnOrderTheDataCannotNameNamesThatOrder() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		executeDataSet("AnswerFromFindingsUnnamedWarfarinOrderTestData.xml");
		int note = screenNoteIndex();

		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertEquals(SCREEN_NOTE_WORDS + " [" + note + "]\n"
				+ "Not checked: 1 active order the drug data does not identify — Marevan.", answer.getAnswer());
	}

	/** A screen for a patient with no active medication orders says so (ADR Decision 162): there are none to check
	 *  against each other. The model answered "The records do not address whether any of her medications are
	 *  interacting with each other." Patient 6 holds no active order. */
	@Test
	public void aScreenForAPatientWithNoActiveOrdersSaysThereAreNoneToCheck() {
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider, shipped()).search(Context.getPatientService().getPatient(6), SCREEN);

		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals("This patient has no active medication orders, so there are none to check against each other.",
				answer.getAnswer());
	}

	/**
	 * A patient with ONE active medication order has no pair to screen, and the model, left to answer, did not say so:
	 * on the 3.7.1 standalone a patient whose one order is acetaminophen was told, for "What interactions does he
	 * have?", that he "has had the following interactions: Encounter: Vitals at Outpatient Clinic …". The module says
	 * what is true of the chart, as it does for a patient with none (ADR Decision 178).
	 */
	@Test
	public void aScreenForAPatientWithOneActiveOrderSaysThereIsNothingToCheckItAgainst() {
		for (String question : new String[] { SCREEN, "What interactions does she have?" }) {
			RecordingProvider provider = new RecordingProvider();
			ChartAnswer answer = serviceWith(provider, shipped()).search(patient, question);

			assertEquals(0, provider.calls, question + " — the module answers: " + answer.getAnswer());
			assertTrue(answer.isAnsweredByTheModule());
			assertTrue(answer.getAnswer().matches("This patient has one active medication order, \\S.*, so there is no "
					+ "other active medication to check it against\\."), question + " — was: " + answer.getAnswer());
		}
	}

	/** Two orders of one drug are not "one active medication order": the module does not say there is nothing to check. */
	@Test
	public void aScreenForAPatientWithTwoOrdersIsNotToldThereIsOnlyOne() throws Exception {
		executeDataSet(METFORMIN_ORDER);
		ChartAnswer answer = serviceWith(new RecordingProvider(), shipped()).search(patient, SCREEN);

		assertFalse(answer.getAnswer().contains("one active medication order"), answer.getAnswer());
	}

	/** The screen note's words for two substances, as {@code DrugReferenceInjector} renders them after its finding
	 *  prefix — pinned here as a literal, so a reword of the note shows up as a changed answer. */
	/** Her aspirin and metformin, which DDInter relates by one row rated Unknown, below the floor: counted, never "No
	 *  interactions were found" (ADR Decision 467). */
	private static final String SCREEN_NOTE_WORDS = "No interactions at or above the configured severity level were found "
			+ "among this patient's active medications. 2 substances in them were checked against each other. The reference "
			+ "data lists one pair of them only below that level, with no severity or mechanism on file. This check compares "
			+ "individual substances: relationships resting only on two drugs sharing a drug class are not part of it, so it "
			+ "is not a statement that no relationship exists.";

	/** With the property off, the screen's prompt: the note is injected, and its record number. */
	private int screenNoteIndex() {
		answerFromFindings(false);
		RecordingProvider recorder = new RecordingProvider();
		serviceWith(recorder).search(patient, SCREEN);
		answerFromFindings(true);
		Matcher m = Pattern.compile("\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX + "interaction screen."))
				.matcher(recorder.lastRecords);
		assertTrue(m.find(), "precondition: the screen note is injected, chart was: " + recorder.lastRecords);
		return Integer.parseInt(m.group(1));
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
	 * A screen of her medications whose only finding is not an interaction — her allergy against an order of hers — is
	 * answered with what the screen found, that finding after it under its own lead, never with that finding alone (ADR
	 * Decision 303, which superseded the model call this case pinned): an answer of only that finding would never say what
	 * the screen found.
	 */
	@Test
	public void aScreenWhoseOnlyFindingIsNotAnInteractionStatesWhatTheScreenFoundFirst() throws Exception {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		executeDataSet(METFORMIN_ORDER);
		assertFalse(findingsInThePromptFor(SCREEN).isEmpty(),
				"precondition: her aspirin allergy against her aspirin order is a finding");
		RecordingProvider provider = new RecordingProvider();
		ChartAnswer answer = serviceWith(provider).search(patient, SCREEN);
		assertEquals(0, provider.calls, "the module answers: " + answer.getAnswer());
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith(SCREEN_NOTE_WORDS), answer.getAnswer());
		assertEquals("Found as well, beyond interactions:", lines[1], answer.getAnswer());
		assertTrue(lines.length == 3 && lines[2].matches("The patient has a recorded allergy to Acetylsalicylic acid \\(aspirin\\)\\. "
				+ "This finding is about a medication this patient is already taking\\. \\[\\d+\\]"), answer.getAnswer());
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
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return record(numberedRecords);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
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
