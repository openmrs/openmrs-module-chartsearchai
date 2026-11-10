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
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether two drugs interact, where the data lists them against each other only below the severity floor, is answered
 * with that listing and neither "Yes" nor "No" (ADR Decision 329). On the 3.7.1 standalone "Do her nevirapine and
 * stavudine interact?" went to the model for every patient: most were told "The records do not address the interaction",
 * Amina Wekesa "Nevirapine and Stavudine do not interact", and Richard Brown each drug's partners from the reference
 * record. The data lists the pair rated Unknown. Over the shipped knowledge base.
 */
public class TwoDrugsListedBelowTheFloorTest extends BaseModuleContextSensitiveTest {

	private static final String LISTED = "The interaction data lists Nevirapine against Stavudine, with no severity or "
			+ "mechanism on file. [n] [n]";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoDrugsListedOnlyBelowTheFloorAreSaidSo() {
		String neither = ask("Do her nevirapine and stavudine interact?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(neither, "the module answers");
		String[] lines = mask(neither).split("\n");
		assertEquals("Neither Nevirapine nor Stavudine is one of this patient's active medications.", lines[0], neither);
		assertEquals(LISTED, lines[1], neither);
		DrugReferenceTestSupport.assertNoScopeLine(neither);

		String both = ask("Do her nevirapine and stavudine interact?",
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));
		assertNotNull(both, "the module answers of her own two");
		lines = mask(both).split("\n");
		assertTrue(lines[0].endsWith(" are both among this patient's active medications."), both);
		assertEquals(LISTED, lines[1], both);
		assertTrue(!both.contains("Yes —") && !both.contains("No —"), "neither Yes nor No: " + both);
	}

	/**
	 * Two drugs proposed together that the data lists against each other only below the floor state that listing between
	 * them, as the question whether they interact does (ADR Decision 576): on the demo replica "Can I give her fluconazole
	 * with her aspirin?" answered each drug against her orders and said nothing of the pair asked about.
	 */
	@Test
	public void twoDrugsProposedTogetherStateTheirListingBetweenThem() {
		PatientClinicalContext.ActiveDrugOrder metronidazole = DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole",
			"Metronidazole");
		for (String question : Arrays.asList("Can I give her nevirapine and stavudine?",
				"Can I give her nevirapine with stavudine?")) {
			String answer = ask(question, metronidazole);
			assertNotNull(answer, question + " — the module answers");
			String[] lines = mask(answer).split("\n");
			assertTrue(lines[0].endsWith(", asked about together; neither is one of this patient's active medications."),
				question + " — " + answer);
			assertEquals("Between them:", lines[1], question + " — " + answer);
			assertEquals(LISTED, lines[2], question + " — the pair, in the words its own question gets: " + answer);
		}
	}

	/**
	 * Two of hers that share a drug class and are related by nothing else are related by that finding, stated once (ADR
	 * Decision 584): on the 8089 rig "Does her abacavir interact with her didanosine?" said the data lists the two with no
	 * severity, then put "Abacavir is in the same ATC class (J05AF) as active order Didanosine" and "Didanosine is in the
	 * same ATC class (J05AF) as active order Abacavir" under "about this patient's other medications" — the pair asked
	 * about, from both sides, as if it were about neither.
	 */
	@Test
	public void aSharedClassBetweenTwoOfHersIsStatedOnceAsTheirs() {
		for (String question : Arrays.asList("Does her abacavir interact with her didanosine?",
				"Is abacavir safe with didanosine?")) {
			String answer = ask(question,
				DrugReferenceTestSupport.activeOrder("order-abc", "Abacavir 300mg", "Abacavir"),
				DrugReferenceTestSupport.activeOrder("order-ddi", "Didanosine 400mg", "Didanosine"));
			assertNotNull(answer, question + " — the module answers");
			int fromAbacavir = count(answer, "Abacavir is in the same ATC class (J05AF) as active order Didanosine");
			int fromDidanosine = count(answer, "Didanosine is in the same ATC class (J05AF) as active order Abacavir");
			assertEquals(1, fromAbacavir + fromDidanosine, question + " — the shared class stated once: " + answer);
			assertTrue(!answer.contains(DrugReferenceInjector.ABOUT_HER_OTHER_MEDICATIONS_LEAD),
				question + " — the pair asked about is not her other medications: " + answer);
		}
	}

	/**
	 * Whether two drugs interact, beside an order the drug data does not identify, is still the module's: what it can say
	 * of the pair and of her orders it identifies, and that order named after it as not checked (ADR Decision 596). On the
	 * demo replica every patient with a vaccination order had "Do her nevirapine and stavudine interact?" answered by the
	 * model, "The records do not address whether nevirapine and stavudine interact."
	 */
	@Test
	public void twoDrugsBesideAnOrderTheDataCannotIdentifyAreStillAnswered() {
		String answer = ask("Do her nevirapine and stavudine interact?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
			DrugReferenceTestSupport.activeOrder("order-vax", "Measles vaccination", "Measles vaccination"));
		assertNotNull(answer, "the module answers");
		String[] lines = mask(answer).split("\n");
		assertEquals("Neither Nevirapine nor Stavudine is one of this patient's active medications.", lines[0], answer);
		assertEquals(LISTED, lines[1], answer);
		assertTrue(lines[lines.length - 1].startsWith("Not checked: 1 active order the drug data does not identify — Measles "
				+ "vaccination."), "the order it could not check, named: " + answer);
	}

	private static int count(String text, String part) {
		int n = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
			n++;
		}
		return n;
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	/**
	 * Two of hers the data lists only below the floor, one of them related by a finding to a third order of hers, is answered
	 * as without that order, and the finding is not stated (ADR Decisions 370, 618 — the owner's direction that an answer
	 * carries nothing the question did not ask): on the 3.7.1 standalone "Does her lamivudine interact with her nevirapine?"
	 * went to the model for Mogan Kamwara, on stavudine too, whose answer ended "Unknown severity interaction (DDInter 2.0; no
	 * mechanism description on file) and."; the module then stated lamivudine against her stavudine apart, which the question
	 * never asked.
	 */
	@Test
	public void aFindingRelatingOneOfHersToAThirdOrderIsNotStated() {
		PatientClinicalContext.ActiveDrugOrder lamivudine = DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine 150mg",
			"Lamivudine");
		PatientClinicalContext.ActiveDrugOrder nevirapine = DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg",
			"Nevirapine");
		for (String question : Arrays.asList("Does her lamivudine interact with her nevirapine?",
			"Can she take lamivudine and nevirapine together?")) {
			String two = ask(question, lamivudine, nevirapine);
			assertNotNull(two, "precondition: answered of the two alone: " + question);
			PatientChart chart = askChart(question, lamivudine, nevirapine,
				DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));
			String answer = chart.getModuleAnswer();
			assertNotNull(answer, "the module answers " + question);
			// nor any chip of it: the chips are the findings the answer states (ADR Decision 620)
			assertNotNull(chart.getFindingsBearingOnTheQuestion(), question + " — the chips follow the answer");
			for (String detail : chart.getFindingsBearingOnTheQuestion()) {
				assertTrue(!detail.contains("Stavudine"), question + " — a chip the answer does not state: " + detail);
			}
			assertEquals(mask(two), mask(answer),
				question + " — as of the two alone: " + answer);
			assertTrue(!answer.contains("Stavudine") && !answer.contains(DrugReferenceInjector.ABOUT_HER_OTHER_MEDICATIONS_LEAD),
				question + " — nothing about her stavudine: " + answer);
		}
	}

	/**
	 * Whether two drugs the data lists only below the floor interact, one of them covered by her allergy, is answered as
	 * without the allergy (ADR Decisions 371, 618): the allergy says nothing of whether the two interact. Asked whether
	 * giving them is safe, it bears on giving that drug at all, and is stated apart as before: on the 3.7.1 standalone
	 * "Does her amoxicillin interact with her clopidogrel?" went to the model for the three patients recorded allergic to a
	 * penicillin, which answered "The records do not address whether Amoxicillin interacts with Clopidogrel".
	 */
	@Test
	public void aContraindicationAboutADrugAskedAboutIsStatedOnlyWhereGivingItIsAsked() {
		PatientClinicalContext.ActiveDrugOrder metformin = DrugReferenceTestSupport.activeOrder("order-met", "Metformin",
			"Metformin");
		for (String interact : Arrays.asList("Does her amoxicillin interact with her clopidogrel?",
			"Will amoxicillin lower her clopidogrel levels?")) {
			String plain = askAllergic(interact, null, metformin);
			assertNotNull(plain, "precondition: answered with no allergy: " + interact);
			PatientChart chart = askAllergicChart(interact, DrugReferenceTestSupport.set("penicillin g"), metformin);
			String answer = chart.getModuleAnswer();
			assertNotNull(answer, "the module answers " + interact);
			assertEquals(mask(plain), mask(answer), interact + " — as without the allergy: " + answer);
			// and no chip either: the answer states no finding, so none bears on it (ADR Decisions 615, 618)
			assertEquals(java.util.Collections.emptySet(), chart.getFindingsBearingOnTheQuestion(),
				interact + " — no finding published beside an answer stating none");
		}
		String together = askAllergic("Is it safe to give amoxicillin and then clopidogrel later?",
			DrugReferenceTestSupport.set("penicillin g"), metformin);
		assertNotNull(together, "the module answers whether giving them is safe");
		String[] lines = mask(together).split("\n");
		int lead = Arrays.asList(lines).indexOf(DrugReferenceInjector.ABOUT_THE_DRUGS_ASKED_ABOUT_LEAD);
		assertTrue(lead > 1 && lines[lead + 1].startsWith("Amoxicillin"), "the allergy finding apart: " + together);
		DrugReferenceTestSupport.assertNoScopeLine(together);
	}

	private static String askAllergic(String question, java.util.Set<String> allergies,
			PatientClinicalContext.ActiveDrugOrder... given) {
		return askAllergicChart(question, allergies, given).getModuleAnswer();
	}

	private static PatientChart askAllergicChart(String question, java.util.Set<String> allergies,
			PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, allergies, null,
				orders), question);
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		return askChart(question, given).getModuleAnswer();
	}

	private static PatientChart askChart(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question);
	}
}
