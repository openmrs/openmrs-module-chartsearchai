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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the one in-flight turn per conversation and cancels whichever turn currently holds that
 * slot when a new turn starts for the same conversation. At most one turn is ever meant to be
 * active per conversation (asking a new question always supersedes; two turns never run
 * concurrently on purpose), so a new turn starting IS the preempt signal — there is no separate
 * disconnect notification to wait for.
 */
public final class TurnPreemptionRegistry {

	private final ConcurrentHashMap<String, TurnCancellation> inFlight = new ConcurrentHashMap<>();

	/**
	 * Registers a new in-flight turn for {@code conversationUuid}, cancelling whichever turn
	 * currently holds that slot (if any). Returns the new turn's cancellation signal.
	 */
	public TurnCancellation begin(String conversationUuid) {
		TurnCancellation next = new TurnCancellation();
		TurnCancellation previous = inFlight.put(conversationUuid, next);
		if (previous != null) {
			previous.cancel();
		}
		return next;
	}

	/**
	 * Clears the registry entry for a turn that has finished. Only removes the entry if it is
	 * still {@code cancellation} — a newer turn may have already replaced it, and that newer
	 * entry must survive this call.
	 */
	public void end(String conversationUuid, TurnCancellation cancellation) {
		inFlight.remove(conversationUuid, cancellation);
	}
}
