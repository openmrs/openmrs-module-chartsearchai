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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen of her orders relates two substances wherever they come from two DIFFERENT orders, including where every
 * order carrying one also carries the other (ADR Decision 486). On the public demo patient dd88589e, on "Rifampicin
 * and isoniazid" and "Isoniazid / pyrazinamide / rifampin" among others, "Does he have any drug contraindications?"
 * listed nine interactions and left out isoniazid against rifampicin, which the shipped DDInter knowledge base rates
 * Minor, the configured floor: each of the two orders carries both, so each substance's own orders were all the
 * orders carrying the other, and nothing was left to witness the pair. Pyrazinamide against rifampicin, carried in one
 * of those orders the same way, was reported, because rifampicin's second order is not pyrazinamide's.
 */
public class SubstanceInSeveralOrdersPairTest extends BaseModuleContextSensitiveTest {

	private static final String RH = "Rifampicin and isoniazid";

	private static final String RHZ = "Isoniazid / pyrazinamide / rifampin";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void twoOrdersEachCarryingBothSubstancesRelateThem() {
		PairChipExtent extent = screenExtent(order("order-rh", RH), order("order-rhz", RHZ));

		assertNotNull(extent, "the screen stated its extent");
		assertEquals(2, extent.getFound(),
			"pyrazinamide-rifampicin (Major) and isoniazid-rifampicin (Minor): " + extent);
	}

	@Test
	public void oneCombinationOrderAloneRelatesNoneOfItsOwnSubstances() {
		PairChipExtent extent = screenExtent(order("order-rhz", RHZ));

		assertNotNull(extent, "the screen stated its extent");
		assertEquals(0, extent.getFound(), "one tablet is not an interacting pair: " + extent);
	}

	@Test
	public void twoOrdersOfOneCombinationRelateItsSubstancesAcrossThem() {
		PairChipExtent extent = screenExtent(order("order-rh-1", RH), order("order-rh-2", RH));

		assertNotNull(extent, "the screen stated its extent");
		assertEquals(1, extent.getFound(), "isoniazid in one order, rifampicin in the other: " + extent);
	}

	/** The demo's own chart, as its REST API lists his active drug orders. */
	@Test
	public void theDemoPatientsScreenFindsIsoniazidAgainstRifampicin() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		addOrders(orders, "Abacavir", 2);
		addOrders(orders, "Lamivudine", 3);
		addOrders(orders, "Nevirapine", 2);
		addOrders(orders, "Retrovir", 3);
		addOrders(orders, "Trimethoprim and sulfamethoxazole", 3);
		// As the demo's dictionary records them: each order carries its concept's names and WHO ATC code.
		orders.add(DrugReferenceTestSupport.activeOrder("order-rh", RH,
			new LinkedHashSet<String>(Arrays.asList(RH, "Rifinah")), new LinkedHashSet<String>(Arrays.asList("J04AM02"))));
		orders.add(DrugReferenceTestSupport.activeOrder("order-rhz", RHZ,
			new LinkedHashSet<String>(Arrays.asList(RHZ, "Rifampicin isoniazid and pyrazinamide", "Rifater")),
			new LinkedHashSet<String>(Arrays.asList("J04AC51"))));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(loaded())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(orders),
					"Does he have any drug contraindications?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("10 interactions among this patient's active medications"), "was: " + answer);
		// The finding, as its chip and injected record state it, the partner named as the substance; a Minor, so the
		// answer counts it among its cautions and cites its record (ADR Decision 493).
		Matcher finding = Pattern.compile("\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX)
				+ "[^:\\n]+: Rifampicin \\(rifampin\\) interacts with active order Isoniazid — Minor\\.")
				.matcher(chart.getText());
		assertTrue(finding.find(), "isoniazid against rifampicin is found, Minor: " + chart.getText());
		assertTrue(answer.contains("[" + finding.group(1) + "]"), "and the answer cites it: " + answer);
		assertFalse(chart.getText().contains("Rifampicin (rifampin) interacts with active order Rifampicin and isoniazid"),
			"an order carrying the subject too does not name its partner: " + chart.getText());
	}

	private static DrugReferenceService loaded() {
		return new DrugReferenceService();
	}

	private static PatientClinicalContext.ActiveDrugOrder order(String uuid, String display) {
		return DrugReferenceTestSupport.activeOrder(uuid, display);
	}

	private static void addOrders(List<PatientClinicalContext.ActiveDrugOrder> orders, String display, int n) {
		for (int i = 1; i <= n; i++) {
			orders.add(order("order-" + display + "-" + i, display));
		}
	}

	private static PatientClinicalContext context(List<PatientClinicalContext.ActiveDrugOrder> orders) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.ctx(40, null, names, codes, null, null, orders);
	}

	private static PairChipExtent screenExtent(PatientClinicalContext.ActiveDrugOrder... orders) {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		DrugReferenceTestSupport.validator(loaded()).validate("", DrugReferenceTestSupport.SCREENING_QUESTION,
			context(Arrays.asList(orders)), null, null, sink);
		return sink.stated();
	}
}
