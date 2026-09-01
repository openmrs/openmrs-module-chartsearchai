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
 * Which of {@code FINDING_STRENGTH_DESCENDING}'s two keys is asked FIRST (issue #346).
 *
 * <p><b>Why this needs a case of its own.</b>
 * {@link DrugInPlayFindingStrengthOrderTest#aFoldedCautionOutranksAPlainOne} shows that the fold is
 * consulted at all, but it does so over two pairs the knowledge base rates alike, and a comparator
 * that asked {@code severityPriority} first and the fold only as a TIEBREAK satisfies it — the two
 * ratings tie, so the fold decides either way. The two key orders answer differently only where a
 * finding WITHHOLDS while rating BELOW one that does not, and one rating can do that: the ratings
 * that do not withhold are {@code minor} and {@code unknown}, and of those only {@code unknown} sits
 * below the other on {@code severityPriority}. So the arrangement is a rule rated {@code unknown}
 * folded with a class join, which answers {@code licensesWithholding} on the fold and renders
 * {@code STRENGTH_WITHHOLD} in the record the model reads while its rating sorts it last.
 *
 * <p><b>Why a context.</b> {@code unknown} is the one rating the SHIPPED configuration filters out
 * entirely — the default {@code chartsearchai.drugSafety.minInteractionSeverity} is {@code minor} —
 * so the arrangement is reachable only where the property's own documentation points an operator,
 * which is the same configuration {@link UnknownSeverityFindingStrengthContextTest} needs and for the
 * same reason. Both directions are asserted, so the case cannot pass vacuously: under the default
 * floor the Unknown-rated rule raises no rule chip at all, which is what proves the floor write took
 * effect rather than the arrangement having been there all along.
 *
 * <p>Swap the comparator's two keys — rank on {@code severityPriority} and consult
 * {@code licensesWithholding} only where the priorities tie — and
 * {@link #withTheFloorLoweredAFoldedUnknownLeadsAPlainMinorThatOutranksItOnRating} reddens: the plain
 * Minor caution takes the lead, which is issue #346's own failure one rung down, since a truncated
 * answer then keeps the caution and drops the finding that withholds.
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
	public void withTheFloorLoweredAFoldedUnknownLeadsAPlainMinorThatOutranksItOnRating()
			throws Exception {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "unknown");

		List<SafetyWarning> warnings = chips();
		assertEquals(Arrays.asList(
			"interaction | Unknown | Simvastatin interacts with active order Pravastatin",
			"interaction | Minor | Simvastatin interacts with active order Metformin"),
			DrugReferenceTestSupport.chipLeads(warnings),
			"the Unknown-rated pravastatin finding folds a class join, so it is a reason to withhold "
					+ "and must lead the plain Minor caution — which its RATING sorts it below, so a "
					+ "comparator asking severityPriority first reverses this list (issue #346)");
		assertTrue(warnings.get(0).carriesUnratedRelationship(),
			"and it leads BECAUSE of the fold rather than because of anything its rating says, so the "
					+ "leading chip must be the one carrying the unrated relationship, was: "
					+ warnings.get(0).getDetail());
	}
}
