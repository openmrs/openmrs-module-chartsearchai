/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when a patient's required clinical evidence (mandatory safety records plus exact,
 * typed-complete, and panel records) exceeds the LLM's available input budget before optional context is
 * even considered. Distinct from {@link ChartTooLargeException}: that one is llama-server's own
 * after-the-fact rejection of a prompt that turned out too big; this one is a proactive,
 * specifically-diagnosed abstention — the same {@code insufficient_context} outcome the
 * dual-provider conformance fixture's {@code context.mandatory-overflow-abstains} case and
 * med-agent-hub's {@code InsufficientContextError} already use. Mandatory evidence is never
 * droppable (ADR Decision 17), so there is nothing left to trim — the turn must abstain rather
 * than silently omit safety-critical content.
 */
public class InsufficientContextException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> requiredRecordIds;

	public InsufficientContextException(String message, List<String> requiredRecordIds) {
		super(message);
		this.requiredRecordIds = requiredRecordIds == null ? Collections.<String> emptyList()
				: Collections.unmodifiableList(requiredRecordIds);
	}

	/** Stable resource uuids of the required records that together exceeded the budget. */
	public List<String> getRequiredRecordIds() {
		return requiredRecordIds;
	}
}
