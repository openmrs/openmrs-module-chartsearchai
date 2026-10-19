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
 * A proposal naming the drug's form — "ketoconazole cream" — is answered with what the check found about the drug, the
 * form said not weighed, and no verdict (ADR Decision 334): the findings are the drug's, not the form's. On the 3.7.1
 * standalone "Can I give her ketoconazole cream?" fitted no shape and went to the model for every patient: Joshua Johnson
 * was told "Ketoconazole can be given, with one caution", and Karen Sanchez "No — Ketoconazole should not be given: it
 * interacts with active order Metronidazole, a Minor problem". Over the shipped knowledge base.
 */
public class StatedFormProposalTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aStatedFormIsSaidNotWeighedAndMakesNoVerdict() {
		String withhold = ask("Can I give her ketoconazole cream?",
			DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin Co 20mg", "Simvastatin"));
		assertNotNull(withhold, "the module answers");
		String[] lines = withhold.split("\n");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Ketoconazole:", lines[0], withhold);
		assertTrue(lines[1].startsWith("Ketoconazole interacts with active order Simvastatin — Major."), withhold);
		assertEquals(DrugReferenceInjector.FORM_NOT_WEIGHED_OPENING + "cream).", lines[lines.length - 1], withhold);

		String caution = ask("Can I give her ketoconazole cream?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(caution, "the module answers a caution");
		String plain = ask("Can I give her ketoconazole?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertEquals(plain.replaceAll("\\[\\d+\\]", "[n]") + "\n" + DrugReferenceInjector.FORM_NOT_WEIGHED_OPENING + "cream).",
			caution.replaceAll("\\[\\d+\\]", "[n]"));
		assertTrue(!caution.startsWith("No"), caution);
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
