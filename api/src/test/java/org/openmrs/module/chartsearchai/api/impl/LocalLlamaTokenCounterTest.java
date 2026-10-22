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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;

/**
 * The counting cases that run without a server override the engine's I/O methods only
 * ({@code getContextSize}, {@code countChatInputTokens}, {@code infer}, {@code inferStreaming}): the
 * messages those methods receive are built by the production {@link LlmProvider#answerMessages} path. Whether a real server's count
 * equals its inference usage is the opt-in
 * {@link #realEngineCountMatchesInferenceUsageAndArmsIdleUnload}.
 */
public class LocalLlamaTokenCounterTest extends BaseModuleContextSensitiveTest {

	@Test
	public void isAvailableExactlyWhenTheConfiguredEngineIsLocal() {
		LocalLlmEngine engine = new LocalLlmEngine();
		LlmProvider provider = new LlmProvider();
		ReflectionTestUtils.setField(provider, "localEngine", engine);
		ReflectionTestUtils.setField(provider, "remoteEngine", new RemoteLlmEngine());
		LocalLlamaTokenCounter counter = new LocalLlamaTokenCounter();
		counter.setLocalLlmEngine(engine);
		ReflectionTestUtils.setField(counter, "llmProvider", provider);
		for (String local : Arrays.asList(null, "local", " LOCAL ")) {
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, local);
			assertTrue(counter.isAvailable(), String.valueOf(local));
		}
		for (String remote : Arrays.asList("remote", " REMOTE ")) {
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, remote);
			assertFalse(counter.isAvailable(), remote);
		}
		engine.shutdown();
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
					String scope, String seed, ReferenceRecords referenceRecords) {
				inferred.set(system + "\n" + user);
				return new InferenceResult("{\"answer\":\"Recorded medication\",\"citations\":[]}", 37, 4);
			}

			@Override
			public synchronized InferenceResult inferStreaming(String system, String user, int timeout,
					Consumer<String> consumer, String scope, String seed, ReferenceRecords referenceRecords) {
				return infer(system, user, timeout, scope, seed, referenceRecords);
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
				provider.search(records, focus, question, null, null, enumerate, LlmEngine.ReferenceRecords.ABSENT, orders);
				assertEquals(inferred.get(), counted.get());
				provider.searchStreaming(records, focus, question, chunk -> { }, chunk -> { },
						null, null, enumerate, LlmEngine.ReferenceRecords.ABSENT, orders);
				assertEquals(inferred.get(), counted.get());
			}
		}
		engine.shutdown();
	}

	@Test
	public void anIdleTimerThatExpiresDuringACallWaitsOutTheRemainderInsteadOfClosing() {
		AtomicLong now = new AtomicLong(0);
		AtomicInteger closes = new AtomicInteger();
		LocalLlmEngine engine = new LocalLlmEngine() {
			@Override
			public synchronized void close() {
				closes.incrementAndGet();
			}
		};
		engine.clock = now::get;
		ReflectionTestUtils.setField(engine, "lastActivityNanos", 0L);
		try {
			// The call completed half a minute before the startup timer's callback took the lock.
			now.set(TimeUnit.SECONDS.toNanos(90));
			ReflectionTestUtils.setField(engine, "lastActivityNanos", TimeUnit.SECONDS.toNanos(60));
			engine.unloadIfIdle(1);
			assertEquals(0, closes.get(), "a call that finished inside the interval must keep the server");
			ScheduledFuture<?> remainder = (ScheduledFuture<?>) ReflectionTestUtils.getField(engine, "idleUnloadFuture");
			assertNotNull(remainder, "the remainder of the interval must be rescheduled");
			assertTrue(remainder.getDelay(TimeUnit.SECONDS) > 25 && remainder.getDelay(TimeUnit.SECONDS) <= 30);

			// Nothing happened for the full interval: the server is unloaded.
			remainder.cancel(false);
			now.set(TimeUnit.SECONDS.toNanos(120));
			engine.unloadIfIdle(1);
			assertEquals(1, closes.get(), "a full idle interval since the last activity unloads the server");
		}
		finally {
			engine.shutdown();
		}
	}

	/** Captures what the engine schedules, so a test runs the real callback at a time it chooses. */
	private static final class CapturingTimer extends java.util.concurrent.ScheduledThreadPoolExecutor {

		final List<Runnable> scheduled = new java.util.ArrayList<>();

		final List<ScheduledFuture<?>> futures = new java.util.ArrayList<>();

		CapturingTimer() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			scheduled.add(command);
			ScheduledFuture<?> future = super.schedule(() -> { }, 1, TimeUnit.DAYS);
			futures.add(future);
			return future;
		}
	}

	/** The scheduled callback itself, not only the method it calls, waits out a call that just finished. */
	@Test
	public void theScheduledIdleCallbackKeepsAServerACallJustUsed() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES, "1");
		AtomicLong now = new AtomicLong(0);
		AtomicInteger closes = new AtomicInteger();
		LocalLlmEngine engine = new LocalLlmEngine() {
			@Override
			public synchronized void close() {
				closes.incrementAndGet();
			}
		};
		CapturingTimer timer = new CapturingTimer();
		ReflectionTestUtils.setField(engine, "idleTimer", timer);
		engine.clock = now::get;
		try {
			ReflectionTestUtils.invokeMethod(engine, "resetIdleTimer");
			Runnable callback = timer.scheduled.get(0);
			// A call completed at 60 s, refreshing the activity the way every finished call does;
			// the timer armed at 0 still fires at 90 s.
			now.set(TimeUnit.SECONDS.toNanos(60));
			ReflectionTestUtils.invokeMethod(engine, "resetIdleTimer");
			now.set(TimeUnit.SECONDS.toNanos(90));
			callback.run();
			assertEquals(0, closes.get(), "the callback must wait out the interval since the last activity");
		}
		finally {
			timer.shutdownNow();
		}
	}

	/** A count, like an inference, restarts the idle interval: the timer it replaces is cancelled. */
	@Test
	public void aCountReplacesTheIdleTimerItFindsArmed() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES, "1");
		java.io.File model = new java.io.File(OpenmrsUtil.getApplicationDataDirectory(), "count-timer-test.gguf");
		model.createNewFile();
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH,
				"count-timer-test.gguf");
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
				new java.net.InetSocketAddress(LlamaServerEndpoint.LOOPBACK_HOST, 0), 0);
		server.createContext("/v1/messages/count_tokens", exchange -> {
			byte[] body = "{\"input_tokens\":5}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (java.io.OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		LocalLlmEngine engine = new LocalLlmEngine();
		CapturingTimer timer = new CapturingTimer();
		ReflectionTestUtils.setField(engine, "idleTimer", timer);
		try {
			// A server already running with the configured command, so the count does not launch one.
			String modelPath = ChartSearchAiUtils.resolveModelPath("count-timer-test.gguf",
					ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
			int port = server.getAddress().getPort();
			engine.recordLaunch(modelPath, engine.resolveKvCacheDir(), null,
					engine.currentServerCommand("/bin/llama-server", modelPath, port, null));
			ReflectionTestUtils.setField(engine, "serverProcess", new AliveProcess());
			ReflectionTestUtils.setField(engine, "endpoint", LlamaServerEndpoint.open(port));

			assertEquals(5, engine.countChatInputTokens("system", "user"));
			assertEquals(5, engine.countChatInputTokens("system", "user"));

			assertEquals(2, timer.futures.size(), "each count arms the idle timer");
			assertTrue(timer.futures.get(0).isCancelled(), "and the second replaces the first");
		}
		finally {
			ReflectionTestUtils.setField(engine, "serverProcess", null);
			timer.shutdownNow();
			server.stop(0);
			model.delete();
		}
	}

	/** A process that reports itself alive and does nothing else. */
	private static final class AliveProcess extends Process {

		@Override
		public java.io.OutputStream getOutputStream() {
			return java.io.OutputStream.nullOutputStream();
		}

		@Override
		public java.io.InputStream getInputStream() {
			return java.io.InputStream.nullInputStream();
		}

		@Override
		public java.io.InputStream getErrorStream() {
			return java.io.InputStream.nullInputStream();
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public int exitValue() {
			throw new IllegalThreadStateException("running");
		}

		@Override
		public void destroy() {
		}

		@Override
		public boolean isAlive() {
			return true;
		}
	}

	/**
	 * Opt-in: launches the real local engine from a tester-prepared application-data directory.
	 * The module spawns this server itself, so it is not one of the tester-started servers
	 * {@link LlmEndpointTestSupport} serves.
	 */
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
			// A short reasoning cap keeps the one inference quick on a slower host.
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_LLM_REASONING_MAX_CHARS, "200");
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
			LlmProvider.LlmResponse response = provider.search(chart, focus, "Can I add aspirin?", null, null, true,
					LlmEngine.ReferenceRecords.ABSENT, orders);
			assertEquals(count, response.getInputTokens(), "Count must equal the real inference request's usage");
		}
		finally {
			engine.shutdown();
			OpenmrsUtil.setApplicationDataDirectory(previousDirectory);
		}
	}

}
