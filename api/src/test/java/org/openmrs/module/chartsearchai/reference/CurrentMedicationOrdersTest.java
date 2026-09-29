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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A contraindication chip about one of the patient's own active orders names EVERY order it is about,
 * each by its display and its uuid (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/552">#552</a>).
 *
 * <p><b>The case.</b> An order for <em>Advil 400mg</em> raises a chip whose {@code drug} is the substance
 * {@code Ibuprofen}, and before this key nothing on the chip said which of her prescriptions it came from:
 * {@code aboutACurrentMedication} says that it is one of them, not which one, and {@code chartOrderBridges}
 * is built by the interaction and condition-mediated arms alone. A client had to resolve {@code drug}
 * against her orders itself, which is a second resolution that can disagree with the module's (#151).
 *
 * <p>Every case drives the real validator over the bundled curated dataset, whose Ibuprofen entry carries
 * the brands {@code advil}, {@code brufen} and {@code nurofen} as aliases and {@code M01AE01} as its code, and
 * a chart carrying one {@code ActiveDrugOrder} per prescription — the shape production always builds.
 */
public class CurrentMedicationOrdersTest {

	private static final PatientClinicalContext.ActiveDrugOrder ADVIL =
			DrugReferenceTestSupport.activeOrder("uuid-advil", "Advil 400mg", "advil");

	/** Two prescriptions under ONE display — so a key de-duplicated on the display would list one. */
	private static final PatientClinicalContext.ActiveDrugOrder NUROFEN_MORNING =
			DrugReferenceTestSupport.activeOrder("uuid-nurofen-morning", "Nurofen 200mg", "nurofen");

	private static final PatientClinicalContext.ActiveDrugOrder NUROFEN_EVENING =
			DrugReferenceTestSupport.activeOrder("uuid-nurofen-evening", "Nurofen 200mg", "nurofen");

	/** An order the module could read no name for, known by its code alone — PatientClinicalContextBuilder's
	 *  own stand-in display, {@code codeOnlyDisplay}. */
	private static final PatientClinicalContext.ActiveDrugOrder CODED_ONLY =
			PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("uuid-coded-only",
				PatientClinicalContextBuilder.codeOnlyDisplay(DrugReferenceTestSupport.set("M01AE01")),
				DrugReferenceTestSupport.set("M01AE01"));

	/** An order of another substance, which no ibuprofen chip is about. */
	private static final PatientClinicalContext.ActiveDrugOrder METFORMIN =
			DrugReferenceTestSupport.activeOrder("uuid-metformin", "Metformin 500mg", "metformin");

	private static DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
	}

	/** Her four ibuprofen prescriptions and a metformin one, and a recorded ibuprofen allergy. */
	private static PatientClinicalContext fourIbuprofenOrdersAndAnAllergy() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("advil 400mg", "nurofen 200mg", "metformin 500mg"),
				DrugReferenceTestSupport.set("M01AE01"), DrugReferenceTestSupport.set("ibuprofen"), null,
				Arrays.asList(ADVIL, METFORMIN, NUROFEN_MORNING, CODED_ONLY, NUROFEN_EVENING));
	}

	private static SafetyWarning.CurrentMedicationOrder order(PatientClinicalContext.ActiveDrugOrder order) {
		return new SafetyWarning.CurrentMedicationOrder(order.getDisplay(), order.getUuid());
	}

	private static List<SafetyWarning> standingAlerts(PatientClinicalContext chart) {
		DrugSafetyValidator.StandingChartAlerts standing = curatedValidator().standingChartAlerts(chart);
		assertTrue(standing.isScreened(), "precondition: the chart must have been screened");
		return standing.getAlerts();
	}

	@Test
	public void aChipAboutHerOwnPrescriptionNamesEveryOrderItCoversWithItsUuid() {
		List<SafetyWarning> alerts = standingAlerts(fourIbuprofenOrdersAndAnAllergy());

		assertFalse(alerts.isEmpty(), "precondition: her ibuprofen allergy raises a standing alert");
		for (SafetyWarning alert : alerts) {
			assertEquals("Ibuprofen", alert.getDrug(),
					"precondition: the chip names the substance, which none of her orders' displays spells: "
							+ alert);
			assertTrue(alert.isAboutACurrentMedication(), "precondition: raised from her own orders: " + alert);
			assertEquals(Arrays.asList(order(ADVIL), order(NUROFEN_MORNING), order(CODED_ONLY),
					order(NUROFEN_EVENING)), alert.currentMedicationOrders(),
					"every one of her orders the chip is about, in her chart's order, each by its display and "
							+ "uuid — two prescriptions sharing a display are two entries, an order known only by "
							+ "its code is listed by the uuid a client can link on, and her metformin is not "
							+ "listed: " + alert);
		}
	}

	@Test
	public void aChipAboutADrugTheQuestionProposesNamesNoOrder() {
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("ibuprofen"), null,
				Collections.<PatientClinicalContext.ActiveDrugOrder> emptyList());

		List<SafetyWarning> chips = DrugReferenceTestSupport.contraindications(
				curatedValidator().validate("", "Can I give her ibuprofen?", chart));

		assertFalse(chips.isEmpty(), "precondition: proposing a drug she is allergic to raises a chip");
		for (SafetyWarning chip : chips) {
			assertFalse(chip.isAboutACurrentMedication(), "precondition: a proposal, not her medication: " + chip);
			assertEquals(Collections.emptyList(), chip.currentMedicationOrders(),
					"a chip no order of hers is behind names none: " + chip);
		}
	}

	@Test
	public void theAnswerStillNamesEachPrintableDisplayOnce() {
		// The statement ConflictingOrderStatement appends reads the same stamp, and prints what her chart
		// DISPLAYS: an order known only by its code has no name to print, and two prescriptions under one
		// display are one name. Widening the published list must not change that sentence.
		List<SafetyWarning> alerts = standingAlerts(fourIbuprofenOrdersAndAnAllergy());

		ConflictingOrderStatement.Stated stated = ConflictingOrderStatement.state("any allergies?",
			"She is allergic to ibuprofen.", alerts);

		assertTrue(stated.getAnswer().startsWith(
			"She is allergic to ibuprofen. Currently prescribed: Advil 400mg, Nurofen 200mg. "),
				"was: " + stated.getAnswer());
		assertFalse(stated.getAnswer().contains("ATC"), "was: " + stated.getAnswer());
		assertEquals(stated.getAnswer().indexOf(ConflictingOrderStatement.CURRENTLY_PRESCRIBED),
				stated.getAnswer().lastIndexOf(ConflictingOrderStatement.CURRENTLY_PRESCRIBED),
				"one statement of her orders, however many chips of the substance there are: " + stated.getAnswer());
	}
}
