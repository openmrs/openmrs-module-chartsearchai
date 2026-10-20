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
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * Whether to stop her drug is answered with what relates it to her other medications, under a lead saying the check does
 * not weigh stopping it; of a drug she is not on, that there is no order of it to stop (ADR Decision 335). On the 3.7.1
 * standalone "Is it safe to stop her nevirapine?" went to the model, which told Karen Sanchez, on no nevirapine, "The
 * records do not address whether it is safe to stop Nevirapine". Over the shipped knowledge base.
 */
public class StoppingHerDrugQuestionTest extends BaseModuleContextSensitiveTest {

	private static final List<String> QUESTIONS = Arrays.asList("Is it safe to stop her nevirapine?",
		"Can I stop her nevirapine?", "Is it OK to stop her nevirapine?");

	/** Whether she should stop it: of her drug, ADR Decision 259's answer; of a drug she is not on, as above. */
	private static final List<String> SHOULD = Arrays.asList("Should I hold her nevirapine?", "Should I stop her nevirapine?");

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void ofHerDrugItIsWhatInteractsWithItUnderALead() {
		PatientClinicalContext.ActiveDrugOrder[] orders = {
				DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
				DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin") };
		String what = ask("What interacts with her nevirapine?", orders);
		assertNotNull(what, "precondition: the module answers what interacts with her drug");
		for (String question : QUESTIONS) {
			assertEquals(DrugReferenceInjector.WEIGHS_NO_STOPPING_LEAD + "\n" + mask(what), mask(ask(question, orders)), question);
		}
	}

	@Test
	public void ofADrugSheIsNotOnThereIsNothingToStop() {
		List<String> all = new java.util.ArrayList<String>(QUESTIONS);
		all.addAll(SHOULD);
		for (String question : all) {
			assertEquals("Nevirapine is not one of this patient's active medications, so there is no order of it to stop.",
				ask(question, DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole")), question);
		}
	}

	/**
	 * Whether one of her orders of a drug should be stopped is whether to stop her drug (ADR Decision 345): on the 3.7.1
	 * standalone "Should one of his metronidazole orders be stopped?" fitted no shape, and the model answered every patient
	 * — Mogan Kamwara, on no metronidazole, was told "Metronidazole can be given, with one caution", and 16 others
	 * "The records do not address Metronidazole orders".
	 */
	@Test
	public void whetherOneOfHerOrdersOfItShouldBeStoppedIsWhetherToStopIt() {
		List<String> questions = Arrays.asList("Should one of his metronidazole orders be stopped?",
			"Should one of her metronidazole orders be discontinued?");
		PatientClinicalContext.ActiveDrugOrder[] two = {
				DrugReferenceTestSupport.activeOrder("order-mtz-1", "Metronidazole", "Metronidazole"),
				DrugReferenceTestSupport.activeOrder("order-mtz-2", "Metronidazole", "Metronidazole") };
		String stop = ask("Should I stop his metronidazole?", two);
		assertNotNull(stop, "precondition: the module answers whether to stop her drug");
		for (String question : questions) {
			assertEquals(mask(stop), mask(ask(question, two)), question);
			assertEquals("Metronidazole is not one of this patient's active medications, so there is no order of it to stop.",
				ask(question, DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine")), question);
		}
	}

	/** A name the data does not recognise, called hers, in the same question: said so (ADR Decision 335) — after the chart
	 *  answer, since the data has no near name for it and it may be no drug at all (ADR Decision 384). */
	@Test
	public void anUnrecognisedNameSheIsNotOnIsSaidSo() {
		PatientChart chart = askChart("Can I stop his klarizom?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNull(chart.getModuleAnswer(), "the chart answer: " + chart.getModuleAnswer());
		assertEquals("None of this patient's active orders is named \"klarizom\", so there is no order of it to stop.",
			chart.getUncheckedWordStatement());
	}

	/** One of her orders named by its own name, a name the data does not carry: what relates it to her other medications, under
	 *  the same lead (ADR Decision 335). On the 3.7.1 standalone Kevin Brown, on Clarithromycine Co 500mg alone, was told by the
	 *  model "The records do not address the safety of stopping Clarithromycine". */
	@Test
	public void herOrderNamedByItsOwnNameIsAnsweredAsHers() {
		// As the 3.7.1 standalone records it: a concept named only "Clarithromycine", the data's substance reached by its code.
		String answer = ask("Can I stop his clarithromycine?", DrugReferenceTestSupport.activeOrder("order-k",
			"Clarithromycine Co 500mg", new LinkedHashSet<String>(Arrays.asList("clarithromycine co 500mg", "clarithromycine")),
			new LinkedHashSet<String>(Arrays.asList("J01FA09"))));
		assertNotNull(answer, "the module answers");
		assertEquals(DrugReferenceInjector.WEIGHS_NO_STOPPING_LEAD + "\nClarithromycine is one of this patient's active "
				+ "medications (Clarithromycine Co 500mg [n]), and this patient has no other active medication to check it against.",
			mask(answer));
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
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
