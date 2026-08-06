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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
 * (ketoconazole/tioconazole). Over the shipped KB, 560 of the 1090 pairs that share more than one
 * subgroup share no systemic one at all, so the fallback is the majority case, not a corner.
 */
public class CrossReactivityClassChoiceTest {

	/** Six rows copied field-for-field from the shipped 19 MB KB, in KB order — the corticosteroid
	 *  family whose route codes outnumber its systemic one, plus a topical-only azole pair. */
	private static final String FIXTURE = "chartsearchai-test/ddi-shared-class-choice.json";

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
	}

	@Test
	public void aSystemicSteroidNamesTheSystemicClassNotTheAntiAcneOne() throws IOException {
		// Issue #161's first measured row: Solu-Medrol against a dexamethasone allergy, reported live as
		// "(D10AA)" — anti-acne preparations.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
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
		List<SafetyWarning> warnings = fixtureValidator().validate("", NO_DRUG_QUESTION,
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
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is budesonide safe for her?",
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
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is tioconazole safe for her?",
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
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is trioxsalen safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Methoxsalen"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Trioxsalen is in the same ATC class (D05BA) as the patient's allergy to"
				+ " Methoxsalen — possible cross-reactivity", warnings.get(0).getDetail());
	}

	/** The subgroups {@code question}'s entry shares with {@code allergen}, sorted, through the
	 *  production resolver and the production accessor the arm compares with. */
	private static List<String> shared(DrugReferenceService service, String question,
			DrugReference allergen) {
		List<DrugReference> inPlay = service.findByQuery("Is it safe to give " + question + "?");
		assertEquals(1, inPlay.size(), question + " must resolve exactly one row, was: "
				+ DrugReferenceTestSupport.names(inPlay));
		Set<String> refClasses = inPlay.get(0).atcSubgroups();
		List<String> out = new ArrayList<String>();
		for (String subgroup : allergen.atcSubgroups()) {
			if (refClasses.contains(subgroup)) {
				out.add(subgroup);
			}
		}
		Collections.sort(out);
		return out;
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
