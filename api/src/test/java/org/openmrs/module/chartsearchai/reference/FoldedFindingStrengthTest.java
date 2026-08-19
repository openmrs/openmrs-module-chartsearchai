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
 * 24,690</b> Minor-rated interactions the parsed model carries pair two drugs whose subgroups
 * intersect. That is 54 unordered pairs, each held by both entries; the row count is the honest unit
 * here because a chip is raised per subject, so either orientation can fold. A scan of the raw file
 * said 54 because it counted each pair once, which is the counting-base trap CLAUDE.md records — the
 * two agree only once the unit is stated. The fixture is one of them, sliced verbatim
 * (Methylphenidate × Modafinil, rated Minor, both publishing {@code N06BA} — a subgroup named for a
 * pharmacological action, so the duplicate-therapy claim is licensed rather than vetoed by #183's bar).
 */
public class FoldedFindingStrengthTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-folded-minor-class-pair.json";

	private static final String QUESTION = "Is it safe to give methylphenidate?";

	private static final String CO_MEDICATION = "Modafinil";

	/** Pinned as literals here rather than taken from {@code DrugReferenceInjector}'s constants, and
	 *  deliberately alongside the copies in {@link SafetyFindingSeverityStrengthTest} rather than
	 *  hoisted into {@code DrugReferenceTestSupport}: the clause is the sentence a safety answer's
	 *  strength now rests on, and a test comparing that constant to itself would stay green through a
	 *  reword that changed what the model reads. Two files pinning it independently is the same
	 *  arrangement {@code LlmProviderTest} documents for the finding prefix. */
	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

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
		assertTrue(finding.contains("same ATC class (N06BA)"),
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
}
