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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The MODULE half of the measurement issue #347's fix rests on: {@code PatientClinicalContextBuilder}
 * records a name for an active order that the order's own DISPLAY does not carry — the ordered
 * concept's — so an order the chart shows as a brand is reachable by the substance's generic name
 * through {@link PatientClinicalContext.ActiveDrugOrder#getNames()} and through nothing a reader
 * sees.
 *
 * <p><b>Why it is pinned separately.</b> The root cause has two halves and they are facts about two
 * different components. {@code QuerystoreDrugOrderDisplayedNameTest} pins the OTHER half against
 * querystore's real serializer — a {@code drug_order} record renders exactly one name. This one pins
 * that the module's recorded-name set is strictly wider than that, against the real builder, so
 * "{@code recordsANameOfAny} was satisfied by a name no reader could see" is measured at both ends
 * rather than asserted in a javadoc at either. CLAUDE.md: pin the PREMISE, not the conclusion.
 *
 * <p><b>What it does not claim.</b> That the wider set is wrong. It is right for what it is for —
 * {@code resolvesFrom}'s name leg asks which orders a substance was resolved FROM, and an order is
 * this substance's however it was recorded. What #347 changed is the narrower question of whether
 * SAYING so is worth a citable record's budget, and that question is about the one name a reader is
 * shown. {@code DrugSafetyValidator.displaysANameOfAny} is where the two are told apart.
 *
 * <p>Driven through {@code PatientClinicalContextBuilder.build} on a real patient of the standard
 * test dataset. The concept is renamed in the database rather than in memory because
 * {@code addConceptName} reads it through {@code Concept.getName()} under the session's own locale
 * resolution, which a detached in-memory edit does not exercise.
 */
public class RecordedOrderNameBeyondItsDisplayTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order, order 111. */
	private static final int ORDERED_CONCEPT = 88;

	/** The generic the concept is renamed to, standing for the ticket's {@code Ibuprofen}. */
	private static final String GENERIC = "Chartsearchaicillin";

	/** The brand the drug row is renamed to, standing for the ticket's {@code Advil 400mg}. */
	private static final String BRAND = "Brandolin 400mg";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * Makes order 111 the ticket's shape: a coded drug row whose NAME is a brand, on a concept named
	 * with the generic. Both are renamed, because the fixture's drug row and concept share a name and
	 * the divergence between them is the whole subject.
	 *
	 * <p>The concept rename goes through {@code DrugReferenceTestSupport.nameTheConcept}, which is
	 * where the reason for renaming only the FULLY SPECIFIED row lives.
	 */
	private void brandTheOrderOnAGenericConcept() {
		DrugReferenceTestSupport.nameTheConcept(ORDERED_CONCEPT, GENERIC);
		Context.getAdministrationService().executeSQL("update drug set name = '" + BRAND
				+ "' where drug_id = (select drug_inventory_id from drug_order where order_id = 111)",
			false);
		Context.flushSession();
		Context.clearSession();
	}

	/** The one active drug order patient 7 has, off the real builder. */
	private PatientClinicalContext.ActiveDrugOrder theOrder() {
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertEquals(1, context.getActiveDrugOrders().size(),
			"precondition: this fixture patient has exactly one active drug order, was: "
					+ context.getActiveDrugOrders());
		return context.getActiveDrugOrders().get(0);
	}

	@Test
	public void theOrderIsDisplayedByItsBrandAndNotByTheConceptsGenericName() {
		brandTheOrderOnAGenericConcept();

		PatientClinicalContext.ActiveDrugOrder order = theOrder();

		assertNotNull(order.getDisplay(), "the order must have a display to be about");
		assertEquals(BRAND, order.getDisplay(),
			"the drug row's name is the display, which is also the one name querystore renders into "
					+ "the drug_order record (QuerystoreDrugOrderDisplayedNameTest). Was: "
					+ order.getDisplay());
		assertNotEquals(GENERIC, order.getDisplay(), "precondition: the two names must differ");
	}

	@Test
	public void yetTheConceptsGenericNameIsAmongTheNamesTheModuleRecordsForIt() {
		// This is exactly what silenced the bridge clause before #347: recordsANameOfAny asked whether
		// ANY of these reaches the substance, and the generic does, so an order the chart shows only as
		// a brand counted as one that already names its substance. Neuter displaysANameOfAny back to a
		// getNames() fold and OneOrderNameAcrossAnswerAndChipTest is what reddens; this case is why.
		brandTheOrderOnAGenericConcept();

		PatientClinicalContext.ActiveDrugOrder order = theOrder();

		// Case-folded on both sides: ActiveDrugOrder's constructor lower-cases the name set it is handed
		// (PatientClinicalContext's `this.names = lower(names)`), because that set is matched against
		// chart prose. Not addRaw, which only collapses whitespace and trims. Which STRING is present is the subject here;
		// its casing is not, and asserting the raw spelling would pin an unrelated normalization.
		assertTrue(order.getNames().contains(GENERIC.toLowerCase(Locale.ROOT)),
			"the builder records the ordered concept's name beside the drug row's, so the recorded set "
					+ "is strictly wider than the one name a chart record shows. Was: " + order.getNames());
		assertTrue(order.getNames().contains(BRAND.toLowerCase(Locale.ROOT)),
			"and it records the drug row's name too, so the two are a superset rather than an "
					+ "alternative. Was: " + order.getNames());
	}
}
