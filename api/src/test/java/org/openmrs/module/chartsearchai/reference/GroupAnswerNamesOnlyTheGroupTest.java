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
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A finding stating one mechanism over several of his orders (ADR Decision 99) is stated, in an answer about a group of
 * his medications, over only the orders that group relates — its line and its chip (ADR Decision 633). On the demo
 * replica, patient dd5558ed, on efavirenz, an RHZE combination and co-trimoxazole, "Any interactions with his TB
 * treatment?" was answered "Efavirenz interacts with active order Rifampicin isoniazid pyrazinamide and ethambutol (3
 * orders) and Sulfamethoxazole (5 orders) — Moderate.": his co-trimoxazole, no TB drug, named in an answer about his TB
 * drugs. 104 of the 187 group answers of the replica sweeps named several orders in one line. Over these orders, before the
 * change, "Any interactions between his ARVs and TB drugs?" read "Efavirenz interacts with active order Lamivudine,
 * Pyrazinamide, Sulfamethoxazole, Zidovudine, Ethambutol and Isoniazid".
 */
public class GroupAnswerNamesOnlyTheGroupTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aFindingMergingSeveralOfHisOrdersNamesOnlyThoseTheGroupsRelate() {
		PatientChart chart = ask("Any interactions between his ARVs and TB drugs?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer);
		// one mechanism of efavirenz's over his lamivudine, zidovudine, co-trimoxazole and three TB constituents: his TB drugs
		// alone are between the two groups
		assertTrue(answer.contains("\nEfavirenz interacts with active order Pyrazinamide, Ethambutol and Isoniazid — Moderate."),
			answer);
		assertFalse(answer.contains("Sulfamethoxazole") || answer.contains("Lamivudine,"), "nothing outside the groups: "
				+ answer);
		assertTrue(chart.getFindingsBearingOnTheQuestion().stream().noneMatch(d -> d.contains("Sulfamethoxazole")),
			"nor in its chips: " + chart.getFindingsBearingOnTheQuestion());
		// and the chip published for the merged finding is the one stated (LlmInferenceService reads it by its detail)
		assertTrue(chart.getFindingsAsStated().entrySet().stream().anyMatch(e -> e.getKey().contains("Sulfamethoxazole")
				&& !((SafetyWarning) e.getValue()).getDetail().contains("Sulfamethoxazole")
				&& chart.getFindingsBearingOnTheQuestion().contains(((SafetyWarning) e.getValue()).getDetail())),
			String.valueOf(chart.getFindingsAsStated().keySet()));
	}

	@Test
	public void aGroupAskedAboutAgainstAnythingKeepsWhatItsOwnMembersRelateTo() {
		String answer = ask("Any interactions with his TB treatment?").getModuleAnswer();

		assertNotNull(answer);
		assertTrue(answer.contains("\nEfavirenz interacts with active order Pyrazinamide, Ethambutol and Isoniazid — Moderate."),
			answer);
		// his rifampicin against his co-trimoxazole is his TB treatment's own interaction, and stays
		assertTrue(answer.contains("\nRifampicin (rifampin) interacts with active order Sulfamethoxazole and Trimethoprim"),
			answer);
	}

	/** One drug beside a group of his: a finding merging his lopinavir and his fluconazole is stated over his lopinavir,
	 *  never dropped — a member's subject is the merged finding's (ADR Decision 633). */
	@Test
	public void aDrugBesideAGroupKeepsTheMemberOfAMergedFindingInTheGroup() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-flu", "Fluconazole"));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir", "Fluconazole")), null, null, null, orders),
			"Any interactions between his ARVs and methadone?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer);
		assertTrue(answer.contains("\nMethadone interacts with active order Lopinavir — Major."), answer);
		assertFalse(answer.contains("Fluconazole"), answer);
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o1", "Efavirenz"),
			DrugReferenceTestSupport.activeOrder("o2", "Lamivudine / zidovudine"),
			DrugReferenceTestSupport.activeOrder("o3", "Rifampicin isoniazid pyrazinamide and ethambutol"),
			DrugReferenceTestSupport.activeOrder("o4", "Trimethoprim and sulfamethoxazole"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Efavirenz", "Lamivudine / zidovudine", "Rifampicin isoniazid pyrazinamide and ethambutol",
					"Trimethoprim and sulfamethoxazole")), null, null, null, orders), question);
	}
}
