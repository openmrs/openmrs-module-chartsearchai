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

	@Test
	public void aQuestionWhetherHerDrugAndAnotherInteractIsLeftToTheModelWhereNoFindingRelatesThem() {
		// Rifampicin relates to nothing of hers above the floor here, so neither "Yes" nor the proposal's "No" is the module's.
		assertNull(askOverTheShippedData("Is there any interaction between lamivudine and rifampicin?", "Lamivudine 150mg",
			"Lamivudine").getModuleAnswer());
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
