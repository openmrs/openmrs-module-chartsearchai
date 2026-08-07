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
		assertTrue(chips.get(0).contains("as active order Metronidazole —"),
				"and it is named by the dataset's own name for the substance, was: " + chips);
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
