/*
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The two sites where issue #353's bridged-concept leg SILENCES rather than adds, one case each
 * (review round 3).
 *
 * <p>{@code DrugSafetyValidator.activeOrdersOtherThan} and {@code DrugSafetyValidator.ordersOtherThan}
 * both withhold the subject's own prescriptions — from witnessing the subject's interactions, and from
 * being named as its partner's source. ADR Decision 68 rests the whole "the leg must be RANKED"
 * argument on those two, because everywhere else an over-wide answer only misses a pair while here it
 * removes a warning or misattributes one, with no chip difference and no log line. Round 2 measured
 * that neutering the bridged argument at either method ALONE left the whole api suite green and said
 * so rather than closing it. These are the owed cases.
 *
 * <p><b>They are two methods with two parameters, and the plausible slip is dropping the leg from one
 * of them</b>, so one case per method: substitute {@code BridgedOrders.NONE} for the argument at
 * {@code activeOrdersOtherThan}'s own {@code resolvesFromAny} and the first case reddens; do it at
 * {@code ordersOtherThan}'s and the second does.
 *
 * <p><b>Context-sensitive because the arrangement needs a lowered severity floor</b>, which is a
 * global property. It needs one for a structural reason and not a convenient one: the shape both cases
 * turn on is one prescription whose bridged concept resolves TWO substances that the knowledge base
 * relates to each other, and DDInter rates every real fixed-dose combination's constituents against
 * each other {@code Unknown} — below the shipped {@code minor} floor. {@code Abacavir / lamivudine}
 * (CIEL 103166) is that shape in {@code ddi-combination-allergen.json}, verbatim from the shipped
 * dataset, and the rating is the dataset's own.
 */
public class BridgedOrderSelfWitnessContextTest extends BaseModuleContextSensitiveTest {

	/** The uuid the shipped bridge records for CIEL 103166, {@code Abacavir / lamivudine} — filed on
	 *  Abacavir AND Lamivudine, and the fixture carries the KB's own {@code Unknown} rule between
	 *  them. */
	private static final String ABACAVIR_LAMIVUDINE_CONCEPT = "103166AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** How a francophone dictionary spells that combination — a string the fixture carries in no field
	 *  of any entry, so the concept key is provably the only thing that can reach either substance. */
	private static final String KIVEXA_ORDER = "Kivexa 600/300";

	private DrugReferenceService service;

	@BeforeEach
	public void loadTheFixtureAndLowerTheFloor() throws Exception {
		// Unknown is what DDInter rates two constituents of one tablet against each other, so nothing
		// below this floor can show either suppression at all.
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "unknown");
		service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_COMBINATION_ALLERGEN);
	}

	private static PatientClinicalContext.ActiveDrugOrder kivexaOrder() {
		return PatientClinicalContext.ActiveDrugOrder.named("order-kivexa", KIVEXA_ORDER,
			DrugReferenceTestSupport.set(KIVEXA_ORDER), null, null, ABACAVIR_LAMIVUDINE_CONCEPT);
	}

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
		return DrugReferenceTestSupport.ctx(38, null, names, codes, null, null, Arrays.asList(orders));
	}

	private static List<String> bridgeTexts(SafetyWarning warning) {
		List<String> bridged = new ArrayList<String>();
		for (SafetyWarning.ChartOrderBridge bridge : warning.chartOrderBridges()) {
			bridged.add(bridge.toString());
		}
		return bridged;
	}

	private List<SafetyWarning> screen(PatientClinicalContext chart) {
		return DrugReferenceTestSupport.validator(service).validate("",
			"Do any of her medications interact?", service.withReferenceNames(chart));
	}

	/**
	 * {@code activeOrdersOtherThan}: one prescription must not be reported as an interaction between
	 * itself and itself.
	 *
	 * <p>The only prescription on this chart is the combination, and the bridge is the only thing that
	 * joins it to either constituent — its own name reaches neither. Withheld from the reduced context,
	 * it contributes no name, no code and no reference alias, so the arm relates nothing and the screen
	 * is empty. Left in, it supplies the aliases of the entries it resolves and the pair fires with the
	 * same prescription on both sides.
	 *
	 * <p>The premise is asserted first, or an empty screen would also be what a fixture that had
	 * stopped carrying the rule produced.
	 */
	@Test
	public void theBridgedOrderDoesNotWitnessAnInteractionBetweenItsOwnTwoConstituents() {
		PatientClinicalContext chart = chart(kivexaOrder());

		assertEquals(Arrays.asList("Abacavir", "Lamivudine"),
			DrugReferenceTestSupport.names(service.findForActiveOrders(chart)),
			"the premise: one prescription, and the bridge resolves both of its constituents");
		List<String> tokens = new ArrayList<String>();
		for (DrugReference.Interaction rule : DrugReferenceTestSupport
				.row(service.getAll(), "Abacavir").getInteractions()) {
			tokens.add(rule.getToken());
		}
		assertTrue(tokens.contains("lamivudine"),
			"the premise: the fixture relates the two constituents to each other, was: " + tokens);

		assertEquals(Collections.<String> emptyList(),
			DrugReferenceTestSupport.details(screen(chart)),
			"one prescription is not a pair, whichever of its constituents the dataset can name");
	}

	/**
	 * {@code ordersOtherThan}: the prescription the SUBJECT came from must not also be named as its
	 * PARTNER's source.
	 *
	 * <p>Here the patient is on the combination AND on a separate lamivudine order, so the pair is
	 * genuine and is found from the second order — that is what {@code activeOrdersOtherThan} keeps.
	 * The clause then says where each side came from, and the combination is the subject's source
	 * alone: naming it for the partner too would present one prescription as both sides of its own
	 * interaction, inside a citable {@code safety_finding}. The separate lamivudine order names the
	 * partner in its own words and so needs no bridge at all.
	 */
	@Test
	public void theBridgedOrderIsNotNamedAsItsOwnPartnersSource() {
		PatientClinicalContext chart = chart(kivexaOrder(), lamivudineOrder());

		List<SafetyWarning> warnings = screen(chart);

		assertEquals(1, warnings.size(), "precondition: one pair, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertEquals(Arrays.asList("Abacavir from " + KIVEXA_ORDER), bridgeTexts(warnings.get(0)),
			"the bridged prescription is the subject's source and nothing else's, was: "
					+ warnings.get(0).getDetail());
	}
}
