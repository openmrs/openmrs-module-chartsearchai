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

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal naming ONE combination product — two substance names joined by a hyphen or a slash, "artemether-lumefantrine"
 * — is answered as the proposal it is (ADR Decision 217). On the public demo's data, a patient on lopinavir/ritonavir asked
 * "Can I give him artemether-lumefantrine for malaria?" beside a Major finding against that order was told by the model
 * "The records do not address the safety of giving artemether-lumefantrine": the module answered only a question resolving
 * one substance. Two drugs named apart ("artemether and lumefantrine") still keep the model call.
 */
public class CombinationProductProposalContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalNamingOneCombinationProductIsAnsweredWithItsFindings() {
		for (String question : Arrays.asList("Can I give her artemether-lumefantrine?",
			"Can I give her artemether/lumefantrine?", "Is artemether-lumefantrine safe for her?")) {
			String answer = ask(question).getModuleAnswer();

			assertNotNull(answer, "the module answers " + question);
			assertTrue(answer.startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), question + " was: " + answer);
			assertTrue(answer.contains("Artemether interacts with active order Lopinavir / ritonavir — Major."),
				question + " states the Major finding: " + answer);
		}
	}

	@Test
	public void twoDrugsNamedApartStillAskTheModel() {
		assertNull(ask("Can I give her artemether and lumefantrine?").getModuleAnswer());
	}

	private static PatientChart ask(String question) {
		PatientClinicalContext.ActiveDrugOrder lopinavirRitonavir = DrugReferenceTestSupport.activeOrder("order-lpvr",
			"Lopinavir / ritonavir", DrugReferenceTestSupport.set("Lopinavir / ritonavir"),
			DrugReferenceTestSupport.set("J05AR10"));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
			new LinkedHashSet<String>(lopinavirRitonavir.getNames()),
			new LinkedHashSet<String>(lopinavirRitonavir.getAtcCodes()), null, null, Arrays.asList(lopinavirRitonavir));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}
}
