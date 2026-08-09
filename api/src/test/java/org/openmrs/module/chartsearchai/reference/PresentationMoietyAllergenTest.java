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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An allergy recorded as a PRESENTATION is checked against the parent moiety the KB files it apart
 * from (issue #195) — and only where the KB is NAMED that moiety, so two presentations of one stem
 * are not thereby merged.
 *
 * <p><b>The defect.</b> Where the KB files a presentation as its own substance <em>and</em> gives that
 * row no ATC code, issue #192's correct resolution lands on the presentation, the class comparisons
 * have nothing to compare, and the chip vanishes. Before #192 those cases produced a chip naming the
 * WRONG substance; after, they produce nothing at all — issue #135's ATC-less gap reached through a
 * correct resolution, and the silent-on-a-recorded-allergy failure this whole body of work began with
 * (#86).
 *
 * <p><b>The gate, and why it is the DISPLAY NAME rank.</b> The moiety is taken from the recorded name
 * by removing its trailing qualifier, which is a derivation and not a claim: the recorded string does
 * not assert that {@code insulin lispro} names anything. So only a row that is CALLED the stem may
 * stand for it. A row that merely lists the stem among its aliases is a different presentation, and
 * naming it in a chip would report an allergy to a drug the chart does not record — which is issue
 * #176's defect, arriving from the other side. The dextran rows are where that rank is what decides:
 * relax the gate by one rank and they merge. The two vaccine and the two manganese rows are held apart
 * by less than that — the KB publishes no row named their stem at any rank — and are asserted here as
 * the shape the widening must never reach, not as a test of which rank the gate takes.
 *
 * <p>Every case runs verbatim shipped-KB slices through the real {@link DdiDrugReferenceSource} parser
 * and the real {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}, and asserts
 * chip TEXT rather than chip count alone: the failure mode is silence, so a case that passes on absence
 * proves nothing.
 */
public class PresentationMoietyAllergenTest {

	private static final String FIXTURE = DrugReferenceTestSupport.DDI_PRESENTATION_MOIETY;

	private static DrugReference row(List<DrugReference> entries, String name) {
		for (DrugReference entry : entries) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		throw new AssertionError("the fixture must carry the " + name + " row, was: "
				+ DrugReferenceTestSupport.names(entries));
	}

	private static List<SafetyWarning> warningsFor(String question, String allergy) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", question, DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set(allergy), null));
	}

	/** @return whether any entry is CALLED {@code stem} — the gate's own premise, asked through the
	 *          predicate the gate itself asks ({@link DrugReference#nameMatchStrength} at
	 *          {@link DrugReference#NAME_IS_THE_DISPLAY_NAME}) rather than by comparing names here. The
	 *          two are not the same question: that rank is gated on {@code matchesDrugName} first, so an
	 *          entry whose alias list omits its own display name answers NAME_NO_MATCH even though the
	 *          strings are equal — and a premise stated in different terms from the gate can hold while
	 *          the gate disagrees. */
	private static boolean someRowIsCalled(List<DrugReference> entries, String stem) {
		for (DrugReference entry : entries) {
			if (entry.nameMatchStrength(stem) == DrugReference.NAME_IS_THE_DISPLAY_NAME) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void theFixtureReallyCarriesAPresentationFiledApartFromItsMoiety() throws IOException {
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference lispro = row(entries, "Insulin lispro");
		DrugReference protamine = row(entries, "Insulin lispro (protamine)");
		assertEquals(DrugReference.NAME_IS_THE_DISPLAY_NAME,
				protamine.nameMatchStrength("Insulin lispro (protamine)"),
				"precondition: the presentation must be CALLED the recorded string, so issue #192's rank "
						+ "correctly answers it");
		assertNotEquals(lispro.substanceKey(), protamine.substanceKey(),
				"precondition: while the KB files the two as different substances");
		assertTrue(protamine.atcSubgroups().isEmpty(),
				"precondition: and the presentation carries no ATC subgroup, so the class comparisons have "
						+ "nothing to compare and the chip disappears rather than being mislabelled");
		assertFalse(lispro.atcSubgroups().isEmpty(),
				"precondition: while its parent moiety does carry one");
		assertTrue(someRowIsCalled(entries, "insulin lispro"),
				"precondition: and the KB is NAMED the moiety, which is what the widening is gated on");
	}

	@Test
	public void anAllergyToAPresentationIsADirectAllergyToItsParentMoiety() throws IOException {
		// Issue #195's headline case. Before: the allergy resolved correctly to Insulin lispro
		// (protamine), which is a different substance from the drug in play and carries no ATC code, so
		// both class comparisons were skipped and the arm returned NOTHING.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give insulin lispro?",
				"Insulin lispro (protamine)");

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("The patient has a recorded allergy to Insulin lispro.", warnings.get(0).getDetail());
		assertEquals("Insulin lispro", warnings.get(0).getDrug());
	}

	@Test
	public void theFixtureReallyCarriesAnAtcLessMoietyOnlyTheIdentityArmCanReach() throws IOException {
		// The iron rows are the shape where no classification exists on EITHER side, so nothing but the
		// identity comparison can produce a chip at all — issue #135's population, reached through a
		// presentation rather than through a misresolution.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference iron = row(entries, "Iron");
		DrugReference bisglycinate = row(entries, "Iron (bisglycinate)");
		DrugReference polysaccharide = row(entries, "Iron (polysaccharide)");
		assertTrue(iron.atcSubgroups().isEmpty() && bisglycinate.atcSubgroups().isEmpty()
				&& polysaccharide.atcSubgroups().isEmpty(),
				"precondition: none of the three may carry an ATC subgroup");
		assertEquals(iron.substanceKey(), bisglycinate.substanceKey(),
				"precondition: two of them are already ONE substance (one rxnorm_name, one drugbank_id)");
		assertNotEquals(iron.substanceKey(), polysaccharide.substanceKey(),
				"precondition: while the third is filed apart from them");
		assertTrue(entries.indexOf(bisglycinate) < entries.indexOf(iron),
				"precondition: the slice must keep KB order, with the qualified row FIRST — so the moiety "
						+ "is chosen by the claim rank and not by dataset position");
	}

	@Test
	public void anAllergyToAnAtcLessPresentationStillRaisesOneChipForTheSubstanceInPlay()
			throws IOException {
		// Both iron rows in play are one substance, so this is also the ledger's canary for this fix: the
		// widened allergen must not raise a chip per row of the subject substance.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give iron?", "Iron (polysaccharide)");

		assertEquals(1, warnings.size(), "two rows of one substance in play, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Iron.", warnings.get(0).getDetail());
	}

	@Test
	public void twoPresentationsOfAStemTheKbPublishesNoBareRowForStayTwoSubstances() throws IOException {
		// The negative control, and the reason the gate is what it is. Both vaccine rows reduce to the stem
		// "varicella zoster vaccine" and the KB is named no such row — a distinction that decides whether
		// an immunocompromised patient may have the vaccine at all, so merging them on the stem alone
		// would be wrong. Same shape, same answer, for the two manganese salts.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference recombinant = row(entries, "Varicella Zoster Vaccine (Recombinant)");
		DrugReference live = row(entries, "Varicella zoster vaccine (live/attenuated)");
		assertNotEquals(recombinant.substanceKey(), live.substanceKey(),
				"precondition: the KB files the two presentations as different substances");
		assertFalse(someRowIsCalled(entries, "varicella zoster vaccine"),
				"precondition: and it is NAMED no bare moiety for them, which is what withholds the "
						+ "widening");
		assertFalse(someRowIsCalled(entries, "manganese"),
				"precondition: likewise for the manganese salts");

		assertEquals(0, warningsFor("Is it safe to give varicella zoster vaccine (live/attenuated)?",
				"Varicella Zoster Vaccine (Recombinant)").size(),
				"an allergy to the recombinant vaccine is not an allergy to the live one");
		assertEquals(0, warningsFor("Is it safe to give manganese (sulfate)?", "Manganese (chloride)").size(),
				"nor is an allergy to one manganese salt an allergy to the other");
	}

	@Test
	public void aMoietyTheKbOnlyLISTSRatherThanNAMESIsNotReached() throws IOException {
		// The residual bound, pinned so a later widening cannot quietly claim it. The bare name "dextran"
		// is one of Dextran (high molecular weight)'s CIEL names and no row's display name, so the moiety
		// leg withholds it: a chip taking it would have to be named after the high-molecular-weight
		// presentation, reporting an allergy to a preparation the chart does not record.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference high = row(entries, "Dextran (high molecular weight)");
		DrugReference low = row(entries, "Dextran (low molecular weight)");
		assertTrue(high.isNamed("dextran"),
				"precondition: the bare moiety must be one of the high-MW row's own names");
		assertFalse(someRowIsCalled(entries, "dextran"),
				"precondition: while no row is CALLED it");
		assertNotEquals(high.substanceKey(), low.substanceKey(),
				"precondition: and the two are different substances");

		assertEquals(0, warningsFor("Is dextran safe for her?", "Dextran (low molecular weight)").size(),
				"so this case stays silent — the bound this fix carries rather than closes");
	}

	@Test
	public void aMoietyNamedByABareWordRatherThanAQualifierIsNotReached() throws IOException {
		// The other residual bound: Peanut oil contains no trailing qualifier, so nothing reduces it to
		// Peanut. The shape is indistinguishable by spelling from Digoxin Immune Fab (Ovine) against
		// Digoxin — a patient allergic to digoxin's ANTIDOTE, which issue #192 measured and separated —
		// so a rule that reached it by stripping a trailing word would undo that fix.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		assertNotEquals(row(entries, "Peanut").substanceKey(), row(entries, "Peanut oil").substanceKey(),
				"precondition: the KB files the two as different drugbank substances");

		assertEquals(0, warningsFor("Is it safe to give peanut?", "Peanut oil").size(),
				"so this case stays silent too");
	}
}
