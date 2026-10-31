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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * What a composed answer's closing line says was not covered is what the pass did not SCREEN, never what it did not FIND
 * (ADR Decision 544). On the public demo, patient dd74dc61 — no allergy recorded, on Lamivudine, Nevirapine and Stavudine,
 * three orders each — asked "Can I give her amoxicillin?" was told "Not checked: unrated interactions, anything beyond drug
 * interactions, and this patient's conditions …": his allergy records had been read and weighed against amoxicillin, and
 * the line sent the clinician to check them by hand. Over the shipped data, as the demo runs it.
 */
public class ClosingLineScreenedCoverageTest extends BaseModuleContextSensitiveTest {

	private static final String WIDENED = "Not checked: unrated interactions, anything beyond drug interactions and "
			+ "contraindications, and " + DrugReferenceInjector.CONDITIONS_UNCHECKED_PART + ".";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aProposalWhoseContraindicationsWereScreenedDoesNotCallThemUnchecked() {
		String answer = ask("Can I give her amoxicillin?", Collections.<String> emptySet());

		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("The interaction data gives no rated reason to withhold Amoxicillin."),
			"precondition: no finding, the rows below the floor stated; was: " + answer);
		String[] lines = answer.split("\n");
		assertEquals(WIDENED, lines[lines.length - 1], "was: " + answer);
	}

	/** Two wordings of one question about one drug close alike: what was screened does not depend on the wording. */
	@Test
	public void anotherWordingOfTheSameProposalClosesAlike() {
		String answer = ask("Is it safe to start her on amoxicillin?", Collections.<String> emptySet());

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(WIDENED, lines[lines.length - 1], "was: " + answer);
	}

	/**
	 * An answer stating a contraindication must not close saying contraindications were not covered (ADR Decision 547). On
	 * the demo's data, a patient recorded allergic to "Penicillins" asked "Can I give her amoxicillin and ibuprofen
	 * together?" was told amoxicillin is in the penicillin group she is allergic to, and then "Interactions the data does not
	 * rate, and anything beyond drug interactions, are not covered." — a two-drug proposal carries no line about her
	 * conditions, and Decision 544 widened only beside one.
	 */
	@Test
	public void twoDrugsProposedTogetherBesideAStatedContraindicationCloseSayingContraindicationsWereCovered() {
		String answer = ask("Can I give her amoxicillin and ibuprofen together?", DrugReferenceTestSupport.set("penicillins"));

		assertNotNull(answer, "the module answers");
		assertTrue(answer.contains("Amoxicillin is in the drug group (Penicillins) the patient has a recorded allergy to."),
			"precondition: the contraindication is stated; was: " + answer);
		String[] lines = answer.split("\n");
		assertEquals(WIDENED, lines[lines.length - 1], "was: " + answer);
	}

	/** And with no finding at all: two drugs proposed together were each weighed against her allergy records. */
	@Test
	public void twoDrugsProposedTogetherWithNoFindingCloseSayingContraindicationsWereCovered() {
		String answer = ask("Can I give her amoxicillin and ibuprofen together?", Collections.<String> emptySet());

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(WIDENED, lines[lines.length - 1], "was: " + answer);
	}

	/**
	 * Widening joins the line it widens and adds none (ADR Decision 547): an answer closing on no scope line — a switch from a
	 * drug she does not take to one she does, answered so — gains no line about her conditions from it.
	 */
	@Test
	public void anAnswerClosingOnNoScopeLineGainsNoLine() {
		String withoutTheStep = ask("Can I switch her from zidovudine to efavirenz?", Collections.<String> emptySet(),
			"Efavirenz");

		assertNotNull(withoutTheStep, "the module answers");
		assertTrue(withoutTheStep.contains("Efavirenz is already one of this patient's active medications"),
			"precondition: answered that she takes it; was: " + withoutTheStep);
		assertFalse(withoutTheStep.contains("beyond drug interactions"), "precondition: no scope line; was: " + withoutTheStep);
		assertFalse(withoutTheStep.contains(DrugReferenceInjector.CONDITIONS_UNCHECKED_PART), "was: " + withoutTheStep);
	}

	/** With the contraindication arm switched off nothing weighed her allergies, and no answer may say it did. */
	@Test
	public void aPassThatDidNotWeighHerContraindicationsNeverSaysItDid() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");

		String answer = ask("Can I give her amoxicillin?", Collections.<String> emptySet());

		assertTrue(answer == null || !answer.contains("contraindications"), "was: " + answer);
	}

	/**
	 * A recorded drug allergy the data does not identify was compared with nothing (ADR Decision 223), so the pass did not
	 * weigh all her contraindications. On the demo's data, a patient recorded allergic to "Penicillins" is one.
	 */
	@Test
	public void aPassBesideAnAllergyTheDataDoesNotIdentifyNeverSaysItWeighedHerContraindications() {
		String answer = ask("Can I give her amoxicillin?", DrugReferenceTestSupport.set("ace inhibitors"));

		assertNotNull(answer, "the module answers");
		assertTrue(answer.contains("Not checked: a recorded allergy the drug data does not identify"),
			"precondition: the allergy is not identified; was: " + answer);
		assertFalse(answer.contains("contraindications"), "was: " + answer);
	}

	/**
	 * A question only whether the drug interacts states no line about her conditions (ADR Decision 533), and with no
	 * drug-condition rule loaded the line cannot say her contraindications were covered without it: it stays narrow.
	 */
	@Test
	public void aQuestionOnlyWhetherItInteractsKeepsTheNarrowLineWhereNoConditionRuleIsLoaded() {
		String answer = ask("Does amoxicillin interact with her medications?", Collections.<String> emptySet());

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(DrugReferenceInjector.BELOW_FLOOR_SCOPE, lines[lines.length - 1], "was: " + answer);
	}

	private static String ask(String question, Set<String> allergies, String... moreOrders) {
		DrugReferenceService service = new DrugReferenceService();
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
			service.getLoadStatus().coverageOf(DrugReferenceLoad.Arm.CONDITION_RULES),
			"premise: the shipped dataset loaded, and publishes no drug-condition rule");
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			for (String drug : new String[] { "Lamivudine", "Nevirapine", "Stavudine" }) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug + "-" + n, drug, drug));
			}
		}
		Set<String> names = DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine");
		for (String drug : moreOrders) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug, drug, drug));
			names.add(drug);
		}
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.ctx(66, null, names, null,
				allergies, null, orders),
			question);
		return chart.getModuleAnswer();
	}
}
