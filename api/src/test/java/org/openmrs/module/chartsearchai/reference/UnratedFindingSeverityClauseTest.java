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

/**
 * An injected finding that carries no severity rating says so, and one that carries a rating does not
 * (issue #402's residue (a), ADR Decision 121).
 *
 * <p><b>The defect.</b> The prompt asks the answer to carry each finding's severity, and a record of
 * an unrated finding — every contraindication, a class-only relationship, the several-orders finding —
 * stated none. Measured on the live gate, on {@code main} and on the branch alike, the model filled
 * the gap with a rating no chip carries: <em>"(Major)"</em> on an unrated cross-reactivity
 * contraindication, <em>"(Unknown severity)"</em> on an unrated duplicate-therapy finding. The
 * record now states the absence, in the words the condition-mediated record already uses
 * ({@code DrugSafetyValidator.CONDITION_MEDIATED_PROVENANCE}), between the detail and the strength
 * clause so the call stays sentence-final.
 */
public class UnratedFindingSeverityClauseTest {

	private static final String ALIAS_FIXTURE = "chartsearchai-test/ddi-alias-drug-names.json";

	private static final String CLASS_ONLY_FIXTURE = "chartsearchai-test/ddi-class-only-and-rule-one-partner.json";

	private static final String FOLDED_FIXTURE = "chartsearchai-test/ddi-folded-minor-class-pair.json";

	/** Pinned as a literal: it is what the model reads, and a constant compared to itself stays green
	 *  through a reword. */
	private static final String NO_SEVERITY = " This finding has no severity of its own.";

	private static String onlyFinding(String fixture, PatientClinicalContext context, String question)
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(fixture);
		List<String> findings = DrugReferenceTestSupport.findingTexts(DrugReferenceTestSupport
				.injectorWithSafety(service).injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question));
		assertEquals(1, findings.size(), "the arrangement is ONE finding: " + findings);
		return findings.get(0);
	}

	@Test
	public void anUnratedContraindicationSaysItHasNoSeverityBeforeItsCall() throws IOException {
		String finding = onlyFinding(ALIAS_FIXTURE, DrugReferenceTestSupport.ctx(40, null, null, null,
			DrugReferenceTestSupport.set("simvastatin"), null), "Is it safe to give simvastatin?");

		assertEquals("Safety finding — Simvastatin: The patient has a recorded allergy to Simvastatin."
				+ NO_SEVERITY + " This finding is a reason to withhold it.", finding.trim());
	}

	@Test
	public void anUnratedClassOnlyRelationshipSaysItHasNoSeverityBeforeItsCall() throws IOException {
		String finding = onlyFinding(CLASS_ONLY_FIXTURE, DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Methylprednisolone"), DrugReferenceTestSupport.set("H02AB04"), null, null),
			"Is it safe to give prednisolone?");

		assertTrue(finding.trim().endsWith("possible duplicate therapy." + NO_SEVERITY
				+ " This finding is a caution to note, not a reason to withhold it."), finding);
	}

	@Test
	public void aRatedInteractionStatesItsOwnRatingAndNoAbsence() throws IOException {
		String finding = onlyFinding(ALIAS_FIXTURE, DrugReferenceTestSupport.ctx(40, null,
			DrugReferenceTestSupport.set("Clarithromycin"), null, null, null), "Is it safe to give simvastatin?");

		assertTrue(finding.contains("— Major"), "precondition: the rated Major rule: " + finding);
		assertFalse(finding.contains(NO_SEVERITY.trim()), finding);
	}

	/** A fold carries the RULE's rating beside an unrated class claim, so it is rated and says nothing
	 *  about an absence. */
	@Test
	public void aFoldedFindingCarryingARatedRuleStatesNoAbsence() throws IOException {
		String finding = onlyFinding(FOLDED_FIXTURE, DrugReferenceTestSupport.ctx(40, null,
			DrugReferenceTestSupport.set("Modafinil"), DrugReferenceTestSupport.set("N06BA07"), null, null),
			"Is it safe to give methylphenidate?");

		assertTrue(finding.contains("same ATC class (N06BA)"), "precondition: the fold: " + finding);
		assertFalse(finding.contains(NO_SEVERITY.trim()), finding);
	}
}
