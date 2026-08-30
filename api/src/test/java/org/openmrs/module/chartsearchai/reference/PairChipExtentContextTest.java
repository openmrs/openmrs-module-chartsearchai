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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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
 * as a precondition in each case, because interaction chips also come from the drug-in-play arm and
 * the two populations are not the same one.
 */
public class PairChipExtentContextTest extends BaseModuleContextSensitiveTest {

	/** The 16-drug polypharmacy question, the same string {@code PairChipCapContextTest} uses. */
	private static final String POLYPHARMACY_QUESTION = "Reviewing polypharmacy: lisinopril, metformin,"
			+ " methotrexate, omeprazole, sertraline, simvastatin, spironolactone, tramadol, warfarin,"
			+ " aspirin, ciprofloxacin, clarithromycin, digoxin, fluconazole, amiodarone and ibuprofen"
			+ " — any interactions?";

	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** Above-floor pairs among the excerpt's 16 question-named drugs. */
	private static final int QUESTION_PAIRS = 72;

	/** Above-floor pairs among the six active orders the screening arrangement carries. */
	private static final int SCREENED_PAIRS = 15;

	private DrugSafetyValidator validator;

	@BeforeEach
	public void setUp() {
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
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

	/** The screening arm over six active orders interacting 15 ways — the same arrangement the cap
	 *  test and the un-capped screening test use, so the three cannot drift apart. */
	private Pass screeningPass() {
		return pass(SCREENING_QUESTION, DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin", "Warfarin", "Ciprofloxacin", "Clarithromycin",
						"Fluconazole", "Amiodarone"),
				DrugReferenceTestSupport.set("C10AA01", "B01AA03", "J01MA02", "J01FA09", "J02AC01",
						"C01BD01"),
				null, null));
	}

	/** Runs the real validate with a sink, exactly as {@code LlmInferenceService} does. The empty
	 *  answer is the pre-answer production shape ({@code DrugReferenceInjector.preAnswerFindings}). */
	private Pass pass(String question, PatientClinicalContext context) {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		List<SafetyWarning> chips = validator.validate("", question, context, null, null, sink);
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
		assertEquals(12, capped.extent.getWithheld());
		assertTrue(capped.extent.isBounded(), "a cut list must say it was cut");
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
		assertEquals(SCREENED_PAIRS, full.extent.getReported());
		assertEquals(0, full.extent.getWithheld());
		assertFalse(full.extent.isBounded(), "an uncut list must not claim it was cut");
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
		// the default and diverge wherever the candidate list is shorter than the cap.
		for (int cap : new int[] { 1, 3, 10, 15, 1000 }) {
			configureCap(String.valueOf(cap));
			Pass screened = screeningPass();

			assertEquals(SCREENED_PAIRS, screened.extent.getFound(),
					"at cap " + cap + " the candidate count is a property of the data, not of the cap");
			assertEquals(Math.min(SCREENED_PAIRS, cap), screened.extent.getReported(),
					"at cap " + cap + " the stated reported count must be the cut that happened");
			assertEquals(screened.chips.size(), screened.extent.getReported(),
					"at cap " + cap + " the statement must match the chips raised");
		}
	}

	@Test
	public void aScreenThatRelatesNoPairStatesZeroRatherThanNothing() {
		// Zero is a measurement: this screen ran over the patient's orders and the reference data
		// related none of the pairs it enumerated. A caller told nothing at all could not tell that
		// COMPLETE result from a question that never asked to be screened.
		Pass screened = pass(SCREENING_QUESTION, DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin"),
				DrugReferenceTestSupport.set("C10AA01"), null, null));

		assertTrue(screened.chips.isEmpty(),
				"precondition: one order relates to nothing, so no pair chip is raised");
		assertNotNull(screened.extent, "but the screen ran, so it must still state its extent");
		assertEquals(0, screened.extent.getFound());
		assertEquals(0, screened.extent.getReported());
		assertFalse(screened.extent.isBounded());
	}

	@Test
	public void aQuestionThatRunsNeitherPairwiseArmStatesNothing() {
		// Absence means the producer measured nothing, never that the screen was complete. This
		// question names one reference drug — too few for the question-pair arm — and does not ask to
		// be screened, so neither arm enumerates a candidate list to state the extent of.
		Pass none = pass("What is warfarin used for?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));

		assertNull(none.extent, "no pairwise arm ran, so nothing may be stated on the answer's behalf");
	}
}
