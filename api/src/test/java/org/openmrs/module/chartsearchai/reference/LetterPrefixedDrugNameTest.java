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
 * A drug name written with a one-letter chemical prefix — "N-acetylcysteine" — is the drug's name, prefix and all (ADR
 * Decision 317). On the 3.7.1 standalone "Can I give her N-acetylcysteine?" resolved acetylcysteine but left "n" a word of
 * its own beside the name, which no question shape admits, so the model answered every patient: "N-acetylcysteine can be
 * given, with one caution: it has an Unknown severity interaction with metronidazole", and "No — N-acetylcysteine should
 * not be given because it has an Unknown severity interaction with Lisinopril". Over the shipped knowledge base.
 */
public class LetterPrefixedDrugNameTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aLetterPrefixedNameIsAnsweredAsTheDrugItNames() {
		for (PatientClinicalContext.ActiveDrugOrder order : Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"),
				DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril"))) {
			for (String[] pair : new String[][] { { "Can I give her N-acetylcysteine?", "Can I give her acetylcysteine?" },
					{ "Is N-acetylcysteine safe for her?", "Is acetylcysteine safe for her?" } }) {
				String plain = ask(pair[1], order);
				assertNotNull(plain, pair[1] + " / " + order.getDisplay() + " — precondition: the module answers");
				assertEquals(mask(plain), mask(ask(pair[0], order)), pair[0] + " / " + order.getDisplay());
			}
		}
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
