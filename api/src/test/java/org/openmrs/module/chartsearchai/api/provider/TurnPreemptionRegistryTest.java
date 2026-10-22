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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TurnPreemptionRegistryTest {

	@Test
	public void aSecondTurnOnTheSameConversationCancelsTheFirst() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();

		TurnCancellation first = registry.begin("conversation-1", 1);
		assertFalse(first.isCancelled());

		TurnCancellation second = registry.begin("conversation-1", 1);

		assertTrue(first.isCancelled());
		assertFalse(second.isCancelled());
	}

	@Test
	public void anOlderTurnRegisteringAfterANewerOneIsCancelledInsteadOfCancellingIt() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation newer = registry.begin("conversation", 2);
		TurnCancellation older = registry.begin("conversation", 1);

		assertTrue(older.isCancelled(), "the older question must not run in place of the newer one");
		assertFalse(newer.isCancelled());
		registry.end("conversation", older);
		TurnCancellation next = registry.begin("conversation", 3);
		assertTrue(newer.isCancelled(), "the newer turn still held the slot after the older one ended");
		assertFalse(next.isCancelled());
	}

	@Test
	public void turnsOnDifferentConversationsDoNotCancelEachOther() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();

		TurnCancellation a = registry.begin("conversation-a", 1);
		TurnCancellation b = registry.begin("conversation-b", 1);

		assertFalse(a.isCancelled());
		assertFalse(b.isCancelled());
	}

	@Test
	public void endOnlyClearsTheEntryIfItIsStillTheCurrentTurn() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation first = registry.begin("conversation-1", 1);
		TurnCancellation second = registry.begin("conversation-1", 1);

		// The first turn finishing (e.g. its provider noticed cancellation and returned) must not
		// clear the SECOND turn's still-active entry out from under it.
		registry.end("conversation-1", first);
		TurnCancellation third = registry.begin("conversation-1", 1);

		assertTrue(second.isCancelled(), "second should have been cancelled by third starting");
		assertFalse(third.isCancelled());
	}

	@Test
	public void anOlderTurnRegisteringAfterTheNewerOneFinishedIsStillCancelled() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation newer = registry.begin("conversation-1", 2);
		registry.end("conversation-1", newer);

		TurnCancellation older = registry.begin("conversation-1", 1);

		assertTrue(older.isCancelled(),
				"a turn older than one that already ran must not run its question");
	}

	@Test
	public void endReleasesTheFinishedTurnsCancellation() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation first = registry.begin("conversation-1", 1);

		registry.end("conversation-1", first);
		registry.begin("conversation-1", 1);

		// end() kept the ordinal but released the finished turn's signal, so this second begin()
		// has nothing to cancel.
		assertFalse(first.isCancelled());
	}

	@Test
	public void aFinishedTurnPastItsRetentionIsSweptOnceTheRegistryGrows() {
		long[] now = {0};
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry(() -> now[0], 1);
		registry.end("conversation-1", registry.begin("conversation-1", 2));

		now[0] = TurnPreemptionRegistry.FINISHED_RETENTION_NANOS + 1;
		registry.end("conversation-2", registry.begin("conversation-2", 1));

		assertFalse(registry.begin("conversation-1", 1).isCancelled(),
				"a conversation whose finished turn was swept starts afresh");
	}

	@Test
	public void aFinishedTurnWithinItsRetentionSurvivesTheSweep() {
		long[] now = {0};
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry(() -> now[0], 1);
		registry.end("conversation-1", registry.begin("conversation-1", 2));

		now[0] = TurnPreemptionRegistry.FINISHED_RETENTION_NANOS;
		registry.end("conversation-2", registry.begin("conversation-2", 1));

		assertTrue(registry.begin("conversation-1", 1).isCancelled(),
				"an older turn arriving within the retention is still cancelled");
	}

	@Test
	public void aRunningTurnIsNeverSwept() {
		long[] now = {0};
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry(() -> now[0], 1);
		TurnCancellation running = registry.begin("conversation-1", 1);

		now[0] = TurnPreemptionRegistry.FINISHED_RETENTION_NANOS * 2;
		registry.begin("conversation-2", 1);
		registry.begin("conversation-1", 2);

		assertTrue(running.isCancelled(), "the running turn kept its slot, so the newer one cancels it");
	}
}
