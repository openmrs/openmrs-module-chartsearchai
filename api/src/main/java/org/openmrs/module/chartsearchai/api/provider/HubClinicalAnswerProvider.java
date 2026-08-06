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

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Relays one med-agent-hub product-profile request and maps the hub's staged SSE wire onto the
 * canonical {@link TurnEventType} lifecycle. The hub owns answer schema, validation, evidence, and
 * In-Depth content; this provider never reinterprets those fields — it only translates event names
 * and preserves complete payloads in {@link AnswerEnvelope}.
 *
 * <p>There is never a silent fallback to the bundled provider. Failures end in one
 * {@code turn_error} with a normalized problem code.</p>
 */
@Service("chartSearchAi.hubClinicalAnswerProvider")
public class HubClinicalAnswerProvider implements ClinicalAnswerProvider {

	public static final String PROVIDER_ID = "hub";

	public static final String PROBLEM_PROFILE_REQUIRED = "profile_required";

	public static final String PROBLEM_HUB_NOT_CONFIGURED = "hub_not_configured";

	public static final String PROBLEM_HUB_STREAM_INCOMPLETE = "hub_stream_incomplete";

	public static final String PROBLEM_CANCELLED = "cancelled";

	public static final String PROBLEM_PROVIDER_FAILURE = "provider_failure";

	private static final Logger log = LoggerFactory.getLogger(HubClinicalAnswerProvider.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HubStreamTransport transport;

	@Autowired
	public HubClinicalAnswerProvider(HubStreamTransport transport) {
		this.transport = transport;
	}

	/** Global-property read seam, overridable in tests. */
	protected String gp(String property, String defaultValue) {
		return Context.getAdministrationService().getGlobalProperty(property, defaultValue);
	}

	@Override
	public String id() {
		return PROVIDER_ID;
	}

	@Override
	public ProviderDescriptor descriptor() {
		String endpoint = configuredEndpoint();
		boolean ready = endpoint != null;
		Set<ProviderCapability> capabilities = EnumSet.of(ProviderCapability.ANSWER,
				ProviderCapability.ANSWER_CHECK, ProviderCapability.ANSWER_REVIEW, ProviderCapability.INDEPTH,
				ProviderCapability.GROUNDING, ProviderCapability.DRUG_SAFETY,
				ProviderCapability.STRUCTURED_BLOCKS, ProviderCapability.MULTI_TURN_CONTEXT);
		return new ProviderDescriptor(PROVIDER_ID, "Med-Agent Hub", true, ready, false,
				Collections.singletonList(ProviderMode.QUERY_SCOPED), capabilities,
				ready ? null : ChartSearchAiConstants.GP_HUB_ENDPOINT_URL + " is not set");
	}

	@Override
	public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
			CancellationSignal cancellation) {
		AtomicInteger sequence = new AtomicInteger();
		events.accept(TurnEvent.of(TurnEventType.TURN_STARTED, sequence.getAndIncrement(), PROVIDER_ID));

		if (cancellation.isCancelled()) {
			return failed(events, sequence, request.getMode(), PROBLEM_CANCELLED);
		}
		String endpoint = configuredEndpoint();
		if (endpoint == null) {
			return failed(events, sequence, request.getMode(), PROBLEM_HUB_NOT_CONFIGURED);
		}
		String profileId = request.getProfileId();
		if (profileId == null || profileId.trim().isEmpty()) {
			return failed(events, sequence, request.getMode(), PROBLEM_PROFILE_REQUIRED);
		}

		HubCallRequest call = new HubCallRequest(endpoint, profileId.trim(),
				request.getPatient().getUuid(), request.getConversationId(), request.getRequestId(),
				request.getQuestion(), request.getPriorClinicalTurns());

		AtomicReference<AnswerEnvelope> latestAnswer = new AtomicReference<>();
		AtomicReference<String> streamError = new AtomicReference<>();
		Set<TurnEventType> emitted = EnumSet.of(TurnEventType.TURN_STARTED);
		boolean[] doneSeen = { false };
		try {
			transport.stream(call,
					wire -> handleWire(wire, events, sequence, latestAnswer, streamError, emitted,
							doneSeen), cancellation);
		}
		catch (HubTransportException e) {
			if (latestAnswer.get() != null) {
				return completedWithPartialAnswer(events, sequence, request.getMode(),
						latestAnswer.get(), emitted);
			}
			return failed(events, sequence, request.getMode(), problemCodeFromHubBody(e.getBody()));
		}
		catch (RuntimeException e) {
			AnswerEnvelope current = latestAnswer.get();
			if (current != null) {
				return completedWithPartialAnswer(events, sequence, request.getMode(), current, emitted);
			}
			if (cancellation.isCancelled()) {
				return failed(events, sequence, request.getMode(), PROBLEM_CANCELLED);
			}
			log.warn("Hub provider turn failed for request {}", request.getRequestId(), e);
			return failed(events, sequence, request.getMode(), PROBLEM_PROVIDER_FAILURE);
		}

		if (streamError.get() != null) {
			return CompletableFuture.completedFuture(
					TurnResult.error(PROVIDER_ID, request.getMode(), streamError.get()));
		}
		if (!doneSeen[0]) {
			return failed(events, sequence, request.getMode(), PROBLEM_HUB_STREAM_INCOMPLETE);
		}
		AnswerEnvelope answer = latestAnswer.get();
		if (answer == null) {
			return failed(events, sequence, request.getMode(), PROBLEM_HUB_STREAM_INCOMPLETE);
		}
		return CompletableFuture.completedFuture(
				TurnResult.done(PROVIDER_ID, request.getMode(), answer));
	}

	private CompletionStage<TurnResult> completedWithPartialAnswer(TurnEventSink events,
			AtomicInteger sequence, ProviderMode mode, AnswerEnvelope current,
			Set<TurnEventType> emitted) {
		AnswerEnvelope answer = withInterruptedStages(current);
		emitInterruptedStageOutcomes(events, sequence, current, answer, emitted);
		events.accept(TurnEvent.withAnswer(TurnEventType.TURN_DONE,
				sequence.getAndIncrement(), PROVIDER_ID, answer));
		return CompletableFuture.completedFuture(TurnResult.done(PROVIDER_ID, mode, answer));
	}

	private void handleWire(HubWireEvent wire, TurnEventSink events, AtomicInteger sequence,
			AtomicReference<AnswerEnvelope> latestAnswer, AtomicReference<String> streamError,
			Set<TurnEventType> emitted, boolean[] doneSeen) {
		if (doneSeen[0]) {
			return;
		}
		String event = wire.getEvent();
		if (event == null || event.isEmpty()) {
			return;
		}
		if ("error".equals(event)) {
			String code = problemCodeFromHubPayload(wire.getPayload());
			events.accept(TurnEvent.error(sequence.getAndIncrement(), PROVIDER_ID, code));
			streamError.set(code);
			doneSeen[0] = true;
			return;
		}
		if ("done".equals(event)) {
			AnswerEnvelope answer = envelopeOrNull(wire.getPayload());
			if (answer == null) {
				events.accept(TurnEvent.error(sequence.getAndIncrement(), PROVIDER_ID,
						PROBLEM_HUB_STREAM_INCOMPLETE));
				streamError.set(PROBLEM_HUB_STREAM_INCOMPLETE);
				doneSeen[0] = true;
				return;
			}
			AnswerEnvelope previous = latestAnswer.getAndSet(answer);
			if (previous == null) {
				events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_DONE,
						sequence.getAndIncrement(), PROVIDER_ID, answer));
				emitted.add(TurnEventType.ANSWER_DONE);
			} else if (!previous.getPayload().equals(answer.getPayload())
					&& !emitted.contains(TurnEventType.EVIDENCE_UPDATED)
					&& !emitted.contains(TurnEventType.INDEPTH_PENDING)) {
				events.accept(TurnEvent.withAnswer(TurnEventType.EVIDENCE_UPDATED,
						sequence.getAndIncrement(), PROVIDER_ID, answer));
				emitted.add(TurnEventType.EVIDENCE_UPDATED);
			}
			events.accept(TurnEvent.withAnswer(TurnEventType.TURN_DONE,
					sequence.getAndIncrement(), PROVIDER_ID, answer));
			doneSeen[0] = true;
			return;
		}
		TurnEventType type = mapEvent(event);
		if (type == null) {
			return;
		}
		AnswerEnvelope answer = envelopeOrNull(wire.getPayload());
		if (answer != null) {
			latestAnswer.set(answer);
			events.accept(TurnEvent.withAnswer(type, sequence.getAndIncrement(), PROVIDER_ID, answer));
		} else {
			events.accept(TurnEvent.of(type, sequence.getAndIncrement(), PROVIDER_ID));
		}
		emitted.add(type);
	}

	@SuppressWarnings("unchecked")
	private static void emitInterruptedStageOutcomes(TurnEventSink events, AtomicInteger sequence,
			AnswerEnvelope before, AnswerEnvelope settled, Set<TurnEventType> emitted) {
		Object validation = before.getPayload().get("answerValidation");
		boolean validationInterrupted = validation instanceof Map
				&& "checking".equals(((Map<String, Object>) validation).get("status"));
		boolean laterStageStarted = emitted.contains(TurnEventType.EVIDENCE_UPDATED)
				|| emitted.contains(TurnEventType.INDEPTH_PENDING);
		if (validationInterrupted && !emitted.contains(TurnEventType.ANSWER_VALIDATION)
				&& !laterStageStarted) {
			events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_VALIDATION,
					sequence.getAndIncrement(), PROVIDER_ID, settled));
			emitted.add(TurnEventType.ANSWER_VALIDATION);
		}

		Object inDepth = before.getPayload().get("inDepth");
		boolean inDepthInterrupted = inDepth instanceof Map
				&& "pending".equals(((Map<String, Object>) inDepth).get("status"));
		if (inDepthInterrupted && emitted.contains(TurnEventType.INDEPTH_PENDING)
				&& !emitted.contains(TurnEventType.INDEPTH_DONE)
				&& !emitted.contains(TurnEventType.INDEPTH_ERROR)) {
			events.accept(TurnEvent.withAnswer(TurnEventType.INDEPTH_ERROR,
					sequence.getAndIncrement(), PROVIDER_ID, settled));
			emitted.add(TurnEventType.INDEPTH_ERROR);
		}
	}

	private static TurnEventType mapEvent(String hubEvent) {
		switch (hubEvent) {
			case "answer_done":
				return TurnEventType.ANSWER_DONE;
			case "heartbeat":
				return TurnEventType.HEARTBEAT;
			case "answer_validation":
				return TurnEventType.ANSWER_VALIDATION;
			case "evidence_updated":
			case "grounded":
				return TurnEventType.EVIDENCE_UPDATED;
			case "indepth_pending":
				return TurnEventType.INDEPTH_PENDING;
			case "indepth_done":
				return TurnEventType.INDEPTH_DONE;
			case "indepth_error":
				return TurnEventType.INDEPTH_ERROR;
			default:
				return null;
		}
	}

	/**
	 * Settles any validation or In-Depth stage interrupted after the fast answer arrived. The
	 * persisted record is the source of truth for every future reader (audit review, reload
	 * hydration, or another UI), so it cannot retain a status that will never complete.
	 */
	@SuppressWarnings("unchecked")
	private static AnswerEnvelope withInterruptedStages(AnswerEnvelope answer) {
		Map<String, Object> updatedPayload = new LinkedHashMap<>(answer.getPayload());
		boolean changed = false;
		Object validation = answer.getPayload().get("answerValidation");
		if (validation instanceof Map
				&& "checking".equals(((Map<String, Object>) validation).get("status"))) {
			Map<String, Object> updatedValidation = new LinkedHashMap<>((Map<String, Object>) validation);
			updatedValidation.put("status", "unavailable");
			updatedValidation.put("label", "Check unavailable");
			updatedValidation.put("summary", "Answer checking was interrupted.");
			updatedPayload.put("answerValidation", updatedValidation);
			changed = true;
		}
		Object inDepth = answer.getPayload().get("inDepth");
		if (inDepth instanceof Map && "pending".equals(((Map<String, Object>) inDepth).get("status"))) {
			Map<String, Object> updatedInDepth = new LinkedHashMap<>((Map<String, Object>) inDepth);
			updatedInDepth.put("status", "failed");
			updatedInDepth.put("error", "In-Depth was interrupted.");
			updatedPayload.put("inDepth", updatedInDepth);
			changed = true;
		}
		return changed ? AnswerEnvelope.fromPayload(updatedPayload) : answer;
	}

	private static AnswerEnvelope envelopeOrNull(Map<String, Object> payload) {
		if (payload == null || !(payload.get("answer") instanceof String)) {
			return null;
		}
		return AnswerEnvelope.fromPayload(payload);
	}

	private CompletionStage<TurnResult> failed(TurnEventSink events, AtomicInteger sequence,
			ProviderMode mode, String problemCode) {
		events.accept(TurnEvent.error(sequence.getAndIncrement(), PROVIDER_ID, problemCode));
		return CompletableFuture.completedFuture(TurnResult.error(PROVIDER_ID, mode, problemCode));
	}

	private String configuredEndpoint() {
		String endpoint = gp(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL, "");
		if (endpoint == null) {
			return null;
		}
		endpoint = endpoint.trim();
		return endpoint.isEmpty() ? null : endpoint;
	}

	@SuppressWarnings("unchecked")
	static String problemCodeFromHubBody(String body) {
		if (body == null || body.trim().isEmpty()) {
			return "hub_rejected_request";
		}
		try {
			Map<String, Object> envelope = MAPPER.readValue(body,
					new TypeReference<Map<String, Object>>() {});
			return problemCodeFromHubPayload(envelope);
		}
		catch (Exception ignored) {
			return "hub_rejected_request";
		}
	}

	@SuppressWarnings("unchecked")
	static String problemCodeFromHubPayload(Map<String, Object> payload) {
		if (payload == null) {
			return "hub_rejected_request";
		}
		Object detail = payload.get("detail");
		if (detail instanceof Map) {
			Object code = ((Map<String, Object>) detail).get("code");
			if (code != null && !String.valueOf(code).trim().isEmpty()) {
				return String.valueOf(code).trim();
			}
		}
		Object code = payload.get("code");
		if (code != null && !String.valueOf(code).trim().isEmpty()) {
			return String.valueOf(code).trim();
		}
		return "hub_rejected_request";
	}
}
