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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

	/**
	 * The evidence-against claim is conditional on the strength the finding states (#283) — asked of
	 * EVERY sentence that makes the claim and not the first (issue #348, round 3 of this PR).
	 *
	 * <p>Reading the FIRST occurrence was enough while the paragraph made the claim once, and it is
	 * not any more. Measured on this PR's pushed head: rewriting the change-of-therapy branch as the
	 * pre-#348 refusal WITHOUT the {@code "No"} literal — <em>"… is evidence against giving it: open
	 * by naming the medication that must not be given and what to avoid"</em> — was green, because
	 * the first occurrence stays in the withholding branch and nothing read further. Every occurrence
	 * is gated now, and the paragraph is still required to make the claim at least once.
	 *
	 * <p><b>A NEGATED occurrence is skipped rather than gated</b>: the caution branch's <em>"is not
	 * evidence against giving the drug"</em> DENIES the direction instead of instructing it, so
	 * demanding the withholding clause of it would demand the clause of a sentence whose whole point
	 * is that the clause does not apply. Two residues that leaves, stated rather than argued away: a
	 * positive claim worded around this literal (<em>"is evidence against that medication"</em>) is
	 * matched by no occurrence here, and a negated occurrence is not read for what it then instructs
	 * — {@link #assertCurrentMedicationBranch} is what holds the current-medication branches' own
	 * leads, and {@link #everyRefusalInstructionInTheParagraphIsGatedOnTheWithholdingClause} the
	 * refusal token wherever it appears.
	 */
	@Test
	public void theEvidenceAgainstClaimIsConditionalOnTheStrengthTheFindingStates() {
		String paragraph = safetyParagraph();
		String claimText = "evidence against giving it";
		int gated = 0;
		for (int claim = paragraph.indexOf(claimText); claim >= 0; claim = paragraph
				.indexOf(claimText, claim + 1)) {
			// The sentence carrying the claim, not the paragraph: a severity-blind instruction
			// elsewhere in the paragraph would satisfy a paragraph-wide check while asserting the
			// old rule.
			int sentenceStart = paragraph.lastIndexOf(". ", claim);
			String sentence = paragraph.substring(sentenceStart < 0 ? 0 : sentenceStart + 2, claim);
			if (sentence.endsWith("not ")) {
				continue;
			}
			gated++;
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
							+ "which is the whole of what separates this branch from the caution "
							+ "one: " + sentence);
		}
		assertTrue(gated > 0,
				"the paragraph must still state that direction comes from the finding, or this case "
						+ "passes by having nothing to check: " + paragraph);
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
	 *
	 * <p>The branch is checked for what it INSTRUCTS and not only for naming its class —
	 * {@link #assertCurrentMedicationBranch} carries which mutation holds each of those assertions,
	 * and the measured reason the naming half alone was not enough.
	 */
	@Test
	public void bothCurrentMedicationClassesAreTaughtInTheWordsTheInjectedRecordUses() {
		assertCurrentMedicationBranch(
			clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION),
			"the change-of-therapy class must have a BRANCH of its own, or a screened pair of the "
					+ "patient's own prescriptions has a clause no branch of the prompt answers and "
					+ "falls through to whichever lead the model reaches for — which is what #283 "
					+ "measured");
		assertCurrentMedicationBranch(
			clauseCore(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION),
			"and so must its caution counterpart, in the same words");
	}

	/**
	 * Asserts that some sentence of the safety paragraph is a BRANCH for the current-medication class
	 * {@code core}: that it names the class, tells the model how to OPEN, and instructs the lead issue
	 * #348 exists for rather than a verdict about giving a drug.
	 *
	 * <p><b>Why the paragraph-wide containment this replaced was not enough, measured rather than
	 * argued.</b> The ranking sentence for a mixed set names both current-medication classes in order
	 * to name the loser, so it satisfies a bare {@code paragraph.contains(core)} on its own: deleting
	 * the change-of-therapy BRANCH — the sentence that says what to do with such a finding — left the
	 * whole api suite green. That is the guard-satisfied-by-a-sibling shape, and it is what the
	 * "open" term closes; the ranking sentence carries no opening instruction.
	 *
	 * <p><b>And why naming the class and the word "open" was not enough either.</b> That pair is an
	 * EXISTENCE check, and the #348 defect is about CONTENT: a sentence naming the class and then
	 * telling the model to refuse satisfies both terms. Measured on {@code 86a5a4c0} by a reviewer
	 * who rewrote the change-of-therapy branch as <em>"… is evidence against giving it: open with
	 * \"No\" and what to avoid."</em> — the pre-#348 instruction reattached to the new class — and ran
	 * the whole api module: 0 failures. The four assertions below are what closes that. <b>A mutation
	 * violating more than one of them is reported against the FIRST it violates</b> — assertion order
	 * decides which failure JUnit prints — so the witness recorded for each individual assertion is a
	 * mutation that violates it ALONE, measured against this class on the head this note was written
	 * on:
	 *
	 * <ul>
	 * <li>{@code "open by naming"} — the lead #348 asks for is a STATEMENT naming the medication, not
	 * a verdict about giving a drug. This is the assertion the reviewer's REFUSAL mutation reports
	 * against, the change branch rewritten as a refusal. Its own witness is rewording just the verb —
	 * <em>"open by stating that medication and what the finding relates it to"</em>, everything else
	 * intact — which leaves the other three green. <b>What this line is not is an exclusion of a
	 * permission</b>, and until round 3 of this PR's hardening this bullet said otherwise: it named
	 * the caution-current branch given the #107 arm-C permission (<em>"open by stating that the drug
	 * can be given"</em>) as a second witness for this line, which it reddens only because that
	 * wording happens to DROP the literal. Keep the literal and add the permission and this line is
	 * green — which is what the fourth bullet is for.</li>
	 * <li>no {@code "No"} — the refusal lead belongs to the withholding branch and to the older
	 * ranking sentence. This is the assertion {@link #theCautionBranchLeadsWithNeitherARefusalNorAYes}
	 * already carried for the caution branch, whose own comment names the drift as "a refusal creeping
	 * back INTO the caution branch rather than replacing it" — which is #348 realised. Its witness is
	 * appending <em>"but open with \"No\" where the mechanism is serious"</em> to the shipped branch,
	 * which keeps the lead and the prohibition and reddens this line alone.</li>
	 * <li>the prohibition {@code "never open by refusing to give a drug"} — a positive lead does not
	 * by itself stop the model reaching for the refusal it read two sentences earlier, and this is the
	 * clause that says so. Its witness is deleting exactly that clause from one branch, which reddens
	 * this line alone.</li>
	 * <li>no {@link #proposalCautionPermission()} — the branch may not borrow the lead the
	 * PROPOSAL-caution sentence two sentences earlier is required to use. This is the FAIL-OPEN
	 * direction and the one the three above do not touch: #107 arm C measured a presence-shaped
	 * permission inverting the call 5 of 6 times, and it is the whole reason
	 * {@link DrugReferenceInjector#STRENGTH_CAUTION_CURRENT_MEDICATION} exists rather than the
	 * withholding counterpart alone. Three witnesses, each keeping the other three properties above
	 * intact and each measured to redden THIS line and no other case in this class — and each of the
	 * three was green across the whole api module before this line existed, which is how round 3
	 * found the gap:
	 * <em>"open by naming that the drug can be given and the caution in the same sentence"</em> for
	 * the caution-current branch — the verbatim direction that clause's own javadoc names;
	 * <em>"open by naming it and stating that the medication can be given, with the caution in the
	 * same sentence"</em>, the harmonisation a maintainer would reach for; and, on the
	 * WITHHOLDING-class branch, <em>"open by naming that medication, stating that it can be given,
	 * and what the finding relates it to"</em>, which instructs a permission lead for a Major
	 * screened pair of the patient's own prescriptions — #348's own reproduction cell.</li>
	 * </ul>
	 *
	 * <p><b>What these four do NOT catch</b>, since a guard over text has a gap between the property
	 * it means and the string it matches: they are literals, so a semantically equivalent reword —
	 * <em>"do not open by refusing"</em>, or a lead that names the medication in other words — reddens
	 * them and must be re-read here deliberately rather than repaired by editing the literal. In the
	 * other direction, the permission line matches the sibling's own words and nothing else, so a
	 * permission phrased away from them — <em>"open by stating that the medication may be
	 * continued"</em>, which is the natural wording for a medication she is already ON — is NOT
	 * caught HERE. That residue is why the phrase is read off the paragraph instead of copied: the
	 * derivation cannot close the gap, but it does stop the two branches and their sibling drifting
	 * apart unnoticed. What CLOSES it is round 4's
	 * {@link #theTwoCurrentMedicationBranchesAreExactlyTheseWords}, which seals both branch sentences
	 * verbatim — so a permission in ANY wording, written into either of those two sentences or added
	 * as a third one the same selector reaches, reddens the build. These four stay because they say
	 * WHICH part of each sentence mattered, which a seal cannot. What is out of BOTH their reach,
	 * since they select sentences by the same rule, is a permission instructed in a sentence naming no
	 * class core, or naming one and saying nothing about opening; within a branch sentence the line is
	 * not scoped to the lead, so a permission stated later in the same sentence reddens it as well.
	 *
	 * <p><b>They are asserted of EVERY matching sentence, not the first.</b> They read the first one
	 * until round 2 of this PR's hardening, which meant a second sentence instructing a refusal for
	 * the same class was invisible — the #348 defect ADDED rather than substituted. Measured by
	 * appending one such sentence after the change-of-therapy branch — <em>"… is evidence against
	 * giving it: open with \"No\" and what to avoid."</em>, the pre-#348 instruction again: the whole
	 * api module was green on {@code 026613de} and it now reddens, reported (as such a mutation always
	 * is here) against the FIRST it violates, {@code "open by naming"}.
	 * It costs nothing today because exactly one sentence per class carries both terms: the two
	 * RANKING sentences name
	 * a class core and contain no "open", which is what the loop's second term excludes and what
	 * {@link #aSetMixingAProposalCallAndACurrentMedicationCallIsLedByTheStrongest} guards instead.
	 * What is still out of reach is a refusal instruction that names no class core at all;
	 * {@link #everyRefusalInstructionInTheParagraphIsGatedOnTheWithholdingClause} is that one.
	 */
	private static void assertCurrentMedicationBranch(String core, String because) {
		String paragraph = safetyParagraph();
		int matched = 0;
		for (String sentence : paragraph.split("(?<=\\.)\\s+")) {
			if (!sentence.contains(core) || !sentence.contains("open")) {
				continue;
			}
			matched++;
			assertTrue(sentence.contains("open by naming"),
					because + ". The branch must open by NAMING the medication — the statement #348 "
							+ "says the chip carries and the answer does not — and not with a verdict "
							+ "about giving a drug: " + sentence);
			assertFalse(sentence.contains("\"No\""),
					because + ". And it must not instruct a refusal: that lead belongs to the "
							+ "withholding branch and to the ranking sentence, and reattaching it "
							+ "here is issue #348 itself: " + sentence);
			assertTrue(sentence.contains("never open by refusing to give a drug"),
					because + ". And it must forbid the refusal outright, because the withholding "
							+ "branch the model reads two sentences earlier is what it fell through "
							+ "to: " + sentence);
			assertFalse(sentence.contains(proposalCautionPermission()),
					because + ". And it must not instruct a PERMISSION either — the words the "
							+ "PROPOSAL-caution branch leads with are #107 arm C's presence-shaped "
							+ "permission, and there is nothing to permit about a medication she is "
							+ "already taking: " + sentence);
		}
		if (matched == 0) {
			fail(because + ". No sentence of the paragraph both names \"" + core
					+ "\" and says how to open: " + paragraph);
		}
	}

	/**
	 * The words the PROPOSAL-caution branch instructs its lead with — the permission a
	 * current-medication branch may not borrow — read OFF that branch rather than written out here.
	 *
	 * <p><b>Derived, because the failure mode is HARMONISATION.</b> The sibling two sentences earlier
	 * is REQUIRED to lead with a permission ({@link #theCautionBranchLeadsWithNeitherARefusalNorAYes}
	 * asserts its "can be given"), so a maintainer making a caution answer about a current medication
	 * more useful reaches for exactly those words; and if that sibling is ever reworded, a literal
	 * copied over here would go on excluding a phrase the prompt no longer uses, silently. Reworded
	 * away from a permission, the permission assertion in {@link #assertCurrentMedicationBranch}
	 * fails HERE instead — saying the two branches must be re-read against the new words — rather
	 * than passing vacuously.
	 *
	 * <p>It reads the LEAD only (to the comma that ends the instruction) and not the whole sentence,
	 * so the phrase it hands back is the permission itself and not the sibling's whole clause; the
	 * frame terms are asserted for the reason {@link #clauseCore} asserts its own.
	 */
	private static String proposalCautionPermission() {
		String paragraph = safetyParagraph();
		int at = paragraph.indexOf("a caution to note, not a reason to withhold it");
		assertTrue(at > 0,
				"the proposal-caution branch must be present for its lead to be read off: " + paragraph);
		String sibling = sentenceAround(paragraph, at);
		int open = sibling.indexOf("open by ");
		assertTrue(open > 0,
				"the proposal-caution branch must still say how to open, for this to read its "
						+ "permission off: " + sibling);
		int comma = sibling.indexOf(',', open);
		assertTrue(comma > open,
				"its lead must still end at a comma for this to read the permission rather than the "
						+ "rest of the sentence: " + sibling);
		String lead = sibling.substring(open, comma);
		int permission = lead.indexOf("can be ");
		assertTrue(permission > 0,
				"the proposal-caution lead must still BE a permission for this check to name one; "
						+ "reworded, the current-medication branches have to be re-read against the "
						+ "new words rather than this literal repaired: " + lead);
		return lead.substring(permission);
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
	 *
	 * <p><b>And it asserts the ORDER they are named in, which naming them does not.</b> Until round 2
	 * of this PR's hardening this case checked only that the sentence names both classes, and naming
	 * is not ranking: the sentence inverted — <em>"a finding that is a reason to change a medication
	 * this patient is already taking leads, then one that is a reason to withhold it, then a
	 * caution"</em> — still names both, so it was green, while telling the model to demote a proposal
	 * refusal below a change-of-therapy statement whenever one response states both. Reachable for the
	 * reason above. So the positions are read: withholding call, then change-of-therapy call, then the
	 * caution.
	 *
	 * <p>What the order assertions do NOT catch, stated rather than argued away: a reword that keeps
	 * the order and drops the RANKING. <em>"… a finding that is a reason to withhold it may be stated
	 * before one that is a reason to change a medication this patient is already taking, then a
	 * caution"</em> passes every line here, because the anchor this case opens on is the antecedent
	 * ({@code "calls of both kinds"}) and nothing reads the verb that governs — the older sibling's
	 * {@code "the strongest governs"} anchor does not match this sentence's <em>"strongest STILL
	 * governs"</em>. Nor is a caution named by some other word caught, the third term being the
	 * literal the paragraph uses.
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

		// ORDER, which is the only property this sentence exists to state and which the two
		// assertions above cannot see. Witness for this line: the sentence inverted so the
		// change-of-therapy call leads and the withholding one follows. Both cores are still named,
		// so both assertions above still pass and only this one reddens (measured on 026613de).
		int withholdAt = sentence.indexOf(clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD));
		int changeAt = sentence
				.indexOf(clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION));
		assertTrue(withholdAt < changeAt,
				"the proposal refusal must LEAD: a set stating calls of both kinds takes the "
						+ "strongest, and inverted this sentence demotes a refusal below a "
						+ "change-of-therapy statement: " + sentence);
		// And the caution trails BOTH, which the line above cannot see: moving the caution term
		// between the two — "… is a reason to withhold it leads, then a caution, then one that is a
		// reason to change a medication this patient is already taking" — keeps withhold ahead of
		// change and reddens only here. Matched on the short form the ranking actually uses rather
		// than on either caution CLAUSE, neither of which the sentence spells out; a term that is
		// absent altogether is -1 and reddens here too, so this line carries its own existence
		// check.
		int cautionAt = sentence.indexOf("a caution");
		assertTrue(cautionAt > changeAt,
				"and the caution must be named LAST — omitted or promoted, the ranking states an "
						+ "order the two branches above contradict: " + sentence);
	}

	/**
	 * Nothing in the paragraph puts the refusal lead in front of the model unless the withholding
	 * clause licenses it — or unless the sentence is the paragraph's own prohibition on a verdict
	 * (issue #348).
	 *
	 * <p>{@link #assertCurrentMedicationBranch} closes the same hole one branch at a time, and only
	 * for a sentence that names a class core: a refusal instruction naming no class at all —
	 * <em>"Where the interaction is serious, open with \"No\"."</em> — is invisible to it. Measured:
	 * appended to the paragraph, that sentence reddens this case and no other in this class, so before
	 * this case existed nothing saw it. So the question is asked of every
	 * sentence that carries the token instead, positively: a sentence putting {@code "No"} in front of
	 * the model must either be gated on the clause that LICENSES a refusal, or be the paragraph's own
	 * prohibition on one.
	 *
	 * <p>Two things it does not reach. A refusal worded without that literal — <em>"open by
	 * refusing"</em> — matches nothing here (the branch prohibition is what stands against that). And
	 * a sentence that does name the withholding clause while instructing a refusal for a DIFFERENT
	 * class passes, since naming it is all this case asks; that is the content the per-branch
	 * assertions cover.
	 */
	@Test
	public void everyRefusalInstructionInTheParagraphIsGatedOnTheWithholdingClause() {
		String paragraph = safetyParagraph();
		String withhold = clauseCore(DrugReferenceInjector.STRENGTH_WITHHOLD);
		int carrying = 0;
		for (String sentence : paragraph.split("(?<=\\.)\\s+")) {
			if (!sentence.contains("\"No\"")) {
				continue;
			}
			carrying++;
			assertTrue(sentence.contains(withhold) || sentence.contains("never \"Yes\" or \"No\""),
					"this sentence instructs the refusal lead without being gated on the clause "
							+ "that licenses it, so it can refuse a drug the patient is already "
							+ "taking — which is issue #348: " + sentence);
		}
		assertTrue(carrying > 0,
				"and the paragraph must still instruct the refusal somewhere, or this case passes "
						+ "by having nothing to check: " + paragraph);
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

	/**
	 * The two current-medication branches are EXACTLY these words (issue #348, round 4 of this PR's
	 * hardening).
	 *
	 * <p><b>Why a verbatim pin on top of the four properties.</b>
	 * {@link #assertCurrentMedicationBranch} holds four properties of each branch, and round 3 showed
	 * what a property guard over text cannot do: its permission term is read OFF the proposal-caution
	 * sibling's lead, so it matches that sibling's spelling of a permission ("can be given") and
	 * nothing else — and the natural wording for a medication the patient is already on is
	 * <em>continued</em>, not <em>given</em>. Measured on the pushed head this case was added to,
	 * where the api module was otherwise green: the caution-current branch reworded to <em>"open by naming it and stating that the
	 * medication may be continued,"</em>, and the withholding-class branch to <em>"open by naming that
	 * medication, stating that it may be continued, and what the finding relates it to,"</em>, were
	 * each green across all four properties. #107 arm C measured a presence-shaped permission
	 * inverting the clinical call 5 of 6 times on this question shape, and the second of those two
	 * mutations instructs that lead for a Major screened pair of the patient's own prescriptions —
	 * #348's own reproduction cell.
	 *
	 * <p><b>And it is a tripwire rather than an over-strict guard.</b> These two sentences are prompt
	 * surface whose effect nothing in this repository can see, so ADR Decision 71's two-build A/B is
	 * what licensed the words that are here. Any legitimate reword re-opens that A/B, which means
	 * failing loudly on one is the WANTED behaviour: the failure tells the next maintainer a live
	 * measurement is owed, and that is exactly why
	 * {@code DrugClassQuestionNoteTest.theRenderedNoteIsExactlyTheseWords} pins its own rendered note
	 * verbatim. The four properties stay because a verbatim pin alone says nothing about WHICH part of
	 * the sentence mattered; they are the reasons, this is the seal.
	 *
	 * <p>The branch sentences are SELECTED by the same rule {@link #assertCurrentMedicationBranch}
	 * selects by — a sentence naming a current-medication class core and saying how to open — and the
	 * cores are derived from the record's own constants. So a reword of a CLAUSE constant is reported
	 * by the pin failing here rather than by this case quietly selecting nothing, and a THIRD such
	 * sentence appearing in the paragraph fails the equality rather than being skipped.
	 */
	@Test
	public void theTwoCurrentMedicationBranchesAreExactlyTheseWords() {
		String change = clauseCore(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION);
		String caution = clauseCore(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION);
		List<String> branches = new ArrayList<String>();
		for (String sentence : safetyParagraph().split("(?<=\\.)\\s+")) {
			if ((sentence.contains(change) || sentence.contains(caution)) && sentence.contains("open")) {
				branches.add(sentence);
			}
		}

		assertEquals(Arrays.asList(
			"A finding that says it is a reason to change a medication this patient is already taking "
					+ "is not about a drug anything proposed: open by naming that medication and what "
					+ "the finding relates it to, carry the finding's severity, and never open by "
					+ "refusing to give a drug.",
			"A finding that says it is a caution about a medication this patient is already taking, "
					+ "not a reason to change it, is not evidence against that medication: open by "
					+ "naming it and the caution in the same sentence, and never open by refusing to "
					+ "give a drug."),
			branches,
			"these two sentences are what a clinician's answer opens from, and nothing in this "
					+ "repository can see what a model makes of them — ADR Decision 71's two-build "
					+ "A/B is what licensed these words. A reword re-opens that measurement, so this "
					+ "failure is the reminder that one is owed, not a literal to repair");
	}
}
