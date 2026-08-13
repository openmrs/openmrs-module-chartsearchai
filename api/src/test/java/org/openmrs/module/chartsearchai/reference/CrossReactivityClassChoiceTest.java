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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * WHICH shared ATC subgroup a cross-reactivity chip names, when the two substances share more than
 * one (issue #161).
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.sharedClass} returned the first shared level-4
 * subgroup in the allergen's own ATC array. Every ATC array in the shipped 19 MB KB is in ascending
 * code order (measured: 1839 of 1839 entries with codes), so "first" meant "alphabetically smallest"
 * — and ATC's alphabet puts the locally-applied groups (A01 stomatological, C05A topical, D
 * dermatological) ahead of the systemic ones (H, J, L, M, N). A substance marketed by several routes
 * carries a code for each, so the chip systematically justified a systemic cross-reactivity concern
 * with a topical class: measured live against a dexamethasone allergy, methylprednisolone was
 * reported as {@code D10AA} (anti-acne preparations) and hydrocortisone as {@code A01AC}
 * (corticosteroids for local ORAL treatment), while the correct {@code H02AB} appeared in the same
 * answer for prednisone, which happens to carry no earlier shared code.
 *
 * <p>Asserted here on chip TEXT — the naming is the whole defect, the finding was already right —
 * through the real {@link DrugSafetyValidator#validate} entry point, on both call sites that reach
 * the arm (a drug the question names, and a drug the patient is already on), over rows copied
 * field-for-field from the shipped KB.
 *
 * <p><b>And the two limits, pinned so the preference cannot be read as "always say systemic".</b> A
 * pair that shares only a locally-applied subgroup keeps it (budesonide/dexamethasone share
 * {@code R01AD} and nothing else), and a pair of topical preparations keeps theirs even when the
 * ALLERGEN carries systemic codes of its own that the other drug does not share
 * (ketoconazole/tioconazole). Over the shipped KB the majority of the pairs that share more than one
 * subgroup share no systemic one at all, so the fallback is the common case and not a corner; the
 * figure is recorded once, in {@code DrugSafetyValidator.sharedClass}'s javadoc.
 *
 * <p><b>And the groups whose route is named below the anatomical main group.</b> Three cases here are
 * the ones a prefix list written at main-group granularity gets wrong: {@code A07A} "Intestinal
 * antiinfectives", {@code B05C} "Irrigating solutions" and {@code G02CC} "Antiinflammatory products
 * for vaginal administration" each sort ahead of the class their pair shares that does explain the
 * concern, so leaving one out of the list does not leave the old answer in place — it makes that
 * group the new answer.
 */
public class CrossReactivityClassChoiceTest {

	/** Rows copied field-for-field from the shipped 19 MB KB, in KB order — the corticosteroid family
	 *  whose route codes outnumber its systemic one, a topical-only azole pair, the psoralens, and the
	 *  three pairs whose locally-applied class is not one of the anatomical main groups. */
	private static final String FIXTURE = "chartsearchai-test/ddi-shared-class-choice.json";

	/** The same azole pair, with the ALLERGEN's {@code atc} array written descending — the one
	 *  deviation from verbatim, because every KB array is ascending and the scan order is otherwise
	 *  unobservable. */
	private static final String DESCENDING_FIXTURE =
			"chartsearchai-test/ddi-shared-class-descending-atc.json";

	/** A question that resolves no reference drug and is not an interaction screen, so the only arm
	 *  that can chip is the order-driven one ({@code addActiveOrderContraindications}). */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	@Test
	public void theFixtureReallyOffersTheArmAChoiceOfSharedSubgroups() throws IOException {
		// The precondition every case below rests on, through the production accessors the arm itself
		// compares with: without a CHOICE of shared subgroup there is nothing for this issue to get
		// wrong, and the cases would pass while testing nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugReference dexamethasone = service.lookupByToken("Dexamethasone");
		assertNotNull(dexamethasone, "the allergy must resolve to the dexamethasone row");
		assertEquals("Dexamethasone", dexamethasone.displayLabel());

		assertEquals("[D10AA, H02AB]", shared(service, "methylprednisolone", dexamethasone).toString(),
				"methylprednisolone must share an anti-acne subgroup AND the systemic one");
		assertEquals("[A01AC, C05AA, H02AB, S01BA, S01CB, S02BA]",
				shared(service, "hydrocortisone", dexamethasone).toString(),
				"and hydrocortisone six subgroups, five of them locally applied");
		assertEquals("[R01AD]", shared(service, "budesonide", dexamethasone).toString(),
				"while budesonide shares ONE, and it is a nasal-preparation subgroup");

		DrugReference ketoconazole = service.lookupByToken("Ketoconazole");
		assertNotNull(ketoconazole, "the second allergy must resolve too");
		assertEquals("[D01AC, G01AF]", shared(service, "tioconazole", ketoconazole).toString(),
				"and the azole pair shares only locally-applied subgroups");
		assertTrue(ketoconazole.atcSubgroups().contains("J02AB"),
				"though the ALLERGEN carries a systemic subgroup of its own — which tioconazole does "
						+ "not share, so no rule may reach for it: " + ketoconazole.atcSubgroups());

		DrugReference methoxsalen = service.lookupByToken("Methoxsalen");
		assertNotNull(methoxsalen, "the third allergy must resolve too");
		assertEquals("[D05AD, D05BA]", shared(service, "trioxsalen", methoxsalen).toString(),
				"and the psoralens share a topical and a systemic subgroup of ONE dermatological "
						+ "main group, so main-group granularity alone cannot tell them apart");

		DrugReference kanamycin = service.lookupByToken("Kanamycin");
		assertNotNull(kanamycin, "the fourth allergy must resolve too");
		assertEquals("[A07AA, J01GB, S01AA]", shared(service, "neomycin", kanamycin).toString(),
				"the aminoglycosides share an INTESTINAL-antiinfective subgroup that sorts ahead of "
						+ "their systemic one");

		DrugReference neomycin = service.lookupByToken("Neomycin");
		assertNotNull(neomycin, "the fifth allergy must resolve too");
		assertEquals("[A01AB, B05CA, S02AA, S03AA]",
				shared(service, "chlorhexidine", neomycin).toString(),
				"while neomycin and chlorhexidine share four subgroups and NO systemic one — an "
						+ "irrigating-solution subgroup among them, which classifies a formulation "
						+ "exactly as the other three do");

		DrugReference ibuprofen = service.lookupByToken("Ibuprofen");
		assertNotNull(ibuprofen, "the sixth allergy must resolve too");
		assertEquals("[G02CC, M01AE, M02AA]", shared(service, "naproxen", ibuprofen).toString(),
				"and ibuprofen and naproxen share a VAGINAL-administration subgroup that sorts ahead "
						+ "of the propionic-acid one that actually relates them");
	}

	@Test
	public void aSystemicSteroidNamesTheSystemicClassNotTheAntiAcneOne() throws IOException {
		// Issue #161's first measured row: Solu-Medrol against a dexamethasone allergy, reported live as
		// "(D10AA)" — anti-acne preparations.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("",
				"Is it safe to give methylprednisolone?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(), "one substance, one allergen, one chip, was: " + warnings);
		assertEquals("Methylprednisolone is in the same ATC class (H02AB) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void aPrescribedSteroidNamesTheSystemicClassNotTheLocalOralOne() throws IOException {
		// Issue #161's third row, and on the OTHER call site: the drug is one the patient is already on,
		// not one the question names, so the chip comes from the order-driven arm. Reported live as
		// "(A01AC)" — corticosteroids for local oral treatment, of an injected steroid.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Hydrocortisone Injection vial 100mg"), null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(), "one order, one allergen, one chip, was: " + warnings);
		assertEquals("Hydrocortisone is in the same ATC class (H02AB) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void aPairSharingOnlyALocallyAppliedSubgroupStillNamesIt() throws IOException {
		// Issue #161's second row, and the limit of what a shared-class chip can honestly say: budesonide
		// and dexamethasone are both corticosteroids, but the KB gives budesonide no systemic code, so
		// R01AD is the ONLY class they share. Naming it is true; naming H02AB would be a fabrication, and
		// the issue's expectation that all three rows implicate H02AB does not survive the data.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", "Is budesonide safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Budesonide is in the same ATC class (R01AD) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void twoTopicalPreparationsKeepTheirTopicalClass() throws IOException {
		// The case a "prefer the systemic class" rule gets wrong if it is a filter rather than a
		// preference, or if it looks at either drug's codes rather than at the SHARED ones: ketoconazole
		// carries J02AB (systemic antimycotics) and H02CA, tioconazole carries neither, and the honest
		// statement about two topical azoles is the topical class.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", "Is tioconazole safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ketoconazole"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Tioconazole is in the same ATC class (D01AC) as the patient's allergy to"
				+ " Ketoconazole — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void aSystemicSubgroupInsideATopicalMainGroupIsStillTheSystemicOne() throws IOException {
		// The exception ATC writes into its own naming, and the case a route-group prefix list gets
		// wrong if it stops at the main group: D is Dermatologicals, but D05B is "Antipsoriatics for
		// SYSTEMIC use" and D05A is the topical half of the same therapeutic group. Methoxsalen and
		// trioxsalen share both (D05AD topical, D05BA systemic), so the systemic one has to win from
		// inside a main group the rule otherwise treats as locally applied.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", "Is trioxsalen safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Methoxsalen"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Trioxsalen is in the same ATC class (D05BA) as the patient's allergy to"
				+ " Methoxsalen — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void anIntestinalAntiinfectiveSubgroupLosesToTheSystemicOne() throws IOException {
		// A07 is not one of the anatomical main groups the rule reads as locally applied, and A07A
		// "Intestinal antiinfectives" names its site one level down — exactly as A07E "Intestinal
		// antiinflammatory agents" does, which the list already carries. Neomycin and kanamycin are
		// aminoglycosides (J01GB); A07AA is their oral, non-absorbed, gut-lumen formulation class, and
		// it sorts first. Reachable between DIFFERENT substances and not only between route variants of
		// one, so issue #160's collapse does not hide it.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", "Is neomycin safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Kanamycin"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Neomycin is in the same ATC class (J01GB) as the patient's allergy to"
				+ " Kanamycin — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void anIntestinalAntiinfectiveSubgroupStillLosesToTheSystemicOneAsDuplicateTherapy()
			throws IOException {
		// ADDED by issues #183/#184, for a coverage loss the change above would otherwise make
		// silently. The case above still passes and still reports J01GB — but it no longer PINS A07A's
		// membership of DrugReference.LOCALLY_APPLIED_ATC_GROUPS, because A07AA "Antibiotics" is now
		// refused by the cross-reactivity arm before the locally-applied question is ever asked, so
		// dropping A07A from that list changes nothing it can see. Measured by mutation on a throwaway
		// tree: with only the case above, dropping A07A reddened NOTHING in the suite.
		//
		// The duplicate-therapy arm still considers A07AA, so the property is still observable there,
		// on the same pair. Same mutation, this case red — which is what makes this an addition rather
		// than a restatement.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is neomycin safe for her?", DrugReferenceTestSupport.ctx(60, null, null, null, null,
						null, Collections.singletonList(DrugReferenceTestSupport.activeOrderFor(service, "Kanamycin"))));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Neomycin is in the same ATC class (J01GB) as active order"
				+ " Kanamycin — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void anIrrigatingSolutionSubgroupDoesNotBecomeTheSystemicAnswer() throws IOException {
		// The same gap in the direction where there is no systemic class to fall forward to: B05C
		// "Irrigating solutions" names how it is given, and neomycin and chlorhexidine share nothing
		// but locally-applied subgroups. So the answer must be the FIRST of those, not the one that
		// merely happens to sit outside the anatomical main groups — a rule that reads B05CA as
		// classifying the substance names it here and says something false about both drugs.
		//
		// MOVED TO THE DUPLICATE-THERAPY ARM by issues #183/#184, keeping the pair, the shape and the
		// exact-string assertion. This case used to run on the cross-reactivity arm, where the same
		// four shared subgroups now assert too little to license that claim at all (every one of
		// A01AB, B05CA, S02AA and S03AA is "Antiinfectives …" — see DrugReference.isPurposeOnlyAtcCode),
		// so the chip it asserted is gone by design and the case could no longer carry the property.
		// The property itself is untouched, because the duplicate-therapy arm still considers all four:
		// B05CA has to be READ as locally applied here or it is returned outright as the systemic
		// answer. Verified by mutation on a throwaway tree — dropping B05C from
		// DrugReference.LOCALLY_APPLIED_ATC_GROUPS reddens this case and NOTHING else in the suite, so
		// this is still the only thing pinning that member.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is chlorhexidine safe for her?", DrugReferenceTestSupport.ctx(60, null, null, null,
						null, null, Collections.singletonList(DrugReferenceTestSupport.activeOrderFor(service, "Neomycin"))));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Chlorhexidine is in the same ATC class (A01AB) as active order"
				+ " Neomycin — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void twoNsaidsAreNotRelatedByAVaginalPreparationClass() throws IOException {
		// The clinically loudest instance of the same gap: G02CC is "Antiinflammatory products for
		// vaginal administration" and sorts ahead of M01AE, the propionic-acid subgroup that is the
		// whole reason an ibuprofen allergy says anything about naproxen. The duplicate-therapy arm,
		// which names the ORDER's own code instead of choosing among shared subgroups, reports M01AE
		// for a naproxen order (DuplicateInteractionChipTest).
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", "Is naproxen safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ibuprofen"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Naproxen is in the same ATC class (M01AE) as the patient's allergy to"
				+ " Ibuprofen — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void theAnswerDoesNotDependOnTheAllergenArraysCodeOrder() throws IOException {
		// The invariant the sort exists for, on a pair that shares two subgroups of ONE tier — two
		// locally-applied ones and no systemic one — which is where array order decides. (The systemic
		// tier has the same property; that is issue #168's alphabetical tie-break.) Ketoconazole's atc
		// array is written descending in this fixture, so a scan in array order reaches G01AF first and
		// reports it; the sorted scan reports D01AC either way. Not observable on a verbatim slice — all
		// 1839 KB entries that carry codes are ascending — which is why this is the one fixture that
		// deviates. Verified by mutation on a throwaway tree: dropping the sort makes this read
		// "(G01AF)" while every other case here stays green.
		List<SafetyWarning> warnings = fixtureValidator(DESCENDING_FIXTURE).validate("",
				"Is tioconazole safe for her?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ketoconazole"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Tioconazole is in the same ATC class (D01AC) as the patient's allergy to"
				+ " Ketoconazole — possible cross-reactivity", warnings.get(0).getDetail(),
				"the same chip the ascending fixture produces");
	}

	/** The subgroups {@code question}'s entry shares with {@code allergen}, sorted, through the
	 *  production resolver and the production accessor the arm compares with. A set intersection
	 *  rather than a scan, deliberately: the scan is what {@code sharedClass} does, and a hand copy of
	 *  it here would drift with the production one instead of characterising the data it is given. */
	private static Set<String> shared(DrugReferenceService service, String question,
			DrugReference allergen) {
		List<DrugReference> inPlay = service.findByQuery("Is it safe to give " + question + "?");
		assertEquals(1, inPlay.size(), question + " must resolve exactly one row, was: "
				+ DrugReferenceTestSupport.names(inPlay));
		Set<String> out = new TreeSet<String>(allergen.atcSubgroups());
		out.retainAll(inPlay.get(0).atcSubgroups());
		return out;
	}

	private static DrugSafetyValidator fixtureValidator(String fixture) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture));
	}
}
