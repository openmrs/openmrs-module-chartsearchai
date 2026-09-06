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
import java.util.Set;

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
 * <order display> [N]"} inside the injected {@code safety_finding} (the number since issue #379). Its silence test asked whether ANY
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
 * <p>One case gives that brand-named order the CIEL concept the ticket's own prescription carries and
 * drops its ATC codes ({@link #aBrandNamedOrderJoinedByItsBridgedConceptIsBridgedToTheSubstanceToo}),
 * which is the arrangement production builds; the other orders here carry no concept, so issue
 * #353's ambiguity refusal has nothing of its own to weigh for them.
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
		return ticketChart(DrugReferenceTestSupport.set("B01AA03"), null);
	}

	/** The uuid the shipped bridge records for CIEL 86415 {@code Warfarin}, which this fixture's
	 *  verbatim slice carries on DDInter1951 — a concept filed on ONE substance, whose recorded name
	 *  names it. That is the ticket's own concept ({@code Ibuprofen}) in this vocabulary. */
	private static final String WARFARIN_CONCEPT = "86415AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/**
	 * As {@link #ticketChart()}, taking the brand-named order's own ATC codes and the concept it was
	 * written against — the two things the ticket's real prescription has differently, and the two the
	 * bridged case below varies: {@code Advil 400mg} on concept {@code Ibuprofen} carries no ATC map at
	 * all, and its concept is one the knowledge base's bridge does record.
	 *
	 * <p>The flattened code set is derived from the same argument rather than passed beside it, because
	 * {@code PatientClinicalContextBuilder} unions each order's codes into it — a case handing one
	 * without the other would be describing a chart no builder produces. {@code named} with a null
	 * concept is the public five-argument constructor {@link DrugReferenceTestSupport#activeOrder}
	 * reaches, so {@link #ticketChart()} is the order it always was.
	 */
	private static PatientClinicalContext ticketChart(Set<String> brandOrderCodes,
			String brandOrderConcept) {
		Set<String> flattenedCodes = DrugReferenceTestSupport.set();
		flattenedCodes.addAll(brandOrderCodes);
		flattenedCodes.add("N02BA01");
		return DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Coagubrand", "Warfarin", "Aspirin 81mg",
				"Acetylsalicylate sodium"),
			flattenedCodes, null, null,
			Arrays.asList(
				PatientClinicalContext.ActiveDrugOrder.named("order-warf", "Coagubrand",
					DrugReferenceTestSupport.set("Coagubrand", "Warfarin"), brandOrderCodes, null,
					brandOrderConcept),
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
		return injected(ticketChart());
	}

	private static PatientChart injected(PatientClinicalContext chart) throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(BRAND_NAMED_ORDERS);
		return DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(ticketRecords(), chart, SCREENING_QUESTION);
	}

	/** The one finding this arrangement raises — asserted rather than assumed, because every case
	 *  below turns on a single record's text and a second finding would let the wrong one answer. */
	private static String onlyFinding() throws IOException {
		return onlyFinding(ticketChart());
	}

	private static String onlyFinding(PatientClinicalContext chart) throws IOException {
		List<String> findings = DrugReferenceTestSupport.findingTexts(injected(chart));
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

		assertEquals("Warfarin from Coagubrand [1].", DrugReferenceTestSupport.bridgeOf(finding),
			"the brand-named order must be bridged to the substance the chip names it by, was: "
					+ finding);
	}

	@Test
	public void theChartsOwnRecordsSpellNoNameOfTheBridgedSubstance() throws Exception {
		// The PREMISE the case above rests on: the order RECORDS the name Warfarin and no record in the
		// chart carries it. Neuter the bridge and this stays green — it is the reason the bridge is
		// needed, not the bridge.
		//
		// Both halves are read off THIS fixture, which is a model of the arrangement and not evidence
		// that a real chart has it. The two production facts it models are pinned against the real
		// components elsewhere, and this case is not a substitute for either:
		// QuerystoreDrugOrderDisplayedNameTest drives querystore's own serializer for what a drug_order
		// record renders, and RecordedOrderNameBeyondItsDisplayTest drives
		// PatientClinicalContextBuilder for the concept name it records beyond that. What this case
		// adds is that the arrangement the cases around it run on really has the shape they assume.
		PatientChart records = ticketRecords();

		assertFalse(records.getText().toLowerCase().contains("warfarin"),
			"this fixture's records must name the prescription only by its brand, was: "
					+ records.getText());
		assertTrue(hasRecordedName("order-warf", "Warfarin"),
			"and its order must record the substance's name, which is the shape that made the old "
					+ "silence test fire — modelled here, measured in RecordedOrderNameBeyondItsDisplayTest");
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
	 *  correspondence could be supplied by that rather than by the clause under test.
	 *
	 *  <p>A negative assertion, so a green run says nothing on its own — it needs a positive control,
	 *  and it has one: drop {@code order-warf}'s record from {@link #ticketRecords()} and this reddens
	 *  with {@code Active drug order: Coagubrand}, while the other cases in this class stay green. So the
	 *  arm it forbids is reachable from this very path, and the fixture is not silently unable to
	 *  produce what the assertion excludes. */
	@Test
	public void theChartSubstantiatesBothOrdersSoNoActiveOrderRecordIsInjected() throws Exception {
		for (RecordMapping mapping : injected().getMappings()) {
			assertFalse("active_drug_order".equals(mapping.getResourceType()),
				"both orders have a drug-order record, so none may be injected, was: "
						+ mapping.getText());
		}
	}

	/**
	 * The same shape on an order that carries a BRIDGED CONCEPT, which is the arrangement production
	 * actually builds and the one {@link #aSubstanceTheChartNamesOnlyByABrandIsBridgedToTheOrderItCameFrom}
	 * does not reach (issue #347, review round 3).
	 *
	 * <p>{@link #ticketChart()}'s brand-named order carries no concept, so
	 * {@code DrugSafetyValidator.restsOnAnAmbiguousBridge}'s first conjunct is answered by that
	 * order's own recorded name and code and its bridged half is never reached — no case in this class
	 * said anything about that seam. The ticket's own prescription does carry one: {@code Advil 400mg}
	 * was written against concept {@code Ibuprofen}, which the shipped bridge files on ONE substance
	 * and whose recorded name names it — read off the bundled knowledge base, where CIEL 77897
	 * {@code Ibuprofen} is carried by that one entry and by no other. This case is that arrangement in
	 * this fixture's vocabulary: CIEL 86415 {@code Warfarin}, which the same slice carries the same
	 * way, and the brand order's ATC codes dropped, because a concept the dictionary maps to no ATC
	 * code is the majority shape ({@code ActiveDrugOrder}'s own javadoc carries that count). The clause
	 * the ticket exists for must still stand.
	 *
	 * <p><b>Which conjunct carries it, since the order is inside the ambiguity refusal's population
	 * here and was not before.</b> Nothing the chart records about this order reaches Warfarin except
	 * the bridge's own name ({@code Warfarin}, which is also the concept's recorded name, and which the
	 * exclusion in {@code resolvesAsideFromTheBridgesOwnName} therefore removes) — the display names
	 * nothing the dataset carries and there are no codes left. So the first conjunct is satisfied and
	 * the clause stands on the second: {@code BridgedOrders.recordedNameNames} says that name NAMES the
	 * one substance the concept is filed on. Widening that exclusion to every recorded name does NOT
	 * redden this case, which is why review round 3's other finding needed a case of its own
	 * ({@code BridgedConceptOrderResolutionTest.aRecordedNameBesideTheBridgesOwnIsStillEvidenceOfWhichSubstanceItIs}).
	 *
	 * <p>Revert {@code displaysANameOfAny} to the {@code getNames()} fold it replaced and this reddens
	 * with a null bridge, as that case does too — the order RECORDS {@code Warfarin}, and that is the
	 * name no chart record renders.
	 */
	@Test
	public void aBrandNamedOrderJoinedByItsBridgedConceptIsBridgedToTheSubstanceToo() throws Exception {
		String finding = onlyFinding(ticketChart(DrugReferenceTestSupport.set(), WARFARIN_CONCEPT));

		assertEquals("Warfarin from Coagubrand [1].", DrugReferenceTestSupport.bridgeOf(finding),
			"an order joined to the substance by its bridged concept states the prescription the"
					+ " substance came from, was: " + finding);
	}
}
