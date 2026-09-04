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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issues #189 and #175 — <b>one substance, one rule</b>. Every arm already agrees on which SUBSTANCE
 * a chip is about (issues #162/#174) and on what to call it (#187/#192). What none of them agreed on
 * is which of that substance's ROWS supplies the rule they quote: the survivor was whichever row the
 * dataset listed first, not the most severe or the most apt.
 *
 * <p>Three arms, one defect:
 * <ul>
 *   <li><b>#189, the screening arm</b> ({@code addActiveOrderPairInteractions}) asked
 *       {@code bestRulePerPartner} about ONE row at a time and collapsed the siblings on its own
 *       pair key, so the first row to reach a pair kept the chip whatever its rating;</li>
 *   <li><b>#189, the question-PAIR arm</b> ({@code addQuestionPairInteractions}) kept the first
 *       ENTRY PAIR to reach a clinical pair, for the same reason;</li>
 *   <li><b>#175, the drug-in-play arm</b> ({@code addInteractionWarnings}) sees only the rows the
 *       question and answer TEXT resolved. Where the patient's own order name resolves more of them
 *       its chip could carry a milder rule than the screen would have raised — and since issue #173
 *       the screen stands down from a pair that arm reported, so the more severe row was never
 *       reported at all. That is the one place in this cluster where the surviving chip was not
 *       provably equivalent to the one it replaced, and the direction is under-warning.</li>
 * </ul>
 *
 * <p>Every scenario runs the REAL production path: verbatim DDInter KB slices parsed by the real
 * {@link DdiDrugReferenceSource}, the real {@code validate} entry point, real question and answer
 * strings, GP reads on their no-context defaults.
 */
public class OneSubstanceOneRuleTest {

	/** The canonical screening question, verbatim from issue #113 — it must name no drug. */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/**
	 * A verbatim slice of the shipped KB whose {@code Insulin human} family reaches ONE partner
	 * substance ({@code fluticasone}) at two different ratings from two different rows: the family's
	 * FIRST row only through a Minor rule, its second row through a Moderate one. It is also the only
	 * family in the shipped KB where a single alias resolves the milder row ALONE while an order name
	 * resolves both, which is what makes issue #175's under-report reachable on shipped data.
	 */
	private static final String ASYMMETRY = "chartsearchai-test/ddi-substance-rule-asymmetry.json";

	/** The Moderate rule's note as the real parser assembles it (mechanism group 5607, whose
	 *  {@code INTERVAL:} field marker the parser strips). */
	private static final String MODERATE_NOTE = "Moderate. Bronchodilators and other orally inhaled "
			+ "products may alter the absorption of inhaled human insulin.";

	/** The opening of the Minor rule's mechanism (group 3606) — the row the dataset lists first, and
	 *  the one that used to survive. */
	private static final String MINOR_MECHANISM_OPENING =
			"The efficacy of insulin and other antidiabetic agents may be diminished by topical";

	/** The order name that resolves BOTH insulin rows, and the only alias naming the second. */
	private static final String INHALED_INSULIN_ORDER = "Insulin human (inhalation, rapid acting)";

	private static List<SafetyWarning> interactionChips(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())) {
				out.add(warning);
			}
		}
		return out;
	}

	/** Both of the patient's own orders, insulin first so the insulin side reaches the pair first. */
	private static PatientClinicalContext bothOrders() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(INHALED_INSULIN_ORDER, "Fluticasone"), null, null, null);
	}

	@Test
	public void theScreenQuotesTheSubstancesMostSevereRuleNotItsFirstRowsRule() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ASYMMETRY);
		PatientClinicalContext context = bothOrders();

		// Preconditions through the production resolvers, so the case cannot pass vacuously.
		assertTrue(service.findByQuery(SCREENING_QUESTION).isEmpty(),
				"precondition: the screening question must name no drug, or the screen never runs");
		assertEquals(Arrays.asList("Insulin human", INHALED_INSULIN_ORDER, "Fluticasone",
				"Fluticasone (topical)"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: the row carrying only the MINOR rule must resolve FIRST — that is the row "
						+ "the screen used to keep");

		List<SafetyWarning> chips = interactionChips(DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, context));

		assertEquals(1, chips.size(), "one substance against one partner is one chip, was: " + chips);
		assertTrue(chips.get(0).getDetail().endsWith(" — " + MODERATE_NOTE),
				"the screen must quote the substance's most severe rule, not the rule on whichever row "
						+ "the dataset listed first, was: " + chips.get(0).getDetail());
		assertFalse(chips.get(0).getDetail().contains(MINOR_MECHANISM_OPENING),
				"and must not carry the mechanism belonging to the rating it outranks, was: "
						+ chips.get(0).getDetail());
	}

	@Test
	public void bothArmsQuoteOneRuleForOneSubstanceInOneBuild() throws Exception {
		// The live-confirmed shape, in process: one patient, one substance, two questions. A question
		// NAMING the drug reaches the drug-in-play arm; a screening question reaches the arm above.
		// Two mechanism paragraphs for one pair in one build is the module contradicting itself, and it
		// is the axis issue #188 reported as still open once it had closed the name and the count.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ASYMMETRY);
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext context = bothOrders();

		List<SafetyWarning> screened = interactionChips(
				validator.validate("", SCREENING_QUESTION, context));
		List<SafetyWarning> named = interactionChips(
				validator.validate("", "Is it safe to give her insulin human?", context));

		assertEquals(1, screened.size(), "precondition: the screen must chip, was: " + screened);
		assertEquals(1, named.size(),
				"precondition: the drug-named question must chip the same pair, was: " + named);
		assertEquals(named.get(0).getDetail(), screened.get(0).getDetail(),
				"one substance must quote ONE rule across the two arms");
		assertTrue(named.get(0).getDetail().endsWith(" — " + MODERATE_NOTE),
				"and the one they agree on must be the most severe available, or they agree on the wrong "
						+ "rule, was: " + named.get(0).getDetail());
	}

	@Test
	public void thePairTheScreenDefersToCarriesTheSeverityTheScreenWouldHaveRaised() throws Exception {
		// Issue #175. The question names no drug, so the screen is live; the ANSWER names the substance
		// by the one alias that resolves the family's FIRST row alone, so the drug-in-play arm's subject
		// group was a strict subset of the rows the chart itself resolved. It chipped the Minor rule,
		// recorded the pair, and the screen stood down from the Moderate one.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ASYMMETRY);
		PatientClinicalContext context = bothOrders();

		assertEquals(Arrays.asList("Insulin human"),
				DrugReferenceTestSupport.names(service.findByQuery("insulin human")),
				"precondition: the answer's own word must resolve the MINOR row alone, or the two arms "
						+ "see the same rows and there is nothing to under-report");

		List<SafetyWarning> chips = interactionChips(DrugReferenceTestSupport.validator(service)
				.validate("Her insulin human dose may need review.", SCREENING_QUESTION, context));

		assertEquals(1, chips.size(),
				"the pair is still reported exactly once — issue #173's cross-arm ledger stands, was: "
						+ chips);
		assertTrue(chips.get(0).getDetail().endsWith(" — " + MODERATE_NOTE),
				"and the surviving chip must carry the severity the screen would have raised, not the "
						+ "milder rule the answer's own wording happened to reach, was: "
						+ chips.get(0).getDetail());
	}

	/** The opening of the MODERATE sirolimus x lapatinib mechanism (group 981) — the rule on the
	 *  family's first row, which the Major row outranks. */
	private static final String SIROLIMUS_MODERATE_OPENING =
			"Coadministration with lapatinib may increase the plasma concentrations of drugs";

	@Test
	public void theScreenQuotesTheMostSevereRuleOnASecondIndependentSlice() throws Exception {
		// A second slice, and the dangerous direction on the screen itself: Lapatinib rates Sirolimus
		// Moderate and Sirolimus (protein-bound) MAJOR, and the unqualified row is the dataset's first —
		// so the screen reported Moderate for a pair its own KB rates Major, while the drug-in-play arm
		// reported Major for that very pair (InteractionRouteVariantTest
		// .severityStillOutranksTheRoutePreference pins that half).
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Sirolimus 1mg", "Lapatinib 250mg"), null, null, null);

		assertEquals(Arrays.asList("Sirolimus", "Sirolimus (protein-bound)", "Lapatinib"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: the MODERATE row must resolve before the Major one");

		List<SafetyWarning> chips = interactionChips(DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, context));

		assertEquals(1, chips.size(), "one substance, one partner, one chip, was: " + chips);
		assertTrue(chips.get(0).getDetail()
				.startsWith("Sirolimus interacts with active order lapatinib — Major. "),
				"the screen must not report the milder of a pair's two ratings, was: "
						+ chips.get(0).getDetail());
		assertFalse(chips.get(0).getDetail().contains(SIROLIMUS_MODERATE_OPENING),
				"and must not carry the Moderate row's mechanism, was: " + chips.get(0).getDetail());
	}

	@Test
	public void theQuestionPairArmQuotesTheMostSevereRuleOfTheSubstance() throws Exception {
		// The same defect on the arm that needs no chart at all (issue #114): one clinical pair arrives
		// as several ENTRY pairs and the first of them kept the sentence. No active orders, so this arm
		// is the only one that can chip.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		String question = "Does sirolimus interact with lapatinib?";

		assertEquals(Arrays.asList("Sirolimus", "Sirolimus (protein-bound)", "Lapatinib"),
				DrugReferenceTestSupport.names(service.findByQuery(question)),
				"precondition: the MODERATE row must be the first entry pair the walk reaches");

		List<SafetyWarning> chips = interactionChips(DrugReferenceTestSupport.validator(service)
				.validate("", question, DrugReferenceTestSupport.ctx(60, null, null, null, null, null)));

		assertEquals(1, chips.size(), "one clinical pair is one chip, was: " + chips);
		assertTrue(chips.get(0).getDetail().startsWith(
				"Sirolimus interacts with Lapatinib, also named in the question — Major. "),
				"the pair sentence must quote the pair's most severe rule, not the rule on whichever "
						+ "entry pair the walk reached first, was: " + chips.get(0).getDetail());
		assertFalse(chips.get(0).getDetail().contains(SIROLIMUS_MODERATE_OPENING),
				"and must not carry the Moderate row's mechanism, was: " + chips.get(0).getDetail());
	}
}
