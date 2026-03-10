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

import java.text.SimpleDateFormat;
import java.util.Date;

import org.openmrs.Concept;
import org.openmrs.Order;
import org.springframework.stereotype.Component;

/**
 * Serializes an {@link Order} into embedding-friendly text.
 *
 * <p>Example output: {@code "Order: Complete Blood Count. Action: NEW. Urgency: STAT.
 * Reason: Suspected anemia. Date: 2024-01-15"}</p>
 */
@Component
public class OrderTextSerializer implements ClinicalTextSerializer<Order> {

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

	@Override
	public String toText(Order order) {
		StringBuilder sb = new StringBuilder();
		sb.append("Order: ").append(getConceptName(order.getConcept()));
		sb.append(". Action: ").append(order.getAction());
		sb.append(". Urgency: ").append(order.getUrgency());

		if (order.getInstructions() != null && !order.getInstructions().trim().isEmpty()) {
			sb.append(". Instructions: ").append(order.getInstructions().trim());
		}
		if (order.getOrderReason() != null) {
			sb.append(". Reason: ").append(getConceptName(order.getOrderReason()));
		} else if (order.getOrderReasonNonCoded() != null
				&& !order.getOrderReasonNonCoded().trim().isEmpty()) {
			sb.append(". Reason: ").append(order.getOrderReasonNonCoded().trim());
		}
		sb.append(". Date: ").append(formatDate(order.getDateActivated()));

		if (order.getDateStopped() != null) {
			sb.append(". Stopped: ").append(formatDate(order.getDateStopped()));
		}

		return sb.toString();
	}

	private String getConceptName(Concept concept) {
		if (concept == null) {
			return "Unknown";
		}
		if (concept.getName() != null) {
			return concept.getName().getName();
		}
		return "Concept:" + concept.getConceptId();
	}

	private String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		synchronized (DATE_FORMAT) {
			return DATE_FORMAT.format(date);
		}
	}
}
