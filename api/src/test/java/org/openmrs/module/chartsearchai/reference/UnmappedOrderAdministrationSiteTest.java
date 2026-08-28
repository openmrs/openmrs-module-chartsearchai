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
import java.util.LinkedHashSet;
import java.util.List;
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
	private static final Set<String> TERMLESS_SITES = DrugReferenceTestSupport.set("mouth", "gut",
			"anorectal");

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
		Set<String> names = DrugReferenceTestSupport.set("Hydrocortisone eye ointment");
		assertEquals(
				Collections.singletonList("Dexamethasone is in the same ATC class (S01BA) as active"
						+ " order Hydrocortisone — possible duplicate therapy"),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION,
						order("Hydrocortisone eye ointment", "Bilateral eye administration"))),
				"recorded at the eye, the ophthalmic class is what the two share");
		assertFalse(names.isEmpty());

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
		// tablet.
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
	public void twoPresentationsTheModuleCanBothPlaceNarrowToTheUnionOfTheirSites() throws IOException {
		// The pair to the case above: where EVERY order of the substance names a site, there is nothing
		// unattributable and the narrowing is the union of what they name. Topical plus ophthalmic
		// hydrocortisone shares S01BA with dexamethasone and no longer shares H02AB — the chip the eye
		// case pins, reached with a second order beside it.
		Set<String> creamNames = DrugReferenceTestSupport.set(CREAM);
		Set<String> dropNames = DrugReferenceTestSupport.set("Hydrocortisone eye preparation");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(CREAM, "Hydrocortisone eye preparation"), null, null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-234-cream", CREAM, creamNames, null,
								DrugReferenceTestSupport.set(CUTANEOUS)),
						DrugReferenceTestSupport.activeOrder("order-234-drop",
								"Hydrocortisone eye preparation", dropNames, null,
								DrugReferenceTestSupport.set("Bilateral eye administration"))));

		assertEquals(
				Collections.singletonList("Dexamethasone is in the same ATC class (S01BA) as active"
						+ " order Hydrocortisone — possible duplicate therapy"),
				DrugReferenceTestSupport.details(chips(DEXAMETHASONE_QUESTION, context)));
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
		// sees it — and the factory carries them anyway, for the reason its javadoc gives. Pinned
		// because an overload with no case is one the next change can quietly drop the argument from.
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

	private static PatientClinicalContext cream(String... administration) {
		return order(CREAM, administration);
	}

	private static PatientClinicalContext ketoprofen(String... administration) {
		return order("Ketoprofen 2.5% preparation", administration);
	}

	private static PatientClinicalContext terbinafine(String... administration) {
		return order("Terbinafine 1% preparation", administration);
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
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.shippedEntries());
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return DrugReferenceTestSupport.validator(service).validate("", question, context);
	}
}
