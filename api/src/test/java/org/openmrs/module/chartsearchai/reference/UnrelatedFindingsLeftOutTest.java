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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A question about a drug states nothing about her other medications alone (ADR Decision 387): on the 3.7.1 standalone
 * Rose Rifadupla's two TB regimens, which share isoniazid, pyrazinamide and rifampicin, were said to be possible
 * duplicate therapy after "Is ampicillin safe for him?" and "Can he drink alcohol?" — nothing either asked. Her orders as
 * the standalone holds them; the shipped knowledge base.
 */
public class UnrelatedFindingsLeftOutTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ROSE = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine / zidovudine 150mg/300mg", "Lamivudine", "Zidovudine"),
		DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz 600mg", "Efavirenz"),
		DrugReferenceTestSupport.activeOrder("order-ctx", "Cotrimoxazole 960mg", "Sulfamethoxazole", "Trimethoprim"),
		DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin 75/400/150mg", "Isoniazid",
			"Pyrazinamide", "Rifampicin"),
		DrugReferenceTestSupport.activeOrder("order-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg",
			"Rifampicin", "Isoniazid", "Pyrazinamide", "Ethambutol"),
		DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOwnOrdersSharingASubstanceAreNotStatedAfterAQuestionAboutAnotherDrug() {
		String answer = ask("Is ampicillin safe for her?");
		assertNotNull(answer, "precondition: the module answers");
		assertFalse(answer.contains("possible duplicate therapy"), "nothing about her TB regimens: " + answer);
	}

	@Test
	public void herOwnOrdersSharingTheSubstanceAskedAboutAreStillStated() {
		String answer = ask("Is her isoniazid safe?");
		assertNotNull(answer, "precondition: the module answers");
		assertTrue(answer.contains("possible duplicate therapy"), "isoniazid is in both her TB regimens: " + answer);
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ROSE) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ROSE), question).getModuleAnswer();
	}
}
