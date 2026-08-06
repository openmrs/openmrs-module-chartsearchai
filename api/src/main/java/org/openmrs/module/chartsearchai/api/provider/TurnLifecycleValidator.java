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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates one provider turn's ordered event sequence against the canonical lifecycle and the
 * provider's advertised capabilities (see {@link TurnEventType} for the lifecycle and
 * {@link ProviderCapability} for the capability-gating of optional events).
 *
 * <p>The rules it enforces:</p>
 * <ol>
 *   <li>A turn begins with exactly one {@code turn_started}.</li>
 *   <li>A turn ends with exactly one terminal event ({@code turn_done} or {@code turn_error}),
 *       and nothing follows it.</li>
 *   <li>{@code turn_done} requires a preceding {@code answer_done}; {@code turn_error} does not,
 *       because a turn may fail before any answer exists.</li>
 *   <li>Events follow the canonical stage order; only the delta events may repeat, and the
 *       reasoning stream ends once answer deltas begin.</li>
 *   <li>Optional events require their advertised capability: deltas require
 *       {@code token_streaming}; {@code answer_validation} requires {@code answer_check} or
 *       {@code answer_review}; {@code evidence_updated} requires {@code grounding};
 *       In-Depth events require {@code indepth}; producing an answer requires {@code answer}.</li>
 *   <li>{@code indepth_done}/{@code indepth_error} require a preceding {@code indepth_pending},
 *       and at most one of them may occur.</li>
 * </ol>
 */
public final class TurnLifecycleValidator {

	/** Canonical position of each event type; validation requires non-decreasing stages. */
	private static final Map<TurnEventType, Integer> STAGES = new EnumMap<>(TurnEventType.class);

	static {
		STAGES.put(TurnEventType.TURN_STARTED, 0);
		STAGES.put(TurnEventType.REASONING_DELTA, 1);
		STAGES.put(TurnEventType.ANSWER_DELTA, 2);
		STAGES.put(TurnEventType.ANSWER_DONE, 3);
		STAGES.put(TurnEventType.ANSWER_VALIDATION, 4);
		STAGES.put(TurnEventType.EVIDENCE_UPDATED, 5);
		STAGES.put(TurnEventType.INDEPTH_PENDING, 6);
		STAGES.put(TurnEventType.INDEPTH_DONE, 7);
		STAGES.put(TurnEventType.INDEPTH_ERROR, 7);
		STAGES.put(TurnEventType.TURN_DONE, 8);
		STAGES.put(TurnEventType.TURN_ERROR, 8);
	}

	private TurnLifecycleValidator() {
	}

	/**
	 * Checks a complete turn's event sequence.
	 *
	 * @param capabilities the capabilities the emitting provider advertises
	 * @param events the turn's events in emission order
	 * @return every violated lifecycle rule; empty when the sequence conforms
	 */
	public static List<String> violations(Set<ProviderCapability> capabilities, List<TurnEventType> events) {
		List<String> violations = new ArrayList<>();

		if (events == null || events.isEmpty()) {
			violations.add("a turn must contain events");
			return violations;
		}
		if (events.get(0) != TurnEventType.TURN_STARTED) {
			violations.add("a turn must begin with turn_started");
		}

		Map<TurnEventType, Integer> counts = new EnumMap<>(TurnEventType.class);
		int previousStage = -1;
		boolean orderViolated = false;
		int terminalIndex = -1;
		for (int i = 0; i < events.size(); i++) {
			TurnEventType event = events.get(i);
			counts.merge(event, 1, Integer::sum);
			if (event == TurnEventType.HEARTBEAT) {
				continue;
			}
			int stage = STAGES.get(event);
			if (stage < previousStage) {
				orderViolated = true;
			}
			previousStage = stage;
			if (event.isTerminal() && terminalIndex < 0) {
				terminalIndex = i;
			}
		}
		if (orderViolated) {
			violations.add("events must follow the canonical stage order");
		}

		int terminalCount = count(counts, TurnEventType.TURN_DONE) + count(counts, TurnEventType.TURN_ERROR);
		if (terminalCount == 0) {
			violations.add("a turn must end with turn_done or turn_error");
		} else if (terminalCount > 1) {
			violations.add("a turn must contain exactly one terminal event");
		} else if (terminalIndex != events.size() - 1) {
			violations.add("no events may follow the terminal event");
		}

		for (TurnEventType onceOnly : new TurnEventType[] { TurnEventType.TURN_STARTED,
				TurnEventType.ANSWER_DONE, TurnEventType.ANSWER_VALIDATION, TurnEventType.EVIDENCE_UPDATED,
				TurnEventType.INDEPTH_PENDING }) {
			if (count(counts, onceOnly) > 1) {
				violations.add(onceOnly.getWireName() + " may occur at most once");
			}
		}

		boolean answered = count(counts, TurnEventType.ANSWER_DONE) > 0;
		if (count(counts, TurnEventType.TURN_DONE) > 0 && !answered) {
			violations.add("turn_done requires a preceding answer_done");
		}

		int inDepthOutcomes = count(counts, TurnEventType.INDEPTH_DONE)
				+ count(counts, TurnEventType.INDEPTH_ERROR);
		if (inDepthOutcomes > 1) {
			violations.add("at most one of indepth_done or indepth_error may occur");
		}
		if (inDepthOutcomes > 0 && count(counts, TurnEventType.INDEPTH_PENDING) == 0) {
			violations.add("an In-Depth outcome requires a preceding indepth_pending");
		}

		requireCapability(violations, capabilities, ProviderCapability.ANSWER,
				answered || count(counts, TurnEventType.ANSWER_DELTA) > 0,
				"producing an answer");
		requireCapability(violations, capabilities, ProviderCapability.TOKEN_STREAMING,
				count(counts, TurnEventType.REASONING_DELTA) + count(counts, TurnEventType.ANSWER_DELTA) > 0,
				"delta events");
		if (count(counts, TurnEventType.ANSWER_VALIDATION) > 0
				&& !capabilities.contains(ProviderCapability.ANSWER_CHECK)
				&& !capabilities.contains(ProviderCapability.ANSWER_REVIEW)) {
			violations.add("answer_validation requires the answer_check or answer_review capability");
		}
		requireCapability(violations, capabilities, ProviderCapability.GROUNDING,
				count(counts, TurnEventType.EVIDENCE_UPDATED) > 0, "evidence_updated");
		requireCapability(violations, capabilities, ProviderCapability.INDEPTH,
				count(counts, TurnEventType.INDEPTH_PENDING) + inDepthOutcomes > 0, "In-Depth events");

		return violations;
	}

	private static int count(Map<TurnEventType, Integer> counts, TurnEventType event) {
		return counts.getOrDefault(event, 0);
	}

	private static void requireCapability(List<String> violations, Set<ProviderCapability> capabilities,
			ProviderCapability required, boolean used, String usage) {
		if (used && !capabilities.contains(required)) {
			violations.add(usage + " requires the " + required.getWireName() + " capability");
		}
	}
}
