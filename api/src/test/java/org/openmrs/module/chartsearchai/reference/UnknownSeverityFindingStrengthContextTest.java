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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * An {@code Unknown}-rated interaction is a caution, and the only way to see it is to lower the floor
 * (issue #283).
 *
 * <p><b>Why this needs a context.</b> {@code unknown} is the one rating on the caution side of
 * {@link DrugSafetyValidator#ratingLicensesWithholding(String)} that the SHIPPED configuration can never show: the
 * default {@code chartsearchai.drugSafety.minInteractionSeverity} is {@code minor}, so an
 * Unknown-rated row raises no chip and therefore renders no finding. It becomes reachable exactly
 * where the property's own documentation points an operator — lowering the floor to audit what the
 * knowledge base holds — so the mapping has to be pinned under that configuration rather than assumed
 * from the {@code minor} case beside it.
 *
 * <p><b>What it would cost to have this wrong.</b> DDInter's Unknown tier is 14% of the knowledge base
 * and carries <em>no mechanism text at all</em> — that is why the default floor filters it. If the
 * mapping withheld instead of cautioning, an operator auditing the KB would get an answer refusing a
 * drug on a finding that states nothing but the two drug names.
 *
 * <p>Both directions are asserted, so the arrangement cannot pass vacuously: under the default floor
 * the pair yields no finding at all, which is what proves the floor write took effect rather than the
 * case having been silently empty all along.
 */
public class UnknownSeverityFindingStrengthContextTest extends BaseModuleContextSensitiveTest {

	/** Lisinopril × Omeprazole in the real DDInter excerpt: rated {@code Unknown}, and its mechanism
	 *  reference is the excerpt's "none" sentinel, so the finding carries the two names and nothing
	 *  else — the shape the floor exists to keep out of a clinician's way.
	 *
	 *  <p>The rendered partner reads <b>esomeprazole</b> rather than omeprazole, and that is the
	 *  dataset's own defect rather than this case's: the KB's {@code Omeprazole} row publishes
	 *  {@code esomeprazole} among its names, which the loader reports as
	 *  {@code alias-names-another-substance} (18 rows over the shipped KB, this one first). It is
	 *  named here so the label is not mistaken for something the strength clause did. This case is
	 *  about the RATING, so it asserts nothing about the partner's name. */
	private static final String QUESTION = "Is it safe to give lisinopril?";

	private static final String ACTIVE_ORDER = "Omeprazole";

	/** Pinned as literals here rather than taken from {@code DrugReferenceInjector}'s constants, and
	 *  deliberately alongside the copies in {@link SafetyFindingSeverityStrengthTest} rather than
	 *  hoisted into {@code DrugReferenceTestSupport}: the clause is the sentence a safety answer's
	 *  strength now rests on, and a test comparing that constant to itself would stay green through a
	 *  reword that changed what the model reads. Two files pinning it independently is the same
	 *  arrangement {@code LlmProviderTest} documents for the finding prefix. */
	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static List<RecordMapping> findings() {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(
				DrugReferenceTestSupport.ddinterServiceWithGroups()).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set(ACTIVE_ORDER), null, null, null),
						QUESTION);
		return DrugReferenceTestSupport.injectedFindings(chart);
	}

	@Test
	public void theShippedFloorLeavesAnUnknownRatedPairWithNoFindingToStateAStrengthFor() {
		assertTrue(findings().isEmpty(),
				"precondition for the case below: under the default minor floor this pair raises no "
						+ "chip, so nothing renders a strength clause");
	}

	@Test
	public void withTheFloorLoweredTheUnknownRatedFindingIsACautionAndNotAReasonToWithhold() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "unknown");

		List<RecordMapping> findings = findings();
		assertEquals(1, findings.size(),
				"lowering the floor must surface exactly the Unknown-rated pair this case is about");
		String text = findings.get(0).getText();
		assertTrue(text.contains(CAUTION),
				"an Unknown rating is on the caution side of the split — DDInter rates it as carrying "
						+ "no established significance: " + text);
		assertFalse(text.contains(WITHHOLD),
				"and it must not be read as unrated, which withholds: " + text);
	}
}
