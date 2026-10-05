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

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * For cases about another sentence of an answer, written before ADR Decision 147: the answer without the statement that
 * decision appends, which those cases' fixtures — proposals whose model answer leaves a finding against her own order
 * uncited — now carry. What the statement says is pinned by the cases of
 * {@code LlmInferenceServiceListedMedicationsContextTest} that exist for it; this only takes it off the end.
 */
final class OwnOrderFindingStatementTestSupport {

	private OwnOrderFindingStatementTestSupport() {
	}

	/**
	 * @return {@code answer} with the ADR Decision 147 statement taken off its end, or {@code answer} where it carries
	 *         none — failing where the statement is followed by a sentence another of the module's completions writes
	 */
	static String withoutTheOwnOrderStatement(String answer) {
		int at = answer.indexOf(" " + OwnOrderFindingStatement.LEAD + " ");
		if (at < 0) {
			return answer;
		}
		String statement = answer.substring(at);
		assertFalse(statement.contains("Also covered by those findings"),
				"the statement is the last sentence the module appends here, was: " + answer);
		return answer.substring(0, at);
	}
}
