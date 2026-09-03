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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #174 site 3 — the interaction SCREENING arm
 * ({@code DrugSafetyValidator.addActiveOrderPairInteractions}, issue #113) named its subject after
 * whichever row {@link DrugReferenceService#findForActiveOrders} returned first, so a substance the
 * KB files as several rows was named by a route-qualified row for a systemic order.
 *
 * <p>Label-only: the arm's own unordered pair key already collapses the duplicate chips, and issue
 * #173's identity ledger already stops the drug-in-play arm and this one both reporting one pair.
 * What was left is that the two arms could still CALL one substance two different things in one
 * build — live-confirmed on a deployment where the same patient's chip read {@code Salicylic acid}
 * for a question naming the drug and {@code Salicylic acid (sodium)} for a screening question.
 *
 * <p>Seven of the shipped KB's multi-row families name their route-unspecified row somewhere other
 * than first, which is what makes the choice observable at all; {@code Chloroprocaine} is one of
 * them, and the fixture below is a verbatim slice of it.
 *
 * <p>Every scenario runs the REAL production path: a verbatim DDInter KB slice parsed by the real
 * {@link DdiDrugReferenceSource}, the real {@code validate} entry point, real question strings, GP
 * reads on their no-context defaults.
 */
public class ScreeningSubjectLabelTest {

	/** The canonical screening question, verbatim from issue #113. */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/**
	 * The verbatim slice whose {@code Chloroprocaine} family lists its OPHTHALMIC row first and
	 * carries lidocaine's rule on that row alone — so a first-row subject and a canonical-row
	 * subject are two different strings. Shared with
	 * {@code DrugSafetyInteractionScreeningTest}'s cross-arm cases, which assert the ledger this one
	 * does not exercise.
	 */
	private static final String FIXTURE = "chartsearchai-test/ddi-crossarm-canonical-duplicate.json";

	private static PatientClinicalContext bothOrders() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Chloroprocaine 20mg/mL", "Lidocaine 2%"), null, null, null);
	}

	@Test
	public void theScreenNamesTheSubstanceNotWhicheverRowResolvedFirst() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientClinicalContext context = bothOrders();

		// Preconditions through the production resolvers: the screening gate needs a question naming
		// no drug, and the defect needs the QUALIFIED row to resolve first.
		assertTrue(service.findByQuery(SCREENING_QUESTION).isEmpty(),
				"precondition: the screening question must name no drug, or the screen never runs");
		assertEquals(Arrays.asList("Chloroprocaine (ophthalmic)", "Chloroprocaine", "Lidocaine"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: the ROUTE-QUALIFIED row must resolve first — that is what the subject "
						+ "label used to be taken from");

		// The EMPTY answer is the pre-answer production shape (DrugReferenceInjector.preAnswerFindings
		// calls validate exactly this way), so nothing is in play and the screening arm is the only
		// arm that can raise this chip. That isolation is the point: the cross-arm ledger cannot mask
		// the label here, because there is no other arm.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, context);

		assertEquals(1, warnings.size(),
				"precondition: the screen must reach the one pair among these orders, was: " + warnings);
		assertEquals("Chloroprocaine", warnings.get(0).getDrug(),
				"the screening chip must name the SUBSTANCE, not the row the dataset listed first, "
						+ "was: " + warnings);
		assertFalse(warnings.get(0).getDetail().contains("(ophthalmic)"),
				"and its detail must not assert an ophthalmic preparation the chart does not record, "
						+ "was: " + warnings.get(0).getDetail());
	}

	@Test
	public void theQuestionPairArmNamesTheSubstanceToo() throws Exception {
		// A FIFTH site of the same shape, found while sweeping the four issue #174 enumerates and not
		// listed there: the question-PAIR arm (issue #114) names both sides of its sentence by
		// whichever entry row it reached, and its own javadoc records that the tie "goes to whichever
		// entry the DATASET lists first". For Chloroprocaine that is the ophthalmic row, so a question
		// naming two drugs asserted a preparation the clinician never named — the same defect issue
		// #162 fixed on the drug-in-play arm and #174 site 3 on the screening arm.
		//
		// No active orders, so this arm is the only one that can chip: the drug-in-play arm needs
		// hasActiveDrug and the screening arm needs a question naming no drug.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null, null, null);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Does chloroprocaine interact with lidocaine?", context);

		assertEquals(1, warnings.size(),
				"precondition: the question pair must chip exactly once, was: " + warnings);
		assertTrue(warnings.get(0).getDetail().contains("interacts with Chloroprocaine,"),
				"the pair sentence must name the SUBSTANCE, not the row the dataset listed first, "
						+ "was: " + warnings.get(0).getDetail());
		assertFalse(warnings.get(0).getDetail().contains("(ophthalmic)")
				|| warnings.get(0).getDrug().contains("(ophthalmic)"),
				"and must assert no preparation the question did not name, was: " + warnings);
	}

	@Test
	public void bothArmsCallOneSubstanceTheSameThingInOneBuild() throws Exception {
		// The live-confirmed shape, in process: one patient, one substance, two questions. A question
		// NAMING the drug reaches the drug-in-play arm, which has named the substance's canonical row
		// since issue #162; a SCREENING question reaches the arm above. Two labels for one substance
		// in one build is what a clinician reads as the module contradicting itself, and the identity
		// ledger of issue #173 deliberately does not fix it — it stopped depending on the label
		// instead.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext context = bothOrders();

		List<SafetyWarning> screened = validator.validate("", SCREENING_QUESTION, context);
		List<SafetyWarning> named = validator.validate("", "Is it safe to give her chloroprocaine?",
				context);

		assertEquals(1, screened.size(), "precondition: the screen must chip, was: " + screened);
		assertEquals(1, named.size(),
				"precondition: the drug-named question must chip the same pair, was: " + named);
		assertEquals(named.get(0).getDrug(), screened.get(0).getDrug(),
				"one substance must have one name across the two arms — was '" + named.get(0).getDrug()
						+ "' when the question named it and '" + screened.get(0).getDrug()
						+ "' when it was screened for");
	}
}
