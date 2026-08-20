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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * <p><b>Containment disqualifies an OCCURRENCE, and it has to disqualify a VETO as well.</b> Dropping
 * the nested occurrence RAISES the substance's nearest distance, so on its own the filter handed the
 * container an ordinary strictly-nearer veto over a mention that is not nested at all: on
 * {@link #NAMED_NESTED_AND_INDEPENDENTLY} the combination's name ends 5 characters before the number and
 * the surviving standalone mention starts 7 after it, so the substance the clause doses BY NAME lost its
 * warning. Where the container publishes no band of its own that is not even a re-attribution — the
 * number goes unwarned by anybody ({@link #CONTAINER_PUBLISHES_NO_BAND}), the direction
 * {@code attributedDoses} forbids. So a rival that is only that near because the edge of its span facing
 * the dose is this substance's own name may not veto it either — and no wider than that, because a
 * container CAN be nearer by a word of its own ({@link #CONTAINER_NEAR_BY_ITS_OWN_WORD}), where barring it
 * would hand this substance a dose the per-row form never gave it. What the rule gives up
 * ({@link #FAR_STANDALONE_MENTION}) and what it does not exempt the substance from
 * ({@link #INDEPENDENT_RIVAL_BETWEEN}) are cases here rather than sentences in a comment.
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
	 *  combination contains it. Observable HERE, which it was not while the container could still veto
	 *  the survivor: reduce {@code DrugReference.namedOccurrences} to its single nearest occurrence and
	 *  this case reddens, so it pins the all-occurrences widening alongside
	 *  {@link #NAMED_TWICE_AT_THE_SAME_DISTANCE} rather than merely agreeing with a single-occurrence
	 *  accessor. */
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

	/** The same nesting with a THIRD substance between the number and the surviving mention. Barring the
	 *  container from vetoing does not exempt the constituent from the ordinary rule: Estrone is an
	 *  independent rival, it is nearer than Clavulanate's surviving mention, and it takes the number —
	 *  which the nested mention, had it counted, would have out-ranked. This is what the containment
	 *  filter costs BEYOND the two cases where the nested mention is the only one, and it is here so that
	 *  cost is an assertion rather than a claim in a comment. */
	private static final String INDEPENDENT_RIVAL_BETWEEN =
			"Amoxicillin and clavulanate 2.5 mg, estrone too, clavulanate later.";

	/** And the cost of the other direction. The container states the dose and the constituent is named on
	 *  its own 22 characters the far side of the number; with the container barred from vetoing, that
	 *  mention keeps the dose. The arm did that before this issue too — the fix neither introduces the
	 *  attribution nor removes it — and closing it means a rule about the number's IMMEDIATE neighbour
	 *  ({@code N mg <name>} outranking a nearer name before it), which is a different rule needing its
	 *  own measurement. Here so that a later change cannot close it in the silent direction unremarked. */
	private static final String FAR_STANDALONE_MENTION =
			"Amoxicillin and clavulanate 2.5 mg daily was fine, clavulanate is renally cleared.";

	/** {@link #NAMED_NESTED_AND_INDEPENDENTLY}'s shape over a pair where the CONTAINER publishes no band
	 *  of its own, so what the container's veto costs is not a re-attribution but the only warning there
	 *  was. The question is its own because this pair is deliberately not part of the nesting cases
	 *  above. */
	private static final String CONTAINER_PUBLISHES_NO_BAND =
			"Dexamethasone and framycetin ok, 2.5 mg framycetin daily.";

	private static final String FRAMYCETIN_QUESTION = "Is framycetin safe for her?";

	/** The SAME nesting as {@link #PREFIX_NESTING_DOSE_BEFORE} with the dose on the other side, and the
	 *  bound on barring a container from vetoing. Here the container's dose-facing edge is its own word
	 *  ({@code sulfate}) and not the constituent's name: it sits 1 character from the number while the
	 *  occurrence it contains sits 9 away, so its nearness is its own and it vetoes like any independent
	 *  rival. Bar it and Estrone would claim a dose stated for Estrone sulfate from the far side of the
	 *  number — an attribution the per-row form never made either, i.e. a false positive of exactly the
	 *  kind this issue removes, introduced by its own fix. */
	private static final String CONTAINER_NEAR_BY_ITS_OWN_WORD =
			"Estrone sulfate 2.5 mg daily, estrone was the metabolite.";

	private static List<SafetyWarning> validate(String answer) throws Exception {
		return validate(answer, QUESTION);
	}

	private static List<SafetyWarning> validate(String answer, String question) throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		return DrugReferenceTestSupport.validator(service).validate(answer, question,
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

		// And the two properties the OTHER two cases rest on, which the assertions above do not reach:
		// both are fixture facts a later edit could break in the direction that leaves every assertion
		// green because the case has gone vacuous. Read through the production locator itself, in the
		// coordinate system attributedDoses establishes (folded clause, dose position taken from it).
		DrugReference menotrophinA = DrugReferenceTestSupport.row(entries, "Menotrophin A");
		DrugReference menotrophinB = DrugReferenceTestSupport.row(entries, "Menotrophin B");
		String shared = DrugReference.foldedLower(ONE_NAME_TWO_SUBSTANCES.toLowerCase(Locale.ROOT));
		List<DrugReference.NamedOccurrence> aSays = menotrophinA.namedOccurrences(shared,
				shared.indexOf("2.5"));
		List<DrugReference.NamedOccurrence> bSays = menotrophinB.namedOccurrences(shared,
				shared.indexOf("2.5"));

		assertFalse(menotrophinA.substanceGroupKey().equals(menotrophinB.substanceGroupKey()),
				"precondition: the shared-name pair must be different SUBSTANCES as well, or the veto "
						+ "skips one as a sibling and the strictness of containment decides nothing");
		assertEquals(1, aSays.size(), "precondition: each is named exactly once in that clause");
		assertEquals(1, bSays.size(), "precondition: and so is the second");
		assertEquals(aSays.get(0).getDistance(), bSays.get(0).getDistance(),
				"precondition: the two occurrences sit at the same distance from the dose");
		assertFalse(aSays.get(0).strictlyContains(bSays.get(0))
				|| bSays.get(0).strictlyContains(aSays.get(0)),
				"precondition: neither containing the other — the IDENTICAL-span shape that case is "
						+ "about. A fixture in which one of the two names nested inside the other would "
						+ "pass it for the opposite reason");

		String control = DrugReference.foldedLower(NO_NESTING_DOSE_BETWEEN.toLowerCase(Locale.ROOT));
		DrugReference.NamedOccurrence estroneAt = estrone
				.namedOccurrences(control, control.indexOf("2.5")).get(0);
		DrugReference.NamedOccurrence clavulanateAt = clavulanate
				.namedOccurrences(control, control.indexOf("2.5")).get(0);

		assertFalse(estroneAt.strictlyContains(clavulanateAt)
				|| clavulanateAt.strictlyContains(estroneAt),
				"precondition: the control's two names must not nest, or the sub-span rule settles it and "
						+ "the case stops being a statement about ambiguity");
		assertNotEquals(estrone.displayLabel().length(), clavulanate.displayLabel().length(),
				"precondition: and they must differ in LENGTH — the fixture publishes each display name "
						+ "as that substance's one alias — or a blanket longer-wins rule would leave both "
						+ "standing here too and the case could not tell the two rules apart");
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
	public void aSubstanceTheClauseAlsoNamesOnItsOwnKeepsTheDoseTheContainerSitsNearer()
			throws Exception {
		List<SafetyWarning> warnings = validate(NAMED_NESTED_AND_INDEPENDENTLY);

		// The container's nearness here is measured on a span that carries the constituent's own name:
		// the combination's phrase ENDS five characters before the number while the surviving standalone
		// mention STARTS seven after it, so on distance alone the combination wins — but only because the
		// occurrence the filter just disqualified is the one it is standing on. Vetoing with it compares
		// the name against itself, and the clause states this number in front of that very name. Here the
		// loss looks like a re-attribution because the combination publishes a band too; the next case is
		// the same shape over a pair where it does not, and there the veto left no warning at all.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"the substance the clause doses by name keeps the number: a rival that is only this near "
						+ "because its span ENDS in this substance's own name may not veto with it");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"and the combination still claims it too — the clause really does name both, so this is "
						+ "the ambiguity the arm keeps rather than the nesting it settles");
	}

	@Test
	public void andWhereTheContainerPublishesNoBandThatDoseIsTheOnlyWarningThereIs() throws Exception {
		List<SafetyWarning> warnings = validate(CONTAINER_PUBLISHES_NO_BAND, FRAMYCETIN_QUESTION);

		// The same shape as the case above with the fixture's cover removed. There the combination
		// published a 1 mg/day band of its own, so the container's veto looked like a re-attribution — the
		// number was still warned about, under the other name. Here the container publishes no band at
		// all, so it is not a claimant for anything: the veto produced a clause containing "2.5 mg
		// framycetin" against a 1 mg/day ceiling and NO warning of any kind. That is why the veto is
		// scoped rather than the loss accepted, and this case is what keeps the reason checkable instead
		// of a sentence about a fixture nobody kept.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Framycetin"),
				"the constituent's own mention, which the clause puts directly behind the number, carries "
						+ "the only ceiling either substance publishes");
		assertEquals(0,
				DrugReferenceTestSupport.overdoseCount(warnings, "Dexamethasone and framycetin"),
				"the container publishes no band, so it warns about nothing and cannot stand in for the "
						+ "warning it would otherwise have taken");
		assertEquals(1, warnings.size(),
				"and that warning is the whole of what this clause raises: with the container allowed to "
						+ "veto, the count here was zero — a lost overdose warning, not a renamed one");
	}

	@Test
	public void aContainerNearTheNumberByItsOwnWordStillTakesTheDose() throws Exception {
		List<SafetyWarning> warnings = validate(CONTAINER_NEAR_BY_ITS_OWN_WORD);

		// The bound on the case above, and the reason the rule is "a rival whose nearness is INHERITED"
		// rather than "a rival that contains one of my occurrences". Same nesting, dose on the other side:
		// the container is 1 character from the number by its own word while the occurrence it contains is
		// 9 away, so it is not standing on this substance's name and there is nothing self-referential
		// about its veto. Measured against the per-row form: it vetoes there too, so keeping the veto here
		// is what makes this fix one-directional; barring every container instead raised an Estrone
		// warning neither form ever raised.
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone"),
				"the constituent's surviving mention is on the far side of the number and the container is "
						+ "beside it by a word of its own, so the ordinary rule stands");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone sulfate"),
				"and the substance the clause doses keeps its warning");
	}

	@Test
	public void anIndependentRivalBetweenTheNumberAndTheSurvivingMentionStillTakesIt() throws Exception {
		List<SafetyWarning> warnings = validate(INDEPENDENT_RIVAL_BETWEEN);

		// What barring the container does NOT do: exempt the substance from the ordinary rule. The nested
		// mention sat one character from the number and would have out-ranked Estrone; the survivor sits
		// twenty-one away, so Estrone takes the dose from Clavulanate. Measured against the per-row form
		// (this arm with the containment filter and the veto scoping both disabled) this is the third of
		// the ten clauses here where the two disagree, and the only one where the substance is named
		// outside a rival's name as well — so the honest statement of the filter's cost is "the nearest
		// occurrence was nested", not "every occurrence was".
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"a rival that contains none of this substance's occurrences still vetoes on distance, and "
						+ "the distance it is compared against is the SURVIVOR's");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"the combination, named nearest of all, owns the number");
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Estrone"),
				"and Estrone does not: it is nearer than Clavulanate's survivor but farther than the "
						+ "combination, which contains none of Estrone's occurrences and so vetoes it");
	}

	@Test
	public void aSurvivingMentionOnTheFarSideOfTheNumberStillClaimsTheContainersDose() throws Exception {
		List<SafetyWarning> warnings = validate(FAR_STANDALONE_MENTION);

		// The price of the rule above, stated as an assertion. Here the combination is what the clause
		// doses and the constituent is named on its own 22 characters the other side of the number; the
		// container cannot veto, no independent rival is named, so the constituent claims it. Measured:
		// the per-row form attributes it exactly the same way, so this is a case #270 leaves open and not
		// one the fix creates. It is pinned so that closing it is a deliberate change with its own
		// measurement — and so that closing it by re-admitting the container's veto, which would also
		// silence the case above, cannot be done quietly.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"the constituent claims a dose stated for the combination — the sub-span rule settles a "
						+ "nested mention, and this mention is not one");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"and the combination, which the clause really does dose, claims it as well");
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
