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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A drug the chart records a DIRECT allergy to leads with that finding, whatever order the chart
 * returned the allergy records in (issue #388).
 *
 * <p><b>The shape.</b> One drug can carry two contraindication findings raised by two DIFFERENT
 * recorded allergens: the chart records an allergy to the drug itself, AND a second allergen shares
 * its ATC level-4 subgroup. Both are true and both are kept — the ledger's collapse unit is the
 * recorded FINDING (issue #145), and a class chip about a different allergen reports a second chart
 * record that the identity chip cannot ({@code ContraindicationChips}). Issue #388 asked whether the
 * class one should instead be suppressed on a drug already contraindicated by name, and decided not
 * to: the yielding chip carries content the surviving one cannot, which is issue #88's own test for
 * a wrong dedup, and this module states what it withholds rather than dropping it silently.
 *
 * <p><b>What was wrong.</b> {@code DrugSafetyValidator.addAllergyContraindications} walked the
 * recorded allergens once and raised whichever relationship each one produced, so which of the two
 * findings LED was decided by the order {@code PatientService.getAllergies} returned the records —
 * the same order-dependence issue #268 removed from the fold's CONTENT, still standing in its order.
 *
 * <p><b>What ordering buys, stated narrowly.</b> Two surfaces, neither of them the model's reading of
 * the prompt: the order a client renders the chip list in, and which finding survives a truncation
 * (issue #346's own property, {@code DrugSafetyValidator.FINDING_STRENGTH_DESCENDING}). ADR Decision
 * 37 records why it buys nothing at the third — the prompt is handed a SET and its order is not
 * stated to the model — so no case here asserts anything about what the answer says.
 *
 * <p><b>The fixture</b> is the verbatim DDInter excerpt {@code ddi-unclassified-allergen.json}, whose
 * {@code Ciprofloxacin} ({@code J01MA02}) and {@code Levofloxacin} ({@code J01MA12}) rows are the real
 * dataset's, so the class comparison here is the one the shipped knowledge base makes.
 */
public class DirectAllergyFindingLeadsTest {

	private static final String FIXTURE = DrugReferenceTestSupport.DDI_UNCLASSIFIED_ALLERGEN;

	private static final String QUESTION = "Is it safe to give her ciprofloxacin?";

	private static final String IDENTITY = "The patient has a recorded allergy to Ciprofloxacin.";

	private static final String CROSS_REACTIVITY = "Ciprofloxacin is in the same ATC class (J01MA) as"
			+ " the patient's allergy to Levofloxacin — possible cross-reactivity";

	/** The active order's display, and what {@code getActiveDrugNames} holds for it. */
	private static final String PRESCRIPTION = "Ciprofloxacin 500mg";

	@Test
	public void theDirectAllergyLeadsWhereTheChartRecordedTheClassAllergenFirst() throws IOException {
		// THE case, and the one that fails before the fix: the class-related allergen is the chart's
		// first record, so the single-pass walk raised its cross-reactivity chip before it ever asked
		// the identity question about the second record.
		assertLeadsWithTheDirectAllergy(DrugReferenceTestSupport.set("Levofloxacin", "Ciprofloxacin"));
	}

	@Test
	public void theDirectAllergyLeadsWhereTheChartRecordedItFirst() throws IOException {
		// The mirror, so the rule is "identity leads" and not "the record order is reversed". Green
		// before the fix as well as after it, deliberately: it is what says the change made the lead
		// independent of the chart's record order rather than dependent on it the other way round.
		assertLeadsWithTheDirectAllergy(DrugReferenceTestSupport.set("Ciprofloxacin", "Levofloxacin"));
	}

	@Test
	public void theDirectAllergyLeadsOnAPrescriptionTheQuestionNeverNames() throws IOException {
		// The ticket's own arm: its sixteen chips were measured on a question about a THIRD drug, and
		// ten of them came from the active-order arm — a prescription checked against the chart's
		// allergy records rather than a drug the question resolved. Both arms call this one method, and
		// this case is what says so: nothing here names ciprofloxacin, so the question-driven arm has no
		// anchor and it is addActiveOrderContraindications that reaches the prescription, through the
		// allergy widening its subject-matter gate reads off the question (issue #143).
		DrugReferenceService service = fixtureService();
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Does she have any drug allergies?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(PRESCRIPTION), null,
						DrugReferenceTestSupport.set("Levofloxacin", "Ciprofloxacin"), null,
						Collections.singletonList(DrugReferenceTestSupport.activeOrder(
								"e2f7a1c4-3b6d-4a58-9f21-0c7d8e5b4a63", PRESCRIPTION))));

		List<SafetyWarning> contraindications = DrugReferenceTestSupport.contraindications(warnings);
		assertEquals(2, contraindications.size(),
				"the prescription is checked against both recorded allergens, was: " + warnings);
		assertEquals(IDENTITY, contraindications.get(0).getDetail(),
				"and leads with the chart's own allergy to the drug it is prescribed");
		assertEquals(CROSS_REACTIVITY, contraindications.get(1).getDetail(),
				"the cross-reactivity finding standing behind it, as on the question-driven arm");
	}

	/** Both findings, in one order, through the real validator over the real fixture. */
	private static void assertLeadsWithTheDirectAllergy(Set<String> allergies)
			throws IOException {
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixtureService()).validate("",
				QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null, allergies, null));

		// Kept, not suppressed: two recorded allergens are two findings and stay two chips (issue #145).
		assertEquals(2, warnings.size(), "two recorded allergens are two findings, was: " + warnings);
		assertEquals(IDENTITY, warnings.get(0).getDetail(),
				"the chart's own allergy to the drug itself leads, whatever order " + allergies
						+ " was recorded in");
		assertEquals(CROSS_REACTIVITY, warnings.get(1).getDetail(),
				"and the class finding about the OTHER allergen still stands behind it");
	}

	/** The real fixture entries behind a service carrying the real curated cross-reactivity groups —
	 *  as {@code DirectAllergyContraindicationTest} builds it, so the class comparison here is made
	 *  against the curated data a deployment really has and not against an empty group list. */
	private static DrugReferenceService fixtureService() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return service;
	}
}
