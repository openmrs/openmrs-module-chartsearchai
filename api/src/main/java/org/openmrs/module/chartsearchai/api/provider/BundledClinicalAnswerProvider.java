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
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the bundled ChartSearchAI pipeline (the {@link ChartSearchService} caching router and
 * everything behind it: local/remote engines, query-scoped or full-chart context, grounding,
 * drug safety, warmup) onto the provider-neutral {@link ClinicalAnswerProvider} boundary. Bundled
 * behavior is unchanged — this class only translates the pipeline's streaming seams into the
 * canonical turn lifecycle:
 *
 * <ul>
 *   <li>preliminary and committed reasoning fragments become {@code reasoning_delta};</li>
 *   <li>answer tokens become {@code answer_delta};</li>
 *   <li>the ungrounded-answer seam becomes {@code answer_done} (verdicts still {@code null});</li>
 *   <li>the grounded return value becomes {@code evidence_updated} when grounding is enabled;</li>
 *   <li>an answer that arrives already final (e.g. cached, verdicts attached when first computed)
 *       becomes a single {@code answer_done} with no separate evidence event.</li>
 * </ul>
 *
 * Failures are normalized to one problem code and never fall back to another provider.
 */
public class BundledClinicalAnswerProvider implements ClinicalAnswerProvider {

	public static final String PROVIDER_ID = "bundled";

	public static final String PROBLEM_PROVIDER_FAILURE = "provider_failure";

	public static final String PROBLEM_CHART_TOO_LARGE = "chart_too_large";

	public static final String PROBLEM_UNSUPPORTED_MODE = "unsupported_mode";

	public static final String PROBLEM_CANCELLED = "cancelled";

	private static final Logger log = LoggerFactory.getLogger(BundledClinicalAnswerProvider.class);

	private final ChartSearchService chartSearchService;

	public BundledClinicalAnswerProvider(ChartSearchService chartSearchService) {
		this.chartSearchService = chartSearchService;
	}

	/** Global-property read seam, overridable in tests (same pattern as the caching router). */
	protected String gp(String property, String defaultValue) {
		return Context.getAdministrationService().getGlobalProperty(property, defaultValue);
	}

	@Override
	public ProviderDescriptor descriptor() {
		Set<ProviderCapability> capabilities = EnumSet.of(ProviderCapability.ANSWER,
				ProviderCapability.TOKEN_STREAMING);
		if (groundingEnabled()) {
			capabilities.add(ProviderCapability.GROUNDING);
		}
		if (Boolean.parseBoolean(gp(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED,
				String.valueOf(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_ENABLED)))) {
			capabilities.add(ProviderCapability.DRUG_SAFETY);
		}
		return new ProviderDescriptor(PROVIDER_ID, "ChartSearchAI (bundled)", true, true, true,
				Collections.singletonList(configuredMode()), capabilities, null);
	}

	@Override
	public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
			CancellationSignal cancellation) {
		// The bundled pipeline is synchronous; the turn runs on the caller's thread and the
		// stage completes when the last event has been delivered.
		AtomicInteger sequence = new AtomicInteger();
		events.accept(TurnEvent.of(TurnEventType.TURN_STARTED, sequence.getAndIncrement(), PROVIDER_ID));

		if (cancellation.isCancelled()) {
			return failed(events, sequence, null, PROBLEM_CANCELLED);
		}
		ProviderMode configuredMode = configuredMode();
		if (request.getMode() != null && request.getMode() != configuredMode) {
			return failed(events, sequence, null, PROBLEM_UNSUPPORTED_MODE);
		}

		boolean[] answerDoneEmitted = { false };
		ChartAnswer finalAnswer;
		try {
			finalAnswer = chartSearchService.searchStreaming(request.getPatient(), request.getQuestion(),
					token -> events.accept(TurnEvent.delta(TurnEventType.ANSWER_DELTA,
							sequence.getAndIncrement(), PROVIDER_ID, token)),
					reasoning -> events.accept(TurnEvent.delta(TurnEventType.REASONING_DELTA,
							sequence.getAndIncrement(), PROVIDER_ID, reasoning)),
					// Citations arrive inside the ungrounded answer below; the pre-grounding
					// citations channel needs no separate lifecycle event.
					citations -> {
					}, ungrounded -> {
						answerDoneEmitted[0] = true;
						events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_DONE,
								sequence.getAndIncrement(), PROVIDER_ID, toAnswerEnvelope(ungrounded)));
					}, preliminary -> events.accept(TurnEvent.delta(TurnEventType.REASONING_DELTA,
							sequence.getAndIncrement(), PROVIDER_ID, preliminary)));
		}
		catch (ChartTooLargeException e) {
			return failed(events, sequence, configuredMode, PROBLEM_CHART_TOO_LARGE);
		}
		catch (RuntimeException e) {
			log.warn("Bundled provider turn failed for request {}", request.getRequestId(), e);
			return failed(events, sequence, configuredMode, PROBLEM_PROVIDER_FAILURE);
		}

		AnswerEnvelope finalEnvelope = toAnswerEnvelope(finalAnswer);
		if (answerDoneEmitted[0]) {
			if (groundingEnabled()) {
				events.accept(TurnEvent.withAnswer(TurnEventType.EVIDENCE_UPDATED,
						sequence.getAndIncrement(), PROVIDER_ID, finalEnvelope));
			}
		} else {
			// The ungrounded seam did not fire, so the returned answer was already final
			// (e.g. cached with verdicts attached when first computed).
			events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_DONE, sequence.getAndIncrement(),
					PROVIDER_ID, finalEnvelope));
		}
		events.accept(TurnEvent.of(TurnEventType.TURN_DONE, sequence.getAndIncrement(), PROVIDER_ID));
		return CompletableFuture.completedFuture(TurnResult.done(PROVIDER_ID, configuredMode, finalEnvelope));
	}

	private CompletionStage<TurnResult> failed(TurnEventSink events, AtomicInteger sequence,
			ProviderMode mode, String problemCode) {
		events.accept(TurnEvent.error(sequence.getAndIncrement(), PROVIDER_ID, problemCode));
		return CompletableFuture.completedFuture(TurnResult.error(PROVIDER_ID, mode, problemCode));
	}

	private boolean groundingEnabled() {
		return Boolean.parseBoolean(gp(ChartSearchAiConstants.GP_GROUNDING_ENABLED,
				String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ENABLED)));
	}

	private static AnswerEnvelope toAnswerEnvelope(ChartAnswer answer) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", answer.getAnswer());

		List<Map<String, Object>> references = new ArrayList<>();
		for (RecordReference reference : answer.getReferences()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("index", reference.getIndex());
			value.put("resourceType", reference.getResourceType());
			value.put("resourceUuid", reference.getResourceUuid());
			value.put("date", reference.getDate() == null ? null
					: DateFormatUtil.formatDate(reference.getDate()));
			value.put("grounded", reference.getGrounded());
			references.add(value);
		}
		payload.put("references", references);

		List<Map<String, Object>> warnings = new ArrayList<>();
		for (SafetyWarning warning : answer.getSafetyWarnings()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("type", warning.getType());
			value.put("drug", warning.getDrug());
			value.put("detail", warning.getDetail());
			warnings.add(value);
		}
		payload.put("safetyWarnings", warnings);
		payload.put("blocks", Collections.emptyList());
		payload.put("inputTokens", answer.getInputTokens());
		payload.put("outputTokens", answer.getOutputTokens());
		payload.put("cachedTokens", answer.getCachedTokens());
		return AnswerEnvelope.fromPayload(payload);
	}

	private ProviderMode configuredMode() {
		String chartMode = gp(ChartSearchAiConstants.GP_CHART_MODE, ChartSearchAiConstants.CHART_MODE_DEFAULT);
		return ChartSearchAiConstants.CHART_MODE_FULL_CHART.equals(chartMode)
				? ProviderMode.FULL_CHART_STABLE : ProviderMode.QUERY_SCOPED;
	}
}
