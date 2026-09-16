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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #446: the endpoint {@code chartsearchai.llm.remote.endpointUrl} names is an untrusted
 * network peer — a third-party provider, a self-hosted vLLM/Ollama, or anyone in a
 * machine-in-the-middle position on the plaintext {@code http://} endpoints the README's examples
 * use. {@link RemoteLlmEngine} read its answers with no ceiling on how many of them there were, so
 * one clinician query could be answered with a body or an SSE stream of the peer's chosen size and
 * drive the shared OpenMRS Tomcat heap into {@code OutOfMemoryError}. The request timeout is no
 * defence: {@code LlmEngine.inferStreaming} records that {@code HttpRequest.timeout()} stops
 * applying once the peer's response headers arrive.
 *
 * <p><b>The peer here is a real one.</b> Each case binds a {@link HttpServer} to an ephemeral
 * loopback port, points the two {@code chartsearchai.llm.remote.*} global properties at it and
 * calls {@code RemoteLlmEngine.infer} / {@code RemoteLlmEngine.inferStreaming} — so the JDK
 * {@code HttpClient}, the global-property reads, the request body the engine builds and
 * {@code LlmResponseParser} are all production's. Only the peer is the test's, which is the
 * variable the measurement is about.
 *
 * <p><b>The load-bearing assertion is the byte count, not the exception.</b> A thrown
 * {@code APIException} says only that the call ended; the bytes the server managed to write before
 * the client stopped reading are what says the heap was bounded, and an oversized body can raise an
 * exception for the wrong reason (a truncated JSON body fails to parse). So every case asserts the
 * peer's write total first. The handlers stop themselves at {@link #SAFETY_LIMIT}, well above the
 * ceiling, so an absent bound fails the assertion rather than running until the JVM dies.
 *
 * <p>The composed {@code LlmInferenceService.search} path is deliberately not used: every existing
 * suite that drives it stubs the model out at {@code LlmProvider} — see
 * {@link SafetyFindingCitationExtentTest}'s class javadoc — which is exactly the layer this
 * ceiling lives in, so routing through it would replace the code under test with a stub.
 */
public class RemoteLlmEngineResponseSizeBoundTest extends BaseModuleContextSensitiveTest {

	/**
	 * Where a handler gives up. Four times {@link RemoteLlmEngine#MAX_RESPONSE_BYTES}, so an
	 * unbounded read is separated from a bounded one by a factor a socket buffer cannot explain,
	 * and small enough that the pre-fix run's accumulated {@code StringBuilder} does not itself
	 * exhaust the test JVM.
	 */
	private static final long SAFETY_LIMIT = 4L * RemoteLlmEngine.MAX_RESPONSE_BYTES;

	/**
	 * What the peer may still get onto the wire after the module stops reading its ERROR body at
	 * {@link RemoteLlmEngine#MAX_ERROR_BODY_BYTES}. Nothing like that ceiling, and the gap is the
	 * instrument's rather than the module's: the JDK's {@code HttpServer} and macOS loopback
	 * auto-tuning together absorbed ~0.7 MB before the server's write saw the broken pipe, far
	 * past the socket slack {@link #TOLERATED} describes. So this measures what a socket can
	 * swallow and not what the module read — the module read 8192 — and what it discriminates is
	 * a ceiling raised to megabytes, which is the mutation that matters.
	 */
	private static final long ERROR_BODY_BUDGET = 2L * 1024 * 1024;

	/**
	 * What the peer may still have got onto the wire after the module stopped reading: twice the
	 * ceiling, i.e. {@link RemoteLlmEngine#MAX_RESPONSE_BYTES} of slack above it. Far more than a
	 * socket needs — {@code net.inet.tcp.sendspace} and {@code recvspace} are 131072 each on this
	 * platform — and deliberately so: the assertion is "something stopped the read", not a
	 * measurement of how much the kernel buffers. An unbounded read reaches
	 * {@link #SAFETY_LIMIT}, twice this again.
	 */
	private static final long TOLERATED = 2L * RemoteLlmEngine.MAX_RESPONSE_BYTES;

	/** ASCII only, so the envelope's byte length is its character length. */
	private static final String CEILING_PREFIX = "{\"choices\":[{\"message\":{\"content\":\"";

	private static final String CEILING_SUFFIX = "\"}}]}";

	private HttpServer server;

	private final AtomicLong written = new AtomicLong();

	private final RemoteLlmEngine engine = new RemoteLlmEngine();

	@BeforeEach
	public void startPeer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/flood-stream", this::floodStream);
		server.createContext("/flood-body", exchange -> floodBody(exchange, 200));
		server.createContext("/flood-error", exchange -> floodBody(exchange, 500));
		server.createContext("/ordinary-body", this::ordinaryBody);
		server.createContext("/ordinary-stream", this::ordinaryStream);
		server.createContext("/exactly-at-the-ceiling", this::exactlyAtTheCeiling);
		server.start();
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME, "test-model");
	}

	@AfterEach
	public void stopPeer() {
		engine.close();
		server.stop(0);
	}

	@Test
	public void anEndlessTokenStreamIsAbortedInsteadOfAccumulatedWithoutABound() {
		pointEngineAt("/flood-stream");

		APIException raised = callAndCatch(() -> engine.inferStreaming("system", "user", 60,
				token -> { }));

		assertWroteNoMoreThanTheCeiling("the streamed answer");
		assertNotNull(raised, "a peer that never stops streaming must end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	@Test
	public void anOversizedNonStreamingBodyIsAbortedInsteadOfBufferedWhole() {
		pointEngineAt("/flood-body");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

		assertWroteNoMoreThanTheCeiling("the non-streaming body");
		assertNotNull(raised,
				"a peer answering with an oversized body must end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	@Test
	public void anOversizedErrorBodyIsTruncatedAndStillReportsItsStatusCode() {
		pointEngineAt("/flood-error");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

		assertPeerWasCutOffAt("the error body", ERROR_BODY_BUDGET,
				RemoteLlmEngine.MAX_ERROR_BODY_BYTES);
		assertNotNull(raised, "a 500 from the endpoint is still a failed call");
		assertTrue(raised.getMessage() != null && raised.getMessage().contains("500"),
				"the status code is the operator's only clue that the endpoint URL or model name "
						+ "is misconfigured, and reading the error body under a ceiling must not "
						+ "cost it. Got: " + raised.getMessage());
	}

	/**
	 * The positive control for the two aborting reads. A ceiling nothing can reach would satisfy
	 * every case above — they only ever assert that a flood STOPPED — so an ordinary completion has
	 * to come back whole and parsed, through the same rewritten read.
	 */
	@Test
	public void anOrdinaryCompletionIsStillReadAndParsedWhole() {
		pointEngineAt("/ordinary-body");

		LlmEngine.InferenceResult result = engine.infer("system", "user", 60);

		assertEquals("the whole answer", result.getText(),
				"the non-streaming read now goes through the ceiling, and must still deliver "
						+ "every byte of a response that stays under it");
		assertEquals(11, result.getInputTokens());
		assertEquals(22, result.getOutputTokens());
	}

	/** The same control for the streaming read, whose parser is now handed a bounded stream. */
	@Test
	public void anOrdinaryTokenStreamIsStillAssembledAndDelivered() {
		pointEngineAt("/ordinary-stream");
		List<String> delivered = new ArrayList<String>();

		LlmEngine.InferenceResult result = engine.inferStreaming("system", "user", 60,
				delivered::add);

		assertEquals("one two three", result.getText());
		assertEquals(Arrays.asList("one", " two", " three"), delivered,
				"the consumer must still see the answer arrive in PIECES. The assembled text "
						+ "cannot say this: the parser appends to it and calls the consumer with "
						+ "the same value in the same iteration, so only the deliveries can tell "
						+ "three chunks from one lump.");
	}

	/**
	 * The boundary the ceiling is written on: a body of EXACTLY the ceiling is a body that fits, so
	 * it must arrive whole. Off by one here and the largest legitimate answer is the one that fails.
	 */
	@Test
	public void aBodyOfExactlyTheCeilingArrivesWholeRatherThanCutOff() {
		pointEngineAt("/exactly-at-the-ceiling");

		LlmEngine.InferenceResult result = engine.infer("system", "user", 60);

		assertEquals(RemoteLlmEngine.MAX_RESPONSE_BYTES, written.get(),
				"the fixture has to sit ON the boundary for this case to be about the boundary");
		assertEquals(ceilingPadding(), result.getText().length(),
				"a response of exactly the ceiling is within it and must not be abandoned");
	}

	/**
	 * Runs the call and hands back the {@link APIException} it raised, or {@code null}. Deliberately
	 * not {@code assertThrows}: the byte assertion is the one that says the heap was bounded, and it
	 * has to be reached even on the run where no exception was raised at all — which is precisely
	 * the unbounded case.
	 */
	private APIException callAndCatch(Runnable call) {
		try {
			call.run();
			return null;
		}
		catch (APIException e) {
			return e;
		}
	}

	/**
	 * The failure has to be one the module can REPORT, and neither half of that is implied by an
	 * exception merely being raised. The message has to name the ceiling, or it is any other
	 * transport failure. And the cause must not be an {@link IOException}, for the reason
	 * {@code RemoteLlmEngine.oversized}'s javadoc gives and measured.
	 */
	private static void assertCeilingFailureIsReportable(APIException raised) {
		assertNotNull(raised.getMessage(), "the failure must say something an operator can act on");
		assertTrue(raised.getMessage().contains(String.valueOf(RemoteLlmEngine.MAX_RESPONSE_BYTES)),
				"the message must name the ceiling that was exceeded. Got: "
						+ raised.getMessage());
		assertFalse(raised.getCause() instanceof IOException,
				"an IOException cause makes ChartSearchAiRestController.streamAnswer classify this "
						+ "as a client disconnect: no error event reaches the client and nothing "
						+ "is logged. Got cause: " + raised.getCause());
	}

	private void pointEngineAt(String path) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
				"http://" + server.getAddress().getHostString() + ":"
						+ server.getAddress().getPort() + path);
	}

	private void assertWroteNoMoreThanTheCeiling(String what) {
		assertPeerWasCutOffAt(what, TOLERATED, RemoteLlmEngine.MAX_RESPONSE_BYTES);
	}

	/**
	 * The peer stopped being listened to somewhere under {@code budget}. Stated as what the PEER
	 * got onto the wire rather than as what the module read, because the two differ by everything
	 * the socket absorbed after the module stopped reading — so every caller sets {@code budget}
	 * well above {@code ceiling}, by a margin of its own. {@link #TOLERATED} and
	 * {@link #ERROR_BODY_BUDGET} each say what theirs is and why.
	 */
	private void assertPeerWasCutOffAt(String what, long budget, long ceiling) {
		assertTrue(written.get() <= budget,
				what + ": the peer got " + written.get() + " bytes onto the wire, so nothing "
						+ "stopped reading them. A response may not grow the shared JVM's heap "
						+ "past " + ceiling + " bytes no matter how much the endpoint sends, and "
						+ "past " + budget + " the overshoot is more than a socket buffer can "
						+ "explain.");
	}

	/** A well-formed completion, comfortably under the ceiling. */
	private void ordinaryBody(HttpExchange exchange) throws IOException {
		respondOnce(exchange, 200, "application/json",
				("{\"choices\":[{\"message\":{\"content\":\"the whole answer\"}}],"
						+ "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}")
						.getBytes(StandardCharsets.UTF_8));
	}

	/** A well-formed SSE stream that ends the way a conformant peer ends one. */
	private void ordinaryStream(HttpExchange exchange) throws IOException {
		respondOnce(exchange, 200, "text/event-stream",
				("data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\n"
						+ "data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}\n\n"
						+ "data: {\"choices\":[{\"delta\":{\"content\":\" three\"}}]}\n\n"
						+ "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
	}

	/** A well-formed completion whose body is exactly {@link RemoteLlmEngine#MAX_RESPONSE_BYTES}. */
	private void exactlyAtTheCeiling(HttpExchange exchange) throws IOException {
		respondOnce(exchange, 200, "application/json",
				(CEILING_PREFIX + "z".repeat(ceilingPadding()) + CEILING_SUFFIX)
						.getBytes(StandardCharsets.UTF_8));
	}

	/** How much filler makes {@link #CEILING_PREFIX} + filler + {@link #CEILING_SUFFIX} the ceiling. */
	private static int ceilingPadding() {
		return (int) RemoteLlmEngine.MAX_RESPONSE_BYTES - CEILING_PREFIX.length()
				- CEILING_SUFFIX.length();
	}

	/** An SSE stream that never reaches {@code [DONE]} — one content chunk after another. */
	private void floodStream(HttpExchange exchange) throws IOException {
		byte[] chunk = ("data: {\"choices\":[{\"delta\":{\"content\":\""
				+ "x".repeat(1024) + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
		respond(exchange, 200, "text/event-stream", chunk);
	}

	/** A single JSON body that never ends — a plausible completion envelope, then filler. */
	private void floodBody(HttpExchange exchange, int status) throws IOException {
		byte[] opening = "{\"choices\":[{\"message\":{\"content\":\""
				.getBytes(StandardCharsets.UTF_8);
		byte[] filler = "y".repeat(4096).getBytes(StandardCharsets.UTF_8);
		respond(exchange, status, "application/json", opening, filler);
	}

	/**
	 * Answers with {@code status}, then writes every element of {@code parts} but the last once
	 * each and repeats the last until the client stops reading or {@link #SAFETY_LIMIT} is
	 * reached, counting every byte that left.
	 */
	private void respond(HttpExchange exchange, int status, String contentType, byte[]... parts)
			throws IOException {
		drainRequest(exchange);
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			for (int i = 0; i < parts.length - 1; i++) {
				out.write(parts[i]);
				written.addAndGet(parts[i].length);
			}
			byte[] repeated = parts[parts.length - 1];
			while (written.get() < SAFETY_LIMIT) {
				out.write(repeated);
				out.flush();
				written.addAndGet(repeated.length);
			}
		}
		catch (IOException e) {
			// The client stopped reading — which is the whole point of the cases above.
		}
	}

	/** Answers with {@code status} and exactly {@code body}, counting what left. */
	private void respondOnce(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		drainRequest(exchange);
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
			written.addAndGet(body.length);
		}
	}

	/** The engine POSTs a prompt; read it so the client's write completes. */
	private static void drainRequest(HttpExchange exchange) throws IOException {
		try (InputStream request = exchange.getRequestBody()) {
			while (request.read() >= 0) {
				continue;
			}
		}
	}
}
