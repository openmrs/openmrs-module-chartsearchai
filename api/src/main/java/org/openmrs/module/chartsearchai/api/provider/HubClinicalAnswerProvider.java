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
				ProviderCapability.TOKEN_STREAMING, ProviderCapability.ANSWER_CHECK,
				ProviderCapability.ANSWER_REVIEW, ProviderCapability.INDEPTH,
				ProviderCapability.GROUNDING, ProviderCapability.DRUG_SAFETY,
				ProviderCapability.STRUCTURED_BLOCKS, ProviderCapability.MULTI_TURN_CONTEXT);
		return new ProviderDescriptor(PROVIDER_ID, "med-agent-hub", true, ready, false,
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
		boolean[] doneSeen = { false };
		try {
			transport.stream(call,
					wire -> handleWire(wire, events, sequence, latestAnswer, streamError, doneSeen), cancellation);
		}
		catch (HubTransportException e) {
			return failed(events, sequence, request.getMode(), problemCodeFromHubBody(e.getBody()));
		}
		catch (RuntimeException e) {
			if (cancellation.isCancelled() && latestAnswer.get() != null) {
				AnswerEnvelope answer = withInDepthInterrupted(latestAnswer.get());
				events.accept(TurnEvent.of(TurnEventType.TURN_DONE, sequence.getAndIncrement(), PROVIDER_ID));
				return CompletableFuture.completedFuture(
						TurnResult.done(PROVIDER_ID, request.getMode(), answer));
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

	private void handleWire(HubWireEvent wire, TurnEventSink events, AtomicInteger sequence,
			AtomicReference<AnswerEnvelope> latestAnswer, AtomicReference<String> streamError,
			boolean[] doneSeen) {
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
			if (answer != null) {
				latestAnswer.set(answer);
			}
			events.accept(TurnEvent.of(TurnEventType.TURN_DONE, sequence.getAndIncrement(), PROVIDER_ID));
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
		} else if (type == TurnEventType.REASONING_DELTA || type == TurnEventType.ANSWER_DELTA) {
			Object delta = wire.getPayload().get("delta");
			events.accept(TurnEvent.delta(type, sequence.getAndIncrement(), PROVIDER_ID,
					delta == null ? "" : String.valueOf(delta)));
		} else {
			events.accept(TurnEvent.of(type, sequence.getAndIncrement(), PROVIDER_ID));
		}
	}

	private static TurnEventType mapEvent(String hubEvent) {
		switch (hubEvent) {
			case "reasoning_delta":
			case "thinking":
				return TurnEventType.REASONING_DELTA;
			case "answer_delta":
			case "token":
				return TurnEventType.ANSWER_DELTA;
			case "answer_done":
				return TurnEventType.ANSWER_DONE;
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
	 * Rewrites a dangling {@code inDepth.status == "pending"} to {@code "failed"} before this
	 * answer is persisted. The persisted record is the source of truth for every future reader
	 * (audit review, a reload's hydration, another UI) — leaving it saying "pending" forever would
	 * be a lie, since a cancelled turn's In-Depth will never actually finish generating.
	 */
	@SuppressWarnings("unchecked")
	private static AnswerEnvelope withInDepthInterrupted(AnswerEnvelope answer) {
		Object inDepth = answer.getPayload().get("inDepth");
		if (!(inDepth instanceof Map) || !"pending".equals(((Map<String, Object>) inDepth).get("status"))) {
			return answer;
		}
		Map<String, Object> updatedInDepth = new LinkedHashMap<>((Map<String, Object>) inDepth);
		updatedInDepth.put("status", "failed");
		updatedInDepth.put("error", "In-Depth was interrupted.");
		Map<String, Object> updatedPayload = new LinkedHashMap<>(answer.getPayload());
		updatedPayload.put("inDepth", updatedInDepth);
		return AnswerEnvelope.fromPayload(updatedPayload);
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
