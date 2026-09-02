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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #347: one response named one active order two ways — the ANSWER prose printed the chart's
 * brand ({@code active order Advil}) and the CHIP printed the knowledge base's substance
 * ({@code active order Ibuprofen}), for the one prescription citation {@code [2]} resolved to.
 * Neither name is false, which is why the chip side must not be re-decided (CLAUDE.md, and #339's
 * reverted rounds 5–6); what was missing is the CORRESPONDENCE between the two.
 *
 * <p><b>Why #349's clause was silent on exactly this shape.</b> That issue built the mechanism that
 * states the correspondence — {@code DrugSafetyValidator.chartOrderBridges} decides it and
 * {@link DrugReferenceInjector#FINDING_CHART_ORDER_LEAD} renders it as {@code "<Substance> from
 * <order display>"} inside the injected {@code safety_finding}. Its silence test asked whether ANY
 * name the order RECORDS reaches the substance, and
 * {@code PatientClinicalContextBuilder.addDrugName} puts the order's CONCEPT name into that set
 * beside its drug-row name. So an order displayed {@code Advil 400mg} on concept {@code Ibuprofen}
 * satisfied the silence test through {@code Ibuprofen} — a name that reaches no prompt text at all,
 * because querystore's {@code DrugOrderRecordSerializer} renders exactly ONE name per drug-order
 * record ({@code drug.getName()} where non-blank, else the concept's preferred name, never both).
 * {@code QuerystoreDrugOrderDisplayedNameTest} pins that measurement against the real serializer.
 *
 * <p>The shape below is the ticket's, in the vocabulary the pre-existing verbatim DDInter slice
 * already carries: {@code Coagubrand} standing for {@code Advil 400mg} — a brand-named order whose
 * concept name {@code Warfarin} is a name the module records and the chart's own records do not
 * spell — beside {@code Aspirin 81mg}, whose display DOES reach its substance through the alias
 * {@code aspirin} and which must therefore stay unbridged.
 *
 * <p>Every case drives the real {@code DrugReferenceInjector.injectRecords} wired to the real
 * {@code DrugSafetyValidator} over a fixture parsed by the real production parser, and reads the
 * record a model would read. The prompt is one half of the fix; the deterministic half a
 * {@code /search} consumer reads is {@code ChartSearchAiChartOrderBridgeTest}.
 */
public class OneOrderNameAcrossAnswerAndChipTest {

	/** The pre-existing verbatim DDInter slice {@code InteractionFindingChartOrderBridgeTest} uses,
	 *  which already carries Acetylsalicylic acid ({@code N02BA01}, {@code rxnorm_name} {@code aspirin})
	 *  x Warfarin ({@code B01AA03}) Major — the pair that lets one order's display reach its substance
	 *  through an alias while the other's does not reach its own at all. */
	private static final String BRAND_NAMED_ORDERS = "chartsearchai-test/ddi-alias-drug-names.json";

	private static final String SCREENING_QUESTION =
			"Are any of his current medications interacting with each other?";

	/**
	 * The ticket's chart in this fixture's own vocabulary — the class javadoc above says what stands
	 * in for what. One order the chart names only by its brand, whose CONCEPT name is a substance name
	 * the module records ({@code Coagubrand} on concept {@code Warfarin}, standing in for the ticket's
	 * {@code Advil 400mg} on {@code Ibuprofen}), and one whose own display reaches its substance
	 * ({@code Aspirin 81mg}, which is the ticket's own order).
	 */
	private static PatientClinicalContext ticketChart() {
		return DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Coagubrand", "Warfarin", "Aspirin 81mg",
				"Acetylsalicylate sodium"),
			DrugReferenceTestSupport.set("B01AA03", "N02BA01"), null, null,
			Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-warf", "Coagubrand",
					DrugReferenceTestSupport.set("Coagubrand", "Warfarin"),
					DrugReferenceTestSupport.set("B01AA03")),
				DrugReferenceTestSupport.activeOrder("order-aspirin", "Aspirin 81mg",
					DrugReferenceTestSupport.set("Aspirin 81mg", "Acetylsalicylate sodium"),
					DrugReferenceTestSupport.set("N02BA01"))));
	}

	/** The chart records a querystore index carries for those two orders: ONE name each, the one
	 *  {@code DrugOrderRecordSerializer} renders, which for both of these is the drug row's. */
	private static PatientChart ticketRecords() {
		return DrugReferenceTestSupport.chartOf(
			DrugReferenceTestSupport.drugOrderRecord(1, "order-warf", "Coagubrand"),
			DrugReferenceTestSupport.drugOrderRecord(2, "order-aspirin", "Aspirin 81mg"));
	}

	private static PatientChart injected() throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(BRAND_NAMED_ORDERS);
		return DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(ticketRecords(), ticketChart(), SCREENING_QUESTION);
	}

	/** The one finding this arrangement raises — asserted rather than assumed, because every case
	 *  below turns on a single record's text and a second finding would let the wrong one answer. */
	private static String onlyFinding() throws IOException {
		List<String> findings = DrugReferenceTestSupport.findingTexts(injected());
		assertEquals(1, findings.size(), "one pair is one citable record, was: " + findings);
		return findings.get(0);
	}

	@Test
	public void aSubstanceTheChartNamesOnlyByABrandIsBridgedToTheOrderItCameFrom() throws Exception {
		// #347 itself. The finding names Warfarin; every chart record of that prescription says
		// Coagubrand; so without this clause nothing in the prompt connects the two, and the model
		// closes the gap by renaming the order after the record it cited — which is the ticket's
		// answer-versus-chip split.
		String finding = onlyFinding();

		assertEquals("Warfarin from Coagubrand.", DrugReferenceTestSupport.bridgeOf(finding),
			"the brand-named order must be bridged to the substance the chip names it by, was: "
					+ finding);
	}

	@Test
	public void theChartsOwnRecordsSpellNoNameOfTheBridgedSubstance() throws Exception {
		// The PREMISE the case above rests on, pinned rather than assumed: the order RECORDS the name
		// Warfarin (it is the concept's) and no record in the chart carries it. Neuter the bridge and
		// this stays green — it is the reason the bridge is needed, not the bridge.
		PatientChart records = ticketRecords();

		assertFalse(records.getText().toLowerCase().contains("warfarin"),
			"the querystore records name this prescription only by its brand, was: "
					+ records.getText());
		assertTrue(hasRecordedName("order-warf", "Warfarin"),
			"and the module records that name for it, which is what made the silence test wrong");
	}

	@Test
	public void anOrderWhoseOwnDisplayReachesItsSubstanceIsStillNotBridged() throws Exception {
		// The bound on the widening, and the pre-existing property
		// InteractionFindingChartOrderBridgeTest.anOrderNamingTheSubstanceOnlyByAnAliasIsNotBridged
		// states: Acetylsalicylic acid's rxnorm_name is aspirin, so the display "Aspirin 81mg" reaches
		// it and the chart's own words therefore carry a name of it. A clause here would be noise in a
		// record whose whole budget is evidence.
		String bridge = DrugReferenceTestSupport.bridgeOf(onlyFinding());

		assertNotNull(bridge, "the brand-named order is bridged, so there is a clause to bound");
		assertFalse(bridge.contains("Aspirin"),
			"the aspirin order's own display names its substance, was: " + bridge);
		assertFalse(bridge.contains("Acetylsalicyl"),
			"nor may it be bridged under the printed label, was: " + bridge);
	}

	private static boolean hasRecordedName(String orderUuid, String name) {
		for (PatientClinicalContext.ActiveDrugOrder order : ticketChart().getActiveDrugOrders()) {
			if (orderUuid.equals(order.getUuid())) {
				return order.getNames().contains(name.toLowerCase());
			}
		}
		return false;
	}

	/** Unused-record guard: the arrangement must not inject an {@code active_drug_order} line, or the
	 *  correspondence could be supplied by that rather than by the clause under test. */
	@Test
	public void theChartSubstantiatesBothOrdersSoNoActiveOrderRecordIsInjected() throws Exception {
		for (RecordMapping mapping : injected().getMappings()) {
			assertFalse("active_drug_order".equals(mapping.getResourceType()),
				"both orders have a drug-order record, so none may be injected, was: "
						+ mapping.getText());
		}
	}
}
