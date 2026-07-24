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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * Red-first conformance adapter for the {@code temporal_gate} family of
 * {@code conformance/dual-provider-conformance.v1.json}. Bundled chartsearchai has no temporal-gate
 * engine of its own — {@link ProviderCapability#ANSWER_CHECK} is advertised only by
 * {@link HubClinicalAnswerProvider} (see its capability set), which relays the hub's already-gated
 * {@code temporalGate} object opaquely (per {@link AnswerEnvelope}'s javadoc: "preserved without
 * converting ... into Java-owned domain types"). So the Java-side obligation for this family is not
 * to re-derive a gate result (the hub's Python {@code run_temporal_gate} owns that, proven by
 * med-agent-hub's own {@code test_dual_provider_conformance_adapter.py}), but to prove the relay
 * never drops or reshapes the gate's {@code status} on its way through
 * {@link HubClinicalAnswerProvider#execute}. Each fixture case's {@code expected_status} becomes the
 * status the scripted hub transport sends for that case, so a relay regression that dropped or
 * reset {@code temporalGate} would fail here even though every case in this fixture version happens
 * to expect {@code "fail"}.
 */
public class TemporalGateRelayConformanceTest {

	private static final String FIXTURE = "/conformance/dual-provider-conformance.v1.json";

	private static JsonNode temporalGateCases() throws Exception {
		try (InputStream in = TemporalGateRelayConformanceTest.class.getResourceAsStream(FIXTURE)) {
			JsonNode root = new ObjectMapper().readTree(in);
			JsonNode cases = root.get("temporal_gate");
			assertTrue(cases != null && cases.isArray() && cases.size() > 0,
					"fixture must contain temporal_gate cases");
			return cases;
		}
	}

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-uuid-1");
		return patient;
	}

	private static TurnRequest request() {
		return new TurnRequest(patient(), "When was the last visit?", "conversation-1", "request-1",
				ProviderMode.QUERY_SCOPED, "product-profile-a",
				Collections.singletonList(new PriorClinicalTurn("prior q", "prior a")));
	}

	/** Scripted hub transport that emits exactly one prepared SSE sequence. */
	private static final class ScriptedHubTransport implements HubStreamTransport {

		private final java.util.List<HubWireEvent> events;

		ScriptedHubTransport(java.util.List<HubWireEvent> events) {
			this.events = events;
		}

		@Override
		public void stream(HubCallRequest request, Consumer<HubWireEvent> sink, CancellationSignal cancellation) {
			for (HubWireEvent event : events) {
				sink.accept(event);
			}
		}
	}

	private static HubClinicalAnswerProvider provider(HubStreamTransport transport) {
		return new HubClinicalAnswerProvider(transport) {

			@Override
			protected String gp(String property, String defaultValue) {
				if (ChartSearchAiConstants.GP_HUB_ENDPOINT_URL.equals(property)) {
					return "http://hub.example/v1/chat/completions";
				}
				return defaultValue;
			}
		};
	}

	/** A staged done-envelope payload carrying a real-shaped temporalGate object. */
	private static Map<String, Object> payloadWithTemporalGate(String answer, String gateStatus) {
		Map<String, Object> temporalGate = new LinkedHashMap<>();
		temporalGate.put("schema_version", "temporal_gate.v1");
		temporalGate.put("mode", "enforce");
		temporalGate.put("status", gateStatus);
		temporalGate.put("checks", Collections.emptyList());

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", answer);
		payload.put("references", Collections.emptyList());
		payload.put("temporalGate", temporalGate);
		return payload;
	}

	@Test
	public void everyTemporalGateFixtureCaseSurvivesRelayUnaltered() throws Exception {
		int caseCount = 0;
		for (JsonNode fixtureCase : temporalGateCases()) {
			String id = fixtureCase.get("id").asText();
			String expectedStatus = fixtureCase.get("expected_status").asText();

			Map<String, Object> done = payloadWithTemporalGate("An answer.", expectedStatus);
			ScriptedHubTransport transport = new ScriptedHubTransport(Arrays.asList(
					new HubWireEvent("answer_done", done),
					new HubWireEvent("done", done)));

			TurnResult result = provider(transport)
					.execute(request(), event -> { }, CancellationSignal.NONE)
					.toCompletableFuture().get();

			Object relayedGate = result.getAnswer().getPayload().get("temporalGate");
			assertTrue(relayedGate instanceof Map, id + ": temporalGate must survive relay as an object");
			assertEquals(expectedStatus, ((Map<?, ?>) relayedGate).get("status"),
					id + ": relayed temporalGate.status must match the fixture's expected_status");
			assertEquals("temporal_gate.v1", ((Map<?, ?>) relayedGate).get("schema_version"),
					id + ": relay must not drop sibling fields of the gate object");

			caseCount++;
		}
		assertEquals(4, caseCount, "sanity: every fixture case actually ran");
	}
}
