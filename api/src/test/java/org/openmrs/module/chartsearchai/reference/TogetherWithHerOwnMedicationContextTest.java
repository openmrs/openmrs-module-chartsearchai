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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
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
 * A question whether two drugs may be given together, one of which she already takes, is about the other drug and that
 * pair (ADR Decision 196). Measured on the 3.7.1 standalone: Sarah Taylor's "Can warfarin and ibuprofen be given
 * together?" — ibuprofen being her Advil — was answered by the model without saying she already takes ibuprofen, and
 * four chips about her ibuprofen against her other medications were drawn beside it as findings about the question.
 * Over the slice of {@link ScreenSharedMechanismContextTest}: her ibuprofen, prednisone and dexamethasone, and celecoxib
 * proposed with the ibuprofen.
 */
public class TogetherWithHerOwnMedicationContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-screen-shared-mechanism.json";

	private static final String QUESTION = "Can celecoxib and ibuprofen be given together?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herOwnDrugsFindingsAgainstHerOtherMedicationsAreDrawnApart() throws IOException {
		boolean sawIbuprofen = false;
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service()).validate("", QUESTION, context())) {
			if (!SafetyWarning.TYPE_INTERACTION.equals(chip.getType())) {
				continue;
			}
			if (chip.getDetail().startsWith("Ibuprofen interacts with active order Prednisone")) {
				sawIbuprofen = true;
				assertTrue(chip.isAboutAnotherOfHerMedications(), "not about the pair asked: " + chip.getDetail());
			} else {
				assertFalse(chip.isAboutAnotherOfHerMedications(), "about the drug proposed: " + chip.getDetail());
			}
		}
		assertTrue(sawIbuprofen, "precondition: her ibuprofen against her corticosteroids is raised");
	}

	@Test
	public void theModuleSaysWhichDrugIsHersAndAnswersForTheOther() throws IOException {
		String answer = DrugReferenceTestSupport.injectorWithSafety(service())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(), QUESTION).getModuleAnswer();

		// What ibuprofen is to her, then celecoxib's own answer — its cautions, the pair asked about among them.
		assertEquals("Ibuprofen is one of this patient's active medications.\n"
				+ "2 interaction cautions for Celecoxib:\n"
				+ "Celecoxib interacts with active order Prednisone and Dexamethasone — Moderate. [7] [3] [4]\n"
				+ "Celecoxib interacts with active order Ibuprofen — Moderate. [8] [2]", answer);
	}

	/**
	 * A question whether the two INTERACT, one of them hers, is answered "Yes" where a finding relates the other drug to hers
	 * (ADR Decision 218). On the public demo's data, "Is there any interaction between nevirapine and rifampicin?" for
	 * patients on nevirapine was answered by the model "No — Rifampicin interacts with active order Nevirapine: Major":
	 * the polarity of a proposal's lead on a question asking whether they interact.
	 */
	@Test
	public void aQuestionWhetherHerDrugAndAnotherInteractIsAnsweredYesWhereAFindingRelatesThem() {
		for (String question : Arrays.asList("Is there any interaction between nevirapine and rifampicin?",
			"Does rifampicin interact with nevirapine?", "Any interactions between nevirapine and rifampicin?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ "Yes — Rifampicin (rifampin) interacts with this patient's active medications."), question + " was: " + answer);
			assertTrue(answer.contains("Rifampicin (rifampin) interacts with active order Nevirapine — Major."),
				question + " states the pair: " + answer);
			assertTrue(!answer.contains("No —"), "never the proposal's polarity: " + answer);
		}
	}

	/**
	 * Her drug named as hers in a together question (ADR Decision 246). On the 3.7.1 standalone Donald Thompson's "Is
	 * fluconazole safe with his warfarin?" fitted no shape — the together grammar took no "his" — and the model answered
	 * "The records do not address the safety of Fluconazole with his current regimen" beside a Major finding relating the
	 * two.
	 */
	@Test
	public void aTogetherQuestionNamingHerDrugAsHersIsAnsweredForTheOther() {
		for (String question : Arrays.asList("Is rifampicin safe with her nevirapine?",
			"Can I give her rifampicin with her nevirapine?", "Can rifampicin be given with her nevirapine?",
			"Is it safe to give her rifampicin with her nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ "No — this module's drug-safety check found a reason to withhold Rifampicin (rifampin)."),
				question + " was: " + answer);
			assertTrue(answer.contains("Rifampicin (rifampin) interacts with active order Nevirapine — Major."),
				question + " states the pair: " + answer);
		}
	}

	/**
	 * Whether one drug AFFECTS the other asks whether they interact (ADR Decision 248). On the 3.7.1 standalone Donald
	 * Thompson's "Will fluconazole affect his warfarin?" reached the model, which opened "No — Fluconazole should not be
	 * given: coadministration with fluconazole may significantly increase … warfarin": a "No" to whether it would affect it.
	 */
	@Test
	public void aQuestionWhetherADrugAffectsHerDrugIsAnsweredYesWhereAFindingRelatesThem() {
		for (String question : Arrays.asList("Will rifampicin affect her nevirapine?", "Does rifampicin affect her nevirapine?",
			"Would rifampicin affect nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ "Yes — Rifampicin (rifampin) interacts with this patient's active medications."), question + " was: " + answer);
			assertTrue(!answer.contains("No —"), "never the proposal's polarity: " + answer);
		}
	}

	/**
	 * Whether one drug raises, lowers or otherwise changes a particular effect of the other — an effect the data does not
	 * state — is answered with the finding and neither "Yes" nor "No" (ADR Decision 249). On the 3.7.1 standalone Donald
	 * Thompson's "Does metronidazole increase the bleeding risk with his warfarin?" reached the model, which opened "No —
	 * metronidazole should not be given: coadministration … may increase … the hypoprothrombinemic effect of warfarin".
	 */
	@Test
	public void aQuestionWhetherADrugChangesAnEffectOfHerDrugIsAnsweredWithTheFindingAndNeitherYesNorNo() {
		for (String question : Arrays.asList("Will rifampicin decrease her nevirapine levels?",
			"Does rifampicin reduce the effect of her nevirapine?", "Can rifampicin lower nevirapine concentrations?",
			"Does rifampicin increase the risk of toxicity with her nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"), question + " was: " + answer);
			assertTrue(answer.contains("Rifampicin (rifampin) interacts with active order Nevirapine — Major."),
				question + " states the pair: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	/**
	 * What a drug does beside her drug — whether to avoid the two together, what happens or what the risk is if they are
	 * combined, whether her drug's dose needs changing — is answered as Decision 249 answers an effect (ADR Decision 251).
	 * On the 3.7.1 standalone Brian Lopez's "Should he avoid codeine with his tramadol?" and Donald Thompson's "Should his
	 * warfarin dose be adjusted if I start fluconazole?" were answered by the model with a "No —".
	 */
	@Test
	public void whatADrugDoesBesideHerDrugIsAnsweredWithTheFindingAndNeitherYesNorNo() {
		for (String question : Arrays.asList("Should she avoid rifampicin with her nevirapine?",
			"What happens if I give her rifampicin with her nevirapine?", "What is the risk of combining rifampicin with her nevirapine?",
			"Should her nevirapine dose be adjusted if I start rifampicin?", "Will rifampicin lower her nevirapine absorption?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"), question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	/**
	 * Whether to stop or keep her drug for another, or to monitor the two, is answered with the finding and neither "Yes" nor
	 * "No" (ADR Decision 252). On the 3.7.1 standalone Carol Brown's "Do I need to stop her simvastatin before starting
	 * clarithromycin?" and "Does she need potassium monitoring on lisinopril and spironolactone?" were answered by the model
	 * "No — Clarithromycin should not be started" and "No — Spironolactone should not be given": a "No" to whether to stop
	 * it, and to whether to monitor.
	 */
	@Test
	public void stoppingOrKeepingHerDrugForAnotherAndMonitoringBothAreAnsweredWithTheFindingAndNeitherYesNorNo() {
		for (String question : Arrays.asList("Do I need to stop her nevirapine before starting rifampicin?",
			"Should I stop her nevirapine before giving rifampicin?", "Can she continue her nevirapine if I start rifampicin?",
			"Does she need monitoring on nevirapine and rifampicin?", "Does she need any monitoring if I add rifampicin to her nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"), question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	/** Whether a drug is dangerous, risky or a cause of problems beside her drug (ADR Decision 255): "No" would say it is not. */
	@Test
	public void whetherADrugIsDangerousBesideHerDrugIsAnsweredWithTheFindingAndNeitherYesNorNo() {
		for (String question : Arrays.asList("Is rifampicin dangerous with her nevirapine?",
			"Is it risky to combine rifampicin and her nevirapine?", "Could rifampicin cause problems with her nevirapine?",
			"Would rifampicin be harmful with her nevirapine?", "Is the combination of rifampicin and her nevirapine dangerous?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"), question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	@Test
	public void aQuestionWhetherOneOfHerDrugsChangesAnEffectOfAnotherOfHersStatesThemSideBySide() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nevirapine", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rifampicin", "Rifampicin 150mg", "Rifampicin"));
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
					new LinkedHashSet<String>(Arrays.asList("Nevirapine 200mg", "Nevirapine", "Rifampicin 150mg", "Rifampicin")),
					null, null, null, orders), "Will her rifampicin reduce her nevirapine levels?").getModuleAnswer();

		assertTrue(answer != null && answer.contains(DrugReferenceInjector.TWO_OF_HER_OWN_LEAD), "was: " + answer);
		assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
	}

	/**
	 * A word naming one of her own orders is her drug, checked through the order, and never said to be unrecognised (ADR
	 * Decision 250). Measured on the 3.7.1 standalone while that decision was verified: Amina Wekesa, on "Cotrimoxazole
	 * 960mg", asked "Is warfarin safe with her cotrimoxazole?" — "cotrimoxazole" being no name the data carries — and was
	 * told "No interaction check was run for "cotrimoxazole" and Warfarin", beside the Major finding the check had raised
	 * against that very order.
	 */
	@Test
	public void aWordNamingHerOwnOrderIsNeverSaidToBeUnrecognised() {
		PatientChart chart = askOverTheShippedData("Is warfarin safe with her cotrimoxazole?", "Cotrimoxazole 960mg",
			"Sulfamethoxazole / trimethoprim");

		assertTrue(chart.getText().contains("Warfarin interacts with active order"), "precondition: the check ran against her "
				+ "order:\n" + chart.getText());
		String answer = chart.getModuleAnswer();
		assertTrue(answer == null || !answer.contains("No interaction check was run"), "was: " + answer);
	}

	/**
	 * Nor is a word the question names as hers (ADR Decision 250): Ruth Ritonavira's "Will rifampicin reduce the effect of
	 * her ARVs?" names a group of her own orders, every one of which was checked — and was told "No interaction check was
	 * run for "ARVs" and Rifampicin (rifampin)" while that decision was verified.
	 */
	@Test
	public void aWordTheQuestionNamesAsHersIsNeverSaidToBeUnrecognised() {
		String answer = askOverTheShippedData("Will rifampicin reduce the effect of her ARVs?", "Nevirapine 200mg", "Nevirapine")
				.getModuleAnswer();
		assertTrue(answer == null || !answer.contains("No interaction check was run"), "was: " + answer);
	}

	/** Whether two drugs CLASH asks whether they interact (ADR Decision 256): Carol Brown's "Do clarithromycin and
	 *  simvastatin clash?" was answered by the model "No — clarithromycin and simvastatin clash". */
	@Test
	public void whetherHerDrugAndAnotherClashIsAnsweredYesWhereAFindingRelatesThem() {
		for (String question : Arrays.asList("Do rifampicin and nevirapine clash?", "Does rifampicin clash with her nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();

			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
					+ "Yes — Rifampicin (rifampin) interacts with this patient's active medications."), question + " was: " + answer);
			assertTrue(!answer.contains("No —"), "never the proposal's polarity: " + answer);
		}
	}

	/**
	 * A question whose "No" would read backwards, about a drug whose reason to withhold is an interaction no rating makes
	 * one — a drug group folded onto a Moderate rule — states what the check found, and neither "Yes", "No" nor that a reason
	 * to withhold was found (ADR Decision 257). On the 3.7.1 standalone Carol Brown's "Is naproxen contraindicated with her
	 * aspirin?" was answered by the model "No — Naproxen should not be given with Aspirin". A proposal of it alone is
	 * answered with what the check found too, and no "No" (ADR Decision 312): issue #469 keeps the "No" for a rated reason.
	 */
	@Test
	public void aNeutralQuestionAboutADrugWithholdingWithoutARatingStatesWhatTheCheckFound() {
		for (String question : Arrays.asList("Is naproxen contraindicated with her aspirin?", "Is naproxen contraindicated for her?")) {
			String answer = askOverTheShippedData(question, "Aspirin 81mg", "Aspirin").getModuleAnswer();

			assertTrue(answer != null && answer.contains(DrugReferenceInjector.FOUND_LEAD_OPENING + "Naproxen:\n"),
				question + " was: " + answer);
			assertTrue(answer.contains("Naproxen interacts with active order Acetylsalicylic acid (aspirin) — Moderate. Naproxen is in "
					+ "the same cross-reactivity group (NSAID) as active order Acetylsalicylic acid (aspirin)"), question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —") && !answer.contains("reason to withhold"),
				"neither a verdict nor a withholding claim: " + answer);
		}
		String proposal = askOverTheShippedData("Can I give her naproxen?", "Aspirin 81mg", "Aspirin").getModuleAnswer();
		assertTrue(proposal != null && proposal.startsWith(DrugReferenceInjector.FOUND_LEAD_OPENING + "Naproxen:\n"),
			"a proposal withheld by no rating states what the check found: " + proposal);
		assertTrue(!proposal.contains("No —") && !proposal.contains("reason to withhold"),
			"a proposal's No rests on a rated reason alone (issue #469): " + proposal);
	}

	/**
	 * Whether one drug changes an effect of another, neither of them hers, is answered with what relates them and neither
	 * "Yes" nor "No" (ADR Decision 286), as ADR Decision 249 answers it where one is hers. On the 3.7.1 standalone Amina, on
	 * neither, asked "Will ibuprofen raise her lithium levels?" and the model answered "No — ibuprofen should not be given:
	 * coadministration with nonsteroidal anti-inflammatory drugs (NSAIDs) may increase serum lithium levels" — a "No" to
	 * whether it raises them.
	 */
	@Test
	public void whetherOneDrugChangesAnEffectOfAnotherNeitherHersIsAnsweredWithWhatRelatesThem() {
		for (String question : Arrays.asList("Will ibuprofen raise her lithium levels?", "Does ibuprofen increase lithium levels?")) {
			String answer = askOverTheShippedData(question, "Metformin 500mg", "Metformin").getModuleAnswer();
			assertTrue(answer != null && answer.startsWith("Neither Ibuprofen nor Lithium carbonate is one of this patient's "
					+ "active medications. What the interaction check relates between them:\n"), question + " was: " + answer);
			assertTrue(answer.contains(":\nIbuprofen interacts with Lithium carbonate, also named in the question — Major. ["),
				question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither a Yes nor a No: " + answer);
		}
	}

	/**
	 * Whether her drug's dose is safe beside another drug is answered with the finding and neither "Yes" nor "No" (ADR
	 * Decision 258). On the 3.7.1 standalone Donald Thompson's "Is his warfarin dose safe with his amiodarone?" was
	 * answered by the model "No — Warfarin should not be given with Amiodarone", of two drugs he already takes.
	 */
	@Test
	public void whetherHerDrugsDoseIsSafeBesideAnotherIsAnsweredWithTheFindingAndNeitherYesNorNo() {
		String answer = askOverTheShippedData("Is her nevirapine dose safe with rifampicin?", "Nevirapine 200mg", "Nevirapine")
				.getModuleAnswer();
		assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\n"
				+ DrugReferenceInjector.NEUTRAL_WITHHOLD_LEAD_OPENING + "Rifampicin (rifampin).\n"), "was: " + answer);

		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nevirapine", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rifampicin", "Rifampicin 150mg", "Rifampicin"));
		String both = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
					new LinkedHashSet<String>(Arrays.asList("Nevirapine 200mg", "Nevirapine", "Rifampicin 150mg", "Rifampicin")),
					null, null, null, orders), "Is her nevirapine dose safe with her rifampicin?").getModuleAnswer();
		assertTrue(both != null && both.contains(DrugReferenceInjector.TWO_OF_HER_OWN_LEAD), "was: " + both);
		assertTrue(!both.contains("No —") && !both.contains("Yes —"), "neither Yes nor No: " + both);
	}

	/**
	 * One drug asked about beside several of hers is answered for it, saying which are hers (ADR Decision 263). On the 3.7.1
	 * standalone Rhoda Ritonavirb's "Can I give her fluconazole with her nevirapine and lopinavir?" fitted no shape — the
	 * together grammar took one drug after "with" — and the model answered "Fluconazole can be given, with two cautions",
	 * leaving out a third: fluconazole with her zidovudine.
	 */
	@Test
	public void aDrugAskedAboutBesideSeveralOfHersIsAnsweredForItSayingWhichAreHers() {
		String answer = askOverTheseOrders("Can I give her fluconazole with her nevirapine and lopinavir?").getModuleAnswer();

		assertTrue(answer != null && answer.startsWith("Nevirapine and Lopinavir are among this patient's active medications.\n"),
			"was: " + answer);
		assertTrue(answer.contains("interaction cautions for Fluconazole:"), "fluconazole's own answer: " + answer);
		assertTrue(answer.contains("\nFluconazole interacts with active order Zidovudine — Moderate. ["),
			"every caution, the one the model left out among them: " + answer);
		assertTrue(!answer.contains("can be given"), "never a clearance: " + answer);
	}

	/**
	 * What the mechanism of the interaction between two drugs is, is answered with the finding relating them, its
	 * mechanism whole, and neither "Yes" nor "No" (ADR Decision 264). On the 3.7.1 standalone Mary Smith, on simvastatin,
	 * asked "What is the mechanism of the interaction between simvastatin and clarithromycin?" and the model opened "No —
	 * Clarithromycin should not be given".
	 */
	@Test
	public void theMechanismOfAnInteractionIsAnsweredWithTheFindingWhole() {
		String answer = askOverTheShippedData("What is the mechanism of the interaction between nevirapine and rifampicin?",
			"Nevirapine 200mg", "Nevirapine").getModuleAnswer();

		assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\nWhat the "
				+ "interaction check relates between Nevirapine and Rifampicin (rifampin):\nRifampicin (rifampin) interacts with "
				+ "active order Nevirapine — Major. Coadministration with rifampin may substantially decrease the plasma "
				+ "concentrations of nevirapine"), "the finding, its mechanism whole: " + answer);
		assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
	}

	/**
	 * What the interaction between two drugs is, asked without the word "mechanism", is answered as its mechanism is (ADR
	 * Decision 289). On the 3.7.1 standalone Ruth, on lopinavir/ritonavir, asked "What is the interaction between
	 * rifampicin and her lopinavir?" and the model opened "No — Rifampicin should not be given".
	 */
	@Test
	public void whatTheInteractionBetweenTwoDrugsIsIsAnsweredWithTheFindingWhole() {
		for (String question : Arrays.asList("What is the interaction between nevirapine and rifampicin?",
			"What interactions are there between rifampicin and her nevirapine?", "How do nevirapine and rifampicin interact?",
			"How does rifampicin interact with her nevirapine?")) {
			String answer = askOverTheShippedData(question, "Nevirapine 200mg", "Nevirapine").getModuleAnswer();
			assertTrue(answer != null && answer.startsWith("Nevirapine is one of this patient's active medications.\nWhat the "
					+ "interaction check relates between "), question + " was: " + answer);
			assertTrue(answer.contains("\nRifampicin (rifampin) interacts with active order Nevirapine — Major. Coadministration "
					+ "with rifampin may substantially decrease the plasma concentrations of nevirapine"),
				"the finding, its mechanism whole: " + question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	/**
	 * What the interaction between two drugs is, where one of them is not hers, also states that drug's findings against her
	 * active medications (ADR Decision 425): it is a drug being weighed for her, and the answer's own lines are what the
	 * check found. On the 3.7.1 standalone Mark Smith, on nevirapine and stavudine, "Interactions between simvastatin and
	 * clarithromycin" stated the pair alone once ADR Decision 423 read it as this question, leaving simvastatin and
	 * clarithromycin with her nevirapine, and simvastatin with her stavudine, to the chips.
	 */
	@Test
	public void thePairsInteractionStatesADrugNotHersAgainstHerActiveMedications() {
		String neither = askOverTheShippedData("Interactions between simvastatin and clarithromycin", "Nevirapine 200mg",
			"Nevirapine").getModuleAnswer();
		assertTrue(neither != null && neither.startsWith("What the interaction check relates between Simvastatin and "
				+ "Clarithromycin:\nSimvastatin interacts with Clarithromycin, also named in the question — Major."), "was: " + neither);
		assertTrue(neither.contains("\nAgainst this patient's active medications:\n"), "the heading: " + neither);
		assertTrue(neither.contains("\nSimvastatin interacts with active order Nevirapine — Moderate.")
				&& neither.contains("\nClarithromycin interacts with active order Nevirapine — Moderate."),
			"each drug against her nevirapine: " + neither);
		assertTrue(!neither.contains("No —") && !neither.contains("Yes —"), "neither Yes nor No: " + neither);

		String oneHers = askOverTheseOrders("What is the interaction between fluconazole and nevirapine?").getModuleAnswer();
		assertTrue(oneHers != null && oneHers.startsWith("Nevirapine is one of this patient's active medications.\nWhat the "
				+ "interaction check relates between Fluconazole and Nevirapine:\n"), "was: " + oneHers);
		assertTrue(oneHers.contains("\nAgainst this patient's active medications:\n")
				&& oneHers.contains("\nFluconazole interacts with active order Zidovudine — Moderate."),
			"fluconazole against her zidovudine: " + oneHers);
		assertEquals(1, oneHers.split("Fluconazole interacts with active order Nevirapine", -1).length - 1,
			"the pair's finding stated once: " + oneHers);
	}

	/**
	 * Whether to separate two drugs' doses is answered as the mechanism is, with the finding relating them whole (ADR
	 * Decision 270): it is the mechanism that says whether spacing them helps. On the 3.7.1 standalone Rhoda, on didanosine,
	 * asked "Should I separate the doses of ciprofloxacin and her didanosine?" and the model opened "Didanosine can be given".
	 */
	@Test
	public void whetherToSeparateTwoDrugsDosesIsAnsweredWithTheFindingWhole() {
		for (String question : Arrays.asList("Should I separate the doses of ciprofloxacin and her didanosine?",
			"Do I need to space out the doses of ciprofloxacin and didanosine?")) {
			String answer = askOverTheShippedData(question, "Didanosine 250mg", "Didanosine").getModuleAnswer();
			assertTrue(answer != null && answer.startsWith("Didanosine is one of this patient's active medications.\nWhat the "
					+ "interaction check relates between Ciprofloxacin and Didanosine:\nCiprofloxacin interacts with active order "
					+ "Didanosine — Moderate. Coadministration with didanosine"), question + " was: " + answer);
			assertTrue(!answer.contains("No —") && !answer.contains("Yes —"), "neither Yes nor No: " + answer);
		}
	}

	private static PatientChart askOverTheseOrders(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-lpv", "Lopinavir / ritonavir 200mg/50mg", "Lopinavir / ritonavir"),
			DrugReferenceTestSupport.activeOrder("order-azt", "Lamivudine / zidovudine 150mg/300mg", "Lamivudine / zidovudine"));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, names, null, null, null, orders),
			question);
	}

	@Test
	public void aQuestionWhetherHerDrugAndAnotherInteractStatesTheListingWhereNoFindingRelatesThem() {
		// Rifampicin relates to nothing of hers above the floor here, so neither "Yes" nor the proposal's "No": what the data
		// lists between the two, below the floor (ADR Decision 329).
		String answer = askOverTheShippedData("Is there any interaction between lamivudine and rifampicin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer();
		assertTrue(answer != null && answer.replaceAll("\\[\\d+\\]", "[n]").startsWith("Lamivudine is one of this patient's "
				+ "active medications (Lamivudine 150mg [n]); Rifampicin (rifampin) is not.\nThe interaction data lists Lamivudine "
				+ "against Rifampicin (rifampin), with no severity or mechanism on file."), "was: " + answer);
		assertTrue(!answer.contains("Yes —") && !answer.contains("No —"), "was: " + answer);
	}

	/**
	 * Neither drug hers (ADR Decision 220): the demo's "Is there any interaction between nevirapine and rifampicin?" for a
	 * patient on neither was answered by the model "No — Nevirapine interacts with Rifampicin, which is a Major interaction",
	 * beside the question-pair finding stating that very pair.
	 */
	@Test
	public void aQuestionWhetherTwoDrugsNeitherOfThemHersInteractIsAnsweredYesWhereTheDataRelatesThem() {
		String answer = askOverTheShippedData("Is there any interaction between nevirapine and rifampicin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer();

		assertTrue(answer != null && answer.startsWith("Yes — Nevirapine and Rifampicin (rifampin) interact; neither is one of "
				+ "this patient's active medications.\nNevirapine interacts with Rifampicin (rifampin), also named in the question "
				+ "— Major."), "was: " + answer);
		assertTrue(!answer.contains("No —"), "was: " + answer);
	}

	@Test
	public void aQuestionWhetherTwoDrugsNeitherOfThemHersInteractStatesTheListingWhereNoFindingRelatesThem() {
		// ADR Decision 329: the data lists the pair only below the floor.
		String answer = askOverTheShippedData("Is there any interaction between amoxicillin and metformin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer();
		assertTrue(answer != null && answer.replaceAll("\\[\\d+\\]", "[n]").startsWith("Neither Amoxicillin nor Metformin is "
				+ "one of this patient's active medications.\nThe interaction data lists Amoxicillin against Metformin, with no "
				+ "severity or mechanism on file."), "was: " + answer);
		assertTrue(!answer.contains("Yes —") && !answer.contains("No —"), "was: " + answer);
	}

	/**
	 * Two drugs proposed together, neither hers (ADR Decision 222): the demo's "Can I give him fluconazole and
	 * clarithromycin?" was answered by the model "The drug can be given, with a caution: …", naming no drug the clearance
	 * was about. The module answers each drug as its own proposal is answered, and says what relates the two.
	 */
	@Test
	public void twoDrugsProposedTogetherNeitherOfThemHersAreEachAnsweredAsTheirOwnProposal() {
		String answer = askOverTheShippedData("Can I give him fluconazole and clarithromycin?", "Nevirapine 200mg", "Nevirapine")
				.getModuleAnswer();

		assertTrue(answer != null && answer.startsWith("Fluconazole and Clarithromycin, asked about together; neither is one of "
				+ "this patient's active medications."), "was: " + answer);
		assertTrue(answer.contains("\nFor Fluconazole:\n1 interaction caution for Fluconazole:\nFluconazole interacts with "
				+ "active order Nevirapine — Moderate."), "fluconazole's own answer: " + answer);
		assertTrue(answer.contains("\nFor Clarithromycin:\n1 interaction caution for Clarithromycin:\nClarithromycin interacts "
				+ "with active order Nevirapine — Moderate."), "clarithromycin's own answer: " + answer);
		assertTrue(!answer.contains("can be given"), "never a clearance: " + answer);
	}

	private static PatientChart askOverTheShippedData(String question, String display, String name) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-hers", display, name));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(Arrays.asList(display, name)), null, null, null, orders), question);
	}

	private static DrugReferenceService service() throws IOException {
		return DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
	}

	private static PatientClinicalContext context() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"),
			DrugReferenceTestSupport.activeOrder("order-dexamethasone", "Dexamethasone 8mg"));
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null, null, null, orders);
	}
}
