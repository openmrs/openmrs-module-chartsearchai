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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Two drugs proposed together, neither hers, where one's findings make no verdict — a contraindication, a reason to
 * withhold no rating makes — are answered with what the check found about that one, and no verdict (ADR Decision 305).
 * On the 3.7.1 standalone "Is azithromycin safe with his ondansetron?" went to the model for Kevin Brown, recorded
 * allergic to clarithromycin, which answered "Ondansetron can be given with Azithromycin, with one caution" and left out
 * that azithromycin shares clarithromycin's class (J01FA) — possible cross-reactivity. Over the shipped knowledge base.
 */
public class TwoProposalsNeutralFindingsTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposedDrugWhoseFindingsMakeNoVerdictIsStatedWithWhatWasFound() {
		String answer = ask("Is azithromycin safe with his ondansetron?", DrugReferenceTestSupport.set("clarithromycin"),
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycine Co 500mg", "Clarithromycin"));
		assertNotNull(answer, "the module answers");
		List<String> lines = Arrays.asList(answer.split("\n"));
		assertEquals("Azithromycin and Ondansetron, asked about together; neither is one of this patient's active "
				+ "medications.", lines.get(0), "was: " + answer);
		int azithromycin = lines.indexOf("For Azithromycin:");
		assertTrue(azithromycin > 0, "was: " + answer);
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Azithromycin:", lines.get(azithromycin + 1), "was: " + answer);
		assertTrue(answer.contains("Azithromycin is in the same ATC class (J01FA) as the patient's allergy to Clarithromycin"),
			"the cross-reactivity is stated, was: " + answer);
		assertTrue(!answer.contains("can be given"), "no clearance, was: " + answer);
	}

	private static String ask(String question, Set<String> allergens, PatientClinicalContext.ActiveDrugOrder... orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, allergens, null,
				Arrays.asList(orders)), question).getModuleAnswer();
	}
}
