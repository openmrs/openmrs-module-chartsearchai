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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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

	/** A name the data does not recognise, called hers, in the same question: said so, never left to the model (ADR
	 *  Decision 335) — the model answered "Can I stop his klarizom?" for patients on no Klarizom "The records do not address
	 *  Klarizom". */
	@Test
	public void anUnrecognisedNameSheIsNotOnIsSaidSo() {
		String answer = ask("Can I stop his klarizom?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(answer, "the module answers");
		assertEquals("None of this patient's active orders is named \"klarizom\", so there is no order of it to stop.", answer);
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
}
