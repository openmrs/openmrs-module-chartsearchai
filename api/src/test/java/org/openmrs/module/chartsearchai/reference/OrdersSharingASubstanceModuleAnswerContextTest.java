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
		int lastShared = -1;
		int firstPair = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains(" — possible duplicate therapy")) {
				lastShared = i;
			}
			if (firstPair < 0 && lines.get(i).contains(" interacts with active order ")) {
				firstPair = i;
			}
		}
		assertTrue(firstPair >= 0, "precondition: the pairs are in the answer: " + answer);
		assertTrue(lastShared >= 0 && lastShared < firstPair, "the reasons to change lead the cautions: " + answer);
		assertTrue(answer.contains("Isoniazid is in active orders " + RHZE + " and Isoniazid 300mg"
				+ " — possible duplicate therapy"), "was: " + answer);
		assertTrue(answer.contains("Ethambutol is in active orders " + RHZE + " and Ethambutol 400mg"
				+ " — possible duplicate therapy"), "was: " + answer);
	}

	@Test
	public void theSharedSubstanceTiesARatedMajorAndFollowsIt() throws IOException {
		// A Major pair about her current therapy and the shared-substance finding state the same clause,
		// a reason to change it, so the strength sort ties them and keeps the order the arms raised them
		// in: the screen's pairs, then this finding. Only a caution falls behind it.
		PatientChart chart = screen(
			DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a pair was related, so the module answers: " + chart.getText());
		List<String> lines = Arrays.asList(answer.split("\\n"));
		assertEquals(3, lines.size(), "was: " + answer);
		assertTrue(lines.get(0).startsWith("Pyrazinamide interacts with active order Rifampicin (rifampin) — Major."),
			"the Major leads: " + answer);
		assertTrue(lines.get(1).startsWith("Rifampicin (rifampin) is in active orders Isoniazid / pyrazinamide /"
				+ " rifampin and Rifampicin 150mg — possible duplicate therapy."), "then this finding: " + answer);
		assertTrue(lines.get(2).startsWith("Isoniazid interacts with active order Rifampicin (rifampin) — Minor."),
			"then the caution: " + answer);
	}

	@Test
	public void aScreenWhoseOnlyInteractionFindingIsTwoOrdersSharingASubstanceGoesToTheModel()
			throws IOException {
		// Two orders of one drug: no pair to relate, and the duplicate is the only interaction finding.
		PatientChart chart = screen(DrugReferenceTestSupport.activeOrder("order-rif-1", "Rifampicin 150mg"),
			DrugReferenceTestSupport.activeOrder("order-rif-2", "Rifampicin 150mg"));

		assertTrue(chart.getText().contains("Rifampicin (rifampin) is in active orders Rifampicin 150mg (2 orders)"),
			"precondition: the finding reached the prompt: " + chart.getText());
		assertNull(chart.getModuleAnswer(), "no pair was related: " + chart.getModuleAnswer());
	}

	private static PatientChart screen(PatientClinicalContext.ActiveDrugOrder... orders) throws IOException {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(names), null, null, null, Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
					DrugReferenceTestSupport.SCREENING_QUESTION);
	}
}
