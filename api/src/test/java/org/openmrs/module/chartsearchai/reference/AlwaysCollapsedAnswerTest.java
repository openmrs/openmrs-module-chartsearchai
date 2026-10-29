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
	 * On the demo, "Is it safe to start her on clarithromycin?" is answered with its one caution, and the finding under
	 * it says nothing of what the check did not cover — Decision 146 left the drug's rows below the floor out of a caution
	 * answer, as telling a clinician nothing they could use. So the flag says the details state no limits, and a client
	 * labels them "Show details" rather than promising limits that are not there.
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
		assertTrue(lines[1].startsWith("Clarithromycin interacts with active order Nevirapine — Moderate."), "was: " + answer);
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
		assertTrue(summary.endsWith("1 unrated interaction."), "and counts what it cites: " + summary);
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
