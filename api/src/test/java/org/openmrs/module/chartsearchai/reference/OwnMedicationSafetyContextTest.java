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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question whether a drug she already takes is safe is answered by the module: that the drug is one of her active
 * medications, then its findings counted and stated, then the scope — never a verdict (ADR Decision 193). Measured on
 * the 3.7.1 standalone: Sarah Taylor's "Is ibuprofen safe for her?" went to the model, which answered no question,
 * said ibuprofen "should be changed", cited heparin by her Advil record and wrote "adding it would duplicate that order"
 * of a drug she already takes. Over the slice of {@link ScreenSharedMechanismContextTest}.
 */
public class OwnMedicationSafetyContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-screen-shared-mechanism.json";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aSafetyQuestionAboutHerOwnMedicationIsAnsweredWithItsFindings() throws IOException {
		String answer = answer("Is ibuprofen safe for her?");

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(4, lines.length, "the lead, two lines and the scope: " + answer);
		assertTrue(lines[0].matches("Ibuprofen is one of this patient's active medications \\(Ibuprofen 400mg \\[\\d+\\]\\)\\. "
				+ "3 interactions with this patient's other active medications:"), "was: " + answer);
		assertTrue(lines[1].matches("Ibuprofen interacts with active order Prednisone and Dexamethasone — Moderate\\. \\[\\d+\\].*"),
			"was: " + answer);
		assertTrue(lines[2].matches("Ibuprofen interacts with active order Celecoxib — Moderate\\. \\[\\d+\\].*"), "was: " + answer);
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[3]);
		assertTrue(!answer.contains("adding it") && !answer.contains("withhold") && !answer.contains("safe"),
			"no proposal wording and no verdict: " + answer);
	}

	@Test
	public void aRecordedAllergyToHerOwnDrugIsStatedWithItsInteractions() throws IOException {
		String answer = answer("Is ibuprofen safe for her?", DrugReferenceTestSupport.set("ibuprofen"), ALL_FOUR);

		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].endsWith("3 interactions with this patient's other active medications, and 1 "
				+ "unrated contraindication:"), "was: " + answer);
		// The lead has said it is her medication, so the line does not say it again.
		assertTrue(answer.matches("(?s).*\nThe patient has a recorded allergy to Ibuprofen\\. \\[\\d+\\]\n.*"),
			"was: " + answer);
		assertTrue(answer.endsWith("\n" + DrugReferenceInjector.SCREEN_SCOPE), "it covers contraindications: " + answer);
	}

	@Test
	public void anUnratedLineSaysNothingTheCountDoesNotSayOnce() throws IOException {
		// Her dexamethasone shares an ATC class with her prednisone — a duplicate-therapy finding with no rating. The
		// count says how many lines are unrated, once, and the line does not repeat it (ADR Decision 194).
		String answer = answer("Is dexamethasone safe for her?");

		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].endsWith(" interactions with this patient's other active medications, 1 of them "
				+ "with no severity rated:"), "was: " + answer);
		assertTrue(answer.contains("possible duplicate therapy. ["), "the class line ends with its marker: " + answer);
		assertTrue(!answer.contains("No severity is rated"), "and no line repeats it: " + answer);
	}

	@Test
	public void aRecordedAllergyToHerOwnDrugIsStatedEvenWithNoInteraction() throws IOException {
		// Susan Young takes tiotropium and is recorded allergic to it; nothing relates it to her other orders above the
		// floor, so Decision 193 left it to the model, which wrote "Tiotropium is already in active order [4]. Adding it
		// would duplicate that order" (ADR Decision 208). The allergy is a finding the module states — never "0
		// interactions", which would read as a clearance.
		String answer = answer("Is ibuprofen safe for her?", DrugReferenceTestSupport.set("ibuprofen"),
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg")));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertTrue(lines[0].matches("Ibuprofen is one of this patient's active medications \\(Ibuprofen 400mg \\[\\d+\\]\\)\\. "
				+ "1 contraindication, with no severity rated:"), "was: " + answer);
		assertTrue(lines[1].matches("The patient has a recorded allergy to Ibuprofen\\. \\[\\d+\\]"), "was: " + answer);
		assertEquals(DrugReferenceInjector.SCREEN_SCOPE, lines[2], "was: " + answer);
		assertEquals(3, lines.length, "was: " + answer);
	}

	@Test
	public void herOwnDrugWithNoOtherMedicationSaysThereIsNoneToCheckItAgainst() throws IOException {
		// No finding relates it to another of her orders, for she has none, and the module states findings, never a
		// clearance — so it says what the check could not do (ADR Decision 316, superseding Decision 193's hand-off to the
		// model). On the 3.7.1 standalone the model answered Mark Williams's "Can I give her ondansetron?", ondansetron her
		// one order, "The drug can be given, with one caution".
		for (String question : Arrays.asList("Is ibuprofen safe for her?", "Can I give her ibuprofen?", "Is her ibuprofen safe?")) {
			String answer = answer(question, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg")));
			assertNotNull(answer, question + " — the module answers");
			assertTrue(answer.matches("Ibuprofen is one of this patient's active medications \\(Ibuprofen 400mg \\[\\d+\\]\\), and "
					+ "this patient has no other active medication to check it against\\."), question + " was: " + answer);
		}
	}

	@Test
	public void aQuestionWhetherSheIsOnHerOwnDrugIsAnsweredThatItIsWithItsFindings() throws IOException {
		// Measured on the 3.7.1 standalone (ADR Decision 202): Sarah Taylor's "Is she on prednisone?" was answered "No,
		// the patient is not currently on Prednisone" by the model, beside seven findings each calling prednisone a
		// medication she already takes. Her orders answer the question; the module states it with the order cited.
		for (String question : Arrays.asList("Is she on ibuprofen?", "Is she taking ibuprofen?", "Does she take ibuprofen?",
			"Is the patient currently on ibuprofen?", "Is ibuprofen one of her medications?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertTrue(lines[0].matches("Ibuprofen is one of this patient's active medications \\(Ibuprofen 400mg \\[\\d+\\]\\)\\. "
					+ "3 interactions with this patient's other active medications:"), question + " was: " + answer);
			assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], question + " was: " + answer);
		}
	}

	@Test
	public void aQuestionWhetherSheIsOnADrugNoneOfHerOrdersCarriesIsNotAnsweredAsHers() throws IOException {
		// The statement rests on her orders: a drug none of them carries is not called hers. It is said not to be, then
		// what the check found about it, and no verdict (ADR Decision 287): left to the model, "Is she on efavirenz?" was
		// answered "The drug can be given, with two cautions".
		String answer = answer("Is she on celecoxib?", null,
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
				DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg")));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Celecoxib is not one of this patient's active medications.", lines[0], "was: " + answer);
		assertEquals("2 interaction cautions for Celecoxib:", lines[1], "was: " + answer);
		assertTrue(!answer.contains("can be given") && !answer.contains("No —") && !answer.contains("Yes —"),
			"no verdict: " + answer);
	}

	/**
	 * Over the shipped knowledge base, the two drugs the 3.7.1 standalone's Mogan Kamwara — on lamivudine, nevirapine and
	 * stavudine — was asked about and is not on: the model answered "Is she on efavirenz?" with "The drug can be given,
	 * with two cautions" and "Is she taking tenofovir?" with "Tenofovir can be given, with several cautions" (ADR Decision
	 * 287).
	 */
	@Test
	public void aQuestionWhetherSheIsOnADrugSheIsNotOnSaysSoBeforeWhatTheCheckFound() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lamivudine", "Lamivudine 150mg"),
			DrugReferenceTestSupport.activeOrder("order-nevirapine", "Nevirapine 200mg"),
			DrugReferenceTestSupport.activeOrder("order-stavudine", "Stavudine 30mg"));

		// Efavirenz and nevirapine share J05AG, the non-nucleoside reverse transcriptase inhibitors, folded onto a Moderate
		// rule: a reason to withhold no rating makes, so the lead states what the check found (ADR Decisions 257, 283).
		String efavirenz = answerOverTheShippedData("Is she on efavirenz?", orders);
		assertNotNull(efavirenz, "the module answers");
		assertTrue(efavirenz.startsWith("Efavirenz is not one of this patient's active medications.\n"
				+ DrugReferenceInjector.FOUND_LEAD_OPENING + "Efavirenz:\n"
				+ "Efavirenz interacts with active order Nevirapine — Moderate. Efavirenz is in the same ATC class (J05AG) as "
				+ "active order Nevirapine — possible duplicate therapy. ["), "was: " + efavirenz);

		String tenofovir = answerOverTheShippedData("Is she taking tenofovir?", orders);
		assertNotNull(tenofovir, "the module answers");
		assertTrue(tenofovir.startsWith("Tenofovir is not one of this patient's active medications.\n"
				+ "4 interaction cautions for Tenofovir:\n"), "was: " + tenofovir);
		assertTrue(!efavirenz.contains("can be given") && !tenofovir.contains("can be given"), "no clearance");
	}

	@Test
	public void aQuestionWhetherSheIsOnADrugSheIsOnlyScheduledToStartSaysWhenItStarts() throws IOException {
		// Dora's "Is she on rifampicin?" opened "Rifampicin (rifampin) is one of this patient's active medications." of an
		// order scheduled to start 2026-10-28, its own line saying so (ADR Decision 202): a drug none of her orders has
		// started is not called one of her active medications.
		// At UTC midnight, the zone every date this module publishes is spelled in (DateFormatUtil).
		java.util.Date start = java.util.Date.from(java.time.LocalDate.of(2026, 10, 28).atStartOfDay(java.time.ZoneOffset.UTC)
				.toInstant());
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			PatientClinicalContext.ActiveDrugOrder.named("order-ibuprofen", "Ibuprofen 400mg",
				new LinkedHashSet<String>(Arrays.asList("Ibuprofen 400mg")), java.util.Collections.<String> emptySet(),
				java.util.Collections.<String> emptySet(), null, start),
			DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"),
			DrugReferenceTestSupport.activeOrder("order-dexamethasone", "Dexamethasone 8mg"));

		String answer = answer("Is she on ibuprofen?", null, orders);

		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].matches("Ibuprofen is not yet one of this patient's active medications: "
				+ "Ibuprofen 400mg \\[\\d+\\] is scheduled to start 2026-10-28\\. .*"), "was: " + answer);
		assertTrue(!answer.contains("Ibuprofen is one of this patient's active medications"), "was: " + answer);
	}

	@Test
	public void aQuestionWhetherHerOwnDrugIsSafeWithHerOtherMedicationsIsAnsweredAsHers() throws IOException {
		// Susan Young's "Is lidocaine safe with her other medications?" — her own lidocaine, which she is also recorded
		// allergic to — went to the model, which answered "No — Lidocaine should not be given" and left the allergy out.
		String answer = answer("Is ibuprofen safe with her other medications?", DrugReferenceTestSupport.set("ibuprofen"),
			ALL_FOUR);

		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Ibuprofen is one of this patient's active medications (Ibuprofen 400mg ["), "was: " + answer);
		assertTrue(answer.matches("(?s).*\nThe patient has a recorded allergy to Ibuprofen\\. \\[\\d+\\]\n.*"),
			"the allergy is stated: " + answer);
	}

	@Test
	public void anInteractionQuestionAboutHerOwnDrugNamesTheOrderItIs() throws IOException {
		// Susan Young's "Does metoclopramide interact with her meds?" opened "Metoclopramide is one of this patient's active
		// medications." and named no order: the lead's order came only from a proposal's finding (ADR Decision 202).
		// The shape of Rhoda's "Which of her medications interact with lopinavir/ritonavir?", which the model answered with
		// none of the severities. Hers is a combination, which this path does not take (ADR Decision 202), so the case asks
		// it of a single-substance drug.
		for (String question : Arrays.asList("Does ibuprofen interact with her medications?",
			"Does ibuprofen interact with her other medications?", "Which of her medications interact with ibuprofen?",
			"Which of her other medications interact with ibuprofen?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.startsWith("Ibuprofen is one of this patient's active medications (Ibuprofen 400mg ["),
				question + " was: " + answer);
		}
	}

	@Test
	public void herOwnDrugRelatedToHerOtherOrdersOnlyBelowTheFloorIsAnsweredAsHers() throws IOException {
		// Mogan Kamwara's "Is nevirapine safe for him?" — his own nevirapine, which the data lists against his lamivudine
		// and stavudine with no rating — went to the model, which answered "Adding it would duplicate that order …
		// Nevirapine is a caution about a medication this patient is already taking"; under another wording "Nevirapine
		// can be given" (ADR Decision 204). With the floor at Major, her ibuprofen's Moderate pair is below it.
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));
		for (String question : Arrays.asList("Is ibuprofen safe for her?", "Is she on ibuprofen?",
			"Is ibuprofen safe with her other medications?")) {
			String answer = answer(question, null, orders);

			assertEquals("Ibuprofen is one of this patient's active medications (Ibuprofen 400mg [2]). The interaction data "
					+ "gives no rated reason to change it: it lists Ibuprofen against one of this patient's other orders, rated "
					+ "Moderate, below the level this module reports as a finding. [4]\n" + DrugReferenceInjector.BELOW_FLOOR_SCOPE,
				answer, question);
		}
	}

	@Test
	public void herOwnDrugRelatedOnlyBelowTheFloorIsAnsweredAsHersBesideAnotherOfHerDrugsInTwoOrders() throws IOException {
		// On the public demo's data every drug is re-ordered at each visit and the old order never stopped, so a patient's
		// other drugs are each in several of her orders. Mogan Kamwara's "Which of his medications interact with
		// nevirapine?" then went to the model, which answered "Nevirapine interacts with lamivudine and stavudine" — the
		// data rates neither pair — because the finding that his lamivudine is in three of his orders stopped Decision
		// 204's answer, as it had stopped a proposal's before Decision 159 (ADR Decision 212). Here her prednisone is in two
		// of her orders.
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");
		// The demo's shape is the asked drug re-ordered too: the drug-in-play arm's own finding that IT is in several of
		// her orders reached this gate as well, and stopped it just the same.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen-1", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-ibuprofen-2", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone-1", "Prednisone 5mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone-2", "Prednisone 5mg"));
		for (String question : Arrays.asList("Which of her medications interact with ibuprofen?", "Is ibuprofen safe for her?",
			"Is she on ibuprofen?")) {
			String answer = answer(question, null, orders);

			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertTrue(lines[0].matches("Ibuprofen is one of this patient's active medications \\(Ibuprofen 400mg, 2 orders\\)\\. "
					+ "The interaction data gives no rated reason to change it: it lists Ibuprofen against .* of this patient's "
					+ "other orders, rated Moderate, below the level this module reports as a finding\\. \\[\\d+\\]"),
				question + " was: " + answer);
			// Its own two orders are stated once: by the lead ("2 orders"), and as a line where the question did not propose it.
			// Her prednisone in two orders is about prednisone, which the question does not name (ADR Decision 387).
			assertTrue(!answer.contains("Prednisone is in active orders"), question + " was: " + answer);
			assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], question + " was: " + answer);
		}
	}

	@Test
	public void aBelowFloorPartnerInsideTheDrugsOwnCombinationOrderIsNotCalledAnotherOrder() throws IOException {
		// The demo's "Which of his medications interact with zidovudine?" — his zidovudine inside a Lamivudine / zidovudine
		// order — read "it lists Zidovudine against 2 of this patient's other orders", one of them that very order (ADR
		// Decision 212).
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY, "major");
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(DrugReferenceTestSupport.activeOrder(
			"order-combination", "Ibuprofen and celecoxib", "Ibuprofen", "Celecoxib"));

		String answer = answer("Which of her medications interact with ibuprofen?", null, orders);

		assertNotNull(answer, "the module answers");
		assertTrue(answer.split("\n")[0].contains("it lists Ibuprofen against one of this patient's orders, rated Moderate"),
			"was: " + answer);
	}

	@Test
	public void herOwnDrugTheCheckRelatesToNoneOfHerOtherOrdersIsAnsweredAsHersAndItsSecondOrderIsNoInteraction()
			throws IOException {
		// James Thompson's "Is metronidazole safe with his other medications?" — two orders of his own metronidazole —
		// opened "1 interaction with this patient's other active medications" for the finding that the drug is in two of
		// his orders, which relates it to no OTHER medication; and the model answered his own chloroquine "Adding it would
		// duplicate that order" (ADR Decision 206). Metformin here is listed against neither of her other orders.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-metformin-1", "Metformin 500mg"),
			DrugReferenceTestSupport.activeOrder("order-metformin-2", "Metformin 500mg"),
			DrugReferenceTestSupport.activeOrder("order-atorvastatin", "Atorvastatin 10mg"));
		for (String question : Arrays.asList("Is metformin safe with her other medications?", "Is she on metformin?")) {
			String answer = answerOver("chartsearchai-test/ddi-folded-caution-order.json", question, orders);

			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertTrue(lines[0].matches("Metformin is one of this patient's active medications \\(Metformin 500mg, 2 orders\\)\\. "
					+ "The interaction check relates it to none of this patient's 1 other active medication\\. \\[\\d+\\]"),
				question + " was: " + answer);
			assertTrue(lines[1].matches("Metformin is (?:already )?in active orders Metformin 500mg \\(2 orders\\) — possible "
					+ "duplicate therapy\\..*\\[\\d+\\]"), question + " was: " + answer);
			assertEquals(2, lines.length, question + " was: " + answer);
			assertTrue(!answer.contains("interaction with this patient's other"), "no interaction is counted: " + answer);
		}
	}

	@Test
	public void theFindingThatHerOrdersCarryTheDrugTwiceIsNeitherAnInteractionNorAnUnratedOne() throws IOException {
		// The first cut of ADR Decision 206 took the finding that the drug is in two of her orders out of the interaction
		// count and left it in the unrated count, so two Moderate lines were introduced "none with a severity rated". On
		// the rig it also swallowed rated interactions whose partner order carries the drug too (Rose Rifadupla's
		// rifampicin against her other TB combination, "— Major"): only an UNRATED finding naming no partner substance is
		// her orders sharing the drug.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-combination", "Ibuprofen and celecoxib", "Ibuprofen", "Celecoxib"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));

		String answer = answer("Is ibuprofen safe with her other medications?", null, orders);

		assertNotNull(answer, "the module answers");
		assertEquals("Ibuprofen is one of this patient's active medications (Ibuprofen 400mg [2] and Ibuprofen and celecoxib "
				+ "[3]). 2 interactions with this patient's other active medications, and its substance in more than one of "
				+ "this patient's orders:", answer.split("\n")[0], "was: " + answer);
	}

	@Test
	public void anyInteractionsOrProblemsWithHerOwnDrugIsAnsweredAsHers() throws IOException {
		// Karen Sanchez's "Any problems with her metronidazole?" and Joshua Johnson's "Any interactions with his
		// lisinopril?" — each patient's own drug — went to the model, which recited the reference's partners neither
		// patient takes: "Metronidazole has several major interactions: salmonella typhi ty21a live antigen, … warfarin,
		// bcg …" (ADR Decision 237).
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));
		String expected = answer("Is ibuprofen safe with her other medications?", null, orders);
		assertNotNull(expected, "precondition: the module answers the shape Decision 202 admits");
		for (String question : Arrays.asList("Any interactions with her ibuprofen?", "Are there any drug interactions with "
				+ "her ibuprofen?", "Any problems with her ibuprofen?", "Are there any issues with her ibuprofen?")) {
			assertEquals(expected, answer(question, null, orders), question);
		}
	}

	@Test
	public void whetherHerOwnDrugIsSafeOrWhatInteractsWithItIsAnsweredAsHers() throws IOException {
		// ADR Decision 301: asked of forty patients on the 3.7.1 standalone, "Is her X safe?" went to the model, which told
		// Donald Thompson "No — Amiodarone should not be given" of his own amiodarone and two patients "Lamivudine can be
		// given" of theirs; "What interacts with her X?" listed five partners from the reference she does not take.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));
		String expected = answer("Is ibuprofen safe with her other medications?", null, orders);
		assertNotNull(expected, "precondition: the module answers the shape Decision 202 admits");
		for (String question : Arrays.asList("Is her ibuprofen safe?", "Is her ibuprofen safe for her?",
			"Is her ibuprofen safe to continue?", "What interacts with her ibuprofen?",
			"What drugs interact with her ibuprofen?")) {
			assertEquals(expected, answer(question, null, orders), question);
		}
	}

	@Test
	public void whetherToHoldOrStopHerOwnDrugIsAnsweredAsHers() throws IOException {
		// Carol Brown's "Should I hold her metformin?" was answered by the model "Metformin can be given, with two
		// cautions": her own drug, read as one proposed (ADR Decision 259).
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"));
		String expected = answer("Is ibuprofen safe with her other medications?", null, orders);
		assertNotNull(expected, "precondition: the module answers the shape Decision 202 admits");
		for (String question : Arrays.asList("Should I hold her ibuprofen?", "Should I stop her ibuprofen?",
			"Do I need to stop her ibuprofen?")) {
			assertEquals(expected, answer(question, null, orders), question);
		}
	}

	@Test
	public void herOwnDrugWithNoOtherMedicationToCheckItAgainstSaysSo() throws IOException {
		// James Thompson holds two orders of metronidazole and nothing else: there is no other medication to relate it
		// to, as Decision 178 says of one order on a screen (ADR Decision 206).
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-metformin-1", "Metformin 500mg"),
			DrugReferenceTestSupport.activeOrder("order-metformin-2", "Metformin 500mg"));

		String answer = answerOver("chartsearchai-test/ddi-folded-caution-order.json",
			"Is metformin safe with her other medications?", orders);

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Metformin is one of this patient's active medications (Metformin 500mg, 2 orders), and this patient has "
				+ "no other active medication to check it against.", lines[0], "was: " + answer);
		assertTrue(lines.length == 2 && lines[1].matches("Metformin is (?:already )?in active orders Metformin 500mg \\(2 orders\\) "
				+ "— possible duplicate therapy\\..*\\[\\d+\\]"), "was: " + answer);
	}

	@Test
	public void twoOfHerOwnDrugsAskedAboutTogetherAreAnsweredWithWhatRelatesThem() throws IOException {
		// Sarah Taylor's "Is it safe to combine ibuprofen and celecoxib?" — both hers — was answered "Ibuprofen can be
		// combined with Celecoxib, with one caution" by the model, and "Does ibuprofen interact with celecoxib?" "Ibuprofen
		// can be given with Celecoxib": a clearance of two NSAIDs she already takes, which the module never gives (ADR
		// Decision 203). The module says both are hers and states what relates them.
		for (String question : Arrays.asList("Can ibuprofen and celecoxib be given together?",
			"Is it safe to combine ibuprofen and celecoxib?", "Does ibuprofen interact with celecoxib?",
			"Is ibuprofen safe with celecoxib?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			String[] lines = answer.split("\n");
			assertTrue(lines[0].matches("Ibuprofen \\(Ibuprofen 400mg \\[\\d+\\]\\) and Celecoxib \\(Celecoxib 200mg \\[\\d+\\]\\) "
					+ "are both among this patient's active medications\\. What the interaction check relates between them:"),
				question + " was: " + answer);
			assertTrue(lines[1].matches("Ibuprofen interacts with active order Celecoxib — Moderate\\. \\[\\d+\\].*"),
				question + " was: " + answer);
			// The point a clinician needs: two NSAIDs (ADR Decision 197's group, which carries celecoxib for this alone).
			assertTrue(lines[2].matches("Ibuprofen and Celecoxib are in one drug group \\(NSAID\\) — possible duplicate therapy\\."
					+ " \\[\\d+\\] \\[\\d+\\]"), question + " was: " + answer);
			assertEquals(4, lines.length, question + " was: " + answer);
			assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], question + " was: " + answer);
			assertTrue(!answer.contains("can be") && !answer.contains("Celecoxib interacts with active order Ibuprofen"),
				"no clearance, and the pair once: " + answer);
		}
	}

	@Test
	public void twoOfHerOwnDrugsNamedAsHersAreAnsweredWithWhatRelatesThem() throws IOException {
		// Helen Roberts' "Does her methotrexate interact with her salicylic acid?" — both hers — was answered "No —
		// Methotrexate and Salicylic acid interact" by the model: a "her" before either drug fitted none of Decision 203's
		// shapes, so the model wrote a "No" over a Major interaction (ADR Decision 233).
		for (String question : Arrays.asList("Does her ibuprofen interact with her celecoxib?",
			"Is there an interaction between her ibuprofen and celecoxib?", "Is her ibuprofen safe with her celecoxib?",
			"Do her ibuprofen and celecoxib interact?", "Do ibuprofen and celecoxib interact with each other?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.split("\n")[0].matches("Ibuprofen \\(Ibuprofen 400mg \\[\\d+\\]\\) and Celecoxib \\(Celecoxib 200mg "
					+ "\\[\\d+\\]\\) are both among this patient's active medications\\. What the interaction check relates "
					+ "between them:"), question + " was: " + answer);
		}
	}

	@Test
	public void twoOfHerOwnDrugsAskedAboutAsWhetherSheCanHaveThemTogetherAreAnsweredAsHers() throws IOException {
		// Sarah Taylor's "Can she have prednisone and ibuprofen together?" — both hers — was answered "Prednisone can be
		// given with Ibuprofen, with one caution" by the model: Decision 203's shape took "take" and not "have" (ADR
		// Decision 209).
		for (String question : Arrays.asList("Can she have ibuprofen and celecoxib together?",
			"Can she be on ibuprofen and celecoxib together?", "Can the patient use ibuprofen with celecoxib?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.split("\n")[0].matches("Ibuprofen \\(Ibuprofen 400mg \\[\\d+\\]\\) and Celecoxib \\(Celecoxib 200mg "
					+ "\\[\\d+\\]\\) are both among this patient's active medications\\. What the interaction check relates "
					+ "between them:"), question + " was: " + answer);
		}
	}

	@Test
	public void aQuestionWhetherHerOwnDrugIsCausingInteractionsIsAnsweredAsHers() throws IOException {
		// Rose Rifadupla's "Is rifampicin causing any interactions with her other drugs?" — her own rifampicin — was
		// answered "No — Rifampicin should not be given" by the model, a proposal's call on a drug she takes (ADR
		// Decision 209).
		for (String question : Arrays.asList("Is ibuprofen causing any interactions with her other drugs?",
			"Is ibuprofen interacting with any of her medications?", "Does ibuprofen cause interactions with her other meds?")) {
			String answer = answer(question);

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.startsWith("Ibuprofen is one of this patient's active medications (Ibuprofen 400mg ["),
				question + " was: " + answer);
		}
	}

	@Test
	public void twoDrugsAskedAboutTogetherOnlyOneOfWhichIsHersAreNotAnsweredAsBothHers() throws IOException {
		// Decision 196's question, not this one: celecoxib is not among her orders here.
		String answer = answer("Can ibuprofen and celecoxib be given together?", null,
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
				DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg")));

		assertTrue(answer == null || !answer.contains("are both among this patient's active medications"), "was: " + answer);
	}

	private static final List<PatientClinicalContext.ActiveDrugOrder> ALL_FOUR = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
		DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"),
		DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"),
		DrugReferenceTestSupport.activeOrder("order-dexamethasone", "Dexamethasone 8mg"));

	private static String answer(String question) throws IOException {
		return answer(question, null, ALL_FOUR);
	}

	/** As {@link #answer(String, java.util.Set, List)}, over another fixture slice. */
	private static String answerOver(String fixture, String question, List<PatientClinicalContext.ActiveDrugOrder> orders)
			throws IOException {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			null, null, orders);
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(fixture))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question).getModuleAnswer();
	}

	/** As {@link #answerOver}, over the knowledge base the module ships. */
	private static String answerOverTheShippedData(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			null, null, orders);
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question).getModuleAnswer();
	}

	private static String answer(String question, java.util.Set<String> allergies,
			List<PatientClinicalContext.ActiveDrugOrder> orders) throws IOException {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			allergies, null, orders);
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question).getModuleAnswer();
	}
}
