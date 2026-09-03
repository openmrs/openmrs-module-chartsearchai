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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * A FOLDED finding — one chip asserting a rated interaction AND an unrated class relationship — states
 * the stronger of the two strengths (issue #283).
 *
 * <p><b>Why this exists.</b> Issue #171's fold puts the class arm's duplicate-therapy sentence onto the
 * rated rule's chip when both arms are about the same co-medication, so one finding carries two claims
 * while {@link SafetyWarning#getSeverity()} keeps reporting the RULE's rating (deliberately — see
 * {@code interactionWarning}: folding must not raise or lower what the pair is rated). Grading the
 * strength clause off that rating alone made the fold LOWER the claim: a Minor-rated rule folded with a
 * duplicate-therapy relationship rendered "a caution to note", while the same relationship on its own
 * renders "a reason to withhold" because it is unrated. Same clinical facts, two strengths, decided by
 * whether a rated row happened to exist beside them.
 *
 * <p>It is also a behaviour change beyond what #283 set out to make. Before that issue every finding
 * produced a refusal, so a folded Minor pair refused; softening it was not measured and is not what the
 * report was about. The fold therefore takes the stronger claim, which leaves those pairs exactly where
 * they were and keeps #283 confined to findings whose only claim is the rated rule.
 *
 * <p><b>Not hypothetical.</b> Measured over the shipped knowledge base through the production
 * predicates — the real {@link DdiDrugReferenceSource#parse}, {@link DrugReference#atcSubgroups()} for
 * the subgroup test and {@link DrugReferenceService#lookupByToken} for the partner — <b>108 of the
 * 24,690</b> Minor-rated interaction ROWS the parsed model carries pair two drugs whose subgroups
 * intersect. The ROW is the honest unit here because a chip is raised per subject, so either
 * orientation can fold. The fixture is one of them, sliced verbatim (Methylphenidate × Modafinil,
 * rated Minor, both publishing {@code N06BA} — a subgroup named for a pharmacological action, so the
 * duplicate-therapy claim is licensed rather than vetoed by #183's bar).
 *
 * <p>This javadoc said "54 unordered pairs, each held by both entries", and review measured that
 * wrong — see {@code DrugSafetyValidator.licensesWithholding} for the full breakdown. Through the
 * same three predicates the 108 rows are <b>56</b> unordered display-name pairs, 18 of them held from
 * one side only, with multiplicities of 1, 2, 3 and 5 from the multi-row families. 54 was 108/2 and
 * not a second count, so the reconciliation with a raw-file scan that this paragraph claimed never
 * existed. The fixture pair itself is one of the 32 symmetric ones.
 */
public class FoldedFindingStrengthTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-folded-minor-class-pair.json";

	private static final String QUESTION = "Is it safe to give methylphenidate?";

	private static final String CO_MEDICATION = "Modafinil";

	/** The class arm's own sentence, shared by the case that requires it and the case that requires
	 *  its ABSENCE: apart, a reword would redden only the first and quietly make the second stop
	 *  discriminating. */
	private static final String CLASS_SENTENCE = "same ATC class (N06BA)";

	/** Pinned as literals here rather than taken from {@code DrugReferenceInjector}'s constants, and
	 *  deliberately alongside the copies in {@link SafetyFindingSeverityStrengthTest} rather than
	 *  hoisted into {@code DrugReferenceTestSupport}: the clause is the sentence a safety answer's
	 *  strength now rests on, and a test comparing that constant to itself would stay green through a
	 *  reword that changed what the model reads. Two files pinning it independently is the same
	 *  arrangement {@code LlmProviderTest} documents for the finding prefix. */
	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	/** The SCREENING arm's caution since issue #348. The divergence this file is about is unchanged —
	 *  the screen still states the weaker claim for the identical pair — but the vocabulary it states
	 *  it in is the current-medication one, because both of a screened pair's drugs are the patient's
	 *  own prescriptions and nothing proposed either of them. */
	private static final String CAUTION_CURRENT = "This finding is a caution about a medication this "
			+ "patient is already taking, not a reason to change it.";

	private static String foldedFinding() throws IOException {
		// ddiFixtureService, not serviceWith(fixtureEntries(…)): the slice is DDInter-shaped, and the
		// curated parser reads a different schema — handed this file it yields no entries at all, so the
		// case passes its own preconditions vacuously and asserts nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set(CO_MEDICATION),
						DrugReferenceTestSupport.set("N06BA07"), null, null),
				QUESTION);
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		assertEquals(1, findings.size(),
				"the fold is the arrangement under test: two arms about one co-medication must be ONE "
						+ "finding, was: " + chart.getText());
		return findings.get(0).getText();
	}

	@Test
	public void theFoldedFindingReallyCarriesBothClaims() throws IOException {
		String finding = foldedFinding();

		assertTrue(finding.toLowerCase().contains("minor"),
				"precondition: the rated half is the Minor rule: " + finding);
		assertTrue(finding.contains(CLASS_SENTENCE),
				"precondition: the unrated half is the duplicate-therapy sentence the fold appends — "
						+ "without it this case would be an ordinary Minor finding: " + finding);
	}

	@Test
	public void aFoldedFindingStatesTheStrongerClaimRatherThanTheRatedOne() throws IOException {
		String finding = foldedFinding();

		assertTrue(finding.contains(WITHHOLD),
				"a finding that also asserts an unrated class relationship licenses withholding, or the "
						+ "fold silently downgrades a claim the same relationship makes on its own: "
						+ finding);
		assertFalse(finding.contains(CAUTION),
				"and it must not read as a mere caution: " + finding);
	}

	/**
	 * The same two drugs on the same chart, reached by the SCREENING arm instead, state the weaker
	 * claim. What differs is which arm asked, not anything in the data.
	 *
	 * <p>Why the two arms differ, and why that is left rather than closed, is argued once on
	 * {@link SafetyWarning#carriesUnratedRelationship()}. What this case adds is that it is CHECKED:
	 * before #283 neither record stated a strength and the prompt refused on either, so the arms
	 * differed only in detail text and nothing here could see it. Reddens on the mutation it is about
	 * — {@code carriesUnratedRelationship()} returning true unconditionally fails this case and
	 * neither of the two above it.
	 */
	@Test
	public void theScreeningArmStatesTheWeakerClaimForTheSamePairBecauseItRunsNoClassArm()
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(40, null,
						DrugReferenceTestSupport.set("Methylphenidate", CO_MEDICATION),
						DrugReferenceTestSupport.set("N06BA04", "N06BA07"), null, null),
				"are there any drug interactions with her current medications?");
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);

		assertEquals(1, findings.size(),
				"the screen must reach this one pair, or the comparison below is against nothing: "
						+ chart.getText());
		String screened = findings.get(0).getText();
		assertTrue(screened.toLowerCase().contains("minor"),
				"precondition: it is the same rated row the folded case is about: " + screened);
		assertFalse(screened.contains(CLASS_SENTENCE),
				"precondition: the screen raises no class sentence, which is WHY the strengths differ "
						+ "— if this ever fails, the fold reached this arm and the assertion below is "
						+ "the one to re-read: " + screened);

		assertTrue(screened.contains(CAUTION_CURRENT),
				"the screened finding carries the rating alone, so it states a caution — the screening "
						+ "arm's own, since issue #348: " + screened);
		assertFalse(screened.contains(CAUTION),
				"and never the PROPOSAL caution, whose prompt branch opens by stating that the drug "
						+ "can be given: " + screened);
		assertTrue(foldedFinding().contains(WITHHOLD),
				"while the drug-in-play arm states withholding for the identical pair — the divergence "
						+ "this case exists to keep visible");
	}
}
