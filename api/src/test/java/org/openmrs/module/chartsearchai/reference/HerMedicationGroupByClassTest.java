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
 * A group of his own medications named by a drug class the module already resolves to the drug data's codes — "his
 * antifungals", "his statins", "his blood thinners" — is answered over that group as "his ARVs" is, and a group of his it
 * cannot resolve — "his BP meds", "his antihypertensives" — is said to be unresolved, never answered with his whole
 * screen (ADR Decision 631). On the demo replica each of those wordings was given every finding of his screen, for all 8
 * patients asked.
 */
public class HerMedicationGroupByClassTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aClassOfHisMedicationsAskedAgainstAnotherGroupIsAnsweredBetweenThem() {
		PatientChart chart = ask("Do his antifungals interact with his ARVs?");
		String answer = chart.getModuleAnswer();

		assertNotNull(answer);
		assertEquals("This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir) and antifungals in WHO ATC antifungal groups "
				+ "(Fluconazole): what"
				+ " the interaction check relates between them:", answer.split("\n")[0]);
		assertFalse(answer.contains("Simvastatin"), "his statin is in neither group: " + answer);
		assertTrue(!chart.getFindingsBearingOnTheQuestion().isEmpty() && chart.getFindingsBearingOnTheQuestion().stream()
				.allMatch(d -> d.contains("Fluconazole")), "its chips are what it states: "
						+ chart.getFindingsBearingOnTheQuestion());
	}

	@Test
	public void aClassOfHisMedicationsAskedAboutAloneIsAnsweredOverIt() {
		String answer = ask("Do his statins interact with anything?").getModuleAnswer();

		assertNotNull(answer);
		assertEquals("This patient's statins in ATC group C10AA (Simvastatin): what the interaction check relates them to:", answer.split("\n")[0]);
		for (String pair : new String[] { "\nRitonavir interacts with active order Simvastatin — Major.",
				"\nFluconazole interacts with active order Simvastatin — Major.",
				"\nNevirapine interacts with active order Simvastatin — Moderate." }) {
			assertTrue(answer.contains(pair), "states" + pair + ": " + answer);
		}
		assertFalse(answer.contains("Nevirapine interacts with active order Lopinavir"), "no pair outside his statins: " + answer);
	}

	@Test
	public void aClassNoneOfHisOrdersIsInIsSaidToBeAbsent() {
		PatientChart chart = ask("Any interactions with his blood thinners?");

		assertEquals("No blood thinner in ATC group B01A is among this patient's active medications the drug data identifies.",
			chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion().isEmpty(), "no chip of his screen: "
				+ chart.getFindingsBearingOnTheQuestion());
	}

	@Test
	public void aGroupOfHisMedicationsTheModuleCannotResolveIsSaidToBeUnresolvedAndNeverScreened() {
		for (String[] asked : new String[][] { { "Do his antihypertensives interact with anything?", "antihypertensives" },
				{ "Any interactions with his BP meds?", "BP meds" }, { "Do his painkillers interact with his ARVs?", "painkillers" },
				{ "Do his vaccines interact with his other medications?", "vaccines" },
				{ "Any interactions with his heart medications?", "heart medications" } }) {
			PatientChart chart = ask(asked[0]);

			assertEquals("This check cannot tell which of this patient's active medications are the \"" + asked[1] + "\" the "
					+ "question names. Ask about a drug by name to have it checked.", chart.getModuleAnswer(), asked[0]);
			assertTrue(chart.getFindingsBearingOnTheQuestion().isEmpty(), asked[0] + " — no chip of his screen: "
					+ chart.getFindingsBearingOnTheQuestion());
		}
	}

	/** Two classes of his are answered alike in either order (ADR Decision 449), and "ART" is his antiretrovirals. */
	@Test
	public void twoClassesOfHisAreAnsweredInOneOrderAndHisARTIsHisAntiretrovirals() {
		String lead = "This patient's antifungals in WHO ATC antifungal groups (Fluconazole) and statins in ATC group C10AA "
				+ "(Simvastatin): what the interaction check relates "
				+ "between them:";
		assertEquals(lead, ask("Do his statins interact with his antifungals?").getModuleAnswer().split("\n")[0]);
		assertEquals(lead, ask("Do his antifungals interact with his statins?").getModuleAnswer().split("\n")[0]);
		assertEquals(lead, ask("Can you check his statins and antifungals for interactions?").getModuleAnswer().split("\n")[0]);
		assertEquals("This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the interaction check "
				+ "relates them to:", ask("Any interactions with his ART?").getModuleAnswer().split("\n")[0]);
		assertEquals("No penicillin is among this patient's active medications the drug data identifies.",
			ask("Do his penicillins interact with anything?").getModuleAnswer());
		String two = "This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir) and statins in ATC group C10AA "
				+ "(Simvastatin): what the "
				+ "interaction check relates between them:";
		assertEquals(two, ask("Do his statins and ARVs interact?").getModuleAnswer().split("\n")[0]);
		assertEquals(two, ask("Do his ARVs and statins interact?").getModuleAnswer().split("\n")[0]);
		assertEquals("No TB drug is among this patient's active medications the drug data identifies.",
			ask("Do his TB and ART interact?").getModuleAnswer());
		for (String question : Arrays.asList("Do his ART and TB interact?", "Is it safe to give her ARVs and TB drugs together?",
			"Do his TB drugs and ARVs interact?")) {
			assertEquals("No TB drug is among this patient's active medications the drug data identifies.",
				ask(question).getModuleAnswer(), question);
		}
		for (String question : Arrays.asList("Do his steroids interact with his corticosteroids?",
			"Do his corticosteroids interact with his steroids?")) {
			assertEquals("No corticosteroid in WHO ATC corticosteroid groups is among this patient's active medications the drug data "
					+ "identifies.",
				ask(question).getModuleAnswer(), question);
		}
		for (String question : Arrays.asList("Do his ARVs and any other meds interact?",
			"Do his ARVs interact with his home medications?", "Can you check his ARVs for interactions?",
			"Does Mary have any interactions with her ARVs?")) {
			assertEquals("This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the interaction check "
					+ "relates them to:", ask(question).getModuleAnswer().split("\n")[0], question);
		}
		for (String question : Arrays.asList("Are his ARVs safe with each other?", "Are his ARVs compatible with each other?",
			"Do his current ARVs interact with each other?",
			"His ARVs, do they interact with each other?")) {
			assertEquals("This patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the interaction check "
					+ "relates among them:", ask(question).getModuleAnswer().split("\n")[0], question);
		}
		// A word narrowing or moving a group leaves a group the module cannot resolve, never the group without it.
		for (String[] asked : new String[][] { { "Did his previous ARVs interact with his TB drugs?", "previous ARVs" },
				{ "Do his inhaled steroids interact with anything?", "inhaled steroids" },
				{ "Do his TB antibiotics interact with his ARVs?", "TB antibiotics" },
				{ "Does his steroid inhaler interact with his ARVs?", "steroid inhaler" },
				{ "Do his antibiotic eye drops interact with his ARVs?", "antibiotic eye drops" } }) {
			assertEquals("This check cannot tell which of this patient's active medications are the \"" + asked[1] + "\" the "
					+ "question names. Ask about a drug by name to have it checked.", ask(asked[0]).getModuleAnswer(), asked[0]);
		}
		// However the two are asked of together (ADR Decision 630's group answer, kept).
		for (String question : Arrays.asList("Are his statins safe to give with his ARVs?", "Are his statins compatible with his "
				+ "ARVs?", "Can his statins be taken together with his ARVs?", "Do his statins interact with any of his ARVs?",
			"Is it safe to take his statins alongside his ARVs?", "Do Bob's statins interact with his ARVs?")) {
			String answer = ask(question).getModuleAnswer();
			assertEquals(two, answer == null ? null : answer.split("\n")[0], question);
		}
		// A group his question cannot resolve, joined to one it can, is said to be that whichever comes first.
		for (String question : Arrays.asList("Any interactions between his statins and BP meds?",
			"Any interactions between his BP meds and statins?", "Do his statins interact with his BP meds?")) {
			assertEquals("This check cannot tell which of this patient's active medications are the \"BP meds\" the question "
					+ "names. Ask about a drug by name to have it checked.", ask(question).getModuleAnswer(), question);
		}
	}

	/**
	 * A question naming something beside a group of his — a class proposed, something the data does not recognise — or
	 * asking of anything but their interactions is not answered over the group (ADR Decision 631): each is what it was
	 * before a class of his was a group.
	 */
	@Test
	public void aQuestionAskingOfMoreThanTheInteractionsOfAGroupOfHisIsNotAnsweredOverIt() {
		for (String question : Arrays.asList("Can I add an NSAID to his blood thinners safely?",
			"Is it safe to give him an NSAID with his BP meds?", "Does grapefruit juice interact with his statins?",
			"Is klarizom safe with his statins?", "Is it safe to give him a statin with his ARVs?",
			"Why were his statins stopped?", "Is his statin dose safe?", "When were his BP meds last changed?",
			"Any problems with his vaccinations?", "Should I stop his statins because of the muscle pain?",
			"Is it safe to give her statins with her ARVs?", "Are his ARVs safe with antibiotics?",
			"Is it safe to give her antibiotics with her ARVs?",
			"Is it safe to restart her statins with her ARVs?", "Is it safe to restart his statins with his ARVs?",
			"Do his statins interact with food?", "Any interactions with his own medications?",
			"Does grapefruit clash with his statins?", "Do klarizom and his statins interact?",
"Do grapefruit & his statins interact?",
			"Does grapefruit taken with his statins interact?", "Do his statins interact with the drugs I am about to prescribe?",
			"Do over-the-counter drugs and his statins interact?", "Do grapefruit/his statins interact?",
			"Is grapefruit alongside his statins a problem, do they interact?", "Do his statins interact with any food?",
			"Do his statins interact with each other and with grapefruit?", "Do his statins and over-the-counter drugs interact?",
			"Any interactions between his statins, TB drugs or grapefruit?",
			"Any interactions with his blood thinners and vitamin K rich foods?",
			"Any interactions between his blood thinners and an NSAID?",
			"Is there an interaction risk if NSAIDs are added to his blood thinners?",
			"Does a glass of grapefruit juice interact with his statins?")) {
			String answer = ask(question).getModuleAnswer();
			assertFalse(answer != null && (answer.startsWith("This patient's ") || answer.matches("(?s)No [^\n]* is among this patient's active medications.*")
					|| answer.startsWith(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING)),
				question + " is no question of a group of his alone: " + answer);
		}
	}

	/** Something the data does not recognise named before a group of his is said unchecked, and his screen is not run
	 *  beside it (ADR Decisions 183, 631). */
	@Test
	public void anUnrecognisedNameBeforeAGroupOfHisRunsNoScreen() {
		for (String question : Arrays.asList("Do klarizom and his ARVs interact?",
			"Is it safe to take klarizom and his ARVs together?")) {
			PatientChart chart = ask(question);
			assertFalse(chart.screenedHerOrders(), question + " — his screen was run");
			assertTrue(chart.getMappings().stream().noneMatch(
				m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())),
				question + " — no finding of his screen");
		}
	}

	private static PatientChart ask(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-flu", "Fluconazole"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir", "Fluconazole", "Simvastatin")), null, null, null, orders),
			question);
	}
}
