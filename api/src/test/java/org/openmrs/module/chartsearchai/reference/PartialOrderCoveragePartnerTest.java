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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #186 — {@code DrugSafetyValidator.orderPartners} keyed the class arm's co-medications by
 * ATC CODE, so an order the loaded dataset covers only PARTLY climbed two different rungs of its
 * identity ladder (the covered codes onto the dataset entry, the uncovered ones onto the order) and
 * became two partners. One co-medication, two chips, under two different labels.
 *
 * <p>Documented by issue #182's hardening and reported rather than fixed there, because regrouping
 * is a behaviour change and #182 was carrying a measured impact partition a regrouping would
 * invalidate. It is the partner-side member of the one-substance-one-row family: #171 removed the
 * per-CODE chip on the same arm, and this removes the per-code PARTNER underneath it.
 *
 * <p>The two shapes the fix must not break are pinned here beside the defect, because both are
 * things the per-code ladder gets right and an order-first regrouping could plausibly lose: two
 * orders of one substance staying ONE partner, and a combination order covering two substances
 * staying TWO.
 *
 * <p>Every scenario runs the REAL production path: a curated fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, GP reads on their
 * no-context defaults.
 */
public class PartialOrderCoveragePartnerTest {

	/**
	 * Three nitroimidazoles. {@code Tinidazole} — the drug the questions ask about — is filed under
	 * {@code P01AB02} and {@code J01XD02}, so it shares a level-4 subgroup with a partner reached
	 * through EITHER family; the dataset carries {@code P01AB01}/{@code P01AB09}
	 * ({@code Metronidazole}) and {@code P01AB07} ({@code Secnidazole}) and no {@code J01XD} code at
	 * all, which is what makes an order mapped to {@code J01XD01} partly-covered.
	 */
	private static final String FIXTURE = "chartsearchai-test/drug-reference-partial-order-coverage.json";

	private static final String QUESTION = "Is it safe to give tinidazole?";

	private static DrugSafetyValidator validator() throws Exception {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	/** The details of the class-relationship chips, which is all this arm can raise over a rule-less
	 *  dataset — so a count here is a count of co-medications the arm decided about. */
	private static List<String> classChipDetails(List<SafetyWarning> warnings) {
		return warnings.stream().filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType()))
				.map(SafetyWarning::getDetail).collect(java.util.stream.Collectors.toList());
	}

	@Test
	public void anOrderTheDatasetCoversOnlyPartlyIsOneCoMedication() throws Exception {
		// ONE order, whose concept maps to two ATC codes: P01AB01 (the dataset carries it) and
		// J01XD01 (it does not). Tinidazole shares a subgroup with each, so before this fix the
		// patient was told twice about one co-medication — once as "active order Metronidazole"
		// through the dataset entry and once as "active order Metronidazole 500mg" through the order
		// itself.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Metronidazole 500mg"),
				DrugReferenceTestSupport.set("P01AB01", "J01XD01"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1", "Metronidazole 500mg",
						DrugReferenceTestSupport.set("Metronidazole 500mg"),
						DrugReferenceTestSupport.set("P01AB01", "J01XD01"))));

		List<String> chips = classChipDetails(validator().validate("", QUESTION, context));

		assertEquals(1, chips.size(),
				"one order is one co-medication however many of its codes the dataset covers, was: "
						+ chips);
		assertTrue(chips.get(0).contains("as active order Metronidazole 500mg —"),
				"and it is named by the ORDER, not by the dataset's name for the codes it happens to "
						+ "cover — that name does not speak for the code it does not, was: " + chips);
	}

	@Test
	public void theMergedPartnerIsNotNamedAfterHalfOfACombination() throws Exception {
		// Why the merged partner takes the ORDER's name, on the real shape that makes it matter. The
		// 3.7.1 demo dictionary's "Isoniazid / Rifapentine" concept maps to J04AB05 and J04AC51, and
		// the loaded 19 MB KB carries the first and not the second — so the covered half names
		// rifapentine while the class the chip states (J04AC) is the isoniazid half's. Naming the
		// partner "Rifapentine" would publish "…is in the same ATC class (J04AC) as active order
		// Rifapentine", whose stated class does not classify the drug it names.
		//
		// Reproduced here over the fixture's own codes rather than over J04*, so the assertion does
		// not depend on the demo dictionary: Secnidazole is covered, J01XD01 is not, and the question
		// drug relates through J01XD.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Secnidazole and ornidazole"),
				DrugReferenceTestSupport.set("P01AB07", "J01XD01"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1",
						"Secnidazole and ornidazole",
						DrugReferenceTestSupport.set("Secnidazole and ornidazole"),
						DrugReferenceTestSupport.set("P01AB07", "J01XD01"))));

		List<String> chips = classChipDetails(validator().validate("", QUESTION, context));

		assertEquals(1, chips.size(), "one order is one co-medication, was: " + chips);
		assertTrue(chips.get(0).contains("as active order Secnidazole and ornidazole —"),
				"the chip must name the order, was: " + chips);
	}

	@Test
	public void twoOrdersOfOneSubstanceAreStillOneCoMedication() throws Exception {
		// The promise the per-code ladder already kept and an order-first regrouping could lose: two
		// separate orders that the dataset resolves to ONE substance must not become two partners.
		// Each order carries a different code of the same Metronidazole entry.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Metronidazole 500mg", "Metronidazole gel"),
				DrugReferenceTestSupport.set("P01AB01", "P01AB09"), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-1", "Metronidazole 500mg",
								DrugReferenceTestSupport.set("Metronidazole 500mg"),
								DrugReferenceTestSupport.set("P01AB01")),
						DrugReferenceTestSupport.activeOrder("order-2", "Metronidazole gel",
								DrugReferenceTestSupport.set("Metronidazole gel"),
								DrugReferenceTestSupport.set("P01AB09"))));

		List<String> chips = classChipDetails(validator().validate("", QUESTION, context));

		assertEquals(1, chips.size(),
				"two orders of one substance are one co-medication, was: " + chips);
	}

	@Test
	public void aCombinationOrderCoveringTwoSubstancesIsStillTwoCoMedications() throws Exception {
		// The other direction, and the reason the fix cannot simply be "group by order". One order
		// whose concept maps to the codes of TWO different substances the dataset carries really is
		// two co-medications in one tablet, and collapsing it would drop a duplicate-therapy chip.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Metronidazole and secnidazole"),
				DrugReferenceTestSupport.set("P01AB01", "P01AB07"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1",
						"Metronidazole and secnidazole",
						DrugReferenceTestSupport.set("Metronidazole and secnidazole"),
						DrugReferenceTestSupport.set("P01AB01", "P01AB07"))));

		List<String> chips = classChipDetails(validator().validate("", QUESTION, context));

		assertEquals(2, chips.size(),
				"a combination order covering two substances stays two co-medications, was: " + chips);
		assertTrue(chips.toString().contains("active order Metronidazole")
				&& chips.toString().contains("active order Secnidazole"),
				"and each is named by its own substance, was: " + chips);
	}

	@Test
	public void askingAboutOneConstituentOfThatCombinationStillReportsTheOther() throws Exception {
		// The same two-partner combination, asked about one of the substances it contains — the case
		// issue #185's restating-existing-therapy skip has to leave alone. The metronidazole half IS
		// existing therapy and is silenced; the secnidazole half is a DIFFERENT nitroimidazole in the
		// same subgroup, so adding metronidazole to this patient really is duplicate therapy and the
		// chip naming Secnidazole has to survive.
		//
		// This is what scopes that skip's name-driven leg to a partner named after the ORDER. A
		// partner the dataset named speaks for ONE substance; giving it the whole tablet's contents
		// silences a chip that names it, which is the opposite of the defect #185 fixes.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Metronidazole and secnidazole"),
				DrugReferenceTestSupport.set("P01AB01", "P01AB07"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1",
						"Metronidazole and secnidazole",
						DrugReferenceTestSupport.set("Metronidazole and secnidazole"),
						DrugReferenceTestSupport.set("P01AB01", "P01AB07"))));

		List<String> chips = classChipDetails(
				validator().validate("", "Is it safe to give metronidazole?", context));

		assertEquals(1, chips.size(),
				"the metronidazole half is silenced and the secnidazole half is not, was: " + chips);
		assertEquals("Metronidazole is in the same ATC class (P01AB) as active order Secnidazole"
				+ " — possible duplicate therapy", chips.get(0));
	}

	@Test
	public void anOrderTheDatasetDoesNotCoverAtAllIsStillOneCoMedication() throws Exception {
		// The rung below, unchanged: with no entry for either code the order itself is the identity,
		// so its codes are one partner named by the order's display name (issue #155).
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Ornidazole 500mg"),
				DrugReferenceTestSupport.set("J01XD03", "P01AB03"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1", "Ornidazole 500mg",
						DrugReferenceTestSupport.set("Ornidazole 500mg"),
						DrugReferenceTestSupport.set("J01XD03", "P01AB03"))));

		List<String> chips = classChipDetails(validator().validate("", QUESTION, context));

		assertEquals(1, chips.size(),
				"an uncovered order is one co-medication, was: " + chips);
		assertTrue(chips.get(0).contains("as active order Ornidazole 500mg —"),
				"named by the order's own display name, was: " + chips);
	}
}
