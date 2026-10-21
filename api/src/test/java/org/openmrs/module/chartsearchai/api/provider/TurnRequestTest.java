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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * A request is the turn's input as it was when the turn started, so a caller changing its own list
 * afterwards must not change what the provider replays.
 */
public class TurnRequestTest {

	@Test
	public void theReplayedTurnsAreFixedWhenTheRequestIsBuilt() {
		List<PriorClinicalTurn> callersList = new ArrayList<>();
		callersList.add(new PriorClinicalTurn("What is her latest weight?", "62 kg on 2026-01-04."));

		TurnRequest request = new TurnRequest(null, "And her height?", "conversation", "request",
				ProviderMode.QUERY_SCOPED, null, callersList);
		callersList.add(new PriorClinicalTurn("Added after the turn started", "must not be replayed"));
		callersList.remove(0);

		assertEquals(1, request.getPriorClinicalTurns().size());
		assertEquals("What is her latest weight?", request.getPriorClinicalTurns().get(0).getQuestion());
	}
}
