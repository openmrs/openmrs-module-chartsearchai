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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A composed line about an order her chart holds several prescriptions of says how many (ADR Decision 555). On the public
 * demo patient dd926bb0, on two "Isoniazid" orders, "Can i give him panadol?" stated "Acetaminophen interacts with active
 * order Isoniazid — Moderate. [67] [2] [32]": two prescriptions cited under a sentence naming one order.
 */
public class PartnerOrderCountTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aLineAboutAPartnerOfTwoPrescriptionsSaysHowManyOrders() {
		String line = interactionLine(ask(2), "Acetaminophen");
		assertTrue(line.matches(
			"Acetaminophen interacts with active order Isoniazid \\(2 orders\\) — Moderate\\. \\[\\d+\\] \\[\\d+\\] \\[\\d+\\]"),
			"the count after the order's name, before the rating, the finding and both prescriptions cited: " + line);
	}

	@Test
	public void aLineAboutAPartnerOfOnePrescriptionCountsNothing() {
		String line = interactionLine(ask(1), "Acetaminophen");
		assertTrue(line.matches("Acetaminophen interacts with active order Isoniazid — Moderate\\. \\[\\d+\\] \\[\\d+\\]"),
			"one order is named as one, as before: " + line);
	}

	@Test
	public void aDisplayCarryingAStrengthIsCountedUnderTheNameTheLinePrints() {
		// The line prints the drug data's name, "Nevirapine", for orders written "Nevirapine 200mg"
		// (DrugBesideHerMedicationGroupTest), so the count is the partner's and never the display's.
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		orders.add(DrugReferenceTestSupport.activeOrder("order-Nevirapine-1", "Nevirapine 200mg", "Nevirapine"));
		orders.add(DrugReferenceTestSupport.activeOrder("order-Nevirapine-2", "Nevirapine 200mg", "Nevirapine"));
		String line = interactionLine(ask(orders, "Nevirapine", "Can I give him codeine for pain?"), "Codeine");
		assertTrue(line.startsWith("Codeine interacts with active order Nevirapine (2 orders) — "),
			"both prescriptions are counted under the name printed: " + line);
	}

	@Test
	public void anOrderThatHasNotStartedIsNotCountedAsAnActiveOne() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		orders.add(DrugReferenceTestSupport.activeOrder("order-Isoniazid-1", "Isoniazid", "Isoniazid"));
		orders.add(PatientClinicalContext.ActiveDrugOrder.named("order-Isoniazid-2", "Isoniazid",
			DrugReferenceTestSupport.set("Isoniazid"), Collections.<String> emptySet(), Collections.<String> emptySet(), null,
			new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000)));
		String line = interactionLine(ask(orders, "Isoniazid", "Can i give him panadol?"), "Acetaminophen");
		assertTrue(!line.contains("orders)"), "one started order is one active order: " + line);
	}

	@Test
	public void aBrandOrderBridgedToThePartnerBesideAGenericOneIsCountedWithIt() {
		// Nydrazid is bridged ("Isoniazid from Nydrazid"); Isoniazid 300mg names the substance and states no bridge
		// (ADR Decision 70). Both are orders of the partner, and both are cited.
		String line = interactionLine(askOver("Can i give him panadol?", coded("o-nyd", "Nydrazid", "J04AC01", "Nydrazid"),
			coded("o-inh", "Isoniazid 300mg", "J04AC01", "Isoniazid")), "Acetaminophen");
		assertTrue(line.startsWith("Acetaminophen interacts with active order Isoniazid (2 orders) — "), line);
	}

	@Test
	public void aCombinationThatIsTheSubjectsOwnOrderIsNoOrderOfItsPartner() {
		// Pyrazinamide's one source is the combination, whose own rifampicin this screen does not relate to it (ADR
		// Decision 486): only Rifampicin 150mg stands behind the partner.
		String answer = askOver("Does he have any drug interactions?",
			coded("o-rhz", "Isoniazid / pyrazinamide / rifampin", null, "Isoniazid / pyrazinamide / rifampin"),
			coded("o-rif", "Rifampicin 150mg", "J04AB02", "Rifampicin")).getModuleAnswer();
		String line = interactionLine(answer, "Pyrazinamide");
		assertTrue(line.startsWith("Pyrazinamide interacts with active order Rifampicin (rifampin) — "), line);
	}

	@Test
	public void aProposalCountsACombinationCarryingThePartnerBesideItsOwnOrder() {
		// The drug-in-play arm lets every order witness the partner, so the combination's isoniazid is an order of it.
		String line = interactionLine(askOver("Can i give him panadol?",
			coded("o-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol", "J04AM06",
				"Rifampicin isoniazid pyrazinamide and ethambutol", "RHEZ"),
			coded("o-inh", "Isoniazid 300mg", "J04AC01", "Isoniazid")), "Acetaminophen");
		assertTrue(line.startsWith("Acetaminophen interacts with active order Isoniazid (2 orders) — "), line);
	}

	@Test
	public void aPartnerPrintedByAnOrdersOwnDisplayCountsOnlyTheOrdersSpellingIt() {
		// The line names the partner "Isoniazid / Rifapentine", one order's own display; a Priftin order is rifapentine
		// too, but it is not a second "Isoniazid / Rifapentine".
		String line = interactionLine(askOver("Can I give him nevirapine?",
			DrugReferenceTestSupport.activeOrder("order-hp", "Isoniazid / Rifapentine",
				DrugReferenceTestSupport.set("Isoniazid / Rifapentine"), DrugReferenceTestSupport.set("J04AB05", "J04AC51")),
			coded("order-priftin", "Priftin", "J04AB05", "Priftin")), "Nevirapine");
		assertTrue(line.startsWith("Nevirapine interacts with active order Isoniazid / Rifapentine — "), line);
	}

	@Test
	public void anOrderDisplayedByTheSubstancesOwnNameIsCountedWithTheOthers() {
		// "Isoniazid" is the partner's own name and one order's display: both orders are isoniazid orders.
		String line = interactionLine(askOver("Can i give him panadol?", coded("o-inh", "Isoniazid", "J04AC01", "Isoniazid"),
			coded("o-inh300", "Isoniazid 300mg", "J04AC01", "Isoniazid")), "Acetaminophen");
		assertTrue(line.startsWith("Acetaminophen interacts with active order Isoniazid (2 orders) — "), line);
	}

	@Test
	public void anOrderCarryingTheProposedDrugIsNoOtherMedicationOfIt() {
		// Zolvimix is simvastatin and clarithromycin: proposing simvastatin, it is the drug's own order, and only Klarizom
		// is another medication carrying clarithromycin.
		String line = interactionLine(askOver("Can I give him simvastatin?",
			DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix", DrugReferenceTestSupport.set("Zolvimix"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09")),
			DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom", DrugReferenceTestSupport.set("Klarizom"),
				DrugReferenceTestSupport.set("J01FA09"))), "Simvastatin");
		assertTrue(line.startsWith("Simvastatin interacts with active order Clarithromycin — "), line);
	}

	private static PatientClinicalContext.ActiveDrugOrder coded(String uuid, String display, String atc, String... names) {
		return DrugReferenceTestSupport.activeOrder(uuid, display, new LinkedHashSet<String>(Arrays.asList(names)),
			atc == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(Arrays.asList(atc)));
	}

	private static PatientChart askOver(String question, PatientClinicalContext.ActiveDrugOrder... list) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(list);
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(60, null, names, codes, null, null, orders),
			question);
	}

	private static String interactionLine(PatientChart chart, String drug) {
		return interactionLine(chart.getModuleAnswer(), drug);
	}

	private static String interactionLine(String answer, String drug) {
		assertNotNull(answer, "the module answers");
		for (String each : answer.split("\n")) {
			if (each.startsWith(drug + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE)) {
				return each;
			}
		}
		throw new AssertionError("precondition: a line states the caution: " + answer);
	}

	private static PatientChart ask(int prescriptions) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= prescriptions; n++) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-Isoniazid-" + n, "Isoniazid", "Isoniazid"));
		}
		return ask(orders, "Isoniazid", "Can i give him panadol?");
	}

	private static PatientChart ask(List<PatientClinicalContext.ActiveDrugOrder> orders, String drug, String question) {
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(drug), null, null, null, orders), question);
	}
}
