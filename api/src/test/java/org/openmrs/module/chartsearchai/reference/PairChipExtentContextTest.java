/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A bounded pairwise interaction list says it is bounded (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/336">#336</a>).
 *
 * <p>Before this, a screen that hit {@code chartsearchai.drugSafety.maxPairChips} truncated its
 * result and stated the fact only to a server-side WARN. Measured on the 3.7.1 standalone against
 * the bundled DDInter knowledge base: a patient on eight anti-inflammatories screened 18 above-floor
 * pairs, reported 10, and every field of the {@code /search} response — the answer, the chips, each
 * reference's {@code withheldInteractions} — was indistinguishable from a complete screen's.
 *
 * <p>Deliberately the same two arrangements {@code PairChipCapContextTest} measures the cap with,
 * so the count a pass STATES and the count it SHOWS cannot drift apart in the suite: the screening
 * arm over six active orders interacting 15 ways, and the 16-drug polypharmacy question with 72
 * above-floor pairs. Context-sensitive for the same reason that class is — the cases write the real
 * global property and read it back through the real {@code validate}.
 *
 * <p><b>What this class pins and what it does not.</b> It pins the counts, through the real
 * {@code validate} over the real DDInter excerpt. It does not pin their transport to the wire; that
 * is {@code LlmInferenceServicePairChipExtentTest} (answer) and
 * {@code ChartSearchAiInteractionPairExtentTest} (response). And {@code getReported()} is asserted
 * against the chips raised only in arrangements whose whole warning list is the arm's own — stated
 * as a precondition in each case, because the drug-in-play arm raises interaction chips for drugs
 * only the ANSWER named and unrated class chips the extent counts nowhere, and those populations
 * are not this one.
 *
 * <p><b>The drug-in-play arm's own cases are at the bottom</b> (issue #356), kept in this class
 * rather than a new one for the reason the paragraph above gives about the two pairwise arms: what
 * one pass STATES about its screen is one question, and three arms answering it in three classes is
 * three chances to answer it differently.
 */
public class PairChipExtentContextTest extends BaseModuleContextSensitiveTest {

	/** The 16-drug polypharmacy question — the shared constant, not a copy of it, so the 72 this
	 *  class states and the 72 {@code PairChipCapContextTest} shows are one measurement. */
	private static final String POLYPHARMACY_QUESTION = DrugReferenceTestSupport.POLYPHARMACY_QUESTION;

	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** Above-floor pairs among the excerpt's 16 question-named drugs. */
	private static final int QUESTION_PAIRS = 72;

	/** Above-floor pairs among the six active orders the screening arrangement carries. */
	private static final int SCREENED_PAIRS = 15;

	private DrugSafetyValidator validator;

	/** The same service object the validator resolves through, so a case asserting what a question
	 *  RESOLVES and a case asserting what the pass then STATES cannot be reading two datasets. */
	private DrugReferenceService service;

	@BeforeEach
	public void setUp() {
		service = DrugReferenceTestSupport.ddinterService();
		validator = DrugReferenceTestSupport.validator(service);
	}

	/** @return how many reference entries {@code question} puts in play — the number the two
	 *          question-driven gates gate on, asserted where a case's whole point is which arm ran. */
	private int entriesResolvedBy(String question) {
		return service.findImpliedByQuery(question).size();
	}

	private void configureCap(String value) {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS, value);
	}

	/** One pass, holding both what the pass reported and what it said about how much it withheld. */
	private static final class Pass {

		private final List<SafetyWarning> chips;

		private final PairChipExtent extent;

		Pass(List<SafetyWarning> chips, PairChipExtent extent) {
			this.chips = chips;
			this.extent = extent;
		}
	}

	/** The question-pair arm on a patient taking nothing, so only that arm can chip. */
	private Pass questionPairPass() {
		return pass(POLYPHARMACY_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));
	}

	/** The screening arm over the shared six-order chart — the same OBJECT the cap test and the
	 *  screening test build, not a copy of it, which is what makes "the three cannot drift apart" a
	 *  fact rather than an intention. */
	private Pass screeningPass() {
		return pass(SCREENING_QUESTION, DrugReferenceTestSupport.screenedSixOrderChart());
	}

	/** Issue #336's own third row in miniature: a chart carrying the ibuprofen the question also
	 *  names, plus one further order the excerpt relates BOTH question drugs to, so the pairs the
	 *  chart arm reports OUTNUMBER the pairs the question-pair arm has to say anything about. That
	 *  inequality is the whole point and both cede cases below need it: where the two counts
	 *  coincide, no assertion can tell the arm DECLINING to speak from the arm counting a ceded pair
	 *  into its own numbers — the cross-arm sum ADR Decision 65 refuses — and, on the partial case,
	 *  none can tell the arm KEEPING the field from the arm yielding it. Measured: on a chart holding
	 *  the ibuprofen alone the partial case reads {@code found: 1} either way. Each order carries its
	 *  name AND its code because that is what {@code PatientClinicalContextBuilder} builds for a
	 *  mapped concept; no case here separates the two legs of {@code hasActiveDrug}, so read this as
	 *  the arrangement's shape rather than as a claim that either leg alone would cede. */
	private static PatientClinicalContext chartOwningTheQuestionsOnlyPair() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Ibuprofen 400mg", "Aspirin 81mg"),
				DrugReferenceTestSupport.set("M01AE01", "N02BA01"), null, null);
	}

	/** One active order, related to nothing the questions below name above the floor. Called by the
	 *  SCREENING case that relates no pair and by the drug-in-play cases at the bottom, so "the two
	 *  arms are measured on one patient" is a fact about this method rather than about two copies of
	 *  a chart that can be edited apart. */
	private static PatientClinicalContext oneSimvastatinOrder() {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Simvastatin"),
				DrugReferenceTestSupport.set("C10AA01"), null, null);
	}

	/** Runs the real validate over an explicit clinical context, which is the arity every other
	 *  chip test in this package drives — NOT the arity production publishes through. That link, from
	 *  the public {@code Patient} entry point down to the arm, is its own case below; without it a
	 *  mutation of the delegation leaves this whole class green (measured). The empty answer is the
	 *  pre-answer production shape ({@code DrugReferenceInjector.preAnswerFindings}). */
	private Pass pass(String question, PatientClinicalContext context) {
		return passWithAnswer("", question, context);
	}

	/** As {@link #pass(String, PatientClinicalContext)}, over an explicit ANSWER — the post-answer
	 *  production shape, and the only one that can put a drug in play the question did not name
	 *  ({@code inPlay} is the question's drugs UNION the answer's, echo-scoped). Every case above
	 *  drives the empty answer, so none of them can express an answer-side drug at all. Named apart
	 *  from {@link #pass(String, PatientClinicalContext)} rather than overloading it: both take a
	 *  leading {@code String} and a reader cannot see from a call site which of the two it is. */
	private Pass passWithAnswer(String answer, String question, PatientClinicalContext context) {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		List<SafetyWarning> chips = validator.validate(answer, question, context, null, null, sink);
		return new Pass(chips, sink.stated());
	}

	@Test
	public void aCappedScreenStatesHowManyPairsItFoundBesideHowManyItReported() {
		// The ticket's own shape: reported < found, and the difference is recoverable from the
		// response rather than from a log line nobody reading the answer can see.
		configureCap("3");
		Pass capped = screeningPass();

		assertNotNull(capped.extent, "a screen that ran must state its extent");
		assertEquals(SCREENED_PAIRS, capped.extent.getFound(),
				"the candidate count must be what the arm enumerated before the cut");
		assertEquals(3, capped.extent.getReported(), "and the reported count the cap that cut it");
		assertEquals(12, capped.extent.getFound() - capped.extent.getReported(),
				"so twelve pairs are recoverable from the response that were not before");
		// Precondition, asserted rather than assumed: this arrangement raises nothing but the screen's
		// own chips, so the statement can be compared against the whole list.
		assertEquals(capped.chips.size(), capped.extent.getReported(),
				"the pass must report exactly what it says it reported: "
						+ DrugReferenceTestSupport.details(capped.chips));
	}

	@Test
	public void anUncappedScreenStatesThatItReportedEveryPairItFound() {
		// The other half, and the half a truncation-only signal would leave unsaid: a complete screen
		// must be able to SAY it is complete, or a client can only ever infer completeness from the
		// absence of a marker.
		configureCap("1000");
		Pass full = screeningPass();

		assertNotNull(full.extent);
		assertEquals(SCREENED_PAIRS, full.extent.getFound());
		assertEquals(SCREENED_PAIRS, full.extent.getReported(),
				"an uncut list must state that it reported everything it found");
	}

	@Test
	public void aCappedQuestionPairListStatesItsOwnExtentToo() {
		// Both arms, one statement. Their gates are mutually exclusive, so a question can never be
		// subject to both — and an extent published for only one of them would be silently absent on
		// exactly the question shape the other answers.
		configureCap("3");
		Pass capped = questionPairPass();

		assertNotNull(capped.extent, "the question-pair arm must state its extent as well");
		assertEquals(QUESTION_PAIRS, capped.extent.getFound());
		assertEquals(3, capped.extent.getReported());
		assertEquals(capped.chips.size(), capped.extent.getReported());
	}

	@Test
	public void theStatedReportedCountTracksTheChipsAtEveryCap() {
		// Stronger than any single cap: whatever the operator sets, what the response SAYS it reported
		// is what it reported. A statement derived from the GP rather than from the cut would pass at
		// the default and diverge wherever the candidate list is SHORTER than the cap — publishing a
		// reported count above the found one, which getReported()'s own javadoc says cannot happen.
		//
		// Asked of BOTH arms, because the class javadoc's "both arms, one statement" is otherwise a
		// claim about one: measured, the GP-instead-of-the-cut mutation on the question-pair arm alone
		// survived the entire api suite while the same mutation on the screening arm reddened two
		// cases — every question-arm case here ran with the cap BELOW the candidate count, where
		// min(found, cap) and cap are the same number.
		for (int cap : new int[] { 1, 3, 10, 15, 1000 }) {
			configureCap(String.valueOf(cap));
			assertCutIsWhatIsStated("screening", screeningPass(), SCREENED_PAIRS, cap);
			assertCutIsWhatIsStated("question-pair", questionPairPass(), QUESTION_PAIRS, cap);
		}
	}

	/** One pass's statement against the cut that actually happened, and against the chips it raised. */
	private static void assertCutIsWhatIsStated(String arm, Pass pass, int candidates, int cap) {
		assertEquals(candidates, pass.extent.getFound(),
				"the " + arm + " arm at cap " + cap + ": the candidate count is a property of the data, "
						+ "not of the cap");
		assertEquals(Math.min(candidates, cap), pass.extent.getReported(),
				"the " + arm + " arm at cap " + cap + ": the stated reported count must be the cut that "
						+ "happened, never the configured cap");
		assertEquals(pass.chips.size(), pass.extent.getReported(),
				"the " + arm + " arm at cap " + cap + ": the statement must match the chips raised");
	}

	@Test
	public void aScreenThatRelatesNoPairStatesZeroRatherThanNothing() {
		// Zero is a measurement: this screen ran over the patient's orders and the reference data
		// related none of the pairs it enumerated. A caller told nothing at all could not tell that
		// COMPLETE result from a question that never asked to be screened.
		Pass screened = pass(SCREENING_QUESTION, oneSimvastatinOrder());

		assertTrue(screened.chips.isEmpty(),
				"precondition: one order relates to nothing, so no pair chip is raised");
		assertNotNull(screened.extent, "but the screen ran, so it must still state its extent");
		assertEquals(0, screened.extent.getFound());
		assertEquals(0, screened.extent.getReported());
	}

	@Test
	public void aQuestionPairListThatRelatesNoPairStatesZeroRatherThanNothing() {
		// The other arm's half of "zero is a measurement". Its own branch is a separate return, and
		// mutating it to null left the ENTIRE suite green before this case existed — so the contract
		// held on one arm and was free to be reverted on the other. Two named drugs the excerpt
		// carries, with the floor raised above the only rating that relates them.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");
		Pass none = pass("Do simvastatin and warfarin interact?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));

		assertTrue(none.chips.isEmpty(),
				"precondition: the excerpt rates this pair Minor, so a Major floor leaves no candidate: "
						+ DrugReferenceTestSupport.details(none.chips));
		assertNotNull(none.extent, "but the arm ran over two named drugs, so it must state its extent");
		assertEquals(0, none.extent.getFound());
		assertEquals(0, none.extent.getReported());
	}

	@Test
	public void thePublicEntryPointHandsItsCallersSinkToThePassThatFillsIt() {
		// The one link the cases above cannot see: they drive the clinical-context arity, while
		// production goes through validate(answer, question, Patient, mappings, sink). Mutating that
		// delegation to pass null instead left api and omod entirely green (measured), so the feature
		// was joined end to end nowhere — the silent, one-directional shape issue #151 records.
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		final List<PairChipExtent.Sink> handedDown = new ArrayList<PairChipExtent.Sink>();
		DrugSafetyValidator spy = new DrugSafetyValidator() {

			@Override
			List<SafetyWarning> validate(String answer, String question, PatientClinicalContext rawContext,
					List<RecordMapping> mappings, List<DrugReference> resolvedOrderEntries,
					PairChipExtent.Sink pairExtentSink) {
				handedDown.add(pairExtentSink);
				return super.validate(answer, question, rawContext, mappings, resolvedOrderEntries,
						pairExtentSink);
			}
		};
		spy.setDrugReferenceService(DrugReferenceTestSupport.ddinterService());
		PairChipExtent.Sink sink = new PairChipExtent.Sink();

		spy.validate("", POLYPHARMACY_QUESTION, Context.getPatientService().getPatient(7), null, sink);

		assertEquals(1, handedDown.size(), "the public entry point must reach the pass exactly once");
		assertSame(sink, handedDown.get(0),
				"and must hand it the CALLER'S sink: a pass given a sink of its own, or none, fills "
						+ "nothing the caller can read, and no chip or count assertion can see that");
	}

	@Test
	public void aPassThatThrewStatesNothingRatherThanACompleteScreen() {
		// The one leg of the null contract a code change can break, and it was unpinned: the others
		// return before an arm can run, but the fail-safe catches a RuntimeException and answers with
		// an EMPTY warning list, so a sink written there would publish {found: 0, reported: 0} — which
		// README tells a client to read as a screen that ran and related nothing, i.e. COMPLETE.
		// Measured before this case existed: recording (0, 0) inside that catch left api and omod
		// entirely green. Rendering silence as a denial, on a safety surface.
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		DrugSafetyValidator throwing = DrugReferenceTestSupport.validator(new DrugReferenceService() {

			// The arity validate actually calls — the one-argument sibling is not on its path, and a
			// stub on that one throws nothing, leaving this case green against a screen that really
			// did run and honestly state (0, 0). Ask what the fixture can EXPRESS, not only what it
			// asserts.
			@Override
			public PatientClinicalContext withReferenceNames(PatientClinicalContext context,
					List<DrugReference> orderEntries) {
				throw new IllegalStateException("the reference dataset is unreadable");
			}
		});
		PairChipExtent.Sink sink = new PairChipExtent.Sink();

		List<SafetyWarning> chips = throwing.validate("", SCREENING_QUESTION,
				Context.getPatientService().getPatient(7), null, sink);

		assertTrue(chips.isEmpty(), "precondition: the fail-safe answers a throw with no warnings");
		assertNull(sink.stated(),
				"and it must state NOTHING about a screen it could not run — a zero here is a positive "
						+ "claim that the pairwise check ran and related no pairs");
	}

	@Test
	public void aQuestionThatRunsNeitherPairwiseArmStatesNothing() {
		// Absence means the producer measured nothing, never that the screen was complete. This
		// question resolves too few reference entries for the question-pair arm and does not ask to be
		// screened, so neither pairwise arm enumerates a candidate list to state the extent of.
		// Since issue #356 the drug-in-play arm can state one on this question shape, and what keeps
		// this arrangement silent is the chart: it records no medication, so there was nothing to
		// screen warfarin against. See aDrugInPlayArmWithNoMedicationToScreenAgainstStatesNothing,
		// which asks the same of a prescribing question.
		Pass none = pass("What is warfarin used for?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));

		assertNull(none.extent,
				"no arm screened anything, so nothing may be stated on the answer's behalf");
	}

	// --- The drug-in-play arm's own screen (issue #356) ---

	@Test
	public void aDrugInPlayScreenThatRelatesNoActiveOrderStatesZeroRatherThanNothing() {
		// The ticket's own row. "Can I give this patient X?" resolves ONE reference entry here, so
		// neither pairwise arm runs — the question-pair arm needs two and the screen needs none — and
		// until issue #356 the arm that DID screen X against her medications said so nowhere, so a
		// complete negative screen and a question nobody screened at all were one value on the wire.
		// "Here" is load-bearing: a name resolving to several entries opens the question-pair arm
		// instead, which is why the metformin and clarithromycin of these cases were each checked
		// against findImpliedByQuery over the excerpt rather than assumed to be one drug apiece.
		Pass none = pass("Can I give this patient metformin?", oneSimvastatinOrder());

		// Asserted, not assumed: on TWO entries the question-pair arm runs instead and returns
		// of(0, 0) for a pair it does not relate, so every assertion below would go on holding while
		// the arm this case exists for never ran. Its sibling below is immune because it asserts a
		// found of 1, which that arm could not produce there.
		assertEquals(1, entriesResolvedBy("Can I give this patient metformin?"),
				"precondition: one entry, so neither pairwise arm is even reachable");
		assertTrue(none.chips.isEmpty(),
				"precondition: the excerpt rates Simvastatin x Metformin Unknown, below the default "
						+ "minor floor, and C10AA/A10BA share no subgroup, so nothing is raised: "
						+ DrugReferenceTestSupport.details(none.chips));
		assertNotNull(none.extent, "but the screen ran, so it must state that it related nothing");
		assertEquals(0, none.extent.getFound());
		assertEquals(0, none.extent.getReported());
	}

	@Test
	public void aDrugInPlayScreenThatRelatesAnActiveOrderStatesWhatItRelated() {
		// The other half: zero has to be a value in a range, not the only thing this arm can say. The
		// excerpt rates Clarithromycin x Simvastatin Major.
		Pass related = pass("Can I give this patient clarithromycin?", oneSimvastatinOrder());

		assertEquals(1, related.chips.size(), "precondition: one order, one rated relationship: "
				+ DrugReferenceTestSupport.details(related.chips));
		assertNotNull(related.extent, "a screen that related a pair must state it too");
		assertEquals(1, related.extent.getFound());
		assertEquals(1, related.extent.getReported(),
				"this arm applies no cap, so it reports everything it relates");
	}

	@Test
	public void aClassOnlyRelationshipIsNotCountedAsAPairFound() {
		// What `found` counts has to be the population the other two arms count, or one wire key means
		// two things by question shape. Neither pairwise arm has a class leg at all — the screen's own
		// comment says so, and the question-pair arm has no such branch — so an unrated
		// shared-subgroup relationship is a chip and not a pair found. An order the excerpt cannot
		// name, carrying a C10AA code Simvastatin's own subgroup covers.
		Pass classOnly = pass("Can I give this patient simvastatin?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Atorvastatin 20mg"),
						DrugReferenceTestSupport.set("C10AA05"), null, null,
						Arrays.asList(DrugReferenceTestSupport.activeOrder("order-atorvastatin",
								"Atorvastatin 20mg", DrugReferenceTestSupport.set("atorvastatin"),
								DrugReferenceTestSupport.set("C10AA05")))));

		// What this arrangement cannot express, stated rather than left to be assumed: the excerpt
		// carries no Atorvastatin entry at all, so bestRulePerPartner has no rule to return and no
		// rule chip is reachable here. It separates "count the class chips too" from correct; that
		// rule chips ARE counted is pinned by aDrugInPlayScreenThatRelatesAnActiveOrderStatesWhatItRelated.
		assertEquals(1, classOnly.chips.size(), "precondition: the shared C10AA subgroup raises a chip: "
				+ DrugReferenceTestSupport.details(classOnly.chips));
		assertNull(classOnly.chips.get(0).getSeverity(),
				"precondition: and it is the class arm's unrated sentence, not a rule");
		assertNotNull(classOnly.extent, "the screen still ran, so it still states its extent");
		assertEquals(0, classOnly.extent.getFound(),
				"an unrated class relationship is not a pair the reference data RATED, and counting it "
						+ "would make this key mean one thing on a one-drug question and another on the "
						+ "two arms that have no class leg");
		assertEquals(0, classOnly.extent.getReported());
	}

	@Test
	public void aDrugInPlayArmWithNoMedicationToScreenAgainstStatesNothing() {
		// A prescribing question on a patient taking nothing. There is no population to screen and no
		// request to screen one, so nothing is stated — absence, which README tells a client not to
		// read as completeness. Unlike the case above it, this asks it of the question shape the
		// ticket is about, so a gate that read the question's INTENT instead would part them.
		Pass none = pass("Can I give this patient clarithromycin?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));

		assertTrue(none.chips.isEmpty(), "precondition: nothing to relate her to: "
				+ DrugReferenceTestSupport.details(none.chips));
		assertNull(none.extent, "a chart recording no medication was not screened, and a zero here "
				+ "would say it was");
	}

	@Test
	public void aDrugOnlyTheAnswerNamedStatesNothingOnItsOwn() {
		// The ticket's last row, and the one it says is the one to get right: found: 0 must mean
		// "screened and related nothing", never "could not resolve the drug". This question resolves
		// no reference drug and carries no interaction cue, so no arm is anchored on anything the
		// clinician asked about — but the ANSWER names one, which reaches the drug-in-play arm. The
		// statement is the question's, so there is none.
		Pass none = passWithAnswer("Clarithromycin would be a reasonable choice.",
				"What should I watch out for?", oneSimvastatinOrder());

		assertEquals(1, none.chips.size(),
				"precondition: the arm ran on the answer's own drug and related the order, so this case "
						+ "is not vacuous: " + DrugReferenceTestSupport.details(none.chips));
		assertNull(none.extent, "the question resolved no drug, so nothing was screened on its behalf "
				+ "and a zero would report the model's prose as the clinician's question");
	}

	@Test
	public void aDrugTheAnswerAddsIsNotCountedIntoTheQuestionsOwnScreen() {
		// The gate above is satisfied by an empty questionDrugs; this is the COUNT, which the gate
		// cannot speak for. One question drug that relates nothing and one answer drug that relates
		// the order: accumulated over inPlay this reads {1, 1} and describes a screen of a drug
		// nobody asked about.
		Pass mixed = passWithAnswer("Clarithromycin would be a reasonable choice.",
				"Can I give this patient metformin?", oneSimvastatinOrder());

		assertEquals(1, mixed.chips.size(),
				"precondition: the answer's drug relates the order and the question's does not: "
						+ DrugReferenceTestSupport.details(mixed.chips));
		assertNotNull(mixed.extent, "the question's own drug was screened, so an extent is stated");
		assertEquals(0, mixed.extent.getFound(),
				"and it counts that screen, not the one the answer's drug brought with it");
		assertEquals(0, mixed.extent.getReported());
	}

	@Test
	public void aPairwiseArmsStatementIsNotDisplacedByTheDrugInPlayScreen() {
		// Two question drugs, so the question-pair arm runs — and the drug-in-play arm runs beside it,
		// relating each of the two to the order. The pairwise statement is about a BOUNDED list and
		// this one is not, so summing them into two integers publishes a ratio over two populations.
		// The pairwise arm keeps the field.
		Pass both = pass("Do clarithromycin and warfarin interact?", oneSimvastatinOrder());

		assertTrue(both.chips.size() > 1,
				"precondition: the drug-in-play arm relates the order too, so a sum would be visible: "
						+ DrugReferenceTestSupport.details(both.chips));
		assertNotNull(both.extent);
		assertEquals(1, both.extent.getFound(),
				"the one question-named pair the excerpt relates, and nothing the other arm added");
		assertEquals(1, both.extent.getReported());
	}

	@Test
	public void aQuestionPairListThatCededEveryPairStatesWhatTheChartArmReported() {
		// Issue #336's own third row, the one its verification comment calls the one that bites — the
		// live measurement of what its zero cost a reader is in PairChipExtent's javadoc and in ADR
		// Decision 69, with the build and the chart it was taken on. This is that shape in miniature.
		// Every pair the question named was the CHART arm's — coveredByActiveOrderArm,
		// because a rule joining the pair names one of her orders — so this arm related a pair and
		// reported none. A zero says an arm ran and the reference data related NONE of the pairs it
		// enumerated, which is what PairChipExtent and README both define found == 0 to mean, and it
		// is false here. Having ceded every one of them the arm has no bounded list of its own to
		// describe, so it states nothing and the arm that DID report them speaks instead.
		String twoDrugs = "Can I give her warfarin and ibuprofen?";
		Pass ceded = pass(twoDrugs, chartOwningTheQuestionsOnlyPair());

		assertEquals(2, entriesResolvedBy(twoDrugs),
				"precondition: two resolved entries, or the question-pair arm never ran and this case "
						+ "measures the fallback's ordinary path instead of a cede");
		assertEquals(3, ceded.chips.size(), "precondition: three Major chips, every one of them the "
				+ "chart arm's: " + DrugReferenceTestSupport.details(ceded.chips));
		for (SafetyWarning chip : ceded.chips) {
			assertFalse(chip.getDetail().contains("named in the question"),
					"precondition: the question-pair arm must have chipped NOTHING, or the pair it ceded "
							+ "was not its only one: " + chip);
		}
		assertNotNull(ceded.extent, "a response carrying three Major interaction chips must not state "
				+ "a screen that related nothing");
		assertEquals(3, ceded.extent.getFound(),
				"and the number is the chart arm's own three, not the one pair the question-pair arm "
						+ "ceded to it — a sum of the two arms, or that arm counting what it ceded, "
						+ "would read 1 or 4 here");
		assertEquals(3, ceded.extent.getReported(),
				"reported by the arm that applies no cap, so it equals what it found");
	}

	@Test
	public void aQuestionPairListThatCededOnlySomeOfItsPairsStillStatesItsOwnBoundedList() {
		// The boundary of aQuestionPairListThatCededEveryPairStatesWhatTheChartArmReported, and the
		// residue it deliberately leaves. A third question drug on the same chart: warfarin x
		// ibuprofen is the chart arm's, simvastatin x warfarin is this arm's, and this arm goes on
		// describing the bounded list it kept — which the cap can still cut, see ADR Decision 69 for
		// what that does and does not let a client conclude. The ceded pair is reported as a chip rather
		// than hidden, which is why the fix above is scoped to a pass that ceded EVERY pair. Withhold
		// the statement on ANY cede instead and this reads the chart arm's three, having forfeited the
		// bounded claim issue #336 exists to publish for a number no cap cut.
		String threeDrugs = "Interactions for warfarin, ibuprofen and simvastatin?";
		Pass partial = pass(threeDrugs, chartOwningTheQuestionsOnlyPair());

		assertEquals(3, entriesResolvedBy(threeDrugs), "precondition: three resolved entries");
		assertEquals(4, partial.chips.size(),
				"precondition: the chart arm's three and this arm's one, so the cede is partial rather "
						+ "than total: " + DrugReferenceTestSupport.details(partial.chips));
		// Not load-bearing on its own: withheld on any cede, the fallback supplies one and this stays
		// green. The count below is what discriminates.
		assertNotNull(partial.extent, "some arm must state one");
		assertEquals(1, partial.extent.getFound(),
				"the pair this arm kept, and not the one it handed to the chart arm — nor the chart "
						+ "arm's own three, which is what this reads if the field is yielded on any cede");
		assertEquals(1, partial.extent.getReported());
	}

	@Test
	public void aScreenThatCededEveryPairItRelatedStatesNothingRatherThanACompleteScreenOfNone() {
		// Issue #370: the same defect as
		// aQuestionPairListThatCededEveryPairStatesWhatTheChartArmReported, on the sibling arm, and
		// ADR Decision 69 recorded it as reproduced and left standing for want of a value; ADR
		// Decision 70 is where the value was chosen, and this case is what pins it.
		// The screening arm cedes a pair the drug-in-play arm has already chipped
		// (reportedPairs.alreadyReported), and where that took every pair it related it kept nothing --
		// so of(0, 0) said the reference data related NONE of the pairs it enumerated, which is what
		// PairChipExtent and README both define found == 0 to mean, beside a Major chip about a pair it
		// had related. Having ceded every one it has no bounded list of its own to describe, so it
		// states nothing. Nothing rescues that into a number here and it must not: validate's issue
		// #356 fallback is gated on questionDrugScreened, which is false whenever this arm ran at all
		// (its own gate is questionDrugs.isEmpty()), and the count no fallback can supply would be the
		// ANSWER's -- the answer-dependence ADR Decision 65 refuses for this field's value.
		Pass ceded = passWithAnswer("Ibuprofen is on the list.", SCREENING_QUESTION,
				chartOwningTheQuestionsOnlyPair());

		assertEquals(1, ceded.chips.size(),
				"precondition: one chip, the drug-in-play arm's, because the screen ceded its only pair "
						+ "to it -- two would mean the cede did not happen and this case measures the "
						+ "ordinary screen instead: " + DrugReferenceTestSupport.details(ceded.chips));
		assertEquals("Ibuprofen", ceded.chips.get(0).getDrug(),
				"precondition: and the chip is subjected on the drug the ANSWER named, which is this "
						+ "arrangement's own tell for which arm raised it -- the screen states the same "
						+ "pair the other way round, as the control below shows. The two arms word this "
						+ "chip identically once the subject is fixed, which is why alreadyReported keys "
						+ "on the pair and not on the text");
		// The control that makes this arrangement's cede a measurement rather than an assumption: the
		// SAME chart, with the answer that names nothing, and the screen states its own pair. So the
		// pair is related on this chart and only the answer moved which arm reported it.
		Pass uncoded = pass(SCREENING_QUESTION, chartOwningTheQuestionsOnlyPair());
		assertNotNull(uncoded.extent, "precondition: the screen states its own extent on this chart");
		assertEquals(1, uncoded.extent.getFound(),
				"precondition: and the pair it relates is the one the case above cedes, so the cede is "
						+ "what empties the list rather than the data relating nothing");

		assertNull(ceded.extent, "a response carrying a Major interaction chip must not state a screen "
				+ "that related none of the pairs it enumerated; was: " + ceded.extent);
	}

	@Test
	public void aScreenThatCededOnlySomeOfItsPairsStillStatesItsOwnBoundedList() {
		// The boundary of the case above, and the screening arm's half of
		// aQuestionPairListThatCededOnlySomeOfItsPairsStillStatesItsOwnBoundedList. Six orders the
		// excerpt relates 15 ways; an answer naming one of them puts that drug in play, so the
		// drug-in-play arm chips its five pairs and the screen cedes exactly those -- keeping ten of
		// its own, which it goes on describing. Withhold the statement on ANY cede instead and this
		// reads null, forfeiting the bounded claim issue #336 exists to publish for a list no cede
		// emptied. The cap is raised above both counts so that `reported` is this arm's own identity
		// rather than a cut: at the shipped cap of 10 the kept list is exactly 10 and the two readings
		// coincide.
		configureCap("20");
		Pass partial = passWithAnswer("Warfarin is on the list.", SCREENING_QUESTION,
				DrugReferenceTestSupport.screenedSixOrderChart());

		assertEquals(SCREENED_PAIRS, partial.chips.size(),
				"precondition: the screen's ten and the drug-in-play arm's five, so the cede is partial "
						+ "rather than total: " + DrugReferenceTestSupport.details(partial.chips));
		assertNotNull(partial.extent, "the arm kept a list, so it describes it -- and unlike the "
				+ "question-pair arm's partial case this is discriminating on its own, no fallback "
				+ "being reachable behind this arm");
		assertEquals(10, partial.extent.getFound(),
				"the ten pairs this arm kept -- not the fifteen it related, and not the five it handed "
						+ "to the drug-in-play arm");
		assertEquals(10, partial.extent.getReported(),
				"reported at a cap that cut nothing, so it equals what the arm kept");
	}

	/** A question about dexamethasone and voxelotor over {@link DrugReferenceTestSupport#DDI_ROUTE_VARIANTS},
	 *  whose four dexamethasone rows share one {@code drugbank_id} and so are one substance — which is
	 *  what puts all four in play from the one question word and makes one clinical pair arrive as four
	 *  entry pairs. */
	private static final String ROUTE_VARIANT_QUESTION = "Does dexamethasone interact with voxelotor?";

	/**
	 * As {@link #pass(String, PatientClinicalContext)}, over
	 * {@link DrugReferenceTestSupport#DDI_ROUTE_VARIANTS} instead of the excerpt. Reassigns BOTH
	 * fields, so {@link #entriesResolvedBy} keeps reading the dataset the pass ran over — a case
	 * asserting what a question resolves and a case asserting what the pass then states must not be
	 * reading two datasets, which is what this class's {@code service} field exists for.
	 */
	private Pass passOverRouteVariants(String question, PatientClinicalContext context)
			throws IOException {
		service = DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		validator = DrugReferenceTestSupport.validator(service);
		return pass(question, context);
	}

	@Test
	public void aClinicalPairOneEntryPairCollectedAndAnotherCededStatesWhatTheChartArmReported()
			throws IOException {
		// The cede the two cases above cannot express, and the reason addQuestionPairInteractions
		// collects candidates and filters them against chartOwned rather than deciding the cede on the
		// candidate map. Both of those arrangements cede a pair that never became a candidate at all,
		// so `candidates` is empty in each and narrowing the guard to "chartOwned non-empty AND
		// candidates empty" is green on both — measured.
		//
		// Here it is not. Dexamethasone x voxelotor arrives as FOUR entry pairs, because the four
		// dexamethasone rows are one substance and the question word puts all of them in play, and they
		// carry different rule sets. The systemic, nasal and ophthalmic rows each carry their own
		// above-floor row against voxelotor, whose token is "voxelotor" and so names her active order:
		// those three pairs are ceded. The TOPICAL row carries no interaction row at all, so its side of
		// the pair contributes nothing that could name the order; what joins it instead is voxelotor's
		// own rows against its three siblings, every one of them tokened with the rxnorm name the family
		// shares ("dexamethasone", measured off the parsed fixture), which names the topical row too —
		// above the floor, naming no order of hers, so that entry pair collects a candidate. All four
		// key alike, on that same shared token, so chartOwned then filters the candidate out: this arm
		// kept nothing and states nothing, and the arm that did report the pair speaks. Decide the cede
		// on the candidate map and it publishes of(0, 0) beside those chips instead — issue #336's own
		// defect, on a shape neither case above reaches.
		Pass ceded = passOverRouteVariants(ROUTE_VARIANT_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Voxelotor 1500mg"),
						null, null, null));

		assertEquals(5, entriesResolvedBy(ROUTE_VARIANT_QUESTION),
				"precondition: the four dexamethasone rows and voxelotor, or one clinical pair does not "
						+ "arrive as several entry pairs and this case measures the same shape as the "
						+ "two above");
		assertTrue(ceded.chips.size() >= 1, "precondition: the chart arm reports the pair: "
				+ DrugReferenceTestSupport.details(ceded.chips));
		for (SafetyWarning chip : ceded.chips) {
			assertFalse(chip.getDetail().contains("named in the question"),
					"precondition: the question-pair arm must have chipped NOTHING, or its candidate "
							+ "survived the filter and no pair was ceded: " + chip);
		}
		assertNotNull(ceded.extent, "a response carrying interaction chips must not state a screen that "
				+ "related nothing");
		assertTrue(ceded.extent.getFound() >= 1,
				"and the number is the chart arm's own, which is what reddens if the cede is decided on "
						+ "an empty candidate map rather than on an empty surviving list; was: "
						+ ceded.extent.getFound() + " with chips "
						+ DrugReferenceTestSupport.details(ceded.chips));
	}
}
