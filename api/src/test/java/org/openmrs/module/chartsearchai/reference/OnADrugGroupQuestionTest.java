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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Whether she is on any drug of a group this module carries — "Is she on any NSAIDs?" — is answered by the module with
 * her orders in that group (ADR Decision 298). On the 3.7.1 standalone the model told Agnes Adams, on aspirin, "No —
 * NSAIDs should not be given", and most patients "The records do not address NSAIDs".
 */
public class OnADrugGroupQuestionTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOrdersInTheGroupAreNamed() {
		for (String question : Arrays.asList("Is she on any NSAIDs?", "Is she taking any NSAIDs?",
				"Does she take any NSAIDs?", "Is she currently on an NSAID?")) {
			assertEquals("Yes — 2 of this patient's active medication orders are in the drug group this module carries as "
					+ "NSAID: Advil 400mg and Aspirin 81mg.", ask(question,
				DrugReferenceTestSupport.activeOrder("order-ibu", "Advil 400mg", "Ibuprofen"),
				DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
				DrugReferenceTestSupport.activeOrder("order-metformin", "Metformin", "Metformin")), question);
		}
	}

	@Test
	public void noneOfHerOrdersInTheGroupIsSaidSo() {
		assertEquals("None of this patient's active medication orders is in the drug group this module carries as NSAID.",
			ask("Is he on any NSAIDs?", DrugReferenceTestSupport.activeOrder("order-apap", "Acetaminophen", "Acetaminophen"),
				DrugReferenceTestSupport.activeOrder("order-metformin", "Metformin", "Metformin")));
	}

	@Test
	public void aClassNoGroupCarriesIsNotThisQuestion() {
		assertNull(ask("Is she on any antibiotics?",
			DrugReferenceTestSupport.activeOrder("order-metformin", "Metformin", "Metformin")));
	}

	/**
	 * Whether she is on a blood thinner is answered with her orders in the ATC group that class IS, B01A (ADR Decision 368):
	 * on the 3.7.1 standalone "Is she on any blood thinners?" went to the model for every patient, which told Sarah Taylor,
	 * on heparin, "No blood thinner is recorded", and Kenneth Hernandez "Yes — the patient is on Salicylic acid".
	 */
	@Test
	public void whetherSheIsOnABloodThinnerIsAnsweredWithHerOrdersInItsAtcGroup() {
		for (String question : Arrays.asList("Is she on any blood thinners?", "Is he on a blood thinner?")) {
			assertEquals("Yes — 2 of this patient's active medication orders are in the ATC group B01A (blood thinner): "
					+ "Warfarin 5mg and Aspirin 81mg.", ask(question,
						DrugReferenceTestSupport.activeOrder("order-war", "Warfarin 5mg", "Warfarin"),
						DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
						DrugReferenceTestSupport.activeOrder("order-met", "Metformin", "Metformin")), question);
			assertEquals("None of this patient's active medication orders is in the ATC group B01A (blood thinner).",
				ask(question, DrugReferenceTestSupport.activeOrder("order-sal", "Salicylic acid", "Salicylic acid")), question);
		}
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		List<PatientClinicalContext.ActiveDrugOrder> list = new ArrayList<PatientClinicalContext.ActiveDrugOrder>(
				Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null, null, null, list),
			question).getModuleAnswer();
	}
}
