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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/** One outbound request from {@link HubClinicalAnswerProvider} to med-agent-hub. */
public final class HubCallRequest {

	private final String endpointUrl;

	private final String profileId;

	private final String patientUuid;

	private final String conversationId;

	private final String requestId;

	private final String question;

	private final List<PriorClinicalTurn> priorTurns;

	private final AccountContext accountContext;

	private final Map<String, Object> execution;

	public HubCallRequest(String endpointUrl, String profileId, String patientUuid,
			String conversationId, String requestId, String question,
			List<PriorClinicalTurn> priorTurns, AccountContext accountContext) {
		this(endpointUrl, profileId, patientUuid, conversationId, requestId, question, priorTurns,
				accountContext, null);
	}

	public HubCallRequest(String endpointUrl, String profileId, String patientUuid,
			String conversationId, String requestId, String question,
			List<PriorClinicalTurn> priorTurns, AccountContext accountContext,
			Map<String, Object> execution) {
		this.endpointUrl = endpointUrl;
		this.profileId = profileId;
		this.patientUuid = patientUuid;
		this.conversationId = conversationId;
		this.requestId = requestId;
		this.question = question;
		this.priorTurns = priorTurns == null ? Collections.emptyList()
				: Collections.unmodifiableList(priorTurns);
		this.accountContext = accountContext == null ? AccountContext.unavailable() : accountContext;
		this.execution = execution == null ? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(execution));
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public String getProfileId() {
		return profileId;
	}

	public String getPatientUuid() {
		return patientUuid;
	}

	public String getConversationId() {
		return conversationId;
	}

	public String getRequestId() {
		return requestId;
	}

	public String getQuestion() {
		return question;
	}

	public List<PriorClinicalTurn> getPriorTurns() {
		return priorTurns;
	}

	public AccountContext getAccountContext() {
		return accountContext;
	}

	public Map<String, Object> getExecution() {
		return execution;
	}
}
