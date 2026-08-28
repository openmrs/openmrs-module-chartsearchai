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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #234: whether {@link PatientClinicalContextBuilder} carries what the chart records about WHERE
 * an active drug order is applied.
 *
 * <p>The class arm's narrowing is decided by
 * {@code DrugReference.codesForRecordedAdministration} and exercised over knowledge-base slices in
 * {@code UnmappedOrderAdministrationSiteTest}; what it needs and never had is the data. The builder
 * read an order's names and its concept's ATC codes and nothing else, so
 * {@code ActiveDrugOrder} had nothing to narrow on — which is why the ticket calls the route "not
 * available to the module at all".
 *
 * <p><b>Both legs, driven through the real {@link PatientClinicalContextBuilder#build(Patient)}</b>,
 * because they come from two different tables and each covers a shape the other cannot. The ROUTE is a
 * column on {@code drug_order}, so it is the only leg a non-coded order can use; the dose FORM is a
 * column on {@code drug}, and it is the only leg that reaches the skin, since the 3.7.1 reference
 * dictionary's 17-member route set names no cutaneous route at all (measured 2026-08-28 against the
 * demo database). A hand-built {@code ActiveDrugOrder} would bypass the whole change, which is the same
 * reason {@code NonCodedDrugOrderNameTest} is context-sensitive.
 *
 * <p>Patient 7's single active drug order (order 111, drug "ASPIRIN", concept 88) is the arrangement,
 * with the two columns set the way the existing context-sensitive cases set theirs.
 */
public class ActiveOrderAdministrationTermsTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN), the concept behind patient 7's single active drug order. */
	private static final int ORDERED_CONCEPT = 88;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void anOrderWithNeitherRecordedCarriesNothing() {
		// The state that must keep meaning "nothing is recorded" rather than "administered nowhere" —
		// it is the reading that narrows nothing, and measured on the 3.7.1 demo it is the state of 14
		// of its 46 active drug orders.
		recordNoRoute();

		assertEquals(Collections.<String> emptySet(), theOrder().getAdministrationTerms());
	}

	@Test
	public void aRouteThatNamesNoSiteIsCarriedAndSelectsNothing() {
		// Order 111 arrives with a route already, and its concept is named "unknown" — which is the
		// ordinary case and the reason the narrowing is decided by what a term NAMES rather than by
		// whether one was recorded. Carried, because whether the module can attribute it is not this
		// class's question; and selecting nothing, so the class arm keeps the answer it has today.
		Set<String> terms = theOrder().getAdministrationTerms();
		assertEquals(DrugReferenceTestSupport.set("unknown"), terms,
				"the standard dataset's own route for order 111");

		Set<String> everyRoute = DrugReferenceTestSupport.set("A01AC03", "D07AA02", "H02AB09");
		assertEquals(everyRoute, DrugReference.codesForRecordedAdministration(everyRoute, terms),
				"a term naming no site leaves the classification exactly as it was");
	}

	@Test
	public void theRouteConceptsNameIsCarried() {
		recordRoute("Bilateral eye administration");

		assertTrue(theOrder().getAdministrationTerms().contains("bilateral eye administration"),
				"was: " + theOrder().getAdministrationTerms());
	}

	@Test
	public void theDrugsDoseFormConceptsNameIsCarriedToo() {
		// The leg the route cannot supply. Not an alternative to the route but a second source: the case
		// below records both and expects both.
		recordNoRoute();
		recordDoseForm("Topical cream");

		assertTrue(theOrder().getAdministrationTerms().contains("topical cream"),
				"was: " + theOrder().getAdministrationTerms());
	}

	@Test
	public void bothAreCarriedTogetherWhenBothAreRecorded() {
		recordRoute("Nasal administration");
		recordDoseForm("Topical cream");

		assertEquals(DrugReferenceTestSupport.set("nasal administration", "topical cream"),
				theOrder().getAdministrationTerms());
	}

	@Test
	public void theTermsAreNormalizedTheSameWayTheOrdersNamesAre() {
		// Both sides of every comparison this feeds go through one normalizer, so a concept a dictionary
		// spelled with irregular whitespace or in a different case still matches a term (issue #293's
		// rule, applied to the strings this issue adds).
		recordRoute("Bilateral  EYE   administration");

		assertEquals(DrugReferenceTestSupport.set("bilateral eye administration"),
				theOrder().getAdministrationTerms());
	}

	@Test
	public void anOrderNoNameCouldBeReadForCarriesItTooThroughTheRealBuilder() {
		// The issue #290 rung, driven through the real builder rather than through the factory. It is
		// reached only by an order carrying ATC codes and no readable name, so the arrangement is
		// NamelessActiveOrderPartnerTest's — the coded drug and the free text cleared, the concept's
		// names voided — plus a WHOATC map so the order still has codes to be identified by.
		//
		// The factory case in UnmappedOrderAdministrationSiteTest pins the overload; without this one
		// the builder's USE of it is unpinned, and dropping the argument at that call site leaves the
		// whole suite green while silently emptying the field for exactly the orders it was added for.
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, "N02BA01");
		recordRoute("Nasal administration");
		makeTheOrderNameless();

		PatientClinicalContext.ActiveDrugOrder order = theOrder();
		assertFalse(order.hasKnownName(), "the arrangement must reach the code-only rung, was: " + order);
		assertEquals(DrugReferenceTestSupport.set("nasal administration"),
				order.getAdministrationTerms());
	}

	/** Order 111 with nothing left that can name it — the shape {@code namedByCodesOnly} stands in for.
	 *  The same three columns {@code NamelessActiveOrderPartnerTest} clears, and cleared for the reason
	 *  that file records: leaving {@code drug_non_coded} to the dataset would make the arrangement
	 *  contingent on data this file does not control. */
	private void makeTheOrderNameless() {
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = null where order_id = 111", false);
		Context.getAdministrationService()
				.executeSQL("update concept_name set voided = 1 where concept_id = " + ORDERED_CONCEPT,
					false);
		flush();
	}

	/** The one active drug order patient 7 has, off the real builder. */
	private PatientClinicalContext.ActiveDrugOrder theOrder() {
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertEquals(1, context.getActiveDrugOrders().size(),
				"the arrangement is one active drug order, was: " + context.getActiveDrugOrders());
		return context.getActiveDrugOrders().get(0);
	}

	/** Clears the route the standard dataset already gives order 111 — see
	 *  {@link #aRouteThatNamesNoSiteIsCarriedAndSelectsNothing}, which is where that pre-existing value
	 *  is pinned. */
	private void recordNoRoute() {
		Context.getAdministrationService()
				.executeSQL("update drug_order set route = null where order_id = 111", false);
		flush();
	}

	private void recordRoute(String conceptName) {
		Context.getAdministrationService().executeSQL("update drug_order set route = "
				+ concept(conceptName) + " where order_id = 111", false);
		flush();
	}

	private void recordDoseForm(String conceptName) {
		Context.getAdministrationService().executeSQL("update drug set dosage_form = "
				+ concept(conceptName) + " where drug_id ="
				+ " (select drug_inventory_id from drug_order where order_id = 111)", false);
		flush();
	}

	/** A concept to stand in for a route or a dose form, saved through the real {@code ConceptService}
	 *  rather than inserted by SQL, so it is a concept the platform would actually hand back — the
	 *  builder reads its {@code getName()}, which walks locales and falls back, and a hand-inserted row
	 *  can be missing what that walk needs. */
	private int concept(String name) {
		Concept concept = new Concept();
		concept.addName(new ConceptName(name, Locale.ENGLISH));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName("N/A"));
		concept.setConceptClass(Context.getConceptService().getConceptClassByName("Misc"));
		Concept saved = Context.getConceptService().saveConcept(concept);
		assertNotNull(saved.getConceptId());
		return saved.getConceptId();
	}

	private void flush() {
		Context.flushSession();
		Context.clearSession();
	}
}
