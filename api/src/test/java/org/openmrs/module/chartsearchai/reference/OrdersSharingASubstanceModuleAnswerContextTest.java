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
	public void aScreenThatRelatedAPairIsAnsweredByTheModuleAndNamesTheSharedSubstance() throws IOException {
		// The four-drug combination beside an ethambutol order: the fixture rates ethambutol against
		// isoniazid, and both orders carry ethambutol.
		PatientChart chart = screen(DrugReferenceTestSupport.activeOrder("order-rhze", RHZE),
			DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid 300mg"),
			DrugReferenceTestSupport.activeOrder("order-emb", "Ethambutol 400mg"));

		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "a pair was related, so the module answers: " + chart.getText());
		assertTrue(answer.contains(" interacts with active order "), "the pair: " + answer);
		assertTrue(answer.contains(" — possible duplicate therapy"), "and the shared substances: " + answer);
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
