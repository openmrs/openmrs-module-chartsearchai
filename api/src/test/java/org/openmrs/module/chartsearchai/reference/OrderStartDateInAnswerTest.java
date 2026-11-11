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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A module answer naming an order as one of her active medications says when it started (ADR Decision 621). On the demo
 * replica, patient dd80812e — one visit, 2006-04-10, isoniazid preventive therapy ordered that day and never stopped — was
 * told for "any drug interactions?" "This patient has one active medication order, Isoniazid, so there is no other active
 * medication to check it against.": true of the record, and read by a clinician as a drug she takes today.
 */
public class OrderStartDateInAnswerTest extends BaseModuleContextSensitiveTest {

	private static final Date APRIL_2006 = new GregorianCalendar(2006, Calendar.APRIL, 10).getTime();

	private static final Date JANUARY_2026 = new GregorianCalendar(2026, Calendar.JANUARY, 5).getTime();

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aScreenOfHerOneOrderSaysWhenItStarted() {
		String answer = answer("any drug interactions?", started("order-inh", "Isoniazid", APRIL_2006, "Isoniazid"));

		assertEquals("This patient has one active medication order, Isoniazid (started 2006-04-10), so there is no other "
				+ "active medication to check it against.", answer);
	}

	@Test
	public void aScreenOfOneDrugInSeveralOrdersSaysWhenEachStarted() {
		String answer = answer("Does he have any drug interactions?",
			started("order-mtz-1", "Metronidazole", APRIL_2006, "Metronidazole"),
			started("order-mtz-2", "Metronidazole", JANUARY_2026, "Metronidazole"));

		assertNotNull(answer, "the module answers");
		assertEquals("This patient's 2 active medication orders are all Metronidazole (started 2006-04-10 and 2026-01-05), so "
				+ "there is no other active medication to check it against.", answer.split("\n")[0], answer);
	}

	@Test
	public void herOwnDrugAskedAboutSaysWhenItsOrderStarted() {
		for (String question : Arrays.asList("Does isoniazid interact with her medications?", "Can I stop her isoniazid?")) {
			String answer = answer(question, started("order-inh", "Isoniazid 300mg", APRIL_2006, "Isoniazid"));

			assertNotNull(answer, question + " — the module answers");
			String line = Arrays.stream(answer.split("\n")).filter(l -> l.startsWith("Isoniazid is one of")).findFirst()
					.orElse(answer);
			assertEquals("Isoniazid is one of this patient's active medications (Isoniazid 300mg [n], started 2006-04-10), and "
					+ "this patient has no other active medication to check it against.", line, question + " — was: " + answer);
		}
	}

	@Test
	public void herOrderNamedByItsOwnNameSaysWhenItStarted() {
		// As the 3.7.1 standalone records Kevin Brown's: a concept named only "Clarithromycine", the data's substance reached
		// by its code (ADR Decisions 335, 336).
		PatientClinicalContext.ActiveDrugOrder order = PatientClinicalContext.ActiveDrugOrder.named("order-k",
			"Clarithromycine Co 500mg", new LinkedHashSet<String>(Arrays.asList("clarithromycine co 500mg", "clarithromycine")),
			new LinkedHashSet<String>(Arrays.asList("J01FA09")), null, null, null, APRIL_2006);
		for (String question : Arrays.asList("Can I stop his clarithromycine?",
			"Does clarithromycine interact with his medications?")) {
			String answer = answer(question, order);

			assertNotNull(answer, question + " — the module answers");
			String line = Arrays.stream(answer.split("\n")).filter(l -> l.startsWith("Clarithromycine is one of")).findFirst()
					.orElse(answer);
			assertEquals("Clarithromycine is one of this patient's active medications (Clarithromycine Co 500mg [n], started "
					+ "2006-04-10), and this patient has no other active medication to check it against.", line,
				question + " — was: " + answer);
		}
	}

	@Test
	public void aDuplicateQuestionOfHerOneOrderSaysWhenItStarted() {
		String answer = answer("Any duplicate therapy?", started("order-inh", "Isoniazid", APRIL_2006, "Isoniazid"));

		assertEquals("This patient has one active medication order, Isoniazid (started 2006-04-10), so there is no other "
				+ "active medication to compare it with.", answer);
	}

	/** The line a client shows of a longer answer keeps when her order started: it is what the answer says of the order,
	 *  not detail the summary drops with the rest of the aside citing it. */
	@Test
	public void theSummaryInViewKeepsWhenHerOrderStarted() {
		PatientChart chart = chart("Can I stop her isoniazid?", started("order-inh", "Isoniazid 300mg", APRIL_2006,
			"Isoniazid"));

		assertNotNull(chart.getModuleAnswerSummary(), "a longer answer has a summary: " + chart.getModuleAnswer());
		assertTrue(chart.getModuleAnswerSummary().contains("Isoniazid is one of this patient's active medications (started "
				+ "2006-04-10)"), chart.getModuleAnswerSummary());
	}

	@Test
	public void anOrderWhoseStartIsNotKnownIsNamedAsBefore() {
		String answer = answer("any drug interactions?", DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid",
			"Isoniazid"));

		assertEquals("This patient has one active medication order, Isoniazid, so there is no other active medication to "
				+ "check it against.", answer);
	}

	private static PatientClinicalContext.ActiveDrugOrder started(String uuid, String display, Date start, String... names) {
		Set<String> all = new LinkedHashSet<String>();
		all.add(display);
		Collections.addAll(all, names);
		return PatientClinicalContext.ActiveDrugOrder.named(uuid, display, all, null, null, null, null, start);
	}

	private static String answer(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		String answer = chart(question, orders).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static PatientChart chart(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, names, null, null, null,
			Arrays.asList(orders));
		// The chart carries each order's own drug_order record, as querystore's does.
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, order.getUuid(),
					order.getDisplay() + ", 1 daily", null));
		}
		PatientChart base = new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(base, context, question);
	}
}
