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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Which of her medications interact with a drug she is not on is answered with the findings relating it to them and
 * neither "Yes" nor "No"; whether anything she takes interacts with it, in its other wordings, is answered as whether it
 * interacts with her medications (ADR Decision 319). On the 3.7.1 standalone "Which of her medications interact with
 * rifampicin?" went to the model for every patient but one: Richard Brown, on salicylic acid, was told "The medications
 * that interact with Rifampicin are ivosidenib, ixazomib, ketoconazole, larotrectinib, and lefamulin" — none of them his.
 * Over the shipped knowledge base.
 */
public class WhichOfHerMedicationsInteractTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	private static final List<List<PatientClinicalContext.ActiveDrugOrder>> FIXTURES = Arrays.asList(
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine 150mg", "Lamivudine")),
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole")),
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-sal", "Salicylic acid", "Salicylic acid")));

	@Test
	public void whichOfHerMedicationsInteractIsAnsweredWithTheFindingsAndNeitherYesNorNo() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : FIXTURES) {
			String interacts = ask("Does rifampicin interact with her medications?", orders);
			assertNotNull(interacts, orders.get(0).getDisplay() + " — precondition: the module answers whether it interacts");
			// The findings as whether it interacts states them, without its "Yes —" line where it opens with one.
			String findings = interacts.startsWith("Yes — ") ? interacts.substring(interacts.indexOf('\n') + 1) : interacts;
			for (String question : Arrays.asList("Which of her medications interact with rifampicin?",
					"Which of her drugs interact with rifampicin?", "Which of her drugs is the problem with rifampicin?")) {
				String answer = ask(question, orders);
				assertNotNull(answer, question + " / " + orders.get(0).getDisplay() + " — the module answers");
				assertTrue(!answer.startsWith("Yes —") && !answer.startsWith("No —"), question + " was: " + answer);
				assertTrue(mask(answer).endsWith(mask(findings)), question + " / " + orders.get(0).getDisplay()
						+ " — the findings: " + answer + "\n  expected to end with: " + findings);
			}
		}
	}

	@Test
	public void whetherAnythingSheTakesInteractsIsAnsweredAsWhetherItInteractsWithHerMedications() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : FIXTURES) {
			String interacts = ask("Does rifampicin interact with her medications?", orders);
			for (String question : Arrays.asList("Is there anything in her med list that interacts with rifampicin?",
					"Is she on any drugs that interact with rifampicin?", "Does rifampicin interact with anything she takes?",
					"Does she have any interactions with rifampicin?")) {
				assertEquals(mask(interacts), mask(ask(question, orders)), question + " / " + orders.get(0).getDisplay());
			}
		}
	}

	/**
	 * Where the data relates the drug to her orders only below the severity floor, or she has no active order, the module
	 * says so — which of her orders the data lists it against, and how rated — and neither "Yes" nor "No" (ADR Decision
	 * 320). On the 3.7.1 standalone David Williams, on aspirin, was told "Aspirin has an Unknown severity interaction and
	 * ivosidenib has a Major interaction", and patients on no medication the reference record's partners.
	 */
	@Test
	public void rowsBelowTheFloorOrNoOrderAreStatedForEveryWording() {
		List<PatientClinicalContext.ActiveDrugOrder> aspirin = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		for (String question : Arrays.asList("Which of her medications interact with rifampicin?",
				"Does rifampicin interact with her medications?", "Is she on any drugs that interact with rifampicin?")) {
			assertEquals("The interaction data lists Rifampicin (rifampin) against one of this patient's orders, Aspirin 81mg, "
					+ "with no severity or mechanism on file. [n]\n" + DrugReferenceInjector.BELOW_FLOOR_SCOPE,
				mask(ask(question, aspirin)), question);
			assertEquals("This patient has no active medication orders, so the interaction check had none to relate Rifampicin "
					+ "(rifampin) to. [n]", mask(ask(question, Arrays.<PatientClinicalContext.ActiveDrugOrder> asList())),
				question + " / no orders");
		}
	}

	/**
	 * "Any interactions with his lisinopril?" asks what "Does lisinopril interact with his medications?" asks (ADR Decision
	 * 338): on the 3.7.1 standalone it fitted no shape, so wherever the data listed lisinopril against his orders only
	 * below the severity floor, or he had no order, the model answered — telling Susan Hall, on no medication, "Yes —
	 * interactions with Lisinopril are recorded: ketoconazole (Major); leflunomide (Major) …", drugs she does not take.
	 */
	@Test
	public void anyInteractionsWithTheDrugIsAnsweredAsWhetherItInteracts() {
		List<PatientClinicalContext.ActiveDrugOrder> aspirin = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		List<PatientClinicalContext.ActiveDrugOrder> none = Arrays.<PatientClinicalContext.ActiveDrugOrder> asList();
		String belowFloor = mask(ask("Does rifampicin interact with her medications?", aspirin));
		String noOrders = mask(ask("Does rifampicin interact with her medications?", none));
		assertNotNull(belowFloor, "precondition");
		assertNotNull(noOrders, "precondition");
		for (String question : Arrays.asList("Any interactions with rifampicin?", "Any drug interactions with rifampicin?",
				"Are there any interactions with rifampicin?")) {
			assertEquals(belowFloor, mask(ask(question, aspirin)), question);
			assertEquals(noOrders, mask(ask(question, none)), question + " / no orders");
		}
		// Named as his, it is answered as "Does his rifampicin interact with his medications?" is: said not to be his.
		String hisBelowFloor = mask(ask("Does his rifampicin interact with his medications?", aspirin));
		assertEquals("Rifampicin (rifampin)" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n" + belowFloor, hisBelowFloor,
			"precondition");
		String hisNoOrders = mask(ask("Does his rifampicin interact with his medications?", none));
		for (String question : Arrays.asList("Any interactions with his rifampicin?",
				"Are there any interactions with her rifampicin?")) {
			assertEquals(hisBelowFloor, mask(ask(question, aspirin)), question);
			assertEquals(hisNoOrders, mask(ask(question, none)), question + " / no orders");
		}
	}

	/**
	 * "Any problems with her metronidazole?" is answered as "Does her metronidazole interact with her medications?" is
	 * (ADR Decision 339): the drug named as hers fitted Decision 237's shape, which reached only an answer from a finding,
	 * so wherever the data listed it only below the severity floor, or she had no order, the model answered — Provenance
	 * Probe, on no medication, was told "Metronidazole has several major interactions: … warfarin, … busulfan".
	 */
	@Test
	public void anyProblemsWithTheDrugIsAnsweredAsWhetherItInteracts() {
		List<PatientClinicalContext.ActiveDrugOrder> aspirin = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		List<PatientClinicalContext.ActiveDrugOrder> none = Arrays.<PatientClinicalContext.ActiveDrugOrder> asList();
		String hisBelowFloor = mask(ask("Does his rifampicin interact with his medications?", aspirin));
		String hisNoOrders = mask(ask("Does his rifampicin interact with his medications?", none));
		for (String question : Arrays.asList("Any problems with his rifampicin?", "Are there any issues with her rifampicin?",
				"Any concerns with her rifampicin?")) {
			assertEquals(hisBelowFloor, mask(ask(question, aspirin)), question);
			assertEquals(hisNoOrders, mask(ask(question, none)), question + " / no orders");
		}
		String belowFloor = mask(ask("Does rifampicin interact with her medications?", aspirin));
		assertEquals(belowFloor, mask(ask("Any problems with rifampicin?", aspirin)), "Any problems with rifampicin?");
	}

	/**
	 * A drug's name asked alone is asked whether it interacts with her medications (ADR Decision 349): on the 3.7.1
	 * standalone "Fluconazole?" fitted no shape, and the model told Mogan Kamwara "Fluconazole can be given, with one
	 * caution", a clearance, and David Williams "Fluconazole is a drug referenced in the chart".
	 */
	@Test
	public void aDrugsNameAloneIsAskedWhetherItInteracts() {
		List<List<PatientClinicalContext.ActiveDrugOrder>> patients = Arrays.asList(
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin")),
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin"),
				DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")),
			Arrays.<PatientClinicalContext.ActiveDrugOrder> asList());
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : patients) {
			String expected = mask(ask("Does rifampicin interact with her medications?", orders));
			assertNotNull(expected, "precondition: the module answers " + orders);
			for (String question : Arrays.asList("Rifampicin?", "rifampicin", "Rifampicin ?")) {
				assertEquals(expected, mask(ask(question, orders)), question + " / " + orders);
			}
		}
	}

	/**
	 * Whether she is on a drug she is not on, with no rated finding relating it to her orders, says she is not and what the
	 * check found (ADR Decision 365): on the 3.7.1 standalone "Is she on lithium?" went to the model wherever no such finding
	 * was raised, which told Mogan Kamwara and seven others "The records do not address Lithium" — though her orders, read,
	 * hold none.
	 */
	@Test
	public void whetherSheIsOnADrugSheIsNotOnSaysSoWhateverTheCheckFound() {
		List<PatientClinicalContext.ActiveDrugOrder> aspirin = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		List<PatientClinicalContext.ActiveDrugOrder> none = Arrays.<PatientClinicalContext.ActiveDrugOrder> asList();
		for (String question : Arrays.asList("Is she on rifampicin?", "Is the patient on rifampicin?",
				"Is she taking rifampicin?", "Is rifampicin one of her medications?")) {
			assertEquals("Rifampicin (rifampin)" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n"
					+ mask(ask("Does rifampicin interact with her medications?", aspirin)), mask(ask(question, aspirin)), question);
			assertEquals(mask(ask("Does rifampicin interact with her medications?", none)), mask(ask(question, none)),
				question + " / no orders");
		}
	}

	/**
	 * What interacts with her drug, asked of one she is not on, says she is not, then which of her medications interact
	 * with it (ADR Decision 369): on the 3.7.1 standalone "What interacts with her lamivudine?" went to the model for every
	 * patient not on lamivudine, which told Susan Hall, on no medication, "Lamivudine interacts with ketoconazole (Major),
	 * leflunomide (Major) …" — drugs she does not take.
	 */
	@Test
	public void whatInteractsWithHerDrugSheIsNotOnSaysSoAndWhichOfHersInteract() {
		List<PatientClinicalContext.ActiveDrugOrder> aspirin = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		List<PatientClinicalContext.ActiveDrugOrder> nevirapine = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"));
		List<PatientClinicalContext.ActiveDrugOrder> none = Arrays.<PatientClinicalContext.ActiveDrugOrder> asList();
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : Arrays.asList(aspirin, nevirapine)) {
			String which = ask("Which of her medications interact with rifampicin?", orders);
			assertNotNull(which, "precondition: " + orders);
			assertEquals("Rifampicin (rifampin)" + DrugReferenceInjector.NOT_HER_MEDICATION + "\n" + mask(which),
				mask(ask("What interacts with her rifampicin?", orders)), String.valueOf(orders));
		}
		assertEquals(mask(ask("Which of her medications interact with rifampicin?", none)),
			mask(ask("What interacts with her rifampicin?", none)), "no orders");
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static String ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
