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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The builder half of issue #132, end to end: an active order's ATC codes must reach the safety layer
 * attributed to THAT order, not only flattened into one context-wide set.
 *
 * <p>Context-sensitive on purpose. {@link ActiveOrderAtcAttributionTest} pins what the validator does
 * with attributed codes, but only the real {@link PatientClinicalContextBuilder} — reading real
 * {@code ConceptMap}s off a real order's concept through the real {@code OrderService} — can show that
 * the association exists in production at all. Run through the PUBLIC entry
 * {@link DrugSafetyValidator#validate(String, String, Patient)}, so the whole chain executes: the
 * feature gate, the builder's single {@code getActiveOrders} pass, and the screening arm.
 *
 * <p>Patient 7 of the standard test dataset has exactly ONE active drug order (order 111, drug
 * "ASPIRIN", concept 88), which is what makes it the shape the issue describes: a patient on a single
 * medication. Mapping that one concept to the ATC codes of TWO interacting reference entries —
 * Simvastatin {@code C10AA01} × Clarithromycin {@code J01FA09}, a real Major row in the bundled DDInter
 * sample whose rule names its partner by that very code — is the whole arrangement. Before the fix the
 * screen reported those two as an interacting pair, i.e. one order witnessing itself through the ATC
 * leg; the same shape reproduced live on the 3.7.1 standalone (2026-08-05) with a purpose-made
 * "Zolvimix" concept.
 *
 * <p>The order's own name resolving to a third entry (aspirin) is not a problem and is left in rather
 * than engineered away: its order is its own by name, so it contributes no witness either, and a
 * dictionary where the order name happens to be recognised is the realistic case.
 */
public class ActiveOrderAtcContextTest extends BaseModuleContextSensitiveTest {

	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order. */
	private static final int ORDERED_CONCEPT = 88;

	private static final String SIMVASTATIN_ATC = "C10AA01";

	private static final String CLARITHROMYCIN_ATC = "J01FA09";

	private DrugSafetyValidator validator;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * Maps {@code codes} onto the ordered concept through a source whose name carries "ATC" — the same
	 * shape the reference demo dictionary uses ({@code WHOATC}), which is what
	 * {@link PatientClinicalContextBuilder} recognises.
	 */
	private void mapOrderedConceptToAtc(String... codes) {
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, codes);
	}

	private static long interactionChips(List<SafetyWarning> warnings) {
		return warnings.stream().filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())).count();
	}

	@Test
	public void theBuilderCarriesEachOrdersOwnAtcCodesAndStillFlattensThem() {
		mapOrderedConceptToAtc(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC);

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);

		assertEquals(1, context.getActiveDrugOrders().size(),
				"precondition: patient 7 has exactly one active drug order, was: "
						+ context.getActiveDrugOrders());
		PatientClinicalContext.ActiveDrugOrder order = context.getActiveDrugOrders().get(0);
		assertEquals(DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC),
				order.getAtcCodes(),
				"the order must carry the codes its own concept maps to, was: " + order.getAtcCodes());
		assertTrue(context.getActiveDrugAtcCodes().containsAll(
				DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC)),
				"and the flattened union must still hold them — the class arms and findByActiveOrders read"
						+ " it, and #118 kept it as the no-per-order-structure fallback, was: "
						+ context.getActiveDrugAtcCodes());
	}

	@Test
	public void oneOrderMappedToTwoInteractingEntriesRaisesNothingEndToEnd() {
		mapOrderedConceptToAtc(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC);
		// Non-vacuity, through the real order-driven matcher: both interacting entries really are
		// subjects of the screen for this patient. Without this a passing "no chips" could just as well
		// mean the ATC maps never reached the context at all.
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<DrugReference> resolved = DrugReferenceTestSupport.ddinterService()
				.findByActiveOrders(context);
		assertEquals(2, resolved.size(),
				"precondition: the order's two codes must resolve two reference entries, was: " + resolved);

		List<SafetyWarning> warnings = validator.validate("", SCREENING_QUESTION, patient);

		assertEquals(0, interactionChips(warnings),
				"a patient on ONE order cannot be told its two ATC codes interact with each other, was: "
						+ warnings);
		// Silent for the RIGHT reason. This entry point fails safe — any RuntimeException degrades to an
		// empty list — so "no chips" alone cannot tell a correct suppression from a broken pipeline. The
		// same validator, patient and knowledge base must still chip for a drug that genuinely interacts
		// with this patient's own order: warfarin x aspirin is Major in the DDInter excerpt, and the
		// order's own NAME is what witnesses it, so this control is independent of the codes mapped
		// above. (That question raises other chips too — warfarin against the two entries those codes
		// resolve to — which is the drug-in-play arm doing its own job against the chart, so the named
		// pair is asserted rather than the count.)
		assertTrue(DrugReferenceTestSupport.detailContains(
				validator.validate("", "Is it safe to start warfarin?", patient),
				SafetyWarning.TYPE_INTERACTION, "Warfarin", "active order aspirin", "Major"),
				"precondition: the same path must still raise a genuine active-order interaction");
	}
}
