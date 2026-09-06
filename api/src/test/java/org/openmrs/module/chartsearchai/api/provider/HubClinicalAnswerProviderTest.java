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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * {@link HubClinicalAnswerProvider} makes one med-agent-hub product-profile call and maps the
 * hub's staged SSE wire onto the canonical turn lifecycle. These tests drive the provider through
 * a scripted transport so the mapping, single-call contract, and readiness rules are pinned without
 * a live hub.
 */
public class HubClinicalAnswerProviderTest {

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-uuid-1");
		return patient;
	}

	private static TurnRequest request(String profileId) {
		return new TurnRequest(patient(), "What medications is this patient on?", "conversation-1",
				"request-1", ProviderMode.QUERY_SCOPED, profileId,
				Collections.singletonList(new PriorClinicalTurn("prior q", "prior a")));
	}

	private static class CollectingSink implements TurnEventSink {

		final List<TurnEvent> events = new ArrayList<>();

		@Override
		public void accept(TurnEvent event) {
			events.add(event);
		}

		List<TurnEventType> types() {
			return events.stream().map(TurnEvent::getType).collect(Collectors.toList());
		}
	}

	/** Scripted hub transport: records calls and emits a prepared SSE sequence. */
	private static class ScriptedHubTransport implements HubStreamTransport {

		final AtomicInteger calls = new AtomicInteger();

		HubCallRequest lastRequest;

		List<HubWireEvent> events = Collections.emptyList();

		RuntimeException failure;

		boolean cancelOnFailure = true;

		int httpStatus = 200;

		String errorBody;

		CancellationSignal lastCancellation;

		@Override
		public void stream(HubCallRequest request, Consumer<HubWireEvent> sink, CancellationSignal cancellation) {
			calls.incrementAndGet();
			lastRequest = request;
			lastCancellation = cancellation;
			if (httpStatus < 200 || httpStatus >= 300) {
				throw new HubTransportException(httpStatus, errorBody == null ? "" : errorBody);
			}
			for (HubWireEvent event : events) {
				sink.accept(event);
			}
			// Thrown AFTER emitting events — reproduces a real preempt: the connection is force-closed
			// mid-stream, after some events (e.g. answer_done) already arrived. Production gets here
			// because cancel() itself closed the resource; mirror that causality instead of the test
			// pre-cancelling (which would trip execute()'s own "not started yet" guard).
			if (failure != null) {
				if (cancelOnFailure && cancellation instanceof TurnCancellation) {
					((TurnCancellation) cancellation).cancel();
				}
				throw failure;
			}
		}
	}

	@Test
	public void aTransportFailureAfterAnswerDonePreservesTheAnswerForReload() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("answer_done", answerPayload("Aspirin 81mg.")));
		transport.failure = new RuntimeException("upstream read timed out");
		transport.cancelOnFailure = false;
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink,
				CancellationSignal.NONE).toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Aspirin 81mg.", result.getAnswer().getText());
		assertEquals("unavailable",
				((Map<?, ?>) result.getAnswer().getPayload().get("answerValidation")).get("status"));
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.ANSWER_VALIDATION, TurnEventType.TURN_DONE), sink.types());
	}

	@Test
	public void aTransportFailureAfterDoneDoesNotEmitASecondTerminalEvent() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("done", answerPayload("Aspirin 81mg.")));
		transport.failure = new RuntimeException("connection close failed");
		transport.cancelOnFailure = false;
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink,
				CancellationSignal.NONE).toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.TURN_DONE), sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
	}

	@Test
	public void aTransportFailureAfterHubErrorPreservesTheError() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("error",
				Collections.<String, Object>singletonMap("code", "upstream_unavailable")));
		transport.failure = new RuntimeException("connection close failed");
		transport.cancelOnFailure = false;
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink,
				CancellationSignal.NONE).toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("upstream_unavailable", result.getProblemCode());
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR),
				sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
	}

	private static HubClinicalAnswerProvider provider(ScriptedHubTransport transport, String endpoint) {
		return new HubClinicalAnswerProvider(transport) {

			@Override
			protected String gp(String property, String defaultValue) {
				if (ChartSearchAiConstants.GP_HUB_ENDPOINT_URL.equals(property)) {
					return endpoint;
				}
				return defaultValue;
			}
		};
	}

	private static Map<String, Object> answerPayload(String answer) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", answer);
		payload.put("references", Collections.emptyList());
		payload.put("answerValidation", Collections.singletonMap("status", "checking"));
		return payload;
	}

	private static HubWireEvent wire(String event, Map<String, Object> payload) {
		return new HubWireEvent(event, payload);
	}

	@Test
	public void descriptorIsNotReadyWhenTheHubEndpointIsUnset() {
		HubClinicalAnswerProvider provider = provider(new ScriptedHubTransport(), "");
		ProviderDescriptor descriptor = provider.descriptor();
		assertEquals(HubClinicalAnswerProvider.PROVIDER_ID, descriptor.getId());
		assertEquals("Med-Agent Hub", descriptor.getLabel());
		assertFalse(descriptor.isReady());
		assertNotNull(descriptor.getUnavailableReason());
		assertTrue(descriptor.getCapabilities().contains(ProviderCapability.ANSWER));
		assertFalse(descriptor.getCapabilities().contains(ProviderCapability.TOKEN_STREAMING));
		assertTrue(descriptor.getCapabilities().contains(ProviderCapability.INDEPTH));
		assertTrue(descriptor.getCapabilities().contains(ProviderCapability.ANSWER_REVIEW));
	}

	@Test
	public void aStagedHubStreamMapsOntoTheCanonicalLifecycleInOneCall() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> answer = answerPayload("Aspirin 81mg.");
		Map<String, Object> validated = new LinkedHashMap<>(answer);
		validated.put("answerValidation", Collections.singletonMap("status", "checked"));
		Map<String, Object> pending = new LinkedHashMap<>(validated);
		pending.put("inDepth", Collections.singletonMap("status", "pending"));
		Map<String, Object> indepthDone = new LinkedHashMap<>(validated);
		Map<String, Object> finalInDepthPayload = new LinkedHashMap<>();
		finalInDepthPayload.put("status", "done");
		finalInDepthPayload.put("validation", Collections.singletonMap("summary",
				"One claim is not supported by its cited source."));
		indepthDone.put("inDepth", finalInDepthPayload);
		Map<String, Object> done = new LinkedHashMap<>(indepthDone);
		transport.events = Arrays.asList(
				wire("answer_done", answer),
				wire("answer_validation", validated),
				wire("indepth_pending", pending),
				wire("indepth_done", indepthDone),
				wire("done", done));

		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request("product-profile-a"), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(1, transport.calls.get(), "exactly one hub call per turn");
		assertEquals("product-profile-a", transport.lastRequest.getProfileId());
		assertEquals("patient-uuid-1", transport.lastRequest.getPatientUuid());
		assertEquals("conversation-1", transport.lastRequest.getConversationId());
		assertEquals(1, transport.lastRequest.getPriorTurns().size());
		assertEquals("prior q", transport.lastRequest.getPriorTurns().get(0).getQuestion());

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.ANSWER_VALIDATION, TurnEventType.INDEPTH_PENDING,
				TurnEventType.INDEPTH_DONE, TurnEventType.TURN_DONE), sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Aspirin 81mg.", result.getAnswer().getText());
		assertEquals("checked",
				((Map<?, ?>) result.getAnswer().getPayload().get("answerValidation")).get("status"));
		Map<?, ?> finalInDepth = (Map<?, ?>) result.getAnswer().getPayload().get("inDepth");
		Map<?, ?> finalInDepthValidation = (Map<?, ?>) finalInDepth.get("validation");
		assertEquals("One claim is not supported by its cited source.",
				finalInDepthValidation.get("summary"));
		assertEquals(done, sink.events.get(sink.events.size() - 1).getAnswer().getPayload(),
				"turn_done must relay the final post-review, post-grounding envelope");
		assertNull(result.getProblemCode());
	}

	@Test
	public void aMissingProfileFailsExplicitlyWithoutCallingTheHub() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnResult result = provider
				.execute(request(null), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(0, transport.calls.get());
		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("profile_required", result.getProblemCode());
	}

	@Test
	public void aHubRejectionEndsInTurnErrorWithTheHubProblemCode() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.httpStatus = 422;
		transport.errorBody = "{\"detail\":{\"code\":\"insufficient_context\",\"message\":\"chart too large\"}}";
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request("product-profile-a"), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(1, transport.calls.get());
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR), sink.types());
		assertEquals("insufficient_context", result.getProblemCode());
		assertNull(result.getAnswer());
	}

	@Test
	public void aStreamThatEndsWithoutDoneIsATurnError() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("answer_done", answerPayload("partial")));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request("product-profile-a"), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("hub_stream_incomplete", result.getProblemCode());
		assertTrue(sink.types().contains(TurnEventType.ANSWER_DONE));
		assertTrue(sink.types().contains(TurnEventType.TURN_ERROR));
		assertFalse(sink.types().contains(TurnEventType.TURN_DONE));
	}

	@Test
	public void aDoneEventWithoutAnAnswerIsOneTurnErrorNotAFalseSuccess() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("done", Collections.emptyMap()));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink,
				CancellationSignal.NONE).toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR),
				sink.types());
		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("hub_stream_incomplete", result.getProblemCode());
	}

	@Test
	public void theCallersCancellationSignalIsHandedToTheTransport() throws Exception {
		// So a preempting turn can force-close the hub's open response body (via
		// TurnCancellation.bindCloseable) rather than the hub connection running to its own
		// natural completion. A silently-dropped cancellation here is exactly what let a
		// preempted turn keep occupying the hub's router slot indefinitely.
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.events = Collections.singletonList(wire("done", answerPayload("Aspirin 81mg.")));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnCancellation cancellation = new TurnCancellation();

		provider.execute(request("product-profile-a"), sink, cancellation).toCompletableFuture().get();

		assertTrue(transport.lastCancellation == cancellation);
	}

	@Test
	public void aCancelledStreamThatAlreadyProducedAnAnswerCompletesAsDoneWithThatAnswer() throws Exception {
		// The real preempt shape: a fast answer already arrived (answer_done/answer_validation),
		// only In-Depth was still generating when this turn got cancelled. The answer itself is
		// not wrong just because a later stage got cut short — discarding it as a generic failure
		// would silently drop a perfectly good answer from history instead of persisting it (with
		// In-Depth left at whatever partial state it reached).
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> answer = answerPayload("Aspirin 81mg.");
		transport.events = Collections.singletonList(wire("answer_done", answer));
		transport.failure = new RuntimeException("connection reset");
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnCancellation cancellation = new TurnCancellation();

		TurnResult result = provider.execute(request("product-profile-a"), sink, cancellation)
				.toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Aspirin 81mg.", result.getAnswer().getText());
		assertEquals("unavailable",
				((Map<?, ?>) result.getAnswer().getPayload().get("answerValidation")).get("status"));
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.ANSWER_VALIDATION, TurnEventType.TURN_DONE), sink.types());
		assertEquals("unavailable", ((Map<?, ?>) sink.events.get(sink.events.size() - 1)
				.getAnswer().getPayload().get("answerValidation")).get("status"));
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
		assertNull(result.getProblemCode());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aCancelledStreamsDanglingPendingInDepthIsPersistedAsFailedNotLeftPending() throws Exception {
		// The persisted record is the source of truth for every future reader (audit review,
		// re-hydrating a reload, another UI) — not just this session's frontend, which separately
		// reinterprets a dangling "pending" as failed on its own hydration path. No further
		// generation will ever happen for this turn, so the stored record must say so, not claim
		// forever that In-Depth is still running.
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> answer = answerPayload("Aspirin 81mg.");
		Map<String, Object> checked = answerPayload("Aspirin 81mg.");
		checked.put("answerValidation", Collections.singletonMap("status", "checked"));
		Map<String, Object> pending = new LinkedHashMap<>(checked);
		pending.put("inDepth", new LinkedHashMap<>(Collections.singletonMap("status", "pending")));
		transport.events = Arrays.asList(wire("answer_done", answer),
				wire("answer_validation", checked), wire("indepth_pending", pending));
		transport.failure = new RuntimeException("connection reset");
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnCancellation cancellation = new TurnCancellation();

		TurnResult result = provider.execute(request("product-profile-a"), sink, cancellation)
				.toCompletableFuture().get();

		Map<String, Object> inDepth = (Map<String, Object>) result.getAnswer().getPayload().get("inDepth");
		assertEquals("failed", inDepth.get("status"));
		Map<String, Object> validation = (Map<String, Object>) result.getAnswer().getPayload()
				.get("answerValidation");
		assertEquals("checked", validation.get("status"));
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.ANSWER_VALIDATION, TurnEventType.INDEPTH_PENDING,
				TurnEventType.INDEPTH_ERROR, TurnEventType.TURN_DONE), sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
		Map<?, ?> terminalInDepth = (Map<?, ?>) sink.events.get(sink.events.size() - 1)
				.getAnswer().getPayload().get("inDepth");
		assertEquals("failed", terminalInDepth.get("status"));
	}

	@Test
	public void finalDoneAfterInDepthDoesNotEmitABackwardsEvidenceEvent() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> answer = answerPayload("Initial answer.");
		Map<String, Object> checked = new LinkedHashMap<>(answer);
		checked.put("answerValidation", Collections.singletonMap("status", "checked"));
		Map<String, Object> pending = new LinkedHashMap<>(checked);
		pending.put("inDepth", Collections.singletonMap("status", "pending"));
		Map<String, Object> complete = new LinkedHashMap<>(checked);
		complete.put("inDepth", Collections.singletonMap("status", "complete"));
		Map<String, Object> finalEnvelope = new LinkedHashMap<>(complete);
		finalEnvelope.put("trace", Collections.singletonMap("total_ms", Integer.valueOf(10)));
		transport.events = Arrays.asList(wire("answer_done", answer),
				wire("answer_validation", checked), wire("indepth_pending", pending),
				wire("indepth_done", complete), wire("done", finalEnvelope));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink,
				CancellationSignal.NONE).toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.ANSWER_VALIDATION, TurnEventType.INDEPTH_PENDING,
				TurnEventType.INDEPTH_DONE, TurnEventType.TURN_DONE), sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
		assertEquals(finalEnvelope.get("trace"), result.getAnswer().getPayload().get("trace"));
		assertEquals(finalEnvelope.get("trace"), sink.events.get(sink.events.size() - 1)
				.getAnswer().getPayload().get("trace"));
	}

	@Test
	public void aCancelledStreamThatNeverProducedAnAnswerReportsCancellation() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		transport.failure = new RuntimeException("connection reset");
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();
		TurnCancellation cancellation = new TurnCancellation();

		TurnResult result = provider.execute(request("product-profile-a"), sink, cancellation)
				.toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertNull(result.getAnswer());
		assertEquals("cancelled", result.getProblemCode());
		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR), sink.types());
	}

	@Test
	public void eventsAfterTheFirstTerminalHubEventAreIgnored() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> answer = answerPayload("Aspirin 81mg.");
		transport.events = Arrays.asList(
				wire("done", answer),
				wire("error", Collections.singletonMap("code", "late_error")),
				wire("answer_validation", answer));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.TURN_DONE), sink.types());
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Aspirin 81mg.", result.getAnswer().getText());
	}

	@Test
	public void aChangedFinalDoneEnvelopeIsEmittedBeforeTheTerminalMarker() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		Map<String, Object> checking = answerPayload("Initial answer.");
		Map<String, Object> finalAnswer = new LinkedHashMap<>(checking);
		finalAnswer.put("answer", "Checked answer.");
		finalAnswer.put("answerValidation", Collections.singletonMap("status", "edited"));
		transport.events = Arrays.asList(wire("answer_done", checking), wire("done", finalAnswer));
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");
		CollectingSink sink = new CollectingSink();

		TurnResult result = provider.execute(request("product-profile-a"), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.ANSWER_DONE,
				TurnEventType.EVIDENCE_UPDATED, TurnEventType.TURN_DONE), sink.types());
		assertEquals("Checked answer.", sink.events.get(2).getAnswer().getText());
		assertEquals("Checked answer.", result.getAnswer().getText());
	}

	@Test
	public void cancellationBeforeTheHubCallNeverContactsTheTransport() throws Exception {
		ScriptedHubTransport transport = new ScriptedHubTransport();
		HubClinicalAnswerProvider provider = provider(transport,
				"http://hub.example/v1/chat/completions");

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request("product-profile-a"), sink, () -> true)
				.toCompletableFuture().get();

		assertEquals(0, transport.calls.get());
		assertEquals("cancelled", result.getProblemCode());
	}
}
