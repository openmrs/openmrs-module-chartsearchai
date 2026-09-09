/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.conversation;

/**
 * Canonical prose projection of one completed prior turn. Providers receive this projection rather
 * than another provider's persisted JSON envelope, so conversation replay does not couple an
 * engine to persistence format or provider-specific output fields.
 */
public final class PriorClinicalTurn {

	private final String question;

	private final String answer;

	public PriorClinicalTurn(String question, String answer) {
		this.question = question;
		this.answer = answer;
	}

	public String getQuestion() {
		return question;
	}

	public String getAnswer() {
		return answer;
	}
}
