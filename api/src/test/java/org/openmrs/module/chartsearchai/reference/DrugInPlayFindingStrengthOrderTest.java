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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The drug-in-play arm states its strongest finding first (issue #346).
 *
 * <p><b>Why the ORDER of this arm's chips is a safety property and not presentation.</b> The list
 * this arm appends to is the list {@code DrugReferenceInjector} renders the model's
 * {@code safety_finding} records from, in list order and with no sort of its own, so the arm's
 * emission order is the order the findings reach the prompt. A model that enumerates them and stops
 * partway therefore drops whatever the knowledge base happened to file last. Measured on the
 * standalone and recorded on issue #346: a patient on eight active orders asked "Can I give her
 * warfarin?" got the Major warfarin x ibuprofen bleeding interaction as the eighth chip, and the
 * answer — byte-identical on three consecutive runs — enumerated the first seven and stopped, so the
 * one finding that most answered the question was the one the clinician never read.
 *
 * <p>The two other interaction arms already sort before emitting; this one did not sort at all, so
 * "what is dropped is always the least severe" did not hold here even by accident.
 */
public class DrugInPlayFindingStrengthOrderTest {

	/**
	 * Warfarin's rows in the pinned DDInter excerpt, in the dataset's own order, put every Major after
	 * every Moderate and Minor — the shape issue #346 was reported on. Reading them here rather than
	 * asserting them keeps this case's PREMISE and its subject the same dataset (see
	 * {@link DrugReferenceTestSupport#ddinterEntries}): partner rows 2 Metformin (Moderate), 3
	 * Methotrexate (Minor), 13 Fluconazole (Major), 14 Amiodarone (Major), 15 Ibuprofen (Major).
	 *
	 * <p>Fluconazole and Amiodarone are chosen for the tie rather than any other pair of Majors
	 * because the dataset files them in the order Fluconazole, Amiodarone while their names sort the
	 * other way — so a tie broken on anything but the dataset's own order, a partner name included,
	 * moves them.
	 */
	private static final String QUESTION = "Can I give her warfarin?";

	private static List<String> leads(List<SafetyWarning> warnings) {
		List<String> leads = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			String detail = warning.getDetail();
			int dash = detail.indexOf(" — ");
			leads.add(warning.getSeverity() + " | " + (dash < 0 ? detail : detail.substring(0, dash)));
		}
		return leads;
	}

	private static List<SafetyWarning> chips() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService()).validate("",
			QUESTION,
			DrugReferenceTestSupport.ctx(60, 70.0,
				DrugReferenceTestSupport.set("Metformin 500mg", "Methotrexate 2.5mg", "Fluconazole 150mg",
					"Amiodarone 200mg", "Ibuprofen 400mg"),
				null, null, null));
	}

	/**
	 * The descending half. Every Major precedes the Moderate, which precedes the Minor — asserted as
	 * the whole rendered list rather than as a spot check, so a chip appearing, vanishing or changing
	 * its rating fails here loudly instead of leaving an order assertion vacuously satisfied.
	 */
	@Test
	public void theArmStatesItsMostSevereFindingFirst() {
		assertEquals(java.util.Arrays.asList(
			"Major | Warfarin interacts with active order fluconazole",
			"Major | Warfarin interacts with active order amiodarone",
			"Major | Warfarin interacts with active order ibuprofen",
			"Moderate | Warfarin interacts with active order metformin",
			"Minor | Warfarin interacts with active order methotrexate"),
			leads(chips()),
			"the drug-in-play arm's chips reach the prompt in this order, so the strongest finding must "
					+ "be the one a truncated answer keeps, not the one the knowledge base filed first "
					+ "(issue #346)");
	}

	/**
	 * The stability half, stated as its own case because it fails to a different mutation: the
	 * descending assertion above is satisfied by any sort that puts the three Majors first, including
	 * one that reorders them among themselves. Fluconazole precedes Amiodarone in the dataset and
	 * follows it alphabetically, so this reddens on a tie broken by partner name — and on a sort that
	 * is not stable — while staying silent about which of them is the more severe, because the
	 * dataset does not say.
	 */
	@Test
	public void partnersTiedOnStrengthKeepTheDatasetsOwnOrder() {
		List<String> leads = leads(chips());
		assertTrue(leads.indexOf("Major | Warfarin interacts with active order fluconazole")
				< leads.indexOf("Major | Warfarin interacts with active order amiodarone"),
			"the dataset files Fluconazole before Amiodarone and rates them alike, so nothing about this "
					+ "arm's ordering may separate them, was: " + leads);
	}

	/**
	 * The half that decides what "strongest" MEANS, and the only arrangement in which ordering on the
	 * rating and ordering on the finding disagree.
	 *
	 * <p>This is the arm that FOLDS, and a folded chip's rating deliberately understates it: the chip
	 * goes on reporting the RULE's rating while the class arm's unrated duplicate-therapy relationship
	 * rides along beside it, so a Minor rule folded with a class join states {@code STRENGTH_WITHHOLD}
	 * in the record the model reads. Simvastatin is rated Minor against both partners and the dataset
	 * files metformin first, so the ratings cannot separate the two chips and a severity-only sort
	 * leaves them as they came; only asking {@code licensesWithholding} promotes the finding that is
	 * actually a reason to withhold. Delete that branch of {@code FINDING_STRENGTH_DESCENDING} and
	 * this case reddens while the rest of the suite stays green.
	 */
	@Test
	public void aFoldedCautionOutranksAPlainOne() throws java.io.IOException {
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
						.ddiFixtureEntries(DrugReferenceTestSupport.DDI_FOLDED_CAUTION_ORDER)))
				.validate("", "Can I give her simvastatin?",
					DrugReferenceTestSupport.rawContextNaming(60, 70.0, "Metformin 500mg",
						"Atorvastatin 20mg"));

		assertEquals(2, warnings.size(), "two active partners must raise two chips, was: " + warnings);
		assertEquals("Minor", warnings.get(0).getSeverity(),
			"the knowledge base rates both pairs Minor, so the RATING cannot be what ordered them, was: "
					+ warnings);
		assertEquals("Minor", warnings.get(1).getSeverity(),
			"the knowledge base rates both pairs Minor, so the RATING cannot be what ordered them, was: "
					+ warnings);
		assertTrue(warnings.get(0).getDetail().contains("Atorvastatin"),
			"the folded chip states a relationship the data does not rate, which is a reason to withhold"
					+ " rather than a caution, so it leads a plain Minor the dataset files ahead of it,"
					+ " was: " + warnings.get(0).getDetail());
		assertTrue(warnings.get(0).carriesUnratedRelationship(),
			"and it leads BECAUSE of the fold, so the fold must be what this chip carries, was: "
					+ warnings.get(0).getDetail());
		assertTrue(warnings.get(1).getDetail().contains("Metformin"),
			"the plain Minor caution follows it, was: " + warnings.get(1).getDetail());
	}
}
