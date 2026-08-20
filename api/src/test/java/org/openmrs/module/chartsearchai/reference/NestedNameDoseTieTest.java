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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p><b>And it disqualifies a veto from the OTHER side of the nesting too, which barring the container
 * does not reach.</b> With three names nested ({@code potassium} inside {@code clavulanate potassium}
 * inside the combination) the innermost is a rival standing on a span the filter has already discarded:
 * on {@link #RIVAL_NAMED_ONLY_INSIDE_THIS_SUBSTANCE} it vetoed the substance the clause doses by name and
 * nothing else published a ceiling, so that clause raised nothing at all. The bound is
 * {@link #DOSE_STATED_FOR_THE_INNER_NAME}, where the inner name IS stated on its own and keeps the dose —
 * a case rather than an argument, because barring a rival by the relation between the two NAMES instead
 * of by containment in this clause would reverse it.
 *
 * <p><b>Both of the filter's other costs are cases here as well</b>, so the predicate's javadoc claims
 * nothing this file cannot show: a substance whose only mention is nested loses the dose to nobody where
 * the container publishes no band ({@link #ONLY_MENTION_NESTED_AND_NO_BAND}, the accepted cost, and the
 * one that has to be told apart from the silence the veto scoping refuses), and a survivor can simply be
 * out of range ({@link #SURVIVOR_OUT_OF_RANGE}).
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

	/** The round-2 review's finding: the sub-span rule read off the RIVAL rather than off the subject.
	 *  {@code potassium} nests inside {@code clavulanate potassium}, which nests inside the combination,
	 *  so this clause carries the innermost name ONLY inside two longer ones. Dropping the subject's
	 *  nested occurrence raises its nearest distance past the standalone {@code potassium} at the
	 *  container's tail, and the container's own veto is then barred as inherited — so what was left to
	 *  veto the substance the clause doses BY NAME was that {@code potassium}, a name the clause never
	 *  states on its own. The outermost publishes no band, so the number went unwarned by anybody. */
	private static final String RIVAL_NAMED_ONLY_INSIDE_THIS_SUBSTANCE =
			"Amoxicillin and clavulanate potassium ok, 2.5 mg clavulanate potassium daily.";

	/** The BOUND on that rule, and the reason it is containment against THIS clause's occurrences rather
	 *  than anything about the two names. Here {@code potassium} is stated on its own, directly in front
	 *  of the number, and the substance whose name contains it is named later: the inner name is
	 *  independently named here, so it takes the dose and the containing substance does not get it. Bar a
	 *  rival by the relation between the two NAMES instead and this reverses — which is the maximalist
	 *  reading of the finding, and a false attribution of exactly the kind issue #270 removes. */
	private static final String DOSE_STATED_FOR_THE_INNER_NAME =
			"Potassium 2.5 mg daily, clavulanate potassium was stopped.";

	private static final String POTASSIUM_QUESTION =
			"Is potassium or clavulanate potassium safe for her?";

	/** What the containment filter costs in its PLAINEST form, which is also the case it exists for: the
	 *  substance's only mention is nested, so the clause does not name it and it claims nothing — and the
	 *  container publishes no band, so the number is warned about by nobody. Accepted, unlike the same
	 *  silence in {@link #CONTAINER_PUBLISHES_NO_BAND}, because there the clause states the number in
	 *  front of the substance's own name and here it does not. Pinned so the two cannot be confused for
	 *  one, and so that closing this one — which would undo the whole of issue #270 — cannot be done
	 *  quietly. */
	private static final String ONLY_MENTION_NESTED_AND_NO_BAND =
			"Dexamethasone and framycetin 2.5 mg daily is the plan.";

	/** The second of the filter's two indirect costs ({@link #INDEPENDENT_RIVAL_BETWEEN} is the other),
	 *  and the one nothing pinned. The nested mention sits
	 *  1 character from the number; the surviving mention is 150 characters away in the same clause,
	 *  which is outside {@code MAX_ALIAS_TO_DOSE_DISTANCE}, so the substance loses the dose to the range
	 *  check rather than to a rival. Commas are not clause delimiters, which is what lets one clause run
	 *  that long. */
	private static final String SURVIVOR_OUT_OF_RANGE =
			"Amoxicillin and clavulanate 2.5 mg was continued through the admission and the ward team "
					+ "reviewed the chart with pharmacy on the following morning without changing "
					+ "anything, and clavulanate stayed on it.";

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

		// And the THREE-level nest the round-2 cases rest on, read the same way: the innermost name has
		// to be nested inside the middle one, which has to be nested inside the outermost, IN THIS
		// CLAUSE. A pair would not pose it — the finding is about a rival standing on a span the filter
		// has already discarded, and it takes two levels of nesting to discard one.
		DrugReference potassium = DrugReferenceTestSupport.row(entries, "Potassium");
		DrugReference clavPotassium = DrugReferenceTestSupport.row(entries, "Clavulanate potassium");
		DrugReference coAmoxiclavPotassium = DrugReferenceTestSupport.row(entries,
				"Amoxicillin and clavulanate potassium");
		String nest = DrugReference
				.foldedLower(RIVAL_NAMED_ONLY_INSIDE_THIS_SUBSTANCE.toLowerCase(Locale.ROOT));
		int nestDose = nest.indexOf("2.5");
		DrugReference.NamedOccurrence innermost = potassium.namedOccurrences(nest, nestDose).get(0);
		List<DrugReference.NamedOccurrence> middles = clavPotassium.namedOccurrences(nest, nestDose);
		DrugReference.NamedOccurrence outermost = coAmoxiclavPotassium.namedOccurrences(nest, nestDose)
				.get(0);

		assertEquals(2, middles.size(),
				"precondition: the subject is named twice in that clause — once nested, once on its own");
		assertTrue(outermost.strictlyContains(middles.get(0)),
				"precondition: the first of them nested inside the outermost name, or the filter never "
						+ "discards it and the subject's nearest distance never rises above the rival's");
		assertFalse(outermost.strictlyContains(middles.get(1)),
				"precondition: and the second not, or the subject has no surviving occurrence and the "
						+ "case is about the filter rather than about the veto");
		assertTrue(middles.get(0).strictlyContains(innermost),
				"precondition: the rival's occurrence must be a strict sub-span of the DISCARDED one, or "
						+ "the veto it wins is its own and there is nothing to bar");
		assertTrue(innermost.getDistance() < middles.get(1).getDistance(),
				"precondition: and strictly nearer the number than the surviving occurrence, or it never "
						+ "vetoes and the case passes without exercising the bar at all");
		assertNotNull(potassium.bandForAge(30),
				"precondition: the innermost substance must publish a band of its own, or the assertion "
						+ "that it does not warn passes on the fixture rather than on the nesting");
		assertNull(coAmoxiclavPotassium.bandForAge(30),
				"precondition: while the outermost publishes none, which is what makes the lost warning a "
						+ "loss rather than a re-attribution");
		assertNull(
				DrugReferenceTestSupport.row(entries, "Dexamethasone and framycetin").bandForAge(30),
				"precondition: and likewise for the pair the filter's plainest cost is measured over");

		// And the geometry the out-of-range case rests on, which is a property of the SENTENCE and so
		// the one thing an editor could break without noticing. That both mentions are in ONE clause is
		// not asserted here — CLAUSE_DELIMITER is the validator's own and commas are not in it — but it
		// is shown: raise MAX_ALIAS_TO_DOSE_DISTANCE and that case reddens, which it could not do if the
		// far mention were in a clause of its own.
		String longClause = DrugReference.foldedLower(SURVIVOR_OUT_OF_RANGE.toLowerCase(Locale.ROOT));
		List<DrugReference.NamedOccurrence> spread = clavulanate.namedOccurrences(longClause,
				longClause.indexOf("2.5"));

		assertEquals(2, spread.size(),
				"precondition: the long clause names the constituent twice");
		assertEquals(1, spread.get(0).getDistance(),
				"precondition: once one character from the number, nested inside the combination's name, "
						+ "which is the occurrence the filter discards");
		assertEquals(150, spread.get(1).getDistance(),
				"precondition: and once 150 characters away, outside the arm's alias-to-dose cap — trim "
						+ "the sentence and the case silently becomes a duplicate of the rival one");
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
	public void aRivalNamedOnlyInsideThisSubstancesOwnNameDoesNotVetoEither() throws Exception {
		List<SafetyWarning> warnings = validate(RIVAL_NAMED_ONLY_INSIDE_THIS_SUBSTANCE,
				POTASSIUM_QUESTION);

		// The same principle as the case above, read off the RIVAL. Three names nest here, so barring the
		// container's veto is not enough on its own: the subject's nested occurrence goes, the container's
		// inherited veto goes, and the standalone `potassium` at the container's tail — a name this clause
		// carries only inside two longer ones — was left to veto the substance the clause doses by name.
		// Nothing else could pick the number up, because the outermost publishes no band, so the count
		// here was zero.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate potassium"),
				"the substance the clause states the dose for keeps its warning");
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Potassium"),
				"and the innermost name claims nothing: the clause never states it outside a longer name, "
						+ "so it is not independently named here — the same rule that dropped the "
						+ "subject's nested occurrence, applied to a rival's");
		assertEquals(1, warnings.size(),
				"and that is the whole of what this clause raises: with the sub-span rival allowed to "
						+ "veto, the count was zero — a lost overdose warning, not a renamed one");
	}

	@Test
	public void aDoseStatedForTheInnerNameStaysWithItAndNotWithTheNameThatContainsIt() throws Exception {
		List<SafetyWarning> warnings = validate(DOSE_STATED_FOR_THE_INNER_NAME, POTASSIUM_QUESTION);

		// The bound, and the reason the bar is containment against THIS clause's spans rather than a
		// relation between the two names. Measured: widen the bar to every rival and the second assertion
		// here reddens — Clavulanate potassium claims a dose the clause states for potassium alone.
		// Green before this rule as well as after, and the arithmetic is why. Containment bounds a container
		// to no farther than what it contains, so a rival inside one of the subject's occurrences is
		// never strictly nearer than that occurrence, and where that occurrence SURVIVES the filter the
		// bar therefore cannot fire at all. It bites only where the occurrence it stands on was itself
		// discarded, which is the case above.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Potassium"),
				"the clause states the number in front of the inner name, on its own, so that substance "
						+ "owns it");
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate potassium"),
				"and the substance whose name merely contains it does not: a rival named independently "
						+ "here vetoes on distance like any other, however its name relates to this one");
	}

	@Test
	public void aSubstanceWhoseOnlyMentionIsNestedLosesTheDoseToNobody() throws Exception {
		List<SafetyWarning> warnings = validate(ONLY_MENTION_NESTED_AND_NO_BAND, FRAMYCETIN_QUESTION);

		// What the containment filter costs in its plainest form, which is also the case it exists for.
		// The clause doses the combination; the constituent is present only inside that name, so it is
		// not named here and claims nothing. The combination publishes no band, so nothing warns —
		// deliberately, and it is worth pinning because it is the SAME silence the veto scoping refuses
		// two cases up. What separates them is the question that produced it: the filter answers whether
		// the clause names this substance at all, and where it does not there is nothing to hang the
		// number on, exactly as if the knowledge base carried no row for the combination. The veto
		// answers which of two NAMED claimants owns the number, and silence there means the arm found a
		// claimant beside the number and then discarded it.
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Framycetin"),
				"the constituent is named only inside the combination's name, so this clause does not "
						+ "dose it");
		assertEquals(0, warnings.size(),
				"and the combination publishes no ceiling, so the number is warned about by nobody — the "
						+ "filter's intended cost, not a defect in it");
	}

	@Test
	public void aSurvivingMentionOutsideTheDistanceCapLosesTheDoseToo() throws Exception {
		List<SafetyWarning> warnings = validate(SURVIVOR_OUT_OF_RANGE);

		// The filter's other indirect cost, and the one nothing pinned. The nested mention sits 1
		// character from the number and the surviving one 150 away in the same clause, which is outside
		// MAX_ALIAS_TO_DOSE_DISTANCE — so the substance loses the dose to the range check rather than to
		// a rival, which is the branch the predicate's javadoc claims and could not previously show.
		// Raising that cap would re-attribute this number and redden here.
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Clavulanate"),
				"its nested mention is gone and its surviving one is out of range, so it claims nothing");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin and clavulanate"),
				"while the combination the clause really doses keeps the warning");
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
