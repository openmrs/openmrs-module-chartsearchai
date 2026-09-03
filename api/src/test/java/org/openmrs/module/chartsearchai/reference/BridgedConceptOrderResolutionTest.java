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

	/** How a francophone dictionary spells CIEL 75876 — again a string the fixture carries nowhere, so
	 *  the concept key is provably the only thing that can reach the substance. */
	private static final String INEXIUM_ORDER = "Inexium 40mg";

	/** The uuid the shipped bridge records for CIEL 75876, {@code Esomeprazole magnesium} — filed on
	 *  Omeprazole AND Esomeprazole, which are two substances by
	 *  {@code DdiDrugReferenceSource.substanceIds}' own rule. */
	private static final String ESOMEPRAZOLE_CONCEPT = "75876AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** The ambiguously bridged prescription, optionally carrying the ATC code its concept publishes. */
	private static PatientClinicalContext.ActiveDrugOrder inexiumOrder(Set<String> atcCodes) {
		return PatientClinicalContext.ActiveDrugOrder.named("order-inexium", INEXIUM_ORDER,
			DrugReferenceTestSupport.set(INEXIUM_ORDER), atcCodes, null, ESOMEPRAZOLE_CONCEPT);
	}

	/** The co-prescription the fixture DOES name, so the bridged substance has a pair to chip with. */
	private static PatientClinicalContext.ActiveDrugOrder clopidogrelOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-clopidogrel", "Clopidogrel 75mg",
			DrugReferenceTestSupport.set("Clopidogrel 75mg", "clopidogrel"), null, null, null);
	}

	/** How a francophone dictionary spells the fixed-dose combination CIEL 103166 — again a string the
	 *  fixture carries in no field of any entry. */
	private static final String KIVEXA_ORDER = "Kivexa 600/300";

	/** The uuid the shipped bridge records for CIEL 103166, {@code Abacavir / lamivudine} — filed on
	 *  Abacavir AND Lamivudine, which the prescription really does BOTH contain. */
	private static final String ABACAVIR_LAMIVUDINE_CONCEPT = "103166AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** The combination prescription, joined to the dataset by its concept alone. */
	private static PatientClinicalContext.ActiveDrugOrder kivexaOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-kivexa", KIVEXA_ORDER,
			DrugReferenceTestSupport.set(KIVEXA_ORDER), null, null, ABACAVIR_LAMIVUDINE_CONCEPT);
	}

	/** The co-prescription the fixture DOES name, carrying the {@code Minor} rule with lamivudine. */
	private static PatientClinicalContext.ActiveDrugOrder trimethoprimOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-trimethoprim", "Trimethoprim 100mg",
			DrugReferenceTestSupport.set("Trimethoprim 100mg", "trimethoprim"), null, null, null);
	}

	private static List<String> bridgeTexts(SafetyWarning warning) {
		List<String> bridged = new ArrayList<String>();
		for (SafetyWarning.ChartOrderBridge bridge : warning.chartOrderBridges()) {
			bridged.add(bridge.toString());
		}
		return bridged;
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
	 * The SCREENING arm reaches the leg too — the two sites where a wrong answer SILENCES rather than
	 * adds, {@code activeOrdersOtherThan} and {@code ordersOtherThan}, which withhold an order from
	 * witnessing a pair. ADR Decision 68 rests the whole "the leg must be RANKED" argument on those
	 * two, and until this case they were reachable only through the drug-in-play arm's own consumer:
	 * passing {@code BridgedOrders.NONE} at both left the entire suite green.
	 *
	 * <p><b>What THIS case pins is that the arm reaches them AT ALL, and not either of them on its
	 * own.</b> Measured in review round 2, one mutation per run over the whole api suite: substituting
	 * {@code BridgedOrders.NONE} for the {@code bridged} argument at {@code ordersOtherThan}'s
	 * {@code resolvesFromAny} leaves this case green, and so does the same substitution at
	 * {@code activeOrdersOtherThan}'s own; only neutering all four bridged arguments in those two
	 * methods reddens it. A change that drops the leg from ONE of the two is the plausible slip, since
	 * they are separate methods with separate parameters, and the case per site owed for that is
	 * {@code BridgedOrderSelfWitnessContextTest} — one per method, each reddening on its own
	 * substitution. What that class needs and this one does not is a lowered severity floor, which is
	 * why it is context-sensitive and separate.
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
	 * The one thing a bridged order must NOT be made to say (issue #353, review round 1).
	 *
	 * <p>Where the bridge files a concept on SEVERAL substances the module knows the prescription is
	 * one of them and cannot say which. The bridged leg is still what resolved the order — the screen
	 * runs and the chip stands — but the finding's "&lt;substance&gt; from &lt;prescription&gt;" clause
	 * would state one of those substances as this prescription's, in text
	 * {@code DrugReferenceInjector.renderFinding} copies verbatim into a citable {@code safety_finding}
	 * carrying {@code STRENGTH_WITHHOLD}. Here the prescription is written against
	 * {@code Esomeprazole magnesium} and the clause would read {@code Omeprazole from Inexium 40mg}.
	 *
	 * <p>It is exactly the orders this leg exists for that cannot be silenced by
	 * {@code addChartOrderBridge}'s recorded-names test: the premise of the leg is that the order's own
	 * names do not reach the substance. So the refusal is its own conjunct —
	 * {@code restsOnAnAmbiguousBridge}.
	 *
	 * <p>The premise is asserted rather than assumed: without the two substances the case would also
	 * pass for a guard that refused every bridged order, and for a fixture that had stopped carrying
	 * the shape at all.
	 */
	@Test
	public void aConceptBridgedToSeveralSubstancesNamesNoPrescriptionInTheFinding() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_BRIDGED_CONCEPT_TWO_SUBSTANCES);
		PatientClinicalContext chart = chart(inexiumOrder(null), clopidogrelOrder());

		assertEquals(Arrays.asList("Clopidogrel", "Omeprazole", "Esomeprazole"),
			displayNames(service.findForActiveOrders(chart)),
			"the premise: the bridge files that concept on two substances, so this order resolves both");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient omeprazole?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(), "the screen must still run, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertTrue(warnings.get(0).getDetail()
				.startsWith("Omeprazole interacts with active order Clopidogrel \u2014 Major"),
			"and the chip must still stand, was: " + warnings.get(0).getDetail());
		assertEquals(Collections.<String> emptyList(), bridgeTexts(warnings.get(0)),
			"but nothing may name a prescription the module cannot say the substance of");
	}

	/**
	 * The SCOPE of that refusal, and the reason it is two conjuncts rather than one: the same
	 * ambiguously bridged prescription, now carrying the ATC code its own concept publishes. The
	 * substance is then reached by a key the chart itself records, and the clause stands exactly as it
	 * did before the guard.
	 *
	 * <p>What this case does NOT assert is that the code leg is right to reach it. That leg has an
	 * over-wide residue of its own — two substances under one level-5 code — which
	 * {@code DrugSafetyValidator.resolvesFrom}'s javadoc names and which this change deliberately
	 * leaves open: closing it would change what every ATC-resolved order states, and nothing here
	 * measures that. This pins where the new guard stops.
	 */
	@Test
	public void anAmbiguouslyBridgedOrderTheChartsOwnCodeReachesIsStillAttributed() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_BRIDGED_CONCEPT_TWO_SUBSTANCES);
		PatientClinicalContext chart = chart(inexiumOrder(DrugReferenceTestSupport.set("A02BC05")),
			clopidogrelOrder());

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient omeprazole?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(), "precondition: the pair must chip at all, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertEquals(Arrays.asList("Omeprazole from " + INEXIUM_ORDER), bridgeTexts(warnings.get(0)),
			"an order whose own recorded code reaches the substance is attributed as it was before"
					+ " the ambiguity guard");
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
	 * and a public accessor makes this one known — so both of its accessors carry {@code @JsonIgnore},
	 * and the file below therefore PARSES. Take both annotations away and it does not:
	 * {@code MAPPER.readValue} throws a {@code MismatchedInput}, because the {@code bridgedConcepts}
	 * value here is not even the right TYPE. That is what makes this a pin on the property being
	 * unbindable rather than a pin on nothing. Removing just ONE of the two annotations leaves this
	 * case green — measured, and why neither of them is described as the load-bearing one.
	 *
	 * <p>What the throw would cost the operator is on {@link DrugReference#getBridgedConcepts()}: not
	 * a silent empty load, but their whole dataset replaced by the bundled fallback under a loud
	 * {@code configured-data-file-not-read} finding. It drives the real
	 * {@code JsonDrugReferenceSource.parse}, which is where the throw would happen.
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
	 * The refusal above is of an AMBIGUITY and not of a combination, and this is the case that says so
	 * (issue #353, review round 2).
	 *
	 * <p>{@code Kivexa} is a fixed-dose abacavir/lamivudine tablet — the shape a francophone ARV
	 * medication list is mostly made of. The bridge files CIEL 103166 on Abacavir AND Lamivudine, so
	 * the order resolves two substances; but its recorded name, {@code Abacavir / lamivudine}, NAMES
	 * both of them, and the prescription really does contain both. Nothing about which substance the
	 * prescription is is in doubt, and the clause is true of each side.
	 *
	 * <p>Round 1's guard asked only whether the order's bridged answer spanned more than one substance,
	 * so it refused this — measured over the shipped knowledge base, on 990 of the 1112 bridged concepts
	 * that answer with more than one substance, which is every real combination in it. That reopened
	 * issue #349's defect for the bridged population: a Major chip naming a substance that appears
	 * nowhere on the medication list, with no clause saying which prescription it came from. It is also
	 * what CLAUDE.md's standing rule "a combination order carrying BOTH substances bridges both sides"
	 * says must not happen, though {@code InteractionFindingChartOrderBridgeTest}'s own case for it
	 * stayed green because its order is named rather than bridged.
	 *
	 * <p>Restore the count in {@code restsOnAnAmbiguousBridge} — ask whether the order's bridged answer
	 * spans more than one {@code substanceGroupKey} instead of asking
	 * {@code !bridged.recordedNameNames(rows, order)} — and this case reddens with an empty bridge
	 * list.
	 */
	@Test
	public void aCombinationPrescriptionItsBridgeNameNamesIsStillAttributed() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext chart = chart(kivexaOrder(), trimethoprimOrder());

		assertEquals(Arrays.asList("Trimethoprim", "Abacavir", "Lamivudine"),
			displayNames(service.findForActiveOrders(chart)),
			"the premise: the bridge files that concept on two substances, so this order resolves both");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Do any of her medications interact?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(), "one pair, was: " + DrugReferenceTestSupport.details(warnings));
		assertTrue(warnings.get(0).getDetail()
				.startsWith("Trimethoprim interacts with active order lamivudine \u2014 Minor."),
			"precondition: the pair must be reported from the named order's side, was: "
					+ warnings.get(0).getDetail());
		assertEquals(Arrays.asList("lamivudine from " + KIVEXA_ORDER), bridgeTexts(warnings.get(0)),
			"a combination the bridge's own name names is attributed to the prescription it came from");
	}

	/**
	 * The refusal is asked PER SUBSTANCE, so one bridged prescription can state a clause for the
	 * substance its recorded name names while stating none for the substance it does not.
	 *
	 * <p>Same prescription and same concept as
	 * {@link #aConceptBridgedToSeveralSubstancesNamesNoPrescriptionInTheFinding}; only the question
	 * changes. {@code Esomeprazole magnesium} names Esomeprazole and does not name Omeprazole
	 * ({@code DrugReferenceService.findNamedSubstances} over the bridged answer), and both of those are
	 * the truth about this prescription: the patient IS on esomeprazole, and she is not on omeprazole.
	 *
	 * <p>Pinned deliberately rather than left to fall out. Round 1's guard refused both sides of this
	 * concept, and a maintainer reading only the case above would take the absence of a clause for
	 * Esomeprazole to be intended. Neuter {@code restsOnAnAmbiguousBridge} to {@code return true} and
	 * this reddens; neuter it to {@code return false} and the case above reddens.
	 */
	@Test
	public void theSubstanceTheBridgesOwnNameNamesIsAttributedThoughItsSiblingIsNot() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_BRIDGED_CONCEPT_TWO_SUBSTANCES);
		PatientClinicalContext chart = chart(inexiumOrder(null), clopidogrelOrder());

		List<SafetyWarning> named = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient esomeprazole?", service.withReferenceNames(chart));

		assertEquals(1, named.size(), "precondition: the named substance must chip at all, was: "
				+ DrugReferenceTestSupport.details(named));
		assertTrue(named.get(0).getDetail()
				.startsWith("Esomeprazole interacts with active order Clopidogrel \u2014 Major"),
			"precondition: the chip must be about the substance the bridge names, was: "
					+ named.get(0).getDetail());
		assertEquals(Arrays.asList("Esomeprazole from " + INEXIUM_ORDER), bridgeTexts(named.get(0)),
			"the substance the bridge's own recorded name names is attributed to the prescription");

		List<SafetyWarning> unnamed = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give this patient omeprazole?", service.withReferenceNames(chart));

		assertEquals(1, unnamed.size(), "precondition: the sibling must chip too, was: "
				+ DrugReferenceTestSupport.details(unnamed));
		assertEquals(Collections.<String> emptyList(), bridgeTexts(unnamed.get(0)),
			"and the one it does not name states no prescription, out of the same bridged answer");
	}

	/**
	 * The same refusal asked of the PARTNER side, and the property that makes it a rule about the
	 * substance rather than about the knowledge-base file (issue #353, review round 3).
	 *
	 * <p><b>What was wrong.</b> {@code restsOnAnAmbiguousBridge} is asked of a ROW GROUP, and on the
	 * partner side that group is {@code rowsOfSubstance} of the entry {@code activeOrderEntryFor}
	 * elected. That scan took the FIRST order entry the rule's token identified, and both substances of
	 * this concept carry {@code esomeprazole} as a name — it is Esomeprazole's display name and
	 * Omeprazole's {@code rxnorm_name}, which is the very tie that files them under one bridge. So a
	 * chip printing {@code esomeprazole} had its clause decided against Omeprazole, the bridge's name
	 * does not name Omeprazole, and the clause was withheld: a Major finding naming a substance that
	 * appears nowhere on a medication list reading {@code Inexium 40mg}, with nothing connecting the
	 * two. That is issue #349's defect, in the population issue #353 exists for, from the other side of
	 * the same pair — and which way it fell was decided by which of the two rows the dataset lists
	 * first.
	 *
	 * <p><b>Both row orders, deliberately.</b> A case pinning only today's file would pass for the
	 * first-match scan as soon as the knowledge base put Esomeprazole first — measured, and it is why
	 * the second half of this case exists. {@code activeOrderEntryFor} now RANKS the candidates by
	 * {@link DrugReference#nameMatchStrength}, so the entry the token names as its own display name
	 * beats the entry that carries it as an alias whichever order they arrive in.
	 *
	 * <p><b>And three questions, because the arms differ.</b> {@code esomeprazole?} reaches the
	 * substance as the drug-in-play arm's SUBJECT, where the group was never in doubt and the clause
	 * already stood; {@code clopidogrel?} reaches it as that arm's PARTNER; the plain screening
	 * question reaches it through {@code addActiveOrderPairInteractions}, which resolves its own
	 * partner. Mutate the election back to the first match and the second and third redden while the
	 * first stays green, which is exactly what round 3 found this pinned as.
	 */
	@Test
	public void theBridgedClauseIsAskedOfTheSubstanceAndNotOfTheDatasetsRowOrder() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_BRIDGED_CONCEPT_TWO_SUBSTANCES);

		assertEquals(Arrays.asList("Omeprazole", "Esomeprazole", "Clopidogrel"),
			displayNames(entries), "the premise: the fixture lists the un-named substance first");

		List<DrugReference> esomeprazoleFirst = Arrays.asList(
			DrugReferenceTestSupport.row(entries, "Esomeprazole"),
			DrugReferenceTestSupport.row(entries, "Omeprazole"),
			DrugReferenceTestSupport.row(entries, "Clopidogrel"));

		for (List<DrugReference> rows : Arrays.asList(entries, esomeprazoleFirst)) {
			DrugReferenceService service = DrugReferenceTestSupport.serviceWith(rows);
			PatientClinicalContext chart = chart(inexiumOrder(null), clopidogrelOrder());
			String order = displayNames(rows).toString();

			List<SafetyWarning> partnerSide = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give this patient clopidogrel?", service.withReferenceNames(chart));

			assertEquals(1, partnerSide.size(), "precondition: the pair must chip from this side too,"
					+ " rows " + order + ", was: " + DrugReferenceTestSupport.details(partnerSide));
			assertTrue(partnerSide.get(0).getDetail()
					.startsWith("Clopidogrel interacts with active order esomeprazole \u2014 Major"),
				"precondition: the chip names the partner by the rule's own token, rows " + order
						+ ", was: " + partnerSide.get(0).getDetail());
			assertEquals(Arrays.asList("esomeprazole from " + INEXIUM_ORDER),
				bridgeTexts(partnerSide.get(0)),
				"the substance the chip PRINTS is the one the bridge's name names, so the prescription"
						+ " it came from is stated — rows " + order);

			List<SafetyWarning> screened = DrugReferenceTestSupport.validator(service).validate("",
				"Do any of her medications interact?", service.withReferenceNames(chart));

			assertEquals(1, screened.size(), "precondition: one pair, rows " + order + ", was: "
					+ DrugReferenceTestSupport.details(screened));
			assertEquals(Arrays.asList("esomeprazole from " + INEXIUM_ORDER),
				bridgeTexts(screened.get(0)),
				"and the screening arm, which resolves its own partner, states it too — rows " + order);

			List<SafetyWarning> subjectSide = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give this patient esomeprazole?", service.withReferenceNames(chart));

			assertEquals(Arrays.asList("Esomeprazole from " + INEXIUM_ORDER),
				bridgeTexts(subjectSide.get(0)),
				"the subject side is unchanged by the election — rows " + order);

			List<SafetyWarning> sibling = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give this patient omeprazole?", service.withReferenceNames(chart));

			assertEquals(Collections.<String> emptyList(), bridgeTexts(sibling.get(0)),
				"and the substance the bridge's name does not name still states no prescription, from"
						+ " either row order — rows " + order);
		}
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
