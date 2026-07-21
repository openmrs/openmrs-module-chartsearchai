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
 * One event in a provider turn's canonical lifecycle, as delivered to a {@link TurnEventSink}.
 * Which payload field is populated depends on the {@link TurnEventType}: delta events carry
 * {@link #getTextDelta()}, answer/evidence events carry {@link #getAnswer()}, and
 * {@code turn_error} carries {@link #getProblemCode()}. Every event identifies its emitting
 * provider and carries a per-turn monotonically increasing sequence number.
 */
public final class TurnEvent {

	private final TurnEventType type;

	private final int sequence;

	private final String providerId;

	private final String textDelta;

	private final AnswerEnvelope answer;

	private final String problemCode;

	private TurnEvent(TurnEventType type, int sequence, String providerId, String textDelta,
			AnswerEnvelope answer, String problemCode) {
		this.type = type;
		this.sequence = sequence;
		this.providerId = providerId;
		this.textDelta = textDelta;
		this.answer = answer;
		this.problemCode = problemCode;
	}

	public static TurnEvent of(TurnEventType type, int sequence, String providerId) {
		return new TurnEvent(type, sequence, providerId, null, null, null);
	}

	public static TurnEvent delta(TurnEventType type, int sequence, String providerId, String textDelta) {
		return new TurnEvent(type, sequence, providerId, textDelta, null, null);
	}

	public static TurnEvent withAnswer(TurnEventType type, int sequence, String providerId,
			AnswerEnvelope answer) {
		return new TurnEvent(type, sequence, providerId, null, answer, null);
	}

	public static TurnEvent error(int sequence, String providerId, String problemCode) {
		return new TurnEvent(TurnEventType.TURN_ERROR, sequence, providerId, null, null, problemCode);
	}

	public TurnEventType getType() {
		return type;
	}

	public int getSequence() {
		return sequence;
	}

	public String getProviderId() {
		return providerId;
	}

	/** Incremental answer or reasoning text; only populated on delta events. */
	public String getTextDelta() {
		return textDelta;
	}

	/** The answer as of this event; populated on {@code answer_done} and {@code evidence_updated}. */
	public AnswerEnvelope getAnswer() {
		return answer;
	}

	/** Normalized machine-readable failure code; only populated on {@code turn_error}. */
	public String getProblemCode() {
		return problemCode;
	}
}
