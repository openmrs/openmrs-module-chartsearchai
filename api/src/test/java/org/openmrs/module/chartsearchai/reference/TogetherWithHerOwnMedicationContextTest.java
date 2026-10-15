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

	@Test
	public void aQuestionWhetherHerDrugAndAnotherInteractIsLeftToTheModelWhereNoFindingRelatesThem() {
		// Rifampicin relates to nothing of hers above the floor here, so neither "Yes" nor the proposal's "No" is the module's.
		assertNull(askOverTheShippedData("Is there any interaction between lamivudine and rifampicin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer());
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
	public void aQuestionWhetherTwoDrugsNeitherOfThemHersInteractIsLeftToTheModelWhereTheDataDoesNotRelateThem() {
		assertNull(askOverTheShippedData("Is there any interaction between amoxicillin and metformin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer());
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
