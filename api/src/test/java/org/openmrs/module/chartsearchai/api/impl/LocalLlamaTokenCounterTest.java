/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.module.chartsearchai.api.provider.TurnCancellation;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

public class LocalLlamaTokenCounterTest extends BaseModuleContextSensitiveTest {

	@Test
	public void availabilityMatchesTheConfiguredEngine() {
		assertTrue(LocalLlamaTokenCounter.supportsExactCount(null));
		assertTrue(LocalLlamaTokenCounter.supportsExactCount("local"));
		assertTrue(LocalLlamaTokenCounter.supportsExactCount(" LOCAL "));
		assertFalse(LocalLlamaTokenCounter.supportsExactCount("remote"));
		assertFalse(LocalLlamaTokenCounter.supportsExactCount(" REMOTE "));
	}

	@Test
	public void delegatesCountingAndDerivesTheInputBudget() {
		AtomicReference<String> measuredUserMessage = new AtomicReference<>();
		LocalLlmEngine engine = new LocalLlmEngine() {

			@Override
			int getContextSize() {
				return 8192;
			}

			@Override
			synchronized int countChatInputTokens(String systemPrompt, String userMessage) {
				measuredUserMessage.set(userMessage);
				return 37;
			}
		};
		LocalLlamaTokenCounter counter = new LocalLlamaTokenCounter();
		ReflectionTestUtils.setField(counter, "llmProvider", new LlmProvider());
		counter.setLocalLlmEngine(engine);

		assertEquals(37, counter.countPrompt("[1] Medication: Aspirin",
				Arrays.asList(1, 3), "What medications is the patient taking?",
				false, Collections.emptyList()));
		assertTrue(measuredUserMessage.get().contains("[1] Medication: Aspirin"));
		assertTrue(measuredUserMessage.get().contains(
				"Records ranked by similarity to the query: 1, 3"));
		assertTrue(measuredUserMessage.get().endsWith(
				"Clinician's query: What medications is the patient taking?"));
		assertEquals(8192 - ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS,
				counter.inputBudget());
		engine.shutdown();
	}

	@Test
	public void countsExactlyTheMessagesUsedForInference() {
		AtomicReference<String> counted = new AtomicReference<>();
		AtomicReference<String> inferred = new AtomicReference<>();
		LocalLlmEngine engine = new LocalLlmEngine() {
			@Override
			synchronized int countChatInputTokens(String system, String user) {
				counted.set(system + "\n" + user);
				return 37;
			}

			@Override
			public synchronized InferenceResult infer(String system, String user, int timeout,
					ReferenceRecords referenceRecords) {
				inferred.set(system + "\n" + user);
				return new InferenceResult("{\"answer\":\"Recorded medication\",\"citations\":[]}", 37, 4);
			}

			@Override
			public synchronized InferenceResult inferStreaming(String system, String user, int timeout,
					Consumer<String> consumer, String scope, String seed, ReferenceRecords referenceRecords, CancellationSignal cancellation) {
				return infer(system, user, timeout, referenceRecords);
			}
		};
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SYSTEM_PROMPT, "Custom system instructions");
		LlmProvider provider = new LlmProvider();
		ReflectionTestUtils.setField(provider, "localEngine", engine);
		LocalLlamaTokenCounter counter = new LocalLlamaTokenCounter();
		counter.setLocalLlmEngine(engine);
		List<AlreadyOrderedDrug> orders =
				Collections.singletonList(new AlreadyOrderedDrug(
						"aspirin", Collections.singletonList("aspirin 75 mg daily"), 1, "an existing order"));
		String records = "[1] Medication: Aspirin 75 mg daily";
		String question = "Can I add aspirin?";
		List<Integer> focus = Collections.singletonList(1);
		ReflectionTestUtils.setField(counter, "llmProvider", provider);
		for (boolean clientRendersFindings : Arrays.asList(false, true)) {
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_SAFETY_FINDINGS_RENDERED_BY_CLIENT,
					Boolean.toString(clientRendersFindings));
			for (boolean enumerate : Arrays.asList(false, true)) {
				counter.countPrompt(records, focus, question, enumerate, orders);
				provider.search(records, focus, question, enumerate, LlmEngine.ReferenceRecords.ABSENT, orders);
				assertEquals(inferred.get(), counted.get());
				provider.searchStreaming(records, focus, question, chunk -> { }, chunk -> { },
						null, null, enumerate, LlmEngine.ReferenceRecords.ABSENT, orders);
				assertEquals(inferred.get(), counted.get());
				provider.searchStreaming(records, focus, question, chunk -> { }, chunk -> { },
						null, null, enumerate, LlmEngine.ReferenceRecords.ABSENT, orders, new TurnCancellation());
				assertEquals(inferred.get(), counted.get());
			}
		}
		engine.shutdown();
	}

	/** Opt-in: launches the real local engine from a tester-prepared application-data directory. */
	@Test
	public void realEngineCountMatchesInferenceUsageAndArmsIdleUnload() {
		LlmEndpointTestSupport.assumeOptedIn("chartsearchai.test.tokenCount.enabled");
		String directory = System.getProperty("chartsearchai.test.tokenCount.dataDirectory");
		assertNotNull(directory,
				"Set dataDirectory containing model.gguf and chartsearchai/bin/llama-server");
		String previousDirectory = OpenmrsUtil.getApplicationDataDirectory();
		LocalLlmEngine engine = new LocalLlmEngine();
		try {
			OpenmrsUtil.setApplicationDataDirectory(directory);
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH, "model.gguf");
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE, "4096");
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES, "1");
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_KV_CACHE_DIR, "off");
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_SERVER_PORT,
					System.getProperty("chartsearchai.test.tokenCount.port", "18095"));
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_SYSTEM_PROMPT,
					"Return only this JSON: {\"answer\":\"Recorded\",\"citations\":[]}");
			LlmProvider provider = new LlmProvider();
			ReflectionTestUtils.setField(provider, "localEngine", engine);
			LocalLlamaTokenCounter counter = new LocalLlamaTokenCounter();
			counter.setLocalLlmEngine(engine);
			ReflectionTestUtils.setField(counter, "llmProvider", provider);
			String chart = "[1] Medication: aspirin 75 mg daily";
			List<Integer> focus = Collections.singletonList(1);
			List<AlreadyOrderedDrug> orders = Collections.singletonList(new AlreadyOrderedDrug(
					"aspirin", Collections.singletonList("aspirin 75 mg daily"), 1, "an existing order"));
			// Invalid timeout fails after cold startup, before any generation request is sent.
			assertThrows(IllegalArgumentException.class, () -> engine.infer("system", chart, 0));
			ScheduledFuture<?> failedCallIdle = (ScheduledFuture<?>)
					ReflectionTestUtils.getField(engine, "idleUnloadFuture");
			assertNotNull(failedCallIdle, "A failed first inference must not leave a loaded server indefinitely");
			assertFalse(failedCallIdle.isDone());
			engine.close();
			int count = counter.countPrompt(chart, focus, "Can I add aspirin?", true, orders);
			assertTrue(count > 0);
			ScheduledFuture<?> idle = (ScheduledFuture<?>)
					ReflectionTestUtils.getField(engine, "idleUnloadFuture");
			assertNotNull(idle, "Counting alone must arm idle unload");
			assertFalse(idle.isDone());
			LlmProvider.LlmResponse response = provider.search(chart, focus, "Can I add aspirin?", true,
					LlmEngine.ReferenceRecords.ABSENT, orders);
			assertEquals(count, response.getInputTokens(), "Count must equal the real inference request's usage");
		}
		finally {
			engine.shutdown();
			OpenmrsUtil.setApplicationDataDirectory(previousDirectory);
		}
	}

}
