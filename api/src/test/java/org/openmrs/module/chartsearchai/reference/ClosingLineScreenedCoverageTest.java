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
 * A composed answer carries no closing line saying what kinds of finding were not covered (ADR Decision 573, the owner's
 * direction): "Interactions the data does not rate, and anything beyond drug interactions … are not covered." describes the
 * module's check, the same on every answer, and is the documentation's to state, as Decision 562 made the knowledge base's
 * lack of condition rules. What was not covered for THIS patient — a recorded allergy or an active order the drug data
 * does not identify — is still said. The cases below were Decisions 544 and 547's, which chose that line's words; each
 * now asserts the line is absent. Over the shipped data, as the demo runs it, for patient dd74dc61's three orders each of
 * lamivudine, nevirapine and stavudine.
 */
public class ClosingLineScreenedCoverageTest extends BaseModuleContextSensitiveTest {

	private static void assertNoScopeLine(String answer) {
		DrugReferenceTestSupport.assertNoScopeLine(answer);
		assertFalse(answer.contains("anything beyond drug interactions"), "no scope line; was: " + answer);
	}

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aProposalClosesOnNoScopeLine() {
		String answer = ask("Can I give her amoxicillin?", Collections.<String> emptySet());

		assertNotNull(answer, "the module answers");
		assertNoScopeLine(answer);
		// The bottom line, then the data it rests on, and nothing after (ADR Decisions 534, 573, 574).
		assertTrue(answer.matches("The interaction data gives no rated reason to withhold Amoxicillin\\.\nThe data lists Amoxicillin "
				+ "against 2 of this patient's medications, none with a severity or mechanism on file\\. \\[\\d+\\]"),
			"was: " + answer);
	}

	/** Another wording of the same proposal closes alike. */
	@Test
	public void anotherWordingOfTheSameProposalClosesAlike() {
		String answer = ask("Is it safe to start her on amoxicillin?", Collections.<String> emptySet());

		assertEquals(ask("Can I give her amoxicillin?", Collections.<String> emptySet()), answer);
		assertNoScopeLine(answer);
	}

	/**
	 * An answer stating a contraindication closes on no scope line either: Decision 547's case, "Can I give her amoxicillin
	 * and ibuprofen together?" for a patient recorded allergic to "Penicillins".
	 */
	@Test
	public void twoDrugsProposedTogetherBesideAStatedContraindicationCloseOnNoScopeLine() {
		String answer = ask("Can I give her amoxicillin and ibuprofen together?", DrugReferenceTestSupport.set("penicillins"));

		assertNotNull(answer, "the module answers");
		assertTrue(answer.contains("Amoxicillin is in the drug group (Penicillins) the patient has a recorded allergy to."),
			"precondition: the contraindication is stated; was: " + answer);
		assertNoScopeLine(answer);
	}

	/** And with no finding at all. */
	@Test
	public void twoDrugsProposedTogetherWithNoFindingCloseOnNoScopeLine() {
		assertNoScopeLine(ask("Can I give her amoxicillin and ibuprofen together?", Collections.<String> emptySet()));
	}

	/**
	 * An answer that never closed on a scope line — a switch from a drug she does not take to one she does — gains no line
	 * about what was covered.
	 */
	@Test
	public void anAnswerClosingOnNoScopeLineGainsNoLine() {
		String withoutTheStep = ask("Can I switch her from zidovudine to efavirenz?", Collections.<String> emptySet(),
			"Efavirenz");

		assertNotNull(withoutTheStep, "the module answers");
		assertTrue(withoutTheStep.contains("Efavirenz is already one of this patient's active medications"),
			"precondition: answered that she takes it; was: " + withoutTheStep);
		assertFalse(withoutTheStep.contains("beyond drug interactions"), "precondition: no scope line; was: " + withoutTheStep);
		assertFalse(withoutTheStep.contains("recorded allergies"), "was: " + withoutTheStep);
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
	 * A question only whether the drug interacts closes on no scope line either.
	 */
	@Test
	public void aQuestionOnlyWhetherItInteractsClosesOnNoScopeLine() {
		assertNoScopeLine(ask("Does amoxicillin interact with her medications?", Collections.<String> emptySet()));
	}

	/** An active order the drug data does not identify is still named as not checked, on a line of its own (ADR Decision
	 *  161), where it was folded into the scope line (ADR Decision 496). */
	@Test
	public void anOrderTheDataDoesNotIdentifyIsStillNamedAsNotChecked() {
		String answer = ask("Any drug interactions?", Collections.<String> emptySet(), "Infant formula");

		assertNoScopeLine(answer);
		assertTrue(answer.endsWith("\nNot checked: 1 active order the drug data does not identify — Infant formula."),
			"was: " + answer);
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
