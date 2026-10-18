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
 * Whether one drug is a problem beside a group of her medications is answered as whether it interacts with her medications
 * (ADR Decision 271). On the 3.7.1 standalone a patient on a rifampicin-based TB regimen asked "Is there any problem
 * combining her TB drugs with fluconazole?" and the model opened "No — Fluconazole should not be combined with rifampin",
 * a "No" that reads backwards to "is there any problem?" — where the run before it had read "Yes, there are problems". The
 * group named no drug, so no shape held the question and the model's lead decided it. Over the shipped knowledge base.
 */
public class DrugBesideHerMedicationGroupTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugBesideAGroupOfHerMedicationsIsAnsweredAsWhetherItInteractsWithThem() {
		String[][] asked = { { "Is there any problem combining her TB drugs with fluconazole?", "TB drugs" },
			{ "Does fluconazole interact with her TB drugs?", "TB drugs" },
			{ "Are there any issues with giving fluconazole with her TB treatment?", "TB treatment" } };
		for (String[] each : asked) {
			String question = each[0];
			String answer = ask(question);
			assertNotNull(answer, question + ": the module answers");
			// Under the lead naming the group, which no drug name identifies (ADR Decision 313).
			assertTrue(answer.startsWith(groupLead(each[1], "Fluconazole")
					+ "Yes — Fluconazole interacts with this patient's active medications."),
				question + " was: " + answer);
			assertTrue(answer.contains("\nFluconazole interacts with active order Rifampicin (rifampin) — Major."),
				question + " was: " + answer);
			assertTrue(!answer.contains("No —"), question + " was: " + answer);
		}
	}

	/**
	 * Whether one drug changes an effect of a group of her medications is answered as ADR Decision 249 answers it of one of
	 * her drugs, with the finding and neither "Yes" nor "No" (ADR Decision 281). On the 3.7.1 standalone Ruth, on lopinavir
	 * with ritonavir and nevirapine, asked "Will rifampicin reduce the effect of her ARVs?"; one run of the model answered
	 * "Yes, rifampicin could reduce the effect of her ARVs", the next, its prompt moved by Decision 276, "No, rifampicin should
	 * not be used" — a "No" to whether it reduces their effect.
	 */
	@Test
	public void whetherADrugChangesAnEffectOfAGroupOfHerMedicationsIsAnsweredWithTheFindingAndNeitherYesNorNo() {
		String[][] asked = { { "Will rifampicin reduce the effect of her ARVs?", "ARVs" },
			{ "Does rifampicin affect her ARVs?", "ARVs" }, { "Will rifampicin lower her ARV levels?", "ARV" } };
		for (String[] each : asked) {
			String question = each[0];
			String answer = ask(question, DrugReferenceTestSupport.activeOrder("order-lpv", "Lopinavir / ritonavir 200mg/50mg",
				"Lopinavir / ritonavir"), DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg"));
			assertNotNull(answer, question + ": the module answers");
			assertTrue(answer.startsWith(groupLead(each[1], "Rifampicin (rifampin)")
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"),
				question + " was: " + answer);
			assertTrue(answer.contains("\nRifampicin (rifampin) interacts with active order Nevirapine — Major."),
				question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), question + " was: " + answer);
		}
	}

	private static String groupLead(String group, String drug) {
		return DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING + group + DrugReferenceInjector.GROUP_NOT_IDENTIFIED_CLOSING
				+ drug + " against all of them.\n";
	}

	private static String ask(String question) {
		return ask(question, DrugReferenceTestSupport.activeOrder("order-rhze",
			"Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg"));
	}

	private static String ask(String question, PatientClinicalContext.ActiveDrugOrder... given) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(given);
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(35, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
