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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The clause naming which of her orders a finding's substances were resolved from ({@link
 * DrugReferenceInjector#FINDING_CHART_ORDER_LEAD}) is a sentence of its own, and the substance it opens with is the
 * chip's partner name, which can be the knowledge base's lower-case token: on the 3.7.1 standalone, <em>"Can I start
 * her on rifampicin?"</em> beside a {@code Cotrimoxazole 960mg} order was answered <em>"… — Moderate. This module
 * resolved the substances named here from this patient's own active orders. trimethoprim from Cotrimoxazole
 * 960mg."</em> The clause opens with a capital, as every other sentence the module writes does.
 */
public class ChartOrderClauseSentenceCaseContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void theOrderClauseOpensItsSentenceWithACapital() {
		PatientClinicalContext.ActiveDrugOrder cotrimoxazole = DrugReferenceTestSupport.activeOrder("order-ctx",
			"Cotrimoxazole 960mg",
			DrugReferenceTestSupport.set("Cotrimoxazole 960mg", "Septrin", "Sulfamethoxazole / trimethoprim", "Bactrim",
				"Septra", "Cotrimoxazole", "Sulfatrim", "SMX-TMP", "CTX", "Cotrim", "Co-trimoxazole"),
			DrugReferenceTestSupport.set("J01EE01"));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, cotrimoxazole.getNames(),
				cotrimoxazole.getAtcCodes(), null, null, Arrays.asList(cotrimoxazole));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(
			DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, "Can I start her on rifampicin?");

		String lead = DrugReferenceInjector.FINDING_CHART_ORDER_LEAD;
		boolean bridged = false;
		for (String finding : DrugReferenceTestSupport.findingTexts(chart)) {
			int at = finding.indexOf(lead);
			if (at < 0) {
				continue;
			}
			bridged = true;
			char opening = finding.charAt(at + lead.length());
			assertFalse(Character.isLowerCase(opening), "the clause opens a sentence: " + finding);
		}
		assertTrue(bridged, "precondition: a finding names the order a substance came from: " + chart.getText());
		String answer = chart.getModuleAnswer();
		assertNotNull(answer, chart.getText());
		assertFalse(answer.contains(lead + "trimethoprim") || answer.contains(lead + "sulfa"),
			"nor does the module's own answer open that sentence in lower case: " + answer);
	}
}
