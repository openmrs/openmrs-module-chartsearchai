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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Whether the duplicate-therapy class arm can see an active order the concept dictionary did not map
 * to ATC — issue #228.
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.orderPartners} walked
 * {@link PatientClinicalContext#getActiveDrugAtcCodes()} and nothing else, so a patient's
 * co-medications were exactly the subset of her prescriptions a DICTIONARY happened to classify. An
 * order carrying no {@code WHOATC} map produced no partner, and the whole arm was silently
 * unreachable for it however well the loaded reference data knows that drug by name. This is the
 * same key-mismatch issue #151 fixed one layer over in {@code DrugReferenceInjector} — the sparsity
 * belongs to the dictionary, never to the reference data, which classifies most of what it carries.
 *
 * <p>A MISSING warning, which is the direction this feature exists to prevent and the one that
 * leaves no trace: no chip, no log line, nothing separating "no duplicate therapy" from "could not
 * look".
 *
 * <p><b>What must not move, and why the cases below are shaped as pairs.</b> Widening what an order
 * resolves to widens what the restating-existing-therapy skip (issue #185) and the co-medication
 * grouping (issue #186) see. So every widening case here has a companion asserting the thing the
 * widening could break: the drug the order itself NAMES must not be reported as duplicating that
 * order, and a combination order must stay as many co-medications as it has substances rather than
 * silencing the chip that names one of them.
 *
 * <p>Every scenario runs the REAL production path — a verbatim KB slice parsed by the real
 * {@link DdiDrugReferenceSource}, or the curated fixture parsed by the real
 * {@link JsonDrugReferenceSource}, through the real {@code validate} entry point.
 */
public class UnmappedOrderClassPartnerTest {

	/** Verbatim KB rows. Dexamethasone {@code H02AB02} and Hydrocortisone {@code H02AB09} are the
	 *  issue's own measured pair, and both carry a route-variant family, so the partner label is a
	 *  {@code canonicalRow} choice rather than a single row's name. */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS;

	/** The nitroimidazole fixture issue #186's grouping cases use, reached here through its
	 *  never-mapped shape: the combination assertions below are the SAME assertions that file makes
	 *  about a mapped combination order, so the two rungs cannot come to disagree. */
	private static final String NITROIMIDAZOLES = "chartsearchai-test/drug-reference-partial-order-coverage.json";

	private static final String HYDROCORTISONE_QUESTION = "Is it safe to give hydrocortisone?";

	/** The chip the issue reports as absent, worded exactly as the ATC-mapped context produces it. */
	private static final String EXPECTED_CHIP = "Hydrocortisone is in the same ATC class (H02AB) as"
			+ " active order Dexamethasone — possible duplicate therapy";

	/** Dexamethasone's own WHO ATC code — what a dictionary that maps the concept supplies, and what
	 *  the 27 of 43 active orders on the 3.7.1 standalone measured for issue #228 do not have. */
	private static final String DEXAMETHASONE_CODE = "H02AB02";

	@Test
	public void anOrderTheDictionaryDidNotMapToAtcIsStillACoMedication() throws IOException {
		// Issue #228's headline, verbatim from the report: one active order for dexamethasone whose
		// concept carries no WHOATC map at all, and a question about a drug in its level-4 subgroup.
		// The reference data classifies both drugs; only the dictionary is silent.
		List<SafetyWarning> warnings = chips(HYDROCORTISONE_QUESTION, dexamethasoneOrder(null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals(EXPECTED_CHIP, warnings.get(0).getDetail());
	}

	@Test
	public void andTheChipIsTheSameOneAMappedDictionaryProduces() throws IOException {
		// The property the fix is really for: this arm's answer is a function of the patient's
		// prescriptions and the loaded reference data, not of whether a dictionary classified the
		// concept. Asserted as an equality between the two contexts rather than as two literals, so
		// neither side can be corrected into agreement one at a time — and the chip is asserted
		// non-empty too, or an arm silenced altogether would satisfy the equality.
		List<String> mapped = DrugReferenceTestSupport
				.details(chips(HYDROCORTISONE_QUESTION, dexamethasoneOrder(DEXAMETHASONE_CODE)));
		List<String> unmapped = DrugReferenceTestSupport
				.details(chips(HYDROCORTISONE_QUESTION, dexamethasoneOrder(null)));

		assertEquals(Collections.singletonList(EXPECTED_CHIP), mapped,
				"the mapped context is the control and states the chip the unmapped one must match");
		assertEquals(mapped, unmapped, "the same prescription, unclassified by the dictionary");
	}

	@Test
	public void theDrugThatOrderNAMESStillDoesNotDuplicateItself() throws IOException {
		// Issue #185's rule on the rung this issue adds. The widened partner set contains the very
		// substance the order is, so the skip has to reach it here too or the fix re-opens the
		// self-chip that PR closed — "Dexamethasone is in the same ATC class (H02AB) as active order
		// Dexamethasone".
		//
		// An emptiness assertion cannot catch an arm that is simply not running, so the second half
		// shows the arm IS live on this exact context.
		PatientClinicalContext context = dexamethasoneOrder(null);

		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips("Is it safe to give dexamethasone?", context)),
				"a drug the patient is already prescribed does not duplicate itself");
		assertEquals(Collections.singletonList(EXPECTED_CHIP),
				DrugReferenceTestSupport.details(chips(HYDROCORTISONE_QUESTION, context)),
				"…and the arm reaches this order, so the emptiness above is a skip and not silence");
	}

	@Test
	public void aSubstanceOrderedTwiceIsStillOneCoMedicationWhenOnlyOneOrderIsMapped() throws IOException {
		// The promise issue #186 keeps for two orders of one substance, over the mixed shape this fix
		// makes possible: one order the dictionary classified and one it did not, both dexamethasone.
		// They are one co-medication and must produce one chip, not one per resolution route.
		Set<String> unmappedNames = DrugReferenceTestSupport.set("Dexamethasone 4mg tablet");
		Set<String> mappedNames = DrugReferenceTestSupport.set("Dexamethasone injection 4mg/ml");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Dexamethasone 4mg tablet", "Dexamethasone injection 4mg/ml"),
				DrugReferenceTestSupport.set(DEXAMETHASONE_CODE), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-228-a", "Dexamethasone 4mg tablet",
								unmappedNames, null),
						DrugReferenceTestSupport.activeOrder("order-228-b",
								"Dexamethasone injection 4mg/ml", mappedNames,
								DrugReferenceTestSupport.set(DEXAMETHASONE_CODE))));

		List<SafetyWarning> warnings = chips(HYDROCORTISONE_QUESTION, context);

		assertEquals(1, warnings.size(),
				"two orders of one substance are one co-medication, was: " + warnings);
		assertEquals(EXPECTED_CHIP, warnings.get(0).getDetail());
	}

	@Test
	public void anUnmappedOrderTheDatasetCannotNameRaisesNothing() throws IOException {
		// The bound, stated rather than left to be rediscovered: with no code to look up and no name
		// the loaded dataset carries an alias for, there is nothing to classify the order by. A
		// brand-only display name is the real shape of it. That is a dataset-coverage limit, the same
		// one findForActiveOrders documents for the screen, and not something this arm can close.
		Set<String> names = DrugReferenceTestSupport.set("Decadron 4mg");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, names, null, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-228-c", "Decadron 4mg", names,
						null)));

		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips(HYDROCORTISONE_QUESTION, context)));
	}

	@Test
	public void anUnmappedCombinationOrderIsAsManyCoMedicationsAsItHasSubstances() throws IOException {
		// The half of issue #186 that a widening could lose in the other direction. One order naming
		// two substances the dataset carries really is two co-medications in one tablet, and the
		// assertion is the same one PartialOrderCoveragePartnerTest makes about the MAPPED form of
		// this order — the two rungs must answer alike.
		List<String> chips = classChipDetails(
				nitroimidazoleChips("Is it safe to give tinidazole?", unmappedCombination()));

		assertEquals(2, chips.size(),
				"an unmapped combination order covering two substances is two co-medications, was: "
						+ chips);
		assertTrue(chips.toString().contains("active order Metronidazole")
				&& chips.toString().contains("active order Secnidazole"),
				"and each is named by its own substance, was: " + chips);
	}

	@Test
	public void askingAboutOneConstituentOfThatCombinationStillReportsTheOther() throws IOException {
		// And the pair that pins WHAT such a partner is known to contain. Each partner here was named
		// by the DATASET and stands for one substance, so the metronidazole half is silenced and the
		// secnidazole half — a different nitroimidazole in the same subgroup — is a real duplicate and
		// must survive. Attaching the whole tablet's contents to both partners loses this chip, which
		// is the inverse of the defect issue #185 fixed.
		List<String> chips = classChipDetails(
				nitroimidazoleChips("Is it safe to give metronidazole?", unmappedCombination()));

		assertEquals(1, chips.size(),
				"the metronidazole half is silenced and the secnidazole half is not, was: " + chips);
		assertEquals("Metronidazole is in the same ATC class (P01AB) as active order Secnidazole"
				+ " — possible duplicate therapy", chips.get(0));
	}

	/**
	 * One active order for dexamethasone as {@link PatientClinicalContextBuilder} builds it, with the
	 * order's own ATC code when {@code atcCode} is given and with none at all when it is null — the
	 * two shapes the issue's measured table compares.
	 */
	private static PatientClinicalContext dexamethasoneOrder(String atcCode) {
		Set<String> names = DrugReferenceTestSupport.set("Dexamethasone 4mg tablet");
		Set<String> codes = atcCode == null ? null : DrugReferenceTestSupport.set(atcCode);
		return DrugReferenceTestSupport.ctx(60, null, names, codes, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-228", "Dexamethasone 4mg tablet",
						names, codes)));
	}

	/** ONE order naming two substances the nitroimidazole fixture carries, mapped to nothing. */
	private static PatientClinicalContext unmappedCombination() {
		Set<String> names = DrugReferenceTestSupport.set("Metronidazole and secnidazole");
		return DrugReferenceTestSupport.ctx(60, null, names, null, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-228-d",
						"Metronidazole and secnidazole", names, null)));
	}

	/** The class-relationship chips, which is all the rule-less nitroimidazole fixture can raise — so
	 *  a count here is a count of co-medications the arm decided about. */
	private static List<String> classChipDetails(List<SafetyWarning> warnings) {
		return warnings.stream().filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType()))
				.map(SafetyWarning::getDetail).collect(java.util.stream.Collectors.toList());
	}

	private static List<SafetyWarning> chips(String question, PatientClinicalContext context)
			throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", question, context);
	}

	private static List<SafetyWarning> nitroimidazoleChips(String question, PatientClinicalContext context)
			throws IOException {
		return DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.serviceWith(DrugReferenceTestSupport.fixtureEntries(NITROIMIDAZOLES)))
				.validate("", question, context);
	}
}
