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
 * A drug proposed after a list of drugs the question says she is on is answered by the module where a finding about it is
 * a contraindication too, with no verdict, and every listed drug the check raised nothing against is said so (ADR Decision
 * 389). On the 3.7.1 standalone Betty Williams, on bupivacaine and lidocaine and recorded allergic to ketoconazole, was
 * answered by the model for fluconazole after such a list: "No — Fluconazole should not be given", the zidovudine and
 * sulfamethoxazole interactions left out; and the rifampicin answer after her other list never named abacavir. Her chart
 * as the standalone holds it; the shipped knowledge base.
 */
public class ListProposalBesideHerAllergyTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> BETTY = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-bupi", "Bupivacaine", "Bupivacaine"),
		DrugReferenceTestSupport.activeOrder("order-lido", "Lidocaine", "Lidocaine"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aContraindicationAboutTheDrugProposedAfterAListIsStatedWithNoVerdict() {
		String answer = ask("The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and "
				+ "sulfamethoxazole  is it safe to give Fluconazole?");
		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Fluconazole:", lines[0], answer);
		assertTrue(answer.contains("\nFluconazole is in the same ATC class (D01AC) as the patient's allergy to Ketoconazole"),
			"her allergy: " + answer);
		for (String line : Arrays.asList("Fluconazole interacts with Efavirenz, also named in the question — Major.",
				"Fluconazole interacts with Zidovudine, also named in the question — Moderate.",
				"Fluconazole interacts with Sulfamethoxazole, also named in the question — Minor.",
				"Fluconazole interacts with active order Lidocaine — Moderate.")) {
			assertTrue(answer.contains("\n" + line), "every finding about fluconazole — " + line + ": " + answer);
		}
		assertTrue(answer.contains("\nThe check of Fluconazole against Lamivudine and Trimethoprim, also named in the question, "
				+ "raised no finding."), "the listed drugs it raised nothing against: " + answer);
		assertTrue(!answer.contains("No —") && !answer.contains("No if") && !answer.contains("can be given"),
			"no verdict beside an unrated contraindication: " + answer);
	}

	@Test
	public void aListedDrugTheCheckRaisedNothingAgainstIsSaidSo() {
		String answer = ask("The patient is currently on Abacavir, Lopinavir / ritonavir, Didanosine and Trimethoprim and "
				+ "sulfamethoxazole is it safe to give Rifampicin?");
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("No if the patient is on Lopinavir or Ritonavir"), answer);
		assertTrue(answer.contains("\nThe check of Rifampicin (rifampin) against Abacavir, also named in the question, raised "
				+ "no finding."), "abacavir, named and checked: " + answer);
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : BETTY) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					DrugReferenceTestSupport.set("acetylsalicylate sodium", "ketoconazole", "lidocaine"), null, BETTY),
					question).getModuleAnswer();
	}
}
