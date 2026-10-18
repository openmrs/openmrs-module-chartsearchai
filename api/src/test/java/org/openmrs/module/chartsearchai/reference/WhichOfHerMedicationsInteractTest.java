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
 * Which of her medications interact with a drug she is not on is answered with the findings relating it to them and
 * neither "Yes" nor "No"; whether anything she takes interacts with it, in its other wordings, is answered as whether it
 * interacts with her medications (ADR Decision 319). On the 3.7.1 standalone "Which of her medications interact with
 * rifampicin?" went to the model for every patient but one: Richard Brown, on salicylic acid, was told "The medications
 * that interact with Rifampicin are ivosidenib, ixazomib, ketoconazole, larotrectinib, and lefamulin" — none of them his.
 * Over the shipped knowledge base.
 */
public class WhichOfHerMedicationsInteractTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	private static final List<List<PatientClinicalContext.ActiveDrugOrder>> FIXTURES = Arrays.asList(
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine 150mg", "Lamivudine")),
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole")),
		Arrays.asList(DrugReferenceTestSupport.activeOrder("order-sal", "Salicylic acid", "Salicylic acid")));

	@Test
	public void whichOfHerMedicationsInteractIsAnsweredWithTheFindingsAndNeitherYesNorNo() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : FIXTURES) {
			String interacts = ask("Does rifampicin interact with her medications?", orders);
			assertNotNull(interacts, orders.get(0).getDisplay() + " — precondition: the module answers whether it interacts");
			// The findings as whether it interacts states them, without its "Yes —" line where it opens with one.
			String findings = interacts.startsWith("Yes — ") ? interacts.substring(interacts.indexOf('\n') + 1) : interacts;
			for (String question : Arrays.asList("Which of her medications interact with rifampicin?",
					"Which of her drugs interact with rifampicin?", "Which of her drugs is the problem with rifampicin?")) {
				String answer = ask(question, orders);
				assertNotNull(answer, question + " / " + orders.get(0).getDisplay() + " — the module answers");
				assertTrue(!answer.startsWith("Yes —") && !answer.startsWith("No —"), question + " was: " + answer);
				assertTrue(mask(answer).endsWith(mask(findings)), question + " / " + orders.get(0).getDisplay()
						+ " — the findings: " + answer + "\n  expected to end with: " + findings);
			}
		}
	}

	@Test
	public void whetherAnythingSheTakesInteractsIsAnsweredAsWhetherItInteractsWithHerMedications() {
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : FIXTURES) {
			String interacts = ask("Does rifampicin interact with her medications?", orders);
			for (String question : Arrays.asList("Is there anything in her med list that interacts with rifampicin?",
					"Is she on any drugs that interact with rifampicin?", "Does rifampicin interact with anything she takes?",
					"Does she have any interactions with rifampicin?")) {
				assertEquals(mask(interacts), mask(ask(question, orders)), question + " / " + orders.get(0).getDisplay());
			}
		}
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	private static String ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
