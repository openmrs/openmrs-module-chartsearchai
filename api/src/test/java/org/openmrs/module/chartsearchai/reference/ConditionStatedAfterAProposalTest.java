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
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal followed by a sentence stating what she has — "Can I give her ciprofloxacin? She has a UTI." — is answered
 * as the proposal, and says the check did not weigh it, as a purpose the proposal states is (ADR Decision 325). On the
 * 3.7.1 standalone it went to the model for every patient: Karen Sanchez, on metronidazole, was told "Ciprofloxacin can be
 * given, with one caution". Over the shipped knowledge base.
 */
public class ConditionStatedAfterAProposalTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aConditionStatedAfterTheProposalIsSaidNotWeighed() {
		String plain = ask("Can I give her ciprofloxacin?");
		assertNotNull(plain, "precondition: the module answers the proposal");
		assertEquals(mask(plain) + "\n" + DrugReferenceInjector.PURPOSE_NOT_WEIGHED_OPENING + "She has a UTI).",
			mask(ask("Can I give her ciprofloxacin? She has a UTI.")));
		assertEquals(mask(plain) + "\n" + DrugReferenceInjector.PURPOSE_NOT_WEIGHED_OPENING + "she has a urinary infection).",
			mask(ask("Can I give her ciprofloxacin, she has a urinary infection?")));
	}

	/** A factor the findings do not weigh keeps the model call, as a purpose carrying one does. */
	@Test
	public void aStatedFactorTheFindingsDoNotWeighKeepsTheModel() {
		assertNull(ask("Can I give her ciprofloxacin? She has kidney disease."));
		assertNull(ask("Can I give her ciprofloxacin? She is pregnant."));
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS),
			question).getModuleAnswer();
	}
}
