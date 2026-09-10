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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * An interaction finding whose ONLY evidence is that two co-prescribed drugs share a classification
 * states a CAUTION, not a reason to withhold — issue #400.
 *
 * <p><b>What was measured.</b> On the 3.7.1 standalone at {@code :8081}, {@code sourceFormat=ddinter},
 * a patient on Lamivudine 150mg, Nevirapine 200mg and Stavudine 30mg, asked <i>"Can stavudine and
 * lamivudine be given together?"</i>, answered: <i>"No — Stavudine and Lamivudine should not be given
 * together: they are in the same ATC class (J05AF) — possible duplicate therapy."</i> Both chips
 * carried {@code severity: null} and {@code interactionPairs} reported {@code found: 0}, so nothing
 * rated the relationship: the refusal rests on ATC co-membership alone. J05AF is the NRTI class, and
 * two NRTIs are the backbone of antiretroviral therapy — same-class co-prescription is the design of
 * the regimen, not an error in it.
 *
 * <p><b>Why the change is a strength change and not a list of exempt classes.</b> The module encodes
 * no clinical domain knowledge, so it cannot know which classes are co-prescribed on purpose. What it
 * does know is how strong its own evidence is: a relationship the reference data states WITHOUT rating
 * it, inferred from shared classification with no rule of any kind behind it, is the weakest claim this
 * layer makes. Grading it as a caution is a claim about the evidence, not about the drugs.
 *
 * <p><b>This is the change {@code DrugSafetyValidator.ratingLicensesWithholding} reserved.</b> Its
 * javadoc separates the two things an unrated finding can be — a CURATED rule an implementation
 * authored deliberately, and an ATC-subgroup or cross-reactivity JOIN nobody authored at all — and says
 * of the join that "it withholds here because that is the behaviour it already had", that it is "the
 * weaker claim", and that "a later decision to grade those joins should be made on its own evidence".
 * The standalone answer above is that evidence. The curated half is untouched and is not re-pinned
 * here: {@code SafetyFindingSeverityStrengthTest.anUnratedCuratedRuleIsNotSoftenedToACaution} is its
 * home, and it reddens on a change made by softening {@code ratingLicensesWithholding}'s unrated leg
 * instead of the join.
 *
 * <p><b>Scoped to INTERACTION findings.</b> A contraindication states a withholding-class clause
 * whatever rates it, which is the boundary #283 crossed once and was measured wrong on; a recorded
 * allergy plus shared classification is a different claim from duplicate therapy, and
 * {@link #aClassDerivedContraindicationStillLicensesWithholding} pins that it did not move.
 *
 * <p>The fixture is {@code OneOrderNameAcrossOneResponseTest}'s, reused rather than copied because its
 * own note already establishes the arrangement this file needs: Prednisolone and Methylprednisolone
 * share {@code H02AB} and their only knowledge-base rule is rated {@code Unknown}, which the shipped
 * {@code chartsearchai.drugSafety.minInteractionSeverity} default filters out — so that pair reaches
 * the class arm alone, with no rule to fold. Warfarin shares no subgroup and its Moderate rule clears
 * the floor, which is the rated control {@link #aRatedRuleInTheSameArrangementStillLicensesWithholding}
 * uses.
 */
public class ClassOnlyFindingStrengthTest {

	/** A verbatim shipped-KB slice in which one pair reaches the class arm alone and another reaches
	 *  the rule arm alone — see the fixture's own note. */
	private static final String FIXTURE =
			"chartsearchai-test/ddi-class-only-and-rule-one-partner.json";

	/** The subject of the class-only pair: shares {@code H02AB} with the co-medication below, with no
	 *  above-floor rule between them. */
	private static final String CLASS_ONLY_SUBJECT = "prednisolone";

	/** The subject of the RATED pair in the same fixture: no shared subgroup, one Moderate rule. */
	private static final String RATED_SUBJECT = "warfarin";

	private static final String CO_MEDICATION = "Methylprednisolone";

	/** The co-medication's glucocorticoid code — the one the class arm's shared-subgroup test reads.
	 *  H02AB is named for a pharmacological action, so it licenses the duplicate-therapy claim rather
	 *  than being vetoed by #183's bar. */
	private static final String CO_MEDICATION_CODE = "H02AB04";

	/** The class arm's own sentence, shared by every case here that needs the arrangement confirmed:
	 *  apart, a reword would redden one case and quietly stop the others discriminating. */
	private static final String CLASS_SENTENCE = "same ATC class (H02AB)";

	/** Pinned as literals rather than read off {@code DrugReferenceInjector}'s constants, for the
	 *  reason {@link FoldedFindingStrengthTest} states of its own copies: the clause is what the model
	 *  reads, and a test comparing that constant to itself stays green through a reword. */
	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION =
			"This finding is a caution to note, not a reason to withhold it.";

	/**
	 * @return the text of the one finding the arrangement injects, through the real injector and the
	 *         real validator behind it.
	 */
	private static String onlyFinding(String question, java.util.Set<String> allergies)
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null,
						allergies == null ? DrugReferenceTestSupport.set(CO_MEDICATION) : null,
						allergies == null ? DrugReferenceTestSupport.set(CO_MEDICATION_CODE) : null,
						allergies, null),
				question);
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		assertEquals(1, findings.size(),
				"the arrangement under test is ONE finding, or the assertions below are about the wrong "
						+ "one: " + chart.getText());
		return findings.get(0).getText();
	}

	/**
	 * The defect, in the shape the standalone produced it: the relationship rests on shared
	 * classification alone, so the finding may not tell the model to refuse the drug.
	 */
	@Test
	public void aRelationshipRestingOnSharedClassificationAloneStatesACautionRatherThanARefusal()
			throws IOException {
		String finding = onlyFinding("Is it safe to give " + CLASS_ONLY_SUBJECT + "?", null);

		assertTrue(finding.contains(CLASS_SENTENCE),
				"precondition: this is the class arm's own sentence, with no rated rule folded into it "
						+ "— without that the case is an ordinary rated finding: " + finding);
		assertFalse(finding.toLowerCase().contains("minor")
				|| finding.toLowerCase().contains("moderate") || finding.toLowerCase().contains("major"),
				"precondition: nothing rated this relationship, which is the whole of why its strength "
						+ "is in question: " + finding);

		assertTrue(finding.contains(CAUTION),
				"a relationship no dataset rates, inferred from shared classification alone, is the "
						+ "weakest claim this layer makes and must reach the model as a caution — "
						+ "stating it as a reason to withhold is what refused a standard two-NRTI "
						+ "regimen on the standalone: " + finding);
		assertFalse(finding.contains(WITHHOLD),
				"and it must not also state the withholding clause — every finding states exactly one: "
						+ finding);
	}

	/**
	 * The rated control, from the same fixture and the same run of the same arm: a rule the source
	 * rates above the floor still licenses withholding. Reddens if the change is made by softening the
	 * arm rather than the class-only join.
	 */
	@Test
	public void aRatedRuleInTheSameArrangementStillLicensesWithholding() throws IOException {
		String finding = onlyFinding("Is it safe to give " + RATED_SUBJECT + "?", null);

		assertTrue(finding.toLowerCase().contains("moderate"),
				"precondition: this is the fixture's rated pair: " + finding);
		assertFalse(finding.contains(CLASS_SENTENCE),
				"precondition: and it shares no subgroup, so no class sentence is folded in: " + finding);

		assertTrue(finding.contains(WITHHOLD),
				"a Moderate rule is a reason to withhold, and this case is what stops the class-only "
						+ "change reaching the rated rules: " + finding);
	}

	/**
	 * A class-derived CONTRAINDICATION keeps withholding. Same unrated cross-reactivity join, a
	 * different claim: this one rests on a recorded allergy as well as the shared classification, and
	 * a contraindication states a withholding-class clause whatever rates it.
	 */
	@Test
	public void aClassDerivedContraindicationStillLicensesWithholding() throws IOException {
		String finding = onlyFinding("Is it safe to give " + CLASS_ONLY_SUBJECT + "?",
				DrugReferenceTestSupport.set(CO_MEDICATION));

		assertTrue(finding.contains("cross-reactivity"),
				"precondition: the recorded allergy plus the shared subgroup is a cross-reactivity "
						+ "contraindication, which is the claim under test: " + finding);
		assertTrue(finding.contains(WITHHOLD),
				"a contraindication is a reason to withhold whatever rates it — #283 scoped the clause "
						+ "to interaction findings once and that was measured wrong: " + finding);
		assertFalse(finding.contains(CAUTION),
				"and it must not soften to the interaction arm's caution: " + finding);
	}
}
