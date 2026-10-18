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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal the interaction check relates to none of her orders, one of which has not started, is answered with that,
 * the scheduled order said to be scheduled (ADR Decision 310). On the 3.7.1 standalone Dora DdiScheduled — nevirapine, and
 * rifampicin scheduled to start later — was answered "The records do not address the safety of Colchicine for him." by the
 * model: Decision 143's answer calls her orders her active medications, and refused. Over the shipped knowledge base.
 */
public class NoPairBesideAScheduledOrderTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalRelatedToNoneOfHerOrdersOneScheduledSaysSo() {
		Date start = new GregorianCalendar(2099, Calendar.JANUARY, 1).getTime();
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			PatientClinicalContext.ActiveDrugOrder.named("order-rif", "Rifampicin 150mg",
				DrugReferenceTestSupport.set("Rifampicin 150mg", "Rifampicin"), null, null, null, start));
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null,
					null, null, orders), "Is colchicine safe for him?").getModuleAnswer();
		assertNotNull(answer, "the module answers");
		assertTrue(answer.matches("The interaction check relates Colchicine to none of this patient's 2 medication orders "
				+ "\\(one of them scheduled and not yet started\\)\\. \\[\\d+\\]"), "was: " + answer);
	}
}
