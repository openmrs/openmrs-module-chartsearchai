/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.List;

import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;

/**
 * The module's own statement of which of her orders are in the drug class a question names, where the class's WHO ATC
 * group IS that class — ADR Decision 275. <em>"Is it safe to give her a statin?"</em> was told the class was not resolved
 * to any substance, of a patient already on simvastatin. Which orders is
 * {@code PatientChart.getOrdersInTheNamedClass()}'s, stamped by the pre-answer pass; this class decides nothing about them.
 * Like {@link ListedDrugStatement} it APPENDS: no marker, no chip, no prompt change, and never a word about whether the
 * class may be given. It is not the class note, whose citable words name no member (ADR Decision 67).
 */
final class DrugClassMembersStatement {

	private DrugClassMembersStatement() {
	}

	/**
	 * {@code answer} with one sentence naming {@code orders} under {@code group} appended — or, for an empty list, that none
	 * of her medications is there — else {@code answer} unchanged where there is no statement ({@code null}).
	 */
	static String withMembersStated(String answer, String group, List<String> orders) {
		if (answer == null || group == null || orders == null) {
			return answer;
		}
		return (DrugSafetyValidator.endSentence(answer.trim()) + " " + statement(group, orders)).trim();
	}

	static String statement(String group, List<String> orders) {
		if (orders.isEmpty()) {
			return "None of this patient's active medication orders is in that class (ATC group " + group + ").";
		}
		StringBuilder sb = new StringBuilder("Already among this patient's active medication orders in that class (ATC group ")
				.append(group).append("): ");
		for (int i = 0; i < orders.size(); i++) {
			if (i > 0) {
				sb.append(i == orders.size() - 1 ? " and " : ", ");
			}
			sb.append(orders.get(i));
		}
		return sb.append('.').toString();
	}
}
