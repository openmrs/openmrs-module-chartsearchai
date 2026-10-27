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
 * Issue #469's module-answered screen and issue #477's finding that two of her orders share a
 * substance. That finding is an INTERACTION finding relating no pair, so a screen whose only
 * interaction finding it is must still go to the model, as ADR Decision 108 requires of a screen that
 * related nothing. A screen that DID relate a pair is answered by the module, and the finding is one of
 * its lines.
 */
public class OrdersSharingASubstanceModuleAnswerContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-substance-in-several-orders.json";

	private static final String RHZE = "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aScreenThatRelatedAPairIsAnsweredByTheModuleStrongestFirstAsThePromptRanksIt() throws IOException {
		// The four-drug combination beside isoniazid and ethambutol orders: the fixture rates isoniazid
		// against rifampicin (Minor) and against ethambutol (Moderate), both cautions about her current
		// therapy, while two of her orders sharing a substance is a reason to change it. The prompt's
		// ranking sentence has the model lead with that, so the module's answer does too.
		PatientChart chart = screen(DrugReferenceTestSupport.activeOrder("order-rhze", RHZE),
			DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid 300mg"),
			DrugReferenceTestSupport.activeOrder("order-emb", "Ethambutol 400mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a pair was related, so the module answers: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\n"));
		// Both cautions are counted on one line, and her two shared substances stated on one, before it (ADR Decision 493).
		int shared = -1;
		int cautions = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(DrugReferenceInjector.SHARED_SUBSTANCES_LEAD)) {
				shared = i;
			}
			if (lines.get(i).startsWith("2 more interactions, each a caution (1 Moderate, 1 Minor)")) {
				cautions = i;
			}
		}
		assertTrue(cautions >= 0, "precondition: the pairs are counted in the answer: " + answer);
		assertTrue(shared >= 0 && shared < cautions, "the reasons to change lead the cautions: " + answer);
		assertTrue(lines.get(shared).startsWith(DrugReferenceInjector.SHARED_SUBSTANCES_LEAD + "Ethambutol; Isoniazid. "),
			"was: " + answer);
	}

	@Test
	public void theSharedSubstanceFollowsARatedMajorAndPrecedesACaution() throws IOException {
		// A Major pair about her current therapy and the shared-substance finding state the same clause,
		// a reason to change it, so the strength sort ties them and keeps the order the arm raised them
		// in: the Major, then this finding. Only a caution falls behind it.
		PatientChart chart = screen(
			DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a pair was related, so the module answers: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\n"));
		assertEquals(5, lines.size(), "the count, three findings and the scope (ADR Decision 171), was: " + answer);
		assertEquals("2 interactions among this patient's active medications, and 1 substance carried by more than one "
				+ "of this patient's orders:", lines.get(0), "the shared substance is counted on its own, being no pair");
		assertTrue(lines.get(1).startsWith("Pyrazinamide interacts with active order Rifampicin (rifampin) — Major."),
			"the Major leads: " + answer);
		assertTrue(lines.get(2).startsWith("Rifampicin (rifampin) is in active orders Isoniazid / pyrazinamide /"
				+ " rifampin and Rifampicin 150mg — possible duplicate therapy."), "then this finding: " + answer);
		assertTrue(lines.get(3).startsWith("1 more interaction, a caution (1 Minor), is listed in full in the safety warnings "
				+ "beside this answer. ["), "then the caution, counted (ADR Decision 493): " + answer);
	}

	@Test
	public void aScreenTheCapTruncatedCountsEveryPairItFoundAndSaysHowManyItDoesNotShow() throws IOException {
		// The pairs of theSharedSubstanceFollowsARatedMajorAndPrecedesACaution, with the cap at one: the screen
		// relates two pairs and reports the Major alone. A lead counting the one line it lists would tell a
		// clinician this patient has one interaction when the module found two.
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS, "1");
		PatientChart chart = screen(
			DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a pair was related, so the module answers: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\n"));
		// The answer lists only the Major, so the lead no longer says how many are listed (ADR Decision 493).
		assertEquals("2 interactions among this patient's active medications (1 more, rated no higher, is not shown), and "
				+ "1 substance carried by more than one of this patient's orders:", lines.get(0),
			"the lead counts what the screen FOUND, and says how many the cap left out: " + answer);
		assertTrue(lines.get(1).startsWith("Pyrazinamide interacts with active order Rifampicin (rifampin) — Major."),
			"the Major is the pair listed: " + answer);
		assertTrue(!answer.contains(" — Minor."), "the withheld Minor is not listed: " + answer);
	}

	@Test
	public void aScreenWhoseOnlyInteractionFindingIsTwoOrdersSharingASubstanceCountsNoPair()
			throws IOException {
		// Two orders of one drug: no pair to relate, and the duplicate is the only interaction finding. It is never
		// counted as an interaction; since ADR Decision 228 the module says there is nothing else to check the drug
		// against and states the finding, where the model used to answer.
		PatientChart chart = screen(DrugReferenceTestSupport.activeOrder("order-rif-1", "Rifampicin 150mg"),
			DrugReferenceTestSupport.activeOrder("order-rif-2", "Rifampicin 150mg"));

		assertTrue(chart.getText().contains("Rifampicin (rifampin) is in active orders Rifampicin 150mg (2 orders)"),
			"precondition: the finding reached the prompt: " + chart.getText());
		assertEquals("This patient's 2 active medication orders are all Rifampicin (rifampin), so there is no other "
				+ "active medication to check it against.\nRifampicin (rifampin) is in active orders Rifampicin 150mg (2 "
				+ "orders) — possible duplicate therapy. No severity is rated for this finding. [4]", chart.getModuleAnswer(),
			"no pair was related: " + chart.getModuleAnswer());
	}

	@Test
	public void onAProposalTheFindingFollowsTheProposedDrugsCautionsInTheModulesAnswer() throws IOException {
		// Rifampicin proposed to a patient on two isoniazid orders and pyrazinamide: the Major against
		// pyrazinamide licenses the "No", the Minor against isoniazid is a caution about the drug asked
		// about, and her two isoniazid orders are a finding about her own therapy the question did not
		// ask about. That finding comes after what was asked about, as the chips put it
		// (OrdersSharingASubstanceTest.onAProposalTheFindingFollowsTheProposedDrugsCautions).
		PatientChart chart = ask("Can I give her rifampicin?",
			DrugReferenceTestSupport.activeOrder("order-inh-1", "Isoniazid 300mg"),
			DrugReferenceTestSupport.activeOrder("order-inh-2", "Isoniazid 100mg"),
			DrugReferenceTestSupport.activeOrder("order-pza", "Pyrazinamide 500mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a Major licenses the module's No: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\n"));
		// Her two isoniazid orders are about isoniazid, which the question does not name (ADR Decision 387).
		assertEquals(3, lines.size(), "was: " + answer);
		assertEquals("No — this module's drug-safety check found a reason to withhold Rifampicin (rifampin).",
			lines.get(0), "the lead, off the Major: " + answer);
		assertTrue(lines.get(1).startsWith("Rifampicin (rifampin) interacts with active order Pyrazinamide — Major."),
			"the Major under the No: " + answer);
		assertTrue(lines.get(2).startsWith("Rifampicin (rifampin) interacts with active order Isoniazid")
				&& lines.get(2).contains(" — Minor."), "then the proposed drug's caution: " + answer);
		assertTrue(!answer.contains("possible duplicate therapy"), "and nothing about her own orders: " + answer);
	}

	private static PatientChart screen(PatientClinicalContext.ActiveDrugOrder... orders) throws IOException {
		return ask(DrugReferenceTestSupport.SCREENING_QUESTION, orders);
	}

	private static PatientChart ask(String question, PatientClinicalContext.ActiveDrugOrder... orders)
			throws IOException {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(names), null, null, null, Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}
}
