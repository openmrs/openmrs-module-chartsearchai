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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The one thing about issue #357's three-segment ordering that the shipped floor cannot show: that the
 * boundary between the promoted segment and the head of the tail is
 * {@code chartsearchai.drugSafety.minInteractionSeverity} rather than the word {@code Unknown}.
 *
 * <p>At the shipped {@code minor} floor the middle segment can hold only {@code Unknown} rows — an
 * UNRATED rule is exempt from the floor rather than below it — so a {@code renderTier} that had
 * hardcoded that floor, or tested the rating rather than asking
 * {@link DrugSafetyValidator#configuredSeverityFloor}, would be indistinguishable there. Context-sensitive
 * because setting the global property needs a running context, which is the same reason
 * {@code PairChipExtentContextTest} is.
 */
public class InjectedInteractionRelevanceOrderContextTest extends BaseModuleContextSensitiveTest {

	@Test
	public void aRaisedFloorMovesAPartnerFromThePromotedSegmentToTheHeadOfTheTail() {
		// Both of these partners are drugs the chart records, and at the shipped floor both are
		// promoted — segment 1 renders each of them, the Moderate one with its full mechanism
		// paragraph. Raised to `major` the Moderate rule is filtered, so it must keep its place ahead
		// of every stranger while losing everything promotion buys: it becomes the single compact
		// representative segment 2 renders behind the Major one.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");

		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Spironolactone", "Ibuprofen"), null, null,
								null),
						"is it safe to give lisinopril?");
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).stream()
				.filter(m -> m.getText().startsWith("Drug reference — Lisinopril ")).findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"no Lisinopril record was injected: " + chart.getText()));
		String text = record.getText();
		String interactions = text.substring(text.indexOf("Interactions:")).toLowerCase(Locale.ROOT);

		assertTrue(interactions.startsWith("interactions: spironolactone (major. "),
				"only the rule the raised floor still admits is promoted: " + interactions);
		assertTrue(interactions.endsWith("; ibuprofen (moderate)."),
				"and the rule it filtered keeps the head of the tail, ahead of every partner the chart "
						+ "does not name, in the compact form promotion would have spared it: "
						+ interactions);
	}
}
