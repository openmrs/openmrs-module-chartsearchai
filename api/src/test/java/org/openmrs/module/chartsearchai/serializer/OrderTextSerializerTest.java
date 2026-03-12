/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Order;
import java.util.Locale;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class OrderTextSerializerTest extends BaseModuleContextSensitiveTest {

	private OrderTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new OrderTextSerializer();
	}

	@Test
	public void toText_shouldSerializeBasicOrder() {
		Order order = new Order();
		Concept concept = new Concept();
		concept.addName(conceptName("Complete Blood Count"));
		order.setConcept(concept);
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.ROUTINE);
		order.setDateActivated(new Date());

		String result = serializer.toText(order);
		assertTrue(result.contains("Order: Complete Blood Count"));
		assertTrue(result.contains("Action: NEW"));
		assertTrue(result.contains("Urgency: ROUTINE"));
		assertTrue(result.contains("Date:"));
	}

	@Test
	public void toText_shouldIncludeInstructions() {
		Order order = new Order();
		Concept concept = new Concept();
		concept.addName(conceptName("X-Ray Chest"));
		order.setConcept(concept);
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.STAT);
		order.setInstructions("PA and lateral views");
		order.setDateActivated(new Date());

		String result = serializer.toText(order);
		assertTrue(result.contains("Instructions: PA and lateral views"));
	}

	@Test
	public void toText_shouldIncludeCodedReason() {
		Order order = new Order();
		Concept concept = new Concept();
		concept.addName(conceptName("Hemoglobin"));
		order.setConcept(concept);
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.ROUTINE);
		order.setDateActivated(new Date());

		Concept reason = new Concept();
		reason.addName(conceptName("Suspected anemia"));
		order.setOrderReason(reason);

		String result = serializer.toText(order);
		assertTrue(result.contains("Reason: Suspected anemia"));
	}

	@Test
	public void toText_shouldIncludeNonCodedReason() {
		Order order = new Order();
		Concept concept = new Concept();
		concept.addName(conceptName("Urinalysis"));
		order.setConcept(concept);
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.ROUTINE);
		order.setDateActivated(new Date());
		order.setOrderReasonNonCoded("Follow-up after UTI treatment");

		String result = serializer.toText(order);
		assertTrue(result.contains("Reason: Follow-up after UTI treatment"));
	}

	@Test
	public void toText_shouldIncludeUrgency() {
		Order order = new Order();
		Concept concept = new Concept();
		concept.addName(conceptName("Blood Culture"));
		order.setConcept(concept);
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.STAT);
		order.setDateActivated(new Date());

		String result = serializer.toText(order);
		assertTrue(result.contains("Urgency: STAT"));
	}

	private ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}
}
