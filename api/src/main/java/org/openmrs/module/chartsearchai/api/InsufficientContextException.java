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
 * Thrown when a patient's mandatory clinical evidence (demographics, allergies, active
 * conditions) alone exceeds the LLM's available input budget, before any optional context is
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

	private final List<String> mandatoryRecordIds;

	public InsufficientContextException(String message, List<String> mandatoryRecordIds) {
		super(message);
		this.mandatoryRecordIds = mandatoryRecordIds == null ? Collections.<String> emptyList()
				: Collections.unmodifiableList(mandatoryRecordIds);
	}

	/** Stable resource uuids of the mandatory records that alone exceeded the budget. */
	public List<String> getMandatoryRecordIds() {
		return mandatoryRecordIds;
	}
}
