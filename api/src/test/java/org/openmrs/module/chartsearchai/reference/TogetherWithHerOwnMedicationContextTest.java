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
