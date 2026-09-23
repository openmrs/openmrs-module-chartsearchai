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
 * the stronger of the two strengths (issue #283), and since issue #471's review round 1 the class
 * relationship's strength is a caution, so the fold states what its rule's rating does.
 *
 * <p><b>Why this exists.</b> Issue #171's fold puts the class arm's duplicate-therapy sentence onto the
 * rated rule's chip when both arms are about the same co-medication, so one finding carries two claims
 * while {@link SafetyWarning#getSeverity()} keeps reporting the RULE's rating (deliberately — see
 * {@code interactionWarning}: folding must not raise or lower what the pair is rated). Issue #283 made
 * the fold take the stronger claim, and at the time the class relationship on its own was a reason to
 * withhold, so a folded Minor withheld. Issue #400 (ADR Decision 86) graded that relationship a caution
 * where it stands alone, and issue #471 made Moderate a caution; each half of a folded Minor or
 * Moderate finding is then a caution, and a fold that went on withholding stated a call neither half
 * licenses. Review of PR #474 measured it on the shipped knowledge base — Efavirenz with Nevirapine,
 * rated Moderate and both in J05AG, read "This finding is a reason to withhold it."
 *
 * <p><b>Not hypothetical.</b> Measured over the shipped knowledge base through the production
 * predicates — the real {@link DdiDrugReferenceSource#parse}, {@link DrugReference#atcSubgroups()} for
 * the subgroup test and {@link DrugReferenceService#lookupByToken} for the partner — <b>108 of the
 * 24,690</b> Minor-rated interaction ROWS the parsed model carries pair two drugs whose subgroups
 * intersect (a count taken for issue #283, before Moderate was a caution; the Moderate rows were not
 * counted). The ROW is the honest unit here because a chip is raised per subject, so either
 * orientation can fold. The Minor fixture is one of them, sliced verbatim (Methylphenidate × Modafinil,
 * rated Minor, both publishing {@code N06BA} — a subgroup named for a pharmacological action, so the
 * duplicate-therapy claim is licensed rather than vetoed by #183's bar).
 *
 * <p>This javadoc said "54 unordered pairs, each held by both entries", and review measured that
 * wrong — see {@code DrugSafetyValidator.licensesWithholding} for the full breakdown. Through the
 * same three predicates the 108 rows are <b>56</b> unordered display-name pairs, 18 of them held from
 * one side only, with multiplicities of 1, 2, 3 and 5 from the multi-row families. 54 was 108/2 and
 * not a second count, so the reconciliation with a raw-file scan that this paragraph claimed never
 * existed. The fixture pair itself is one of the 32 symmetric ones.
 *
 * <p>Restore the fold leg — {@code || finding.carriesUnratedRelationship()} in
 * {@code licensesWithholding} — and {@link #aModerateRuleFoldedWithAClassRelationshipIsACaution},
 * {@link #aFoldedMinorFindingIsACautionBecauseNeitherOfItsClaimsWithholds} and
 * {@link #theScreeningArmStatesTheSameStrengthForTheSamePair} redden.
 */
public class FoldedFindingStrengthTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-folded-minor-class-pair.json";

	/** Efavirenz and Nevirapine, rated Moderate and both filed under J05AG — see the file's own note. */
	private static final String MODERATE_FIXTURE = "chartsearchai-test/ddi-folded-moderate-class-pair.json";

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

	/** The SCREENING arm's caution since issue #348 — the current-medication vocabulary, because both
	 *  of a screened pair's drugs are the patient's own prescriptions and nothing proposed either. */
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

	/**
	 * A MODERATE rule folded with a class relationship is a caution (issue #471, review round 1 of
	 * PR #474). Each half is a caution on its own — the rule's rating by ADR Decision 109, the shared
	 * classification by Decision 86 — so the stronger of the two claims is a caution, and a fold that
	 * withheld here would state a call neither half licenses. Efavirenz and Nevirapine are the shipped
	 * knowledge base's own rows, rated Moderate and both filed under J05AG; the review drove this very
	 * arrangement over the shipped KB and read "This finding is a reason to withhold it."
	 */
	@Test
	public void aModerateRuleFoldedWithAClassRelationshipIsACaution() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(MODERATE_FIXTURE);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Nevirapine"),
						DrugReferenceTestSupport.set("J05AG01"), null, null),
				"Can I give this patient efavirenz?");
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		assertEquals(1, findings.size(),
				"the fold is the arrangement under test: two arms about one co-medication must be ONE "
						+ "finding, was: " + chart.getText());
		String finding = findings.get(0).getText();
		assertTrue(finding.contains("Moderate"), "precondition: the rated half is the Moderate rule: "
				+ finding);
		assertTrue(finding.contains("same ATC class (J05AG)"),
				"precondition: the unrated half is the duplicate-therapy sentence the fold appends: "
						+ finding);

		assertTrue(finding.contains(CAUTION),
				"a Moderate rule is a caution and so is a shared classification, so the fold of the two "
						+ "is a caution — the finding must not state a call neither half licenses: "
						+ finding);
		assertFalse(finding.contains(WITHHOLD), "and it must not withhold: " + finding);
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
	public void aFoldedMinorFindingIsACautionBecauseNeitherOfItsClaimsWithholds() throws IOException {
		String finding = foldedFinding();

		assertTrue(finding.contains(CAUTION),
				"a Minor rule is a caution and so is a shared classification (ADR Decision 86), so the "
						+ "stronger of the fold's two claims is a caution: " + finding);
		assertFalse(finding.contains(WITHHOLD),
				"and it must not withhold on a relationship that is a caution where it stands alone: "
						+ finding);
	}

	/**
	 * The same two drugs on the same chart, reached by the SCREENING arm instead, state the same
	 * STRENGTH, each in its own arm's vocabulary. The screen runs no class arm, so its finding carries
	 * the rule alone; until issue #471's review round 1 the drug-in-play arm's fold withheld on the
	 * class sentence the screen never raises, so one pair was a caution or a reason to withhold by
	 * which arm asked. A class relationship being a caution closes that.
	 */
	@Test
	public void theScreeningArmStatesTheSameStrengthForTheSamePair() throws IOException {
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
				"precondition: the screen raises no class sentence, which is what made the strengths "
						+ "differ before a class relationship was a caution — if this ever fails, the fold "
						+ "reached this arm and the assertions below are the ones to re-read: " + screened);

		assertTrue(screened.contains(CAUTION_CURRENT),
				"the screened finding carries the rating alone, so it states a caution — the screening "
						+ "arm's own, since issue #348: " + screened);
		assertFalse(screened.contains(CAUTION),
				"and never the PROPOSAL caution, whose prompt branch opens by stating that the drug "
						+ "can be given: " + screened);
		assertTrue(foldedFinding().contains(CAUTION),
				"and the drug-in-play arm states a caution for the identical pair too — the class sentence "
						+ "only it raises must not make the pair a reason to withhold");
	}
}
