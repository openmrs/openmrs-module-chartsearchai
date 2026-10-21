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

	/** Updates activity without overwriting a conversation closed by another request. */
	void touchConversation(Integer conversationId, Date activityAt);

	/**
	 * Loads and locks a conversation for a short write transaction. Turn allocation uses this
	 * before reading the last ordinal so two concurrent requests cannot choose the same number.
	 */
	ClinicalConversation getConversationForUpdate(Integer conversationId);

	ClinicalConversation getConversationByUuid(String uuid);

	ClinicalConversation getLatestActiveConversation(Patient patient, User user);

	/**
	 * Holds the patient's row for the rest of this transaction, so two transactions opening or
	 * replacing the patient's active conversation run one after the other and the second reads
	 * what the first committed, also when no conversation exists yet.
	 */
	void lockConversationsOf(Patient patient);

	ClinicalConversationTurn saveTurn(ClinicalConversationTurn turn);

	/** The stored turn, re-read under a row lock; {@code null} when no such turn exists. */
	ClinicalConversationTurn getTurnForUpdate(Integer turnId);

	ClinicalConversationTurn getTurnByUuid(String uuid);

	List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation);

	int getLastOrdinal(ClinicalConversation conversation);

	/**
	 * Deletes turns completed before the retention horizon (or started before it if unfinished),
	 * then conversation headers with no
	 * surviving turns whose last activity is also before the horizon.
	 */
	int purgeBefore(Date before);
}
