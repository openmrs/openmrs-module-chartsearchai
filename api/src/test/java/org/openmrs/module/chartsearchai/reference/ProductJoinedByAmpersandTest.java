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
 * A product's names joined by "&" are one product, and a word the data does not recognise joined to it so is part of its
 * name, said not checked (ADR Decision 373). On the 3.7.1 standalone "Is her eucalyptus & diphenhydramine & phenylephrine
 * safe?" fitted no shape for any patient: the model told Karen Thompson, whose order it is, "The records do not address
 * the safety of Eucalyptus & Diphenhydramine & Phenylephrine", and Joshua Johnson, on lisinopril, "Diphenhydramine can be
 * given, with one caution". Over the shipped knowledge base.
 */
public class ProductJoinedByAmpersandTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Is her eucalyptus & diphenhydramine & phenylephrine safe?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOrderOfTheProductIsAnsweredAsHersTheUnrecognisedPartSaidNotChecked() {
		String answer = ask(QUESTION, DrugReferenceTestSupport.activeOrder("order-euc",
			"Eucalyptus & Diphenhydramine & Phenylephrine", "Diphenhydramine", "Phenylephrine"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertTrue(lines[0].contains(" is one of this patient's active medications (Eucalyptus & Diphenhydramine & "
				+ "Phenylephrine [n])"), "was: " + answer);
		assertEquals(DrugReferenceInjector.notCheckedInTheProduct("eucalyptus"), lines[lines.length - 1], "was: " + answer);
		assertEquals(1, answer.split("Not checked:", -1).length - 1, "the one word not recognised, once: " + answer);
	}

	@Test
	public void theProductNamedAsHersOfAPatientNotOnItIsSaidSoTheUnrecognisedPartSaidNotChecked() {
		String answer = ask(QUESTION, DrugReferenceTestSupport.activeOrder("order-lis", "Lisinopril 10 mg", "Lisinopril"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertTrue(lines[0].endsWith(DrugReferenceInjector.NOT_HER_MEDICATION), "was: " + answer);
		assertTrue(!answer.contains("can be given"), "never a clearance: " + answer);
		assertEquals(DrugReferenceInjector.notCheckedInTheProduct("eucalyptus"), lines[lines.length - 1], "was: " + answer);
		assertEquals(1, answer.split("Not checked:", -1).length - 1, "the one word not recognised, once: " + answer);
	}

	@Test
	public void twoNamesJoinedByAnAmpersandAreOneProduct() {
		String answer = ask("Is her diphenhydramine & phenylephrine safe?", DrugReferenceTestSupport.activeOrder("order-euc",
			"Eucalyptus & Diphenhydramine & Phenylephrine", "Diphenhydramine", "Phenylephrine"));
		assertNotNull(answer, "the module answers");
		assertTrue(answer.contains(" is one of this patient's active medications (Eucalyptus & Diphenhydramine & "
				+ "Phenylephrine [n])"), "was: " + answer);
		assertTrue(!answer.contains("Not checked:"), "every name recognised: " + answer);
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
