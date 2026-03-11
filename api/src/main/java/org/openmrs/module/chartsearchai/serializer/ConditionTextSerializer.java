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

import org.openmrs.Condition;
import org.springframework.stereotype.Component;

/**
 * Serializes a {@link Condition} into embedding-friendly text.
 *
 * <p>Example output: {@code "Condition: Type 2 Diabetes Mellitus. Status: ACTIVE. Verification:
 * CONFIRMED. Onset: 2019-03-10"}</p>
 */
@Component
public class ConditionTextSerializer implements ClinicalTextSerializer<Condition> {

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

	@Override
	public String toText(Condition condition) {
		StringBuilder sb = new StringBuilder();
		String name = getConditionName(condition);
		if (!name.isEmpty()) {
			sb.append("Condition: ").append(name);
		}
		sb.append(". Status: ").append(condition.getClinicalStatus());

		if (condition.getVerificationStatus() != null) {
			sb.append(". Verification: ").append(condition.getVerificationStatus());
		}
		if (condition.getOnsetDate() != null) {
			sb.append(". Onset: ").append(formatDate(condition.getOnsetDate()));
		}
		if (condition.getAdditionalDetail() != null && !condition.getAdditionalDetail().trim().isEmpty()) {
			sb.append(". Detail: ").append(condition.getAdditionalDetail().trim());
		}
		if (condition.getEndDate() != null) {
			sb.append(". Resolved: ").append(formatDate(condition.getEndDate()));
			if (condition.getEndReason() != null && !condition.getEndReason().trim().isEmpty()) {
				sb.append(" (").append(condition.getEndReason().trim()).append(")");
			}
		}

		return sb.toString();
	}

	private String getConditionName(Condition condition) {
		if (condition.getCondition() == null) {
			return "";
		}
		if (condition.getCondition().getCoded() != null) {
			return ConceptNameUtil.getName(condition.getCondition().getCoded());
		}
		if (condition.getCondition().getNonCoded() != null
				&& !condition.getCondition().getNonCoded().trim().isEmpty()) {
			return condition.getCondition().getNonCoded().trim();
		}
		return "";
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
