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

		TurnCancellation first = registry.begin("conversation-1");
		assertFalse(first.isCancelled());

		TurnCancellation second = registry.begin("conversation-1");

		assertTrue(first.isCancelled());
		assertFalse(second.isCancelled());
	}

	@Test
	public void turnsOnDifferentConversationsDoNotCancelEachOther() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();

		TurnCancellation a = registry.begin("conversation-a");
		TurnCancellation b = registry.begin("conversation-b");

		assertFalse(a.isCancelled());
		assertFalse(b.isCancelled());
	}

	@Test
	public void endOnlyClearsTheEntryIfItIsStillTheCurrentTurn() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation first = registry.begin("conversation-1");
		TurnCancellation second = registry.begin("conversation-1");

		// The first turn finishing (e.g. its provider noticed cancellation and returned) must not
		// clear the SECOND turn's still-active entry out from under it.
		registry.end("conversation-1", first);
		TurnCancellation third = registry.begin("conversation-1");

		assertTrue(second.isCancelled(), "second should have been cancelled by third starting");
		assertFalse(third.isCancelled());
	}

	@Test
	public void endClearsTheEntryWhenItIsStillCurrent() {
		TurnPreemptionRegistry registry = new TurnPreemptionRegistry();
		TurnCancellation first = registry.begin("conversation-1");

		registry.end("conversation-1", first);
		TurnCancellation second = registry.begin("conversation-1");

		// Nothing was left registered for "conversation-1" after end(), so this second begin()
		// must not find (and cancel) anything.
		assertFalse(second.isCancelled());
	}
}
