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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;

/**
 * The strength of a safety answer's opening call follows the finding's own stated strength (#283).
 *
 * <p><b>Why this exists.</b> The addressed-safety branch used to assert, of any finding naming the
 * drug, that "that finding is evidence against giving it, so begin the answer with the call it
 * supports — No", and the one demonstrated safety verdict was a MAJOR finding refusing a delivery. A
 * Minor-rated interaction therefore refused the drug in the same words: measured on the standalone,
 * {@code main} @ b0cfe545, <em>"No — gentamicin should not be given: Gentamicin interacts with active
 * order lidocaine, a Minor interaction"</em>, on a finding whose mechanism text ends "No special
 * precautions are necessary". The severity was deterministic the whole time; only the wording carried
 * the call.
 *
 * <p><b>The caution branch must not be a "Yes", and that is not a wording preference.</b> #107 arm C
 * measured a presence-shaped "Yes" inverting the clinical meaning 5/6 on exactly this question shape,
 * which is why {@code LlmProviderTest} pins the never-"Yes" token. So a finding that withholds
 * nothing still may not produce "Yes": the lead states that the drug can be given and names the
 * caution. Both properties hold together — that is what makes this a gradation rather than a
 * loosening.
 *
 * <p><b>What these assertions are, and are not.</b> They pin the prompt's CONTENT: that the
 * evidence-against claim is now conditional on the strength the finding states, that both strength
 * classes are taught in the words the injected record uses ({@code SafetyFindingSeverityStrengthTest}
 * pins the record side), and that the caution class is demonstrated. What the model then produces is
 * measured on a server; a prompt test cannot assert an answer.
 */
public class SafetyVerdictSeverityGradationTest {

	/** The safety/suitability paragraph, extracted the way {@code LlmProviderTest} extracts it — to
	 *  the next newline, so a branch moved out of the paragraph fails rather than slips through. */
	private static String safetyParagraph() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		int at = prompt.indexOf("The same rules apply to safety and suitability questions");
		assertTrue(at > 0, "the safety/suitability paragraph must still be present");
		int end = prompt.indexOf('\n', at);
		assertTrue(end > at, "the safety/suitability paragraph must still be newline-terminated");
		return prompt.substring(at, end);
	}

	@Test
	public void theEvidenceAgainstClaimIsConditionalOnTheStrengthTheFindingStates() {
		String paragraph = safetyParagraph();
		int claim = paragraph.indexOf("evidence against giving it");
		assertTrue(claim > 0, "the paragraph must still state that direction comes from the finding");

		// The sentence carrying the claim, not the paragraph: a severity-blind instruction elsewhere
		// in the paragraph would satisfy a paragraph-wide check while asserting the old rule.
		int sentenceStart = paragraph.lastIndexOf(". ", claim);
		String sentence = paragraph.substring(sentenceStart < 0 ? 0 : sentenceStart + 2, claim);
		assertTrue(sentence.contains("a reason to withhold it"),
				"the claim must be gated on the finding SAYING it is a reason to withhold — "
						+ "unconditionally, a Minor rating refuses the drug: " + sentence);
	}

	@Test
	public void bothStrengthClassesAreTaughtInTheWordsTheInjectedRecordUses() {
		String paragraph = safetyParagraph();
		assertTrue(paragraph.contains("a reason to withhold it"),
				"the withholding class must be named, or a Major finding loses its refusal");
		assertTrue(paragraph.contains("a caution to note, not a reason to withhold it"),
				"the caution class must be named in the same words the record states it, or the "
						+ "model has to infer the mapping from severity to strength itself");
	}

	/** The part of a strength clause that NAMES the strength — the sentence without its
	 *  "This finding is …" frame and its full stop, i.e. what the rule below has to quote back. The
	 *  frame is asserted rather than assumed, so rewording the constant's SHAPE fails here and says
	 *  so instead of silently making the check vacuous. */
	private static String clauseCore(String clause) {
		String sentence = clause.trim();
		String frame = "This finding is ";
		assertTrue(sentence.startsWith(frame) && sentence.endsWith("."),
				"the strength clause must still read \"" + frame + "…\" for this check to mean "
						+ "anything; it was: " + sentence);
		return sentence.substring(frame.length(), sentence.length() - 1);
	}

	@Test
	public void theRuleNamesTheClauseTheRecordActuallyCarries() {
		String paragraph = safetyParagraph();

		// DERIVED from the constants, and that is the whole point of this case. The two assertions in
		// bothStrengthClassesAreTaughtInTheWordsTheInjectedRecordUses are literals, which is right —
		// they pin what the model reads. But literals on both sides of a coupling are what let the
		// two halves drift: measured, rewording STRENGTH_WITHHOLD reddens eight cases and every one
		// of them is repaired by editing a test literal, after which the rule here still names a
		// phrase no record carries. Same failure LlmProviderTest records for FINDING_PREFIX, and the
		// demonstrations already avoid it by being built from the constants; the rule cannot be,
		// because it embeds the phrase in a sentence, so it is checked instead.
		assertTrue(paragraph.contains(clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD)),
				"the withholding branch must name the clause renderFinding appends: " + paragraph);
		assertTrue(paragraph.contains(clauseCore(DrugReferenceInjector.STRENGTH_CAUTION)),
				"and so must the caution branch: " + paragraph);
	}

	@Test
	public void theCautionBranchLeadsWithNeitherARefusalNorAYes() {
		String paragraph = safetyParagraph();
		int caution = paragraph.indexOf("a caution to note, not a reason to withhold it");
		assertTrue(caution > 0, "the caution branch must be present");
		String rest = paragraph.substring(caution);
		assertTrue(rest.contains("can be given"),
				"the caution branch needs a lead of its own — stating the drug can be given and "
						+ "naming the caution: " + rest);
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("Never open such an answer with \"Yes\""),
				"and it must not reach that lead by dropping #107's never-\"Yes\" token, which arm C "
						+ "measured at 5/6 inverted verdicts");
	}

	/** The demonstration record line for {@code drug}, to its newline — the shape
	 *  {@code DrugReferenceInjector.renderFinding} produces and the model is handed. Shared by the
	 *  two cases below so the withholding and caution demonstrations cannot be checked differently. */
	private static String demonstratedFindingLine(String drug) {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		int at = prompt.indexOf(DrugReferenceInjector.FINDING_PREFIX + drug + ":");
		assertTrue(at > 0, "the prompt must still demonstrate a safety finding for " + drug);
		int end = prompt.indexOf('\n', at);
		return prompt.substring(at, end < 0 ? prompt.length() : end);
	}

	@Test
	public void aCautionClassFindingIsDemonstratedAndItIsARatedMinorOne() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		String findingLine = demonstratedFindingLine("Lychee");
		assertTrue(findingLine.contains("— Minor."),
				"the demonstrated caution has to be a rated Minor finding: " + findingLine);
		assertTrue(findingLine.contains("a caution to note, not a reason to withhold it"),
				"and its record line must carry the clause renderFinding appends, or the "
						+ "demonstration teaches a record shape the model never sees: " + findingLine);
		assertTrue(prompt.contains("\"answer\": \"Lychee can be delivered, with one caution:"),
				"the demonstrated answer must lead with the qualified call");
		assertFalse(prompt.contains("\"answer\": \"No — lychee"),
				"and must not refuse on a finding that withholds nothing");
	}

	@Test
	public void theWithholdingClassIsDemonstratedOnItsOwnRecordLineToo() {
		String findingLine = demonstratedFindingLine("Durian");

		assertTrue(findingLine.contains("— Major."),
				"the demonstrated refusal has to be a rated Major finding: " + findingLine);
		// The half the caution case above does not cover, and the one carrying every refusal that
		// matters. Found by mutation: deleting the clause from this record left all 1295 tests green,
		// so the withholding class could lose its demonstrated record shape while the rule above kept
		// instructing on it — the model matching a sentence no demonstration shows.
		assertTrue(findingLine.contains("This finding is a reason to withhold it."),
				"and must carry the withholding clause renderFinding appends: " + findingLine);
		assertFalse(findingLine.contains("caution to note"),
				"the two demonstrations must not teach the same strength: " + findingLine);
	}

	/** How the caution clause NAMES its class, i.e. {@code clauseCore(STRENGTH_CAUTION)} up to the
	 *  comma that turns into the negation of the other one. Derived rather than written out for the
	 *  reason {@link #theRuleNamesTheClauseTheRecordActuallyCarries} gives, and the comma is asserted
	 *  so a reworded constant fails here instead of making the check vacuous. */
	private static String cautionClass() {
		String core = clauseCore(DrugReferenceInjector.STRENGTH_CAUTION);
		int comma = core.indexOf(',');
		assertTrue(comma > 0,
				"the caution clause must still name its class before negating the other, for this "
						+ "check to mean anything; its core was: " + core);
		return core.substring(0, comma);
	}

	/**
	 * Two findings about ONE drug can state different strengths, and the answer has one lead.
	 *
	 * <p><b>Reachable, measured through the real pipeline rather than argued.</b> Driving the real
	 * {@code DrugReferenceInjector.injectRecords} over the DDInter sample fixture, a patient on
	 * Warfarin and Aspirin asked <em>"Is it safe to give methotrexate?"</em> is handed two findings:
	 * the Minor warfarin pair stating {@code STRENGTH_CAUTION} and the <b>Major</b> aspirin pair
	 * stating {@code STRENGTH_WITHHOLD}. Both antecedents above are then true at once, where the
	 * single unconditional claim they replaced had nothing to resolve.
	 *
	 * <p>The caution is listed FIRST there, and that is not an ordering accident to lean on: the
	 * drug-in-play arm emits one finding per partner in the entry's own rule order with no severity
	 * sort (the question-pair arm sorts on {@code PAIR_SEVERITY_DESCENDING} and the screen on
	 * {@code SCREENED_PAIR_SEVERITY_DESCENDING}; {@code addInteractionWarnings} does not), and 10 of
	 * that fixture's 16 entries produce an interleaved mix when the patient is on the rest, caution
	 * before withhold in every one. So both antecedents were true with nothing ranking them, on the
	 * {@code warfarin × aspirin} pair issue #283 names as the one this arm exists for. What that
	 * produced on a server was not measured, and this case does not assert it — what is checked is
	 * the reachability above and the paragraph's silence, which is the gap.
	 *
	 * <p>Keyed on the clause the RECORD carries rather than on a severity word, so the withholding
	 * half is derived from the constant here for the reason
	 * {@link #theRuleNamesTheClauseTheRecordActuallyCarries} gives. Every clause assertion in
	 * {@code SafetyFindingSeverityStrengthTest} is per finding, so a set was pinned by nothing at all
	 * before this case.
	 */
	@Test
	public void aSetOfFindingsStatingDifferentStrengthsIsLedByTheStrongest() {
		String paragraph = safetyParagraph();
		int at = paragraph.indexOf("the strongest governs");
		assertTrue(at > 0,
				"the paragraph must decide the lead where a set of findings states BOTH strengths: "
						+ "both antecedents are true and only one lead can be taken: " + paragraph);

		// The sentence carrying the rule, not the paragraph — the two branch sentences above already
		// name both clauses, so a paragraph-wide check would pass on them alone.
		int start = paragraph.lastIndexOf(". ", at);
		int end = paragraph.indexOf(". ", at);
		String sentence = paragraph.substring(start < 0 ? 0 : start + 2,
				end < 0 ? paragraph.length() : end);
		assertTrue(sentence.contains("more than one finding"),
				"the rule has to be about a SET, not a reworded restatement of the single-finding "
						+ "branches above it: " + sentence);
		assertTrue(sentence.contains(clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD)),
				"and keyed on the clause the record actually carries rather than on a severity word: "
						+ sentence);
		// BOTH sides named in the ranking, and this is not symmetry for its own sake. The phrase the
		// withholding clause names its class with, "a reason to withhold it", occurs inside the
		// CAUTION clause as well, negated ("…is a caution to note, NOT a reason to withhold it") —
		// the two clauses are not substrings of one another, the shared phrase is. So a rule whose
		// antecedent is that bare phrase is satisfied by a caution read shallowly, and the only thing
		// separating them would be the "different strengths" half of the sentence. Naming the loser
		// puts the discrimination inside the clause that does the ranking, which is what the two
		// branches above already do.
		assertTrue(sentence.contains(cautionClass()),
				"the ranking must name what the withholding finding outranks, or its antecedent is a "
						+ "phrase the caution clause also contains: " + sentence);
		assertTrue(sentence.contains("\"No\""),
				"the strongest of the two is the withholding one, so the lead it governs is the "
						+ "refusal: " + sentence);
	}
}
