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
import static org.junit.jupiter.api.Assertions.fail;

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
 * pins the record side), and that the caution class is demonstrated. Since the two clauses SHARE a
 * phrase — "a reason to withhold it" occurs inside the caution one, negated — they also pin which
 * SENTENCE each phrase sits in, without which the two branches can be swapped and stay green.
 * What the model then produces is measured on a server; a prompt test cannot assert an answer.
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

	/** The sentence of {@code paragraph} containing {@code at}, bounded by the ". " between
	 *  sentences. One definition because two cases below need it and a paragraph-wide read is
	 *  satisfied by a NEIGHBOURING branch in both: the paragraph states two gated branches and a
	 *  ranking over them, all built from the same two clauses, so which sentence a phrase sits in
	 *  is most of what this class checks. The third case scopes to a sentence PREFIX rather than a
	 *  sentence — the antecedent, up to the claim it gates — and stays inline for that reason:
	 *  reading its whole sentence would let a consequent naming the clause satisfy it. */
	private static String sentenceAround(String paragraph, int at) {
		int start = paragraph.lastIndexOf(". ", at);
		int end = paragraph.indexOf(". ", at);
		return paragraph.substring(start < 0 ? 0 : start + 2, end < 0 ? paragraph.length() : end);
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
		// "a reason to withhold it" occurs inside STRENGTH_CAUTION as well, negated ("…is a caution
		// to note, NOT a reason to withhold it"), so the assertion above passes whichever of the two
		// clauses the antecedent names — the same defect d016a8ab removed from the ranking sentence
		// one sentence further down, left standing here.
		// Measured by mutation rather than argued: swapping the two branch antecedents in
		// LlmProvider, so the paragraph says a CAUTION is evidence against giving the drug and a
		// withholding finding is not, left the whole api suite at 1301 tests / 0 failures — a
		// prompt reinstating #283 and inverting every Major refusal and every contraindication on
		// top of it. Giving branch one the caution clause alone is green too, and then NO branch
		// matches a withholding finding, which is the fall-through the contraindication round
		// measured at 3/3 on the standalone. Nothing else in this class reached either mutation when
		// this line was added — bothStrengthClassesAreTaughtInTheWordsTheInjectedRecordUses and
		// theRuleNamesTheClauseTheRecordActuallyCarries test containment in the whole paragraph, and
		// theCautionBranchLeadsWithNeitherARefusalNorAYes read forward from the first occurrence of
		// the caution clause. That case is sentence-scoped now and reddens on both as well, so the
		// two lines overlap; each still holds one the other does not, which is why both are here.
		// The one that is this line's alone: gate branch one on the caution CLASS without the full
		// clause ("a caution to note RATHER THAN a reason to withhold it"). The caution case's
		// anchor then still finds branch two and passes, and only this line reddens (measured).
		assertFalse(sentence.contains(cautionClass()),
				"and on THAT clause rather than on the phrase the caution clause also contains, "
						+ "which is the whole of what separates this branch from the caution one: "
						+ sentence);
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
		// The SENTENCE carrying the clause, not everything after it. Read to the end of the
		// paragraph this was satisfied by a lead belonging to another branch: with the two branch
		// antecedents swapped — the mutation the case above is about — the clause sits in the
		// sentence that opens with "No", the caution lead is still further down in the other one,
		// and a tail check passes on it. Scoped to one sentence the clause and the lead it governs
		// have to be the same branch.
		String sentence = sentenceAround(paragraph, caution);
		assertTrue(sentence.contains("can be given"),
				"the caution branch needs a lead of its own — stating the drug can be given and "
						+ "naming the caution: " + sentence);
		// Which the assertion above does not cover, and this one is pinned by its own mutation
		// rather than added on symmetry: appending "but open with \"No\" where the mechanism is
		// serious" to this sentence keeps the lead intact, so only this line reddens. That is the
		// drift shape the branch is exposed to once it has a lead of its own — a refusal creeping
		// back INTO the caution branch rather than replacing it.
		//
		// It also constrains the paragraph's SHAPE, which is worth saying because the failure is
		// otherwise cryptic: the caution branch and the ranking sentence have to stay separate
		// sentences. Joined by a semicolon into one, this line reddens on the ranking half's "No"
		// while aSetOfFindingsStatingDifferentStrengthsIsLedByTheStrongest still passes on the same
		// span (measured). That is the right constraint — they are two rules and the model is told
		// to apply them in different cases — but a reword that merges them fails here rather than
		// where the ranking is checked.
		assertFalse(sentence.contains("\"No\""),
				"and it is the branch that must NOT open with a refusal — that lead belongs to the "
						+ "withholding branch and to the ranking sentence: " + sentence);
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
		// The half LlmProviderTest pins for the WITHHOLDING demonstration and that did not come across
		// to this one. Found by mutation: replacing the lychee answer with "in store, a Minor problem.",
		// "citations": [] left all 1300 tests green, so the few-shot could go on demonstrating a verdict
		// that cites nothing while every prompt-reading case stayed green — the fabricated-verdict shape
		// #126 records the eval gate cannot see, taught by example. Same gap as
		// theWithholdingClassIsDemonstratedOnItsOwnRecordLineToo below, one demonstration over.
		assertTrue(prompt.contains("a Minor problem [5].\", \"citations\": [2, 5]}"),
				"the demonstrated answer must carry the finding's own severity and cite BOTH the "
						+ "finding and the record it rests on");
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
	 * <p>The caution was listed FIRST there when this was written, and the point was that it is not
	 * an ordering accident to lean on: the drug-in-play arm emitted one finding per partner in the
	 * entry's own rule order with no severity sort, and 10 of that fixture's 16 entries produced an
	 * interleaved mix when the patient is on the rest, caution before withhold in every one. So both
	 * antecedents were true with nothing ranking them, on the {@code warfarin × aspirin} pair issue
	 * #283 names as the one this arm exists for. What that produced on a server was not measured, and
	 * this case does not assert it — what is checked is the reachability above and the paragraph's
	 * silence, which is the gap.
	 *
	 * <p>Issue #346 has since given that arm an ordering ({@code FINDING_STRENGTH_DESCENDING}), so
	 * the withholding finding is now read first in that arrangement and the measured figure above
	 * describes the arm as it was. <b>Nothing about this case rests on it</b>, which is why the
	 * assertions below did not move: they read the prompt PARAGRAPH's wording and never a chip order.
	 * That independence is the whole argument for keeping the rule — the model is handed a SET whose
	 * order is not stated to it and which two arms can still interleave, so a lead resting on
	 * emission ordering rests on something the prompt cannot express, before and after #346 alike.
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
		String sentence = sentenceAround(paragraph, at);
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

	/**
	 * The two CURRENT-MEDICATION classes are taught in the record's own words too (issue #348).
	 *
	 * <p>This is the property ADR Decision 44 measured the absence of, on this very record type: an
	 * additive clause nothing in the prompt keys on left the answer byte-identical across six runs,
	 * and the decision's own reasoning names why — "The clause introduces no new call for
	 * {@code DEFAULT_SYSTEM_PROMPT} to teach". So a counterpart clause that the paragraph does not
	 * quote is not a smaller version of this change; it is the inert one. Derived from the constants
	 * for the reason {@link #theRuleNamesTheClauseTheRecordActuallyCarries} gives.
	 */
	@Test
	public void bothCurrentMedicationClassesAreTaughtInTheWordsTheInjectedRecordUses() {
		assertBranchNaming(clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION),
			"the change-of-therapy class must have a BRANCH of its own, or a screened pair of the "
					+ "patient's own prescriptions has a clause no branch of the prompt answers and "
					+ "falls through to whichever lead the model reaches for — which is what #283 "
					+ "measured");
		assertBranchNaming(clauseCore(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION),
			"and so must its caution counterpart, in the same words");
	}

	/**
	 * Asserts that some sentence of the safety paragraph names {@code core} AND tells the model how to
	 * OPEN — i.e. that the class has a branch of its own, not merely a mention.
	 *
	 * <p><b>Why the paragraph-wide containment this replaced was not enough, measured rather than
	 * argued.</b> The ranking sentence for a mixed set names both current-medication classes in order
	 * to name the loser, so it satisfies a bare {@code paragraph.contains(core)} on its own: deleting
	 * the change-of-therapy BRANCH — the sentence that says what to do with such a finding — left the
	 * whole api suite green. That is the guard-satisfied-by-a-sibling shape, and it is what the
	 * "open" term closes; the ranking sentence carries no opening instruction.
	 */
	private static void assertBranchNaming(String core, String because) {
		String paragraph = safetyParagraph();
		for (String sentence : paragraph.split("(?<=\\.)\\s+")) {
			if (sentence.contains(core) && sentence.contains("open")) {
				return;
			}
		}
		fail(because + ". No sentence of the paragraph both names \"" + core
				+ "\" and says how to open: " + paragraph);
	}

	/**
	 * A set of findings mixing a PROPOSAL call with a CURRENT-MEDICATION call has one lead, and the
	 * paragraph decides it (issue #348).
	 *
	 * <p>Reachable and not a corner: the order-driven contraindication arm (issue #143) walks the
	 * patient's own prescriptions on any question that asks about her medications, allergies or
	 * conditions, so it can raise a finding beside a drug-in-play arm's on a question that DID
	 * propose a drug. Without this rule the two antecedents are both true and nothing chooses.
	 *
	 * <p>It names the losers as well as the winner, for the reason
	 * {@link #aSetOfFindingsStatingDifferentStrengthsIsLedByTheStrongest} states of the older pair.
	 */
	@Test
	public void aSetMixingAProposalCallAndACurrentMedicationCallIsLedByTheStrongest() {
		String paragraph = safetyParagraph();
		int at = paragraph.indexOf("calls of both kinds");
		assertTrue(at > 0,
				"the paragraph must decide the lead where a proposal call and a current-medication "
						+ "call are stated in one response: " + paragraph);

		String sentence = sentenceAround(paragraph, at);
		assertTrue(sentence.contains(clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD)),
				"the winner is the proposal refusal, named by the clause the record carries rather "
						+ "than by a severity word: " + sentence);
		assertTrue(sentence.contains(
			clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION)),
			"and the ranking must name what it outranks: " + sentence);
	}

	/**
	 * Across the FOUR strength classes there is exactly ONE phrase that sits inside another's words,
	 * and it is the one ADR Decision 37 already handles (issue #348).
	 *
	 * <p>The hazard is that decision's own: "a reason to withhold it" occurs inside
	 * {@code STRENGTH_CAUTION} too, negated, so a branch whose antecedent is that bare phrase is
	 * satisfied by a caution read SHALLOWLY. What Decision 37 did about it is name the loser in the
	 * ranking sentence, which {@link #aSetOfFindingsStatingDifferentStrengthsIsLedByTheStrongest}
	 * pins; the containment itself is left standing, deliberately, because the clauses read as
	 * English.
	 *
	 * <p>Adding two more classes turns 2 ordered pairs into 12, and no behavioural test can see a
	 * shallow read — the MODEL is what reads shallowly. So the whole product is walked and the one
	 * admitted pair is named rather than skipped: a reword that introduced a SECOND containment
	 * reddens here, and so does one that removed the admitted one without this case being re-read.
	 */
	@Test
	public void theOnlyStrengthClassNamedInsideAnothersWordsIsTheOneDecision37Handles() {
		String withhold = clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD);
		String caution = clauseCore(DrugReferenceInjector.STRENGTH_CAUTION);
		String[] cores = { withhold, caution,
			clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION),
			clauseCore(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION) };

		assertTrue(caution.contains(withhold),
				"precondition: the admitted pair is the withholding class named inside the caution "
						+ "class, negated. If this ever fails, the exemption below has become a hole "
						+ "and this case is the one to re-read: " + caution);

		for (String inner : cores) {
			for (String outer : cores) {
				if (inner.equals(outer) || (inner.equals(withhold) && outer.equals(caution))) {
					continue;
				}
				assertFalse(outer.contains(inner),
						"no other class's own words may sit inside another's, or a branch gated on "
								+ "the first is satisfied by a finding stating the second — and only "
								+ "the withholding-inside-caution pair has a ranking sentence written "
								+ "for it: \"" + inner + "\" occurs in \"" + outer + "\"");
			}
		}
	}
}
