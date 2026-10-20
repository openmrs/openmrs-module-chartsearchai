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

/**
 * The module's own statement, after the model's answer, that no interaction check was run for a word standing where a drug
 * question names its drug, which the drug data neither recognises nor finds a near name for — ADR Decision 384. Such a
 * word may be no drug at all ("Can I give her oranges?"), so the question takes the chart answer every other question
 * gets; the statement keeps that answer from reading as a check that was made. Which statement is {@code
 * PatientChart.getUncheckedWordStatement()}'s, stamped by the pre-answer pass. It APPENDS, on a line of its own: no
 * marker, no chip, no prompt change.
 */
final class UncheckedWordStatement {

	private UncheckedWordStatement() {
	}

	/** {@code answer} with {@code statement} on a line after it, or {@code answer} unchanged where there is none. */
	static String withUncheckedWordStated(String answer, String statement) {
		if (answer == null || statement == null || statement.isEmpty()) {
			return answer;
		}
		return answer.trim() + "\n" + statement;
	}
}
