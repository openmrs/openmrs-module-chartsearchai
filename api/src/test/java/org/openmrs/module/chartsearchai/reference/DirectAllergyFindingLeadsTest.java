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
 * <p>Both are kept and the identity one leads. Why, what the alternative would have cost, and what
 * the ordering does and does not reach are ADR Decision 82; nothing of that argument is restated
 * here. What the cases below add to it is the arrangement each one drives.
 *
 * <p><b>The fixture</b> is the verbatim DDInter excerpt {@code ddi-unclassified-allergen.json}, whose
 * {@code Ciprofloxacin} and {@code Levofloxacin} rows are the real dataset's — so the class comparison
 * here is the one the shipped knowledge base makes, and {@code DirectAllergyContraindicationTest}'s
 * javadoc is where that pair's subgroups and the reason {@code (J01MA)} is the one printed are
 * recorded.
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
		// The arm the ticket measured: a prescription checked against the chart's allergy records rather
		// than a drug the question resolved. Neither the question nor the answer names it, so the
		// question-driven arm has no anchor and addActiveOrderContraindications reaches the prescription.
		// WHICH widening admits it is not this case's subject and is not pinned here: measured by
		// neutering each in turn, the question matches the medications cues as well as the allergy ones
		// and either alone admits it. What this case pins is the ORDER of the two findings that arm
		// raises about one prescription.
		// That this arm reaches the identity check at all is
		// ActiveOrderContraindicationTest.thePrescribedDrugIsCheckedByTheIdentityArmToo; what is new
		// here is the ORDER the two findings about one prescription are stated in.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
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
		List<SafetyWarning> warnings = fixtureValidator().validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null, allergies, null));

		// Kept, not suppressed: two recorded allergens are two findings and stay two chips (issue #145).
		assertEquals(2, warnings.size(), "two recorded allergens are two findings, was: " + warnings);
		assertEquals(IDENTITY, warnings.get(0).getDetail(),
				"the chart's own allergy to the drug itself leads, whatever order " + allergies
						+ " was recorded in");
		assertEquals(CROSS_REACTIVITY, warnings.get(1).getDetail(),
				"and the class finding about the OTHER allergen still stands behind it");
	}

	/** As the two sibling classes over this fixture spell it — the shared service builder, validated. */
	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
