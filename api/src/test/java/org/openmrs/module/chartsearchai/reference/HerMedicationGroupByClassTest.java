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

	/**
	 * A word the data does not recognise asked "interactions between" it and a group of his, or his medications, either
	 * way round, is said unchecked, as "Does rifampcin interact with his ARVs?" is (ADR Decision 638): by the module where the
	 * data has a near name for it, after the chart's answer where it has none (ADR Decision 384). On the
	 * demo replica "Any interactions between his rifampcin and his ARVs?" reached the model, which read rifampin and was
	 * handed its findings against every order of his, his cotrimoxazole among them; "Any interactions between his ARVs and
	 * klarizom?" was given his ARVs' interactions among themselves.
	 */
	@Test
	public void anUnrecognisedNameAskedInteractionsBetweenItAndAGroupOfHisIsSaidUnchecked() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-azt", "Retrovir", "Zidovudine"),
			DrugReferenceTestSupport.activeOrder("order-ctx", "Sulfamethoxazole and Trimethoprim", "Sulfamethoxazole",
				"Trimethoprim"));
		String[][] cases = { { "Any interactions between his rifampcin and his ARVs?", "rifampcin" },
				{ "Any interactions between rifampcin and his ARVs?", "rifampcin" },
				{ "Any interactions between his ARVs and rifampcin?", "rifampcin" },
				{ "Are there any interactions between rifampcin and his medications?", "rifampcin" },
				{ "Is there an interaction between klarizom and his ARVs?", "klarizom" },
				{ "Any interactions between his ARVs and klarizom?", "klarizom" } };
		for (String[] c : cases) {
			for (boolean everyOrderARecord : new boolean[] { false, true }) {
				PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
						.injectRecords(everyOrderARecord ? DrugReferenceTestSupport.chartOf(
							DrugReferenceTestSupport.drugOrderRecord(1, "order-lpvr", "Lopinavir / ritonavir"),
							DrugReferenceTestSupport.drugOrderRecord(2, "order-azt", "Retrovir"),
							DrugReferenceTestSupport.drugOrderRecord(3, "order-ctx", "Sulfamethoxazole and Trimethoprim"))
								: DrugReferenceTestSupport.oneRecordChart(),
							DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(Arrays.asList("Lopinavir", "Ritonavir",
								"Retrovir", "Zidovudine", "Sulfamethoxazole", "Trimethoprim")), null, null, null, orders), c[0]);
				String where = c[0] + (everyOrderARecord ? " (every order a record)" : "");
				// "rifampcin" has a near name, so the module answers; "klarizom" none, so its statement follows the chart's answer
				String statement = "rifampcin".equals(c[1]) ? chart.getModuleAnswer() : chart.getUncheckedWordStatement();
				assertNotNull(statement, where + " — not said unchecked");
				assertTrue(statement.contains("No interaction check was run: \"" + c[1]
						+ "\" is not a drug name the interaction data recognises."), where + ": " + statement);
				assertFalse(chart.screenedHerOrders(), where + " — his screen was run");
				assertTrue(chart.getMappings().stream().noneMatch(
					m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())),
					where + " — a finding of his screen");
			}
		}
	}

	/**
	 * A group answer states every pair of its groups, whatever the cap on the screen's chips (ADR Decision 639). That cap
	 * keeps the most severe pairs of ALL her medications, so before this a group whose pairs were milder than her ten worst
	 * was answered "The interaction check relates none of this patient's antiretrovirals … to … antifungals (Fluconazole)"
	 * of a patient whose fluconazole the data relates to his nevirapine and lopinavir, and his ARVs' Moderate pair with his
	 * simvastatin went unsaid.
	 */
	@Test
	public void aGroupAnswerIsTheSameWhateverTheCapOnTheScreensChips() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid"),
			DrugReferenceTestSupport.activeOrder("order-flu", "Fluconazole"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin"),
			DrugReferenceTestSupport.activeOrder("order-aml", "Amlodipine"));
		for (String question : Arrays.asList("Do his ARVs interact with his antifungals?",
			"Do his ARVs interact with his statins?", "Are his TB drugs ok with his ARVs?",
			"Any interactions with his ARVs, statins and TB drugs?", "Any interactions with his antifungals?")) {
			String[] answers = new String[2];
			Object[] chips = new Object[2];
			for (int i = 0; i < 2; i++) {
				Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS,
					i == 0 ? String.valueOf(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS) : "100");
				PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
					DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
						Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir", "Rifampicin", "Isoniazid", "Fluconazole",
							"Simvastatin", "Amlodipine")), null, null, null, orders), question);
				answers[i] = chart.getModuleAnswer();
				chips[i] = chart.getFindingsBearingOnTheQuestion();
			}
			assertNotNull(answers[1], question);
			assertEquals(answers[1], answers[0], question + " — the cap on the screen's chips changed the group answer");
			assertEquals(chips[1], chips[0], question + " — the cap changed its chips");
		}
	}

	/**
	 * A group of medications he takes, asked of after "interact with" without "his", is his — "Do his statins interact with TB
	 * drugs?" of a patient on rifampicin and isoniazid is answered as "… with his TB drugs?" is (ADR Decision 640). Read as
	 * the drug class it names, it was answered by nothing the module states, and "Does his simvastatin interact with TB
	 * drugs?" by every interaction of his simvastatin, his amlodipine and fluconazole among them.
	 */
	@Test
	public void aGroupHeTakesAskedOfWithoutHisIsAnsweredAsHis() {
		for (String[] asked : new String[][] { { "Do his statins interact with TB drugs?", "Do his statins interact with his TB drugs?" },
				{ "Does his simvastatin interact with TB drugs?", "Does his simvastatin interact with his TB drugs?" },
				{ "Do his ARVs interact with statins?", "Do his ARVs interact with his statins?" },
				{ "Would his ARVs interact with antifungals?", "Would his ARVs interact with his antifungals?" } }) {
			PatientChart bare = askOfSeven(asked[0]);
			PatientChart his = askOfSeven(asked[1]);
			assertNotNull(his.getModuleAnswer(), asked[1]);
			assertEquals(his.getModuleAnswer(), bare.getModuleAnswer(), asked[0]);
			assertEquals(his.getFindingsBearingOnTheQuestion(), bare.getFindingsBearingOnTheQuestion(), asked[0] + " — its chips");
		}
	}

	/**
	 * "St John's wort" names a herb and no one's medications: asked beside a group of his, it is answered over that group
	 * (ADR Decision 641). Read as the possessive of a patient named John, "Does St John's wort interact with his ARVs?" was
	 * told the module cannot tell which of his medications are the ARVs and answered over all of them, his simvastatin and
	 * amlodipine among them.
	 */
	@Test
	public void aSaintsNameBeforeAHerbIsNoPatientsPossessive() {
		for (String question : Arrays.asList("Does St John's wort interact with his ARVs?",
			"Is it safe to give him St John's wort with his ARVs?", "Does St. John's Wort interact with his ARVs?",
			"Do his ARVs and St John's wort interact?")) {
			String answer = askOfSeven(question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.startsWith("St. John's Wort and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): "
					+ "what the interaction check relates between them:"), question + ": " + answer);
			assertFalse(answer.contains("Simvastatin") || answer.contains("Amlodipine"), question + ": " + answer);
		}
	}

	/**
	 * A word the data does not recognise asked of beside a group of his — after "each other or", in a clause after the group,
	 * given him with it — is said unchecked and his screen is not run for it (ADR Decision 642). Each was answered with his
	 * group's interactions, or his whole screen, and nothing of the word.
	 */
	@Test
	public void aWordAskedOfBesideAGroupOfHisInAnyShapeIsSaidUnchecked() {
		String[][] cases = { { "Do his TB drugs interact with each other or klarizom?", "klarizom" },
				{ "Do his ARVs interact with each other or with klarizom?", "klarizom" },
				{ "Can I give klarizom to him with his ARVs, any interactions?", "klarizom" },
				{ "Any interactions with his ARVs if he drinks grapefruit juice?", "grapefruit juice" },
				{ "Do his ARVs interact with anything if he drinks grapefruit juice?", "grapefruit juice" },
				{ "Do his ARVs and klarizom interact?", "klarizom" } };
		for (String[] c : cases) {
			PatientChart chart = askOfSeven(c[0]);
			String statement = chart.getModuleAnswer() != null ? chart.getModuleAnswer() : chart.getUncheckedWordStatement();
			assertNotNull(statement, c[0] + " — not said unchecked");
			assertTrue(statement.contains("No interaction check was run: \"" + c[1]
					+ "\" is not a drug name the interaction data recognises."), c[0] + ": " + statement);
			assertFalse(chart.screenedHerOrders(), c[0] + " — his screen was run");
			assertTrue(chart.getMappings().stream().noneMatch(
				m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())),
				c[0] + " — a finding of his screen");
		}
	}

	/** Two groups of his asked whether one interferes with the other are asked whether they interact, and "antituberculars"
	 *  are his TB drugs (ADR Decision 643): "Would his ARVs interfere with his antifungals?" was answered by nothing the
	 *  module states, and "Do his antiretrovirals and antituberculars interact?" with every interaction of his ARVs. */
	@Test
	public void twoGroupsOfHisAskedWhetherOneInterferesWithTheOtherAreAnsweredAsInteracting() {
		for (String[] asked : new String[][] {
				{ "Would his ARVs interfere with his antifungals?", "Would his ARVs interact with his antifungals?" },
				{ "Will his TB drugs interfere with his statins?", "Will his TB drugs interact with his statins?" },
				// and his TB drugs named "antituberculars"
				{ "Do his antiretrovirals and antituberculars interact?", "Do his ARVs and TB drugs interact?" } }) {
			PatientChart interferes = askOfSeven(asked[0]);
			PatientChart interacts = askOfSeven(asked[1]);
			assertNotNull(interacts.getModuleAnswer(), asked[1]);
			assertEquals(interacts.getModuleAnswer(), interferes.getModuleAnswer(), asked[0]);
			assertEquals(interacts.getFindingsBearingOnTheQuestion(), interferes.getFindingsBearingOnTheQuestion(), asked[0]);
		}
	}

	/**
	 * A group named after "interact with" without "his" is never his medications as a whole (ADR Decision 645): of a patient
	 * on no TB drug, "Does his fluconazole interact with TB drugs?" was answered with every interaction of his fluconazole,
	 * and is now said unchecked, as "… with antifungals?" is. Of one on ARVs, "Does fluconazole interact with ARVs?" is answered
	 * as "… with his ARVs?" is, whether or not the question names anything of his before the group.
	 */
	@Test
	public void aGroupNamedWithoutHisIsNeverHisMedicationsAsAWhole() {
		for (String question : Arrays.asList("Does his fluconazole interact with TB drugs?",
			"Does his fluconazole interact with antibiotics?")) {
			PatientChart chart = ask(question);
			String word = question.substring(question.lastIndexOf("with ") + 5, question.length() - 1);
			assertNotNull(chart.getUncheckedWordStatement(), question + ": " + chart.getModuleAnswer());
			assertTrue(chart.getUncheckedWordStatement().startsWith("No interaction check was run for \"" + word
					+ "\" and Fluconazole"), question + ": " + chart.getUncheckedWordStatement());
			assertTrue(chart.getModuleAnswer() == null, question + ": " + chart.getModuleAnswer());
		}
		for (String[] asked : new String[][] { { "Does fluconazole interact with ARVs?", "Does fluconazole interact with his ARVs?" },
				{ "Is fluconazole safe with ARVs?", "Is fluconazole safe with his ARVs?" } }) {
			PatientChart bare = askOfSeven(asked[0]);
			PatientChart his = askOfSeven(asked[1]);
			assertNotNull(his.getModuleAnswer(), asked[1]);
			assertEquals(his.getModuleAnswer(), bare.getModuleAnswer(), asked[0]);
			assertEquals(his.getFindingsBearingOnTheQuestion(), bare.getFindingsBearingOnTheQuestion(), asked[0]);
		}
		// and a group safe with a group of his proposes it, as Decision 631 reads it
		assertTrue(askOfSeven("Are his ARVs safe with antibiotics?").getModuleAnswer() == null
				|| !askOfSeven("Are his ARVs safe with antibiotics?").getModuleAnswer().startsWith("This patient's antiretrovirals"));
	}

	/**
	 * One drug beside groups of hers the module resolves is answered over the drug and those groups (ADR Decision 646): two
	 * groups, "Is fluconazole safe with his ARVs and TB drugs?"; "Is his fluconazole interacting with his ARVs?"; "Is it safe
	 * to continue his fluconazole with his ARVs?". Each was answered with every interaction of the drug, under a lead saying
	 * the check cannot tell which of his medications are the ARVs — which it can.
	 */
	@Test
	public void oneDrugBesideGroupsOfHisIsAnsweredOverThem() {
		String[][] cases = {
				{ "Is fluconazole safe with his ARVs and TB drugs?",
						"Fluconazole and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir) and TB drugs (Rifampicin "
								+ "and Isoniazid): what the interaction check relates between them:" },
				{ "Is fluconazole safe with his TB and HIV meds?",
						"Fluconazole and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir) and TB drugs (Rifampicin "
								+ "and Isoniazid): what the interaction check relates between them:" },
				{ "Is his fluconazole interacting with his ARVs?",
						"Fluconazole and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the interaction "
								+ "check relates between them:" },
				{ "Is it safe to continue his fluconazole with his ARVs?",
						"Fluconazole and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the interaction "
								+ "check relates between them:" } };
		for (String[] c : cases) {
			String answer = askOfSeven(c[0]).getModuleAnswer();
			assertNotNull(answer, c[0]);
			assertEquals(c[1], answer.split("\n")[0], c[0] + ": " + answer);
			assertFalse(answer.contains("Simvastatin") || answer.contains("Amlodipine"), c[0] + ": " + answer);
		}
		// Where the drug keeps its answer against all of them — beside another of his the module cannot resolve, or one of the
		// group itself, whose combination order's other substance would be left out — no lead says the check cannot tell
		// which are the ARVs, which it can
		for (String question : Arrays.asList("Is fluconazole safe with his ARVs and his cold medicine?",
			"Does ritonavir interact with his ARVs?")) {
			String answer = askOfSeven(question).getModuleAnswer();
			assertNotNull(answer, question);
			assertFalse(answer.contains(DrugReferenceInjector.GROUP_NOT_IDENTIFIED_OPENING + "ARVs"), question + ": " + answer);
		}
		// A drug that is one of the group is asked, with the whole order it is taken in, against the group's other members
		// (ADR Decision 648): the Major finding of lopinavir, in ritonavir's order, with his nevirapine stays
		String ritonavir = askOfSeven("Does ritonavir interact with his ARVs?").getModuleAnswer();
		assertEquals("Ritonavir (taken in Lopinavir / ritonavir) and this patient's other antiretrovirals (Nevirapine): what the "
				+ "interaction check relates between them:", ritonavir.split("\n")[0], ritonavir);
		assertTrue(ritonavir.contains("Lopinavir interacts with active order Nevirapine — Major"),
			"the Major finding of the order ritonavir is taken in: " + ritonavir);
		assertFalse(ritonavir.contains("Simvastatin") || ritonavir.contains("Amlodipine") || ritonavir.contains("Rifampicin")
				|| ritonavir.contains("Fluconazole"), ritonavir);
		// One of some groups named, against the others alone; its order named only where it carries more than the drug
		String rifampicin = askOfSeven("Does rifampicin in his TB drugs interact with his ARVs?").getModuleAnswer();
		assertEquals("Rifampicin (rifampin) and this patient's antiretrovirals (Nevirapine and Lopinavir / ritonavir): what the "
				+ "interaction check relates between them:", rifampicin.split("\n")[0], rifampicin);
		assertFalse(rifampicin.contains("Isoniazid") || rifampicin.contains("Simvastatin"), rifampicin);
		// Never "relates none" of an order whose other substance's findings were not drawn: "Is ritonavir safe with his
		// ARVs?" drew none of lopinavir's, whose Major finding with his nevirapine is the order's
		String safeWith = askOfSeven("Is ritonavir safe with his ARVs?").getModuleAnswer();
		assertNotNull(safeWith);
		assertFalse(safeWith.contains("to none of this patient's other antiretrovirals"), safeWith);
		// and, its order's other substance put in play as "Does ritonavir interact …" puts it, it is answered over the group
		// as that wording is (ADR Decision 652)
		assertEquals(ritonavir.split("\n")[0], safeWith.split("\n")[0], safeWith);
		assertTrue(safeWith.contains("Lopinavir interacts with active order Nevirapine — Major"), safeWith);
		String nevirapine = askOfSeven("Does nevirapine interact with his ARVs?").getModuleAnswer();
		assertEquals("Nevirapine and this patient's other antiretrovirals (Lopinavir / ritonavir): what the interaction check "
				+ "relates between them:", nevirapine.split("\n")[0], nevirapine);
	}

	/** A drug the question names by a word the data resolves it from, not one of its names — "alcohol", ethanol — is the
	 *  drug asked beside his group, as named alone it is (ADR Decision 647): "Any interactions between his ARVs and alcohol?"
	 *  was answered with ethanol against all his medications. */
	@Test
	public void aDrugNamedByAWordItResolvesFromIsTheDrugAskedBesideHisGroup() {
		for (String[] asked : new String[][] {
				{ "Any interactions between his ARVs and alcohol?", "Does alcohol interact with his ARVs?" },
				{ "Do his TB drugs and alcohol interact?", "Does alcohol interact with his TB drugs?" } }) {
			String plain = askOfSeven(asked[1]).getModuleAnswer();
			assertNotNull(plain, asked[1]);
			assertEquals(plain, askOfSeven(asked[0]).getModuleAnswer(), asked[0]);
		}
	}

	/** A drug beside a group of Decision 630's he has none of is told he has none, as a class he has none of is (ADR
	 *  Decision 649): "Does fluconazole interact with his TB drugs?" of a patient on no TB drug was answered with every
	 *  interaction of fluconazole. Decision 632 kept that answer because "No antibiotic …" was said of a patient on
	 *  rifampicin, which Decision 634 counts among his antibiotics. */
	@Test
	public void aDrugBesideAGroupHeHasNoneOfIsToldHeHasNone() {
		// A proposal of the drug while he is on a group he has none of keeps the proposal's own answer (ADR Decision 402)
		assertEquals(ask("Can I give him fluconazole?").getModuleAnswer(),
			ask("Can he take fluconazole while on his TB drugs?").getModuleAnswer());
		for (String[] asked : new String[][] {
				{ "Does fluconazole interact with his TB drugs?", "No TB drug is among this patient's active medications the drug "
						+ "data identifies." },
				{ "Does amoxicillin interact with his antibiotics?", "No antibiotic is among this patient's active "
						+ "medications the drug data identifies." } }) {
			PatientChart chart = ask(asked[0]);
			assertEquals(asked[1], chart.getModuleAnswer(), asked[0]);
			assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
				asked[0] + " — no chip: " + chart.getFindingsBearingOnTheQuestion());
		}
	}

	/** A group the drug asked about is one of, which he takes none of, names the drug and is no group it is asked against:
	 *  "Does rifampicin in his TB drugs interact with his ARVs?" of a patient on no TB drug is answered over his ARVs, its
	 *  Major findings with them stated; and a drug of the group keeps the duplicates of its own orders (ADR Decision 648). */
	@Test
	public void aGroupNamingTheDrugIsNoGroupItIsAskedAgainst() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-lpvr2", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-azt", "Retrovir", "Zidovudine"),
			DrugReferenceTestSupport.activeOrder("order-ctx", "Sulfamethoxazole and Trimethoprim", "Sulfamethoxazole",
				"Trimethoprim"));
		java.util.function.Function<String, String> ask = question -> DrugReferenceTestSupport.injectorWithSafety(
			new DrugReferenceService()).injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40,
				null, new LinkedHashSet<String>(Arrays.asList("Lopinavir", "Ritonavir", "Zidovudine", "Sulfamethoxazole",
					"Trimethoprim")), null, null, null, orders), question).getModuleAnswer();
		String rifampicin = ask.apply("Does rifampicin in his TB drugs interact with his ARVs?");
		assertNotNull(rifampicin);
		assertTrue(rifampicin.startsWith("Rifampicin (rifampin) and this patient's antiretrovirals ("), rifampicin);
		assertTrue(rifampicin.contains("Rifampicin (rifampin) interacts with active order Lopinavir") && rifampicin.contains(
			"— Major"), rifampicin);
		String ritonavir = ask.apply("Is ritonavir safe with his ARVs?");
		assertNotNull(ritonavir);
		assertTrue(ritonavir.contains("possible duplicate therapy"), "the duplicate of its own orders: " + ritonavir);
		assertFalse(ritonavir.contains("Sulfamethoxazole"), "his co-trimoxazole is no ARV: " + ritonavir);
	}

	/** A drug of his group is never told the check relates it to none of the group's other members where it relates it by
	 *  class or by his own repeated orders (ADR Decision 648): on the demo replica "Does lamivudine interact with his
	 *  ARVs?" was told so of a patient whose lamivudine is in the same ATC class as his stavudine and abacavir, and in two
	 *  of his orders. */
	@Test
	public void aDrugOfHisGroupRelatedByClassOrByItsOwnOrdersIsSaidSo() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o1", "Lamivudine", "Lamivudine"),
			DrugReferenceTestSupport.activeOrder("o2", "Lamivudine", "Lamivudine"),
			DrugReferenceTestSupport.activeOrder("o3", "Stavudine", "Stavudine"),
			DrugReferenceTestSupport.activeOrder("o4", "Abacavir", "Abacavir"),
			DrugReferenceTestSupport.activeOrder("o5", "Nevirapine", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("o6", "Trimethoprim and sulfamethoxazole", "Trimethoprim", "Sulfamethoxazole"));
		String answer = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Lamivudine", "Stavudine", "Abacavir", "Nevirapine", "Trimethoprim", "Sulfamethoxazole")), null,
				null, null, orders), "Does lamivudine interact with his ARVs?").getModuleAnswer();
		assertNotNull(answer);
		assertFalse(answer.contains("to none of"), answer);
		assertTrue(answer.contains("Lamivudine is in the same ATC class (J05AF) as active order Stavudine")
				&& answer.contains("Lamivudine is in the same ATC class (J05AF) as active order Abacavir")
				&& answer.contains("Lamivudine is already in active orders Lamivudine (2 orders)"), answer);
		assertFalse(answer.contains("Trimethoprim"), "his co-trimoxazole is no ARV: " + answer);
	}

	/** A drug she already takes, asked beside a group of hers it is not one of, is said to be hers there, as a drug of the
	 *  group is (ADR Decision 698): on the demo "Can she take co-trimoxazole with her ARVs?" was answered over her ARVs for
	 *  the 7 of 18 patients already on co-trimoxazole with its Minor finding alone, never that she takes it. Proposed, as the
	 *  finding that one order of hers carries the drug is raised only of a proposal (ADR Decision 129). */
	@Test
	public void aDrugSheTakesBesideAGroupItIsNotOneOfIsSaidToBeHers() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o1", "Lamivudine", "Lamivudine"),
			DrugReferenceTestSupport.activeOrder("o2", "Stavudine", "Stavudine"),
			DrugReferenceTestSupport.activeOrder("o3", "Nevirapine", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("o4", "Trimethoprim and sulfamethoxazole", "Trimethoprim", "Sulfamethoxazole"),
			DrugReferenceTestSupport.activeOrder("o5", "Warfarin", "Warfarin"));
		for (String question : Arrays.asList("Can she take co-trimoxazole with her ARVs?",
			"Is it safe to give her co-trimoxazole with her ARVs?", "Can I add co-trimoxazole to her ARVs?")) {
			String answer = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
					Arrays.asList("Lamivudine", "Stavudine", "Nevirapine", "Trimethoprim", "Sulfamethoxazole", "Warfarin")), null,
					null, null, orders), question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.startsWith("Co-trimoxazole and this patient's antiretrovirals"), question + ": " + answer);
			assertTrue(answer.contains("Sulfamethoxazole is already in active order Trimethoprim and sulfamethoxazole")
					&& answer.contains("Trimethoprim is already in active order Trimethoprim and sulfamethoxazole"),
				question + ": " + answer);
			assertTrue(answer.contains("Trimethoprim interacts with active order Lamivudine — Minor."), question + ": " + answer);
			assertFalse(answer.contains("Warfarin"), "her warfarin is no ARV: " + question + ": " + answer);
		}
	}

	/** Whether to stop a drug he is not on is answered that there is no order of it to stop, and nothing of the drug's
	 *  interactions is published beside that (ADR Decision 651): on the demo replica "Should I stop his dapsone?" of a
	 *  patient on no dapsone carried six chips of dapsone against his medications. */
	@Test
	public void stoppingADrugHeIsNotOnPublishesNoFindingOfIt() {
		PatientChart chart = askOfSeven("Should I stop his dapsone?");
		assertNotNull(chart.getModuleAnswer());
		assertTrue(chart.getModuleAnswer().contains("so there is no order of it to stop"), chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
			"no chip: " + chart.getFindingsBearingOnTheQuestion());
	}

	/** Continuing a drug he is not on, or changing its dose, publishes no finding of it either (ADR Decision 654). */
	@Test
	public void continuingOrChangingTheDoseOfADrugHeIsNotOnPublishesNoFindingOfIt() {
		for (String question : new String[] { "Should I continue his dapsone?", "Should I increase his dapsone dose?" }) {
			PatientChart chart = askOfSeven(question);
			assertNotNull(chart.getModuleAnswer(), question);
			assertTrue(chart.getModuleAnswer().startsWith("Dapsone is not one of this patient's active medications"),
				chart.getModuleAnswer());
			assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
				question + " no chip: " + chart.getFindingsBearingOnTheQuestion());
		}
	}

	/** A drug whose own order is every member of the group it is asked beside is answered over that order's substances,
	 *  and nothing of her other medications (ADR Decision 652): "Is rifampicin safe with her TB drugs?" of a patient whose
	 *  one TB-drug order is rifampicin's RHZE order was given rifampicin's order against all her medications. */
	@Test
	public void aDrugWhoseOrderIsAllOfTheGroupIsAnsweredOverItsOrder() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol", "Rifampicin",
				"Isoniazid", "Pyrazinamide", "Ethambutol"),
			DrugReferenceTestSupport.activeOrder("o-efv", "Efavirenz", "Efavirenz"),
			DrugReferenceTestSupport.activeOrder("o-war", "Warfarin", "Warfarin"));
		for (String question : Arrays.asList("Is rifampicin safe with her TB drugs?", "Does rifampicin interact with her TB drugs?")) {
			String answer = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
					Arrays.asList("Rifampicin", "Isoniazid", "Pyrazinamide", "Ethambutol", "Efavirenz", "Warfarin")), null, null,
					null, orders), question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.startsWith("Rifampicin (rifampin) (taken in Rifampicin isoniazid pyrazinamide and ethambutol) is "
					+ "this patient's only TB drug order"), question + ": " + answer);
			assertFalse(answer.contains("Efavirenz") || answer.contains("Warfarin"), question + ": " + answer);
		}
	}

	/** A duplicate-therapy question publishes only the findings its answer states (ADR Decision 654): on the 8083 rig,
	 *  answered that no two of a patient's medications duplicate each other, it carried the screen's interaction chips —
	 *  aspirin with warfarin, Major — none of which is a duplicate. */
	@Test
	public void aDuplicateTherapyQuestionPublishesOnlyWhatItsAnswerStates() {
		PatientChart chart = askOfSeven("Any duplicate therapy?");
		assertNotNull(chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
			"no chip but what the answer states: " + chart.getFindingsBearingOnTheQuestion() + " beside " + chart.getModuleAnswer());
	}

	/** A drug that is his only order in the group it is asked beside, alone in its order, is said to be that (ADR Decision
	 *  654): on the 8083 rig "Does aspirin interact with his blood thinners?" of a patient whose one blood thinner is his
	 *  aspirin went to the model, which answered with aspirin's Major interaction with his ibuprofen, no blood thinner. */
	@Test
	public void aDrugThatIsHisOnlyOrderOfTheGroupIsSaidToBe() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("o-asa", "Aspirin 81mg", "Aspirin"),
			DrugReferenceTestSupport.activeOrder("o-ibu", "Advil 400mg", "Ibuprofen"));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Aspirin", "Ibuprofen")), null, null, null, orders), "Does aspirin interact with his blood thinners?");
		assertNotNull(chart.getModuleAnswer(), "left to the model");
		assertTrue(chart.getModuleAnswer().startsWith("Acetylsalicylic acid (aspirin) is this patient's only blood thinner"),
			chart.getModuleAnswer());
		assertFalse(chart.getModuleAnswer().contains("Ibuprofen"), chart.getModuleAnswer());
		assertTrue(chart.getFindingsBearingOnTheQuestion() != null && chart.getFindingsBearingOnTheQuestion().isEmpty(),
			String.valueOf(chart.getFindingsBearingOnTheQuestion()));
	}

	/** A question naming something besides his groups — food, a remedy, a condition, a word in a drug's place — is not
	 *  answered over his groups alone (ADR Decision 656): through the service each was given the screen of his ARVs, what
	 *  it named left unsaid. Questions of his groups alone, however worded, keep it. */
	@Test
	public void aGroupQuestionNamingSomethingBesideHisGroupsIsNotAnsweredOverThem() {
		for (String question : new String[] { "Any interactions if he eats grapefruit with his ARVs?",
				"Any interactions between his ARVs and herbal medicines?", "Any interactions with his ARVs while breastfeeding?",
				"Do grapefruit & his ARVs interact?", "Do his ARVs interact with any food?",
				"Any interactions with his ARVs during pregnancy?", "Do his TB drugs and the pill interact?",
				// a class of his, Decision 631's residue
				"Do his statins interact if he drinks grapefruit juice?", "Any interactions with his antifungals while breastfeeding?" }) {
			String answer = askOfSeven(question).getModuleAnswer();
			assertTrue(answer == null || !answer.startsWith("This patient's"), question + ": " + answer);
		}
		for (String question : new String[] { "Do his ARVs interact with his TB drugs or each other?",
				"Does Mary have any interactions with her ARVs?", "Is it safe for John to take his ARVs with his TB drugs?",
				"What interactions do his ARVs have?", "Do his TB drugs interact with anything else?",
				"Do any of his ARVs interact with each other?", "Are his TB and HIV meds safe together?",
				"Do his ARVs clash with each other?" }) {
			String answer = askOfSeven(question).getModuleAnswer();
			assertTrue(answer != null && answer.startsWith("This patient's"), question + ": " + answer);
		}
	}

	/** A word the question parts from her group with punctuation is the word said unchecked, never the two as one name (ADR
	 *  Decision 657): "Any interactions with his ARVs, klarizom?" was told "arvs klarizom" is no drug name. */
	@Test
	public void aWordPartedFromHisGroupByPunctuationIsTheWordSaidUnchecked() {
		PatientChart chart = askOfSeven("Any interactions with his ARVs, klarizom?");
		String statement = chart.getModuleAnswer() != null ? chart.getModuleAnswer() : chart.getUncheckedWordStatement();
		assertNotNull(statement, "not said unchecked");
		assertTrue(statement.contains("No interaction check was run: \"klarizom\" is not a drug name the interaction data "
				+ "recognises."), statement);
	}

	/**
	 * Two groups of his asked of one another and of anything else are asked what the check relates them to (ADR Decision
	 * 658): "Any interactions between his ARVs and TB drugs, or with anything else?" was answered with what it relates
	 * between them, his simvastatin against his lopinavir left out.
	 */
	@Test
	public void twoGroupsOfHisAskedOfAnythingElseAreAnsweredWithWhatTheyAreRelatedTo() {
		String between = askOfSeven("Any interactions between his ARVs and TB drugs?").getModuleAnswer();
		assertNotNull(between);
		assertTrue(between.contains("what the interaction check relates between them:"), between);
		assertFalse(between.contains("Simvastatin"), between);
		for (String question : Arrays.asList("Any interactions between his ARVs and TB drugs, or with anything else?",
				"Do his ARVs and TB drugs interact with each other or anything else?")) {
			String answer = askOfSeven(question).getModuleAnswer();
			assertNotNull(answer, question);
			assertTrue(answer.contains("what the interaction check relates them to:"), question + " was: " + answer);
			assertTrue(answer.contains("Simvastatin"), question + " was: " + answer);
			assertTrue(answer.contains("Rifampicin"), question + " was: " + answer);
		}
		// A group he has none of is said to be, and the one he has is still asked of anything else: on the demo replica a
		// patient on no TB drug was told only that.
		String noTb = ask("Any interactions between his ARVs and TB drugs, or with anything else?").getModuleAnswer();
		assertNotNull(noTb);
		assertTrue(noTb.startsWith("No TB drug is among this patient's active medications"), noTb);
		assertTrue(noTb.contains("what the interaction check relates them to:"), noTb);
		assertTrue(noTb.contains("Simvastatin"), noTb);
		assertEquals("No TB drug is among this patient's active medications the drug data identifies.",
			ask("Any interactions between his ARVs and TB drugs?").getModuleAnswer());
	}

	private static PatientChart askOfSeven(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin"),
			DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid"),
			DrugReferenceTestSupport.activeOrder("order-flu", "Fluconazole"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin"),
			DrugReferenceTestSupport.activeOrder("order-aml", "Amlodipine"));
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(
				Arrays.asList("Nevirapine", "Lopinavir", "Ritonavir", "Rifampicin", "Isoniazid", "Fluconazole", "Simvastatin",
					"Amlodipine")), null, null, null, orders), question);
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

	/**
	 * A group of his named and not asked of is stamped so whether or not anything resolved for the question (ADR Decision
	 * 637). On the demo replica every order of his was already a record of his chart and nothing resolved for "Any
	 * interactions between his rifampcin and his ARVs?", so the injection returned before the stamp, the model read the
	 * question as one of his medications, and his whole screen — his cotrimoxazole and TB drugs among it — answered it.
	 */
	@Test
	public void aGroupOfHisNotAskedOfIsStampedWhereEveryOrderIsAlreadyARecordOfHisChart() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-lpvr", "Lopinavir / ritonavir", "Lopinavir", "Ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-azt", "Retrovir", "Zidovudine"),
			DrugReferenceTestSupport.activeOrder("order-ddi", "Didanosine"));
		for (String question : new String[] { "Any interactions between his rifampcin and his ARVs?",
				"Do his TB drugs interact with anything in his diet?", "When were his ARVs last changed?" }) {
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.chartOf(
						DrugReferenceTestSupport.drugOrderRecord(1, "order-lpvr", "Lopinavir / ritonavir"),
						DrugReferenceTestSupport.drugOrderRecord(2, "order-azt", "Retrovir"),
						DrugReferenceTestSupport.drugOrderRecord(3, "order-ddi", "Didanosine")),
						DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(Arrays.asList("Lopinavir", "Ritonavir",
							"Retrovir", "Zidovudine", "Didanosine")), null, null, null, orders), question);

			assertFalse(chart.screenedHerOrders(), question);
			assertTrue(chart.namesAGroupOfHersNotAskedOf(), question + " names his ARVs and asks nothing the module answers "
					+ "over them, so the model's reading may not run his whole screen for it");
		}
	}
}
