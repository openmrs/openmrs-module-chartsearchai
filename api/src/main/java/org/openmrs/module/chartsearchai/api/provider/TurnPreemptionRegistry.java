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
 * Tracks the one in-flight turn per conversation by turn ordinal: a newer turn starting cancels
 * the older one holding that conversation's slot, and an older turn arriving late is cancelled
 * instead. At most one turn is ever meant to be active per conversation (asking a new question
 * always supersedes; two turns never run concurrently on purpose), so a new turn starting IS the
 * preempt signal — there is no separate disconnect notification to wait for.
 */
public final class TurnPreemptionRegistry {

	private static final class InFlightTurn {

		final int ordinal;

		final TurnCancellation cancellation;

		InFlightTurn(int ordinal, TurnCancellation cancellation) {
			this.ordinal = ordinal;
			this.cancellation = cancellation;
		}
	}

	private final ConcurrentHashMap<String, InFlightTurn> inFlight = new ConcurrentHashMap<>();

	/**
	 * Registers the in-flight turn {@code ordinal} of {@code conversationUuid} and returns its
	 * cancellation signal. The newer turn by ordinal wins: registering cancels an older turn that
	 * holds the slot, and a turn that registers after a newer one has (its request paused between
	 * creating its turn and arriving here) comes back already cancelled, so an older question never
	 * cancels the newer one and runs in its place.
	 */
	public TurnCancellation begin(String conversationUuid, int ordinal) {
		TurnCancellation next = new TurnCancellation();
		InFlightTurn[] superseded = new InFlightTurn[1];
		inFlight.compute(conversationUuid, (uuid, current) -> {
			if (current != null && current.ordinal > ordinal) {
				superseded[0] = new InFlightTurn(ordinal, next);
				return current;
			}
			superseded[0] = current;
			return new InFlightTurn(ordinal, next);
		});
		if (superseded[0] != null && superseded[0].cancellation != null) {
			superseded[0].cancellation.cancel();
		}
		return next;
	}

	/**
	 * Releases the slot of a turn that has finished, only if it still holds it: a newer turn may
	 * have replaced it, and that entry must survive this call. The finished turn's ordinal is kept,
	 * so an older turn whose request paused until after it finished still comes back cancelled
	 * rather than running its older question; one ordinal per conversation is all that stays.
	 */
	public void end(String conversationUuid, TurnCancellation cancellation) {
		inFlight.computeIfPresent(conversationUuid,
				(uuid, current) -> current.cancellation == cancellation
						? new InFlightTurn(current.ordinal, null) : current);
	}
}
