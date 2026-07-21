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
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;

/**
 * {@link BundledClinicalAnswerProvider} adapts the bundled ChartSearchAI pipeline (the
 * {@code ChartSearchService} router) onto the provider-neutral {@link ClinicalAnswerProvider}
 * boundary without changing bundled behavior. These tests drive the adapter through the real
 * production boundary — {@code execute} — with a scripted downstream service, and hold every
 * emitted sequence to the canonical lifecycle the conformance fixture pins.
 */
public class BundledClinicalAnswerProviderTest {

	private static final String QUESTION = "What medications is this patient on?";

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-uuid-1");
		return patient;
	}

	private static TurnRequest request() {
		return new TurnRequest(patient(), QUESTION, "conversation-1", "request-1", null);
	}

	/** Collects every event the provider emits, in order. */
	private static class CollectingSink implements TurnEventSink {

		final List<TurnEvent> events = new ArrayList<>();

		@Override
		public void accept(TurnEvent event) {
			events.add(event);
		}

		List<TurnEventType> types() {
			return events.stream().map(TurnEvent::getType).collect(Collectors.toList());
		}

		TurnEvent single(TurnEventType type) {
			List<TurnEvent> matches = events.stream().filter(e -> e.getType() == type)
					.collect(Collectors.toList());
			assertEquals(1, matches.size(), "expected exactly one " + type.getWireName());
			return matches.get(0);
		}
	}

	/**
	 * Scripted stand-in for the bundled router: drives the streaming consumers exactly the way
	 * the production {@code LlmInferenceService} does (reasoning, tokens, ungrounded answer,
	 * then the grounded return), so the adapter is exercised against the real seam contract.
	 */
	private static class ScriptedChartSearchService implements ChartSearchService {

		ChartAnswer ungrounded;

		ChartAnswer groundedResult;

		RuntimeException failure;

		boolean fireUngroundedSeam = true;

		@Override
		public ChartAnswer search(Patient patient, String question) {
			throw new UnsupportedOperationException("adapter must use the streaming path");
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer) {
			throw new UnsupportedOperationException("adapter must use the full streaming overload");
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, citationsConsumer,
					ungroundedAnswerConsumer, ignored -> {
					});
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer, Consumer<String> preliminaryReasoningConsumer) {
			if (failure != null) {
				throw failure;
			}
			preliminaryReasoningConsumer.accept("preview ");
			reasoningConsumer.accept("thinking ");
			tokenConsumer.accept("Aspirin ");
			tokenConsumer.accept("81mg");
			if (fireUngroundedSeam) {
				citationsConsumer.accept(ungrounded.getReferences());
				ungroundedAnswerConsumer.accept(ungrounded);
			}
			return groundedResult;
		}
	}

	private static ChartSearchService.RecordReference reference(int index, Boolean grounded) {
		return new ChartSearchService.RecordReference(index, "obs", "obs-uuid-" + index, new Date(), grounded);
	}

	private static ChartSearchService.ChartAnswer answer(String text,
			List<ChartSearchService.RecordReference> references) {
		return new ChartSearchService.ChartAnswer(text, references, 100, 20, 5);
	}

	/** Provider with grounding enabled so the full canonical lifecycle (evidence_updated) runs. */
	private static BundledClinicalAnswerProvider provider(ChartSearchService service) {
		return new BundledClinicalAnswerProvider(service) {

			@Override
			protected String gp(String property, String defaultValue) {
				if (ChartSearchAiConstants.GP_GROUNDING_ENABLED.equals(property)) {
					return "true";
				}
				return defaultValue;
			}
		};
	}

	/** Provider with every optional GP left at its upstream default (grounding and drug safety off). */
	private static BundledClinicalAnswerProvider defaultConfigProvider(ChartSearchService service) {
		return new BundledClinicalAnswerProvider(service) {

			@Override
			protected String gp(String property, String defaultValue) {
				return defaultValue;
			}
		};
	}

	@Test
	public void aLiveStreamedTurnEmitsTheCanonicalLifecycle() throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.ungrounded = answer("Aspirin 81mg [1]", Arrays.asList(reference(1, null)));
		service.groundedResult = answer("Aspirin 81mg [1]", Arrays.asList(reference(1, Boolean.TRUE)));
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.REASONING_DELTA,
				TurnEventType.REASONING_DELTA, TurnEventType.ANSWER_DELTA, TurnEventType.ANSWER_DELTA,
				TurnEventType.ANSWER_DONE, TurnEventType.EVIDENCE_UPDATED, TurnEventType.TURN_DONE),
				sink.types());
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty(),
				"emitted events must satisfy the provider's own advertised capabilities");

		assertEquals("Aspirin ", sink.events.get(3).getTextDelta());
		TurnEvent answerDone = sink.single(TurnEventType.ANSWER_DONE);
		assertEquals("Aspirin 81mg [1]", answerDone.getAnswer().getAnswer());
		assertNull(answerDone.getAnswer().getReferences().get(0).getGrounded(),
				"answer_done fires before grounding, so verdicts are still null");
		TurnEvent evidence = sink.single(TurnEventType.EVIDENCE_UPDATED);
		assertEquals(Boolean.TRUE, evidence.getAnswer().getReferences().get(0).getGrounded());

		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals(BundledClinicalAnswerProvider.PROVIDER_ID, result.getProviderId());
		assertEquals(Boolean.TRUE, result.getAnswer().getReferences().get(0).getGrounded(),
				"the result carries the grounded answer");
		assertNull(result.getProblemCode());
	}

	@Test
	public void everyEventCarriesTheProviderIdentityAndAMonotonicSequence() throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.ungrounded = answer("a", Collections.emptyList());
		service.groundedResult = answer("a", Collections.emptyList());
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		provider.execute(request(), sink, CancellationSignal.NONE).toCompletableFuture().get();

		int previous = -1;
		for (TurnEvent event : sink.events) {
			assertEquals(BundledClinicalAnswerProvider.PROVIDER_ID, event.getProviderId());
			assertTrue(event.getSequence() > previous, "sequence must increase monotonically");
			previous = event.getSequence();
		}
	}

	@Test
	public void aCachedAnswerEmitsAnswerDoneFromTheFinalResultWithoutASeparateEvidenceEvent()
			throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.fireUngroundedSeam = false;
		service.groundedResult = answer("cached [1]", Arrays.asList(reference(1, Boolean.TRUE)));
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		TurnEvent answerDone = sink.single(TurnEventType.ANSWER_DONE);
		assertEquals("cached [1]", answerDone.getAnswer().getAnswer());
		assertEquals(Boolean.TRUE, answerDone.getAnswer().getReferences().get(0).getGrounded(),
				"a cached answer is already final, so answer_done carries its grounded verdicts");
		assertFalse(sink.types().contains(TurnEventType.EVIDENCE_UPDATED),
				"no separate evidence event when the answer arrived final");
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
	}

	@Test
	public void aPipelineFailureEndsInTurnErrorWithANormalizedProblemCode() throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.failure = new IllegalStateException("engine exploded");
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR), sink.types());
		assertEquals("provider_failure", result.getProblemCode());
		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertNull(result.getAnswer());
		assertEquals("provider_failure", sink.single(TurnEventType.TURN_ERROR).getProblemCode());
	}

	@Test
	public void aTooLargeChartMapsToItsOwnProblemCode() throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.failure = new ChartTooLargeException("chart exceeds context window");
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals("chart_too_large", result.getProblemCode());
		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
	}

	@Test
	public void theDescriptorAdvertisesBundledIdentityAndTruthfulDefaultCapabilities() {
		BundledClinicalAnswerProvider provider = defaultConfigProvider(new ScriptedChartSearchService());

		ProviderDescriptor descriptor = provider.descriptor();
		assertEquals(BundledClinicalAnswerProvider.PROVIDER_ID, descriptor.getId());
		assertNotNull(descriptor.getLabel());
		assertTrue(descriptor.getCapabilities().contains(ProviderCapability.ANSWER));
		assertTrue(descriptor.getCapabilities().contains(ProviderCapability.TOKEN_STREAMING));
		assertFalse(descriptor.getCapabilities().contains(ProviderCapability.GROUNDING),
				"grounding defaults off upstream, so it must not be advertised");
		assertFalse(descriptor.getCapabilities().contains(ProviderCapability.DRUG_SAFETY),
				"drug reference defaults off upstream, so it must not be advertised");
		assertFalse(descriptor.getCapabilities().contains(ProviderCapability.INDEPTH),
				"bundled has no In-Depth stage");
		assertEquals(Collections.singletonList(ProviderMode.QUERY_SCOPED), descriptor.getModes(),
				"bundled advertises the configured chart mode (queryScoped is the upstream default)");
	}

	@Test
	public void groundingAndDrugSafetyCapabilitiesFollowTheirGlobalProperties() {
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(
				new ScriptedChartSearchService()) {

			@Override
			protected String gp(String property, String defaultValue) {
				if (ChartSearchAiConstants.GP_GROUNDING_ENABLED.equals(property)
						|| ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED.equals(property)) {
					return "true";
				}
				return defaultValue;
			}
		};

		assertTrue(provider.descriptor().getCapabilities().contains(ProviderCapability.GROUNDING));
		assertTrue(provider.descriptor().getCapabilities().contains(ProviderCapability.DRUG_SAFETY));
	}

	@Test
	public void withGroundingOffTheAnswerIsFinalAtAnswerDoneAndNoEvidenceEventFollows()
			throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		// With grounding off the pipeline returns the same answer the ungrounded seam carried.
		service.ungrounded = answer("Aspirin 81mg [1]", Arrays.asList(reference(1, null)));
		service.groundedResult = service.ungrounded;
		BundledClinicalAnswerProvider provider = defaultConfigProvider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertFalse(sink.types().contains(TurnEventType.EVIDENCE_UPDATED),
				"a provider that does not advertise grounding must not emit evidence_updated");
		assertTrue(TurnLifecycleValidator
				.violations(provider.descriptor().getCapabilities(), sink.types()).isEmpty());
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
	}

	@Test
	public void theConfiguredFullChartModeIsAdvertisedAsFullChartStable() {
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(
				new ScriptedChartSearchService()) {

			@Override
			protected String gp(String property, String defaultValue) {
				if (ChartSearchAiConstants.GP_CHART_MODE.equals(property)) {
					return ChartSearchAiConstants.CHART_MODE_FULL_CHART;
				}
				return defaultValue;
			}
		};

		assertEquals(Collections.singletonList(ProviderMode.FULL_CHART_STABLE),
				provider.descriptor().getModes());
	}

	@Test
	public void aRequestForAModeTheProviderDoesNotOfferFailsExplicitlyInsteadOfSilentlySwitching()
			throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		service.ungrounded = answer("a", Collections.emptyList());
		service.groundedResult = answer("a", Collections.emptyList());
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnRequest fullChartRequest = new TurnRequest(patient(), QUESTION, "conversation-1", "request-1",
				ProviderMode.FULL_CHART_STABLE);
		TurnResult result = provider.execute(fullChartRequest, sink, CancellationSignal.NONE)
				.toCompletableFuture().get();

		assertEquals(TurnEventType.TURN_ERROR, result.getTerminalState());
		assertEquals("unsupported_mode", result.getProblemCode());
	}

	@Test
	public void aCancelledRequestNeverReachesThePipeline() throws Exception {
		ScriptedChartSearchService service = new ScriptedChartSearchService();
		// If cancellation is honored the pipeline never runs; if it runs anyway, this failure
		// surfaces as provider_failure instead of cancelled and the assertion below catches it.
		service.failure = new IllegalStateException("pipeline must not run after cancellation");
		BundledClinicalAnswerProvider provider = provider(service);

		CollectingSink sink = new CollectingSink();
		TurnResult result = provider.execute(request(), sink, () -> true).toCompletableFuture().get();

		assertEquals(Arrays.asList(TurnEventType.TURN_STARTED, TurnEventType.TURN_ERROR), sink.types());
		assertEquals("cancelled", result.getProblemCode());
	}
}
