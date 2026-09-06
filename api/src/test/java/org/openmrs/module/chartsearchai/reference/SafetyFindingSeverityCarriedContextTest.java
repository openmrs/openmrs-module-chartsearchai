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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #337, round three: the rating an injected {@code safety_finding} states travels beside the
 * record as well as inside its prose, so a consumer asking "which rating did this finding state"
 * has one answer rather than one per parse.
 *
 * <p>What this file pins is the WRITE, through the real
 * {@code DrugReferenceInjector.injectRecords} over the pinned DDInter excerpt — which rating reaches
 * {@link RecordMapping#getFindingSeverity()} and which is deliberately withheld. What the ANSWER
 * check does with it is {@code SafetyFindingSeverityFidelityTest}'s.
 *
 * <p><b>Why a context.</b> One of the two withheld cases is {@code unknown}, and the shipped floor
 * ({@code chartsearchai.drugSafety.minInteractionSeverity} = {@code minor}) filters an
 * Unknown-rated row out of the findings entirely. It becomes reachable exactly where that property's
 * own documentation points an operator — lowering the floor to audit what the knowledge base holds —
 * so the carve-out has to be pinned under that configuration rather than assumed from the rated
 * cases beside it. The neighbouring {@link UnknownSeverityFindingStrengthContextTest} reaches the
 * same rating for a different question, and the two are not substitutes: that one asserts the
 * STRENGTH clause an Unknown finding renders, this one asserts that no rating is carried for it.
 */
public class SafetyFindingSeverityCarriedContextTest extends BaseModuleContextSensitiveTest {

	/** The ticket's own drug. Its partners in the pinned excerpt span all four rating classes, which
	 *  is what lets one arrangement discriminate the carried cases from the withheld ones. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static List<RecordMapping> findingsFor(String... activeOrders) {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(
				DrugReferenceTestSupport.ddinterServiceWithGroups()).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set(activeOrders), null, null, null),
						QUESTION);
		return DrugReferenceTestSupport.injectedFindings(chart);
	}

	/** The rating carried by the single finding the arrangement raises, asserted to BE single so a
	 *  case cannot silently read one finding's rating while asserting about another's. */
	private static String carriedRatingOfTheOnlyFinding(String activeOrder) {
		List<RecordMapping> findings = findingsFor(activeOrder);
		assertEquals(1, findings.size(),
				"the arrangement must raise exactly one finding, or this case cannot say whose rating "
						+ "it read. Findings were: " + findings.size());
		return findings.get(0).getFindingSeverity();
	}

	@Test
	public void aRatedFindingCarriesItsOwnRatingBesideTheRecordAndNotOnlyInsideIt() {
		// Simvastatin x Clarithromycin is Major in the excerpt; Sertraline is Moderate. Both are
		// carried, and each carries ITS OWN word — a constant would satisfy one of these and not both.
		assertEquals("Major", carriedRatingOfTheOnlyFinding("Simvastatin"),
				"a Major-rated finding must carry Major");
		assertEquals("Moderate", carriedRatingOfTheOnlyFinding("Sertraline"),
				"and a Moderate-rated one must carry Moderate, or the carrier is a constant");
	}

	@Test
	public void theRatingIsCarriedStructurallyAndNotReadBackOutOfTheRenderedProse() {
		// The record's prose states the rating too — that is where the model reads it — so this case
		// establishes only that the two agree on a finding whose text certainly contains it. What
		// makes the structural copy necessary rather than redundant is stated at the write site: a
		// mechanism can contain its own rating word, so a parse is not a safe way to recover this.
		List<RecordMapping> findings = findingsFor("Simvastatin");
		assertEquals(1, findings.size(), "one finding expected");
		RecordMapping finding = findings.get(0);
		assertNotNull(finding.getFindingSeverity(), "the rating must be carried");
		assertTrue(finding.getText().contains(finding.getFindingSeverity()),
				"and it must be the same word the record's own prose states, or the model reads one "
						+ "rating and a consumer compares against another. Record was: "
						+ finding.getText());
	}

	@Test
	public void aMinorRatedFindingCarriesItsRatingToo() {
		// The floor's own boundary is `minor`, so this is the weakest rating the shipped
		// configuration can produce — and it is carried, because the question this check asks is
		// about a WORD's survival, not about how strongly the finding licenses a clinical call.
		// Neutering statableRating to the withholding split reddens exactly here.
		assertEquals("Minor", carriedRatingOfTheOnlyFinding("Omeprazole"),
				"a Minor rating is a word an answer can drop, so it is carried like the others");
	}

	@Test
	public void theShippedFloorLeavesTheUnknownRatedPairWithNoFindingAtAll() {
		// The precondition for the case below: without it, that one could pass by raising nothing.
		assertTrue(findingsFor("Lisinopril").isEmpty(),
				"under the default minor floor an Unknown-rated pair raises no chip and so renders no "
						+ "finding");
	}

	@Test
	public void withTheFloorLoweredAnUnknownRatedFindingCarriesNoRatingForAnAnswerToOwe() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "unknown");

		List<RecordMapping> findings = findingsFor("Lisinopril");
		assertEquals(1, findings.size(),
				"lowering the floor must surface exactly the Unknown-rated pair this case is about, "
						+ "or the assertion below passes on an empty list");
		assertNull(findings.get(0).getFindingSeverity(),
				"`Unknown` is RATED, not unrated, so it is not covered by the null an unrated finding "
						+ "gets — and requiring an answer to write the word \"Unknown\" would accuse a "
						+ "large share of an auditing operator's findings of dropping a rating that "
						+ "communicates nothing. Record was: " + findings.get(0).getText());
	}
}
