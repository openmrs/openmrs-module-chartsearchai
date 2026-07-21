/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

import org.openmrs.Patient;

/**
 * One turn's input to a {@link ClinicalAnswerProvider}. The common layer resolves and authorizes
 * the patient before constructing a request, so providers receive an already-authorized
 * {@link Patient} and never re-implement access control.
 */
public final class TurnRequest {

	private final Patient patient;

	private final String question;

	private final String conversationId;

	private final String requestId;

	private final ProviderMode mode;

	/**
	 * @param mode the caller-requested context mode, or {@code null} to use the provider's
	 *        configured default; a provider must fail explicitly rather than silently substitute
	 *        a mode it does not offer
	 */
	public TurnRequest(Patient patient, String question, String conversationId, String requestId,
			ProviderMode mode) {
		this.patient = patient;
		this.question = question;
		this.conversationId = conversationId;
		this.requestId = requestId;
		this.mode = mode;
	}

	public Patient getPatient() {
		return patient;
	}

	public String getQuestion() {
		return question;
	}

	public String getConversationId() {
		return conversationId;
	}

	public String getRequestId() {
		return requestId;
	}

	public ProviderMode getMode() {
		return mode;
	}
}
