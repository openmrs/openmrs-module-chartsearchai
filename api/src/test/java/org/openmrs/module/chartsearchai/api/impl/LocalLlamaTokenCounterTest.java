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

	/** Captures what the engine schedules, so a test runs the real callback at a time it chooses. */
	private static final class CapturingTimer extends java.util.concurrent.ScheduledThreadPoolExecutor {

		final List<Runnable> scheduled = new java.util.ArrayList<>();

		final List<Long> delaysSeconds = new java.util.ArrayList<>();

		final List<ScheduledFuture<?>> futures = new java.util.ArrayList<>();

		CapturingTimer() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			scheduled.add(command);
			delaysSeconds.add(unit.toSeconds(delay));
			ScheduledFuture<?> future = super.schedule(() -> { }, 1, TimeUnit.DAYS);
			futures.add(future);
			return future;
		}
	}

	/** A count endpoint answering 5 tokens, standing in for the running local server. */
	private static com.sun.net.httpserver.HttpServer startCountServer() throws java.io.IOException {
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
		return server;
	}

	/** Records a server already running with the configured command, so a count does not launch one. */
	private static void attachRunningServer(LocalLlmEngine engine, com.sun.net.httpserver.HttpServer server) {
		String modelPath = ChartSearchAiUtils.resolveModelPath("count-timer-test.gguf",
				ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		int port = server.getAddress().getPort();
		engine.recordLaunch(modelPath, engine.resolveKvCacheDir(), null,
				engine.currentServerCommand("/bin/llama-server", modelPath, port, null));
		ReflectionTestUtils.setField(engine, "serverProcess", new AliveProcess());
		ReflectionTestUtils.setField(engine, "endpoint", LlamaServerEndpoint.open(port));
	}

	private static java.io.File configureIdleTestModel() throws java.io.IOException {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES, "1");
		java.io.File model = new java.io.File(OpenmrsUtil.getApplicationDataDirectory(), "count-timer-test.gguf");
		model.createNewFile();
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH,
				"count-timer-test.gguf");
		return model;
	}

	/**
	 * A timer that fires while a later call is in flight runs after that call: the callback waits
	 * out the rest of the interval since the call, then unloads once a full interval passes with
	 * nothing. Both calls are real counts, so a call that stopped refreshing the activity fails it.
	 */
	@Test
	public void theIdleCallbackWaitsOutACallThatFinishedAfterItWasArmed() throws Exception {
		java.io.File model = configureIdleTestModel();
		com.sun.net.httpserver.HttpServer server = startCountServer();
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
			attachRunningServer(engine, server);
			assertEquals(5, engine.countChatInputTokens("system", "user"));
			Runnable armedFirst = timer.scheduled.get(0);
			now.set(TimeUnit.SECONDS.toNanos(60));
			assertEquals(5, engine.countChatInputTokens("system", "user"));

			// The first timer expired during the second count and took the lock after it, at 90 s.
			now.set(TimeUnit.SECONDS.toNanos(90));
			armedFirst.run();
			assertEquals(0, closes.get(), "a count that finished inside the interval must keep the server");
			assertEquals(3, timer.scheduled.size(), "the remainder of the interval must be rescheduled");
			assertEquals(30L, timer.delaysSeconds.get(2));

			// Nothing happened for a full interval since the second count: the server is unloaded.
			now.set(TimeUnit.SECONDS.toNanos(120));
			timer.scheduled.get(2).run();
			assertEquals(1, closes.get(), "a full idle interval since the last activity unloads the server");
		}
		finally {
			ReflectionTestUtils.setField(engine, "serverProcess", null);
			timer.shutdownNow();
			server.stop(0);
			model.delete();
		}
	}

	/** A count, like an inference, restarts the idle interval: the timer it replaces is cancelled. */
	@Test
	public void aCountReplacesTheIdleTimerItFindsArmed() throws Exception {
		java.io.File model = configureIdleTestModel();
		com.sun.net.httpserver.HttpServer server = startCountServer();
		LocalLlmEngine engine = new LocalLlmEngine();
		CapturingTimer timer = new CapturingTimer();
		ReflectionTestUtils.setField(engine, "idleTimer", timer);
		try {
			attachRunningServer(engine, server);

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
