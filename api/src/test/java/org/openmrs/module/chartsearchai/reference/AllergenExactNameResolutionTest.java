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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An allergy resolves to the entry that IS the recorded drug, not to the earliest entry that merely
 * claims its name (issue #176).
 *
 * <p><b>The defect.</b> {@link DrugReferenceService#lookupByToken} returned the EARLIEST entry any of
 * whose names matched, and reference names nest, so the row a chart's allergen resolved to could be a
 * different presentation of the substance — or a different substance. Since issue #187 that row is what
 * every one of the three chips this arm can raise NAMES, so the misresolution is printed: the chip
 * reports an allergy to a drug the chart does not record.
 *
 * <p><b>Not the boundary problem #128/#148 fixed.</b> {@code botulinum toxin type a} is a whole-word
 * match — indeed the whole string — inside the {@code Daxibotulinumtoxina} row's alias list, because
 * that row's {@code rxnorm_name} IS that name. Anchoring cannot separate them and neither can
 * {@link DrugReference#canonicalRow}: both rows name no route, so the fold keeps the earliest, which is
 * the wrong one. What separates them is that one row's own DISPLAY NAME is the recorded name and the
 * other's is not.
 *
 * <p>Every case runs over verbatim shipped-KB slices through the real {@link DdiDrugReferenceSource}
 * parser, and the cases that assert an OUTCOME go through the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} and assert the chip text,
 * so none can pass by raising some other chip about the right substance. The
 * {@code theFixtureReallyCarries…} cases are their premises: each states, through the production
 * predicates, what makes the outcome case next to it discriminating, so that case cannot rot into a test
 * of nothing.
 */
public class AllergenExactNameResolutionTest {

	/** Verbatim KB rows carrying the botulinum pair and the enalapril/enalaprilat pair — see the
	 *  fixture's own {@code metadata.note}. Shared, so a rename breaks in one place. */
	private static final String IDENTITY_FIXTURE = DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY;

	/** Verbatim KB rows carrying the two PPIs filed under one substance name, and the four
	 *  hydrocortisone rows. */
	private static final String PPI_FIXTURE = DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS;

	/** Verbatim KB rows for the middle rank — a name that IS a later row's own CIEL name and only
	 *  OCCURS INSIDE an earlier row's. See the fixture's own {@code metadata.note}. */
	private static final String NAME_CLAIM_FIXTURE = "chartsearchai-test/ddi-allergen-name-claim.json";

	private static DrugReference row(List<DrugReference> entries, String name) {
		for (DrugReference entry : entries) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		throw new AssertionError("the fixture must carry the " + name + " row, was: "
				+ DrugReferenceTestSupport.names(entries));
	}

	private static int indexOfRow(List<DrugReference> entries, String name) {
		return entries.indexOf(row(entries, name));
	}

	/** The file-shaped delegate the sibling tests in this package keep, so each case below reads as its
	 *  question rather than as its arrangement. {@code ddiFixtureService} rather than
	 *  {@code serviceWith}, so the real curated cross-reactivity groups are loaded. */
	private static DrugSafetyValidator fixtureValidator(String fixture) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture));
	}

	/** The premise two of the cases below share: no row's own DISPLAY NAME is {@code recorded}, so the
	 *  display-name rank cannot be what decides them. Deliberately NOT {@link DrugReference#isNamed},
	 *  which asks about the whole alias list — both cases below assert that a row IS named the recorded
	 *  string in that wider sense, and the point is that no row is CALLED it. */
	private static void assertNoRowsDisplayNameIs(List<DrugReference> entries, String recorded,
			String because) {
		for (DrugReference entry : entries) {
			assertNotEquals(DrugReference.normalizeName(recorded),
					DrugReference.normalizeName(entry.getName()), because + " — " + entry.getName());
		}
	}

	@Test
	public void theFixtureReallyCarriesTheEarliestMatchTrapAnchoringCannotFix() throws IOException {
		// The premise, through the production predicates: the trap is an EARLIER row whose own alias list
		// contains the recorded name in full, so no boundary rule and no canonical-row fold can see it.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(IDENTITY_FIXTURE);
		DrugReference daxi = row(entries, "Daxibotulinumtoxina");
		DrugReference botox = row(entries, "Botulinum toxin type A");
		assertTrue(indexOfRow(entries, "Daxibotulinumtoxina") < indexOfRow(entries, "Botulinum toxin type A"),
				"precondition: the slice must keep KB order, with the trap row FIRST");
		assertTrue(daxi.isNamed("Botulinum toxin type A"),
				"precondition: the trap row must claim the recorded name in FULL — so this is not the "
						+ "unanchored-substring hazard #128/#148 fixed");
		assertEquals(daxi.substanceGroupKey(), botox.substanceGroupKey(),
				"precondition: since issue #187 the two are one substance, so the identity VERDICT was "
						+ "already right and only the name the chip prints was wrong");
		assertSame(daxi, DrugReference.canonicalRow(Arrays.asList(daxi, botox)),
				"precondition: and the canonical row is the trap row — both name no route, so that fold "
						+ "keeps the earliest and cannot be the remedy here");
	}

	@Test
	public void anAllergyIsNamedByTheRowThatIsItRatherThanByAnEarlierRowThatClaimsItsName()
			throws IOException {
		// Issue #176's live observation, on the arm that prints the resolved row: a Botulinum toxin type A
		// allergy was reported as an allergy to Daxibotulinumtoxina, a different product.
		List<SafetyWarning> warnings = fixtureValidator(IDENTITY_FIXTURE)
				.validate("", "Is it safe to give botulinum toxin type A?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Botulinum toxin type A"), null));

		assertEquals(1, warnings.size(), "one substance, one chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("Botulinum toxin type A", warnings.get(0).getDrug(),
				"the chip is subjected on the drug the chart records the allergy to");
		assertEquals("The patient has a recorded allergy to Botulinum toxin type A.",
				warnings.get(0).getDetail(),
				"and says so, rather than naming the earlier row whose rxnorm_name happens to be that "
						+ "same string");
	}

	@Test
	public void theFixtureReallyCarriesAnInflectionTrapAcrossTwoSubstances() throws IOException {
		// The other half of the population, and the one that is not a labelling defect: here the earlier
		// row is a DIFFERENT substance, and it does not claim the recorded name at all — it matches only
		// through the inflectional tail #128 measured and allowed.
		//
		// Verbatim in CONTENT, but this slice REORDERS the pair relative to the KB, which is what makes
		// the case reachable: in the shipped 19 MB KB Enalaprilat (index 1142) precedes Enalapril (1882),
		// so an allergy recorded there resolves to itself even under earliest-match, and it was measured
		// doing so live. The shape is not hypothetical — Mecasermin rinfabate, Melphalan flufenamide,
		// Trastuzumab emtansine and Selenium Sulfide are the same shape in shipped-KB order, and
		// Ciprofloxacin lactate below is the alias-rank version of it — but the fixture supplies it here
		// because these two rows are also the pair issue #121 decided must stay two substances. Do NOT
		// "correct" the slice to KB order: that would leave this case asserting nothing.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(IDENTITY_FIXTURE);
		DrugReference enalapril = row(entries, "Enalapril");
		DrugReference enalaprilat = row(entries, "Enalaprilat");
		assertTrue(indexOfRow(entries, "Enalapril") < indexOfRow(entries, "Enalaprilat"),
				"precondition: the prodrug row must come FIRST, or there is no trap");
		assertTrue(enalapril.matchesDrugName("Enalaprilat"),
				"precondition: the prodrug row must match the recorded name (two trailing letters)");
		assertFalse(enalapril.isNamed("Enalaprilat"),
				"precondition: while claiming it as none of its own names — so the two rows CAN be told "
						+ "apart, unlike the botulinum pair");
		assertNotEquals(enalapril.substanceKey(), enalaprilat.substanceKey(),
				"precondition: and they are two substances, which is issue #121's decision");
		assertTrue(enalaprilat.atcSubgroups().isEmpty(),
				"precondition: the recorded drug must carry no ATC subgroup, so nothing can rescue the "
						+ "finding by classification");
	}

	@Test
	public void anAllergyToADrugWithNoClassificationIsStillFoundWhenAnEarlierRowInflectsToItsName()
			throws IOException {
		// The consequence is not a mislabel but SILENCE: the allergy resolved to the prodrug row, which
		// is a different substance from the row in play, and the row in play carries no ATC code and no
		// curated group — so the class comparisons had nothing to compare and the arm returned no chip at
		// all. Issue #135's direct-allergy gap, reached through misresolution.
		List<SafetyWarning> warnings = fixtureValidator(IDENTITY_FIXTURE)
				.validate("", "Is it safe to give enalaprilat?", DrugReferenceTestSupport.ctx(60, null,
						null, null, DrugReferenceTestSupport.set("Enalaprilat"), null));

		assertEquals(1, warnings.size(), "the recorded allergy must be reported, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("The patient has a recorded allergy to Enalaprilat.", warnings.get(0).getDetail());
	}

	@Test
	public void anAllergyToOneOfTwoSubstancesUnderOneNameIsNotAttributedToTheOther() throws IOException {
		// The sharp edge: Omeprazole's rxnorm_name IS "esomeprazole", so an esomeprazole allergy resolved
		// to the Omeprazole row — which the substance key deliberately keeps a DIFFERENT substance (issue
		// #121). Both chips were then wrong in the same pass: the direct allergy was attributed to
		// omeprazole, and esomeprazole — the drug the chart actually names — was reported as merely
		// cross-reactive with it.
		List<SafetyWarning> warnings = fixtureValidator(PPI_FIXTURE)
				.validate("", "Is it safe to give esomeprazole?", DrugReferenceTestSupport.ctx(60, null,
						null, null, DrugReferenceTestSupport.set("Esomeprazole"), null));

		assertEquals(2, warnings.size(), "two substances, two chips, was: " + warnings);
		assertEquals("Omeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Esomeprazole — possible cross-reactivity", warnings.get(0).getDetail(),
				"the OTHER substance is the cross-reactivity hedge");
		assertEquals("The patient has a recorded allergy to Esomeprazole.", warnings.get(1).getDetail(),
				"and the recorded drug is the direct allergy");
	}

	@Test
	public void twoRecordedAllergiesToOneSubstanceStillRaiseOneChipPerSubject() throws IOException {
		// The precision this fix adds has to stop at the substance: two allergy records for two
		// presentations of ONE substance are one clinical fact, and before the fix they collapsed only
		// because BOTH misresolved onto the same earlier row. Keyed on the resolved row, a correct
		// resolution would split every such patient's chip in two — which is what makes the ledger's
		// finding side a substance rather than a row (issues #145, #160, #187 in that direction).
		List<SafetyWarning> warnings = fixtureValidator(PPI_FIXTURE)
				.validate("", "Is hydrocortisone safe for her?",
						DrugReferenceTestSupport.ctx(60, null, null, null, DrugReferenceTestSupport
								.set("Hydrocortisone (ophthalmic)", "Hydrocortisone (topical)"), null));

		assertEquals(2, warnings.size(),
				"three presentations collapse and the ester keeps its own chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Hydrocortisone (ophthalmic).",
				warnings.get(0).getDetail(),
				"named after the FIRST record of the substance, since ties keep the incumbent");
		assertEquals("Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's allergy"
				+ " to Hydrocortisone (ophthalmic) — possible cross-reactivity",
				warnings.get(1).getDetail());
	}

	@Test
	public void theFixtureReallyCarriesANameOneRowIsAndAnotherOnlyContains() throws IOException {
		// The premise of the middle rank, which neither case above reaches: they contrast a row's own
		// DISPLAY NAME against an alias (botulinum, esomeprazole) or against a fragment (enalaprilat).
		// Here NO row is named the recorded string at all — one row claims it as an alias and an earlier
		// row merely contains it, so only the alias-over-fragment half of the ranking can decide it.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(NAME_CLAIM_FIXTURE);
		DrugReference lactic = row(entries, "Lactic acid");
		DrugReference cipro = row(entries, "Ciprofloxacin");
		assertTrue(indexOfRow(entries, "Lactic acid") < indexOfRow(entries, "Ciprofloxacin"),
				"precondition: the slice must keep KB order, with the fragment row FIRST");
		assertNoRowsDisplayNameIs(entries, "Ciprofloxacin lactate",
				"precondition: no row's own DISPLAY NAME may be the recorded string");
		assertTrue(cipro.isNamed("Ciprofloxacin lactate"),
				"precondition: the later row must claim it as one of its own names");
		assertTrue(lactic.matchesDrugName("Ciprofloxacin lactate") && !lactic.isNamed("Ciprofloxacin lactate"),
				"precondition: while the earlier row only CONTAINS it, through its CIEL name 'Lactate'");
		assertNotEquals(lactic.substanceKey(), cipro.substanceKey(),
				"precondition: and the two are different substances");
		assertTrue(lactic.normalizedAtcCodes().isEmpty(),
				"precondition: the earlier row must carry no ATC code, so resolving to it leaves the class "
						+ "comparisons nothing to compare and the finding disappears rather than being "
						+ "merely mislabelled");
	}

	@Test
	public void anAllergyResolvesToTheRowWhoseOwnNameItIsRatherThanAnEarlierRowItOccursInside()
			throws IOException {
		List<SafetyWarning> warnings = fixtureValidator(NAME_CLAIM_FIXTURE)
				.validate("", "Is it safe to give ciprofloxacin?", DrugReferenceTestSupport.ctx(60, null,
						null, null, DrugReferenceTestSupport.set("Ciprofloxacin lactate"), null));

		assertEquals(1, warnings.size(), "the recorded allergy must be reported, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("The patient has a recorded allergy to Ciprofloxacin.", warnings.get(0).getDetail(),
				"the salt is one of ciprofloxacin's own names, not a lactate the patient reacts to");
	}

	@Test
	public void twoEntriesMakingTheSameStrongestClaimResolveToTheEarliestOfThem() throws IOException {
		// The residual bound, and the reason the scan takes a STRICTLY stronger claim: where two entries
		// claim one name equally the earliest keeps the role, which is the answer every one of those names
		// already had. Not a corner — of the 7452 name strings the shipped KB publishes, 1367 are claimed
		// equally by two or more entries at the strongest rank they reach, all of them at the alias rank
		// (0 at the display-name rank, since no shipped entry's display name is filed twice; measured
		// 2026-08-08 through nameMatchStrength). 1125 of the 1367 are '/'-joined combination names, each
		// claimed by more than one constituent ('abacavir / lamivudine'); the remaining 242 are single
		// names two entries both publish ('ketorolac tromethamine'). Accepting the LATER claimant instead
		// would silently reseat all 1367.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(IDENTITY_FIXTURE);
		String shared = "botulinum type a toxin-haemagglutinin complex";
		DrugReference daxi = row(entries, "Daxibotulinumtoxina");
		DrugReference botox = row(entries, "Botulinum toxin type A");
		assertTrue(indexOfRow(entries, "Daxibotulinumtoxina") < indexOfRow(entries, "Botulinum toxin type A"),
				"precondition: the slice must keep KB order");
		assertTrue(daxi.isNamed(shared) && botox.isNamed(shared),
				"precondition: BOTH rows must claim the recorded name as one of their own names");
		assertNoRowsDisplayNameIs(entries, shared,
				"precondition: and no row's own DISPLAY NAME may be it, or that row would outrank the tie");

		// serviceWith over the very list above, so the identity assertion is about WHICH row and not about
		// which parse produced it — ddiFixtureService would parse a second time and every row would then
		// be a different object. Its groups-empty seam is harmless here: resolution reads no curated
		// group, and the validate leg below goes through ddiFixtureService, which carries them.
		assertSame(daxi, DrugReferenceTestSupport.serviceWith(entries).lookupByToken(shared),
				"the earliest of two equal claimants wins");

		List<SafetyWarning> warnings = fixtureValidator(IDENTITY_FIXTURE)
				.validate("", "Is it safe to give botulinum toxin type A?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(shared), null));

		assertEquals(1, warnings.size(), "one substance, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Daxibotulinumtoxina (botulinum toxin type a).",
				warnings.get(0).getDetail(),
				"and the tie is visible in the chip, not only in the resolver: the verdict is right either "
						+ "way — one substance — and which presentation is named is the dataset's order");
	}

	@Test
	public void aRecordedNameNoEntryIsNamedStillResolvesToTheEarliestMatchingEntry() throws IOException {
		// The fallback, and why it cannot be dropped: a chart's allergen is one localized display name
		// with a strength appended, which no reference entry is ever NAMED. #147 gave this shape the
		// order-name matcher precisely so it resolves at all, and preferring an exact name must not
		// undo that — so where nothing is named the recorded string, the earliest matching entry still
		// wins.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(PPI_FIXTURE);
		for (DrugReference entry : entries) {
			assertFalse(entry.isNamed("Pantoprazole Co 40mg"),
					"precondition: no entry may be NAMED the recorded string, or this is not the "
							+ "fallback case — " + entry.getName());
		}
		DrugReference resolved = DrugReferenceTestSupport.ddiFixtureService(PPI_FIXTURE)
				.lookupByToken("Pantoprazole Co 40mg");
		assertNotNull(resolved, "a localized display name must still resolve");
		assertEquals("Pantoprazole", resolved.getName());

		List<SafetyWarning> warnings = fixtureValidator(PPI_FIXTURE)
				.validate("", "Is it safe to give esomeprazole?", DrugReferenceTestSupport.ctx(60, null,
						null, null, DrugReferenceTestSupport.set("Pantoprazole Co 40mg"), null));

		assertEquals(2, warnings.size(), "was: " + warnings);
		assertEquals("Omeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Pantoprazole — possible cross-reactivity", warnings.get(0).getDetail());
		assertEquals("Esomeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Pantoprazole — possible cross-reactivity", warnings.get(1).getDetail());
	}
}
