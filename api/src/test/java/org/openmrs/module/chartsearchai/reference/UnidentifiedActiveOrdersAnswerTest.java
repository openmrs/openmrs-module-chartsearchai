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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The closing line a composed answer carries beside active orders the drug data does not identify (ADR Decision 161),
 * over the shapes the database-backed cases cannot build: several such orders, a display several of them share, and
 * an order recorded only by its codes. On the demo a patient's vaccine and infant-formula orders took every drug
 * proposal away from the module; this is that shape, beside an aspirin order the data identifies.
 */
public class UnidentifiedActiveOrdersAnswerTest extends BaseModuleContextSensitiveTest {

	private static DrugReferenceService shipped;

	private static synchronized DrugReferenceService shipped() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.shippedServiceWithGroups();
		}
		return shipped;
	}

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	/**
	 * Every order of hers is one the data does not identify (ADR Decision 215). On the public demo a patient whose active
	 * orders are her childhood vaccinations was answered by the model for every drug question: "The records do not address
	 * the safety of giving rifampicin", and "Clarithromycin has major interactions with ivosidenib, ketoconazole …" — the
	 * dataset's partners, none of them hers. Decision 158 answers a patient with no orders, Decision 161 one with some
	 * identified; with orders and none identified, neither did.
	 */
	@Test
	public void aProposalForAPatientNoneOfWhoseOrdersTheDataIdentifiesSaysTheCheckHadNoneToRelateItTo() {
		PatientChart chart = askOverVaccinationsOnly("Can I give her rifampicin?");

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "the module answers: " + chart.getText());
		String[] lines = answer.split("\n");
		assertTrue(lines[0].matches("The drug data identifies none of this patient's 4 active medication orders, so the "
				+ "interaction check had none to relate Rifampicin \\(rifampin\\) to\\. \\[\\d+\\]"), "was: " + answer);
		assertEquals("Not checked: 4 active orders the drug data does not identify — Polio vaccination, oral; Measles "
				+ "vaccination; Hepatitis B vaccination (2 orders). Whether one of them is the drug asked about is not "
				+ "established.", lines[1], "was: " + answer);
		assertEquals(2, lines.length, "was: " + answer);
	}

	@Test
	public void aScreenOfAPatientNoneOfWhoseOrdersTheDataIdentifiesSaysThereAreNoneToCheck() {
		PatientChart chart = askOverVaccinationsOnly("Does she have any drug interactions between her current medications?");

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "the module answers: " + chart.getText());
		String[] lines = answer.split("\n");
		assertEquals("The drug data identifies none of this patient's 4 active medication orders, so there are none to "
				+ "check against each other.", lines[0], "was: " + answer);
		assertTrue(lines[1].startsWith("Not checked: 4 active orders the drug data does not identify — Polio vaccination, "
				+ "oral; Measles vaccination; Hepatitis B vaccination (2 orders). "), "was: " + answer);
		assertEquals(2, lines.length, "was: " + answer);
	}

	/**
	 * A recorded drug allergy the data does not identify is said to be unchecked (ADR Decision 223). On the public demo's
	 * data a patient recorded allergic to "Penicillins" — a drug-class concept naming no substance the data carries — was
	 * asked "Can I give her amoxicillin?" and answered "The interaction check relates Amoxicillin to none of this patient's
	 * 3 active medications.", nothing saying her penicillin allergy had not been compared at all.
	 */
	@Test
	public void aRecordedAllergyTheDataDoesNotIdentifyIsSaidToBeUnchecked() {
		// A drug class no curated group carries; "Penicillins", the demo's own case, is compared with the Penicillins group's
		// members since ADR Decision 231.
		String answer = askBesideAnAllergy("Cephalosporins", "Can I give her amoxicillin?").getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Not checked: a recorded allergy the drug data does not identify — cephalosporins. Whether it covers "
				+ "Amoxicillin is not established.", lines[lines.length - 1], "was: " + answer);
	}

	/**
	 * That allergy is said unchecked only after a question its check bears on — whether to give a drug, whether one is
	 * safe, her allergies, a screen of her medications — and not after one it does not (ADR Decision 388). On the 3.7.1
	 * standalone "Not checked: a recorded allergy the drug data does not identify — Dexibuprofen." closed every module
	 * answer for Provenance Probe: "Is she on more than one steroid?", "Can I stop her amlodipine?", "What interacts with
	 * her amlodipine?" among them.
	 */
	@Test
	public void anUncheckedAllergyIsSaidOnlyAfterAQuestionItsCheckBearsOn() {
		String unchecked = "recorded allergy the drug data does not identify";
		for (String question : java.util.Arrays.asList("Can I give her amoxicillin?", "Is amoxicillin safe for her?",
				"Is her nevirapine safe?")) {
			String answer = askBesideAnAllergy("Cephalosporins", question).getModuleAnswer();
			assertNotNull(answer, question + " — the module answers");
			assertTrue(answer.contains(unchecked), question + " — was: " + answer);
		}
		for (String question : java.util.Arrays.asList("Is she on more than one steroid?", "Can I stop her nevirapine?",
				"What interacts with her nevirapine?", "Does amoxicillin interact with her nevirapine?")) {
			String answer = askBesideAnAllergy("Cephalosporins", question).getModuleAnswer();
			assertNotNull(answer, question + " — the module answers");
			assertTrue(!answer.contains(unchecked), question + " — nothing about an allergy it does not ask of: " + answer);
		}
	}

	@Test
	public void aRecordedAllergyTheDataIdentifiesIsNotSaidToBeUnchecked() {
		String answer = askBesideAnAllergy("Aspirin", "Can I give her amoxicillin?").getModuleAnswer();

		assertNotNull(answer, "the module answers");
		assertTrue(!answer.contains("recorded allergy the drug data does not identify"), "was: " + answer);
	}

	private PatientChart askBesideAnAllergy(String allergen, String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-nvp", "Nevirapine", DrugReferenceTestSupport.set("Nevirapine")));
		return DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(35, null, DrugReferenceTestSupport.set("Nevirapine"), DrugReferenceTestSupport.set(),
						DrugReferenceTestSupport.set(allergen), null, orders),
				question);
	}

	private PatientChart askOverVaccinationsOnly(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-polio", "Polio vaccination, oral",
						DrugReferenceTestSupport.set("Polio vaccination, oral")),
				new PatientClinicalContext.ActiveDrugOrder("order-measles", "Measles vaccination",
						DrugReferenceTestSupport.set("Measles vaccination")),
				new PatientClinicalContext.ActiveDrugOrder("order-hepb-1", "Hepatitis B vaccination",
						DrugReferenceTestSupport.set("Hepatitis B vaccination")),
				new PatientClinicalContext.ActiveDrugOrder("order-hepb-2", "Hepatitis B vaccination",
						DrugReferenceTestSupport.set("Hepatitis B vaccination")));
		return DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Polio vaccination, oral", "Measles vaccination", "Hepatitis B vaccination"),
						DrugReferenceTestSupport.set(), null, null, orders),
				question);
	}

	@Test
	public void aProposalBesideSeveralUnidentifiedOrdersNamesEachOnceAndCountsEveryOrder() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-aspirin", "Aspirin", DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("B01AC06")),
				new PatientClinicalContext.ActiveDrugOrder("order-formula-1", "Infant formula",
						DrugReferenceTestSupport.set("Infant formula")),
				new PatientClinicalContext.ActiveDrugOrder("order-formula-2", "Infant formula",
						DrugReferenceTestSupport.set("Infant formula")),
				new PatientClinicalContext.ActiveDrugOrder("order-polio", "Polio vaccination, oral",
						DrugReferenceTestSupport.set("Polio vaccination, oral")),
				PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-coded", "J07BC01",
						DrugReferenceTestSupport.set("J07BC01")));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("B01AC06"), null, null, orders),
				"Can I give her ibuprofen?");

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "the module answers beside the orders it cannot identify: " + chart.getText());
		String[] lines = answer.split("\n");
		assertTrue(lines[0].startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING),
				"ibuprofen beside her aspirin is a reason to withhold it: " + answer);
		// Semicolons between orders: a display can carry a comma of its own, as the demo's "Polio vaccination, oral".
		assertEquals("Not checked: 4 active orders the drug data does not identify — Infant formula (2 orders); "
				+ "Polio vaccination, oral; an order recorded only by its codes. Whether one of them is the drug asked "
				+ "about is not established.", lines[lines.length - 1]);
	}
}
