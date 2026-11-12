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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * A question whether one group of her own medications interacts with another — "Does her TB treatment interact with her
 * ARVs?" — was a screen of her medications, which states every pair (ADR Decision 221); since ADR Decision 630 it is
 * answered over the groups it names. On the public
 * demo's data the model answered it "The records do not address whether her TB treatment interacts with her ARVs", for a
 * patient whose nevirapine the data rates Major against her rifampicin regimen. The groups are never resolved to drugs:
 * the screen covers every pair of hers, those between the two groups among them.
 */
public class MedicationGroupsScreenContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	/**
	 * Since ADR Decision 630 (the owner's direction that an answer carries nothing beyond what was asked, superseding the
	 * screen Decisions 221 and 572 gave): two groups asked about are answered over those two groups, what the check relates
	 * between them, and their chips are what the answer states.
	 */
	@Test
	public void aQuestionWhetherTwoGroupsOfHerMedicationsInteractIsAnsweredOverThoseGroups() {
		for (String question : Arrays.asList("Does her TB treatment interact with her ARVs?",
			"Do her TB drugs interact with her antiretrovirals?", "Any interactions between her ARVs and TB drugs?")) {
			PatientChart chart = ask(question);
			String answer = chart.getModuleAnswer();

			assertNotNull(answer, "the module answers " + question);
			// The groups in one order, whatever order the question names them in (ADR Decision 449).
			String lead = "This patient's antiretrovirals (Nevirapine) and TB drugs (Isoniazid / pyrazinamide / rifampin): ";
			assertEquals(lead + "what the interaction check relates between them:", answer.split("\n")[0], question);
			assertTrue(answer.matches("(?s).*\nNevirapine interacts with active order [^\n]*[Rr]ifamp[^\n]* — Major\\..*"),
				question + " states the pair between the two groups: " + answer);
			assertTrue(!chart.getFindingsBearingOnTheQuestion().isEmpty() && chart.getFindingsBearingOnTheQuestion().stream()
					.allMatch(d -> d.startsWith("Nevirapine") || d.contains("Nevirapine")),
				question + " — its chips are the findings it states: " + chart.getFindingsBearingOnTheQuestion());
		}
	}

	/** One group asked about against anything: what the check relates its members to (ADR Decision 630, superseding 572). */
	@Test
	public void aQuestionWhetherOneGroupOfHerMedicationsInteractsIsAnsweredOverThatGroup() {
		for (String question : Arrays.asList("Any interactions with her TB treatment?",
			"Are there any drug interactions with her TB medications?")) {
			String answer = ask(question).getModuleAnswer();

			assertNotNull(answer, question);
			assertEquals("This patient's TB drugs (Isoniazid / pyrazinamide / rifampin): what the interaction check relates them "
					+ "to:", answer.split("\n")[0], question);
			assertTrue(answer.contains("\nNevirapine interacts with active order"), question + ": " + answer);
		}
		assertNull(ask("Any interactions with her diet?").getModuleAnswer(), "her diet is no group of her medications");
	}

	/** One group among itself: what the check relates among its members — here none, her one TB tablet's own substances being
	 *  no pair of each other (ADR Decisions 622, 630). */
	@Test
	public void aGroupAskedAboutAmongItselfIsAnsweredAmongItsMembers() {
		for (String question : Arrays.asList("Any interactions between her TB drugs?",
			"Do any of her TB medications interact with each other?", "Do any of her TB drugs interact with each other?",
			"Do her TB drugs interact with each other?")) {
			assertEquals("The interaction check relates none of this patient's TB drugs (Isoniazid / pyrazinamide / rifampin) to "
					+ "each other.", ask(question).getModuleAnswer(), question);
		}
	}

	/** "ARVs" is no word of medication the router reads a screen by, and the question is still about that group (ADR
	 *  Decision 630): on the demo replica "Do any of his ARVs interact with each other?" kept his whole screen. */
	@Test
	public void herARVsAmongThemselvesAreAnsweredAmongThem() {
		for (String question : Arrays.asList("Do any of her ARVs interact with each other?", "Do her ARVs clash with each other?")) {
			assertEquals("The interaction check relates none of this patient's antiretrovirals (Nevirapine) to each other.",
				ask(question).getModuleAnswer(), question);
		}
	}

	/**
	 * Her ARVs asked whether they interact, with each other or at all, are answered over what the check relates among them,
	 * each finding stated — a combination order's every substance among them (ADR Decision 630). On the demo replica patient
	 * dd553355, on Retrovir, Lopinavir / ritonavir and Didanosine, "Do any of his ARVs interact with each other?" was given his
	 * whole screen: no shape read "his ARVs" as his medications, so the check screened none of his orders and the model's
	 * reading of the question ran the whole medication check instead. And lopinavir, which the data files under no
	 * antiretroviral code of its own, was no member: "Lopinavir interacts with active order Didanosine" was left out.
	 */
	@Test
	public void herARVsAskedWhetherTheyInteractAreAnsweredOverWhatTheCheckRelatesAmongThem() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-azt", "Retrovir", "Zidovudine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-ddi", "Didanosine"));
		for (String[] asked : new String[][] { { "Do any of his ARVs interact with each other?", "among them" },
				{ "Do his ARVs clash with each other?", "among them" }, { "Do his ARVs interact?", "them to" } }) {
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
					Arrays.asList("Zidovudine", "Lopinavir", "Ritonavir", "Didanosine")), null, null, null, orders), asked[0]);
			String answer = chart.getModuleAnswer();

			assertNotNull(answer, asked[0]);
			assertEquals("This patient's antiretrovirals (Retrovir, Lopinavir / ritonavir and Didanosine): what the interaction "
					+ "check relates " + asked[1] + ":", answer.split("\n")[0], asked[0]);
			for (String pair : Arrays.asList("\nLopinavir interacts with active order Didanosine — Moderate.",
				"\nZidovudine interacts with active order Ritonavir — Minor.")) {
				assertTrue(answer.contains(pair), asked[0] + " states" + pair + ": " + answer);
			}
		}
	}

	/** A group none of her orders is in is said to be absent, and nothing stands in for it (ADR Decision 630). On the demo
	 *  replica a patient on no TB drug, asked "Any interactions between his TB drugs?", was given his antiretrovirals against
	 *  each other. */
	@Test
	public void aGroupNoneOfHerOrdersIsInIsSaidToBeAbsent() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"));
		for (String question : Arrays.asList("Any interactions between his TB drugs?",
			"Any interactions between his ARVs and TB drugs?")) {
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
						new LinkedHashSet<String>(Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir")), null, null, null,
						orders), question);

			assertEquals("No TB drug is among this patient's active medications the drug data identifies.",
				chart.getModuleAnswer(), question);
			assertTrue(chart.getFindingsBearingOnTheQuestion().isEmpty(), question + " — no chip of his screen: "
					+ chart.getFindingsBearingOnTheQuestion());
		}
	}

	@Test
	public void aQuestionAboutSomethingThatIsNotOneOfHerMedicationGroupsIsLeftToTheModel() {
		assertNull(ask("Does her diet interact with her ARVs?").getModuleAnswer());
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin", "Isoniazid",
				"Pyrazinamide", "Rifampin"));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(58, null,
				new LinkedHashSet<String>(Arrays.asList("Nevirapine", "Isoniazid", "Pyrazinamide", "Rifampin")), null, null,
				null, orders), question);
	}
}
