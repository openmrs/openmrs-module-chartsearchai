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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The screening trigger's phrasing corpus: many ways a clinician asks for a reading of the drugs a
 * patient is already on, each put to the real validator over the real shipped knowledge base.
 *
 * <p><b>Why a corpus rather than more cases beside the ones that found the defect.</b> The gap this
 * exists to close is not a bug in a line of code — it is that
 * {@code QueryScopeRouter.isInteractionScreening} answers a question about NATURAL LANGUAGE, and a
 * vocabulary is never finished. Measured live on 2026-09-10 over sixteen DDI questions on eight
 * patients: <em>"Should I stop any of the medications he is on?"</em> screened nothing on a patient
 * whose <em>"Are any of his current medications interacting?"</em> reported a <b>Major</b> pair.
 * Fixing that one phrasing fixes one phrasing. What stops the next one reaching a clinician first is
 * this list — a miss is added HERE, as a red case, and the trigger is widened until it passes.
 *
 * <p><b>Both directions are load-bearing and neither list may be grown alone.</b> The positives say
 * the screen is reachable by the words clinicians actually use; the negatives say it is still tied
 * to what was asked, which is the constraint {@code DrugSafetyValidator.SubjectMatter}'s rules put
 * on this whole area — a screen on every medication-domain question is issue #143's over-reach, and
 * an ENUMERATION request is the shape that separates the two. Widening the trigger to pass a new
 * positive is only correct while every negative stays green.
 *
 * <p>This is not a duplicate of {@code DrugSafetyInteractionScreeningTest}, which pins the ARM's
 * behaviour — capping, pair keying, standing down from the drug-in-play arm. This class pins only
 * which QUESTIONS reach it, and deliberately holds one chart fixed so a failure is never about the
 * data.
 */
public class DrugSafetyScreeningPhrasingCorpusTest {

	/**
	 * The chart every cell runs against: two active orders carrying a real Major DDInter pair, the
	 * same fixture {@code DrugSafetyInteractionScreeningTest} screens. Held constant on purpose —
	 * this class varies the QUESTION and nothing else, so a red case names a phrasing rather than a
	 * resolution.
	 */
	private static PatientClinicalContext interactingPairContext() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin", "Clarithromycin"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null);
	}

	/** The pre-ANSWER production shape, exactly as {@code DrugReferenceInjector.preAnswerFindings}
	 *  calls the validator: the empty answer means only the question can put a drug in play. */
	private static List<SafetyWarning> screen(String question) {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService())
				.validate("", question, interactingPairContext());
	}

	/**
	 * Questions that ask for a reading of the drugs the patient is on. Every one of these must reach
	 * the screening arm. A phrasing found in the wild that does not belongs here as a red case.
	 */
	static Stream<Arguments> asksForAReading() {
		return Stream.of(
				// The interact* family — the trigger's original vocabulary.
				Arguments.of("Are any of her current medications interacting with each other?"),
				Arguments.of("Do any of her medications interact?"),
				Arguments.of("Are there any drug interactions in his chart?"),
				// Safety, asked plainly. None of these carries an interact* word.
				Arguments.of("Is it safe to continue his current medications?"),
				Arguments.of("Are her medications safe together?"),
				Arguments.of("Is there anything dangerous in his medications?"),
				Arguments.of("What are the risks of his current drugs?"),
				// Concern, which is how a clinician asks to be told what they have not thought of.
				Arguments.of("Anything I should worry about in his current medications?"),
				Arguments.of("Any concerns about her medications?"),
				Arguments.of("Any problems with her medication list?"),
				Arguments.of("Is anything wrong with her medications?"),
				// Changing therapy — the decision an interaction screen exists to inform.
				Arguments.of("Should I stop any of the medications he is on?"),
				Arguments.of("Should I change any of her medications?"),
				Arguments.of("Should I discontinue any of his medicines?"),
				Arguments.of("Should I adjust any of her prescriptions?"));
	}

	/**
	 * <b>Questions this module SHOULD screen and does not.</b> Every one is a clinician asking exactly
	 * what the screening arm answers, and every one names the drugs by something other than the six
	 * literal words {@code QueryScopeRouter.MEDICATIONS_CUES} matches
	 * ({@code medication(s)}, {@code medicine(s)}, {@code meds}, {@code drug(s)},
	 * {@code prescription(s)}, {@code prescribed}) — so the trigger's MEDICATIONS conjunct refuses
	 * them and nothing is screened.
	 *
	 * <p><b>These are DEFECTS, recorded rather than fixed, and the assertion below is inverted on
	 * purpose.</b> Measured 2026-09-11: all six raise nothing on a chart holding a real Major pair.
	 * The fix is to widen that vocabulary, and it is not taken here because the vocabulary is SHARED:
	 * {@code asksAboutMedications} reads the same classification as one of
	 * {@code DrugSafetyValidator.SubjectMatter}'s three widenings, so a word added for this gate also
	 * widens which contraindication chips a question raises — the direction issue #143's over-reach
	 * came from. That needs its own measurement, and this file is what makes the gap a tracked case
	 * instead of something a clinician finds first.
	 *
	 * <p><b>When you widen it, these cases go RED, and that is the signal to move them</b> — cut the
	 * newly-passing string into {@link #asksForAReading} rather than deleting it, and re-run
	 * {@link #asksForNoReading} in the same change to show the widening bought no over-firing.
	 */
	static Stream<Arguments> knownToBeMissed() {
		return Stream.of(
				Arguments.of("Is his current regimen safe?"),
				Arguments.of("Anything risky about her treatment?"),
				Arguments.of("Is she on anything that should not be combined?"),
				Arguments.of("Any red flags in what he is taking?"),
				Arguments.of("Is this combination safe for her?"),
				Arguments.of("What is she taking that could harm her?"));
	}

	/**
	 * Questions that must screen NOTHING, and each is a different reason. Enumeration requests are
	 * the shape that separates a safety reading from "tell me what she is on"; the last two carry no
	 * medication domain at all. Losing any of these is issue #143's over-reach returning — an
	 * unasked-for safety screen — so a widening that reddens one here is wrong however many
	 * positives it buys.
	 */
	static Stream<Arguments> asksForNoReading() {
		return Stream.of(
				Arguments.of("What medications is the patient taking?"),
				Arguments.of("List her current prescriptions."),
				Arguments.of("Show me an interactive list of her medications."),
				Arguments.of("When was her last medication prescribed?"),
				Arguments.of("Does the patient interact well with the care team?"),
				Arguments.of("What are her vitals?"),
				// Near the boundary on the other side: a HISTORY question about prescriptions is still
				// an enumeration, and must not be read as asking for a safety reading.
				Arguments.of("What was she prescribed last year?"));
	}

	@ParameterizedTest(name = "screens: {0}")
	@MethodSource("asksForAReading")
	public void aQuestionAskingForAReadingOfTheDrugsIsScreened(String question) {
		List<SafetyWarning> warnings = screen(question);

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"this question asks for a reading of the drugs the patient is on, so the real Major "
				+ "pair among her own active orders must be raised. A red case here is a phrasing "
				+ "the trigger does not recognise — widen it, and keep every asksForNoReading case "
				+ "green while you do. Was: " + warnings);
	}

	@ParameterizedTest(name = "GAP, screens nothing today: {0}")
	@MethodSource("knownToBeMissed")
	public void aQuestionNamingTheDrugsAnotherWayIsNotScreenedYet(String question) {
		List<SafetyWarning> warnings = screen(question);

		assertTrue(warnings.isEmpty(),
				"THIS IS A CHARACTERISATION OF A DEFECT, NOT A REQUIREMENT. This question asks for a "
				+ "reading of the drugs the patient is on and the module does not give one, because it "
				+ "names them by something other than the six words MEDICATIONS_CUES matches. If this "
				+ "case has gone RED, the vocabulary was widened and that is the fix landing — move "
				+ "this string into asksForAReading and prove asksForNoReading is still green in the "
				+ "same change. Was: " + warnings);
	}

	@ParameterizedTest(name = "screens nothing: {0}")
	@MethodSource("asksForNoReading")
	public void aQuestionAskingForSomethingElseScreensNothing(String question) {
		List<SafetyWarning> warnings = screen(question);

		assertTrue(warnings.isEmpty(),
				"this question asks to be told what she is on, or is not about medications at all, "
				+ "so the chips must stay tied to what was asked and nothing may be screened. A red "
				+ "case here is issue #143's over-reach returning. Was: " + warnings);
	}
}
