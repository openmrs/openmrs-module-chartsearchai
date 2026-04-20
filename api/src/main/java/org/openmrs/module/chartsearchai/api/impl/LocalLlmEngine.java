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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local LLM engine that manages an embedded llama-server subprocess for GGUF
 * model inference. The server is started lazily on first use and automatically
 * stopped after an idle period to free memory. It exposes an
 * OpenAI-compatible chat completions API internally.
 */
@Component("chartSearchAi.localLlmEngine")
public class LocalLlmEngine implements LlmEngine {

	private static final Logger log = LoggerFactory.getLogger(LocalLlmEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int SERVER_STARTUP_TIMEOUT_SECONDS = 120;

	private static final int HEALTH_POLL_INTERVAL_MS = 500;

	private Process serverProcess;

	private String loadedModelPath;

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

		String requestBody = buildRequestBody(systemPrompt, userMessage, false);

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
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			resetIdleTimer();
			return parseResponse(response.body());
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
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			InferenceResult result = parseStreamingResponse(response.body(), tokenConsumer);
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

		if (serverProcess != null && serverProcess.isAlive()
				&& modelPath.equals(loadedModelPath)) {
			return;
		}

		if (serverProcess != null && serverProcess.isAlive()
				&& !modelPath.equals(loadedModelPath)) {
			log.info("LLM model path changed from {} to {}, restarting server",
					loadedModelPath, modelPath);
			stopServer();
		}

		startServer(modelPath);
	}

	private void startServer(String modelPath) {
		String serverBinaryPath = resolveServerBinaryPath();
		serverPort = getServerPort();

		List<String> command = new ArrayList<>();
		command.add(serverBinaryPath);
		command.add("-m");
		command.add(modelPath);
		command.add("--port");
		command.add(String.valueOf(serverPort));
		command.add("-ngl");
		command.add("99");
		command.add("-fa");
		command.add("on");
		command.add("-c");
		command.add("4096");
		command.add("--log-disable");

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
		}
		httpClient = null;
	}

	private String buildRequestBody(String systemPrompt, String userMessage, boolean stream) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("temperature", 0.0);
		root.put("max_tokens", ChartSearchAiConstants.DEFAULT_MAX_TOKENS);
		root.put("stream", stream);
		if (stream) {
			ObjectNode streamOptions = MAPPER.createObjectNode();
			streamOptions.put("include_usage", true);
			root.set("stream_options", streamOptions);
		}

		ObjectNode responseFormat = MAPPER.createObjectNode();
		responseFormat.put("type", "json_object");
		root.set("response_format", responseFormat);

		ArrayNode messages = MAPPER.createArrayNode();

		ObjectNode systemMsg = MAPPER.createObjectNode();
		systemMsg.put("role", "system");
		systemMsg.put("content", systemPrompt);
		messages.add(systemMsg);

		ObjectNode userMsg = MAPPER.createObjectNode();
		userMsg.put("role", "user");
		userMsg.put("content", userMessage);
		messages.add(userMsg);

		root.set("messages", messages);

		try {
			return MAPPER.writeValueAsString(root);
		}
		catch (IOException e) {
			throw new APIException("Failed to build request body", e);
		}
	}

	private InferenceResult parseResponse(String responseBody) throws IOException {
		JsonNode root = MAPPER.readTree(responseBody);

		String text = "";
		JsonNode choices = root.get("choices");
		if (choices != null && choices.isArray() && !choices.isEmpty()) {
			JsonNode message = choices.get(0).get("message");
			if (message != null && message.has("content")) {
				text = message.get("content").asText("");
			}
		}

		int inputTokens = 0;
		int outputTokens = 0;
		JsonNode usage = root.get("usage");
		if (usage != null) {
			inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt(0) : 0;
			outputTokens = usage.has("completion_tokens")
					? usage.get("completion_tokens").asInt(0) : 0;
		}

		log.info("LLM token usage: {} input + {} output", inputTokens, outputTokens);
		return new InferenceResult(text, inputTokens, outputTokens);
	}

	private InferenceResult parseStreamingResponse(InputStream inputStream,
			Consumer<String> tokenConsumer) throws IOException {
		StringBuilder fullText = new StringBuilder();
		int inputTokens = 0;
		int outputTokens = 0;

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6).trim();
				if ("[DONE]".equals(data)) {
					break;
				}
				try {
					JsonNode chunk = MAPPER.readTree(data);
					JsonNode choices = chunk.get("choices");
					if (choices == null || !choices.isArray() || choices.isEmpty()) {
						continue;
					}
					JsonNode delta = choices.get(0).get("delta");
					if (delta != null && delta.has("content")) {
						String content = delta.get("content").asText("");
						if (!content.isEmpty()) {
							fullText.append(content);
							tokenConsumer.accept(content);
						}
					}
					JsonNode usage = chunk.get("usage");
					if (usage != null) {
						inputTokens = usage.has("prompt_tokens")
								? usage.get("prompt_tokens").asInt(0) : 0;
						outputTokens = usage.has("completion_tokens")
								? usage.get("completion_tokens").asInt(0) : 0;
					}
				}
				catch (IOException e) {
					log.debug("Skipping unparseable SSE chunk: {}", data);
				}
			}
		}

		if (inputTokens > 0 || outputTokens > 0) {
			log.info("LLM token usage: {} input + {} output", inputTokens, outputTokens);
		}
		return new InferenceResult(fullText.toString(), inputTokens, outputTokens);
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

	private String resolveServerBinaryPath() {
		return extractBundledServer();
	}

	private String extractBundledServer() {
		String platform = detectPlatform();
		String arch = detectArch();

		String binaryName = platform.equals("Windows") ? "llama-server.exe" : "llama-server";
		String resourceDir = "llama-server/" + platform + "/" + arch + "/";

		File appDataDir = new File(
				org.openmrs.util.OpenmrsUtil.getApplicationDataDirectory());
		File targetDir = new File(appDataDir, "chartsearchai/bin");
		File targetFile = new File(targetDir, binaryName);

		if (targetFile.isFile() && targetFile.canExecute()) {
			log.info("Using existing llama-server at {}", targetFile.getAbsolutePath());
			return targetFile.getAbsolutePath();
		}

		try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceDir + binaryName)) {
			if (is == null) {
				throw new IllegalStateException(
						"No bundled llama-server for " + platform + "/" + arch
								+ ". Place a compatible llama-server binary at "
								+ targetFile.getAbsolutePath());
			}

			targetDir.mkdirs();
			Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			targetFile.setExecutable(true);
			log.info("Extracted bundled llama-server to {}", targetFile.getAbsolutePath());
		}
		catch (IOException e) {
			throw new IllegalStateException(
					"Failed to extract bundled llama-server: " + e.getMessage(), e);
		}

		extractSharedLibraries(resourceDir, targetDir, platform);

		return targetFile.getAbsolutePath();
	}

	private void extractSharedLibraries(String resourceDir, File targetDir, String platform) {
		String libListResource = resourceDir + "libs.txt";
		try (InputStream libList = getClass().getClassLoader().getResourceAsStream(libListResource)) {
			if (libList == null) {
				log.warn("No libs.txt found at {}; shared libraries may be missing", libListResource);
				return;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(libList, StandardCharsets.UTF_8))) {
				String libName;
				while ((libName = reader.readLine()) != null) {
					libName = libName.trim();
					if (libName.isEmpty()) {
						continue;
					}
					try (InputStream libStream = getClass().getClassLoader()
							.getResourceAsStream(resourceDir + libName)) {
						if (libStream == null) {
							log.warn("Listed library {} not found in resources", libName);
							continue;
						}
						File libFile = new File(targetDir, libName);
						Files.copy(libStream, libFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
						if (!platform.equals("Windows")) {
							libFile.setExecutable(true);
						}
						log.debug("Extracted shared library {}", libName);
					}
				}
			}
		}
		catch (IOException e) {
			log.warn("Failed to extract shared libraries: {}", e.getMessage());
		}
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

	static String detectPlatform() {
		String osName = System.getProperty("os.name", "");
		if (osName.contains("Windows")) {
			return "Windows";
		}
		if (osName.contains("Mac") || osName.contains("Darwin")) {
			return "Mac";
		}
		return "Linux";
	}

	static String detectArch() {
		String osArch = System.getProperty("os.arch", "").toLowerCase();
		if (osArch.contains("aarch64") || osArch.contains("arm64")) {
			return "aarch64";
		}
		if (osArch.equals("x86") || osArch.equals("i386") || osArch.equals("i686")) {
			return "x86";
		}
		return "x86_64";
	}
}
