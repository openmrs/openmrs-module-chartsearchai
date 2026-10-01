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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A chip about one of her OTHER medications, beside a response about a different drug, says so: a
 * contraindication the check of her own prescriptions against her own records raised, for a substance no
 * drug the question or the answer put in play is of, on a response that put one in play.
 *
 * <p><b>The case.</b> Asked "Can I give her ibuprofen?", a 12-year-old's answer named her lidocaine and
 * tiotropium orders as interaction partners, which put both in the response's subject matter, so the chips
 * "The patient has a recorded allergy to Lidocaine." and "…to Tiotropium." were raised beside it. Both
 * answer {@code aboutACurrentMedication} true — and so does a chip about a drug the question names that she
 * takes (issue #402), which IS what was asked. Nothing on the chip told the two apart, so a client could not
 * set the first kind beside the answer without hiding the second.
 *
 * <p>Every case drives the real validator over the bundled curated dataset (its Ibuprofen entry carries the
 * brand {@code advil}), over a chart carrying one {@code ActiveDrugOrder} per prescription.
 */
public class OtherMedicationChipTest {

	private static final PatientClinicalContext.ActiveDrugOrder ADVIL =
			DrugReferenceTestSupport.activeOrder("uuid-advil", "Advil 400mg", "advil");

	private static final PatientClinicalContext.ActiveDrugOrder METFORMIN =
			DrugReferenceTestSupport.activeOrder("uuid-metformin", "Metformin 500mg", "metformin");

	private static DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
	}

	/** Her Advil and metformin prescriptions, and a recorded ibuprofen allergy. */
	private static PatientClinicalContext advilAndAnIbuprofenAllergy() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("advil 400mg", "metformin 500mg"), DrugReferenceTestSupport.set("M01AE01"),
				DrugReferenceTestSupport.set("ibuprofen"), null, Arrays.asList(ADVIL, METFORMIN));
	}

	private static List<SafetyWarning> contraindications(String answer, String question) {
		return DrugReferenceTestSupport.contraindications(
				curatedValidator().validate(answer, question, advilAndAnIbuprofenAllergy()));
	}

	@Test
	public void aChipAboutHerOwnPrescriptionBesideAQuestionAboutAnotherDrugSaysSo() {
		List<SafetyWarning> chips = contraindications("", "Can I give her amoxicillin given her allergies?");

		assertFalse(chips.isEmpty(), "precondition: the question's allergies put her ibuprofen allergy in scope");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.isAboutACurrentMedication(), "precondition: raised from her Advil order: " + chip);
			assertTrue(chip.isAboutAnotherOfHerMedications(),
					"a finding about her Advil, on a question about amoxicillin, is about another of her "
							+ "medications: " + chip);
		}
	}

	@Test
	public void aChipAboutTheDrugTheQuestionNamesIsNotAboutAnotherMedicationEvenWhereSheTakesIt() {
		for (String question : Arrays.asList("Can I give her ibuprofen?", "Is her Advil safe given her allergies?")) {
			List<SafetyWarning> chips = contraindications("", question);

			assertFalse(chips.isEmpty(), "precondition: her allergy raises a chip about the drug, " + question);
			for (SafetyWarning chip : chips) {
				assertTrue(chip.isAboutACurrentMedication(),
						"precondition: her Advil order establishes she takes ibuprofen (#402), " + question + ": "
								+ chip);
				assertFalse(chip.isAboutAnotherOfHerMedications(),
						"the drug the question asks about is not ANOTHER medication, " + question + ": " + chip);
				assertFalse(chip.isAboutADrugOtherThanTheOneProposed(), question + ": " + chip);
			}
		}
	}

	@Test
	public void aResponseThatPutsNoDrugInPlayHasNoOtherMedication() {
		// Asked about her own medications and nothing else, a chip about one of them IS the answer.
		List<SafetyWarning> chips = contraindications("", "Is she allergic to any of her current medications?");

		assertFalse(chips.isEmpty(), "precondition: her ibuprofen allergy against her Advil raises a chip");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.isAboutACurrentMedication(), "precondition: raised from her Advil order: " + chip);
			assertFalse(chip.isAboutAnotherOfHerMedications(),
					"no drug is in play, so there is no other drug for this one to be beside: " + chip);
		}
	}

	@Test
	public void aStandingAlertIsNotAboutAnotherMedication() {
		DrugSafetyValidator.StandingChartAlerts standing =
				curatedValidator().standingChartAlerts(advilAndAnIbuprofenAllergy());
		assertTrue(standing.isScreened(), "precondition: the chart must have been screened");
		assertFalse(standing.getAlerts().isEmpty(), "precondition: her allergy raises a standing alert");
		for (SafetyWarning alert : standing.getAlerts()) {
			assertFalse(alert.isAboutAnotherOfHerMedications(),
					"a standing alert has no question, so no drug for it to be beside: " + alert);
		}
	}
}
