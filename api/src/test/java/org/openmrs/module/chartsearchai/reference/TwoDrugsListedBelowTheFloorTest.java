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
 * Whether two drugs interact, where the data lists them against each other only below the severity floor, is answered
 * with that listing and neither "Yes" nor "No" (ADR Decision 329). On the 3.7.1 standalone "Do her nevirapine and
 * stavudine interact?" went to the model for every patient: most were told "The records do not address the interaction",
 * Amina Wekesa "Nevirapine and Stavudine do not interact", and Richard Brown each drug's partners from the reference
 * record. The data lists the pair rated Unknown. Over the shipped knowledge base.
 */
public class TwoDrugsListedBelowTheFloorTest extends BaseModuleContextSensitiveTest {

	private static final String LISTED = "The interaction data lists Nevirapine against Stavudine, with no severity or "
			+ "mechanism on file. [n] [n]";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoDrugsListedOnlyBelowTheFloorAreSaidSo() {
		String neither = ask("Do her nevirapine and stavudine interact?",
			DrugReferenceTestSupport.activeOrder("order-mtz", "Metronidazole", "Metronidazole"));
		assertNotNull(neither, "the module answers");
		String[] lines = mask(neither).split("\n");
		assertEquals("Neither Nevirapine nor Stavudine is one of this patient's active medications.", lines[0], neither);
		assertEquals(LISTED, lines[1], neither);
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], neither);

		String both = ask("Do her nevirapine and stavudine interact?",
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));
		assertNotNull(both, "the module answers of her own two");
		lines = mask(both).split("\n");
		assertTrue(lines[0].endsWith(" are both among this patient's active medications."), both);
		assertEquals(LISTED, lines[1], both);
		assertTrue(!both.contains("Yes —") && !both.contains("No —"), "neither Yes nor No: " + both);
	}

	private static String mask(String answer) {
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}

	/**
	 * Two of hers the data lists only below the floor, one of them related by a finding to a third order of hers, is answered
	 * as without that order, the finding stated apart (ADR Decision 370): on the 3.7.1 standalone "Does her lamivudine
	 * interact with her nevirapine?" went to the model for Mogan Kamwara, on stavudine too, whose answer ended "Unknown
	 * severity interaction (DDInter 2.0; no mechanism description on file) and.".
	 */
	@Test
	public void aFindingRelatingOneOfHersToAThirdOrderIsStatedApart() {
		PatientClinicalContext.ActiveDrugOrder lamivudine = DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine 150mg",
			"Lamivudine");
		PatientClinicalContext.ActiveDrugOrder nevirapine = DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg",
			"Nevirapine");
		String two = ask("Does her lamivudine interact with her nevirapine?", lamivudine, nevirapine);
		assertNotNull(two, "precondition: answered of the two alone");
		String[] twoLines = two.replaceAll("\\[\\d+\\]", "[n]").split("\n");
		String answer = ask("Does her lamivudine interact with her nevirapine?", lamivudine, nevirapine,
			DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));
		assertNotNull(answer, "the module answers");
		String[] lines = answer.replaceAll("\\[\\d+\\]", "[n]").split("\n");
		assertEquals(twoLines[0], lines[0], "was: " + answer);
		assertEquals(twoLines[1], lines[1], "was: " + answer);
		assertEquals(DrugReferenceInjector.ABOUT_HER_OTHER_MEDICATIONS_LEAD, lines[2], "was: " + answer);
		assertTrue(lines[3].contains("Stavudine"), "the finding relating lamivudine to stavudine: " + answer);
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], "was: " + answer);
	}

	/**
	 * Two drugs the data lists only below the floor, one of them related by a contraindication to her allergy, is answered
	 * as without the allergy, the contraindication stated apart (ADR Decision 371): on the 3.7.1 standalone "Does her
	 * amoxicillin interact with her clopidogrel?" went to the model for the three patients recorded allergic to a penicillin,
	 * which answered "The records do not address whether Amoxicillin interacts with Clopidogrel".
	 */
	@Test
	public void aContraindicationAboutADrugAskedAboutIsStatedApart() {
		PatientClinicalContext.ActiveDrugOrder metformin = DrugReferenceTestSupport.activeOrder("order-met", "Metformin",
			"Metformin");
		String plain = askAllergic("Does her amoxicillin interact with her clopidogrel?", null, metformin);
		assertNotNull(plain, "precondition: answered with no allergy");
		String[] plainLines = plain.replaceAll("\\[\\d+\\]", "[n]").split("\n");
		String answer = askAllergic("Does her amoxicillin interact with her clopidogrel?",
			DrugReferenceTestSupport.set("penicillin g"), metformin);
		assertNotNull(answer, "the module answers");
		String[] lines = answer.replaceAll("\\[\\d+\\]", "[n]").split("\n");
		assertEquals(plainLines[0], lines[0], "was: " + answer);
		assertEquals(plainLines[1], lines[1], "was: " + answer);
		int lead = Arrays.asList(lines).indexOf(DrugReferenceInjector.ABOUT_THE_DRUGS_ASKED_ABOUT_LEAD);
		assertTrue(lead > 1 && lines[lead + 1].startsWith("Amoxicillin"), "the allergy finding apart: " + answer);
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], "was: " + answer);
	}

	private static String askAllergic(String question, java.util.Set<String> allergies,
			PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, allergies, null,
				orders), question).getModuleAnswer();
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
