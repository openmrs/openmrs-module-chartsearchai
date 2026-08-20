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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Issue #270: a dose stated for one substance charged to another whose name it contains.
 *
 * <p>{@code DrugSafetyValidator.substanceOwnsDose} gives a stated dose to a substance unless a DIFFERENT
 * substance's name sits <b>strictly</b> nearer the {@code N mg}. Where two published names nest, the two
 * occurrences sit at the SAME distance, so neither is strictly nearer, no veto fires on either side, and
 * BOTH substances claim the number — the shorter one warning about a dose the answer stated for the longer.
 *
 * <p><b>Co-location is not one fact but two, and that is why the nesting is posed twice.</b> The
 * metric is {@code pos < idx ? idx - pos : (pos > end ? pos - end : 0)}, so equal distance means equal
 * START when the dose precedes the names and equal END when it follows them. A prefix pair
 * ({@code estrone} inside {@code estrone sulfate}) ties only in the first arrangement and a suffix pair
 * ({@code clavulanate} inside {@code amoxicillin and clavulanate}) only in the second. A rule written for
 * one leaves the other reporting the same defect, which is what a same-start-index reading of this issue
 * would have shipped.
 *
 * <p><b>One case is the bound, and it is what stops the remedy being "the longer name wins".</b>
 * {@link #twoSubstancesEquallyNearADoseAndNeitherContainingTheOtherBothKeepIt} —
 * {@code Estrone} and {@code Clavulanate} do not nest and their names differ in length, so a blanket
 * tie-breaker would silence one of them while the sub-span rule leaves both — two substances equally near
 * a dose and neither containing the other is genuinely ambiguous prose, and this issue is about a text that
 * is NOT ambiguous. Without a length difference the case would pin nothing, because neither rule would
 * fire.
 *
 * <p>Every case runs the REAL production path: the fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, real question and answer strings.
 */
public class NestedNameDoseTieTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-nested-name-dose-tie.json";

	private static final String QUESTION = "Is estrone or estrone sulfate or clavulanate safe for her?";

	/** Every entry publishes a 1 mg/day ceiling, so 2.5 mg/day must warn once it reaches the substance it
	 *  belongs to — and must warn exactly once, for that substance. */
	private static final String PREFIX_NESTING_DOSE_BEFORE =
			"The usual approach is 2.5 mg of estrone sulfate daily.";

	/** The same nesting with the dose on the other side, where the tie is on the shared END rather than the
	 *  shared start. */
	private static final String SUFFIX_NESTING_DOSE_AFTER =
			"Amoxicillin and clavulanate 2.5 mg daily is the usual approach.";

	/** Two names that do not nest, positioned so the dose sits between them. The lengths differ (7 against
	 *  11), so a blanket longer-wins rule would silence Estrone here while the sub-span rule cannot. */
	private static final String NO_NESTING_DOSE_BETWEEN =
			"Estrone was stopped, 2.5 mg daily, clavulanate was started.";

	/** The substance named TWICE in one clause: once nested inside the combination's name, once on its
	 *  own, immediately after the dose the answer states for it. Judging the substance on a single
	 *  reported occurrence silences it — the nested mention is the nearer of the two, and the
	 *  combination contains it. */
	private static final String NAMED_NESTED_AND_INDEPENDENTLY =
			"Amoxicillin and clavulanate ok, 2.5 mg clavulanate daily.";

	/** The same shape with both of the substance's occurrences at the SAME distance from the dose, so it
	 *  is independently named AT the minimum — and which occurrence a single-occurrence rule reports then
	 *  depends on scan order rather than on anything about the text. */
	private static final String NAMED_TWICE_AT_THE_SAME_DISTANCE =
			"Amoxicillin and clavulanate was at 2.5 mg, clavulanate was stopped.";

	/** Two DIFFERENT substances publishing the same name, so their occurrences are the identical span.
	 *  Neither may disqualify the other: containment has to be strict, or each discards the other and the
	 *  dose goes unwarned by both. */
	private static final String ONE_NAME_TWO_SUBSTANCES =
			"Menotrophin 2.5 mg daily was the regimen.";

	private static List<SafetyWarning> validate(String answer) throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		return DrugReferenceTestSupport.validator(service).validate(answer, QUESTION,
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null));
	}

	@Test
	public void theFixturePosesNestingTheProseRuleItselfFinds() throws Exception {
		// Premises through the production predicates, so no case below can pass on a fixture where the
		// shapes are not actually posed — which would leave the defect unexpressible with every assertion
		// still green.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(FIXTURE);
		DrugReference estrone = DrugReferenceTestSupport.row(entries, "Estrone");
		DrugReference estroneSulfate = DrugReferenceTestSupport.row(entries, "Estrone sulfate");
		DrugReference clavulanate = DrugReferenceTestSupport.row(entries, "Clavulanate");
		DrugReference coAmoxiclav = DrugReferenceTestSupport.row(entries, "Amoxicillin and clavulanate");

		assertFalse(estrone.substanceGroupKey().equals(estroneSulfate.substanceGroupKey()),
				"precondition: the nesting pair must be different SUBSTANCES, or the veto excludes the "
						+ "rival as a sibling and the nesting decides nothing");
		assertFalse(clavulanate.substanceGroupKey().equals(coAmoxiclav.substanceGroupKey()),
				"precondition: and so must the suffix pair");
		assertTrue(estrone.matchesText(PREFIX_NESTING_DOSE_BEFORE.toLowerCase(Locale.ROOT)),
				"precondition: the module's own prose rule says this clause NAMES the shorter substance — "
						+ "that is what makes it a claimant at all, and why this is not issue #260");
		assertTrue(clavulanate.matchesText(SUFFIX_NESTING_DOSE_AFTER.toLowerCase(Locale.ROOT)),
				"precondition: likewise for the suffix pair");
		assertTrue(estrone.matchesText(NO_NESTING_DOSE_BETWEEN.toLowerCase(Locale.ROOT))
				&& clavulanate.matchesText(NO_NESTING_DOSE_BETWEEN.toLowerCase(Locale.ROOT)),
				"precondition: the control clause names BOTH non-nesting substances");
	}

	@Test
	public void aDoseStatedForTheLongerNameIsNotChargedToTheShorterOneItContains() throws Exception {
		List<SafetyWarning> warnings = validate(PREFIX_NESTING_DOSE_BEFORE);

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone sulfate"),
				"the answer states 2.5 mg OF ESTRONE SULFATE, so that substance owns the dose");
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone"),
				"and nothing in the answer doses estrone: it is present only as the longer name's prefix, "
						+ "so charging it this number is a false positive");
	}

	@Test
	public void theSameHoldsWhenTheNestingIsAtTheEndAndTheDoseFollowsIt() throws Exception {
		List<SafetyWarning> warnings = validate(SUFFIX_NESTING_DOSE_AFTER);

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"the clause names the combination and doses it");
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"the constituent is present only as the combination's suffix — the same false positive with "
						+ "the dose on the other side of the name");
	}

	@Test
	public void aNestedMentionIsDiscardedAndTheStandaloneOneIsThenJudgedOnDistanceLikeAnyOther()
			throws Exception {
		List<SafetyWarning> warnings = validate(NAMED_NESTED_AND_INDEPENDENTLY);

		// Discarding the nested mention does not hand the substance the dose: it puts the substance back
		// under the arm's ordinary rule, and here that rule goes against it. The combination's name ENDS
		// five characters before the dose while the standalone mention STARTS seven after it, so the
		// combination is strictly nearer and owns the number. Reading the clause, a human would say the
		// answer doses clavulanate; the distance heuristic disagrees because the combination's phrase is
		// long and finishes just before the number. That disagreement is the heuristic's own limit, not
		// this issue's — closing it would mean preferring a name AFTER a dose over a nearer one before
		// it, which is a different rule needing its own measurement.
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"the constituent's only surviving mention is farther from the dose than the combination's "
						+ "name, so the ordinary strictly-nearer veto takes the number from it");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"and NOTHING is lost by that: the dose is still warned about, attributed to the name the "
						+ "clause puts nearest it. On main the constituent warned as well, but only by "
						+ "treating its nested mention as a naming — the defect this issue removes");
	}

	@Test
	public void andWhenBothOfItsMentionsAreEquallyNearTheStandaloneOneStillDecides() throws Exception {
		List<SafetyWarning> warnings = validate(NAMED_TWICE_AT_THE_SAME_DISTANCE);

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"both of its occurrences sit at the same distance, so the substance IS independently "
						+ "named at the minimum; a rule that reported whichever the scan reached first "
						+ "would decide a clinical warning on alias order");
	}

	@Test
	public void twoSubstancesSharingOneNameDoNotDisqualifyEachOther() throws Exception {
		List<SafetyWarning> warnings = validate(ONE_NAME_TWO_SUBSTANCES);

		// The identical-span case, and the reason containment is STRICT. Relax it to >= and each
		// substance's occurrence contains the other's, so both are discarded and the stated dose is
		// warned about by nobody — the direction this arm never takes.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Menotrophin A"),
				"one published name naming two substances is ambiguous, not nested: both keep the dose");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Menotrophin B"),
				"and the second, which a non-strict containment test would silence along with the first");
	}

	@Test
	public void twoSubstancesEquallyNearADoseAndNeitherContainingTheOtherBothKeepIt() throws Exception {
		List<SafetyWarning> warnings = validate(NO_NESTING_DOSE_BETWEEN);

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone"),
				"neither name contains the other, so the clause really is ambiguous about which substance "
						+ "the number belongs to and the arm keeps both — a rule that preferred the longer "
						+ "name would silence this one on nothing but its length");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"and the longer of the two, which such a rule would have kept");
	}
}
