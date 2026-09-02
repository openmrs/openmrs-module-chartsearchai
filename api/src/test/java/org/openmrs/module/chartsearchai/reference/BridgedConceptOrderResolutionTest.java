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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Issue #353: an active order joins the reference data through the CONCEPT it was written against, and
 * not only through whichever of that concept's names the deployment's locale elects.
 *
 * <p><b>The reported failure.</b> The knowledge base's CIEL bridge keeps ONE name per code — the
 * {@code en} locale-preferred fully specified name — so CIEL 105281 reaches the dataset as
 * {@code Sulfamethoxazole / trimethoprim} and as nothing else. No entry in the shipped 19 MB knowledge
 * base carries any spelling of {@code cotrimoxazole}. The order-to-entry join had two keys and both
 * fail for that concept: the ATC leg because the concept maps to {@code J01EE01}, which the knowledge
 * base publishes on no entry at all, and the NAME leg because
 * {@code PatientClinicalContextBuilder.addConceptName} reads {@code Concept.getName()}, the single
 * locale-PREFERRED spelling. In an {@code en} session that spelling happens to be the one the bridge
 * carries and the screen works; the ticket measured the same order in {@code fr}, where the preferred
 * name is {@code Cotrimoxazole}, returning no chips at all — with no exception and no log line.
 *
 * <p><b>What these cases pin, and why the order name they use has no spelling in the dataset.</b>
 * Every case here gives the order a display and names that appear nowhere in the fixture, which is
 * what makes the NAME leg provably not the thing that resolved it. The concept uuid is the real one
 * the shipped bridge records for CIEL 105281, and the fixture is {@code ddi-combination-allergen.json}
 * — a verbatim slice of the shipped knowledge base that already carries DDInter1874 Trimethoprim with
 * that bridge, DDInter1019 Lamivudine, and the {@code Minor} interaction row they share. Shared rather
 * than sliced again for this issue: a second file carrying the same verbatim bridge is a second place
 * to update when the knowledge base is refreshed, and only one of them would be.
 *
 * <p>A slice rather than the whole 19 MB dataset for the reason
 * {@code DrugReferenceTestSupport.shippedEntries} records — a case asserting chip TEXT must not depend
 * on a knowledge-base refresh leaving one family alone. {@code BridgedConceptLegBoundsTest} is the
 * opposite choice, and says why it has to be.
 */
public class BridgedConceptOrderResolutionTest {

	/** The uuid the shipped bridge records for CIEL 105281, {@code Sulfamethoxazole / trimethoprim}. */
	private static final String COTRIMOXAZOLE_CONCEPT = "105281AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/**
	 * How a francophone dictionary spells that concept. Deliberately a string the knowledge base
	 * carries in no field of any entry — the ticket measured that, and it is what makes every
	 * assertion below a statement about the concept key rather than about a name.
	 */
	private static final String COTRIMOXAZOLE_ORDER = "Cotrimoxazole 960mg";

	private static DrugReferenceService service() throws Exception {
		return DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_COMBINATION_ALLERGEN);
	}

	/** The order the dictionary spells in a locale the bridge does not carry, carrying the concept it
	 *  was written against — {@code PatientClinicalContextBuilder}'s own shape, minus the ATC codes
	 *  the concept does not usefully map to. */
	private static PatientClinicalContext.ActiveDrugOrder cotrimoxazoleOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-cotrimoxazole", COTRIMOXAZOLE_ORDER,
			DrugReferenceTestSupport.set(COTRIMOXAZOLE_ORDER), DrugReferenceTestSupport.set("J01EE01"),
			null, COTRIMOXAZOLE_CONCEPT);
	}

	/** A second prescription the fixture DOES name, so the screening arm has a pair to find. */
	private static PatientClinicalContext.ActiveDrugOrder lamivudineOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-lamivudine", "Lamivudine 150mg",
			DrugReferenceTestSupport.set("Lamivudine 150mg", "lamivudine"), null, null, null);
	}

	private static PatientClinicalContext chart(PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = DrugReferenceTestSupport.set();
		Set<String> codes = DrugReferenceTestSupport.set();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.ctx(38, null, names, codes, null, null,
			Arrays.asList(orders));
	}

	private static List<String> displayNames(List<DrugReference> resolved) {
		List<String> names = new ArrayList<String>();
		for (DrugReference entry : resolved) {
			names.add(entry.getName());
		}
		return names;
	}

	/**
	 * The candidate-set leg, on its own. The order names nothing the dataset carries and its one ATC
	 * code is on no entry, so before issue #353 this list was empty — which is why nothing was
	 * screened and why the answer read as an absence of records.
	 */
	@Test
	public void anOrderNamedOnlyInALocaleTheBridgeDoesNotCarryStillResolvesItsSubstance()
			throws Exception {
		DrugReferenceService service = service();
		List<DrugReference> resolved = service.findForActiveOrders(chart(cotrimoxazoleOrder()));

		assertEquals(Arrays.asList("Trimethoprim"), displayNames(resolved),
			"the bridge records CIEL 105281 as this entry's concept, so an order written against that"
					+ " concept is this substance whatever the session's locale spells it");
	}

	/**
	 * The composed path, and the ticket's own reproduction: the same order screened by the real
	 * {@code validate}, asked the question the ticket asked. Asserted through the CHIP rather than the
	 * candidate set, because a resolution nothing reports on is not the fix.
	 *
	 * <p>The expected text is the one the ticket measured on the standalone in an {@code en} session,
	 * word for word — which is the point of the case. The locale is the only thing that differs here,
	 * and the answer must not.
	 */
	@Test
	public void theInteractionWithTheCoPrescriptionIsScreenedAndNamesTheOtherOrder() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext chart = chart(cotrimoxazoleOrder());

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient Lamivudine?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(),
			"an order the dataset can only be joined to by its concept must still be screened, was: "
					+ DrugReferenceTestSupport.details(warnings));
		assertTrue(warnings.get(0).getDetail()
				.startsWith("Lamivudine interacts with active order trimethoprim \u2014 Minor."),
			"the chip the ticket measured in an en session, in a locale that spells the order"
					+ " differently, was: " + warnings.get(0).getDetail());
	}

	/**
	 * The finding says which of the patient's own orders the substance came from (issue #349), and an
	 * order joined ONLY by its concept is exactly the shape that clause is for: the chart's own words
	 * for the prescription are {@code Cotrimoxazole 960mg}, which name the substance
	 * {@code trimethoprim} nowhere, so without the bridge a clinician reading the chip has no way to
	 * connect it to anything on the medication list.
	 *
	 * <p>{@code addChartOrderBridge} refuses an order whose own recorded names already reach the
	 * substance, so this clause appears for the concept-joined order and would NOT appear for the same
	 * prescription in an {@code en} session — the two locales differ in the bridge clause and agree on
	 * the finding, which is the honest reading of what each one's chart records.
	 */
	@Test
	public void theFindingSaysWhichPrescriptionTheSubstanceCameFrom() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext chart = chart(cotrimoxazoleOrder());

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient Lamivudine?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(), "precondition: the pair must chip at all");
		List<String> bridged = new ArrayList<String>();
		for (SafetyWarning.ChartOrderBridge bridge : warnings.get(0).chartOrderBridges()) {
			bridged.add(bridge.toString());
		}
		assertEquals(Arrays.asList("trimethoprim from " + COTRIMOXAZOLE_ORDER), bridged,
			"the prescription the concept key resolved must be named as the substance's source");
	}

	/**
	 * The SCREENING arm reaches the leg too, and this is what pins the two sites where a wrong answer
	 * SILENCES rather than adds — {@code activeOrdersOtherThan} and {@code ordersOtherThan}, which
	 * withhold an order from witnessing a pair. ADR Decision 68 rests the whole "the leg must be
	 * RANKED" argument on those two, and until this case they were reachable only through the
	 * drug-in-play arm's own consumer: passing {@code BridgedOrders.NONE} at both left the entire
	 * suite green.
	 *
	 * <p>What the mutation moves, measured rather than theorised: with the leg, the pair is reported
	 * from the LAMIVUDINE side and the partner is the bridged substance, so the chip names the
	 * prescription the concept key resolved. With {@code NONE} at those two sites the same pair is
	 * reported from the other side — {@code "Trimethoprim interacts with active order Lamivudine"} —
	 * because the subject's own bridged prescription is no longer withheld from witnessing it. One
	 * pair either way; which drug the clinician is told to look at changes. Nothing here claims the
	 * mutation reddens only this case.
	 */
	@Test
	public void theScreeningArmWithholdsTheBridgedOrderFromWitnessingItsOwnPair() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext chart = chart(cotrimoxazoleOrder(), lamivudineOrder());

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Do any of her medications interact?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(), "one pair, was: " + DrugReferenceTestSupport.details(warnings));
		assertTrue(warnings.get(0).getDetail()
				.startsWith("Lamivudine interacts with active order trimethoprim \u2014 Minor."),
			"the pair must be reported from the side whose own prescription is not the bridged one,"
					+ " was: " + warnings.get(0).getDetail());
		List<String> bridged = new ArrayList<String>();
		for (SafetyWarning.ChartOrderBridge bridge : warnings.get(0).chartOrderBridges()) {
			bridged.add(bridge.toString());
		}
		assertEquals(Arrays.asList("trimethoprim from " + COTRIMOXAZOLE_ORDER), bridged,
			"and the bridged substance must be attributed to the prescription the concept resolved");
	}

	/**
	 * A concept the bridge does not record at all joins to nothing. The negative control for the cases
	 * above and for {@code BridgedConceptLegBoundsTest}, which is where the two bounds that NARROW a
	 * non-empty answer are pinned — an empty result here would also be produced by a leg that did
	 * nothing whatever, and those are what rule that out.
	 */
	@Test
	public void aConceptTheBridgeDoesNotRecordJoinsToNothing() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext.ActiveDrugOrder unbridged = PatientClinicalContext.ActiveDrugOrder.named(
			"order-unknown", "Produit inconnu", DrugReferenceTestSupport.set("Produit inconnu"), null,
			null, "not-a-uuid-the-bridge-records");

		assertEquals(Collections.<DrugReference> emptyList(),
			service.findForActiveOrders(chart(unbridged)),
			"a concept the bridge does not record joins to nothing at all");
	}

	/**
	 * The curated {@code json} format declares no bridge, and an authored file that carried one anyway
	 * must not be read as one.
	 *
	 * <p>{@code DrugReference}'s {@code ignoreUnknown} covers only properties Jackson does not KNOW,
	 * and a public accessor makes this one known — so such a file fails to BIND and
	 * {@code MAPPER.readValue} throws. What that costs the operator is measured on
	 * {@link DrugReference#getBridgedConcepts()}: not a silent empty load, but their whole dataset
	 * replaced by the bundled fallback under a loud {@code configured-data-file-not-read} finding.
	 * This case pins the PARSER, which is where the throw happens; it drives the real
	 * {@code JsonDrugReferenceSource.parse} on a file whose {@code bridgedConcepts} value is not even
	 * the right SHAPE, so it reddens if the property is ever made bindable.
	 */
	@Test
	public void aCuratedFileCarryingABridgeStillLoadsTheEntriesBesideIt() throws Exception {
		String authored = "{\"entries\":[{\"id\":\"a\",\"name\":\"Aspirin\","
				+ "\"aliases\":[\"aspirin\"],\"bridgedConcepts\":\"not even a list\"}]}";
		List<DrugReference> loaded = JsonDrugReferenceSource.parse(
			new java.io.ByteArrayInputStream(authored.getBytes("UTF-8")),
			new DrugReferenceValidity());

		assertEquals(Arrays.asList("Aspirin"), displayNames(loaded),
			"an unrecognised bridge key must cost the key and never the entries beside it");
		assertTrue(loaded.get(0).getBridgedConcepts().isEmpty(),
			"and it must not be read as a bridge either");
	}

	/**
	 * An order the module could read no NAME for is exactly the population this join exists for, and it
	 * reaches the per-order list on its own rung (issue #290). Its {@code getNames()} is empty by
	 * design, so the name leg cannot help it and the concept key is the only one left.
	 */
	@Test
	public void anOrderNoNameCouldBeReadForIsStillJoinedByItsConcept() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext.ActiveDrugOrder nameless = PatientClinicalContext.ActiveDrugOrder
				.namedByCodesOnly("order-nameless", "[ATC J01EE01]",
					DrugReferenceTestSupport.set("J01EE01"), null, COTRIMOXAZOLE_CONCEPT);

		assertEquals(Arrays.asList("Trimethoprim"),
			displayNames(service.findForActiveOrders(chart(nameless))),
			"an order with no readable name is the population the concept key exists for");
	}
}
