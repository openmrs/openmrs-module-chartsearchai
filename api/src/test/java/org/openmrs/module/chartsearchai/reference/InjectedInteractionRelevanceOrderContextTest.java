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

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
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
		// PROMOTED — segment 1 yields the budget to every member and renders each in full while it
		// allows, which for this pair it does, both notes fitting the budget between them. Raised to
		// `major` both rules are filtered, so they move to the
		// segment behind it, which renders the first in full and the rest compact.
		//
		// That difference is the whole assertion, and it is why the case needs TWO filtered partners
		// rather than one: the two segments render a single note identically, and they order their
		// members identically too, since anything the floor admits outranks anything it filters. Only
		// the second member tells them apart. A renderTier that hardcoded the shipped floor, or tested
		// the rating instead of asking DrugSafetyValidator.configuredSeverityFloor, promotes both here
		// and gives Ibuprofen its mechanism paragraph.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");

		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Metformin", "Ibuprofen"), null, null, null),
						"is it safe to give lisinopril?");
		String interactions = DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, "Lisinopril"));

		assertTrue(interactions.startsWith("interactions: metformin (moderate. limited data"),
				"the raised floor promotes neither, so the first partner the chart names leads the tail "
						+ "with its own note: " + interactions);
		assertTrue(interactions.contains("; ibuprofen (moderate); "),
				"and the second is named with the rating alone — the mechanism paragraph promotion "
						+ "would have kept is what the raised floor gives up: " + interactions);
		assertTrue(interactions.endsWith("; methotrexate (moderate)."),
				"while the dataset tail still states breadth with its own representative: "
						+ interactions);
	}

	@Test
	public void theFilteredSegmentKeepsDatasetOrderRatherThanReSortingOnSeverity() {
		// The promoted segment is sorted most-severe-first, because there the budget can force a
		// choice about who keeps their mechanism prose. This segment is not, and at the shipped floor
		// that is unobservable — an UNRATED rule is exempt from the floor rather than below it, so the
		// segment can only hold Unknown rows there and a sort over them orders nothing. A raised floor
		// is the only arrangement that can tell the two apart, and without this case adding the sort
		// leaves the whole suite green while the javadoc says it is absent.
		//
		// Lisinopril files Sertraline (Unknown) at dataset position 3 and Digoxin (Moderate) at 11, so
		// dataset order and severity order disagree about which of them comes first. Sertraline leads
		// and therefore carries the full note; a severity re-sort would hand both to Digoxin.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");

		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Sertraline", "Digoxin"), null, null, null),
						"is it safe to give lisinopril?");
		String interactions = DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, "Lisinopril"));

		assertEquals("interactions: sertraline (unknown severity interaction (ddinter 2.0; no mechanism "
				+ "description on file).); digoxin (moderate); metformin (moderate).", interactions,
				"the segment the floor filtered keeps the entry's own partner order, and the note that "
						+ "carries the source's sentence is the one that order puts first: " + interactions);
	}

	@Test
	public void anOrderDrivenRecordIsTieredLikeAnyOther() {
		// A record injected because its SUBJECT is one of the patient's own active orders, rather than
		// because a question named it: no interaction chip stands behind it, and it reaches `render`
		// through the order-driven leg, which is gated on the subject being clinically related to a
		// drug the question DOES name. `render` branches on nothing about why a record was collected,
		// so it must be tiered exactly as an in-play record is — and this case is here because the
		// decision behind #357 twice asserted a reason why that could not be shown, and a review pass
		// falsified it twice. It is cheaper to pin the behaviour than to keep explaining its absence.
		//
		// The arrangement is OrderDrivenInjectionResolutionTest's — an aspirin order known only by
		// name, an ibuprofen question, bridged by the curated NSAID cross-reactivity family — with two
		// more of the excerpt's drugs prescribed alongside it that Acetylsalicylic acid rates Moderate
		// against. Raised to `major` both are filtered, so they are the record's middle segment, and
		// two members is what makes first-full-rest-compact visible at all.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");

		PatientChart chart = DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(
								"Acetylsalicylic acid 81mg", "Lisinopril", "Sertraline"), null, null, null),
						"Is it safe to give ibuprofen?");
		String interactions = DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, "Acetylsalicylic acid"));

		assertTrue(interactions.contains("lisinopril (moderate. "),
				"the first partner of the filtered segment carries its note, on a record no question "
						+ "asked for: " + interactions);
		assertTrue(interactions.contains("sertraline (moderate);"),
				"and the second is named with its rating alone — the same rule an in-play record's "
						+ "filtered segment follows: " + interactions);
	}
}
