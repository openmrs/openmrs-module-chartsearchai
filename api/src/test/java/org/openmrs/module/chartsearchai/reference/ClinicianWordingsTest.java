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
 * Wordings a clinician types for questions the module already answers are answered as those questions are (ADR Decision
 * 391). On the 3.7.1 standalone each went to the model, which answered "Fluconazole can be given, with one caution", "The
 * records do not address …", or "No — Acetylsalicylic acid (aspirin) should not be given" of a question whether to stop it.
 * Over the shipped knowledge base.
 */
public class ClinicianWordingsTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"),
		DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz 600mg", "Efavirenz"),
		DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
		DrugReferenceTestSupport.activeOrder("order-ibu", "Ibuprofen 400mg", "Ibuprofen"),
		DrugReferenceTestSupport.activeOrder("order-dic", "Diclofenac 50mg", "Diclofenac"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void eachWordingIsAnsweredAsTheQuestionItAsks() {
		String[][] pairs = {
			{ "Check fluconazole against her meds", "Does fluconazole interact with her medications?" },
			{ "Would amiodarone be a problem with her current meds?", "Is amiodarone safe with her medications?" },
			{ "What should I watch for if I add clarithromycin?", "Which of her medications interact with clarithromycin?" },
			{ "Is there any contraindication to warfarin for her?", "Any contraindications to giving her warfarin?" },
			{ "Is co-trimoxazole safe in this patient?", "Is co-trimoxazole safe for her?" },
			{ "Will rifampicin affect her efavirenz levels?", "Does rifampicin decrease her efavirenz levels?" },
			{ "Is she on two NSAIDs?", "Is she on more than one NSAID?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
	}

	@Test
	public void whetherToStopHerDrugBeforeAnOccasionIsAnsweredAsWhetherToStopIt() {
		String canonical = ask("Can I stop her aspirin?");
		assertNotNull(canonical, "precondition: the module answers it");
		String answer = ask("Can I stop her aspirin before surgery?");
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith(canonical), "was: " + answer);
		assertTrue(!answer.contains("should not be given"), "never a proposal's verdict: " + answer);
	}

	/**
	 * A dose asked for, a timing, a reason not to give it and a condition the question names are answered as the proposal
	 * they wrap, each with what the check did not weigh (ADR Decision 394): the model answered each, among them "Ciprofloxacin
	 * can be given, with one caution" of "Can I give her ciprofloxacin two hours after her other medicines?".
	 */
	@Test
	public void aProposalWithADoseATimingOrAConditionIsAnsweredAsTheProposalSayingWhatWasNotWeighed() {
		assertEquals(ask("Is warfarin safe with her medications?"), ask("What dose of warfarin is safe with her medications?"));
		assertTrue(chart("What dose of warfarin is safe with her medications?").statesADose(), "the dose said not checked");
		assertEquals(ask("Is there any reason not to give her warfarin?"),
			ask("Is there any reason I shouldn't give her warfarin?"));
		String timed = ask("Can I give her warfarin two hours after her other medicines?");
		assertNotNull(timed, "the module answers");
		assertTrue(timed.startsWith(ask("Can I give her warfarin?")) && timed.endsWith("\n"
				+ DrugReferenceInjector.TIMING_NOT_WEIGHED), "was: " + timed);
		String condition = ask("Is warfarin safe given her kidney disease?");
		assertNotNull(condition, "the module answers");
		assertTrue(condition.startsWith(ask("Is warfarin safe for her?")) && condition.endsWith("\n"
				+ DrugReferenceInjector.CONDITION_NOT_WEIGHED_OPENING + "kidney disease)."), "was: " + condition);
	}

	/**
	 * A question about ONE drug in no wording the shapes know, made only of the words such questions are made of, is answered
	 * as whether it interacts with her medications where it says "interact", else as the drug proposed (ADR Decision 396). On
	 * the 3.7.1 standalone 15 of 30 such wordings went to the model, which cleared drugs over interactions the data never
	 * rated ("Ciprofloxacin can be given, with one caution: it has an Unknown severity interaction") and listed drugs she
	 * does not take from the reference record.
	 */
	@Test
	public void aQuestionAboutOneDrugInAnUnknownWordingIsAnsweredAsTheProposalOrTheInteraction() {
		String[][] pairs = {
			{ "Can I co-prescribe fluconazole?", "Can I give her fluconazole?" },
			{ "I want to start her on simvastatin, any issues?", "Can I give her simvastatin?" },
			{ "Planning to give her metronidazole, is that safe?", "Can I give her metronidazole?" },
			{ "She needs an antibiotic, is ciprofloxacin okay?", "Can I give her ciprofloxacin?" },
			{ "Can I give her ciprofloxacin, she is on many drugs?", "Can I give her ciprofloxacin?" },
			{ "ciprofloxacin ok?", "Can I give her ciprofloxacin?" },
			{ "Can fluconazole be given with her current treatment?", "Can I give her fluconazole?" },
			{ "Is warfarin compatible with her medications?", "Can I give her warfarin?" },
			{ "Is it safe to start rifampicin with her ARVs?", "Can I give her rifampicin?" },
			{ "Is there a drug interaction if I give her rifampicin?", "Does rifampicin interact with her medications?" },
			{ "Would giving her clarithromycin interact with anything?",
					"Does clarithromycin interact with her medications?" },
			{ "Any interaction between her meds and warfarin?", "Does warfarin interact with her medications?" },
			{ "Does she take anything that interacts with clarithromycin?",
					"Does clarithromycin interact with her medications?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		// A word outside that vocabulary is a question of its own, and the model answers it.
		for (String other : new String[] { "Is her blood pressure ok on warfarin?", "When should I give her warfarin?",
				"Did warfarin cause any problems for her?", "Is warfarin ok for her kidneys?" }) {
			assertEquals(null, ask(other), other);
		}
	}

	/**
	 * Three wordings about her medications as a whole are answered as the questions they ask (ADR Decision 397): the model
	 * answered "Should she stop any medication before starting rifampicin?" with "No — Nevirapine should not be started",
	 * "Is there anything I should not give her?" with "No — Enalaprilat should not be given: it is a drug allergen", and
	 * "Any reason not to continue her current medications?" differently for each patient.
	 */
	@Test
	public void wordingsAboutHerMedicationsAsAWholeAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Should she stop any medication before starting rifampicin?",
					"Which of her medications interact with rifampicin?" },
			{ "Do I need to stop any of her medications before starting rifampicin?",
					"Which of her medications interact with rifampicin?" },
			{ "Is there anything I should not give her?", "What drugs should I avoid in this patient?" },
			{ "What should I not give her?", "What drugs should I avoid in this patient?" },
			{ "Any reason not to continue her current medications?", "Do any of her medications need to be changed?" },
			{ "Should I stop any of her medications?", "Do any of her medications need to be changed?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
	}

	/** Five more wordings are answered as the questions they ask (ADR Decision 400); each went to the model. */
	@Test
	public void fiveMoreWordingsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Can ciprofloxacin be taken with her medicines?", "Can I give her ciprofloxacin?" },
			{ "Should I be worried about giving her fluconazole?", "Can I give her fluconazole?" },
			{ "Does ciprofloxacin clash with any of her medicines?", "Does ciprofloxacin interact with her medications?" },
			{ "Is it ok to give warfarin and metronidazole to her?", "Can I give her warfarin and metronidazole together?" },
			{ "Which of her medications should I stop if I start rifampicin?",
					"Which of her medications interact with rifampicin?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
	}

	/**
	 * Six more wordings are answered as the questions they ask (ADR Decision 402). On the 3.7.1 standalone each went to the
	 * model, which answered "Should I change her efavirenz if I start rifampicin?" with "Efavirenz can be given, with one
	 * caution" for all 15 patients, most of them not on efavirenz. "Give her fluconazole?" stays the model's: no proposal at
	 * all, in words a proposal is made of (aQuestionTheModuleDidNotResolveAsASuitabilityQuestionStillAsksTheModel).
	 */
	@Test
	public void sixMoreWordingsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Can I give her a second NSAID?", "Can I give her an NSAID?" },
			{ "What should I monitor if I give her clarithromycin?", "Which of her medications interact with clarithromycin?" },
			{ "Does fluconazole increase the levels of any of her drugs?", "Does fluconazole increase the levels of her drugs?" },
			{ "Can she take fluconazole while on her TB drugs?", "Can I give her fluconazole?" },
			{ "Should I change her efavirenz if I start rifampicin?", "Does rifampicin interact with her efavirenz?" },
			{ "Any drug allergies I should know about before prescribing?", "What are her drug allergies?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		String condition = ask("Is warfarin safe if she has kidney disease?");
		assertNotNull(condition, "the module answers");
		assertTrue(condition.startsWith(ask("Is warfarin safe for her?")) && condition.endsWith("\n"
				+ DrugReferenceInjector.CONDITION_NOT_WEIGHED_OPENING + "kidney disease)."), "was: " + condition);
	}

	/**
	 * A proposal opened by a greeting or a preamble, or asked as a recommendation or a good choice, is answered as the
	 * proposal (ADR Decision 405); and none of them, nor "What about warfarin?", is a question about the drug's record
	 * alone. On the 3.7.1 standalone "Is fluconazole a good choice for her?" was handed the model with no drug-safety
	 * material and answered "The records do not address fluconazole" for every patient.
	 */
	@Test
	public void aProposalOpenedByAGreetingOrAskedAsARecommendationIsTheProposal() {
		String canonical = ask("Can I give her warfarin?");
		assertNotNull(canonical, "precondition: the module answers it");
		for (String question : new String[] { "Please check if warfarin is safe for her",
				"Doctor here, can I prescribe warfarin for this patient?", "I am thinking of giving her warfarin, is that ok?",
				"Hi, is it ok to start warfarin?", "Would you recommend warfarin for her?", "Is warfarin a good choice for her?",
				"Warfarin for her - safe?" }) {
			assertEquals(canonical, ask(question), question);
		}
		for (String question : new String[] { "Is warfarin a good choice for her?", "What about warfarin?",
				"Would you recommend warfarin for her?" }) {
			assertTrue(!chart(question).asksOnlyAboutADrugsRecord(), "not a question about its record alone: " + question);
		}
	}

	/**
	 * A question asked in the negative is answered with no "Yes" or "No" a reader could take either way (ADR Decision 406).
	 * On the 3.7.1 standalone the model answered "Is fluconazole not safe for her?" with "Fluconazole can be given", "Can't I
	 * give her fluconazole?" with "No — Fluconazole should not be given", and "Is there no interaction between fluconazole and
	 * her meds?" with "No, there is an interaction" and "Yes, there are interactions" for different patients.
	 */
	@Test
	public void aQuestionAskedInTheNegativeIsAnsweredWithNoYesOrNo() {
		String notToGive = ask("Is there any reason not to give her warfarin?");
		assertNotNull(notToGive, "precondition: the module answers it");
		for (String question : new String[] { "Is warfarin not safe for her?", "Is warfarin unsafe for her?",
				"Can't I give her warfarin?", "Cannot I give her warfarin?", "Shouldn't I give her warfarin?" }) {
			assertEquals(notToGive, ask(question), question);
		}
		String which = ask("Which of her medications interact with warfarin?");
		assertNotNull(which, "precondition: the module answers it");
		for (String question : new String[] { "Is there no interaction between warfarin and her meds?",
				"Are there no interactions with warfarin?" }) {
			assertEquals(which, ask(question), question);
		}
		assertTrue(!notToGive.startsWith("Yes") && !notToGive.startsWith("No") && !which.startsWith("Yes")
				&& !which.startsWith("No"), "neither lead is a Yes or a No: " + notToGive + " / " + which);
	}

	/**
	 * Three drugs listed — "X, Y and Z" — are read as the list they are in a question whether it is safe to give them and in a
	 * question whether they interact (ADR Decision 408): on the 3.7.1 standalone both went to the model for every patient.
	 */
	@Test
	public void threeDrugsListedAreReadAsAList() {
		String canonical = ask("Can I give her fluconazole, metronidazole and ciprofloxacin?");
		assertNotNull(canonical, "precondition: the module answers it");
		assertEquals(canonical, ask("Is it safe to give her fluconazole, metronidazole and ciprofloxacin?"));
		String interact = ask("Do fluconazole, rifampicin and clarithromycin interact?");
		assertNotNull(interact, "the module answers");
		assertTrue(interact.contains("Fluconazole") && interact.contains("Rifampicin") && interact.contains("Clarithromycin")
				&& !interact.contains("can be given"), interact);
	}

	/**
	 * A question naming two drugs lists neither of them (ADR Decision 409): the reader of a list tries each closing fragment of
	 * the question as a proposal, and Decision 405's reading of a drug and a word saying it is safe read the fragment "safe
	 * with nevirapine" as one — so "Is fluconazole safe with nevirapine?" was answered "… The chart holds no active order for
	 * Fluconazole." on the 3.7.1 standalone, for every patient on nevirapine.
	 */
	@Test
	public void aQuestionNamingTwoDrugsListsNeither() {
		for (String question : new String[] { "Is fluconazole safe with nevirapine?", "Is warfarin safe with aspirin?" }) {
			assertTrue(chart(question).getListedDrugsWithNoActiveOrder().isEmpty(), question + " lists no drug: "
					+ chart(question).getListedDrugsWithNoActiveOrder());
		}
		assertTrue(!chart("The patient is currently on Lamivudine, is it safe to give fluconazole?")
				.getListedDrugsWithNoActiveOrder().isEmpty(), "precondition: a list before a proposal is read");
	}

	/**
	 * Questions about changing one drug for another, or choosing which of two to stop, are answered with what relates them and
	 * no choice (ADR Decision 411). On the 3.7.1 standalone "Should I reduce her simvastatin if I give clarithromycin?" was
	 * answered "No — Simvastatin should not be given" for all 15 patients, and "Which drug should I stop, aspirin or ibuprofen?"
	 * chose one.
	 */
	@Test
	public void changingOrChoosingBetweenTwoDrugsIsAnsweredWithWhatRelatesThem() {
		String[][] pairs = {
			{ "Should I reduce her simvastatin if I give clarithromycin?", "Does her simvastatin interact with clarithromycin?" },
			{ "Do I need to adjust any doses if I start rifampicin?", "Which of her medications interact with rifampicin?" },
			{ "Can she continue her aspirin with ibuprofen?", "Can she continue her aspirin and ibuprofen?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		String pair = ask("Does her aspirin interact with her ibuprofen?");
		assertNotNull(pair, "precondition: the module answers it");
		for (String question : new String[] { "Which drug should I stop, aspirin or ibuprofen?",
				"Is aspirin or ibuprofen causing the interaction?" }) {
			assertEquals(DrugReferenceInjector.COMPARES_NO_DRUGS_LEAD + "\n" + pair, ask(question), question);
		}
	}

	/**
	 * Eight more wordings about her own medications — her allergies against them, the severity of their interactions, and
	 * duplicates among them — are answered as the questions they ask (ADR Decision 412). On the 3.7.1 standalone each went to
	 * the model, which answered George Anderson's "What is the most serious interaction she has?" with "The most serious
	 * interaction recorded is an allergy to Pantoprazole".
	 */
	@Test
	public void eightMoreWordingsAboutHerOwnMedicationsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Does she have any allergy to her current medicines?", "Is she allergic to any of her medications?" },
			{ "Which of her medications is she allergic to?", "Is she allergic to any of her medications?" },
			{ "Does she have any drug allergies that conflict with her medications?",
					"Is she allergic to any of her medications?" },
			{ "Any cross-reactivity with her allergies?", "Is she allergic to any of her medications?" },
			{ "What is the most serious interaction she has?", "Are there any drug interactions with her medications?" },
			{ "What is the severity of her interactions?", "Are there any drug interactions with her medications?" },
			{ "Any contraindicated combinations?", "Are any of her medications contraindicated together?" },
			{ "Is she on two drugs that do the same thing?", "Is there any duplicate therapy?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
	}

	/**
	 * Terse and informal wordings are answered as the questions they ask (ADR Decision 413). On the 3.7.1 standalone "Is she
	 * able to take fluconazole?" was read as a question about the drug's record and answered "The records do not address
	 * fluconazole" for every patient, and "Tell me about fluconazole" with interactions with drugs she does not take.
	 */
	@Test
	public void terseAndInformalWordingsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Is she able to take warfarin?", "Can I give her warfarin?" },
			{ "Am I able to give her warfarin?", "Can I give her warfarin?" },
			{ "How safe is warfarin for her?", "Is there any reason not to give her warfarin?" },
			{ "How risky is warfarin for her?", "Is there any reason not to give her warfarin?" },
			{ "Rate the risk of giving her warfarin", "Is there any reason not to give her warfarin?" },
			{ "Warfarin and metronidazole", "Does warfarin interact with metronidazole?" },
			{ "warfarin with her meds", "Does warfarin interact with her medications?" },
			{ "DDI check warfarin", "Does warfarin interact with her medications?" },
			{ "Check DDI for warfarin", "Does warfarin interact with her medications?" },
			{ "Tell me about warfarin", "Which of her medications interact with warfarin?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		assertTrue(!chart("Is she able to take warfarin?").asksOnlyAboutADrugsRecord(), "no question about its record alone");
	}

	/**
	 * Technical, conditional and sequential wordings are answered as the questions they ask (ADR Decision 414). On the 3.7.1
	 * standalone "Can I give fluconazole after she finishes rifampicin?" was answered "No — Fluconazole should not be given"
	 * for patients on no rifampicin, and "Is fluconazole hepatotoxic with her other meds?" was read as a question about the
	 * drug's record and handed the model nothing to answer it from.
	 */
	@Test
	public void technicalConditionalAndSequentialWordingsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Can warfarin be coadministered with her drugs?", "Can I give her warfarin?" },
			{ "Is concomitant use of warfarin safe for this patient?", "Can I give her warfarin?" },
			{ "Verify warfarin is safe for her", "Can I give her warfarin?" },
			{ "Is warfarin compatible with this patient's regimen?", "Can I give her warfarin?" },
			{ "Can the patient receive warfarin?", "Can I give her warfarin?" },
			{ "Is warfarin recommended for her?", "Can I give her warfarin?" },
			{ "If I gave her warfarin, would it interact with anything?", "Does warfarin interact with her medications?" },
			{ "If I start warfarin, what interactions should I expect?", "Which of her medications interact with warfarin?" },
			{ "Is there a risk of QT prolongation if I give her warfarin?", "Is there any reason not to give her warfarin?" },
			{ "Will warfarin increase bleeding risk?", "Is there any reason not to give her warfarin?" },
			{ "Is warfarin hepatotoxic with her other meds?", "Is there any reason not to give her warfarin?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		String pair = ask("Does fluconazole interact with rifampicin?");
		assertNotNull(pair, "precondition: the module answers it");
		for (String question : new String[] { "Can I give fluconazole after she finishes rifampicin?",
				"Is it safe to give fluconazole and then rifampicin later?" }) {
			assertEquals(pair + "\n" + DrugReferenceInjector.TIMING_NOT_WEIGHED, ask(question), question);
		}
		assertTrue(!chart("Is warfarin hepatotoxic with her other meds?").asksOnlyAboutADrugsRecord(),
			"a question naming her medications is no question about the drug's record alone");
	}

	/**
	 * Colloquial and non-native wordings are answered as the questions they ask (ADR Decision 415). On the 3.7.1 standalone "Is
	 * fluconazole allowed for her?" was read as a question about the drug's record and handed the model nothing to answer it
	 * from, and twelve more went to the model.
	 */
	@Test
	public void colloquialWordingsAreAnsweredAsTheQuestionsTheyAsk() {
		String[][] pairs = {
			{ "Is warfarin allowed for her?", "Can I give her warfarin?" },
			{ "Can she be put on warfarin?", "Can I give her warfarin?" },
			{ "Can we put her on warfarin?", "Can I give her warfarin?" },
			{ "Will warfarin harm her?", "Can I give her warfarin?" },
			{ "Is it proper to give her warfarin?", "Can I give her warfarin?" },
			{ "Can the client take warfarin?", "Can I give her warfarin?" },
			{ "Can my patient take warfarin?", "Can I give her warfarin?" },
			{ "Can warfarin go with her drugs?", "Can I give her warfarin?" },
			{ "Does warfarin go well with her medicines?", "Can I give her warfarin?" },
			{ "Can warfarin be mixed with her drugs?", "Can I give her warfarin?" },
			{ "Is it safe to combine her drugs with warfarin?", "Can I give her warfarin?" },
			{ "Will warfarin disturb her medicines?", "Does warfarin interact with her medications?" },
			{ "Can I mix warfarin and metronidazole?", "Can I give her warfarin and metronidazole together?" },
			{ "Is warfarin and metronidazole ok together?", "Can I give her warfarin and metronidazole together?" },
			{ "Warfarin and metronidazole, can they be given together?",
					"Can I give her warfarin and metronidazole together?" } };
		for (String[] pair : pairs) {
			String canonical = ask(pair[1]);
			assertNotNull(canonical, "precondition: the module answers " + pair[1]);
			assertEquals(canonical, ask(pair[0]), pair[0]);
		}
		assertTrue(!chart("Is warfarin allowed for her?").asksOnlyAboutADrugsRecord(), "no question about its record alone");
	}

	/**
	 * A question whether her medications as a whole are all right is the screen of them (ADR Decision 416). On the 3.7.1
	 * standalone "Are her current medications ok?" and "Any red flags in her medications?" ran no screen, and patients with a
	 * Major interaction among their orders were told "The records do not address the safety of the current medications".
	 */
	@Test
	public void whetherHerMedicationsAreAllRightIsTheScreen() {
		String screen = ask("Are there any drug interactions with her medications?");
		assertNotNull(screen, "precondition: the module answers it");
		for (String question : new String[] { "Are her current medications ok?", "Is everything ok with her medications?",
				"Any red flags in her medications?", "Are there any issues with her treatment?", "Review her medications",
				"Which of her drugs is causing the interaction?", "Why does she have a drug warning?",
				"What does the interaction warning mean?" }) {
			String answer = ask(question);
			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.startsWith(screen.split("\n")[0]), question + " — the screen, was: " + answer);
		}
		// And never "No interactions were found" from a screen that did not run: a word in a drug's place stands it down.
		String unrecognised = ask("Are there any issues with her blorfen?");
		assertTrue(unrecognised == null || !unrecognised.contains("No interactions were found"), "was: " + unrecognised);
		String changing = ask("Do any of her medications need to be changed?");
		assertNotNull(changing, "precondition: the module answers it");
		for (String question : new String[] { "Do any of her drugs need dose adjustment?",
				"Should any of her medicines be stopped?" }) {
			assertEquals(changing, ask(question), question);
		}
	}

	/**
	 * Explaining or managing an interaction, an alternative to a drug, a safer drug of a kind and a medication review are
	 * answered with what the check found, under a lead saying what it does not do (ADR Decision 418). On the 3.7.1 standalone
	 * "How do I manage the interaction between aspirin and ibuprofen?" was answered "No — Acetylsalicylic acid (aspirin) should
	 * not be given" for patients on neither.
	 */
	@Test
	public void explainingManagingOrReplacingIsAnsweredWithWhatTheCheckFound() {
		String pair = ask("Does warfarin interact with metronidazole?");
		assertNotNull(pair, "precondition: the module answers it");
		assertEquals(pair, ask("Explain the interaction between warfarin and metronidazole"));
		for (String question : new String[] { "How do I manage the interaction between warfarin and metronidazole?",
				"What should I do about the interaction between warfarin and metronidazole?" }) {
			assertEquals(DrugReferenceInjector.MANAGES_NO_INTERACTION_LEAD + "\n" + pair, ask(question), question);
		}
		String which = ask("Which of her medications interact with warfarin?");
		assertNotNull(which, "precondition: the module answers it");
		for (String question : new String[] { "Is there a safer alternative to warfarin?", "What can I use instead of warfarin?",
				"Is there an alternative to warfarin for her?" }) {
			assertEquals(DrugReferenceInjector.CHOOSES_NO_ALTERNATIVE_LEAD + "\n" + which, ask(question), question);
		}
		// Named as hers, of a patient not on it: that she is not on it, first (ADR Decision 260).
		assertEquals(DrugReferenceInjector.CHOOSES_NO_ALTERNATIVE_LEAD + "\nWarfarin is not one of this patient's active "
				+ "medications.\n" + which, ask("Can I replace her warfarin with something safer?"));
		String choice = ask("What is the safest painkiller for her?");
		assertNotNull(choice, "precondition: the module answers it");
		assertEquals(choice, ask("What is a safer painkiller for her?"));
		String screen = ask("Review her medications");
		assertNotNull(screen, "precondition: the module answers it");
		assertEquals(screen, ask("Do a medication review"));
	}

	/**
	 * A proposal opened by a statement about her, or naming a route, a single dose or how long the drug would be given, is
	 * answered as the proposal, closing with what the check did not weigh (ADR Decision 419). On the 3.7.1 standalone "She has
	 * HIV and TB. Can I give her fluconazole?" went to the model, and "Is it safe for her to take fluconazole for 2 weeks?"
	 * closed "This check did not weigh what the drug is for (2 weeks)".
	 */
	@Test
	public void aProposalWithAStatementARouteOrADurationIsTheProposalSayingWhatWasNotWeighed() {
		String give = ask("Can I give her warfarin?");
		assertNotNull(give, "precondition: the module answers it");
		assertEquals(give + "\n" + DrugReferenceInjector.STATEMENT_NOT_WEIGHED_OPENING + "She has HIV and TB).",
			ask("She has HIV and TB. Can I give her warfarin?"));
		assertEquals(give + "\n" + DrugReferenceInjector.STATEMENT_NOT_WEIGHED_OPENING + "She is pregnant).",
			ask("She is pregnant, can I give her warfarin?"));
		String safe = ask("Is warfarin safe?");
		assertNotNull(safe, "precondition: the module answers it");
		assertEquals(safe + "\n" + DrugReferenceInjector.STATEMENT_NOT_WEIGHED_OPENING + "Patient is on ART).",
			ask("Patient is on ART. Is warfarin safe?"));
		String which = ask("Which of her medications interact with warfarin?");
		assertNotNull(which, "precondition: the module answers it");
		assertEquals(which, ask("Before I give warfarin, does she have any interactions?"));
		assertEquals(which, ask("Prior to starting warfarin, should I check anything?"));
		String iv = ask("Can I give her warfarin IV?");
		assertTrue(iv != null && iv.endsWith("\n" + DrugReferenceInjector.FORM_NOT_WEIGHED_OPENING + "iv)."), "was: " + iv);
		String oral = ask("Is oral warfarin safe for her?");
		assertTrue(oral != null && oral.endsWith("\n" + DrugReferenceInjector.FORM_NOT_WEIGHED_OPENING + "oral)."),
			"was: " + oral);
		assertEquals(ask("Is warfarin safe for her?"), ask("Is a single dose of warfarin safe for her?"));
		assertTrue(chart("Is a single dose of warfarin safe for her?").statesADose(), "the dose said not checked");
		for (String question : new String[] { "Is it safe for her to take warfarin for 2 weeks?",
				"Can I give her a short course of warfarin?" }) {
			String answer = ask(question);
			assertTrue(answer != null && answer.endsWith("\n" + DrugReferenceInjector.DURATION_NOT_WEIGHED)
					&& !answer.contains(DrugReferenceInjector.PURPOSE_NOT_WEIGHED_OPENING), question + " — was: " + answer);
		}
	}

	/**
	 * "What about fluconazole?" proposes the drug, and is answered with what the check found and no "Yes" or "No" (ADR
	 * Decision 420). On the 3.7.1 standalone "She has a fungal infection. What about fluconazole?" went to the model, which
	 * answered "Fluconazole can be given, with one caution" for five patients.
	 */
	@Test
	public void whatAboutADrugIsTheProposalWithNoYesOrNo() {
		String notToGive = ask("Is there any reason not to give her warfarin?");
		assertNotNull(notToGive, "precondition: the module answers it");
		for (String question : new String[] { "What about warfarin?", "How about warfarin?", "What about warfarin for her?",
				"What about giving her warfarin?", "How about starting warfarin?" }) {
			assertEquals(notToGive, ask(question), question);
		}
		assertEquals(notToGive + "\n" + DrugReferenceInjector.STATEMENT_NOT_WEIGHED_OPENING + "She has a fungal infection).",
			ask("She has a fungal infection. What about warfarin?"));
	}

	/**
	 * Wordings of a proposal, a screen or a pair asked as a clinician types them are answered as the question they are (ADR
	 * Decision 422). On the 3.7.1 standalone each went to the model, which answered "Fluconazole and Nevirapine can be
	 * co-administered, with one caution", "Ciprofloxacin can be given, with the following cautions" and "Yes — adding
	 * Ciprofloxacin would cause interactions" over findings with no rated severity.
	 */
	@Test
	public void aProposalAScreenOrAPairAsAClinicianTypesItIsTheQuestionItIs() {
		String together = ask("Can warfarin and ibuprofen be given together?");
		assertNotNull(together, "precondition: the module answers it");
		assertEquals(together, ask("Can warfarin and ibuprofen be co-administered?"));
		String withArt = ask("Is warfarin safe with her ART?");
		assertTrue(withArt != null && withArt.startsWith("This check cannot tell which of this patient's active medications are "
				+ "the \"ART\" the question names"), "was: " + withArt);
		assertEquals(withArt, ask("Is it ok to combine warfarin with her ART?"));
		String affects = ask("Does warfarin affect her ART levels?");
		assertTrue(affects != null && !affects.contains("\nYes") && !affects.contains("\nNo"), "was: " + affects);
		String interacts = ask("Does warfarin interact with her medications?");
		assertNotNull(interacts, "precondition: the module answers it");
		for (String question : new String[] { "warfarin + her meds", "Would adding warfarin cause any interaction?",
				"Any drug interactions I should know about before giving warfarin?" }) {
			assertEquals(interacts, ask(question), question);
		}
		assertEquals(ask("Is there any reason not to give her warfarin?"), ask("Can she continue her meds if I add warfarin?"));
		assertEquals(ask("Do I need to adjust any of her meds if I start warfarin?"),
			ask("Does warfarin require dose adjustment with her meds?"));
		String give = ask("Can I give warfarin?");
		assertEquals(give, ask("Can I give warfarin tomorrow?"));
		assertEquals(give, ask("Can I add warfarin on top of her current treatment?"));
		assertEquals(ask("Is it ok to give her warfarin?"), ask("Is it ok to give her warfarin as needed?"));
		assertEquals(ask("Warfarin ok?"), ask("Warfarin 200mg daily - ok?"));
		assertTrue(chart("Warfarin 200mg daily - ok?").statesADose(), "the dose said not checked");
	}

	/**
	 * "What's the interaction between X and Y?", "X vs Y interaction" and a drug beside her antacids are the questions their
	 * spelled-out wordings are (ADR Decision 423). On the 3.7.1 standalone the first two went to the model, which told a
	 * patient on both drugs "No — Nevirapine should not be given", and "antacids" was read as a drug name not recognised.
	 */
	@Test
	public void aContractedOrTerseWordingOfAPairOrAGroupIsTheQuestionItIs() {
		String pair = ask("What is the interaction between warfarin and ibuprofen?");
		assertNotNull(pair, "precondition: the module answers it");
		for (String question : new String[] { "What's the interaction between warfarin and ibuprofen?",
				"warfarin vs ibuprofen interaction", "Interaction between warfarin and ibuprofen?" }) {
			assertEquals(pair, ask(question), question);
		}
		String antacids = ask("Is warfarin safe with her antacids?");
		assertTrue(antacids != null && antacids.startsWith("This check cannot tell which of this patient's active medications are "
				+ "the \"antacids\" the question names"), "was: " + antacids);
	}

	/**
	 * Asking why a drug is a problem, what its risk is, or about a pair in a terse or rated wording is answered with what the
	 * check found (ADR Decision 424). On the 3.7.1 standalone each went to the model, which answered "Fluconazole can be
	 * given, with one caution" to "What's the risk of giving her fluconazole?" and "Why can't I give her fluconazole?", "Fluconazole
	 * can be used with Nevirapine" to "Is fluconazole ok to use with nevirapine?", and "No — Rifampicin should not be given" to
	 * "Is the interaction between rifampicin and nevirapine major?" of patients on both.
	 */
	@Test
	public void whyARiskOrATerseOrRatedPairIsAnsweredWithWhatTheCheckFound() {
		String notToGive = ask("Is there any reason not to give her warfarin?");
		assertNotNull(notToGive, "precondition: the module answers it");
		for (String question : new String[] { "What's the risk of giving her warfarin?", "What's her risk with warfarin?",
				"Explain why warfarin is a problem for her", "Why can't I give her warfarin?",
				"What's wrong with giving her warfarin?" }) {
			assertEquals(notToGive, ask(question), question);
		}
		String screen = ask("What drugs should she avoid?");
		assertNotNull(screen, "precondition: the module answers it");
		assertEquals(screen, ask("Which drugs are contraindicated for her?"));
		String together = ask("Is warfarin safe with ibuprofen?");
		assertNotNull(together, "precondition: the module answers it");
		for (String question : new String[] { "Is warfarin ok to use with ibuprofen?", "warfarin with ibuprofen ok?" }) {
			assertEquals(together, ask(question), question);
		}
		String pair = ask("What is the interaction between warfarin and ibuprofen?");
		assertNotNull(pair, "precondition: the module answers it");
		for (String question : new String[] { "Any concerns with warfarin + ibuprofen?",
				"Is there an interaction if I add warfarin to ibuprofen?", "How serious is the warfarin ibuprofen interaction?",
				"Is the interaction between warfarin and ibuprofen major?" }) {
			assertEquals(pair, ask(question), question);
		}
		String flagged = ask("Why is ibuprofen flagged?");
		assertTrue(flagged != null && flagged.contains("Ibuprofen"), "was: " + flagged);
	}

	/**
	 * Ten wordings the model answered "X can be given, with one caution" on the 3.7.1 standalone (ADR Decision 426): each is
	 * the question it spells, answered as that question's plainer wording is.
	 */
	@Test
	public void aWordingThatWeighsADrugForHerIsTheQuestionItSpells() {
		String notToGive = ask("Is there any reason not to give her warfarin?");
		assertNotNull(notToGive, "precondition: the module answers it");
		for (String question : new String[] { "I want to prescribe warfarin. Thoughts?", "Do her current meds make warfarin unsafe?" }) {
			assertEquals(notToGive, ask(question), question);
		}
		String effect = ask("Is warfarin hepatotoxic with her other meds?");
		assertNotNull(effect, "precondition: the module answers it");
		for (String question : new String[] { "Could warfarin cause QT problems with her meds?",
				"Will warfarin affect her kidneys with her current meds?" }) {
			assertEquals(effect, ask(question), question);
		}
		String watch = ask("What should I watch for if I add warfarin?");
		assertNotNull(watch, "precondition: the module answers it");
		assertEquals(watch, ask("How should I monitor her if I add warfarin?"));
		String interacts = ask("Any drug interactions I should know about before giving warfarin?");
		assertNotNull(interacts, "precondition: the module answers it");
		assertEquals(interacts, ask("Any drug interactions I should be aware of before giving her warfarin?"));
		String withGroup = ask("Is warfarin safe with her HIV medicines?");
		assertNotNull(withGroup, "precondition: the module answers it");
		assertEquals(withGroup, ask("Can warfarin be given with her HIV medicines?"));
		String together = ask("Can warfarin and ibuprofen be taken together?");
		assertNotNull(together, "precondition: the module answers it");
		assertEquals(together, ask("Can warfarin and ibuprofen be taken at the same time?"));
		String pair = ask("What is the interaction between warfarin and ibuprofen?");
		assertNotNull(pair, "precondition: the module answers it");
		assertEquals(pair, ask("warfarin and ibuprofen - any issue?"));
		for (String drug : new String[] { "ibuprofen", "warfarin" }) {
			String toContinue = ask("Is it okay to continue her " + drug + "?");
			assertNotNull(toContinue, "precondition: the module answers it");
			assertEquals(toContinue, ask("Is it ok to continue her " + drug + " with her ARVs?"), drug);
		}
	}

	/**
	 * Thirteen wordings the model answered on the 3.7.1 standalone (ADR Decision 427) — "Fluconazole can be given, with one
	 * caution", "Clarithromycin and Efavirenz can be used together", "No — Nevirapine should not be given" of "Should I change
	 * her nevirapine because of rifampicin?" — each the question it spells, answered as its plainer wording is.
	 */
	@Test
	public void aWordingAClinicianTypesInPassingIsTheQuestionItSpells() {
		String interacts = ask("Does warfarin interact with her medications?");
		assertNotNull(interacts, "precondition: the module answers it");
		for (String question : new String[] { "Is warfarin going to interact with anything she's on?",
				"Tell me about warfarin and her medications" }) {
			assertEquals(interacts, ask(question), question);
		}
		String which = ask("Which of her medications interact with warfarin?");
		assertNotNull(which, "precondition: the module answers it");
		assertEquals(which, ask("What are the interactions of warfarin?"));
		String give = ask("Can I give her warfarin?");
		assertNotNull(give, "precondition: the module answers it");
		for (String question : new String[] { "Planning to start warfarin - safe?", "Is warfarin a good idea for her?" }) {
			assertEquals(give, ask(question), question);
		}
		String forThrush = ask("Can I give her warfarin for thrush?");
		assertNotNull(forThrush, "precondition: the module answers it");
		assertEquals(forThrush, ask("Can I treat her with warfarin for thrush?"));
		String together = ask("Can warfarin and ibuprofen be used together?");
		assertNotNull(together, "precondition: the module answers it");
		assertEquals(together, ask("Can warfarin and ibuprofen be used together safely?"));
		String manage = ask("How do I manage the interaction between aspirin and ibuprofen?");
		assertNotNull(manage, "precondition: the module answers it");
		assertEquals(manage, ask("What should I do about the aspirin ibuprofen interaction?"));
		String change = ask("Should I change her ibuprofen if I start warfarin?");
		assertNotNull(change, "precondition: the module answers it");
		assertEquals(change, ask("Should I change her ibuprofen because of warfarin?"));
		String dose = ask("Should I change her efavirenz if I start rifampicin?");
		assertNotNull(dose, "precondition: the module answers it");
		assertEquals(dose, ask("Does she need a dose change of efavirenz with rifampicin?"));
		String alternative = ask("Is there a safer alternative to warfarin?");
		assertNotNull(alternative, "precondition: the module answers it");
		// and under the lead naming the group, which no order is (ADR Decision 221)
		assertEquals(alternative.replaceFirst("\n", "\nThis check cannot tell which of this patient's active medications are the "
				+ "\"ARVs\" the question names, so it checked Warfarin against all of them.\n"),
			ask("Is there an alternative to warfarin that doesn't interact with her ARVs?"));
		String screen = ask("Is her regimen safe?");
		assertNotNull(screen, "precondition: the module answers it");
		for (String question : new String[] { "Are there any QT prolonging drugs in her list?",
				"Can she safely take all her medicines together?" }) {
			assertEquals(screen, ask(question), question);
		}
	}

	private static org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question);
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
