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

import java.util.List;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;

/**
 * Common OpenMRS ownership boundary for provider-neutral conversation lifecycle, persistence, and
 * audit. Providers execute answers; this service records who asked, which provider/mode served the
 * turn, and the provider's complete content-agnostic answer envelope.
 */
public interface ConversationService {

	/**
	 * Reuses the current user's active conversation only when patient, provider, and mode all match.
	 * Selecting another provider or mode closes the old conversation and starts a new one.
	 */
	ClinicalConversation openOrCreate(Patient patient, String providerId, ProviderMode mode);

	/**
	 * Always closes any active conversation for the current user/patient and opens a fresh empty
	 * one for the given provider/mode. Used by {@code POST /chat/new}.
	 */
	ClinicalConversation startNew(Patient patient, String providerId, ProviderMode mode);

	ClinicalConversation getByUuid(String uuid);

	/**
	 * The current authenticated user's active conversation for this patient, or {@code null} if
	 * none exists. Lets chat history be recovered without a client-supplied session id — a fresh
	 * page load (reload) has no session to send, only the patient it is looking at.
	 */
	ClinicalConversation getLatestActiveConversation(Patient patient);

	List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation);

	ClinicalConversationTurn startTurn(ClinicalConversation conversation, String requestId, String question);

	ClinicalConversationTurn finishTurn(ClinicalConversationTurn turn, TurnResult result, long responseTimeMs);

	/** Completed successful turns projected to canonical prose for the provider's next request. */
	List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation);
}
