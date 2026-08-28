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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Which of a substance's ATC codes an order the concept dictionary did NOT classify is compared on —
 * issue #234.
 *
 * <p><b>The defect.</b> After issue #228 the duplicate-therapy class arm reaches every active order,
 * but on two different authorities: a dictionary-MAPPED order is compared on the DICTIONARY's code
 * for its concept, and an unmapped one on every code the reference DATASET files its substance under.
 * The dataset's list covers every presentation the substance is marketed as, and
 * {@code DrugSafetyValidator.sharedClass} prefers a subgroup that is not locally applied (issue
 * #161), so the systemic one always won: an unmapped {@code Hydrocortisone cream 1%} was named as a
 * co-medication in an {@code H02AB} systemic-corticosteroid chip that the same order, mapped to
 * {@code D07AA02}, correctly does not raise. Mapping an order more correctly made the module quieter,
 * and the chip an implementer saw was the wrong one of the two.
 *
 * <p><b>Why the ticket's own suggested remedy is not the fix.</b> It proposed preferring "a row whose
 * administration route matches". Measured through {@link DdiDrugReferenceSource#parse},
 * {@link DrugReference#substanceGroupKey()} and {@link DrugReference#normalizedAtcCodes()} over the
 * shipped 19 MB knowledge base, all 129 of its multi-row substances publish an IDENTICAL ATC code
 * list across every one of their rows and none differs — the invariant
 * {@code DrugSafetyValidator.entryForAtcCode}'s javadoc already states from the other side. Picking a
 * row therefore cannot change any classification, so the narrowing has to be of the CODES.
 *
 * <p>Every case here runs the real {@code DrugSafetyValidator.validate} over a verbatim knowledge-base
 * slice parsed by the real {@link DdiDrugReferenceSource}, or over the shipped knowledge base itself.
 * The order's recorded administration is what drives the narrowing and the order's NAME never is —
 * {@code UnmappedOrderClassPartnerTest.thePartnerIsNamedByTheRowTheORDERRecords} pins a chip for an
 * order literally named {@code Hydrocortisone (topical)}, and that assertion is untouched by this
 * change.
 */
public class UnmappedOrderAdministrationSiteTest {

	/** The four hydrocortisone rows beside a dexamethasone row carrying its whole ATC list — the slice
	 *  {@code UnmappedOrderClassPartnerTest} already uses for this arm, so a chip's wording here can be
	 *  compared with the one it pins. */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS;

	private static final String DEXAMETHASONE_QUESTION = "Is it safe to give dexamethasone?";

	private static final String ASPIRIN_QUESTION = "Is it safe to give aspirin?";

	private static final String DICLOFENAC_QUESTION = "Is it safe to give diclofenac?";

	private static final String GRISEOFULVIN_QUESTION = "Is it safe to give griseofulvin?";

	/** The three sites {@code SITE_TERMS} deliberately gives no recorded term — mouth, gut and
	 *  anorectal, because {@code Oral} and {@code Rectal administration} serve locally-acting and
	 *  systemic preparations alike. */
	private static final Set<String> TERMLESS_SITES = DrugReferenceTestSupport.set(
			DrugReference.SITE_MOUTH, DrugReference.SITE_GUT, DrugReference.SITE_ANORECTAL);

	/** The curated sentence the issue #88 fold appends to the rated aspirin/ketoprofen rule. */
	private static final String NSAID_GROUP_SENTENCE = "Acetylsalicylic acid (aspirin) is in the same"
			+ " cross-reactivity group (NSAID) as active order Ketoprofen — possible additive or"
			+ " duplicate-class therapy";

	/** The ticket's own order name, verbatim. */
	private static final String CREAM = "Hydrocortisone cream 1%";

	/** A recorded route that names the skin and nothing else. NOT {@code Topical}: see
	 *  {@link #aTermNamingMoreThanOneSiteNamesNone}, which is why that word is not a term. */
	private static final String CUTANEOUS = "Cutaneous administration";

	/** The chip the ticket reports as wrong: a topical presentation named in a SYSTEMIC corticosteroid
	 *  class. Worded exactly as the arm produces it today for an order recording no administration. */
	private static final String SYSTEMIC_CHIP = "Dexamethasone is in the same ATC class (H02AB) as"
			+ " active order Hydrocortisone — possible duplicate therapy";

	@Test
	public void aTopicalPresentationIsNotNamedInASystemicDuplicateTherapyChip() throws IOException {
		// The ticket's concrete input -> wrong output. The order is unmapped, so the arm reaches it by
		// name and classifies it on all nine codes hydrocortisone is filed under, of which H02AB09 is
		// the only systemic one. With the chart recording a topical route the presentation is filed
		// under D07AA02/D07XA01, which meet none of dexamethasone's D subgroups (D07AB, D07XB, D10AA),
		// so the honest answer is the one the MAPPED order already gives: nothing.
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, cream(CUTANEOUS))));
	}

	@Test
	public void andTheSameOrderWithNoRecordedAdministrationIsUnchanged() throws IOException {
		// The control, and the half that makes the case above a NARROWING rather than an arm going
		// silent. It is also the state of every order this repo can drive: measured on the 3.7.1
		// standalone, all 46 active drug orders record either "Oral administration" or nothing, and the
		// reference dictionary's 17-member route set names no locally applied site at all.
		assertEquals(Collections.singletonList(SYSTEMIC_CHIP),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, cream())));
	}

	@Test
	public void anOphthalmicPresentationIsStillNamedInTheOphthalmicClass() throws IOException {
		// What separates "narrow to the site the chart records" from "drop every locally applied
		// claim". Two ophthalmic corticosteroids ARE duplicate therapy, and the chip has to say so —
		// naming the class they actually share rather than the systemic one neither presentation is.
		//
		// The route name is the reference dictionary's own spelling, which names the eye without ever
		// using the word "ophthalmic": the term list has to match what a dictionary records, not what
		// ATC calls the group.
		assertEquals(
				Collections.singletonList("Dexamethasone is in the same ATC class (S01BA) as active"
						+ " order Hydrocortisone — possible duplicate therapy"),
				DrugReferenceTestSupport
						.details(chips(DEXAMETHASONE_QUESTION, cream("Bilateral eye administration"))));
	}

	@Test
	public void aSiteTheSubstanceIsNotFiledUnderNarrowsNothing() throws IOException {
		// The decline branch, and the reason it is a decline and not a suppression. Hydrocortisone
		// publishes no G01/G02CC code, so a vaginal presentation of it is one this dataset does not
		// classify — and its substance-level classification is then the only one there is. Narrowing to
		// an empty set would silence the arm rather than correct it, which is the direction a safety
		// net must not fail in.
		//
		// The premise is asserted rather than assumed: if hydrocortisone did carry a vaginal code this
		// case would be testing the narrowing instead of the decline.
		DrugReference hydrocortisone = DrugReferenceTestSupport
				.row(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE), "Hydrocortisone");
		assertTrue(DrugReference.codesAtSites(hydrocortisone.normalizedAtcCodes(),
				Collections.singleton(DrugReference.SITE_VAGINA)).isEmpty(),
				"the slice must file no vaginal code, or this case pins the wrong branch");

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP), DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION, cream("Vaginal administration"))));
	}

	@Test
	public void aSystemicRouteThatContainsASiteWordNarrowsNothing() throws IOException {
		// The first two spellings are the boundary case and are why this method exists: "Subcutaneous
		// administration" is a member of the reference dictionary's own route set and it CONTAINS the
		// skin term "cutaneous", refused only by containsWord's left boundary — and the hyphenated
		// spelling defeats that boundary on its own, because containsWord accepts any non-alphanumeric
		// there, which is why recordedSites strips hyphens before matching. Both, or the guard pins only
		// the half that never needed one.
		//
		// The middle two are ordinary systemic routes; they are here as the commonest real recorded
		// values (32 of the 3.7.1 demo's 46 active orders say "Oral administration"), and they reach
		// ROUTES_OF_ENTRY rather than merely failing to match a term.
		//
		// The last is why that refusal has to exist at all rather than being an absence: "Transdermal
		// skin patch" CONTAINS the term "skin", so with no refusal it read a systemic presentation as a
		// skin one and silenced a real chip. What the refusal still does not reach is the spaced
		// spelling "sub cutaneous", stated on ROUTES_OF_ENTRY rather than claimed away.
		for (String route : Arrays.asList("Subcutaneous administration", "Sub-cutaneous administration",
				"Transdermal administration", "Oral administration", "Transdermal skin patch")) {
			assertEquals(Collections.singletonList(SYSTEMIC_CHIP),
					DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, cream(route))),
					route + " names no site this module can attribute");
		}
	}

	@Test
	public void aTermNamingMoreThanOneSiteNamesNone() throws IOException {
		// Why "topical" is not a term, and it is the same rule as the form words below rather than a
		// separate exception. ATC uses the word for the anorectum as well as the skin — this repo's own
		// LOCALLY_APPLIED_ATC_GROUPS javadoc quotes C05A "Antihemorrhoidals for topical use" — so read
		// as the skin it SILENCES a claim, which is the direction this rule must not fail in.
		//
		// The order is an EYE preparation recorded with the route "Topical". Read as the skin its
		// ophthalmic codes are dropped and the true S01BA chip disappears; naming no site, the arm keeps
		// the answer it had. Both halves asserted, or the second alone would also pass on an arm that
		// never ran.
		assertEquals(
				Collections.singletonList("Dexamethasone is in the same ATC class (S01BA) as active"
						+ " order Hydrocortisone — possible duplicate therapy"),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
						order("Hydrocortisone eye ointment", "Bilateral eye administration"))),
				"recorded at the eye, the ophthalmic class is what the two share");

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP), DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION,
						order("Hydrocortisone eye ointment", "Topical"))),
				"and recorded only as topical, the arm keeps the answer it had rather than guessing"
						+ " the skin");
	}

	@Test
	public void anotherOrdersRouteDoesNotDecideThisSubstancesClassification() throws IOException {
		// The scope conjunct in codesForThisSubstancesPresentations: the terms are gathered from the
		// orders that name THIS substance, not from every unmapped order. Without it the oral route of
		// an unrelated second prescription declines the narrowing for the first, and the chip this issue
		// removes comes back.
		Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
		Set<String> otherNames = DrugReferenceTestSupport.set("Omeprazole 20mg capsule");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(CREAM, "Omeprazole 20mg capsule"), null, null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-234-cream", CREAM, creamNames, null,
								DrugReferenceTestSupport.set(CUTANEOUS)),
						DrugReferenceTestSupport.activeOrder("order-234-other",
								"Omeprazole 20mg capsule", otherNames, null,
								DrugReferenceTestSupport.set("Oral administration"))));

		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, context)),
				"the omeprazole order's route says nothing about the hydrocortisone one");
	}

	@Test
	public void aSiteWhoseOnlyCodeThereIsAnS03OneStillClaimsIt() {
		// S03 "Ophthalmological and otological preparations" is the one group two sites share, and it is
		// what the first draft of the table dropped. The partition guard below cannot see a HALF-drop —
		// it only asks that SOME site claim each code — so the dual membership is pinned here, on both
		// sides, over the shipped knowledge base's own neomycin row.
		DrugReference neomycin = DrugReferenceTestSupport.row(DrugReferenceTestSupport.shippedEntries(),
				"Neomycin");
		assertTrue(neomycin.normalizedAtcCodes().contains("S03AA01"),
				"the premise: the shipped row files an S03 code, was: " + neomycin.normalizedAtcCodes());

		assertTrue(DrugReference.codesAtSites(neomycin.normalizedAtcCodes(),
				Collections.singleton(DrugReference.SITE_EYE)).contains("S03AA01"),
				"S03 is an eye group");
		assertTrue(DrugReference.codesAtSites(neomycin.normalizedAtcCodes(),
				Collections.singleton(DrugReference.SITE_EAR)).contains("S03AA01"),
				"and an ear one");
	}

	@Test
	public void aRouteOfEntryRefusesTheSiteADoseFormNames() throws IOException {
		// The granularity of the ROUTES_OF_ENTRY refusal, pinned because it is a choice and not an
		// accident. An order records a route AND a dose form, and this refuses on either: a record
		// naming a route of entry beside a site of action contradicts itself, and narrowing on the half
		// one prefers would silence a chip on the strength of a record the module cannot reconcile.
		//
		// It costs the two-source design its one contradictory case — the dose form is otherwise the
		// only leg that reaches the skin, since the reference dictionary has no cutaneous route. The
		// first half shows the form alone does narrow, so the second is a refusal and not a form the
		// module cannot read.
		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION, order(CREAM, "Cutaneous cream"))),
				"the dose form alone names the skin and narrows");

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP), DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION,
						order(CREAM, "Oral administration", "Cutaneous cream"))),
				"and a route of entry beside it declines for the whole record");
	}

	@Test
	public void aFormWordThatServesSeveralSitesNamesNone() throws IOException {
		// Why "cream", "ointment" and "lotion" are not terms. A cream is made for the skin, the vagina
		// or the anorectum alike, so reading one as "skin" asserts something the record did not say —
		// and reading it as all three is worse rather than more cautious: hydrocortisone's C05AA01 then
		// survives, dexamethasone publishes C05AA09, and a chip that said H02AB says C05AA instead,
		// which is a haemorrhoid preparation and no more true. Asserted over the real codes so the
		// second half of that sentence is measured rather than argued.
		DrugReference hydrocortisone = DrugReferenceTestSupport
				.row(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE), "Hydrocortisone");
		Set<String> threeSites = new LinkedHashSet<String>(Arrays.asList(DrugReference.SITE_SKIN,
				DrugReference.SITE_VAGINA, DrugReference.SITE_ANORECTAL));
		assertTrue(DrugReference.codesAtSites(hydrocortisone.normalizedAtcCodes(), threeSites)
				.contains("C05AA01"), "the union reading keeps the haemorrhoid code");

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP), DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION, cream("Cream"))));
	}

	@Test
	public void aCuratedGroupSentenceIsNarrowedAwayWhileItsRatedRuleSurvives() throws IOException {
		// partner.codes has three consumers and this case reaches the other two in one chip, because the
		// issue #88 fold puts them there: a rated interaction rule and a curated cross-reactivity
		// sentence about the same co-medication.
		//
		//   - CrossReactivityGroup.sharedGroupForCodes reads partner.codes, so the curated sentence
		//     narrows with everything else. Ketoprofen is M01AE03 systemic and M02AA10 topical, and the
		//     shipped NSAID group is {M01AE, N02BA}: recorded at the skin the group no longer reaches it,
		//     and a curated claim about a presentation the patient was not given is the same defect one
		//     arm along.
		//   - ruleAbout reads partner.codes too, to correlate the rule to the partner for that fold. It
		//     must still find it — every row of a substance publishes the same ATC list, so M02AA10
		//     resolves ketoprofen exactly as M01AE03 did — and the rated sentence must survive verbatim.
		//     A narrowing that silenced a Moderate interaction would be a far worse defect than the one
		//     this change fixes.
		//
		// Over the shipped knowledge base rather than a slice: the pair has to be one the shipped
		// curated group actually joins, and that group ships with the module.
		List<String> withNothingRecorded = DrugReferenceTestSupport
				.details(shippedChips(ASPIRIN_QUESTION, ketoprofen()));
		assertEquals(1, withNothingRecorded.size(), "was: " + withNothingRecorded);
		assertTrue(withNothingRecorded.get(0).endsWith(NSAID_GROUP_SENTENCE),
				"the control: with nothing recorded the curated sentence is folded on, was: "
						+ withNothingRecorded.get(0));

		List<String> recordedAtTheSkin = DrugReferenceTestSupport
				.details(shippedChips(ASPIRIN_QUESTION, ketoprofen(CUTANEOUS)));
		assertEquals(1, recordedAtTheSkin.size(), "the rated rule must still chip, was: "
				+ recordedAtTheSkin);
		assertFalse(recordedAtTheSkin.get(0).contains("cross-reactivity group"),
				"the curated sentence claims a class this presentation is not in");
		// The rated sentence survives word for word — with ONE consequence that is worth pinning rather
		// than smoothing over. Losing the class sentence unfolds the chip, and an unfolded rule chip is
		// labelled by the rule's own match token (partnerLabel) where a folded one takes the name the
		// two arms reconciled (foldedPartnerLabel, issue #292). So the co-medication is named
		// "ketoprofen" here and "Ketoprofen" in the control. One order, one name per chip either way;
		// what moves is which of the two rules decides it, and it moves because the fold stopped, not
		// because anything about naming changed.
		assertEquals(withNothingRecorded.get(0)
				.substring(0, withNothingRecorded.get(0).length() - NSAID_GROUP_SENTENCE.length()).trim()
				.replace("active order Ketoprofen", "active order ketoprofen"),
				recordedAtTheSkin.get(0),
				"the rated rule's own sentence is untouched but for the label the fold no longer supplies");
	}

	@Test
	public void aClassTheRecordedSiteDoesShareIsStillNamed() throws IOException {
		// The pair to the case above, over the same order: narrowing removes a claim only where the
		// recorded site does not support it. Diclofenac publishes M02AA15 and ketoprofen M02AA10, so the
		// two share a subgroup that IS the skin — and that chip reads the same before and after.
		assertEquals(DrugReferenceTestSupport.details(shippedChips(DICLOFENAC_QUESTION, ketoprofen())),
				DrugReferenceTestSupport
						.details(shippedChips(DICLOFENAC_QUESTION, ketoprofen(CUTANEOUS))));
		assertTrue(DrugReferenceTestSupport.details(shippedChips(DICLOFENAC_QUESTION,
				ketoprofen(CUTANEOUS))).get(0).endsWith("is in the same ATC class (M02AA) as active"
						+ " order Ketoprofen — possible duplicate therapy"),
				"…and it is the class arm that is speaking, not only the rule arm");
	}

	@Test
	public void aSecondPresentationOfOneSubstanceIsNotSilencedByTheFirstInListOrder() throws IOException {
		// Two unmapped orders of ONE substance at different sites. The partner is keyed on the substance
		// (issue #186), so the two are one co-medication and alreadyACoMedication skips the second before
		// its terms are ever read — which made the FIRST order OrderService returned decide the
		// classification for both. Asserted as an equality between the two sequences rather than as two
		// literals, so neither side can be corrected into agreement on its own, and asserted non-empty
		// too, or an arm silenced altogether would satisfy the equality.
		//
		// The patient IS on systemic hydrocortisone in both runs; the cream must not speak for the
		// tablet. Note what this case does NOT pin: its tablet records "Oral administration", which
		// ROUTES_OF_ENTRY refuses before the whole-substance decline is reached, so the decline itself
		// is pinned by aPresentationTheModuleCannotPlaceDeclinesForTheWholeSubstance instead.
		List<String> creamFirst = DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
				twoPresentations(true)));
		List<String> tabletFirst = DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
				twoPresentations(false)));

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP), tabletFirst,
				"a systemic presentation the chart records must still be named");
		assertEquals(tabletFirst, creamFirst,
				"and which order OrderService returned first must not decide it");
	}

	@Test
	public void aPresentationTheModuleCannotPlaceDeclinesForTheWholeSubstance() throws IOException {
		// The case above passes for a reason that is NOT this guard: its tablet records "Oral
		// administration", which ROUTES_OF_ENTRY refuses inside recordedSites before the decline is ever
		// reached. What pins the decline is a second presentation whose terms name no site AND are not a
		// route of entry — a bare dose form, or nothing at all, which is the shape 14 of the 3.7.1
		// demo's 46 active orders are in.
		//
		// Without the decline the cream speaks for the tablet and the systemic chip disappears for a
		// patient who is on systemic hydrocortisone. Both shapes, because "records a form that names no
		// site" and "records nothing" reach it by different roads.
		for (Set<String> tabletTerms : Arrays.asList(DrugReferenceTestSupport.set("Tablet"),
				Collections.<String> emptySet())) {
			Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
			Set<String> tabletNames = DrugReferenceTestSupport.set("Hydrocortisone 20mg tablet");
			PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
					DrugReferenceTestSupport.set(CREAM, "Hydrocortisone 20mg tablet"), null, null, null,
					Arrays.asList(
							DrugReferenceTestSupport.activeOrder("order-234-cream", CREAM, creamNames,
									null, DrugReferenceTestSupport.set(CUTANEOUS)),
							DrugReferenceTestSupport.activeOrder("order-234-tablet",
									"Hydrocortisone 20mg tablet", tabletNames, null, tabletTerms)));

			assertEquals(Collections.singletonList(SYSTEMIC_CHIP),
					DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, context)),
					"a presentation the module cannot place must not be narrowed away by one it can,"
							+ " recorded as: " + tabletTerms);
		}
	}

	@Test
	public void aPresentationAtASiteTheDatasetDoesNotFileDeclinesForTheWholeSubstanceToo() throws IOException {
		// The distinction narrowsAnyCode draws, and the one a weaker guard misses. Hydrocortisone
		// publishes no R01 code, so a NASAL presentation of it is one this module can place and the
		// dataset cannot express — the terms name a site, and narrow nothing.
		//
		// Alone, that order declines and the systemic chip stands. Beside a cutaneous cream it must
		// still decline: the union of {nose, skin} keeps the cream's D codes, so a guard asking only
		// "do the terms name a site" would let the cream's narrowing carry the nasal order with it and
		// the chip would disappear for a patient who is on nasal hydrocortisone.
		// BOTH halves of the premise, asserted rather than assumed — without the first the case would
		// still pass with "Nasal administration" naming no site at all, which is the ordinary
		// names-no-site decline another case already pins and not the distinction this one is for.
		assertTrue(DrugReference.narrowsAnyCode(DrugReferenceTestSupport.set("R01AA08", "H02AB09"),
				DrugReferenceTestSupport.set("Nasal administration")),
				"the premise: the recorded route names a site, and narrows a substance filed there");
		Set<String> hydrocortisone = DrugReferenceTestSupport
				.row(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE), "Hydrocortisone")
				.normalizedAtcCodes();
		assertFalse(DrugReference.narrowsAnyCode(hydrocortisone,
				DrugReferenceTestSupport.set("Nasal administration")),
				"and the second half: THIS substance is filed under no code at that site");

		Set<String> nasalNames = DrugReferenceTestSupport.set("Hydrocortisone nasal preparation");
		Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
		PatientClinicalContext.ActiveDrugOrder nasal = DrugReferenceTestSupport.activeOrder(
				"order-234-nasal", "Hydrocortisone nasal preparation", nasalNames, null,
				DrugReferenceTestSupport.set("Nasal administration"));
		PatientClinicalContext.ActiveDrugOrder cream = DrugReferenceTestSupport.activeOrder(
				"order-234-cream", CREAM, creamNames, null, DrugReferenceTestSupport.set(CUTANEOUS));

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
						DrugReferenceTestSupport.ctx(60, null, nasalNames, null, null, null,
								Collections.singletonList(nasal)))),
				"the nasal order alone: the dataset files no R01 code, so nothing is narrowed");

		assertEquals(Collections.singletonList(SYSTEMIC_CHIP),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Hydrocortisone nasal preparation", CREAM),
								null, null, null, Arrays.asList(nasal, cream)))),
				"and a cream beside it must not carry it into the skin");
	}

	@Test
	public void twoPresentationsTheModuleCanBothPlaceNarrowToTheUnionOfTheirSites() throws IOException {
		// Where EVERY order of the substance is one the data can express, there is nothing to decline
		// and the narrowing is the UNION of what they name. Three assertions, because the obvious one
		// pins less than it looks: eye-only and skin-plus-eye produce the SAME chip here (dexamethasone
		// shares no D07AA/D07XA subgroup with hydrocortisone), so asserting the chip alone would also
		// pass for an implementation that kept the LAST order's terms and threw the rest away.
		//
		// So the union is asserted where it is visible — on the codes — and the order-independence by
		// running both sequences, which is the property codesForThisSubstancesPresentations exists for
		// and the only thing that separates a union from last-order-wins.
		Set<String> hydrocortisone = DrugReferenceTestSupport
				.row(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE), "Hydrocortisone")
				.normalizedAtcCodes();
		Set<String> union = DrugReference.codesForRecordedAdministration(hydrocortisone,
				DrugReferenceTestSupport.set(CUTANEOUS, "Bilateral eye administration"));
		assertTrue(union.contains("D07AA02") && union.contains("S01BA02"),
				"the union keeps a code from each site, was: " + union);

		List<String> creamFirst = DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION, creamAndEyePreparation(true)));
		List<String> dropFirst = DrugReferenceTestSupport
				.details(chips(DEXAMETHASONE_QUESTION, creamAndEyePreparation(false)));

		assertEquals(
				Collections.singletonList("Dexamethasone is in the same ATC class (S01BA) as active"
						+ " order Hydrocortisone — possible duplicate therapy"),
				dropFirst);
		assertEquals(dropFirst, creamFirst,
				"and which order OrderService returned first must not decide it");
	}

	@Test
	public void aGroupNamedForSystemicUseIsNotKeptByTheSiteItsPrefixSitsUnder() throws IOException {
		// The conjunct that would otherwise be free to delete: codesAtSites asks
		// isLocallyAppliedAtcCode as well as the site's group prefixes, so the SYSTEMIC_USE_EXCEPTIONS
		// nested inside a matched site are honoured without a second copy of that list.
		//
		// Terbinafine is D01AE15 (topical antifungal) and D01BA02 — and D01B is "Antifungals for
		// systemic use", which sits under the D prefix the skin claims. Griseofulvin publishes D01BA01,
		// so without the conjunct a topical terbinafine cream would be reported as duplicating an oral
		// griseofulvin. Both halves are asserted, because the emptiness alone would also be satisfied by
		// an arm that never ran.
		assertEquals(
				Collections.singletonList("Griseofulvin is in the same ATC class (D01BA) as active order"
						+ " Terbinafine — possible duplicate therapy"),
				DrugReferenceTestSupport.details(shippedChips(GRISEOFULVIN_QUESTION, terbinafine())),
				"the control: unnarrowed, the systemic antifungal class is what the two share");

		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport
				.details(shippedChips(GRISEOFULVIN_QUESTION, terbinafine(CUTANEOUS))));
	}

	@Test
	public void anOrderNamedOnlyByItsCodesCarriesWhatTheChartRecordsToo() {
		// The issue #290 rung. Nothing reads these terms on an order the builder puts here — such an
		// order always carries ATC codes, so the code walk groups it and the name-resolution leg never
		// sees it — and the factory carries them anyway, for the reason its javadoc gives.
		//
		// This pins the OVERLOAD only. The builder's use of it is pinned separately, through the real
		// build(), by ActiveOrderAdministrationTermsTest.anOrderNoNameCouldBeReadForCarriesItTooThroughTheRealBuilder
		// — without that one, dropping the argument at the call site left the whole suite green.
		assertEquals(DrugReferenceTestSupport.set("nasal administration"),
				PatientClinicalContext.ActiveDrugOrder
						.namedByCodesOnly("order-234-codes", "[ATC R01AA05]",
								DrugReferenceTestSupport.set("R01AA05"),
								DrugReferenceTestSupport.set("Nasal administration"))
						.getAdministrationTerms());
		assertEquals(Collections.<String> emptySet(),
				PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-234-codes",
						"[ATC R01AA05]", DrugReferenceTestSupport.set("R01AA05"))
						.getAdministrationTerms(),
				"and the overload that says nothing about administration keeps meaning nothing recorded");
	}

	@Test
	public void everyLocallyAppliedCodeInTheShippedKnowledgeBaseIsAccountedFor() {
		// The partition guard, and the case that would have caught this change's own first draft: it
		// mapped the eye to S01 and the ear to S02 and dropped S03 "Ophthalmological and otological
		// preparations" altogether, so neomycin's ear codes {S02AA07, S03AA01} would have had one of
		// them discarded by a rule whose whole purpose is to keep the site's own codes.
		//
		// Every locally applied code the shipped knowledge base publishes must be claimed by some site
		// or be one of the three groups whose published name states a property rather than a place.
		// Driven through the production predicates over real data — a DATA guard and not a structural
		// one, so a site group absent from this knowledge base would still escape it.
		List<String> unaccounted = new java.util.ArrayList<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			for (String code : entry.normalizedAtcCodes()) {
				if (!DrugReference.isLocallyAppliedAtcCode(code)
						|| DrugReference.namesNoAdministrationSite(code)) {
					continue;
				}
				if (DrugReference.codesAtSites(Collections.singleton(code),
						DrugReference.administrationSites()).isEmpty()) {
					unaccounted.add(code);
				}
			}
		}
		assertEquals(Collections.<String> emptyList(), unaccounted,
				"every locally applied code must fall under a site or under a group naming none");

		// The escape hatch has to be pinned too, or widening it is how a later unaccounted group gets
		// "fixed": this guard SKIPS a code namesNoAdministrationSite admits, so that predicate must
		// stay false of every group a site does claim.
		for (String code : DrugReferenceTestSupport.set("A01AC03", "C05AA01", "D07AA02", "G01AF01",
				"M02AA10", "P03AB02", "R01AA08", "R02AD02", "R03AC08", "S01BA02", "S02AA07",
				"S03AA01")) {
			assertFalse(DrugReference.namesNoAdministrationSite(code),
					code + " is claimed by a site and must not be excused as naming none");
		}
	}

	@Test
	public void everyLocallyAppliedGroupIsInOneHalfOfThePartitionOrTheOther() {
		// The STRUCTURAL half of the partition claim, and the only thing that can see a group left out
		// of BOTH halves. The data guard above walks the shipped knowledge base's codes, so a group that
		// knowledge base publishes nothing under escapes it entirely — demonstrated by adding V07AY to
		// LOCALLY_APPLIED_ATC_GROUPS and to neither half, which left the whole build green.
		//
		// Prefix-related in EITHER direction: the list writes S at main-group granularity and the site
		// table expands it into S01/S02/S03, which are longer; a shorter site prefix would cover a
		// longer member the other way round.
		for (String group : DrugReference.locallyAppliedGroups()) {
			boolean claimed = DrugReference.namesNoAdministrationSite(group);
			for (String site : DrugReference.administrationSites()) {
				for (String prefix : DrugReference.groupsForSite(site)) {
					claimed = claimed || prefix.startsWith(group) || group.startsWith(prefix);
				}
			}
			assertTrue(claimed, group + " is locally applied but belongs to no site and is not recorded"
					+ " as naming none — the partition has a hole");
		}

		// And the DISJOINTNESS half, which the OR above cannot see: a group claimed by a site must not
		// ALSO be excused as naming none. It matters because that excuse is an escape hatch — the data
		// guard above SKIPS a code namesNoAdministrationSite admits — so a site group wrongly listed
		// there would silently stop that guard checking the very codes it exists for. Derived from the
		// two constants rather than from a hand-listed set of codes, so it cannot go stale as sites are
		// added.
		for (String site : DrugReference.administrationSites()) {
			for (String prefix : DrugReference.groupsForSite(site)) {
				assertFalse(DrugReference.namesNoAdministrationSite(prefix),
						prefix + " is claimed by site " + site + " and must not also be excused as"
								+ " naming none — the partition's halves overlap");
			}
		}
	}

	@Test
	public void everySiteASpelledTermCanSelectClaimsCodesInTheShippedKnowledgeBase() {
		// The other direction of the partition. A site with terms but no groups would be a term list
		// that can never narrow anything — a recorded route silently taken as evidence and then dropped
		// — and a site with groups nobody spells is dead weight. Over the shipped knowledge base so that
		// "claims codes" is a fact about the data rather than about the table.
		Set<String> shipped = new LinkedHashSet<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			shipped.addAll(entry.normalizedAtcCodes());
		}
		for (String site : DrugReference.administrationSites()) {
			if (DrugReference.termsForSite(site).isEmpty()) {
				// Emptying a term list is the drift this case is written against, so the skip states
				// which sites may take it rather than trusting the emptiness itself.
				assertTrue(TERMLESS_SITES.contains(site),
						site + " has no recorded term that can select it, and is not one of the three"
								+ " sites whose terms are deliberately absent");
				continue;
			}
			assertFalse(DrugReference.codesAtSites(shipped, Collections.singleton(site)).isEmpty(),
					site + " is selectable by a recorded term and must claim at least one code");
		}
	}

	/** One recorded spelling that selects each site a term CAN select, beside one ATC code that site's
	 *  groups claim and no other site's do. Real dictionary spellings, so the pairing is a statement
	 *  about the vocabulary a chart actually carries; the codes are real level-5 codes of a locally
	 *  applied presentation at that site. {@code H02AB09} rides along in the pool as the systemic
	 *  control no site may keep. */
	private static final Map<String, String[]> ONE_TERM_AND_ONE_CODE_PER_SITE;

	static {
		Map<String, String[]> pairs = new LinkedHashMap<String, String[]>();
		pairs.put(DrugReference.SITE_SKIN, new String[] { CUTANEOUS, "D07AA02" });
		pairs.put(DrugReference.SITE_EYE, new String[] { "Bilateral eye administration", "S01BA02" });
		pairs.put(DrugReference.SITE_EAR, new String[] { "Bilateral ear administration", "S02AA07" });
		pairs.put(DrugReference.SITE_NOSE, new String[] { "Nasal administration", "R01AA07" });
		pairs.put(DrugReference.SITE_THROAT, new String[] { "Oropharyngeal spray", "R02AA20" });
		pairs.put(DrugReference.SITE_AIRWAY, new String[] { "Inhalation", "R03AC02" });
		pairs.put(DrugReference.SITE_VAGINA, new String[] { "Vaginal administration", "G01AF02" });
		ONE_TERM_AND_ONE_CODE_PER_SITE = Collections.unmodifiableMap(pairs);
	}

	@Test
	public void eachSpelledSiteKeepsItsOwnGroupsCodeAndNoOtherSitesCode() {
		// The two partition guards below relate the table to LOCALLY_APPLIED_ATC_GROUPS and to the
		// shipped knowledge base, but neither relates a GROUP to the RIGHT SITE: mutating nose from
		// {R01} to {R01, R03A} passed the whole build, and under it an order recorded at the nose keeps
		// an inhalant's codes and can be named in a duplicate-therapy chip against an inhaled
		// co-medication. Skin, eye and ear had a case tying a recorded term to the codes it keeps; the
		// other four sites a term can select had none.
		//
		// One recorded term against a pool holding a code of EVERY site, so each assertion is both
		// halves at once — this site's code survives, and no other site's does. A group moved or
		// duplicated between two sites reddens both of them: the site that gained it keeps a code it
		// must not, and the site that lost it keeps nothing, which codesForRecordedAdministration's
		// decline then turns into the whole pool.
		Set<String> pool = new LinkedHashSet<String>();
		pool.add("H02AB09");
		for (String[] pair : ONE_TERM_AND_ONE_CODE_PER_SITE.values()) {
			pool.add(pair[1]);
		}

		for (String site : DrugReference.administrationSites()) {
			String[] pair = ONE_TERM_AND_ONE_CODE_PER_SITE.get(site);
			if (pair == null) {
				// Exhaustive over the table, so a site added with terms but no case here reddens rather
				// than being silently unrelated to any code — which is the state this case is written
				// against.
				assertTrue(DrugReference.termsForSite(site).isEmpty(),
						site + " can be selected by a recorded term and has no term-to-code case");
				continue;
			}
			assertEquals(DrugReferenceTestSupport.set(pair[1]),
					DrugReference.codesForRecordedAdministration(pool,
							DrugReferenceTestSupport.set(pair[0])),
					"a record naming the " + site + " must keep that site's code and no other's, from: "
							+ pool);
		}
	}

	private static PatientClinicalContext cream(String... administration) {
		return order(CREAM, administration);
	}

	private static PatientClinicalContext ketoprofen(String... administration) {
		return order("Ketoprofen 2.5% preparation", administration);
	}

	private static PatientClinicalContext terbinafine(String... administration) {
		return order("Terbinafine 1% preparation", administration);
	}

	/** One patient, two unmapped hydrocortisone orders — a cutaneous cream and an eye preparation — in
	 *  either sequence, which is the only thing that differs between the two contexts. */
	private static PatientClinicalContext creamAndEyePreparation(boolean creamFirst) {
		Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
		Set<String> dropNames = DrugReferenceTestSupport.set("Hydrocortisone eye preparation");
		PatientClinicalContext.ActiveDrugOrder cream = DrugReferenceTestSupport.activeOrder(
				"order-234-cream", CREAM, creamNames, null, DrugReferenceTestSupport.set(CUTANEOUS));
		PatientClinicalContext.ActiveDrugOrder drop = DrugReferenceTestSupport.activeOrder(
				"order-234-drop", "Hydrocortisone eye preparation", dropNames, null,
				DrugReferenceTestSupport.set("Bilateral eye administration"));
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(CREAM, "Hydrocortisone eye preparation"), null, null, null,
				creamFirst ? Arrays.asList(cream, drop) : Arrays.asList(drop, cream));
	}

	/** One patient, two unmapped hydrocortisone orders — a topical cream and an oral tablet — in either
	 *  sequence, which is the only thing that differs between the two contexts. */
	private static PatientClinicalContext twoPresentations(boolean creamFirst) {
		Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
		Set<String> tabletNames = DrugReferenceTestSupport.set("Hydrocortisone 20mg tablet");
		PatientClinicalContext.ActiveDrugOrder cream = DrugReferenceTestSupport
				.activeOrder("order-234-cream", CREAM, creamNames, null,
						DrugReferenceTestSupport.set(CUTANEOUS));
		PatientClinicalContext.ActiveDrugOrder tablet = DrugReferenceTestSupport
				.activeOrder("order-234-tablet", "Hydrocortisone 20mg tablet", tabletNames, null,
						DrugReferenceTestSupport.set("Oral administration"));
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(CREAM, "Hydrocortisone 20mg tablet"), null, null, null,
				creamFirst ? Arrays.asList(cream, tablet) : Arrays.asList(tablet, cream));
	}

	/** One unmapped active order — no ATC codes, so the arm reaches it only by name — carrying whatever
	 *  the chart records about where it is applied. */
	private static PatientClinicalContext order(String name, String... administration) {
		Set<String> names = DrugReferenceTestSupport.set(name);
		return DrugReferenceTestSupport.ctx(60, null, names, null, null, null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder("order-234", name, names,
						null, DrugReferenceTestSupport.set(administration))));
	}

	private static List<SafetyWarning> chips(String question, PatientClinicalContext context)
			throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", question, context);
	}

	/** The whole shipped knowledge base carrying the real curated groups — NOT
	 *  {@code DrugReferenceTestSupport.ddinterServiceWithGroups}, which is the 16-drug excerpt and
	 *  carries no ketoprofen at all, so a case built on it would assert emptiness for the wrong reason.
	 *  The two steps stay together for the reason that helper's own javadoc gives: {@code serviceWith}
	 *  pins the groups empty, and a service built without the second call silently cannot raise a
	 *  curated-group chip. */
	private static List<SafetyWarning> shippedChips(String question, PatientClinicalContext context) {
		return DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.serviceWithGroups(DrugReferenceTestSupport.shippedEntries()))
				.validate("", question, context);
	}
}
