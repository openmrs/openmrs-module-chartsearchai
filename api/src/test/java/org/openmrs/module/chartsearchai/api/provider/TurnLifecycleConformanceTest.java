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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Red-first conformance adapter for the {@code provider_lifecycle} family of
 * {@code conformance/dual-provider-conformance.v1.json}, a versioned copy of the
 * dual-provider conformance fixture shared across the ChartSearchAI, ESM, and
 * med-agent-hub repositories. Every provider implementation must emit turn events
 * that {@link TurnLifecycleValidator} accepts against its advertised capabilities.
 */
public class TurnLifecycleConformanceTest {

	private static final String FIXTURE = "/conformance/dual-provider-conformance.v1.json";

	private static JsonNode fixtureRoot() throws Exception {
		try (InputStream in = TurnLifecycleConformanceTest.class.getResourceAsStream(FIXTURE)) {
			return new ObjectMapper().readTree(in);
		}
	}

	@Test
	public void everyProviderLifecycleFixtureCaseValidatesAsExpected() throws Exception {
		JsonNode cases = fixtureRoot().get("provider_lifecycle");
		assertTrue(cases != null && cases.isArray() && cases.size() > 0,
				"fixture must contain provider_lifecycle cases");

		for (JsonNode fixtureCase : cases) {
			String id = fixtureCase.get("id").asText();
			Set<ProviderCapability> capabilities = EnumSet.noneOf(ProviderCapability.class);
			for (JsonNode capability : fixtureCase.get("capabilities")) {
				capabilities.add(ProviderCapability.fromWireName(capability.asText()));
			}
			List<TurnEventType> events = new ArrayList<>();
			for (JsonNode event : fixtureCase.get("events")) {
				events.add(TurnEventType.fromWireName(event.asText()));
			}

			List<String> violations = TurnLifecycleValidator.violations(capabilities, events);
			if ("accept".equals(fixtureCase.get("expected").asText())) {
				assertTrue(violations.isEmpty(),
						id + " must be accepted but was rejected: " + violations);
			} else {
				assertFalse(violations.isEmpty(), id + " must be rejected but was accepted");
			}
		}
	}

	@Test
	public void fixtureCapabilityAndEventNamesAllResolve() throws Exception {
		JsonNode cases = fixtureRoot().get("provider_lifecycle");
		Set<String> seenEvents = new java.util.HashSet<>();
		for (JsonNode fixtureCase : cases) {
			for (JsonNode capability : fixtureCase.get("capabilities")) {
				assertEquals(capability.asText(),
						ProviderCapability.fromWireName(capability.asText()).getWireName());
			}
			for (JsonNode event : fixtureCase.get("events")) {
				seenEvents.add(event.asText());
				assertEquals(event.asText(), TurnEventType.fromWireName(event.asText()).getWireName());
			}
		}
		assertTrue(seenEvents.contains("turn_started"));
	}

	// --- Rejection rules the fixture's accept-only cases do not pin down ---

	private static final Set<ProviderCapability> FULL_CAPABILITIES = EnumSet.allOf(ProviderCapability.class);

	private static List<TurnEventType> events(String... wireNames) {
		List<TurnEventType> events = new ArrayList<>();
		for (String wireName : wireNames) {
			events.add(TurnEventType.fromWireName(wireName));
		}
		return events;
	}

	private static String joinedViolations(Set<ProviderCapability> capabilities, List<TurnEventType> events) {
		return TurnLifecycleValidator.violations(capabilities, events).stream()
				.collect(Collectors.joining("; "));
	}

	@Test
	public void aTurnMustBeginWithTurnStarted() {
		String violations = joinedViolations(FULL_CAPABILITIES, events("answer_done", "turn_done"));
		assertTrue(violations.contains("turn_started"), violations);
	}

	@Test
	public void aTurnMustEndWithExactlyOneTerminalEvent() {
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES, events("turn_started", "answer_done")).isEmpty(),
				"missing terminal event must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES, events("turn_started", "answer_done", "turn_done", "turn_error"))
				.isEmpty(), "two terminal events must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_done", "turn_done", "evidence_updated"))
				.isEmpty(), "events after the terminal event must be rejected");
	}

	@Test
	public void turnDoneRequiresAnswerDoneButTurnErrorDoesNot() {
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES, events("turn_started", "turn_done")).isEmpty(),
				"turn_done without answer_done must be rejected");
		assertTrue(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES, events("turn_started", "turn_error")).isEmpty(),
				"an error before any answer is a valid failed turn");
	}

	@Test
	public void answerDoneMayOccurOnlyOnce() {
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_done", "answer_done", "turn_done"))
				.isEmpty());
	}

	@Test
	public void eventsMustFollowTheCanonicalStageOrder() {
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_validation", "answer_done", "turn_done"))
				.isEmpty(), "answer_validation before answer_done must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_delta", "reasoning_delta", "answer_done", "turn_done"))
				.isEmpty(), "reasoning after answer deltas must be rejected");
		assertTrue(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "reasoning_delta", "reasoning_delta", "answer_delta",
								"answer_delta", "answer_done", "answer_validation", "evidence_updated",
								"indepth_pending", "indepth_done", "turn_done"))
				.isEmpty(), "the full canonical sequence with repeated deltas must be accepted");
	}

	@Test
	public void heartbeatsMayRepeatBetweenClinicalStagesWithoutChangingTheirOrder() {
		assertTrue(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "heartbeat", "heartbeat", "answer_done",
								"heartbeat", "turn_done"))
				.isEmpty(), "transport liveness events must not change the clinical lifecycle");
	}

	@Test
	public void optionalEventsRequireTheirAdvertisedCapability() {
		Set<ProviderCapability> answerOnly = EnumSet.of(ProviderCapability.ANSWER);
		assertFalse(TurnLifecycleValidator
				.violations(answerOnly, events("turn_started", "answer_delta", "answer_done", "turn_done"))
				.isEmpty(), "answer_delta without token_streaming must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(answerOnly,
						events("turn_started", "answer_done", "answer_validation", "turn_done"))
				.isEmpty(), "answer_validation without a checking/review capability must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(answerOnly,
						events("turn_started", "answer_done", "evidence_updated", "turn_done"))
				.isEmpty(), "evidence_updated without grounding must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(answerOnly,
						events("turn_started", "answer_done", "indepth_pending", "indepth_done", "turn_done"))
				.isEmpty(), "indepth events without the indepth capability must be rejected");
	}

	@Test
	public void anAnsweringTurnRequiresTheAnswerCapability() {
		Set<ProviderCapability> none = EnumSet.noneOf(ProviderCapability.class);
		assertFalse(TurnLifecycleValidator
				.violations(none, events("turn_started", "answer_done", "turn_done")).isEmpty());
	}

	@Test
	public void inDepthTerminalEventsRequireAPrecedingPending() {
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_done", "indepth_done", "turn_done"))
				.isEmpty(), "indepth_done without indepth_pending must be rejected");
		assertFalse(TurnLifecycleValidator
				.violations(FULL_CAPABILITIES,
						events("turn_started", "answer_done", "indepth_pending", "indepth_done",
								"indepth_error", "turn_done"))
				.isEmpty(), "both indepth_done and indepth_error must be rejected");
	}
}
