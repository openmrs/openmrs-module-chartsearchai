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

/**
 * How many drug pairs one question's PAIRWISE interaction check found, and how many of them it
 * reported — the statement a bounded safety list owes about its own bounds (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/336">#336</a>).
 *
 * <p>Before this existed, a screen that hit {@link DrugSafetyValidator#maxPairChips()} was
 * byte-indistinguishable from a complete one: the withheld pairs were named in a WARN line and
 * nowhere else, so a clinician handed ten chips read a list that was not exhaustive as though it
 * were. Measured on the 3.7.1 standalone against the bundled DDInter knowledge base, a patient on
 * eight anti-inflammatories screened 18 above-floor pairs and reported 10, with every wire field
 * — the answer, the chips and each reference's {@code withheldInteractions} — identical to a
 * complete screen's. Silent truncation in a safety net reads as "nothing else was found", which is
 * the one thing it does not mean.
 *
 * <p><b>It is about the PAIRWISE check and about nothing else.</b> Both arms that report it are
 * quadratic in a list this module does not choose and are the two the cap can truncate — the
 * question's own drugs checked against each other
 * ({@link DrugSafetyValidator#addQuestionPairInteractions}, issue #114) and, for a question that
 * names no drug and asks to be screened, the patient's active orders checked against each other
 * ({@link DrugSafetyValidator#addActiveOrderPairInteractions}, issue #113). Their gates are
 * mutually exclusive, so at most one of them runs per question and one extent can speak for both.
 * {@link #getReported()} is therefore <b>not</b> the number of {@code interaction} chips on the
 * response: {@code addInteractionWarnings} raises interaction chips of its own, once per drug in
 * play, and appends them to the same list — so a screening question whose ANSWER names a drug can
 * carry more interaction chips than this states pairs. A client rendering "10 of 18 shown" must
 * say what it is counting, or it publishes a ratio of two different populations.
 *
 * <p><b>Zero is a measurement and absence is not.</b> An extent stating {@code found == 0} says a
 * pairwise arm ran and the reference data related none of the pairs it enumerated — a complete
 * screen, positively assertable, which is half of what this type exists for. No extent at all
 * ({@code null} on the answer, {@code null} on the wire) says the producer stated no measurement.
 * It covers two situations, deliberately not distinguished because a consumer must treat them
 * alike:
 *
 * <ol>
 *   <li>no pairwise arm enumerated anything — the question named fewer than two reference drugs
 *       and did not ask to be screened, which is the ordinary case for most questions, or a
 *       global property gating those arms is off (the drug-reference feature, the answer
 *       validator, {@code warnOnInteractions});</li>
 *   <li>{@code validate} threw and degraded to no warnings, its documented fail-safe — and the
 *       statement is published only on the normal return, so a degraded pass states nothing
 *       rather than describing chips that were discarded.</li>
 * </ol>
 *
 * <p>That list has two homes, this one and {@code CLAUDE.md}'s bullet on it. Never read absence as
 * completeness, and never re-derive either count from the chip list.
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
	 * @param reported how many of them became chips, i.e. {@code min(found, maxPairChips())}
	 */
	static PairChipExtent of(int found, int reported) {
		return new PairChipExtent(found, reported);
	}

	/** How many above-floor pairs the pairwise check found, before {@code maxPairChips()} cut. */
	public int getFound() {
		return found;
	}

	/** How many of those it reported as chips. Never greater than {@link #getFound()}. */
	public int getReported() {
		return reported;
	}

	/** How many pairs the cap withheld — {@link #getFound()} minus {@link #getReported()}. */
	public int getWithheld() {
		return found - reported;
	}

	/** Whether the cap actually cut this list, i.e. whether anything was withheld. */
	public boolean isBounded() {
		return reported < found;
	}

	@Override
	public String toString() {
		return "PairChipExtent[found=" + found + ", reported=" + reported + "]";
	}

	/**
	 * The one-slot accumulator a caller supplies to hear what the pairwise check found — the same
	 * idiom as the {@code List<SafetyWarning> warnings} accumulator
	 * {@link DrugSafetyValidator#validate} already threads through its arms, one level up.
	 *
	 * <p>A caller-supplied sink and never a field on the validator: that bean is a Spring singleton,
	 * so a field would be one slot shared by every concurrent request (issue #172). A pass with no
	 * sink records nothing and costs nothing.
	 *
	 * <p><b>At most one record per pass</b>, because the two arms' gates are mutually exclusive
	 * (see the class javadoc). A second record would mean two pairwise arms ran for one question,
	 * which no gate allows; it overwrites rather than throwing, because {@code validate} is an
	 * additive net whose failure mode is "no warnings" and an exception thrown here would drop
	 * every chip on the request rather than one statement.
	 */
	public static final class Sink {

		private PairChipExtent stated;

		/**
		 * States what the pairwise check found and reported. Public for the same reason the
		 * {@code List<SafetyWarning> warnings} accumulator one level up is a plain public list: the
		 * sink belongs to the CALLER, which creates it and reads it back, and the arms merely write
		 * into it. Production has exactly one writer — {@code DrugSafetyValidator.recordPairExtent},
		 * which both pairwise arms go through — and a second producer anywhere would be the
		 * two-derivations-that-agree shape issue #151 records as failing silently and in one
		 * direction.
		 *
		 * @param found how many above-floor candidate pairs the arm enumerated, before the cap cut
		 * @param reported how many of them became chips, i.e. {@code min(found, maxPairChips())}
		 */
		public void record(int found, int reported) {
			stated = PairChipExtent.of(found, reported);
		}

		/**
		 * @return what the pairwise check stated, or {@code null} where it stated nothing — see the
		 *         class javadoc for the three situations that covers
		 */
		public PairChipExtent stated() {
			return stated;
		}
	}
}
