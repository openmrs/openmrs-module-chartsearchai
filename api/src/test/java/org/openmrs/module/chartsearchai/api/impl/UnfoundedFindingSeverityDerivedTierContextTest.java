/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnfoundedFindingSeverity;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/560">#560</a>, the
 * derived tier: a condition-mediated finding carries NO rating of its own — the derived tier assigns the
 * pair none, and its detail ends by saying so — yet that detail states each drug-disease rating,
 * "Metformin is rated Major in Acidosis, Lactic". So it is a finding the check judges, and the Major its
 * record states is one it carries: quoted in a sentence citing it, beside an unrated finding or alone, that
 * word is the #554 fourth run's shape and stays silent, while a rating its record does not state is reported.
 *
 * <p>Context-sensitive because that tier is gated on {@code chartsearchai.drugSafety.derivedFindings},
 * which ships off (ADR Decision 111); {@link #setUp} turns it on. Everything else is the real pipeline
 * over the shipped knowledge base, as in {@link UnfoundedFindingSeverityTest}.
 */
public class UnfoundedFindingSeverityDerivedTierContextTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Can I give metformin?";

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS,
				ChartSearchAiConstants.DERIVED_FINDINGS_MAJOR);
	}

	@Test
	public void aRatingTheCoCitedConditionMediatedFindingStatesIsNotReported() {
		PatientChart chart = chart();
		int unrated = findingWhoseText(chart, "No severity is rated for this finding.");
		int derived = findingWhoseText(chart, "is rated Major in");
		assertNull(DrugReferenceTestSupport.findingAt(chart, derived).getFindingSeverity(),
				"precondition: the condition-mediated finding carries no rating of its own");
		LlmInferenceService service = UnfoundedFindingSeverityTest.serviceOver(chart,
				"Metformin is rated Major for lactic acidosis with her stavudine and lamivudine, and she is "
						+ "allergic to it [" + derived + "] [" + unrated + "].");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				service.search(UnfoundedFindingSeverityTest.patient(), QUESTION).getUnfoundedFindingSeverities(),
				"Major is what the co-cited condition-mediated finding's record states");
	}

	@Test
	public void aRatingNeitherCitedFindingStatesIsStillReported() {
		PatientChart chart = chart();
		int unrated = findingWhoseText(chart, "No severity is rated for this finding.");
		int derived = findingWhoseText(chart, "is rated Major in");
		LlmInferenceService service = UnfoundedFindingSeverityTest.serviceOver(chart,
				"Her metformin allergy is a Moderate finding beside the lactic acidosis link [" + derived + "] ["
						+ unrated + "].");
		// Both cited findings carry no rating and neither record states Moderate, so the sentence unit
		// reports each: it cannot tell which of the two the answer gave the word to.
		assertEquals(Arrays.asList(new UnfoundedFindingSeverity(derived, "Moderate"),
				new UnfoundedFindingSeverity(unrated, "Moderate")),
				service.search(UnfoundedFindingSeverityTest.patient(), QUESTION).getUnfoundedFindingSeverities());
	}

	@Test
	public void aRatingAttachedToTheConditionMediatedFindingAloneIsReportedUnlessItsRecordStatesIt() {
		PatientChart chart = chart();
		int derived = findingWhoseText(chart, "is rated Major in");
		LlmInferenceService moderate = UnfoundedFindingSeverityTest.serviceOver(chart,
				"The lactic acidosis link with her stavudine and lamivudine is a Moderate finding [" + derived + "].");
		assertEquals(Collections.singletonList(new UnfoundedFindingSeverity(derived, "Moderate")),
				moderate.search(UnfoundedFindingSeverityTest.patient(), QUESTION).getUnfoundedFindingSeverities(),
				"the finding carries no rating, and its record states no Moderate");
		LlmInferenceService major = UnfoundedFindingSeverityTest.serviceOver(chart,
				"Metformin is rated Major for lactic acidosis with her stavudine and lamivudine [" + derived + "].");
		assertEquals(Collections.<UnfoundedFindingSeverity> emptyList(),
				major.search(UnfoundedFindingSeverityTest.patient(), QUESTION).getUnfoundedFindingSeverities(),
				"Major is the drug-disease rating its own record states");
	}

	@Test
	public void theConditionMediatedFindingIsStampedAsCarryingNoRating() {
		// Its detail says it has no severity of its own, though it does not get the no-severity sentence the
		// other unrated findings do (ConditionMediatedFindingTest.theInjectedRecordSaysItHasNoSeverityOnce).
		PatientChart chart = chart();
		int derived = findingWhoseText(chart, "is rated Major in");
		assertTrue(DrugReferenceTestSupport.findingAt(chart, derived).getText().contains("no severity of its own"),
				"precondition: its record says so");
		assertEquals(Boolean.TRUE, DrugReferenceTestSupport.findingAt(chart, derived).getFindingUnrated());
	}

	/** Metformin proposed for a patient on stavudine and lamivudine (the derived tier's own case, ADR
	 *  Decision 111) who is allergic to metformin, which raises an unrated contraindication beside it. */
	private static PatientChart chart() {
		return DrugReferenceTestSupport.injectedFindingsOverOrdersWithRecordedAllergies(
				DrugReferenceTestSupport.shippedServiceWithGroups(),
				UnfoundedFindingSeverityTest.chartOf("Stavudine 30mg capsule", "Lamivudine 150mg tablet"), QUESTION,
				new LinkedHashSet<String>(Arrays.asList("metformin")), "Stavudine", "Lamivudine");
	}

	private static int findingWhoseText(PatientChart chart, String needle) {
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains(needle)) {
				return finding.getIndex();
			}
		}
		throw new IllegalStateException("no injected finding stating '" + needle + "': " + chart.getText());
	}
}
