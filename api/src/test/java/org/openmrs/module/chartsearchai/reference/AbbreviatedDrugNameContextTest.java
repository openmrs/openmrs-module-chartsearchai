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
 * A drug whose name carries an abbreviation — the shipped knowledge base's "St. John's Wort" — is stated whole and
 * resolved as a clinician writes it (ADR Decisions 239, 240). On the 3.7.1 standalone Mogan Kamwara, on nevirapine, asked
 * "Can she take St. John's Wort?" and read the finding line "St. [8] [4].": the composed line was cut at the
 * abbreviation's period. Edward Robinson, on nevirapine, asked "Can I give him St John's wort?" and read "The records do
 * not address St John's wort.": the name written without the period resolved to nothing, so the Major interaction was
 * never checked. Over the shipped knowledge base.
 */
public class AbbreviatedDrugNameContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aFindingLineKeepsADrugNameCarryingAnAbbreviationWhole() {
		assertWithholdsStJohnsWortForHisNevirapine(ask("Can she take St. John's Wort?"), "St. John's Wort");
	}

	@Test
	public void aDrugNameWrittenWithoutItsPeriodOrApostropheResolves() {
		for (String question : Arrays.asList("Can I give him St John's wort?", "Can I give him St Johns wort?",
			"Can I give him St. Johns Wort?")) {
			assertWithholdsStJohnsWortForHisNevirapine(ask(question), question);
		}
	}

	/** The apostrophe a phone's keyboard writes, U+2019, is read as the ASCII one (ADR Decision 242). */
	@Test
	public void aDrugNameWrittenWithATypographicApostropheResolves() {
		for (String question : Arrays.asList("Can I give him St John\u2019s wort?", "Can I give him St. John\u2019s Wort?")) {
			assertWithholdsStJohnsWortForHisNevirapine(ask(question), question);
		}
	}

	private static void assertWithholdsStJohnsWortForHisNevirapine(String answer, String question) {
		assertNotNull(answer, question + ": the module answers");
		String[] lines = answer.split("\n");
		assertEquals(2, lines.length, question + " — the lead and one finding: " + answer);
		assertEquals("No — this module's drug-safety check found a reason to withhold St. John's Wort.", lines[0], question);
		assertTrue(lines[1].matches("St\\. John's Wort interacts with active order Nevirapine — Major\\.( \\[\\d+\\])+"),
			question + " — the finding line whole: " + answer);
	}

	private static String ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nevirapine", "Nevirapine 200mg"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(orders.get(0).getNames()), null, null, null, orders), question).getModuleAnswer();
	}
}
