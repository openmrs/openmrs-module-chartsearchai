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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnfoundedFindingSeverity;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/560">#560</a>: an
 * answer attaches a severity to a cited safety finding that carries none. Measured live on Sarah
 * Taylor's chart (a {@code Prednisone Co 5mg} order, recorded allergies to dexamethasone and
 * hydrocortisone): her dexamethasone cross-reactivity finding has no rating and its record says
 * <em>"No severity is rated for this finding."</em>, and answers still called it <em>"a Major
 * finding"</em>. {@code unstatedFindingSeverities} asks the opposite question and says nothing about a
 * record with no rating, by construction.
 *
 * <p>The cases run the real {@link LlmInferenceService#search}/{@code searchStreaming} orchestration
 * over a chart the real {@link PatientChartSerializer} rendered and the real
 * {@code DrugSafetyValidator} &rarr; {@code injectRecords} chain injected findings into — over the
 * knowledge base the module SHIPS, which is what raises her unrated cross-reactivity findings, except
 * where a case names a hand-authored dataset or a hand-built chart. The model is stubbed, the answer
 * being the one variable this check is about, and so are the seams {@code serviceOver} names: the chart
 * is served already injected, and the post-answer chips are not raised.
 *
 * <p>The findings a case cites are located by what their records SAY — the drug they name, and the
 * rating the injector wrote — never by the stamp the check reads, so a stamp written on the wrong record
 * cannot locate its own evidence.
 */
public class UnfoundedFindingSeverityTest {

	/** The ticket's two questions. */
	private static final String CAN_I_GIVE = "Can I give her prednisone?";

	private static final String IS_IT_SAFE = "Is prednisone safe for her?";

	/** Decision 78's cell — the control, whose answer states only ratings its findings carry. */
	private static final String CLARITHROMYCIN_QUESTION = "Is it safe to start her on clarithromycin?";

	private static final String CHECK = UnfoundedFindingSeverityCheck.class.getName();

	/** One service over the shipped knowledge base for the whole class: loading it is the expensive
	 *  part, and every arrangement below reads it without writing to it. */
	private static final DrugReferenceService SHIPPED = DrugReferenceTestSupport.shippedServiceWithGroups();

	@Test
	public void theTicketsOwnAnswerIsReported() {
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		LlmInferenceService service = serviceOver(chart,
				"Prednisone should be reviewed: her dexamethasone allergy makes this a Major finding ["
						+ dexamethasone + "].");
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
			assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, "Major")),
					answer.getUnfoundedFindingSeverities(),
					"the answer calls a finding that carries no rating Major, and the response must say so "
							+ "— the citation and the rating it was given, never a word of either text");
			assertTrue(capture.hasMessageAt(Level.WARN, "[" + dexamethasone + "] Major", "patient=1"),
					"and the maintainer's channel must carry the same pair with the patient. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void theUnitIsTheSentenceCitingTheUnratedFinding() {
		// Main's own phrasing, and the discriminator the whole-answer unit could not make: "Major" is in
		// the answer, and only the first sentence attaches it to an unrated finding. The second cites the
		// other unrated finding and states no rating.
		PatientChart chart = sarahTaylor(IS_IT_SAFE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		int hydrocortisone = unratedFindingNaming(chart, "Hydrocortisone");
		LlmInferenceService service = serviceOver(chart,
				"No — her dexamethasone allergy is a Major reason to change her prednisone [" + dexamethasone
						+ "]. Her hydrocortisone allergy is a second possible cross-reactivity ["
						+ hydrocortisone + "].");
		ChartAnswer answer = service.search(patient(), IS_IT_SAFE);
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, "Major")),
				answer.getUnfoundedFindingSeverities(),
				"only the finding whose own sentence states a rating is reported");
	}

	@Test
	public void aRatingStatedOnlyInAnotherSentenceIsNotReported() {
		// The inverse of the sibling's whole-answer unit: "Major" elsewhere is exactly what must NOT be
		// read as attached to the unrated finding. The other sentence's rating is one no cited finding
		// carries, so the co-cited exemption cannot stand in for the unit here.
		PatientChart chart = sarahTaylor(CAN_I_GIVE, true);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		int moderate = ratedFinding(chart, "Moderate");
		LlmInferenceService service = serviceOver(chart,
				"Prednisone interacts with active order Clarithromycin, a Major concern [" + moderate
						+ "]. Her dexamethasone allergy is a possible cross-reactivity [" + dexamethasone
						+ "].");
		ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				answer.getUnfoundedFindingSeverities(),
				"a rating in a sentence citing no unrated finding is attached to none, and the check "
						+ "ran, so it states a measurement of none rather than null");
	}

	@Test
	public void aRatingAnotherFindingCitedInTheSameSentenceCarriesIsNotReported() {
		// The #554 fourth run's shape: the rating quoted from another finding's detail, in the sentence
		// that also cites the unrated one.
		PatientChart chart = sarahTaylor(CAN_I_GIVE, true);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		int moderate = ratedFinding(chart, "Moderate");
		LlmInferenceService service = serviceOver(chart,
				"Prednisone interacts with active order Clarithromycin, a Moderate finding, and her "
						+ "dexamethasone allergy is a possible cross-reactivity [" + moderate + "] ["
						+ dexamethasone + "].");
		ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				answer.getUnfoundedFindingSeverities(),
				"Moderate is the rating the co-cited interaction finding carries");
	}

	@Test
	public void aRatingNoFindingCitedInTheSentenceCarriesIsStillReported() {
		// The same sentence, with a rating the co-cited finding does NOT carry: the exemption is for a
		// rating some other cited finding carries, never for any rating once a rated finding is cited.
		PatientChart chart = sarahTaylor(CAN_I_GIVE, true);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		int moderate = ratedFinding(chart, "Moderate");
		LlmInferenceService service = serviceOver(chart,
				"Prednisone interacts with active order Clarithromycin, and her dexamethasone allergy is a "
						+ "Major cross-reactivity [" + moderate + "] [" + dexamethasone + "].");
		ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, "Major")),
				answer.getUnfoundedFindingSeverities());
	}

	@Test
	public void aRatingWordInARatedFindingsMechanismDoesNotCountAsTheRatingItCarries() {
		// A rated finding carries its rating and no other: on the shipped knowledge base Clarithromycin's
		// Major rule with Lumateperone says "strong or moderate inhibitors of CYP450 3A4", and that word is
		// not a Moderate rating the unrated erythromycin cross-reactivity beside it could borrow.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(SHIPPED,
				chartOf("Lumateperone 42mg capsule"), CLARITHROMYCIN_QUESTION, setOf("Lumateperone 42mg"),
				setOf("N05AD10"), setOf("erythromycin"));
		int major = ratedFinding(chart, "Major");
		int erythromycin = unratedFindingNaming(chart, "Erythromycin");
		assertTrue(DrugReferenceTestSupport.findingAt(chart, major).getText().contains("moderate inhibitors"),
				"precondition: the Major finding's mechanism says moderate: " + chart.getText());
		LlmInferenceService service = serviceOver(chart,
				"Clarithromycin interacts with active order Lumateperone, and her erythromycin allergy is a "
						+ "Moderate cross-reactivity [" + major + "] [" + erythromycin + "].");
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(erythromycin, "Moderate")),
				service.search(patient(), CLARITHROMYCIN_QUESTION).getUnfoundedFindingSeverities());
	}

	@Test
	public void aRatedFindingWhoseRecordOmitsItsRatingLendsNoWordOfItsMechanism() throws java.io.IOException {
		// The same rule where the record does not state the rating, which only an operator dataset
		// produces: getFindingSeverity() is null for a finding that DOES carry a rating, so nullness is not
		// "carries none", and the mechanism's "moderate" still lends nothing to the unrated finding.
		String question = "Can I give her ibuprofen?";
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverOrdersWithRecordedAllergies(
				DrugReferenceTestSupport.curatedFixtureService(
						"chartsearchai-test/drug-reference-rated-note-omits-its-rating.json"),
				DrugReferenceTestSupport.oneRecordChart(), question, setOf("ibuprofen"), "Warfarin");
		int allergy = unratedFindingNaming(chart, "Ibuprofen");
		int interaction = findingWhoseText(chart, "moderate doses");
		assertEquals(null, DrugReferenceTestSupport.findingAt(chart, interaction).getFindingSeverity(),
				"precondition: its record does not state its Major rating, so the field is null");
		assertEquals(Boolean.FALSE, DrugReferenceTestSupport.findingAt(chart, interaction).getFindingUnrated(),
				"precondition: and it still carries a rating");
		LlmInferenceService service = serviceOver(chart,
				"Ibuprofen interacts with active order Warfarin, and her ibuprofen allergy is a Moderate concern ["
						+ interaction + "] [" + allergy + "].");
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(allergy, "Moderate")),
				service.search(patient(), question).getUnfoundedFindingSeverities());
	}

	@Test
	public void everyRatingStatableRatingKnowsIsReportedAndUnknownIsNot() {
		// The vocabulary is statableRating's: the three ratings an answer is asked to carry, and never
		// "unknown", which DDInter uses for rows with no mechanism and which a correct answer can say of
		// an unrated finding ("its severity is unknown").
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		for (String rating : Arrays.asList("Minor", "Moderate", "Major")) {
			LlmInferenceService service = serviceOver(chart,
					"Her dexamethasone allergy is a " + rating.toLowerCase() + " cross-reactivity ["
							+ dexamethasone + "].");
			assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, rating)),
					service.search(patient(), CAN_I_GIVE).getUnfoundedFindingSeverities(),
					"a " + rating + " attached to the unrated finding is reported, in any casing, and "
							+ "published in the vocabulary's own spelling");
		}
		LlmInferenceService unknown = serviceOver(chart,
				"Her dexamethasone allergy is a cross-reactivity of unknown severity [" + dexamethasone + "].");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				unknown.search(patient(), CAN_I_GIVE).getUnfoundedFindingSeverities());
	}

	@Test
	public void aRatingWordTheUnratedFindingsOwnRecordStatesIsNotReported() throws java.io.IOException {
		// An operator dataset's note can put a rating word inside the unrated finding's own record. An
		// answer reproducing it has attached nothing the finding lacks, so the exemption asks that record
		// too, and not only the other findings the sentence cites.
		PatientChart chart = DrugReferenceTestSupport.injectedCuratedAllergyFindingChart(
				"chartsearchai-test/drug-reference-unrated-note-states-a-rating.json", "Can I give her carbamazepine?",
				Collections.singletonList("carbamazepine"));
		int unrated = unratedFindingNaming(chart, "Carbamazepine");
		assertTrue(DrugReferenceTestSupport.findingAt(chart, unrated).getText().contains("major hypersensitivity"),
				"precondition: the note's rating word is in the unrated finding's own record: " + chart.getText());
		LlmInferenceService service = serviceOver(chart,
				"Her carbamazepine allergy carries a risk of a major hypersensitivity reaction [" + unrated + "].");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				service.search(patient(), "Can I give her carbamazepine?").getUnfoundedFindingSeverities());
	}

	@Test
	public void anAnswerStatingNoRatingForTheUnratedFindingStatesNone() {
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		LlmInferenceService service = serviceOver(chart,
				"Her dexamethasone allergy is a possible cross-reactivity with her prednisone ["
						+ dexamethasone + "]. No severity is rated for this finding.");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				service.search(patient(), CAN_I_GIVE).getUnfoundedFindingSeverities());
	}

	@Test
	public void oneFindingGivenOneRatingInTwoSentencesIsOneEntry() {
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		LlmInferenceService service = serviceOver(chart,
				"Her dexamethasone allergy is a Major finding [" + dexamethasone + "]. That Major "
						+ "cross-reactivity is with her prednisone [" + dexamethasone + "].");
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, "Major")),
				service.search(patient(), CAN_I_GIVE).getUnfoundedFindingSeverities(),
				"the entry is a (citation, rating) pair, so a client can key on it");
	}

	@Test
	public void theControlCellStatingOnlyRatingsItsFindingsCarryStatesNone() {
		// Decision 78's cell, with every finding's own rating stated — the ticket's control, run as the
		// ticket asks. Said plainly so it does not read as a stronger control than it is: it pins no gate.
		// This arrangement injects no unrated finding, and even with the stamp written TRUE on every
		// finding each one's Major is carried by another cited in the same sentence, so it stays green.
		// The stamp, the unit and the exemption are held by the other cases here.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(SHIPPED,
				chartOf("Methylprednisolone 4mg tablet", "Budesonide 3mg capsule", "Simvastatin 20mg tablet"),
				CLARITHROMYCIN_QUESTION,
				setOf("Methylprednisolone 4mg", "Budesonide 3mg", "Simvastatin 20mg"),
				setOf("H02AB04", "A07EA06", "C10AA01"), null);
		StringBuilder prose = new StringBuilder("No — Clarithromycin should not be started");
		String separator = ": ";
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			assertEquals("Major", finding.getFindingSeverity(),
					"precondition: the control injects Major findings only. Record: " + finding.getText());
			prose.append(separator).append("a Major interaction [").append(finding.getIndex()).append("]");
			separator = ", ";
		}
		LlmInferenceService service = serviceOver(chart, prose.append(".").toString());
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				service.search(patient(), CLARITHROMYCIN_QUESTION).getUnfoundedFindingSeverities());
	}

	@Test
	public void aFindingOnlyTheStructuredArrayNamesIsNotAccused() {
		// The #409 reading: a finding the model listed in its structured citations array and anchored in
		// no sentence was not cited by the answer, so no sentence of it can have attached a rating to it.
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		int hydrocortisone = unratedFindingNaming(chart, "Hydrocortisone");
		LlmInferenceService service = serviceOver(chart,
				"Her dexamethasone allergy is a Major finding. Her hydrocortisone allergy is a possible "
						+ "cross-reactivity [" + hydrocortisone + "].",
				Collections.singletonList(Integer.valueOf(dexamethasone)));
		ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
		assertTrue(ChartAnswerTestSupport.referenceIndexes(answer).contains(Integer.valueOf(dexamethasone)),
				"precondition: the resolution admits the array-only citation, so the check is handed it");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				answer.getUnfoundedFindingSeverities());
	}

	@Test
	public void aBlankAnswerCarryingResolvedCitationsStatesNone() {
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		LlmInferenceService service = serviceOver(chart, "",
				Collections.singletonList(Integer.valueOf(dexamethasone)));
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				service.search(patient(), CAN_I_GIVE).getUnfoundedFindingSeverities(),
				"a degenerate output is not a fidelity defect, and the check did run");
	}

	@Test
	public void aCheckThatThrowsIsReportedAndTheAnswerStillReturns() {
		// getFindingUnrated() is read by nothing on this path before this check, so overriding it
		// throws here and nowhere else.
		PatientChart throwing = new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Safety finding: Prednisone" + System.lineSeparator(),
				Arrays.<RecordMapping> asList(new RecordMapping(1,
						ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING, "finding-uuid-throwing", null,
						"Safety finding: Prednisone") {

					@Override
					public Boolean getFindingUnrated() {
						throw new IllegalStateException("finding stamp unavailable");
					}
				}),
				Collections.<Integer> emptyList());
		LlmInferenceService service = serviceOver(throwing, "A Major finding [1].");
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), CAN_I_GIVE);
			assertTrue(answer.getAnswer().contains("[1]"),
					"the answer must come back whatever the check does; got: " + answer.getAnswer());
			assertTrue(capture.hasMessageAt(Level.WARN, "Unfounded-rating check failed"),
					"and the check's own failure must not be silent. Captured: " + capture.describeAll());
			assertEquals(null, answer.getUnfoundedFindingSeverities(),
					"a failed check states NO measurement, which is not a measurement of none");
		}
	}

	@Test
	public void searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		// /search/stream is the path users hit. The early `done` is built before this check runs, so it
		// states no measurement; the log snapshot at handoff is what makes moving the check above the
		// handoff redden this case, the early answer being handed an explicit null either way.
		PatientChart chart = sarahTaylor(CAN_I_GIVE, false);
		int dexamethasone = unratedFindingNaming(chart, "Dexamethasone");
		LlmInferenceService service = serviceOver(chart,
				"Her dexamethasone allergy makes this a Major finding [" + dexamethasone + "].");
		final List<List<UnfoundedFindingSeverity>> early = new ArrayList<List<UnfoundedFindingSeverity>>();
		final List<List<String>> loggedByHandoff = new ArrayList<List<String>>();
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), CAN_I_GIVE, token -> { },
					reasoning -> { }, citations -> { },
					ungrounded -> {
						early.add(ungrounded.getUnfoundedFindingSeverities());
						loggedByHandoff.add(capture.describeAll());
					});
			assertEquals(1, early.size(), "the early-done consumer must have fired");
			assertEquals(null, early.get(0), "the early answer states no measurement");
			assertTrue(capture.describeAll().toString().contains("attaches a rating"),
					"the capture must have received the check's own WARN by the end. Captured: "
							+ capture.describeAll());
			assertFalse(loggedByHandoff.get(0).toString().contains("attaches a rating"),
					"and the check must not have run by the handoff. Captured at handoff: "
							+ loggedByHandoff.get(0));
			assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(dexamethasone, "Major")),
					answer.getUnfoundedFindingSeverities(), "and the answer this method RETURNS carries it");
		}
	}

	/**
	 * Sarah Taylor's chart as the ticket describes it, put through the real injector over the shipped
	 * knowledge base: her prednisone order and her two corticosteroid allergies — and, where
	 * {@code onClarithromycin}, a clarithromycin order too, which adds a Moderate interaction finding
	 * about her prednisone beside the unrated ones.
	 */
	private static PatientChart sarahTaylor(String question, boolean onClarithromycin) {
		PatientChart chart = onClarithromycin
				? DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(SHIPPED,
						chartOf("Prednisone Co 5mg tablet", "Clarithromycin 500mg tablet"), question,
						setOf("Prednisone Co 5mg", "Clarithromycin 500mg"), setOf("H02AB07", "J01FA09"),
						setOf("dexamethasone", "hydrocortisone"))
				: DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(SHIPPED,
						chartOf("Prednisone Co 5mg tablet"), question, setOf("Prednisone Co 5mg"),
						setOf("H02AB07"), setOf("dexamethasone", "hydrocortisone"));
		return chart;
	}

	/** The injected finding carrying no rating whose record names {@code allergen}, located by what the
	 *  record SAYS rather than by the stamp under test. */
	private static int unratedFindingNaming(PatientChart chart, String allergen) {
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getFindingSeverity() == null && finding.getText().contains(allergen)
					&& finding.getText().contains("No severity is rated for this finding.")) {
				return finding.getIndex();
			}
		}
		throw new IllegalStateException("no unrated finding naming " + allergen + ": " + chart.getText());
	}

	/** The injected finding whose record states {@code needle}, the first in chart order. */
	static int findingWhoseText(PatientChart chart, String needle) {
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains(needle)) {
				return finding.getIndex();
			}
		}
		throw new IllegalStateException("no injected finding stating '" + needle + "': " + chart.getText());
	}

	private static int ratedFinding(PatientChart chart, String rating) {
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (rating.equals(finding.getFindingSeverity())) {
				return finding.getIndex();
			}
		}
		throw new IllegalStateException("no finding rated " + rating + ": " + chart.getText());
	}

	/** The patient's own drug orders as chart records, rendered by the REAL serializer. */
	static PatientChart chartOf(String... orders) {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		int n = 1;
		for (String order : orders) {
			records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
					"order-uuid-" + n++, order + ", 1 daily", null));
		}
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	static LlmInferenceService serviceOver(PatientChart chart, String answer) {
		return serviceOver(chart, answer, Collections.<Integer> emptyList());
	}

	/** The same seam the sibling fidelity tests use, private for the reason theirs are. */
	private static LlmInferenceService serviceOver(PatientChart served, String answer,
			List<Integer> citations) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question,
					ChartReadStatus readStatus) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production calls — see SafetyFindingSeverityFidelityTest for why this one.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		created.setLlmProvider(new StubProvider(answer, citations));
		return created;
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
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

	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private final List<Integer> citations;

		private StubProvider(String answer, List<Integer> citations) {
			this.answer = answer;
			this.citations = citations;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return new LlmResponse(answer, citations);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return new LlmResponse(answer, citations);
		}
	}
}
