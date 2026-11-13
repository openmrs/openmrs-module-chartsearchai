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
 * His antibiotics are his TB drugs too, and his streptomycin is a TB drug (ADR Decision 634). ATC files the TB drugs under
 * J04A, outside the antibacterials of J01, and streptomycin under J01GA: a patient on rifampicin asked of his antibiotics
 * was told "No antibiotic is among this patient's active medications", and one on streptomycin asked of his TB drugs "No
 * TB drug is among …".
 */
public class AntibioticsAndTbDrugsTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void hisTbDrugsAreAmongHisAntibiotics() {
		String answer = ask("Do his antibiotics interact with his ARVs?",
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"));

		assertNotNull(answer);
		assertEquals("This patient's antiretrovirals (Nevirapine) and antibiotics (Rifampicin): what the interaction check "
				+ "relates between them:", answer.split("\n")[0]);
	}

	@Test
	public void hisStreptomycinIsATbDrug() {
		String answer = ask("Any interactions with his TB drugs?",
			DrugReferenceTestSupport.activeOrder("order-sm", "Streptomycin"));

		assertNotNull(answer);
		assertEquals("The interaction check relates none of this patient's TB drugs (Streptomycin) to this patient's other "
				+ "active medications.", answer.split("\n")[0]);
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder other) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"), other);
		LinkedHashSet<String> names = new LinkedHashSet<String>(Arrays.asList("Nevirapine"));
		names.addAll(other.getNames());
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
