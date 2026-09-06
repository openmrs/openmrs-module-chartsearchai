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
 * ATC codes attributed to the ORDER that carries them (issue #132) — the last false-positive path in
 * the interaction screen of #113.
 *
 * <p>The screen joins each active order against the others and must exclude the subject's OWN order so
 * it cannot witness itself. #118's per-order structure let it do that for the NAME leg of the join.
 * The ATC leg could not: codes lived only in the context-wide set, so "one order carrying two codes"
 * and "two orders each carrying one" were the same input, and an order whose name matched no alias —
 * reaching the subject set through its codes alone — paired the two entries its own concept's codes
 * resolved to. One non-blocking chip, bounded by the severity floor and the pair cap, but a fabricated
 * clinical claim about a patient who may be on a single medication.
 *
 * <p>Reproduced live on the 3.7.1 standalone (2026-08-05, before the fix, full DDInter KB): a
 * purpose-made Drug concept named "Zolvimix" mapped to WHOATC {@code C10AA01} and {@code J01FA09},
 * ordered once for a patient with no other live drug order, answered the screening question with
 * <i>"Simvastatin interacts with active order clarithromycin — Major"</i> — twice out of two runs, and
 * the model repeated the claim in its answer prose. Only 85 of 616 Drug-class concepts in the demo
 * dictionary carry any ATC map, which is why no stock probe patient reaches this and why the live
 * reproduction needed a purpose-made concept.
 *
 * <p>Both cases below run the real production path: the real DDInter excerpt parsed by the real
 * source (Simvastatin {@code C10AA01} × Clarithromycin {@code J01FA09} is a real Major row in it, and
 * the rule names its partner by that very code), the real {@code validate} entry point, real question
 * text, GP reads on their no-context defaults. {@link ActiveOrderAtcContextTest} covers the other half
 * — that {@link PatientClinicalContextBuilder} puts those codes on the order in the first place —
 * end to end through a live OpenMRS context.
 */
public class ActiveOrderAtcAttributionTest {

	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** Simvastatin's code in the DDInter excerpt; Clarithromycin's is the partner code its rule names. */
	private static final String SIMVASTATIN_ATC = "C10AA01";

	private static final String CLARITHROMYCIN_ATC = "J01FA09";

	/** A name no alias in the dataset matches, so name attribution cannot identify the subject — the
	 *  first of the three conditions that must coincide to reach this defect. */
	private static final String UNRECOGNISED_ORDER = "Zolvimix 20mg";

	private static List<SafetyWarning> screen(PatientClinicalContext context) {
		// The empty answer is the pre-answer production shape: no answer-named drug can contribute, so
		// every chip here comes from the screening arm.
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService())
				.validate("", SCREENING_QUESTION, context);
	}

	@Test
	public void oneOrderCarryingTwoInteractingEntriesCodesCannotWitnessItself() {
		// The defect. One order, one concept, two ATC maps: the flattened code set makes both
		// Simvastatin and Clarithromycin subjects, the Major row between them joins through the ATC leg
		// of hasActiveDrug, and — before the codes were attributable — the order's own second code stood
		// in for a second order. The patient is on ONE medication and neither of the two named drugs.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(UNRECOGNISED_ORDER),
				DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1", UNRECOGNISED_ORDER,
						DrugReferenceTestSupport.set(UNRECOGNISED_ORDER),
						DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC)))));

		assertTrue(warnings.isEmpty(),
				"one order's own two ATC codes are not two orders, so nothing may be raised, was: "
						+ warnings);
	}

	@Test
	public void aSecondOrderCarryingOneOfThoseCodesStillMakesItAPair() {
		// The other side of the guard, and the reason it cannot be a blanket suppression: the same
		// combination order plus a genuinely different one, whose name is equally unrecognised so the
		// ATC leg is the ONLY thing that can witness the pair. The second order carries a code the first
		// one also carries, which is the case a set-subtraction gets wrong — removing the subject
		// order's codes takes the other order's witness with it unless contributors are told apart.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(UNRECOGNISED_ORDER, "Klarizom 500mg"),
				DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-1", UNRECOGNISED_ORDER,
								DrugReferenceTestSupport.set(UNRECOGNISED_ORDER),
								DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC)),
						DrugReferenceTestSupport.activeOrder("order-2", "Klarizom 500mg",
								DrugReferenceTestSupport.set("Klarizom 500mg"),
								DrugReferenceTestSupport.set(CLARITHROMYCIN_ATC)))));

		assertEquals(1, warnings.size(), "exactly one real pair is on this chart, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"a second order carrying the partner's code must still witness the pair, was: " + warnings);
	}

	@Test
	public void aCoFormulationOrderCarryingOnlyONEConstituentsCodeIsStillOneOrder() {
		// Attribution is per ORDER, but an order's mapped codes need not cover every substance IN it:
		// this is #124's real shape, a fixed-dose combination whose concept maps to one constituent's
		// code (aspirin's N02BA01) while the other half's code (A02BC05) sits in the context-wide set
		// with nothing attributing it. Removing only what the order itself claims therefore leaves that
		// half standing as if it were a second order — measured: "Acetylsalicylic acid (aspirin)
		// interacts with active order esomeprazole — Minor", the two halves of one tablet.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Aspirin and omeprazole 325/40mg"),
				DrugReferenceTestSupport.set("N02BA01", "A02BC05"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1",
						"Aspirin and omeprazole 325/40mg",
						DrugReferenceTestSupport.set("Aspirin and omeprazole 325/40mg"),
						DrugReferenceTestSupport.set("N02BA01")))));

		assertTrue(warnings.isEmpty(),
				"a combination order that maps to only one constituent's code is still one order, was: "
						+ warnings);
	}

	@Test
	public void theDerivedContextCanOnlyEverNARROWWhatTheJoinSees() {
		// The guard may remove witnesses; it must never invent one. Restoring a code that another order
		// also carries is the one step that ADDS, so it is bounded by the context's own union: here an
		// order carries a code the union does not (which the builder cannot produce, but the public
		// constructor can, and every test in this suite builds contexts by hand). Unbounded, that one
		// order would witness a pair through Clarithromycin's code — a code the patient's chart never
		// held — for a subject that reached the subject set from the OTHER code entirely.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null, null,
				DrugReferenceTestSupport.set(SIMVASTATIN_ATC), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1", "Unnamed order",
						DrugReferenceTestSupport.set(), DrugReferenceTestSupport.set(CLARITHROMYCIN_ATC)))));

		assertTrue(warnings.isEmpty(),
				"a code no active order contributed to the chart-wide set cannot witness a pair, was: "
						+ warnings);
	}

	@Test
	public void perOrderCodesAreNormalisedLikeEveryOtherAtcComparison() {
		// The codes an order carries come from a dictionary's ConceptReferenceTerm, so their case and
		// padding are the dictionary's business, not this module's — and every ATC comparison in the
		// feature goes through one shared normaliser for exactly that reason. If the per-order set
		// skipped it, the exclusion above would silently stop matching on any dictionary that stores
		// codes lower-cased or padded, i.e. #132 would be un-fixed there with nothing to show it.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(UNRECOGNISED_ORDER),
				DrugReferenceTestSupport.set(SIMVASTATIN_ATC, CLARITHROMYCIN_ATC), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1", UNRECOGNISED_ORDER,
						DrugReferenceTestSupport.set(UNRECOGNISED_ORDER),
						DrugReferenceTestSupport.set(" c10aa01 ", "j01fa09")))));

		assertTrue(warnings.isEmpty(),
				"a lower-cased, padded per-order code must exclude its own order just the same, was: "
						+ warnings);
	}

	@Test
	public void anOrderWithNoAttributedCodesStillCannotReportItsOwnConstituents() {
		// Attribution is not always available, and where it is missing this must not become WEAKER than
		// what #113 already guaranteed. An order carrying no codes of its own — the pre-#132 three-arg
		// form, and any concept with no ATC map — is the shape where the only thing that can identify
		// the subject's contribution is the old proxy: the codes of the OTHER entries that same order's
		// name resolves to. This is #124's measured co-formulation case (one "Aspirin and omeprazole"
		// order reporting "interacts with active order esomeprazole — Minor", the two halves of one
		// tablet) reached with per-order structure present rather than absent, since the flat fallback
		// covers it only when the context carries no orders at all.
		List<SafetyWarning> warnings = screen(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Aspirin and omeprazole 325/40mg"),
				DrugReferenceTestSupport.set("A02BC05"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-1",
						"Aspirin and omeprazole 325/40mg"))));

		assertTrue(warnings.isEmpty(),
				"one combination order is one order even when no code is attributed to it, was: "
						+ warnings);
	}
}
