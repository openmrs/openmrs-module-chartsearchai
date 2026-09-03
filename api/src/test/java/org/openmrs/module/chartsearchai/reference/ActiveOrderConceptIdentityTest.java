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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.DrugOrder;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsConstants;

/**
 * Issue #353: {@link PatientClinicalContextBuilder} records WHICH concept an active drug order was
 * written against, so the order can join the reference data by identity rather than by whichever of
 * that concept's names the session's locale elects.
 *
 * <p><b>Driven through the real builder</b>, like {@code ActiveOrderAdministrationTermsTest} and
 * {@code NonCodedDrugOrderNameTest} beside it, because the fact being pinned is that the value is READ
 * off a real {@code DrugOrder} — a hand-built {@code ActiveDrugOrder} carries whatever uuid the test
 * hands it and would pin nothing. The resolution matters and is the reason this file exists rather
 * than one assertion appended elsewhere: an order carrying a coded {@code Drug} has TWO concepts, and
 * only one of them is the concept the ATC codes beside this field were read off.
 */
public class ActiveOrderConceptIdentityTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN), the concept on patient 7's single active drug order (order 111). */
	private static final int ORDERED_CONCEPT = 88;

	/** How a francophone dictionary spells that concept — added to it by this class's locale case, and
	 *  written without its accents so the file needs no encoding of its own to say what it means. */
	private static final String FRENCH_SPELLING = "Acide acetylsalicylique";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * The discriminating case. Where the order carries a coded {@code Drug}, the builder resolves the
	 * concept to {@code drugOrder.getDrug().getConcept()} and reads the order's ATC codes off THAT —
	 * so the concept recorded here has to be the same one, or this join and the ATC join would be
	 * keyed on two different concepts for one prescription, silently, which is issue #151's shape.
	 *
	 * <p>The two concepts are made to DIFFER first, because in the standard dataset they need not, and
	 * a case where they agree passes for a builder reading either one.
	 */
	@Test
	public void theConceptRecordedIsTheDrugsConceptAndNotTheOrdersWhereTheyDiffer() {
		Concept otherConcept = aConceptOtherThanTheOrdersOwn();
		pointTheDrugAt(otherConcept.getConceptId());

		DrugOrder order111 = (DrugOrder) Context.getOrderService().getOrder(111);
		assertNotEquals(order111.getConcept().getUuid(), order111.getDrug().getConcept().getUuid(),
			"the premise: the order's concept and its drug's concept must differ, or this case passes"
					+ " for a builder reading either of them");

		assertEquals(order111.getDrug().getConcept().getUuid(), theOrder().getConceptUuid(),
			"the concept recorded must be the one the order's ATC codes were read off");
	}

	/**
	 * An order with no coded {@code Drug} — the non-coded shape {@code NonCodedDrugOrderNameTest}
	 * works on — falls back to the order's own concept, which is again the concept the ATC codes came
	 * from.
	 */
	@Test
	public void anOrderWithNoCodedDrugRecordsItsOwnConcept() {
		Context.getAdministrationService().executeSQL(
			"update drug_order set drug_inventory_id = null, drug_non_coded = 'Cotrimoxazole 960mg'"
					+ " where order_id = 111",
			false);
		Context.flushSession();
		Context.clearSession();

		assertEquals(Context.getConceptService().getConcept(ORDERED_CONCEPT).getUuid(),
			theOrder().getConceptUuid(),
			"with no coded drug the order's own concept is the one the codes came from");
	}

	/**
	 * The issue #290 rung carries it too. That order reaches the per-order list with an EMPTY name set
	 * — nothing about it can be matched by name at all — so the concept key is the only join left to
	 * it, which makes this the rung the change matters most for. Pinned separately because the two
	 * rungs are two call sites: dropping the argument at this one leaves the whole suite green while
	 * silently emptying the field for exactly the orders that most need it.
	 */
	@Test
	public void anOrderNoNameCouldBeReadForRecordsItsConceptToo() {
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, "N02BA01");
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = null where order_id = 111", false);
		Context.getAdministrationService()
				.executeSQL("update concept_name set voided = 1 where concept_id = " + ORDERED_CONCEPT,
					false);
		Context.flushSession();
		Context.clearSession();

		PatientClinicalContext.ActiveDrugOrder order = theOrder();
		assertFalse(order.hasKnownName(),
			"the arrangement must reach the code-only rung, was: " + order);
		assertEquals(Context.getConceptService().getConcept(ORDERED_CONCEPT).getUuid(),
			order.getConceptUuid(), "an order with no readable name still records its concept");
	}

	/**
	 * The property the whole leg rests on, MEASURED for one order across two locales rather than argued
	 * from the type: the recorded NAMES move with the session locale and the recorded CONCEPT does not.
	 * One arrangement is not a proof about every dictionary; what it rules out is the join being keyed,
	 * anywhere on this path, on something a locale can respell.
	 *
	 * <p>That asymmetry is the ticket's own report — same order, same question, same patient, {@code en}
	 * one chip and {@code fr} none. Every other case for this issue models the francophone order by
	 * handing the builder a name the fixture does not carry, which assumes the thing being claimed;
	 * this is the only case in the module that varies a session locale (grep {@code Context.setLocale}).
	 *
	 * <p>The order's coded drug is removed first so that its only name comes from the concept: with a
	 * {@code Drug} row attached, {@code addDrugName} contributes that row's own name, which no locale
	 * respells, and the two name sets would differ by an addition rather than by the spelling the
	 * session elects.
	 *
	 * <p><b>What this does NOT do</b> is screen the ticket's order in {@code fr} end to end against a
	 * real dictionary. Those are the ticket's six verification rows, they belong on the standalone, and
	 * nothing here stands in for them.
	 */
	@Test
	public void theRecordedConceptIsTheSameWhateverNameTheSessionLocaleElects() {
		// Appended to the list already configured rather than replacing it: core refuses an allowed
		// list that drops the installation's own default locale (localeListNotIncludingDefaultLocale),
		// and the default here is the one this list already carries.
		String allowed = Context.getAdministrationService()
				.getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_LOCALE_ALLOWED_LIST);
		Context.getAdministrationService().setGlobalProperty(
			OpenmrsConstants.GLOBAL_PROPERTY_LOCALE_ALLOWED_LIST, allowed + ", fr");
		Context.getAdministrationService().executeSQL(
			"update drug_order set drug_inventory_id = null, drug_non_coded = null where order_id = 111",
			false);
		Concept ordered = Context.getConceptService().getConcept(ORDERED_CONCEPT);
		ordered.setFullySpecifiedName(new ConceptName(FRENCH_SPELLING, Locale.FRENCH));
		Context.getConceptService().saveConcept(ordered);
		Context.flushSession();
		Context.clearSession();
		String conceptUuid = Context.getConceptService().getConcept(ORDERED_CONCEPT).getUuid();

		Context.setLocale(Locale.ENGLISH);
		PatientClinicalContext.ActiveDrugOrder inEnglish = theOrder();
		Context.setLocale(Locale.FRENCH);
		PatientClinicalContext.ActiveDrugOrder inFrench = theOrder();

		// Asserted as membership and not merely as inequality: an fr session that read NO name at all
		// would also make the two sets differ, and would send the order down the code-only rung
		// instead — which is a different fact and would leave this case passing vacuously.
		assertTrue(namesInclude(inFrench, FRENCH_SPELLING),
			"the premise: the fr session must elect the French spelling, was: " + inFrench.getNames());
		assertFalse(namesInclude(inEnglish, FRENCH_SPELLING),
			"and the en session must not, or the locale is not what moved the names, was: "
					+ inEnglish.getNames());
		assertEquals(conceptUuid, inEnglish.getConceptUuid(),
			"the concept key must be recorded in the locale the bridge's own names are written in");
		assertEquals(conceptUuid, inFrench.getConceptUuid(),
			"and must be the SAME in the locale that spells the order differently, which is the whole"
					+ " of what this leg claims over the name key");
	}

	/** Whether the order records {@code name}, compared without case. The case the dictionary hands
	 *  back for a name it was given is not what this case is about, and asserting it would make the
	 *  premise depend on a core behaviour nothing here has measured. */
	private static boolean namesInclude(PatientClinicalContext.ActiveDrugOrder order, String name) {
		for (String recorded : order.getNames()) {
			if (recorded.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** The one active drug order patient 7 has, off the real builder. */
	private PatientClinicalContext.ActiveDrugOrder theOrder() {
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertEquals(1, context.getActiveDrugOrders().size(),
			"the arrangement is one active drug order, was: " + context.getActiveDrugOrders());
		return context.getActiveDrugOrders().get(0);
	}

	/** Any concept of the standard dataset that is not the one order 111 is written against — read
	 *  from the database rather than named, so the case does not pin a second dataset row. */
	private Concept aConceptOtherThanTheOrdersOwn() {
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
			"select concept_id from concept where retired = 0 and concept_id <> " + ORDERED_CONCEPT
					+ " order by concept_id limit 1",
			true);
		assertFalse(rows.isEmpty(), "the standard dataset must carry a second concept");
		Concept other = Context.getConceptService()
				.getConcept(((Number) rows.get(0).get(0)).intValue());
		assertNotNull(other, "the second concept must be readable");
		return other;
	}

	private void pointTheDrugAt(int conceptId) {
		Context.getAdministrationService().executeSQL(
			"update drug set concept_id = " + conceptId
					+ " where drug_id = (select drug_inventory_id from drug_order where order_id = 111)",
			false);
		Context.flushSession();
		Context.clearSession();
	}
}
