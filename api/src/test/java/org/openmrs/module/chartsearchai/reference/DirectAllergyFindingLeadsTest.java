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
 * <p><b>Both cross-reactivity arms are driven</b>, the ATC-class comparison and the curated-group
 * one, because {@code README.md} states the lead over each of them alike and the nested
 * {@code CLAUDE.md} bullet states it as one rule. They need different data, so there are two
 * fixtures.
 *
 * <p><b>The class arm's fixture</b> is the verbatim DDInter excerpt
 * {@code ddi-unclassified-allergen.json}, whose {@code Ciprofloxacin} and {@code Levofloxacin} rows
 * are the real dataset's — so the class comparison here is the one the shipped knowledge base makes,
 * and {@code DirectAllergyContraindicationTest}'s javadoc is where that pair's subgroups and the
 * reason {@code (J01MA)} is the one printed are recorded.
 *
 * <p><b>The curated arm's fixture</b> is the pinned DDInter excerpt, through
 * {@code DrugReferenceTestSupport.ddinterServiceWithGroups()}. A different slice and not a different
 * loader — both services carry the real bundled cross-reactivity groups — because what the class
 * arm's slice has not got is a PAIR a curated group relates. The bundled data has one group,
 * {@code NSAID}, spanning {@code M01AE} and {@code N02BA}, and this excerpt's {@code Ibuprofen} and
 * {@code Acetylsalicylic acid} rows share it while sharing no ATC subgroup — which is what makes the
 * class comparison decline and {@link CrossReactivityGroup#sharedGroup(java.util.List, DrugReference)}
 * the comparison that answers.
 */
public class DirectAllergyFindingLeadsTest {

	private static final String FIXTURE = DrugReferenceTestSupport.DDI_UNCLASSIFIED_ALLERGEN;

	private static final String QUESTION = "Is it safe to give her ciprofloxacin?";

	private static final String IDENTITY = "The patient has a recorded allergy to Ciprofloxacin.";

	private static final String CROSS_REACTIVITY = "Ciprofloxacin is in the same ATC class (J01MA) as"
			+ " the patient's allergy to Levofloxacin — possible cross-reactivity";

	/** The active order's display, and what {@code getActiveDrugNames} holds for it. */
	private static final String PRESCRIPTION = "Ciprofloxacin 500mg";

	/** The curated arm's question — the excerpt's {@code Ibuprofen} is what it puts in play. */
	private static final String GROUP_QUESTION = "Is it safe to give her ibuprofen?";

	private static final String GROUP_IDENTITY = "The patient has a recorded allergy to Ibuprofen.";

	private static final String CROSS_REACTIVITY_GROUP = "Ibuprofen is in the same cross-reactivity"
			+ " group (NSAID) as the patient's allergy to Acetylsalicylic acid (aspirin) — possible"
			+ " cross-reactivity";

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
	public void theDirectAllergyLeadsOverACuratedGroupWhereTheChartRecordedTheGroupAllergenFirst() {
		// THE curated-arm case, and one no ATC-class case in this file can reach — its slice carries no
		// code under either of the NSAID group's prefixes, so nothing in it relates through a curated
		// group at all. Here the excerpt's aspirin and ibuprofen rows share the bundled NSAID group and
		// no ATC subgroup, so the class comparison declines and CrossReactivityGroup.sharedGroup is
		// what raises the chip. The group-related allergen is the chart's first record, so before the
		// fix the single-pass walk raised its group chip ahead of the identity question about the
		// second record — and it is what reddens if the group comparison alone is hoisted into the
		// identity pass, which is the edit this case exists for: mutate the pass split and read the
		// failures.
		assertLeadsWithTheDirectAllergy(groupFixtureValidator(), GROUP_QUESTION,
				DrugReferenceTestSupport.set("Aspirin", "Ibuprofen"), GROUP_IDENTITY,
				CROSS_REACTIVITY_GROUP);
	}

	@Test
	public void theDirectAllergyLeadsOverACuratedGroupWhereTheChartRecordedItFirst() {
		// The curated arm's mirror, for the reason theDirectAllergyLeadsWhereTheChartRecordedItFirst
		// gives: it says the lead is independent of the chart's record order rather than dependent on
		// it the other way round.
		assertLeadsWithTheDirectAllergy(groupFixtureValidator(), GROUP_QUESTION,
				DrugReferenceTestSupport.set("Ibuprofen", "Aspirin"), GROUP_IDENTITY,
				CROSS_REACTIVITY_GROUP);
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

	/** The class arm's two findings, in one order, through the real validator over its own fixture. */
	private static void assertLeadsWithTheDirectAllergy(Set<String> allergies)
			throws IOException {
		assertLeadsWithTheDirectAllergy(fixtureValidator(), QUESTION, allergies, IDENTITY,
				CROSS_REACTIVITY);
	}

	/** Both findings, in one order, through the real validator over the real fixture — one arm's
	 *  sentences per call, because the two arms name their cross-reactivity differently and the whole
	 *  point of the case is which of the two findings the arm emitted first. */
	private static void assertLeadsWithTheDirectAllergy(DrugSafetyValidator validator, String question,
			Set<String> allergies, String identity, String crossReactivity) {
		List<SafetyWarning> warnings = validator.validate("", question,
				DrugReferenceTestSupport.ctx(60, null, null, null, allergies, null));

		// Kept, not suppressed: two recorded allergens are two findings and stay two chips (issue #145).
		assertEquals(2, warnings.size(), "two recorded allergens are two findings, was: " + warnings);
		assertEquals(identity, warnings.get(0).getDetail(),
				"the chart's own allergy to the drug itself leads, whatever order " + allergies
						+ " was recorded in");
		assertEquals(crossReactivity, warnings.get(1).getDetail(),
				"and the cross-reactivity finding about the OTHER allergen still stands behind it");
	}

	/** The shared service builder, validated — as {@code DirectAllergyContraindicationTest} spells it.
	 *  {@code RecordedAllergenMemoScopeTest} uses this fixture and deliberately does not, for a reason
	 *  its own javadoc gives. */
	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}

	/** The pinned DDInter excerpt carrying the real curated groups, validated. Through
	 *  {@code DrugReferenceTestSupport.ddinterServiceWithGroups} because that is the one body the two
	 *  loading steps live in, and a service missing the second of them raises no group chip while
	 *  nothing goes red. */
	private static DrugSafetyValidator groupFixtureValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());
	}
}
