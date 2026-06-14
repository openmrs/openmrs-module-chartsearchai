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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local LLM engine that manages an embedded llama-server subprocess for GGUF
 * model inference. The server is started lazily on first use and automatically
 * stopped after an idle period to free memory. It exposes an
 * OpenAI-compatible chat completions API internally.
 *
 * <p>Public methods ({@code infer}, {@code inferStreaming}, {@code warmup},
 * {@code close}) are {@code synchronized} so that only one call is in flight
 * at a time. Three reasons:
 * <ul>
 *   <li>The subprocess is launched with {@code --parallel 1} (see
 *       {@link #buildServerCommand}), so concurrent callers would queue inside
 *       llama-server with no parallelism gain.</li>
 *   <li>The prefix-cache-reuse strategy ({@code --cache-reuse} +
 *       {@code cache_prompt=true}, primed by {@link #warmup}) assumes serial
 *       access on the same chart prefix; interleaved calls with different
 *       prefixes would thrash the KV cache and erase the warmup gain.</li>
 *   <li>Subprocess and HTTP-client lifecycle state ({@code serverProcess},
 *       {@code loadedModelPath}, {@code loadedContextSize}, {@code httpClient},
 *       {@code idleUnloadFuture}) is shared mutable state that
 *       {@link #ensureServerRunning} and the idle timer mutate; without the
 *       monitor, callers could race on start/stop or tear down the
 *       {@code HttpClient} mid-request.</li>
 * </ul>
 * {@link RemoteLlmEngine} does not synchronize {@code infer} for this reason —
 * the remote endpoint handles concurrency itself and there is no subprocess to
 * manage.
 *
 * <p>See {@code docs/adr.md} &mdash; Decision 12: Concurrency model &mdash;
 * for the fuller rationale (alternatives considered, impact on concurrent
 * users, future options).
 */
@Component("chartSearchAi.localLlmEngine")
public class LocalLlmEngine implements LlmEngine {

	private static final Logger log = LoggerFactory.getLogger(LocalLlmEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int SERVER_STARTUP_TIMEOUT_SECONDS = 120;

	private static final int HEALTH_POLL_INTERVAL_MS = 500;

	private Process serverProcess;

	private String loadedModelPath;

	private int loadedContextSize = -1;

	private int serverPort;

	private HttpClient httpClient;

	private final ScheduledExecutorService idleTimer = Executors.newSingleThreadScheduledExecutor(
			r -> {
				Thread t = new Thread(r, "chartsearchai-llm-idle-timer");
				t.setDaemon(true);
				return t;
			});

	private ScheduledFuture<?> idleUnloadFuture;

	@Override
	public synchronized InferenceResult infer(String systemPrompt, String userMessage,
			int timeoutSeconds) {
		ensureServerRunning();
		return postForResult(buildRequestBody(systemPrompt, userMessage, false), timeoutSeconds);
	}

	@Override
	public synchronized InferenceResult infer(String systemPrompt, String userMessage,
			int timeoutSeconds, ObjectNode responseFormat) {
		ensureServerRunning();
		String requestBody = responseFormat == null
				? buildRequestBody(systemPrompt, userMessage, false)
				: buildRequestBody(systemPrompt, userMessage, false,
						ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS, responseFormat);
		return postForResult(requestBody, timeoutSeconds);
	}

	/**
	 * Sends a pre-built request body to the local llama-server and parses the (non-streaming)
	 * result. Shared by the default-schema and custom-{@code response_format} {@link #infer}
	 * overloads. Called only from {@code synchronized} methods, so it runs under the engine lock.
	 */
	private InferenceResult postForResult(String requestBody, int timeoutSeconds) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.error("Local llama-server returned HTTP {}: {}", response.statusCode(),
						truncate(response.body()));
				if (response.statusCode() == 400 && LlmResponseParser.isContextOverflowError(response.body())) {
					throw new ChartTooLargeException(
							"Patient chart exceeds the LLM context window of "
									+ getContextSize() + " tokens. Increase "
									+ ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE
									+ " or enable embedding pre-filter.");
				}
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			resetIdleTimer();
			return LlmResponseParser.parseResponse(response.body(), log);
		}
		catch (IOException e) {
			throw new APIException("Failed to call local llama-server: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Local llama-server call was interrupted", e);
		}
	}

	@Override
	public synchronized InferenceResult inferStreaming(String systemPrompt, String userMessage,
			int timeoutSeconds, Consumer<String> tokenConsumer) {
		ensureServerRunning();

		String requestBody = buildRequestBody(systemPrompt, userMessage, true);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<InputStream> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
				log.error("Local llama-server returned HTTP {}: {}", response.statusCode(),
						truncate(body));
				if (response.statusCode() == 400 && LlmResponseParser.isContextOverflowError(body)) {
					throw new ChartTooLargeException(
							"Patient chart exceeds the LLM context window of "
									+ getContextSize() + " tokens. Increase "
									+ ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE
									+ " or enable embedding pre-filter.");
				}
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			InferenceResult result = LlmResponseParser.parseStreamingResponse(
					response.body(), tokenConsumer, log);
			resetIdleTimer();
			return result;
		}
		catch (IOException e) {
			throw new APIException("Failed to call local llama-server: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Local llama-server call was interrupted", e);
		}
	}

	@Override
	public synchronized void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		ensureServerRunning();

		// max_tokens=1 forces llama-server to do the prompt prefill (loading the system+user
		// message into the KV cache) without spending time on real generation. The prefix
		// cached this way is what a real query reuses via cache_prompt=true + --cache-reuse.
		String requestBody = buildRequestBody(systemPrompt, userMessage, false, 1);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("Warmup returned HTTP {}: {}", response.statusCode(),
						truncate(response.body()));
				return;
			}

			InferenceResult result = LlmResponseParser.parseResponse(response.body(), log);
			// WARN — visible under OpenMRS's default log4j2 (org.openmrs.* pinned to WARN);
			// the cached-token count is the only direct evidence the warmup primed the KV.
			log.warn("Warmup primed KV cache: {} input ({} cached)",
					result.getInputTokens(), result.getCachedTokens());
			resetIdleTimer();
		}
		catch (IOException e) {
			log.warn("Warmup failed: {}", e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.debug("Warmup interrupted");
		}
	}

	@Override
	public synchronized void close() {
		if (idleUnloadFuture != null) {
			idleUnloadFuture.cancel(false);
			idleUnloadFuture = null;
		}
		stopServer();
	}

	@Override
	public void shutdown() {
		idleTimer.shutdownNow();
		close();
	}

	private void ensureServerRunning() {
		String modelPath = resolveModelPath();
		int currentContextSize = getContextSize();

		if (serverProcess != null && serverProcess.isAlive()
				&& !shouldRestartServer(loadedModelPath, loadedContextSize,
						modelPath, currentContextSize)) {
			return;
		}

		if (serverProcess != null && serverProcess.isAlive()) {
			log.info("LLM config changed (model {}→{}, ctx {}→{}), restarting server",
					loadedModelPath, modelPath, loadedContextSize, currentContextSize);
			stopServer();
		}

		startServer(modelPath);
	}

	/**
	 * Decides whether a running llama-server needs to be restarted because its
	 * model path or context size differs from the latest configured values.
	 * Returns false when nothing has been loaded yet — the caller handles the
	 * initial start in that case.
	 */
	static boolean shouldRestartServer(String loadedPath, int loadedCtx,
			String currentPath, int currentCtx) {
		if (loadedPath == null) {
			return false;
		}
		return !loadedPath.equals(currentPath) || loadedCtx != currentCtx;
	}

	/**
	 * Builds the llama-server argument list. Tuned for chart-search workloads:
	 * long input prompts (whole patient charts), single-user latency-sensitive
	 * generation.
	 *
	 * <p>Flag rationale:
	 * <ul>
	 *   <li>{@code -ngl 99} — offload all layers to GPU when a GPU build is in use; no-op on CPU build.</li>
	 *   <li>{@code -fa on} — flash attention; cuts attention compute on long-context chart prompts.</li>
	 *   <li>{@code --parallel 1} — single decode slot. Chart-search is one request at a time per
	 *       process; the default 4 slots add LRU eviction noise that interferes with prefix-cache
	 *       reuse for no benefit.</li>
	 *   <li>{@code --mlock} — pin model weights in RAM so the OS cannot page them out under
	 *       memory pressure, avoiding multi-second stalls on a busy host.</li>
	 *   <li>{@code -b 4096 -ub 1024} — large batch sizes for prompt processing. Default 2048/512
	 *       leaves prompt-processing parallelism on the table for chart-length inputs.</li>
	 *   <li>{@code --cache-reuse 0} + {@code cache_prompt=true} (in request body) — reuse the
	 *       chart prefix's KV cache across successive queries on the same patient via the
	 *       request-body flag (exact-prefix match). The CLI flag is pinned to 0 (llama.cpp's
	 *       default) because the focus-hint prompt structure has byte-identical prefix bytes
	 *       across successive queries on the same patient — KV shifting (the {@code N>0}
	 *       behavior) is for fuzzy prefix matching when the prefix bytes drift slightly, and
	 *       that case doesn't arise on this prompt shape. Note that cache_prompt itself
	 *       introduces a low-level non-determinism on borderline argmax decisions because the
	 *       reused-vs-fresh KV path is numerically close but not bit-identical — observed as
	 *       "is she pregnant?" alternating between Gravida and Self-Induced Abortion on
	 *       successive identical requests for patient 4acc0b80. The trade-off (latency win
	 *       from cache_prompt vs. determinism on borderline questions) is fundamental to
	 *       llama-server's design; cache_prompt stays on because the latency win is the
	 *       whole point.</li>
	 *   <li>{@code --reasoning-budget 0} — disable reasoning channel; json_schema does not
	 *       constrain it and Gemma 4 burns thousands of tokens before the answer.</li>
	 * </ul>
	 *
	 * <p>Thread count and KV-cache dtype are left to llama-server's auto-detect. Explicit
	 * pinning (e.g. {@code -t/--tb}) and KV quantization (e.g. {@code --cache-type-k/v q4_0})
	 * regress prefill on hosts where logical-core count exceeds physical cores or where the
	 * backend's native KV path is faster than the dequantize-on-read kernel — both common.
	 */
	static List<String> buildServerCommand(String binaryPath, String modelPath, int port,
			int contextSize) {
		List<String> cmd = new ArrayList<>();
		cmd.add(binaryPath);
		cmd.add("-m");
		cmd.add(modelPath);
		cmd.add("--port");
		cmd.add(String.valueOf(port));
		cmd.add("-ngl");
		cmd.add("99");
		cmd.add("-fa");
		cmd.add("on");
		cmd.add("-c");
		cmd.add(String.valueOf(contextSize));
		cmd.add("--parallel");
		cmd.add("1");
		cmd.add("--mlock");
		cmd.add("-b");
		cmd.add("4096");
		cmd.add("-ub");
		cmd.add("1024");
		cmd.add("--cache-reuse");
		cmd.add("0");
		cmd.add("--reasoning-budget");
		cmd.add("0");
		cmd.add("--log-disable");
		return cmd;
	}

	private void startServer(String modelPath) {
		String serverBinaryPath = LlamaServerBinary.resolve();
		serverPort = getServerPort();

		List<String> command = buildServerCommand(serverBinaryPath, modelPath, serverPort,
				getContextSize());

		log.info("Starting llama-server on port {} with model {}", serverPort, modelPath);

		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			String binDir = new File(serverBinaryPath).getParent();
			if (binDir != null) {
				pb.environment().put("DYLD_LIBRARY_PATH", binDir);
				pb.environment().put("LD_LIBRARY_PATH", binDir);
			}
			serverProcess = pb.start();

			// Drain server output in a daemon thread to prevent buffer blocking
			Thread outputDrain = new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(serverProcess.getInputStream(),
								StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						log.debug("llama-server: {}", line);
					}
				}
				catch (IOException e) {
					log.debug("llama-server output stream closed");
				}
			}, "chartsearchai-llama-server-output");
			outputDrain.setDaemon(true);
			outputDrain.start();

			waitForServerReady();
			loadedModelPath = modelPath;
			loadedContextSize = getContextSize();
			log.info("llama-server started successfully on port {}", serverPort);
		}
		catch (IOException e) {
			throw new APIException(
					"Failed to start llama-server. Ensure the binary exists at "
							+ serverBinaryPath + ": " + e.getMessage(), e);
		}
	}

	private void waitForServerReady() {
		long deadline = System.currentTimeMillis()
				+ (SERVER_STARTUP_TIMEOUT_SECONDS * 1000L);
		String healthUrl = "http://127.0.0.1:" + serverPort + "/health";

		while (System.currentTimeMillis() < deadline) {
			if (!serverProcess.isAlive()) {
				throw new APIException(
						"llama-server process exited during startup with code "
								+ serverProcess.exitValue());
			}
			try {
				HttpResponse<String> response = getHttpClient().send(
						HttpRequest.newBuilder()
								.uri(URI.create(healthUrl))
								.timeout(Duration.ofSeconds(2))
								.GET()
								.build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					JsonNode json = MAPPER.readTree(response.body());
					String status = json.has("status") ? json.get("status").asText() : "";
					if ("ok".equals(status)) {
						return;
					}
				}
			}
			catch (IOException | InterruptedException e) {
				// Server not ready yet
			}
			try {
				Thread.sleep(HEALTH_POLL_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new APIException("Interrupted while waiting for llama-server to start");
			}
		}
		stopServer();
		throw new APIException("llama-server did not become healthy within "
				+ SERVER_STARTUP_TIMEOUT_SECONDS + " seconds");
	}

	private void stopServer() {
		if (serverProcess != null) {
			log.info("Stopping llama-server");
			serverProcess.destroy();
			try {
				if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) {
					serverProcess.destroyForcibly();
				}
			}
			catch (InterruptedException e) {
				serverProcess.destroyForcibly();
				Thread.currentThread().interrupt();
			}
			serverProcess = null;
			loadedModelPath = null;
			loadedContextSize = -1;
		}
		httpClient = null;
	}

	static boolean isContextOverflowError(String responseBody) {
		return LlmResponseParser.isContextOverflowError(responseBody);
	}

	String buildRequestBody(String systemPrompt, String userMessage, boolean stream) {
		return buildRequestBody(systemPrompt, userMessage, stream,
				ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS);
	}

	String buildRequestBody(String systemPrompt, String userMessage, boolean stream,
			int maxTokens) {
		return buildRequestBody(systemPrompt, userMessage, stream, maxTokens,
				ChartAnswerResponseFormat.build(MAPPER, resolveReasoningMaxChars()));
	}

	/** Test seam wrapping {@link ChartSearchAiUtils#getReasoningMaxChars()} (fail-safe 0). */
	int resolveReasoningMaxChars() {
		return ChartSearchAiUtils.getReasoningMaxChars();
	}

	/**
	 * As {@link #buildRequestBody(String, String, boolean, int)} but with a caller-supplied
	 * {@code response_format} (e.g. the verdict-only {@link EntailmentBatchResponseFormat}) in
	 * place of the default chart-answer schema. Every other field — temperature, the pinned
	 * sampler chain, DRY penalties, {@code cache_prompt} — is identical, so KV-cache reuse and
	 * decoding behaviour are unchanged.
	 */
	String buildRequestBody(String systemPrompt, String userMessage, boolean stream,
			int maxTokens, ObjectNode responseFormat) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("temperature", 0.0);
		root.put("max_tokens", maxTokens);
		root.put("stream", stream);
		// At temperature=0 the decode is greedy (argmax). The default sampler chain
		// (penalties, dry, top_n_sigma, top_k, typ_p, top_p, min_p, xtc, temperature)
		// runs every one of those samplers per token. At greedy most are no-ops on
		// the OUTPUT but still consume CPU, so we pin the chain to the two samplers
		// that matter: DRY (penalizes multi-token sequence repeats, which is the
		// failure mode small models exhibit on chart search — verbatim repetition
		// of date+finding blocks — without penalizing single-token repetition,
		// which is required for legitimate extraction of dates and identifiers
		// from the chart) and temperature.
		ArrayNode samplers = MAPPER.createArrayNode();
		samplers.add("dry");
		samplers.add("temperature");
		root.set("samplers", samplers);
		// DRY parameters tuned for extractive QA over patient charts: penalize
		// n-gram repeats of length >= 8. Catastrophic loops observed in the
		// 14-model benchmark were all 10+ token sequences ("Ovarian cyst, Zika
		// virus disease, Haemoglobin: 15.8 g/dL (HIGH)," is ~18 tokens; "On
		// 2024-02-28, the patient had a high temperature" is 15+). At
		// allowed_length=4 the penalty fired on 4-7 token date n-grams in the
		// chart context (e.g. "on 2023-05-04 [") and forced the model to drift
		// to neighboring digits (2023-05-04 -> 2023-05-03, 2026-02-28 ->
		// 2026-02-18) or switch scripts to dodge ("Serum potassium" -> "Serum
		// पोटेशियम"). allowed_length=8 keeps the loop-catching margin while
		// letting date and identifier n-grams pass through unchanged.
		root.put("dry_multiplier", 0.8);
		root.put("dry_base", 1.75);
		root.put("dry_allowed_length", 8);
		root.put("dry_penalty_last_n", -1);
		// llama.cpp-specific extension. Without it, each request reprocesses the
		// whole prompt from scratch, so successive queries on the same patient
		// pay full prefill cost every time. With it set, llama-server reuses
		// the slot's KV cache for any matching prefix — typically the system
		// prompt + chart text are byte-identical between queries on one patient,
		// so only the new question's tokens need processing. Order-of-magnitude
		// latency win for repeat queries.
		root.put("cache_prompt", true);
		if (stream) {
			ObjectNode streamOptions = MAPPER.createObjectNode();
			streamOptions.put("include_usage", true);
			root.set("stream_options", streamOptions);
		}

		root.set("response_format", responseFormat);
		root.set("messages", ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage));

		try {
			return MAPPER.writeValueAsString(root);
		}
		catch (IOException e) {
			throw new APIException("Failed to build request body", e);
		}
	}

	private String getCompletionsUrl() {
		return "http://127.0.0.1:" + serverPort + "/v1/chat/completions";
	}

	private String resolveModelPath() {
		String configuredPath = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		if (configuredPath == null || configuredPath.trim().isEmpty()) {
			throw new IllegalStateException(
					"LLM model path not configured. Set the global property: "
							+ ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		}
		return ChartSearchAiUtils.resolveModelPath(
				configuredPath.trim(), ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
	}

	int getContextSize() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid context size '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_CONTEXT_SIZE;
	}

	int getServerPort() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_SERVER_PORT);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0 && parsed <= 65535) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid server port '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_SERVER_PORT;
	}

	private int getIdleTimeoutMinutes() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed >= 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid idle timeout value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_IDLE_TIMEOUT_MINUTES;
	}

	private synchronized void resetIdleTimer() {
		if (idleUnloadFuture != null) {
			idleUnloadFuture.cancel(false);
			idleUnloadFuture = null;
		}
		int idleMinutes = getIdleTimeoutMinutes();
		if (idleMinutes > 0) {
			try {
				idleUnloadFuture = idleTimer.schedule(() -> {
					log.info("LLM idle for {} minutes, stopping server to free memory",
							idleMinutes);
					close();
				}, idleMinutes, TimeUnit.MINUTES);
			}
			catch (java.util.concurrent.RejectedExecutionException e) {
				log.debug("Idle timer already shut down, skipping schedule");
			}
		}
	}

	private synchronized HttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.build();
		}
		return httpClient;
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}

}
