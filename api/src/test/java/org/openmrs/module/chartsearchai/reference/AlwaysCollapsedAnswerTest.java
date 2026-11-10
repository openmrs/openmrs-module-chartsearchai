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
 * Every answer the module writes of more than one line has one sentence a client shows, the rest collapsed under it,
 * and says whether what is under it states what the check did not cover (ADR Decision 519). Over the shipped
 * knowledge base, on the public demo's patient dd74dc61's orders: lamivudine, nevirapine and stavudine, each three times.
 */
public class AlwaysCollapsedAnswerTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	/**
	 * "Is it safe to start her on clarithromycin?" is answered with its one caution, and where the data says nothing of
	 * drug-condition rules, the finding under it says nothing of what the check did not cover — Decision 146 left the
	 * drug's rows below the floor out of a caution answer, as telling a clinician nothing they could use. So the flag says
	 * the details state no limits, and a client labels them "Show details" rather than promising limits that are not
	 * there. Where the data loaded carries no drug-condition rule, as the demo's does, the answer says her conditions were
	 * not checked and the flag says so (ADR Decision 533; {@code ProposalSaysHerConditionsWereNotCheckedTest}).
	 */
	@Test
	public void aProposalsCautionIsOneSentenceAndItsDetailsPromiseNoLimits() {
		PatientChart chart = ask("Is it safe to start her on clarithromycin?", null, demoOrders());
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("1 interaction caution for Clarithromycin — Moderate, with Nevirapine.", lines[0], "was: " + answer);
		assertEquals(lines[0], chart.getModuleAnswerSummary());
		assertEquals(2, lines.length, "the sentence and its finding: " + answer);
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Nevirapine (3 orders) — Moderate."), "was: " + answer);
		assertFalse(chart.moduleAnswerDetailsStateLimits(), "nothing under the sentence says what was not checked");
	}

	/**
	 * A screen's details state what it did not cover, so the flag says so and a client labels them details and limits.
	 */
	@Test
	public void aScreensDetailsStateItsLimits() {
		PatientChart chart = ask("Does he have any drug interactions?", null, demoOrders());

		assertEquals("No rated interactions; 3 possible duplicate therapies.", chart.getModuleAnswerSummary(),
			"was: " + chart.getModuleAnswer());
		assertTrue(chart.moduleAnswerDetailsStateLimits(), "was: " + chart.getModuleAnswer());
	}

	/**
	 * An answer whose own first line is a heading opens with one sentence counting what it cites, so no finding is out
	 * of view that the sentence in view does not count; and what is under it says nothing of what was not checked, so
	 * the flag says so and a client labels it plainly.
	 */
	@Test
	public void anAnswerWithNoBottomLineOfItsOwnOpensWithOneCountingWhatItCites() {
		PatientChart chart = ask("Is she allergic to any of her medications?", DrugReferenceTestSupport.set("lamivudine"),
			demoOrders());
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertTrue(lines.length > 2, "precondition: more than a sentence — " + answer);
		// Her lamivudine allergy and stavudine's cross-reactivity with it: two drugs, so no one drug leads the sentence.
		assertEquals("2 contraindications.", chart.getModuleAnswerSummary(), "was: " + answer);
		assertEquals("Yes — this patient has a recorded allergy to one of the medications this patient is taking:", lines[0],
			"the answer's own words, unchanged, collapsed whole under the sentence: " + answer);
		assertFalse(chart.moduleAnswerDetailsStateLimits(), "nothing under it says what was not checked: " + answer);
	}

	/**
	 * The sentence in view keeps what the answer says first — that he takes the drug — and counts what it cites (ADR
	 * Decision 529): on the replica "Is he on nevirapine?" showed only "Nevirapine: no Major interactions; 1 unrated
	 * interaction.", which does not say he is on it.
	 */
	@Test
	public void whetherHeIsOnADrugIsSaidInTheSentenceInView() {
		PatientChart chart = ask("Is he on nevirapine?", null, demoOrders());
		String summary = chart.getModuleAnswerSummary();

		assertNotNull(summary, "was: " + chart.getModuleAnswer());
		assertTrue(summary.startsWith("Nevirapine is one of this patient's active medications; "), "was: " + summary);
		// The finding that it is already in his orders, counted as the duplicate it is (ADR Decision 592).
		assertTrue(summary.endsWith("1 possible duplicate therapy."), "and counts what it cites: " + summary);
		assertFalse(summary.contains("[") || summary.contains("("), "no markers or asides in view: " + summary);
	}

	/** A switch to a drug he already takes says so in view; on the replica it showed only that the drug switched from
	 *  is not his. */
	@Test
	public void aSwitchToADrugHeAlreadyTakesSaysSoInView() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		orders.add(DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz", "Efavirenz"));
		orders.add(DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine", "Lamivudine"));
		PatientChart chart = ask("Can I switch his nevirapine to efavirenz?", null, orders);
		String summary = chart.getModuleAnswerSummary();

		assertNotNull(summary, "was: " + chart.getModuleAnswer());
		assertTrue(summary.contains("Efavirenz is already one of this patient's active medications"), "was: " + summary);
	}

	/** Two drugs asked about together that raise nothing say so in view, not only that neither is his. */
	@Test
	public void twoDrugsThatRaiseNothingSayItInView() {
		PatientChart chart = ask("Can I give him ibuprofen and omeprazole?", null, demoOrders());
		String summary = chart.getModuleAnswerSummary();

		assertNotNull(summary, "was: " + chart.getModuleAnswer());
		assertTrue(summary.contains("the interaction check raised no finding about Ibuprofen"), "was: " + summary);
		assertTrue(summary.contains("the interaction check raised no finding about Omeprazole"), "was: " + summary);
	}

	/** Where it cites findings, the sentence in view is its first line's statement and the count, and nothing it counts
	 *  is said twice: "Does his nevirapine interact with rifampicin?" grew to 35 words when every statement before
	 *  the finding was kept. */
	@Test
	public void aPairQuestionIsItsFirstStatementAndItsCount() {
		PatientChart chart = ask("Does his nevirapine interact with rifampicin?", null, demoOrders());

		assertEquals("Nevirapine is one of this patient's active medications; 1 Major interaction — Rifampicin (rifampin) with "
				+ "Nevirapine.", chart.getModuleAnswerSummary(), "was: " + chart.getModuleAnswer());
	}

	/** A rated interaction is counted under its rating, whatever the clause it carries: on the DDI rig "Is naproxen
	 *  safe for him?" beside his aspirin showed "Naproxen: no Major interactions; 1 unrated interaction." over a
	 *  finding rated Moderate, whose shared NSAID group made it a reason to withhold rather than a caution. */
	@Test
	public void aRatedInteractionIsNotCountedAsUnrated() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		orders.add(DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));
		PatientChart chart = ask("Is naproxen safe for him?", null, orders);
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		assertTrue(answer.contains("Naproxen interacts with active order Acetylsalicylic acid (aspirin) — Moderate."),
			"precondition: the finding is rated Moderate — " + answer);
		assertEquals("1 Moderate interaction — Naproxen with Acetylsalicylic acid (aspirin).",
			chart.getModuleAnswerSummary(), "was: " + answer);
	}

	/** A pair the data rates from both sides is stated once, from the side the question names first, however the
	 *  question asks about it: on the DDI rig "How severe is the interaction between her rifampicin and nevirapine?"
	 *  stated the one pair twice, once from each drug, and counted "2 Major interactions". */
	@Test
	public void aPairRatedFromBothSidesIsStatedOnce() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		orders.add(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"));
		orders.add(DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin"));
		PatientChart chart = ask("How severe is the interaction between his rifampicin and nevirapine?", null, orders);
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		int stated = answer.split("interacts with active order", -1).length - 1;
		assertEquals(1, stated, "the pair once: " + answer);
		assertTrue(answer.contains("Rifampicin (rifampin) interacts with active order Nevirapine — Major."),
			"from the drug named first: " + answer);
		assertTrue(chart.getModuleAnswerSummary().endsWith("1 Major interaction — Rifampicin (rifampin) with Nevirapine."),
			"was: " + chart.getModuleAnswerSummary());
	}

	/**
	 * A finding that her drug is already in her own orders is counted as a possible duplicate therapy, as a screen counts
	 * it, never as an unrated interaction (ADR Decision 592): on the demo replica "Are there any interactions with her
	 * nevirapine?" was summed up "…; no Major interactions; 1 unrated interaction." over "Nevirapine is already in active
	 * orders Nevirapine (3 orders) — possible duplicate therapy."
	 */
	@Test
	public void aFindingThatHerDrugIsAlreadyInHerOrdersIsCountedAsADuplicate() {
		PatientChart chart = ask("Are there any interactions with her nevirapine?", null, demoOrders());
		assertTrue(chart.getModuleAnswer().contains("Nevirapine is already in active orders Nevirapine (3 orders)"),
			"precondition: the finding — " + chart.getModuleAnswer());
		assertTrue(chart.getModuleAnswerSummary().endsWith("1 possible duplicate therapy."),
			"was: " + chart.getModuleAnswerSummary());
		assertFalse(chart.getModuleAnswerSummary().contains("unrated interaction"), "was: " + chart.getModuleAnswerSummary());
	}

	/**
	 * A drug the data lists against her medications only below the floor names those medications, as the answer about one
	 * she is not on names them (ADR Decision 593): on the demo replica "Can I give her metformin?" said "The data lists
	 * Metformin against 3 of this patient's medications, none with a severity or mechanism on file." and named none.
	 */
	@Test
	public void aProposalTheDataListsBelowTheFloorNamesHerMedications() {
		PatientChart chart = ask("Can I give her metformin?", null, demoOrders());
		assertTrue(chart.getModuleAnswer().contains("Metformin against 3 of this patient's medications, Lamivudine, Nevirapine "
				+ "and Stavudine, none with a severity or mechanism on file."), "was: " + chart.getModuleAnswer());
		// And of one of hers, against her other medications.
		PatientChart own = ask("Can I stop her nevirapine?", null, demoOrders());
		assertTrue(own.getModuleAnswer().contains("Nevirapine against 2 of this patient's other medications, Lamivudine and "
				+ "Stavudine, none with a severity or mechanism on file."), "was: " + own.getModuleAnswer());
	}

	/**
	 * A switch is summed up by the new drug's answer against the orders that stay, and the finding about the order the switch
	 * replaces is counted after it, never headlined (ADR Decision 600): on the demo replica "Can I give her efavirenz instead
	 * of nevirapine?" was summed up "…, and this switch replaces it; 1 Moderate interaction — Efavirenz with Nevirapine —
	 * plus 2 cautions.", leading with the pair the switch ends.
	 */
	@Test
	public void aSwitchIsSummedUpByTheNewDrugAgainstTheOrdersThatStay() {
		PatientChart chart = ask("Can I give him efavirenz instead of nevirapine?", null, demoOrders());
		assertTrue(chart.getModuleAnswer().contains("\nAbout the order the switch replaces:\n"),
			"precondition: " + chart.getModuleAnswer());
		assertEquals("Nevirapine is one of this patient's active medications, and this switch replaces it; 2 interaction cautions "
				+ "for Efavirenz — Moderate, with Lamivudine and Stavudine; 1 finding about the order the switch replaces.",
			chart.getModuleAnswerSummary(), "was: " + chart.getModuleAnswer());
	}

	/**
	 * Whether two drugs interact that the data lists only below the floor is answered in the sentence in view, before what
	 * else was found is counted (ADR Decision 601): on the demo replica "Do her nevirapine and stavudine interact?" was
	 * summed up by her orders and a count headed by another order's Major, and the listing that answers it was not in view.
	 * Since ADR Decision 618 nothing about her other orders follows it.
	 */
	@Test
	public void twoDrugsListedBelowTheFloorAreAnsweredInTheSentenceInView() {
		PatientChart chart = ask("Does her lamivudine interact with her nevirapine?", null, demoOrders());
		String summary = chart.getModuleAnswerSummary();
		assertNotNull(summary, "was: " + chart.getModuleAnswer());
		// and nothing after it: what relates either to her stavudine is not what was asked (ADR Decision 618)
		assertEquals("Lamivudine and Nevirapine are both among this patient's active medications; the interaction data lists "
				+ "Lamivudine against Nevirapine, with no severity or mechanism on file [n] [n].",
			summary.replaceAll("\\[\\d+\\]", "[n]"), "was: " + summary);
	}

	private static List<PatientClinicalContext.ActiveDrugOrder> demoOrders() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			for (String drug : new String[] { "Lamivudine", "Nevirapine", "Stavudine" }) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug + "-" + n, drug, drug));
			}
		}
		return orders;
	}

	private static PatientChart ask(String question, Set<String> allergens, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null, allergens, null,
				orders), question);
	}
}
