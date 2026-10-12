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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen of a patient with ONE active medication order (ADR Decision 178). On the 3.7.1 standalone the injection
 * found nothing to inject for such a chart and returned before composing, so the model answered — <em>"The records do
 * not address drug interactions."</em>, or, for "What interactions does he have?", a list of his encounters. The
 * module states what is true of the chart, as it does for a patient with no order at all.
 */
public class OneActiveOrderScreenContextTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aScreenOfOneOrderSaysThereIsNothingToCheckItAgainst() {
		PatientChart chart = screen("Does she have any drug interactions?",
			DrugReferenceTestSupport.activeOrder("order-apap", "Acetaminophen", "Acetaminophen"));

		assertEquals("This patient has one active medication order, Acetaminophen, so there is no other active medication "
				+ "to check it against.", chart.getModuleAnswer(), chart.getText());
	}

	@Test
	public void aScheduledOrderIsSaidToBeScheduledAndNotActive() {
		// Issue #553's shape: an order that has not started is the module's "scheduled order" everywhere else, and is
		// not one she is taking.
		Date start = new GregorianCalendar(2099, Calendar.JANUARY, 1).getTime();
		PatientChart chart = screen("Does she have any drug interactions?",
			PatientClinicalContext.ActiveDrugOrder.named("order-rif", "Rifampicin 150mg",
				DrugReferenceTestSupport.set("Rifampicin 150mg", "Rifampicin"), null, null, null, start));

		assertEquals("This patient has one medication order, Rifampicin 150mg ("
				+ PatientChartSerializer.scheduledToStart(start)
				+ "), so there is no other active medication to check it against.", chart.getModuleAnswer(),
			chart.getText());
	}

	@Test
	public void twoOrdersOfOneDrugAreNotOneOrder() {
		PatientChart chart = screen("Does she have any drug interactions?",
			DrugReferenceTestSupport.activeOrder("order-apap-1", "Acetaminophen 500mg", "Acetaminophen"),
			DrugReferenceTestSupport.activeOrder("order-apap-2", "Acetaminophen 1g", "Acetaminophen"));

		String answer = chart.getModuleAnswer();
		assertNull(answer == null || !answer.contains("one active medication order") ? null : answer,
			"two orders are never told they are one: " + answer);
	}

	@Test
	public void aQuestionThatIsNotAScreenIsNotAnsweredSo() {
		PatientChart chart = screen("What is her blood pressure?",
			DrugReferenceTestSupport.activeOrder("order-apap", "Acetaminophen", "Acetaminophen"));

		assertNull(chart.getModuleAnswer(), "not a screen: " + chart.getModuleAnswer());
	}

	private static PatientChart screen(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, names, null, null, null,
			Arrays.asList(orders));
		// The chart carries each order's own drug_order record, as querystore's does, so no order is unrepresented.
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, order.getUuid(),
					order.getDisplay() + ", 1 daily", null));
		}
		PatientChart base = new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(base, context, question);
	}
}
