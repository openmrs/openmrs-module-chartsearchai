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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A combination order two of whose substances each interact with one drug: the knowledge base relates the drug to
 * each substance in its own row, so the module raises two findings naming the one order. Each line of the module's
 * own answer said only <em>"Dolutegravir interacts with active order Isoniazid / Rifapentine — Minor."</em>, so a
 * clinician read the same line twice under a count of two, with nothing saying they were two different interactions.
 * Each such line names the substance of the order it is about.
 */
public class CombinationOrderConstituentLineContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoFindingsAboutOneCombinationOrderNameTheSubstanceEachIsAbout() {
		PatientChart chart = ask("Can I give her dolutegravir?",
			isoniazidRifapentine());

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "two cautions about the drug proposed, so the module answers: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\n"));
		assertEquals(3, lines.size(), "the count and two lines, was: " + answer);
		assertEquals(new LinkedHashSet<String>(lines).size(), lines.size(), "no line repeats another: " + answer);
		assertTrue(lines.get(1).contains("The interacting substance is Rifapentine.")
				!= lines.get(2).contains("The interacting substance is Rifapentine."),
			"one line is about rifapentine: " + answer);
		assertTrue(lines.get(1).contains("The interacting substance is Isoniazid.")
				!= lines.get(2).contains("The interacting substance is Isoniazid."),
			"the other about isoniazid: " + answer);
	}

	@Test
	public void herOwnDrugsTwoFindingsAboutOneCombinationOrderNameTheSubstanceEachIsAbout() {
		// The answer about a drug she already takes printed the bare line twice: on the public demo's data, a patient on
		// rifampicin beside an "Isoniazid / pyrazinamide / rifampin" order was told "Rifampicin (rifampin) interacts with
		// active order Isoniazid / pyrazinamide / rifampin — Major." and the same order again "— Minor.", reading as
		// rifampicin interacting with itself (ADR Decision 214).
		PatientChart chart = ask("Which of her medications interact with dolutegravir?",
			DrugReferenceTestSupport.activeOrder("order-dtg", "Dolutegravir 50mg"), isoniazidRifapentine());

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "her own drug, so the module answers: " + chart.getText());
		assertTrue(answer.startsWith("Dolutegravir is one of this patient's active medications"), "was: " + answer);
		List<String> about = new ArrayList<String>();
		for (String line : answer.split("\n")) {
			if (line.contains("active order Isoniazid / Rifapentine")) {
				about.add(line);
			}
		}
		assertEquals(2, about.size(), "two lines about the one order, was: " + answer);
		assertTrue(about.get(0).contains("The interacting substance is Rifapentine.")
				!= about.get(1).contains("The interacting substance is Rifapentine."), "one is about rifapentine: " + answer);
		assertTrue(about.get(0).contains("The interacting substance is Isoniazid.")
				!= about.get(1).contains("The interacting substance is Isoniazid."), "the other about isoniazid: " + answer);
	}

	@Test
	public void aSingleFindingAboutACombinationOrderIsNotGivenTheClause() {
		PatientChart chart = ask("Can I give her warfarin?",
			isoniazidRifapentine(),
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin 81mg", "aspirin"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, chart.getText());
		for (String line : answer.split("\n")) {
			if (line.contains("active order Aspirin")) {
				assertTrue(!line.contains("The interacting substance is"),
					"one finding about an order needs nothing telling it apart: " + answer);
			}
		}
	}

	/** As the 3.7.1 standalone records it: a concept named only "Isoniazid / Rifapentine", mapped to rifapentine's
	 *  ATC code and isoniazid's combination code. */
	private static PatientClinicalContext.ActiveDrugOrder isoniazidRifapentine() {
		return DrugReferenceTestSupport.activeOrder("order-hp", "Isoniazid / Rifapentine",
			DrugReferenceTestSupport.set("Isoniazid / Rifapentine"), DrugReferenceTestSupport.set("J04AB05", "J04AC51"));
	}

	private static PatientChart ask(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			codes.addAll(order.getAtcCodes());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(names), codes, null, null, Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}
}
