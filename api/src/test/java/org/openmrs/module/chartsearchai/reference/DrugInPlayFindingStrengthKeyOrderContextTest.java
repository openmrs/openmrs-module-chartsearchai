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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A folded finding rated BELOW a plain caution stays below it (issue #471, review round 1 of PR #474).
 *
 * <p><b>What this case pinned before.</b> {@code FINDING_STRENGTH_DESCENDING} asks
 * {@code licensesWithholding} first and {@code severityPriority} second, and the two keys answered
 * differently only where a finding withheld while rating below one that did not — a rule rated
 * {@code unknown} folded with a class join, which withheld on the fold. This case pinned that key
 * order over that arrangement. A class relationship is a caution now (ADR Decision 86), so the fold
 * no longer moves a finding's strength, the two keys agree on every chip this arm sorts, and the
 * same arrangement pins the consequence instead: the folded {@code Unknown} is a caution and follows
 * the plain {@code Minor} its rating ranks above it.
 *
 * <p><b>Why a context.</b> {@code unknown} is the one rating the SHIPPED configuration filters out
 * entirely — the default {@code chartsearchai.drugSafety.minInteractionSeverity} is {@code minor} —
 * so this case's arrangement is reachable only where the property's own documentation points an operator,
 * which is the same configuration {@link UnknownSeverityFindingStrengthContextTest} needs and for the
 * same reason. Both directions are asserted, so the case cannot pass vacuously: under the default
 * floor the Unknown-rated rule raises no rule chip at all, which is what proves the floor write took
 * effect rather than the arrangement having been there all along.
 *
 * <p>Restore the fold leg — {@code || finding.carriesUnratedRelationship()} in
 * {@code licensesWithholding} — and
 * {@link #withTheFloorLoweredAFoldedUnknownFollowsThePlainMinorItsRatingRanksAbove} reddens.
 */
public class DrugInPlayFindingStrengthKeyOrderContextTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Can I give her simvastatin?";

	/**
	 * Metformin is rated Minor against simvastatin and shares no ATC subgroup with it; Pravastatin is
	 * rated Unknown and shares simvastatin's C10AA subgroup, so the class arm folds a duplicate-therapy
	 * relationship onto its chip. See the fixture's own {@code metadata.note}, which is the authority on
	 * what it carries and on which of its ratings are invented.
	 */
	private static List<SafetyWarning> chips() throws Exception {
		return DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
						.ddiFixtureEntries(DrugReferenceTestSupport.DDI_FOLDED_CAUTION_ORDER)))
				.validate("", QUESTION, DrugReferenceTestSupport.rawContextNaming(60, 70.0,
					"Metformin 500mg", "Pravastatin 20mg"));
	}

	/**
	 * The precondition, asserted as the whole rendered list: under the shipped floor the Unknown-rated
	 * rule raises no rule chip, so there is no folded finding for the case below to be about, and what
	 * remains beside the Minor caution is the class arm's own unrated chip — which trails the rule
	 * chips, the stated limit {@code FINDING_STRENGTH_DESCENDING} records rather than a property of the
	 * ordering.
	 */
	@Test
	public void theShippedFloorLeavesTheUnknownRatedRuleWithNoRuleChipToFold() throws Exception {
		assertEquals(Arrays.asList(
			"interaction | Minor | Simvastatin interacts with active order Metformin",
			"interaction | null | Simvastatin is in the same ATC class (C10AA) as active order "
					+ "Pravastatin"),
			DrugReferenceTestSupport.chipLeads(chips()),
			"the default minor floor filters the Unknown-rated rule out, so the fold the case below "
					+ "turns on cannot happen under it — which is what makes that case's floor write "
					+ "observable rather than assumed");
	}

	@Test
	public void withTheFloorLoweredAFoldedUnknownFollowsThePlainMinorItsRatingRanksAbove()
			throws Exception {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "unknown");

		List<SafetyWarning> warnings = chips();
		assertEquals(Arrays.asList(
			"interaction | Minor | Simvastatin interacts with active order Metformin",
			"interaction | Unknown | Simvastatin interacts with active order Pravastatin"),
			DrugReferenceTestSupport.chipLeads(warnings),
			"the Unknown-rated pravastatin finding folds a class join, which is a caution, so the fold "
					+ "does not lift it over the plain Minor caution its rating ranks below");
		assertTrue(warnings.get(1).carriesUnratedRelationship(),
			"and the trailing chip must be the one carrying the fold, or this case is about two plain "
					+ "chips, was: " + warnings.get(1).getDetail());
	}
}
