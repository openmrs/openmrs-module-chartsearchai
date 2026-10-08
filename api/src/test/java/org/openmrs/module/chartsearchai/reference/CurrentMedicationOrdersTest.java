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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * whose Amoxicillin entry carries {@code amoxil}, over a chart carrying one {@code ActiveDrugOrder} per
 * prescription — the shape production always builds.
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

	/** An order of a second substance her records also contraindicate, whose chip must list it and none of
	 *  her ibuprofen orders. */
	private static final PatientClinicalContext.ActiveDrugOrder AMOXIL =
			DrugReferenceTestSupport.activeOrder("uuid-amoxil", "Amoxil 500mg", "amoxil");

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
		List<SafetyWarning> alerts = standingAlerts(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("advil 400mg", "nurofen 200mg", "metformin 500mg", "amoxil 500mg"),
				DrugReferenceTestSupport.set("M01AE01"), DrugReferenceTestSupport.set("ibuprofen", "amoxicillin"),
				null, Arrays.asList(ADVIL, METFORMIN, NUROFEN_MORNING, AMOXIL, CODED_ONLY, NUROFEN_EVENING)));

		int amoxicillin = 0;
		int ibuprofen = 0;
		for (SafetyWarning alert : alerts) {
			assertTrue(alert.isAboutACurrentMedication(), "precondition: raised from her own orders: " + alert);
			if ("Amoxicillin".equals(alert.getDrug())) {
				amoxicillin++;
				assertEquals(Collections.singletonList(order(AMOXIL)), alert.currentMedicationOrders(),
						"a chip lists the orders of its own substance only: " + alert);
				continue;
			}
			ibuprofen++;
			assertEquals("Ibuprofen", alert.getDrug(),
					"precondition: the chip names the substance, which none of her orders' displays spells: "
							+ alert);
			assertEquals(Arrays.asList(order(ADVIL), order(NUROFEN_MORNING), order(CODED_ONLY),
					order(NUROFEN_EVENING)), alert.currentMedicationOrders(),
					"every one of her orders the chip is about, in her chart's order, each by its display and "
							+ "uuid — two prescriptions sharing a display are two entries, an order known only by "
							+ "its code is listed by the uuid a client can link on, and her metformin is not "
							+ "listed: " + alert);
		}
		assertTrue(ibuprofen > 0 && amoxicillin > 0,
				"precondition: both of her allergies raise a standing alert, were: " + alerts);
	}

	@Test
	public void aChipAboutADrugTheQuestionProposesNamesNoOrder() {
		// She has an order, of another substance, so an empty list is the arm's answer and not the chart's.
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("metformin 500mg"), null, DrugReferenceTestSupport.set("ibuprofen"),
				null, Collections.singletonList(METFORMIN));

		List<SafetyWarning> chips = DrugReferenceTestSupport.contraindications(
				curatedValidator().validate("", "Can I give her ibuprofen?", chart));

		assertFalse(chips.isEmpty(), "precondition: proposing a drug she is allergic to raises a chip");
		for (SafetyWarning chip : chips) {
			assertFalse(chip.isAboutACurrentMedication(), "precondition: a proposal, not her medication: " + chip);
			assertEquals(Collections.emptyList(), chip.currentMedicationOrders(),
					"a chip no order of hers is behind names none: " + chip);
		}
	}

	/**
	 * A drug the question puts in play that her own orders establish she takes is a finding about her medication
	 * (issue #402), raised by the drug-in-play arm rather than by the check of her prescriptions against her
	 * records — and it is the same chip #552 describes, so it names her order too: asked by the substance, and
	 * asked by the brand her order carries.
	 */
	@Test
	public void aDrugInPlayChipAboutHerOwnOrderListsIt() {
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("advil 400mg", "metformin 500mg"), null,
				DrugReferenceTestSupport.set("ibuprofen"), null, Arrays.asList(METFORMIN, ADVIL));

		for (String question : Arrays.asList("Can I give her ibuprofen?", "Is her Advil safe given her allergies?")) {
			List<SafetyWarning> chips = DrugReferenceTestSupport.contraindications(
					curatedValidator().validate("", question, chart));

			assertFalse(chips.isEmpty(), "precondition: her allergy raises a chip about the drug " + question);
			for (SafetyWarning chip : chips) {
				assertTrue(chip.isAboutACurrentMedication(),
						"precondition: her Advil order establishes she takes ibuprofen (#402), " + question + ": " + chip);
				assertEquals(Collections.singletonList(order(ADVIL)), chip.currentMedicationOrders(),
						"the chip names the order it is about, and not her metformin, " + question + ": " + chip);
			}
		}
	}

	/**
	 * Which of her orders a drug-in-play chip lists is which of them ESTABLISH she takes its substance — the
	 * orders its {@code aboutACurrentMedication} was decided on (ADR Decision 123) — and not every order her
	 * resolution reaches it through. The shipped knowledge base files the brand {@code Nexium} under
	 * Omeprazole and Esomeprazole, so her {@code Nexium 40mg} order resolves to omeprazole without naming it
	 * ({@code DrugInPlayHerOwnOrderReferentTest}); her {@code Omeprazole 20mg} order is what makes an
	 * omeprazole chip about her medication, and it is the one the chip names.
	 */
	@Test
	public void aDrugInPlayChipListsTheOrdersThatEstablishSheTakesItAndNotOneThatMerelyResolvesToIt() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext.ActiveDrugOrder omeprazole =
				DrugReferenceTestSupport.activeOrder("uuid-omeprazole", "Omeprazole 20mg", "omeprazole 20mg");
		PatientClinicalContext.ActiveDrugOrder nexium =
				DrugReferenceTestSupport.activeOrder("uuid-nexium", "Nexium 40mg", "nexium 40mg");
		PatientClinicalContext chart = service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("nexium 40mg", "omeprazole 20mg"), null,
				DrugReferenceTestSupport.set("omeprazole"), null, Arrays.asList(nexium, omeprazole)));
		String question = "Can I give her omeprazole?";
		Set<Object> asked = new HashSet<Object>();
		for (DrugReference entry : service.findImpliedByQuery(question)) {
			asked.add(entry.substanceGroupKey());
		}
		Set<Object> nexiumReadings = new HashSet<Object>();
		for (DrugReference entry : service.findImpliedByDrugName("Nexium 40mg")) {
			nexiumReadings.add(entry.substanceGroupKey());
		}
		assertTrue(!asked.isEmpty() && nexiumReadings.containsAll(asked),
				"precondition: her Nexium order resolves to the omeprazole the question names: asked " + asked
						+ ", Nexium " + nexiumReadings);

		List<SafetyWarning> chips = DrugReferenceTestSupport.contraindications(
				DrugReferenceTestSupport.validator(service).validate("", question, chart));

		assertFalse(chips.isEmpty(), "precondition: her omeprazole allergy raises a chip");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.isAboutACurrentMedication(),
					"precondition: her Omeprazole order establishes she takes it: " + chip);
			assertEquals(Collections.singletonList(new SafetyWarning.CurrentMedicationOrder("Omeprazole 20mg",
					"uuid-omeprazole")), chip.currentMedicationOrders(),
					"the order that establishes she takes it, and not the Nexium order that only resolves to it: "
							+ chip);
		}
	}

	@Test
	public void theAnswerStillNamesEachPrintableDisplayOnce() {
		// The statement ConflictingOrderStatement appends reads the same stamp, and prints what her chart
		// DISPLAYS: an order known only by its code has no name to print, and two prescriptions under one
		// display are one name. Widening the published list must not change that sentence.
		List<SafetyWarning> alerts = standingAlerts(fourIbuprofenOrdersAndAnAllergy());

		ConflictingOrderStatement.Stated stated = ConflictingOrderStatement.state("any allergies?",
			"She is allergic to ibuprofen.", alerts, Collections.<String, Integer> emptyMap());

		assertTrue(stated.getAnswer().startsWith(
			"She is allergic to ibuprofen.\n\nCurrent orders that conflict with the patient's records:\n"
					+ "- Advil 400mg; Nurofen 200mg: "),
				"was: " + stated.getAnswer());
		assertFalse(stated.getAnswer().contains("ATC"), "was: " + stated.getAnswer());
		assertEquals(stated.getAnswer().indexOf("\n- "), stated.getAnswer().lastIndexOf("\n- "),
				"one statement of her orders, however many chips of the substance there are: " + stated.getAnswer());
	}

	/**
	 * A chip that is NOT about her medication names no order, even where her orders establish she takes its
	 * substance. {@code CurrentMedicationFindingStrengthTest}'s sibling-row arrangement: her gel order
	 * establishes levoketoconazole, so the drug-in-play arm records that order for the substance, while the
	 * question's "levo" proposes the tablets row, a sibling row of the same substance. The chip that
	 * survives is therefore a proposal ({@code aboutACurrentMedication} false) under the same
	 * {@code substanceGroupKey}, and the recorded list must not be stamped on it. Unlike the flattened chart
	 * those cases use, this chart carries her order, so a list exists that could leak.
	 */
	@Test
	public void aProposalChipOfASubstanceHerOrdersEstablishNamesNoOrder() throws java.io.IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.fixtureEntries(
					"chartsearchai-test/drug-reference-rule-rows-rank-crossing.json"));
		PatientClinicalContext.ActiveDrugOrder gel =
				DrugReferenceTestSupport.activeOrder("uuid-gel", "Levoketoconazole (gel)");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Levoketoconazole (gel)"), null,
				DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null, Collections.singletonList(gel));

		for (String question : Arrays.asList("Is it safe to give her levo, and what other medications is she on?",
				"Is it safe to give her levo? Does she have any allergies?")) {
			List<SafetyWarning> chips = DrugReferenceTestSupport.contraindications(
					DrugReferenceTestSupport.validator(service).validate("", question, chart));

			assertEquals(1, chips.size(), "precondition: one substance is one chip, " + question + ": " + chips);
			SafetyWarning chip = chips.get(0);
			assertFalse(chip.isAboutACurrentMedication(),
					"precondition: the question proposed this substance, " + question + ": " + chip);
			assertEquals(Collections.emptyList(), chip.currentMedicationOrders(),
					"a proposal chip names none of her orders, " + question + ": " + chip);
		}
	}
}
