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
 * The final outcome of one provider turn: the terminal state, the final answer when the turn
 * completed, and the normalized problem code when it failed. This is what the common layer
 * persists and audits; the event stream carries the same information incrementally.
 */
public final class TurnResult {

	private final String providerId;

	private final ProviderMode mode;

	private final TurnEventType terminalState;

	private final AnswerEnvelope answer;

	private final String problemCode;

	private TurnResult(String providerId, ProviderMode mode, TurnEventType terminalState,
			AnswerEnvelope answer, String problemCode) {
		this.providerId = providerId;
		this.mode = mode;
		this.terminalState = terminalState;
		this.answer = answer;
		this.problemCode = problemCode;
	}

	public static TurnResult done(String providerId, ProviderMode mode, AnswerEnvelope answer) {
		return new TurnResult(providerId, mode, TurnEventType.TURN_DONE, answer, null);
	}

	public static TurnResult error(String providerId, ProviderMode mode, String problemCode) {
		return new TurnResult(providerId, mode, TurnEventType.TURN_ERROR, null, problemCode);
	}

	public String getProviderId() {
		return providerId;
	}

	/** The context mode the turn actually ran with; {@code null} when the turn failed before one applied. */
	public ProviderMode getMode() {
		return mode;
	}

	/** {@link TurnEventType#TURN_DONE} or {@link TurnEventType#TURN_ERROR}. */
	public TurnEventType getTerminalState() {
		return terminalState;
	}

	/** The final (grounded, when grounding ran) answer; {@code null} on a failed turn. */
	public AnswerEnvelope getAnswer() {
		return answer;
	}

	/** Normalized machine-readable failure code; {@code null} on a completed turn. */
	public String getProblemCode() {
		return problemCode;
	}
}
