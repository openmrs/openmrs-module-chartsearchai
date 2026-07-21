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

import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;

/** Persistence boundary for provider-neutral clinical conversations and turns. */
public interface ConversationDAO {

	ClinicalConversation saveConversation(ClinicalConversation conversation);

	ClinicalConversation getConversation(Integer conversationId);

	ClinicalConversation getConversationByUuid(String uuid);

	ClinicalConversation getLatestActiveConversation(Patient patient, User user);

	ClinicalConversationTurn saveTurn(ClinicalConversationTurn turn);

	ClinicalConversationTurn getTurnByUuid(String uuid);

	List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation);

	int getLastOrdinal(ClinicalConversation conversation);

	/**
	 * Deletes turns completed before the retention horizon, then conversation headers with no
	 * surviving turns whose last activity is also before the horizon.
	 */
	int purgeBefore(Date before);
}
