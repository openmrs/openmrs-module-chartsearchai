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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remote LLM engine that calls an OpenAI-compatible chat completions API.
 * Supports any server that implements the OpenAI API format, including
 * self-hosted inference servers (vLLM, Ollama, text-generation-inference)
 * and cloud providers (OpenAI, Azure OpenAI, Google AI, Anthropic).
 */
@Component("chartSearchAi.remoteLlmEngine")
public class RemoteLlmEngine implements LlmEngine {

	private static final Logger log = LoggerFactory.getLogger(RemoteLlmEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * What one output token is allowed to cost on the wire. An ALLOWANCE and not a measurement, and
	 * the two terms it covers are not alike. The answer itself is recorded: a completion at
	 * {@link ChartSearchAiConstants#DEFAULT_LLM_MAX_OUTPUT_TOKENS} is about 16 kB — the
	 * <em>What it costs</em> sections of ADR Decisions 76 and 78 each state that size against
	 * this same constant — i.e. about four bytes a token. The rest is
	 * server-sent-event framing — in the worst conformant case every token arrives as its own event
	 * carrying a whole chunk object and its {@code data: } prefix — and nothing in this repository
	 * measures that against a real provider, so it is set far above any shape one could take. Its
	 * only cost is a looser bound: the defect being closed is growth the peer controls, not growth
	 * of some particular size.
	 */
	static final int BYTE_ALLOWANCE_PER_OUTPUT_TOKEN = 1024;

	/**
	 * How many bytes one response from the remote peer may deliver before the read is abandoned —
	 * the output ceiling the request itself carries, priced at
	 * {@link #BYTE_ALLOWANCE_PER_OUTPUT_TOKEN}.
	 *
	 * <p>The endpoint is an untrusted peer (issue #446) and {@code max_tokens} is advisory to it,
	 * so this is the only thing that decides how much of the shared OpenMRS heap one answer can
	 * occupy.</p>
	 *
	 * <p><b>Two things it does not bound, named rather than left to be found.</b> Not TIME: a peer
	 * trickling less than this still holds the clinician's thread for as long as it likes, because
	 * {@code HttpRequest.timeout()} stops applying once the response headers arrive — see
	 * {@link LlmEngine#inferStreaming(String, String, int, Consumer)}. And not the TRANSIENT PEAK,
	 * which is a small multiple of it: this is what the peer may DELIVER, while decoding a body of
	 * that size holds the accumulated bytes and the decoded text at once. Measured 2026-09-17
	 * against a loopback {@code HttpServer}, as CUMULATIVE caller-thread allocation
	 * ({@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes}) over one call rather
	 * than as a live-heap peak: about 16.8 MB for a non-streaming response at this ceiling,
	 * against about 12.9 MB for the unbounded {@code ofString()} it replaced, and 21 to 29 MB
	 * streaming depending on whether the peer sends one endless line or many chunks. All
	 * bounded, none equal to the ceiling, and it is the MULTIPLE that is fixed here: before
	 * this, the figure was the peer's to choose.</p>
	 */
	static final long MAX_RESPONSE_BYTES = (long) ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS
			* BYTE_ALLOWANCE_PER_OUTPUT_TOKEN;

	/**
	 * How much of a non-2xx body is read. Far tighter than {@link #MAX_RESPONSE_BYTES} because an
	 * error body is never parsed — its one destination is {@link #logErrorBody}'s DEBUG line, cut
	 * down again on the way — and because this read TRUNCATES where the others abort. Where a
	 * body is bigger than that cut, most of what this reads is discarded; that is slack rather
	 * than oversight, since it is a bound on a hostile peer and not a budget for a line. The
	 * common case, a short error body, loses nothing. Abandoning
	 * an oversized error body with a size complaint would cost the status code and the
	 * {@code chartsearchai.llm.remote.*} hint that go with it, which is the operator's only clue
	 * that the endpoint URL or model name is wrong.
	 */
	static final int MAX_ERROR_BODY_BYTES = 8192;

	private HttpClient httpClient;

	@Override
	public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
		return infer(systemPrompt, userMessage, timeoutSeconds, null);
	}

	@Override
	public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
			ObjectNode responseFormat) {
		String endpointUrl = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		String modelName = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME);

		String requestBody = buildRequestBody(systemPrompt, userMessage, modelName, false, responseFormat);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(endpointUrl))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
		if (apiKey != null) {
			requestBuilder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = requestBuilder.build();

		try {
			HttpResponse<InputStream> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				logErrorBody(response.statusCode(), readTruncatedErrorBody(response.body()));
				throw new APIException("Remote LLM API returned HTTP " + response.statusCode()
						+ ". Check the endpoint URL and model name in the "
						+ "chartsearchai.llm.remote.* global properties, and the API key "
						+ "in openmrs-runtime.properties.");
			}

			return parseResponse(readBoundedBody(response.body()));
		}
		catch (BoundedResponseStream.ResponseTooLargeException e) {
			throw oversized(e);
		}
		catch (IOException e) {
			throw new APIException("Failed to call remote LLM API: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Remote LLM API call was interrupted", e);
		}
	}

	@Override
	public InferenceResult inferStreaming(String systemPrompt, String userMessage,
			int timeoutSeconds, Consumer<String> tokenConsumer) {
		String endpointUrl = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL);
		String apiKey = getOptionalRuntimeProperty(ChartSearchAiConstants.RP_LLM_REMOTE_API_KEY);
		String modelName = getRequiredGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME);

		String requestBody = buildRequestBody(systemPrompt, userMessage, modelName, true);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(endpointUrl))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
		if (apiKey != null) {
			requestBuilder.header("Authorization", "Bearer " + apiKey);
		}
		HttpRequest request = requestBuilder.build();

		try {
			HttpResponse<InputStream> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				logErrorBody(response.statusCode(), readTruncatedErrorBody(response.body()));
				throw new APIException("Remote LLM API returned HTTP " + response.statusCode());
			}

			return parseStreamingResponse(response.body(), tokenConsumer);
		}
		catch (BoundedResponseStream.ResponseTooLargeException e) {
			throw oversized(e);
		}
		catch (IOException e) {
			throw new APIException("Failed to call remote LLM API: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Remote LLM API call was interrupted", e);
		}
	}

	@Override
	public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		// Remote OpenAI-compatible APIs typically manage their own caching. Issuing
		// a no-op call here would just incur cost. Local-engine warmup is what closes
		// the gap; remote engines fall back to whatever caching the provider offers.
	}

	@Override
	public boolean supportsWarmup() {
		return false;
	}

	@Override
	public synchronized void close() {
		httpClient = null;
	}

	@Override
	public void shutdown() {
		close();
	}

	String buildRequestBody(String systemPrompt, String userMessage, String modelName,
			boolean stream) {
		return buildRequestBody(systemPrompt, userMessage, modelName, stream, null);
	}

	/**
	 * As {@link #buildRequestBody(String, String, String, boolean)} but with a caller-supplied
	 * {@code response_format} (e.g. the verdict-only batch-grounding schema). A {@code null}
	 * {@code responseFormat} falls back to the default chart-answer schema.
	 */
	String buildRequestBody(String systemPrompt, String userMessage, String modelName,
			boolean stream, ObjectNode responseFormat) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", modelName);
		// Anthropic's compat endpoint rejects temperature/top_p on Opus 4.7; top_k=1 is the only greedy-decoding lever it still accepts.
		if (modelName.startsWith("claude-opus-4-7")) {
			root.put("top_k", 1);
		} else {
			root.put("temperature", 0.0);
		}
		root.put("max_tokens", ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS);
		root.put("stream", stream);
		if (stream) {
			ObjectNode streamOptions = MAPPER.createObjectNode();
			streamOptions.put("include_usage", true);
			root.set("stream_options", streamOptions);
		}

		root.set("response_format",
				responseFormat != null ? responseFormat
						: ChartAnswerResponseFormat.build(MAPPER, resolveReasoningMaxChars()));
		root.set("messages", ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage));

		try {
			return MAPPER.writeValueAsString(root);
		}
		catch (IOException e) {
			throw new APIException("Failed to build request body", e);
		}
	}

	InferenceResult parseResponse(String responseBody) throws IOException {
		return LlmResponseParser.parseResponse(responseBody, log);
	}

	/** Test seam wrapping {@link ChartSearchAiUtils#getReasoningMaxChars()} (fail-safe 0). */
	int resolveReasoningMaxChars() {
		return ChartSearchAiUtils.getReasoningMaxChars();
	}


	InferenceResult parseStreamingResponse(InputStream inputStream,
			Consumer<String> tokenConsumer) throws IOException {
		return LlmResponseParser.parseStreamingResponse(bounded(inputStream), tokenConsumer, log);
	}

	/**
	 * The one place {@link #MAX_RESPONSE_BYTES} is applied. Both reads that may ABORT go through
	 * it — {@link #parseStreamingResponse} and {@link #readBoundedBody}. It is not every read of
	 * a response body: {@link #readTruncatedErrorBody} reads a non-2xx body under its own,
	 * tighter ceiling and never through this.
	 *
	 * <p><b>Nothing structural stops a future caller going round it</b>, and a review measured
	 * two ways: {@code LlmResponseParser.parseStreamingResponse} is a real method of the same
	 * bare name that does not wrap, and a ceiling left in place with an unusable limit looks
	 * identical to any source check. What catches both is
	 * {@code RemoteLlmEngineResponseSizeBoundTest}, which drives every entry point against a
	 * real hostile peer; {@code ArchitectureGuardTest.noProductionSourceBuffersAWholeRemoteResponse}
	 * covers only the spelling half.</p>
	 */
	private static BoundedResponseStream bounded(InputStream body) {
		return new BoundedResponseStream(body, MAX_RESPONSE_BYTES);
	}

	/**
	 * The whole body as text, or {@link BoundedResponseStream.ResponseTooLargeException} if the peer
	 * sent more than {@link #MAX_RESPONSE_BYTES}. Aborts rather than truncating, unlike
	 * {@link #readTruncatedErrorBody}: half a completion envelope is not a completion, and a caller
	 * handed one would report a parse failure for what is really an oversized response.
	 */
	String readBoundedBody(InputStream body) throws IOException {
		try (InputStream ceilinged = bounded(body)) {
			return new String(ceilinged.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * At most {@link #MAX_ERROR_BODY_BYTES} of a non-2xx body, abandoning the rest unread —
	 * truncating rather than raising, for the reason that constant states. The cut is at a byte
	 * and not a character boundary, which costs at worst one replacement character: the result
	 * only ever reaches a log line, never a parser.
	 */
	private static String readTruncatedErrorBody(InputStream body) {
		try (InputStream in = body) {
			// The three-arg form fills a buffer this method sizes. The one-arg readNBytes(int)
			// would allocate its own and then copy out whatever the body actually was, which for
			// a short error body — the common case — is a second allocation for nothing.
			byte[] buffer = new byte[MAX_ERROR_BODY_BYTES];
			return new String(buffer, 0, in.readNBytes(buffer, 0, buffer.length),
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			log.debug("Could not read the error body from the remote LLM API", e);
			return "";
		}
	}

	/**
	 * The status code at ERROR and the endpoint's own text at DEBUG.
	 *
	 * <p>Split because the two have different audiences. The status code is what an operator
	 * needs and carries nothing of the patient's. The BODY is bytes the endpoint chose, and a
	 * compromised or merely verbose one can echo the prompt — which is this patient's chart —
	 * straight back as an error message: measured against a loopback peer, name, date of birth,
	 * diagnosis, allergy and three drug names all fit inside what {@code truncateForLog} keeps.
	 * Core ships {@code org.openmrs} at WARN, so ERROR would put that in the default server log
	 * and whatever ships it onward, with nobody opting in.</p>
	 *
	 * <p><b>ADR Decision 102 declined DEBUG for the safety-finding shortfall (#439), and this is
	 * the distinction rather than a departure.</b> It declined a SECOND channel — Decision 100
	 * already appends every unstated order to the answer, so "a channel nobody needs is not worth
	 * the bytes of PHI it writes". Here the BODY has no first channel. The status code reaches
	 * the operator twice over — the ERROR line below, and the controller logging the
	 * {@code APIException} — but what the endpoint actually SAID reaches nothing else: both
	 * routes replace that message with a generic failure string before it leaves the server. The
	 * choice is not "answer or log" but "log at DEBUG or lose it", and the level is what keeps
	 * it out of the default one.</p>
	 */
	private static void logErrorBody(int statusCode, String body) {
		log.error("Remote LLM API returned HTTP {}; enable DEBUG on {} to see the body it sent",
				statusCode, RemoteLlmEngine.class.getName());
		log.debug("Remote LLM API HTTP {} body: {}", statusCode, truncateForLog(body));
	}

	/**
	 * What an operator is told when the peer sent more than one answer can be.
	 *
	 * <p><b>The cause is logged here and deliberately NOT attached.</b>
	 * {@link BoundedResponseStream.ResponseTooLargeException} is an {@link IOException}, and the
	 * streaming route's terminal handler reads {@code getCause() instanceof IOException} as "the
	 * client hung up" — it then logs at DEBUG and sends no {@code error} event, so an
	 * {@code APIException} carrying this cause would reach a clinician as a stream that simply
	 * stopped. The ERROR line below reaches the operator either way; what detaching the cause
	 * buys is the {@code error} event AND the controller's own ERROR line naming the patient,
	 * both of which the DEBUG branch replaces with one line saying the client hung up — which is
	 * not what happened. Attaching it was measured doing exactly that.
	 * → {@code ChartSearchAiRestController.streamAnswer}.</p>
	 *
	 * <p><b>That heuristic is itself wrong, and this only steps around it.</b> Every other
	 * {@code IOException} the engines wrap is misread the same way — a peer that hangs up
	 * mid-answer is reported as the CLIENT disconnecting — which is older than this ceiling and
	 * wider than it. Fixing it means giving the controller's own disconnect throw a type to test
	 * for, rather than testing for a cause any transport failure can carry; that is a change to
	 * the streaming route and is issue #451, where it is measured.</p>
	 */
	private static APIException oversized(BoundedResponseStream.ResponseTooLargeException e) {
		log.error("Remote LLM API exceeded the {}-byte response ceiling", e.getLimit(), e);
		return new APIException("Remote LLM API sent more than " + e.getLimit()
				+ " bytes, and the response was abandoned rather than read into memory. The "
				+ "endpoint configured in " + ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL
				+ " sent more than a completion of "
				+ ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS
				+ " output tokens is expected to need. Check that it is the endpoint intended and "
				+ "that it speaks the OpenAI chat-completions format; the ceiling itself is fixed "
				+ "and not configurable.");
	}

	private String getRequiredGlobalProperty(String propertyName) {
		String value = Context.getAdministrationService().getGlobalProperty(propertyName);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalStateException(
					"Required global property not configured: " + propertyName);
		}
		return value.trim();
	}

	private String getOptionalRuntimeProperty(String propertyName) {
		Properties props = Context.getRuntimeProperties();
		String value = props != null ? props.getProperty(propertyName) : null;
		return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
	}

	private synchronized HttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.version(HttpClient.Version.HTTP_1_1)
					.connectTimeout(Duration.ofSeconds(30))
					.build();
		}
		return httpClient;
	}

	private static String truncateForLog(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
