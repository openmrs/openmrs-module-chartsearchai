/*
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * WHAT an ATC level-4 subgroup's own published NAME licenses — issues #183 and #184, decided
 * together by one criterion because they are one question asked twice.
 *
 * <p><b>The criterion.</b> A subgroup may justify a claim only as strong as what its name asserts,
 * and a residue asserts whatever the group it sits in asserts (issue #182's rule, applied here at
 * every level rather than one):
 * <ul>
 *   <li>a name that states a CHEMICAL or structural family, a derivative class, or a molecular
 *       TARGET says what its members are, and licenses both claim types;</li>
 *   <li>a name that states only what its members are FOR — an indication, an organism acted
 *       against, a therapeutic area, a diagnostic use — licenses DUPLICATE THERAPY, which is a
 *       claim about purpose, and not CROSS-REACTIVITY, which is a claim about chemistry
 *       ({@link DrugReference#isPurposeOnlyAtcCode}, issue #183);</li>
 *   <li>a name that asserts nothing at any level — a residue whose ancestry is residue up to a bare
 *       LEVEL-1 anatomical main group, a body system — licenses NEITHER
 *       ({@link DrugReference#isUnclassifyingAtcCode}, issue #184). Level 2 is ATC's therapeutic
 *       tier and does assert a purpose, so a residue inheriting one falls under the bullet above.</li>
 * </ul>
 *
 * <p><b>Why the split is by ARM and not by one list.</b> The owner's standing hypothesis was that
 * ATC classifies purpose and route rather than chemistry, so it should license duplicate therapy
 * only and cross-reactivity should come from the curated groups alone. Measured over the shipped
 * 19 MB KB by driving this class's own {@link DrugSafetyValidator#validate} entry point over each of
 * the 5550 substance pairs the knowledge base relates by a level-4 subgroup: that blanket rule
 * removes all 5266 cross-reactivity claims, of which 3701 rest on a subgroup that does name chemistry
 * or a molecular target — the penicillins, the cephalosporins, the aminoglycosides, the
 * benzodiazepines, the statins — against 1565 that rest on purpose or on nothing, and the one curated
 * group the module ships replaces just 24 of the 5266. Lost real signal exceeds the false-claim
 * reduction 2.4 to 1, so the blanket rule is not what shipped; this narrower reading removes the 1565
 * and keeps the 3701. Re-measure before relying on a figure.
 *
 * <p>Asserted through the real {@code validate} entry point on both arms, over rows copied
 * field-for-field from the shipped KB. Every case here pins a LIMB OF THE CRITERION rather than a
 * list entry: each has a partner case whose only difference is what ATC's name says.
 */
public class AtcCrossReactivityLicensingTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-atc-licensing.json";

	@Test
	public void theFixtureReallyOffersEachLimbOfTheCriterionAPairToDecide() throws IOException {
		// The precondition every case below rests on, through the production accessor the arms
		// compare with: without these being the ONLY shared subgroups, a case could pass by falling
		// through to a subgroup the criterion never had to judge.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		assertEquals("[D06AX, S01AA, S02AA, S03AA]", shared(service, "Chloramphenicol", "Gentamicin"),
				"the ophthalmic-antibiotic pair must share purpose-headed subgroups and no chemistry");
		assertEquals("[J04AB]", shared(service, "Rifampicin", "Capreomycin"),
				"and the antimycobacterial pair a purpose-headed subgroup OUTSIDE any locally applied "
						+ "group, so no site rule can be what decides it");
		assertEquals("[A16AX]", shared(service, "Eliglustat", "Givosiran"),
				"and the residue-of-a-residue pair exactly the subgroup issue #184 names");
		assertEquals("[A16AX]", shared(service, "Miglustat", "Eliglustat"),
				"including the pair #184 records as a genuine relationship that this rule costs");

		assertEquals("[J01CA]", shared(service, "Ampicillin", "Amoxicillin"),
				"while the penicillins share a chemically named subgroup and nothing else");
		assertEquals("[J01GB, S01AA]", shared(service, "Gentamicin", "Tobramycin"),
				"the aminoglycosides a chemically named RESIDUE beside a purpose-headed subgroup");
		assertEquals("[R06AX]", shared(service, "Loratadine", "Ketotifen"),
				"and the antihistamines a residue whose parent names a molecular target");
	}

	// ---------------------------------------------------------------- purpose-headed: #183

	@Test
	public void aPurposeHeadedSubgroupRaisesNoCrossReactivityChip() throws IOException {
		// Issue #183's own example: "chloramphenicol cross-reacts with gentamicin because both are
		// ophthalmic antibiotics" is a claim about chemistry made from a claim about purpose.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give gentamicin?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Chloramphenicol"), null));

		assertEquals("[]", DrugReferenceTestSupport.details(warnings).toString(),
				"being applied in the same place for the same purpose is not cross-reactivity");
	}

	@Test
	public void theSamePurposeHeadedSubgroupStillRaisesADuplicateTherapyChip() throws IOException {
		// The other half of the same decision, and the reason this is a per-arm rule and not a veto:
		// two ophthalmic antibiotics ARE duplicate therapy for one another. Same subgroup, same pair,
		// opposite answer, because the two arms ask different questions of the same name.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give gentamicin?", DrugReferenceTestSupport.ctx(60, null, null, null,
						null, null, Collections.singletonList(DrugReferenceTestSupport.activeOrderFor(service, "Chloramphenicol"))));

		assertEquals("[Gentamicin is in the same ATC class (S01AA) as active order Chloramphenicol"
				+ " — possible duplicate therapy]",
				DrugReferenceTestSupport.details(warnings).toString());
	}

	@Test
	public void aPurposeHeadedSubgroupOutsideAnyLocallyAppliedGroupIsAlsoRefused() throws IOException {
		// J04AB "Antibiotics" sits under J04A ANTIMYCOBACTERIALS, which no site rule reaches — so this
		// case can only pass if what decided it is the NAME. Rifampicin and capreomycin share nothing
		// else, and are chemically unrelated (an ansamycin and a cyclic peptide).
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give capreomycin?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Rifampicin"), null));

		assertEquals("[]", DrugReferenceTestSupport.details(warnings).toString());
	}

	// ---------------------------------------------------------------- residue of a residue: #184

	@Test
	public void aResidueWhoseAncestryAssertsNothingRaisesNeitherChip() throws IOException {
		// Issue #184: A16AX "Various alimentary tract and metabolism products" under A16A "OTHER
		// ALIMENTARY TRACT AND METABOLISM PRODUCTS" under A16 (same) under A, the anatomical main
		// group. Nothing is asserted at any level, so neither claim type may be built on it.
		assertEquals("[]", DrugReferenceTestSupport.details(fixtureValidator().validate("",
				"Is it safe to give givosiran?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Eliglustat"), null))).toString(),
				"eliglustat and givosiran have no relationship to cross-react by");

		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		assertEquals("[]", DrugReferenceTestSupport.details(DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give givosiran?",
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null,
								Collections.singletonList(DrugReferenceTestSupport.activeOrderFor(service, "Eliglustat"))))).toString(),
				"and none to duplicate either — unlike the purpose-headed case above, which keeps its "
						+ "duplicate-therapy chip");
	}

	// ---------------------------------------------------------------- the controls: what must stay

	@Test
	public void aChemicallyNamedSubgroupStillRaisesACrossReactivityChip() throws IOException {
		// The signal the blanket rule would have cost. J01CA is "Penicillins with extended spectrum";
		// ampicillin and amoxicillin sharing it is exactly the warning a clinician wants.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give amoxicillin?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ampicillin"), null));

		assertEquals("[Amoxicillin is in the same ATC class (J01CA) as the patient's allergy to"
				+ " Ampicillin — possible cross-reactivity]",
				DrugReferenceTestSupport.details(warnings).toString());
	}

	@Test
	public void aResidueUnderAChemicallyNamedParentStillRaisesOne() throws IOException {
		// J01GB "Other aminoglycosides" is a residue and keeps its claim, because what it inherits —
		// "aminoglycosides" — names a chemical family. The pair also shares the purpose-headed S01AA,
		// so this doubles as the fall-through case: refusing one shared subgroup must let the scan
		// reach the next rather than drop the claim.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give tobramycin?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Gentamicin"), null));

		assertEquals("[Tobramycin is in the same ATC class (J01GB) as the patient's allergy to"
				+ " Gentamicin — possible cross-reactivity]",
				DrugReferenceTestSupport.details(warnings).toString());
	}

	@Test
	public void aResidueUnderAParentNamingAMolecularTargetStillRaisesOne() throws IOException {
		// R06AX "Other antihistamines for systemic use" — issue #182's own worked example of a residue
		// that DOES assert something. "Antihistamines" names a receptor, not a disease, which is the
		// line this criterion draws inside the anti- names.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give ketotifen?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Loratadine"), null));

		assertEquals("[Ketotifen is in the same ATC class (R06AX) as the patient's allergy to"
				+ " Loratadine — possible cross-reactivity]",
				DrugReferenceTestSupport.details(warnings).toString());
	}

	@Test
	public void aChemicallyNamedSubgroupInsideALocallyAppliedGroupStillRaisesOne() throws IOException {
		// The limit that keeps this from becoming a site rule: C05AA/S01BA "Corticosteroids" are
		// locally applied AND name a chemical family, and issue #161's preference already decides
		// WHICH of them is named. Nothing here may change that.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give fluorometholone?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals("[Fluorometholone is in the same ATC class (C05AA) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity]",
				DrugReferenceTestSupport.details(warnings).toString());
	}

	@Test
	public void theTwoArmsMayNameDifferentClassesForOnePairAndBothAreTrue() throws IOException {
		// The composition this settlement makes possible, pinned because it looks like issue #171
		// returning and is not. #171 was two independent scans that could disagree about which of the
		// SAME candidates to prefer; here the preference is one method and the CANDIDATE SETS differ.
		// Miconazole and clotrimazole share A01AB (purpose-named, and first alphabetically), D01AC and
		// G01AF (both imidazole-derivative subgroups). Both sentences are true of the pair and neither
		// would be true under the other's class: they are duplicate therapy because both are
		// antiinfectives given for local oral treatment, and they cross-react because both are
		// imidazoles. Naming them alike would make one of the two false.
		//
		// Rare: 4 of the 3693 shipped-KB pairs that answer on both arms, measured at
		// DrugSafetyValidator.sharedClass, whose javadoc records which joins can raise the two
		// together.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		assertEquals("[A01AB, D01AC, G01AF]", shared(service, "Miconazole", "Clotrimazole"),
				"the pair must share a purpose-named subgroup AND a chemically named one, with the "
						+ "purpose-named one sorting first, or this case cannot discriminate");

		assertEquals("[Clotrimazole is in the same ATC class (D01AC) as the patient's allergy to"
				+ " Miconazole — possible cross-reactivity]",
				DrugReferenceTestSupport.details(DrugReferenceTestSupport.validator(service).validate(
						"", "Is it safe to give clotrimazole?", DrugReferenceTestSupport.ctx(60, null,
								null, null, DrugReferenceTestSupport.set("Miconazole"), null)))
						.toString(),
				"the cross-reactivity claim has to be justified by the chemistry");

		assertEquals("[Clotrimazole is in the same ATC class (A01AB) as active order"
				+ " Miconazole — possible duplicate therapy]",
				DrugReferenceTestSupport.details(DrugReferenceTestSupport.validator(service).validate(
						"", "Is it safe to give clotrimazole?",
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null,
								Collections.singletonList(DrugReferenceTestSupport.activeOrderFor(service, "Miconazole"))))).toString(),
				"while the duplicate-therapy claim keeps the purpose-named class it is actually about");
	}

	// ---------------------------------------------------------------- the two predicates' contract

	@Test
	public void assertingNothingIsStrictlyStrongerThanAssertingOnlyAPurpose() throws IOException {
		// The structural invariant that makes two lists safe to have: a subgroup that asserts nothing
		// at all cannot license a purpose claim either, so the cross-reactivity predicate must SUBSUME
		// the both-arms one. Without this a subgroup could be refused for duplicate therapy and
		// admitted for cross-reactivity, which is backwards.
		// The both-arms case: every one of these resolves, through its residue chain, either to
		// nothing at all or to a bare LEVEL-1 anatomical main group.
		for (String code : new String[] { "A01AD", "V03AB", "S01XA", "D11AX", "A16AX", "B06AX",
				"G02CX", "M09AX", "N07XX", "R07AX" }) {
			assertTrue(DrugReference.isUnclassifyingAtcCode(code),
					code + " must assert nothing at all");
			assertTrue(DrugReference.isPurposeOnlyAtcCode(code),
					code + " asserts nothing, so it certainly asserts no chemistry");
		}
		// The cross-arm-only case, and the line the criterion draws at ATC's own level 2: a residue
		// inheriting a THERAPEUTIC tier name ("ANTINEOPLASTIC AGENTS", "ANTIBACTERIALS FOR SYSTEMIC
		// USE", "DIAGNOSTIC AGENTS") asserts a purpose, so it belongs here and not above.
		for (String code : new String[] { "S01AA", "A07AA", "S02AA", "J04AB", "N06AX", "N03AX",
				"B05XA", "G04BD", "M02AA", "L01XX", "J01XX", "V04CX", "B03XA", "C01EB" }) {
			assertTrue(DrugReference.isPurposeOnlyAtcCode(code),
					code + " states a purpose and no chemistry");
			assertFalse(DrugReference.isUnclassifyingAtcCode(code),
					code + " does assert a purpose, so it is not the both-arms case");
		}
		for (String code : new String[] { "J01CA", "J01GB", "R06AX", "N02AX", "M01AE", "N05BA",
				"C10AA", "N01BB", "S01BA", "D07AC", "D01AC", "S01HA", "L01EX", "L01FX" }) {
			assertFalse(DrugReference.isPurposeOnlyAtcCode(code),
					code + " names a chemical family or a molecular target and licenses both");
		}
	}

	/** The subgroups two fixture rows share, sorted, through the production resolver and the
	 *  production accessor the arms compare with. */
	private static String shared(DrugReferenceService service, String a, String b) {
		DrugReference left = service.lookupByToken(a);
		DrugReference right = service.lookupByToken(b);
		assertNotNull(left, a + " must resolve");
		assertNotNull(right, b + " must resolve");
		Set<String> out = new TreeSet<String>(left.atcSubgroups());
		out.retainAll(right.atcSubgroups());
		return out.toString();
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
