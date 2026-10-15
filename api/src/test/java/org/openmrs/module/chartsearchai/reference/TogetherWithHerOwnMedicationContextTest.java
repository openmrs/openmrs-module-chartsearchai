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
