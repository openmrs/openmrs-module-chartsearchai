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
 * The module's own statement that the patient's chart holds no active order for drugs a question listed
 * as hers — issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>,
 * ADR Decision 119. <em>"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to give
 * Amlodipine?"</em>, asked of a chart holding none of the three, was answered against the list; nothing in
 * the response said the chart disagreed with it.
 *
 * <p>Which drugs is {@code PatientChart.getListedDrugsWithNoActiveOrder()}'s, stamped by the pre-answer
 * pass, and this class decides nothing about them. It APPENDS, as {@link EndedOrderStatement} does for a
 * drug the chart holds only as an ended order: no marker, since it offers no record; no chip; no prompt
 * change; and never a word about whether any drug may be given.
 *
 * <p><b>"No active order", and not "no order".</b> The pass names a drug only where no drug-order record
 * of the chart it built names it, and in a query-scoped chart that need not be every order she ever had.
 * What the pass's gates do guarantee is her ACTIVE orders, read in full and every one resolved.
 */
final class ListedDrugStatement {

	private ListedDrugStatement() {
	}

	/**
	 * {@code answer} with one sentence naming {@code names} appended, or {@code answer} unchanged where
	 * there are none — joined as "A", "A or B", "A, B or C".
	 */
	static String withListedDrugsStated(String answer, List<String> names) {
		if (names == null || names.isEmpty() || answer == null) {
			return answer;
		}
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim()));
		sb.append(" The chart holds no active order for ");
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) {
				sb.append(i == names.size() - 1 ? " or " : ", ");
			}
			sb.append(names.get(i));
		}
		// Trimmed so a blank answer takes the sentence without a leading space.
		return sb.append('.').toString().trim();
	}
}
