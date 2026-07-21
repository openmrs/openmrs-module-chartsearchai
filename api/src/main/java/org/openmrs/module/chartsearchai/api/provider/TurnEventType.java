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

/**
 * One event in the canonical provider turn lifecycle:
 *
 * <pre>
 * turn_started -&gt; reasoning_delta* -&gt; answer_delta* -&gt; answer_done
 *   -&gt; answer_validation? -&gt; evidence_updated? -&gt; indepth_pending?
 *   -&gt; (indepth_done | indepth_error)? -&gt; (turn_done | turn_error)
 * </pre>
 *
 * Every provider — bundled or hub — is normalized into this one contract so the ESM needs a
 * single parser and reducer. {@code answer_done} and exactly one terminal event are required
 * for a completed turn; the optional events are capability-driven (see
 * {@link TurnLifecycleValidator}). Wire names are the stable identifiers shared with the
 * dual-provider conformance fixtures.
 */
public enum TurnEventType {

	TURN_STARTED("turn_started"),

	REASONING_DELTA("reasoning_delta"),

	ANSWER_DELTA("answer_delta"),

	ANSWER_DONE("answer_done"),

	ANSWER_VALIDATION("answer_validation"),

	EVIDENCE_UPDATED("evidence_updated"),

	INDEPTH_PENDING("indepth_pending"),

	INDEPTH_DONE("indepth_done"),

	INDEPTH_ERROR("indepth_error"),

	TURN_DONE("turn_done"),

	TURN_ERROR("turn_error");

	private final String wireName;

	TurnEventType(String wireName) {
		this.wireName = wireName;
	}

	public String getWireName() {
		return wireName;
	}

	/** Whether this event ends the turn. Exactly one terminal event is required, and it is last. */
	public boolean isTerminal() {
		return this == TURN_DONE || this == TURN_ERROR;
	}

	/**
	 * Resolves a stable wire name (e.g. {@code "answer_done"}) to its event type.
	 *
	 * @throws IllegalArgumentException when the name is not a known event type
	 */
	public static TurnEventType fromWireName(String wireName) {
		for (TurnEventType type : values()) {
			if (type.wireName.equals(wireName)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown turn event type: " + wireName);
	}
}
