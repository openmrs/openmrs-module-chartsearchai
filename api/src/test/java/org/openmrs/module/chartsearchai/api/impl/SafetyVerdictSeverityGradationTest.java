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

	@Test
	public void aCautionClassFindingIsDemonstratedAndItIsARatedMinorOne() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		int demonstrated = prompt.indexOf(DrugReferenceInjector.FINDING_PREFIX + "Lychee:");
		assertTrue(demonstrated > 0,
				"the graded rule needs the caution class DEMONSTRATED, not only described — the "
						+ "Major refusal already is, and it is what generalized wrongly");
		String findingLine = prompt.substring(demonstrated,
				prompt.indexOf('\n', demonstrated) < 0 ? prompt.length() : prompt.indexOf('\n', demonstrated));
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
}
