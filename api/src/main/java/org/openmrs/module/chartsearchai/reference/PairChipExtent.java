/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

/**
 * How many drug pairs one question's interaction check found, and how many of them it reported —
 * the statement a bounded safety list owes about its own bounds (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/336">#336</a>), and the
 * statement a COMPLETE one owes about being complete (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/356">#356</a>).
 *
 * <p>Before this existed, a screen that hit {@link DrugSafetyValidator#maxPairChips()} was
 * indistinguishable from a complete one: the withheld pairs were named in a WARN line and nowhere
 * else, so a clinician handed ten chips read a list that was not exhaustive as though it were.
 * Measured on the 3.7.1 standalone against the bundled DDInter knowledge base, a patient on eight
 * anti-inflammatories: 18 above-floor pairs found, 10 reported, and the response carried no trace of
 * the other eight — the answer text ended in an ordinary enumeration, the chip array simply stopped,
 * and every reference's {@code withheldInteractions} read {@code 0} (that field counts a cited
 * record's unshown partners, a different thing). Silent truncation in a safety net reads as "nothing
 * else was found", which is the one thing it does not mean.
 *
 * <p>Not to be confused with {@code DrugSafetyValidator.InteractionPairs}, one case-change away:
 * that is the cross-arm ledger of which pairs a pass has already chipped, and it has a
 * {@code reported} of its own meaning something different. This type counts; that one deduplicates.
 *
 * <p><b>THREE arms state it, one per question shape, and the population is one population.</b> Two
 * of them are PAIRWISE, quadratic in a list this module does not choose, and are the two a cap can
 * truncate — the question's own drugs checked against each other
 * ({@link DrugSafetyValidator#addQuestionPairInteractions}, issue #114) and, for a question that
 * names no drug and asks to be screened, the patient's active orders checked against each other
 * ({@link DrugSafetyValidator#addActiveOrderPairInteractions}, issue #113). The third is the
 * drug-in-play arm ({@code DrugSafetyValidator.addInteractionWarnings}, issue #356), which checks a
 * drug the QUESTION put in play against the patient's own medications — the canonical prescribing
 * question, "can I give this patient X?". Until #356 that shape published no statement at all, so a
 * completed negative screen and a question nobody screened were one value on the wire.
 *
 * <p><b>Which arm owns the field is decided by how many reference entries the question RESOLVED —
 * and, since issue #336's verification round, by whether that arm ceded every pair it related to
 * the drug-in-play arm — not by how many drugs a clinician would say it named</b>, and the resolved
 * count and the clinician's reading come apart on the shipped knowledge base. A name that resolves
 * to SEVERAL reference entries — a substance filed under more than one row, {@code Dexamethasone}
 * beside {@code Dexamethasone (ophthalmic)}, or an alias family sharing an {@code rxnorm_name} —
 * opens the question-pair arm, which then owns this field and states its own pair count. The
 * drug-in-play arm still raises its chips, and they are not in that number. So a response CAN carry
 * an above-floor interaction chip beside {@code found: 0}: the zero is honest about the check that
 * stated it, and a client must not read it as a count of the chips beside it — which is the same
 * warning {@link #getReported()} carries for the other direction.
 *
 * <p><b>The QUESTION-PAIR arm, having CEDED every pair it related, states nothing rather than a
 * zero</b> (issue #336's verification round). It leaves a pair to the drug-in-play arm wherever one
 * of the ABOVE-FLOOR rules joining it names one of the patient's own orders
 * ({@code DrugSafetyValidator.coveredByActiveOrderArm}), because a chip about her own medication is
 * the stronger statement. Where that took EVERY pair it related, the arm has no bounded list left
 * to describe, and a zero there would say the reference data related none of the pairs it
 * enumerated. Measured on the 3.7.1 standalone at {@code main} @ {@code 77c0f9a2}, asking
 * {@code "Can I give her warfarin and ibuprofen?"} of the eight-anti-inflammatory patient of issue
 * #336's own report — one of whose orders is the ibuprofen the question names: the response
 * published {@code found: 0, reported: 0} beside fifteen interaction chips, two of them Major, so
 * a client rendering the field as {@code README} tells it to showed "0 of 0 interaction pairs"
 * above a Major finding. It now states nothing, which is what lets
 * {@code DrugSafetyValidator.validate}'s issue #356 fallback hand the field to the arm that did
 * report those pairs. Scoped to a pass that ceded every pair: where some survive, the arm keeps
 * the field and describes the list it kept — which {@code maxPairChips} can still truncate, exactly
 * as it could before — and the ceded pairs are reported beside it as chips rather than withheld.
 * The SCREENING arm has a cede of its own — it skips a pair the drug-in-play arm already
 * chipped — and still states a zero there, which ADR Decision 69 records as reproduced and
 * deliberately not fixed by this rule.
 *
 * <p>What every one of them counts is the same thing: above-floor interaction RULES relating one
 * drug to another. The drug-in-play arm's unrated class relationships — a shared ATC subgroup, a
 * curated cross-reactivity group — are chips and are deliberately NOT counted here, because neither
 * pairwise arm has a class leg at all and one wire key must not mean two things by question shape.
 * The three differ in what they draw pairs FROM, exactly as the two pairwise arms already did.
 *
 * <p><b>Precedence, never a sum.</b> The two pairwise gates are mutually exclusive, so at most one
 * of THEM runs per question; the drug-in-play arm can run beside either, and where a pairwise arm
 * stated anything its statement stands alone. A pairwise statement is about a BOUNDED list, where
 * {@code found} and {@code reported} may differ; the drug-in-play arm applies no cap and reports
 * everything it relates. Summing the two into these two integers is the ratio of two different
 * populations this type exists to stop, published by the producer instead of by the client.
 *
 * <p>{@link #getReported()} is therefore <b>not</b> the number of {@code interaction} chips on the
 * response, and #356 widened rather than narrowed that gap: {@code addInteractionWarnings} raises
 * interaction chips of its own — one per matched rule and one per class relationship, so several
 * per drug in play — and appends them to the same list, while what it counts here is its rule chips
 * for the QUESTION's own substances alone. So a response can carry interaction chips raised for a
 * drug only the ANSWER named, class chips counted nowhere, and, on a screening question, chips from
 * two arms while one of them speaks. A client rendering "10 of 18 shown" must say what it is
 * counting, or it publishes a ratio of two different populations.
 *
 * <p><b>A candidate the screening arm cannot tell from one it already collected is not a pair it
 * found</b> (issue #339 review round 12). That arm collapses a chip whose every published field
 * repeats one it has already stated — {@code DrugSafetyValidator.StatedInteractionChips}, reachable
 * because a fixed-dose combination prescription is one co-medication carrying two rule partners —
 * and it does so where the candidate is COLLECTED, before the sort and before the cap, so the
 * restatement is absent from BOTH numbers here rather than counted as a pair found and withheld.
 * Two rules did fire; what {@code found} counts is what a reader could have been shown.
 * {@code OneOrderNameAcrossOneResponseTest.aScreenOfACombinationPrescriptionStatesOneRelationshipOnce}
 * reads this extent through the same {@code Sink} {@code PairChipExtentContextTest} drives, so the
 * two agree about what the arity publishes: move the collapse to the emission loop and it reports
 * {@code found=2, reported=2} beside one chip, which is the ratio-of-two-populations claim issue
 * #336 exists to stop.
 *
 * <p><b>Zero is a measurement and absence is not.</b> An extent stating {@code found == 0} says an
 * arm ran and the reference data related none of the pairs it enumerated — a complete screen,
 * positively assertable, which is half of what this type exists for. <b>That is what it is MEANT
 * to say, and there are arrangements where it is false</b> — do not count them here either: an arm
 * can relate pairs and cede every one of them (ADR Decision 69, the screening arm), and a chart
 * whose only medication the reference data cannot resolve was never a population to screen (ADR
 * Decision 65). Those decisions carry the cases. No extent at all
 * ({@code null} on the answer, {@code null} on the wire) says the producer stated no measurement.
 * This javadoc and {@code README.md}'s client-facing paragraph carry that list — the second because
 * it is the only one a frontend author reads — and everything else points here rather than
 * restating it. <b>Do not count the entries or the homes.</b> Three successive drafts of this
 * paragraph published a count and each was refuted by the next review: three situations where the
 * list held two, one home where README held a second, and two situations while the async
 * early-{@code done} case below was a third nobody had counted. What is worth stating is the
 * mechanism, so here it is with no count asserted in prose — the list numbers itself, and a number
 * the list carries cannot disagree with the list. The first cases are alike to a consumer; the last
 * is told apart by WHERE it is read rather than by this value:
 *
 * <ol>
 *   <li>no arm enumerated anything — the question resolved no reference drug AND did not ask to be
 *       screened, which is the ordinary case for most questions, or it resolved one and the chart
 *       records no medication to screen it against, or a global property gating those arms is off
 *       (the drug-reference feature, the answer validator, {@code warnOnInteractions}). Both halves
 *       of the first are needed: a question resolving no drug that DOES ask to be screened runs
 *       {@link DrugSafetyValidator#addActiveOrderPairInteractions}, which states {@code of(0, 0)}
 *       even over no orders at all. What #356 calls the row to get right is the other direction:
 *       {@code found == 0} must mean SCREENED and related nothing, never "the drug could not be
 *       resolved", so a drug only the ANSWER named states nothing on its own;</li>
 *   <li>{@code validate} threw and degraded to no warnings, its documented fail-safe — and the
 *       statement is published only on the normal return, so a degraded pass states nothing
 *       rather than describing chips that were discarded.</li>
 *   <li>the statement has not been produced YET — the early {@code done} answer of an
 *       async-grounding stream is built before validation runs, carries no chips either, and is
 *       followed by a {@code grounded} event carrying both. A streaming client must keep consuming
 *       rather than read this one as an answer about the screen.</li>
 * </ol>
 *
 * <p>Never read absence as completeness, and never re-derive either count from the chip list.
 *
 * <p><b>Two measured counts and no derived one.</b> How many were withheld, and whether anything
 * was, are subtractions a reader makes — deliberately not accessors and deliberately not wire keys,
 * because a derived figure published beside a measured one is the defect issue #261 exists to stop,
 * and one that has no single home cannot go stale.
 */
public final class PairChipExtent {

	private final int found;

	private final int reported;

	private PairChipExtent(int found, int reported) {
		this.found = found;
		this.reported = reported;
	}

	/**
	 * @param found how many above-floor candidate pairs the arm enumerated, before the cap cut
	 * @param reported how many of them became chips — {@code min(found, maxPairChips())} from a
	 *        pairwise arm, and equal to {@code found} from the uncapped drug-in-play arm
	 */
	static PairChipExtent of(int found, int reported) {
		return new PairChipExtent(found, reported);
	}

	/** How many above-floor rule pairs the interaction check found, before {@code maxPairChips()}
	 *  cut — nothing cuts the drug-in-play arm's, which is why its {@link #getReported()} equals it. */
	public int getFound() {
		return found;
	}

	/**
	 * How many of those it reported as chips. For an extent a pairwise arm produced this is
	 * {@code min(found, maxPairChips())} and so never greater than {@link #getFound()}; for the
	 * drug-in-play arm, which applies no cap, it is {@link #getFound()} itself. Either way a property
	 * of the ARMS and not one this type enforces: {@link Sink#record} takes the two numbers it is
	 * given. Stated this way because the type cannot keep the stronger promise, and an accessor
	 * promising what nothing checks is how a client comes to render "1000 of 72 shown".
	 */
	public int getReported() {
		return reported;
	}

	@Override
	public String toString() {
		return "PairChipExtent[found=" + found + ", reported=" + reported + "]";
	}

	/**
	 * The one-slot accumulator a caller supplies to hear what the interaction check found — the same
	 * idiom as the {@code List<SafetyWarning> warnings} accumulator
	 * {@link DrugSafetyValidator#validate} already threads through its arms, one level up.
	 *
	 * <p>A caller-supplied sink and never a field on the validator: that bean is a Spring singleton,
	 * so a field would be one slot shared by every concurrent request (issue #172). A pass with no
	 * sink records nothing and costs nothing.
	 *
	 * <p><b>At most one record per pass</b>, and that is a property of the CALLER rather than of the
	 * gates: {@code validate} holds one local, the two mutually-exclusive pairwise arms assign it and
	 * the drug-in-play arm assigns it only where they did not, and
	 * {@code DrugSafetyValidator.recordPairExtent} writes it once on the normal return. A second
	 * record would mean two arms spoke for one question; it overwrites rather than throwing, because
	 * {@code validate} is an additive net whose failure mode is "no warnings" and an exception thrown
	 * here would drop every chip on the request rather than one statement.
	 */
	public static final class Sink {

		private PairChipExtent stated;

		/**
		 * States what the interaction check found and reported. Public for the same reason the
		 * {@code List<SafetyWarning> warnings} accumulator one level up is a plain public list: the
		 * sink belongs to the CALLER, which creates it and reads it back, and the arms merely write
		 * into it. Production has exactly one writer — {@code DrugSafetyValidator.recordPairExtent},
		 * which all three arms go through — and a second producer anywhere would be the
		 * two-derivations-that-agree shape issue #151 records as failing silently and in one
		 * direction.
		 *
		 * @param found how many above-floor candidate pairs the arm enumerated, before the cap cut
		 * @param reported how many of them became chips — {@code min(found, maxPairChips())} from a
		 *        pairwise arm, and equal to {@code found} from the uncapped drug-in-play arm
		 */
		public void record(int found, int reported) {
			stated = PairChipExtent.of(found, reported);
		}

		/**
		 * @return what the interaction check stated, or {@code null} where it stated nothing — see the
		 *         class javadoc, which enumerates what that covers
		 */
		public PairChipExtent stated() {
			return stated;
		}
	}
}
