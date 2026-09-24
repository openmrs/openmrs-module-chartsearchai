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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
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
 * <p><b>The load-bearing assertion is that the peer was CUT OFF, not that an exception was
 * raised.</b> A thrown {@code APIException} says only that the call ended, and an oversized body
 * can raise one for the wrong reason (a truncated JSON body fails to parse). Every handler that
 * repeats until the client stops reading stops itself at {@link #SAFETY_LIMIT} — so a peer being
 * read to the end reaches that limit and records it, while a peer the module stopped reading is
 * cut off short of it. That flag, read once the handler thread has stopped writing, is what every
 * such FLOOD case asserts, together with a floor: the peer must have got onto the wire at least the
 * bytes the module reads before it stops, so a handler that failed at the start cannot pass for
 * one that was cut off. Of the positive controls only the ones that sit ON a boundary assert a
 * write total, and they assert EQUALITY, because the fixture has to sit on the boundary for the
 * case to be about it; the rest assert content.
 *
 * <p><b>What that does and does not discriminate.</b> It separates a read that stops from one that
 * does not, and the two verdicts are {@link #SAFETY_LIMIT} minus the ceiling apart — megabytes, on
 * any platform, which is the whole point of stating it this way. It does NOT say WHERE the ceiling
 * is: a peer is cut off at the ceiling plus whatever the kernel absorbed, which is how a peer
 * under a raised ceiling can still stop short of {@link #SAFETY_LIMIT} — that constant's javadoc
 * has the measured case — and raising {@link RemoteLlmEngine#MAX_ERROR_BODY_BYTES} to megabytes
 * leaves the peer cut off just the same — measured green at 2 MiB. What pins the response
 * ceiling's VALUE, from both sides, is the pair of fixed-size cases {@link #STATED_RESPONSE_BUDGET}
 * names (issue #454). {@link #aBodyOfExactlyTheCeilingArrivesWholeRatherThanCutOff} pins its
 * BOUNDARY — that a body of exactly the ceiling fits — relative to the constant.
 * {@link #anOrdinaryErrorBodyReachesTheLogWhole} pins the error ceiling from below: one shorter
 * than {@link #SHORT_ERROR_BODY} cuts the body that case asserts arrives whole. Nothing here pins
 * the error ceiling from above, and the byte budget this replaced did not reliably do it either:
 * calibrated against macOS loopback, where the peer got 0.7 to 0.8 MB onto the wire past the
 * 8192-byte error ceiling and 0.8 to 1.9 MB past the 4 MiB response one (measured 2026-09-17,
 * after the handler stopped), it was red on GitHub's Linux runners, which got 2.7 MB past that
 * same error ceiling with the module working (issue #446, run 35173250728). A budget on the peer's
 * wire total measures the kernel the suite happens to run on; this measures the handler.
 *
 * <p><b>Two of the streaming cases are about WHERE the ceiling is counted</b>, not merely
 * that there is one: a peer whose first line never ends
 * ({@link #oneEndlessLineIsCutOffEvenThoughTheParserNeverSeesAChunk}) and a peer whose
 * endless lines the parser discards unread
 * ({@link #endlessLinesCarryingNoContentAtAllAreStillCountedAgainstTheCeiling}) are bounded
 * only if the count is on the STREAM; both pass a ceiling on the parser's accumulated text
 * that is no bound at all. That is the ticket's "per line, per chunk and cumulative" asked
 * of the peer rather than of the counters. Each case's own javadoc says what it pins.
 *
 * <p>The composed {@code LlmInferenceService.search} path is deliberately not used: every existing
 * suite that drives it stubs the model out at {@code LlmProvider} — see
 * {@link SafetyFindingCitationExtentTest}'s class javadoc — which is exactly the layer this
 * ceiling lives in, so routing through it would replace the code under test with a stub.
 */
public class RemoteLlmEngineResponseSizeBoundTest extends BaseModuleContextSensitiveTest {

	/**
	 * The most one response may deliver, stated as a literal and deliberately NOT derived from
	 * {@link RemoteLlmEngine#MAX_RESPONSE_BYTES} (issue #454): it is the number deciding how much
	 * of the shared OpenMRS heap an untrusted endpoint may occupy per query, and a figure computed
	 * from the constant under test moves with it.
	 *
	 * <p>Two cases pin the ceiling to it, one from each side:
	 * {@link #aCompletionOfExactlyTheStatedBudgetArrivesWhole} reddens a ceiling lowered below it
	 * and {@link #aCompletionOneByteOverTheStatedBudgetIsRefused} one raised past it — whether
	 * through {@code BYTE_ALLOWANCE_PER_OUTPUT_TOKEN} or through
	 * {@link ChartSearchAiConstants#DEFAULT_LLM_MAX_OUTPUT_TOKENS} — so that moving it is a
	 * decision made here rather than one that ships unseen. Each sends a well-formed completion of
	 * fixed length, once, so the verdict is whether the module read it whole, never how much of it
	 * the kernel absorbed. Why the flood cases cannot say this is {@link #SAFETY_LIMIT}'s javadoc.</p>
	 */
	private static final long STATED_RESPONSE_BUDGET = 4L * 1024 * 1024;

	/**
	 * Where a handler gives up — and, since {@link #assertPeerWasCutOff} asks whether the handler
	 * REACHED it, the distance by which an unbounded read is separated from a bounded one. Four
	 * times {@link #STATED_RESPONSE_BUDGET}, so that distance is megabytes rather than anything a
	 * socket buffer could account for, and small enough that the pre-fix run's accumulated
	 * {@code StringBuilder} does not itself exhaust the test JVM. A handler that reaches this wrote
	 * every byte it was ever going to; one the module stopped reading did not.
	 *
	 * <p>Absolute rather than a multiple of {@link RemoteLlmEngine#MAX_RESPONSE_BYTES}, so raising
	 * that constant no longer moves this with it — but this is not what pins the ceiling's value.
	 * A flood peer is cut off at the ceiling plus whatever the kernel absorbed, which stays under
	 * this for a ceiling raised to 12 MiB: that mutation left every flood case green with this
	 * limit in place (measured 2026-09-24, macOS loopback). {@link #STATED_RESPONSE_BUDGET} names
	 * what reddens it.</p>
	 */
	private static final long SAFETY_LIMIT = 4L * STATED_RESPONSE_BUDGET;

	/**
	 * How long a cut-off handler is given to notice. It notices on its next write — the module has
	 * closed the body, and closing it cancels the exchange — so this is a hang detector and not a
	 * tuned wait: a handler still writing after this was not cut off at all, which is the failure
	 * the flood cases are looking for and must be reported rather than waited out.
	 */
	private static final int PEER_EXIT_SECONDS = 30;

	/** Distinctive enough that finding it in the log cannot be an accident. */
	private static final String SHORT_ERROR_BODY =
			"{\"error\":{\"message\":\"no such model: chartsearchai-446-probe\"}}";

	/** ASCII only, so the envelope's byte length is its character length. */
	private static final String COMPLETION_PREFIX = "{\"choices\":[{\"message\":{\"content\":\"";

	private static final String COMPLETION_SUFFIX = "\"}}]}";

	/**
	 * The opening of one SSE content chunk, shared so that the endless-line peer below is
	 * visibly {@link #floodStream}'s chunk minus the newline that ends it.
	 */
	private static final String SSE_CONTENT_PREFIX =
			"data: {\"choices\":[{\"delta\":{\"content\":\"";

	private HttpServer server;

	private final AtomicLong written = new AtomicLong();

	/**
	 * Set by a repeating handler that ran out of {@link #SAFETY_LIMIT} instead of being cut off —
	 * i.e. by a peer nothing stopped reading. The verdict of every flood case is this flag and
	 * not a byte count, because a byte count of the peer's wire total is a measurement of the
	 * kernel that ran the test. Never set by {@link #respondOnce}, which writes a fixed body.
	 */
	private final AtomicBoolean peerReachedItsSafetyLimit = new AtomicBoolean();

	/**
	 * Counted down when the handler thread stops writing, however it stopped. Read {@link #written}
	 * or {@link #peerReachedItsSafetyLimit} only after awaiting this: the call under test unwinds
	 * as soon as the module stops reading, which is BEFORE the peer has finished filling the socket
	 * it was writing into, and a sample taken then is a race that errs toward passing.
	 */
	private final CountDownLatch peerFinished = new CountDownLatch(1);

	private final RemoteLlmEngine engine = new RemoteLlmEngine();

	@BeforeEach
	public void startPeer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/flood-stream", this::floodStream);
		server.createContext("/endless-line", this::endlessLine);
		server.createContext("/endless-short-lines", this::endlessShortLines);
		server.createContext("/flood-body", exchange -> floodBody(exchange, 200));
		server.createContext("/flood-error", exchange -> floodBody(exchange, 500));
		server.createContext("/ordinary-body", this::ordinaryBody);
		server.createContext("/ordinary-stream", this::ordinaryStream);
		server.createContext("/exactly-at-the-ceiling",
				exchange -> respondWithCompletionOf(exchange, RemoteLlmEngine.MAX_RESPONSE_BYTES));
		server.createContext("/exactly-the-budget",
				exchange -> respondWithCompletionOf(exchange, STATED_RESPONSE_BUDGET));
		server.createContext("/one-byte-over-the-budget",
				exchange -> respondWithCompletionOf(exchange, STATED_RESPONSE_BUDGET + 1));
		server.createContext("/short-error", this::shortError);
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

		assertPeerWasCutOffAtTheResponseCeiling("the streamed answer");
		assertNotNull(raised, "a peer that never stops streaming must end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	/**
	 * The shape the ticket names for its per-LINE counter: one {@code data:} line that never
	 * ends. {@code /flood-stream} cannot stand in for it, because every chunk that peer sends is
	 * newline-terminated and parses — so a ceiling expressed on the text the PARSER has
	 * accumulated passes that case while leaving this one unbounded, and here nothing downstream
	 * of {@code BufferedReader.readLine()} sees a byte until the line ends, which it never does.
	 * What this pins is that the count is on the STREAM: the peer is cut off whether or not a
	 * line ever completes.
	 */
	@Test
	public void oneEndlessLineIsCutOffEvenThoughTheParserNeverSeesAChunk() {
		pointEngineAt("/endless-line");

		APIException raised = callAndCatch(() -> engine.inferStreaming("system", "user", 60,
				token -> { }));

		assertPeerWasCutOffAtTheResponseCeiling("the endless line");
		assertNotNull(raised, "a peer whose first line never ends must end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	/**
	 * The same invariant approached from the other side: endless SHORT lines, none of them a
	 * {@code data:} line. The parser discards every one, so nothing it accumulates grows at all
	 * — a ceiling on the assembled answer never counts a byte of this peer — while
	 * {@code /flood-stream}, whose every line carries content, counts the same under either
	 * ceiling and so cannot tell them apart. Counting on the stream counts these lines like any
	 * others, which is what {@code BoundedResponseStream}'s class javadoc means by one endless
	 * line and endless short ones being the same peer.
	 */
	@Test
	public void endlessLinesCarryingNoContentAtAllAreStillCountedAgainstTheCeiling() {
		pointEngineAt("/endless-short-lines");

		APIException raised = callAndCatch(() -> engine.inferStreaming("system", "user", 60,
				token -> { }));

		assertPeerWasCutOffAtTheResponseCeiling("the run of short lines");
		assertNotNull(raised,
				"a peer sending lines the parser discards must still end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	@Test
	public void anOversizedNonStreamingBodyIsAbortedInsteadOfBufferedWhole() {
		pointEngineAt("/flood-body");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

		assertPeerWasCutOffAtTheResponseCeiling("the non-streaming body");
		assertNotNull(raised,
				"a peer answering with an oversized body must end the call, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	@Test
	public void anOversizedErrorBodyIsTruncatedAndStillReportsItsStatusCode() {
		pointEngineAt("/flood-error");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

		assertPeerWasCutOff("the error body", RemoteLlmEngine.MAX_ERROR_BODY_BYTES);
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
	 *
	 * <p>While the ceiling equals {@link #STATED_RESPONSE_BUDGET} this sends the same body as
	 * {@link #aCompletionOfExactlyTheStatedBudgetArrivesWhole}, and a stream-side off-by-one alone
	 * (the throw in {@code BoundedResponseStream.count} written as {@code >=}) reddens both. What
	 * this case adds is that its body moves with the constant: that same {@code >=} with the ceiling
	 * raised by one byte to compensate reddened this case and neither budget case in this class
	 * (measured 2026-09-24).</p>
	 */
	@Test
	public void aBodyOfExactlyTheCeilingArrivesWholeRatherThanCutOff() {
		pointEngineAt("/exactly-at-the-ceiling");

		LlmEngine.InferenceResult result = engine.infer("system", "user", 60);

		awaitPeer("the at-the-ceiling body");
		assertEquals(RemoteLlmEngine.MAX_RESPONSE_BYTES, written.get(),
				"the fixture has to sit ON the boundary for this case to be about the boundary");
		assertEquals(paddingFor(RemoteLlmEngine.MAX_RESPONSE_BYTES), result.getText().length(),
				"a response of exactly the ceiling is within it and must not be abandoned");
	}

	/** The ceiling's VALUE, pinned from below — {@link #STATED_RESPONSE_BUDGET} says how (#454). */
	@Test
	public void aCompletionOfExactlyTheStatedBudgetArrivesWhole() {
		pointEngineAt("/exactly-the-budget");

		LlmEngine.InferenceResult result = assertDoesNotThrow(
				() -> engine.infer("system", "user", 60),
				"a completion of exactly the stated budget, " + STATED_RESPONSE_BUDGET + " bytes, "
						+ "must arrive whole, and the ceiling is now "
						+ RemoteLlmEngine.MAX_RESPONSE_BYTES + " bytes. Moving it is decided in "
						+ "STATED_RESPONSE_BUDGET.");

		awaitPeer("the at-the-budget body");
		assertEquals(STATED_RESPONSE_BUDGET, written.get(),
				"the fixture has to sit ON the budget for this case to be about it");
		assertEquals(paddingFor(STATED_RESPONSE_BUDGET), result.getText().length());
	}

	/** The ceiling's VALUE, pinned from above — {@link #STATED_RESPONSE_BUDGET} says how (#454). */
	@Test
	public void aCompletionOneByteOverTheStatedBudgetIsRefused() {
		pointEngineAt("/one-byte-over-the-budget");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

		assertNotNull(raised, "a completion of " + (STATED_RESPONSE_BUDGET + 1) + " bytes came "
				+ "back whole, one byte over the stated budget; MAX_RESPONSE_BYTES is now "
				+ RemoteLlmEngine.MAX_RESPONSE_BYTES + ". Moving the ceiling is decided in "
				+ "STATED_RESPONSE_BUDGET.");
		assertCeilingFailureIsReportable(raised);
	}

	/**
	 * The positive control for the TRUNCATING read. The ceiling cases only ever assert that an
	 * oversized error body stopped, which an error read returning nothing at all would satisfy —
	 * so an ordinary short error body has to come back whole. Asserted through the log because
	 * that is the only place it goes: nothing parses a non-2xx body, and the status-code
	 * {@link APIException} does not carry it.
	 *
	 * <p>At DEBUG, and the second assertion is the point of the first: the body is the
	 * ENDPOINT's text, so it stays off the default log for the reason
	 * {@code RemoteLlmEngine.logErrorBody} gives.</p>
	 */
	@Test
	public void anOrdinaryErrorBodyReachesTheLogWhole() {
		pointEngineAt("/short-error");

		try (LogCapture capture = LogCapture.on(RemoteLlmEngine.class.getName(), Level.DEBUG)) {
			APIException raised = callAndCatch(() -> engine.infer("system", "user", 60));

			assertNotNull(raised, "a 503 from the endpoint is a failed call");
			assertTrue(capture.hasMessageAt(Level.DEBUG, SHORT_ERROR_BODY),
					"the whole of a short error body must reach the log — an error read that "
							+ "returned nothing would still pass every ceiling case above. "
							+ "Captured: " + capture.describeAll());
			// At EVERY level and not merely at ERROR, which is why the capture is opened at
			// DEBUG: a negative naming one level leaves the same disclosure implementable one
			// level down, and an added log.info of the body was measured passing exactly that.
			// The idiom is FindingPartnerLogDisclosureTest's, for the same reason (#439).
			for (String logged : capture.describeAll()) {
				assertTrue(!logged.contains(SHORT_ERROR_BODY) || logged.startsWith("DEBUG"),
						"the endpoint's body may appear at DEBUG and nowhere else: it is text a "
								+ "compromised endpoint can make this patient's chart, and core "
								+ "ships org.openmrs at WARN. Logged: " + logged);
			}
		}
	}

	/**
	 * The same flood on the STREAMING route's non-2xx branch, which {@code inferStreaming} reads
	 * by its own path. Without this the only thing standing behind that branch was a source
	 * guard: a review measured that reverting it to {@code readAllBytes()} reddened no
	 * behavioural case at all, because the case ABOVE shares the {@code /flood-error} peer but
	 * drives {@code infer}, which is a different read.
	 */
	@Test
	public void anOversizedErrorBodyOnTheStreamingRouteIsTruncatedToo() {
		pointEngineAt("/flood-error");

		APIException raised = callAndCatch(() -> engine.inferStreaming("system", "user", 60,
				token -> { }));

		assertPeerWasCutOff("the streaming route's error body",
				RemoteLlmEngine.MAX_ERROR_BODY_BYTES);
		assertNotNull(raised, "a 500 from the endpoint is still a failed call");
		assertTrue(raised.getMessage() != null && raised.getMessage().contains("500"),
				"the status code survives on this route too. Got: " + raised.getMessage());
	}

	/**
	 * The batch-grounding entry point: {@code infer} with a caller-supplied
	 * {@code response_format}. It is the SAME read — the three-argument {@code infer} delegates
	 * here with a {@code null} format — so an unconditional unbounded read reddens this and the
	 * non-streaming flood case together, measured. What it pins alone is a read CONDITIONAL on
	 * the grounding path, measured passing every other case here, and the grounding REQUEST
	 * shape against a real peer, which nothing else drives.
	 */
	@Test
	public void theResponseFormatOverloadIsBoundedLikeTheOthers() {
		pointEngineAt("/flood-body");

		APIException raised = callAndCatch(() -> engine.infer("system", "user", 60,
				new ObjectMapper().createObjectNode()));

		assertPeerWasCutOffAtTheResponseCeiling("the batch-grounding body");
		assertNotNull(raised, "the grounding path must end the call too, not the heap");
		assertCeilingFailureIsReportable(raised);
	}

	/**
	 * Runs the call and hands back the {@link APIException} it raised, or {@code null}. Deliberately
	 * not {@code assertThrows}: the byte assertion is the one that says the heap was bounded, and it
	 * has to be reached even on the run where no exception was raised at all — which is precisely
	 * the unbounded case. {@link #aCompletionOneByteOverTheStatedBudgetIsRefused} makes no byte
	 * assertion and uses it for its failure message instead, which says what a body read whole
	 * means there.
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
	 * transport failure. And the cause has to be the ceiling's own exception, so the stack an
	 * operator reads names where the read was cut off — {@code RemoteLlmEngine.oversized}'s javadoc
	 * says why that is safe to attach now (issue #451).
	 */
	private static void assertCeilingFailureIsReportable(APIException raised) {
		assertNotNull(raised.getMessage(), "the failure must say something an operator can act on");
		assertTrue(raised.getMessage().contains(String.valueOf(RemoteLlmEngine.MAX_RESPONSE_BYTES)),
				"the message must name the ceiling that was exceeded. Got: "
						+ raised.getMessage());
		assertTrue(raised.getCause() instanceof BoundedResponseStream.ResponseTooLargeException,
				"the failure must carry the ceiling's own exception as its cause. Got cause: "
						+ raised.getCause());
	}

	private void pointEngineAt(String path) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
				"http://" + server.getAddress().getHostString() + ":"
						+ server.getAddress().getPort() + path);
	}

	private void assertPeerWasCutOffAtTheResponseCeiling(String what) {
		assertPeerWasCutOff(what, RemoteLlmEngine.MAX_RESPONSE_BYTES);
	}

	/**
	 * Something stopped reading the peer. Asked of the HANDLER and not of the wire total: the
	 * handler repeats until it is cut off or reaches {@link #SAFETY_LIMIT}, so which of those
	 * happened is a difference of megabytes, while the wire total is the ceiling plus whatever the
	 * kernel under the test absorbed on the way down — under 1 MB on macOS loopback, 2.7 MB on
	 * GitHub's Linux runners, both with the same working ceiling. The class javadoc has the
	 * figures and what they cost.
	 *
	 * <p>{@code ceiling} is how much the module reads before it stops, and it is asserted as a
	 * FLOOR on the wire total for one reason: a handler that threw on its first write is also a
	 * handler that did not reach its safety limit, and without this an exchange that failed for
	 * some unrelated reason would read as a bound doing its job. The peer cannot have written
	 * fewer bytes than the module read.</p>
	 */
	private void assertPeerWasCutOff(String what, long ceiling) {
		awaitPeer(what);
		assertFalse(peerReachedItsSafetyLimit.get(),
				what + ": the peer got " + written.get() + " bytes onto the wire and stopped only "
						+ "because it ran out of its own " + SAFETY_LIMIT + "-byte allowance, not "
						+ "because anything cut it off. A response may not grow the shared JVM's "
						+ "heap by whatever the endpoint chooses to send; the module stops reading "
						+ "at " + ceiling + " bytes, and a peer that is stopped never gets near "
						+ "its own limit.");
		assertTrue(written.get() >= ceiling,
				what + ": the peer got only " + written.get() + " bytes onto the wire, fewer than "
						+ "the " + ceiling + " the module reads before it stops — so this run "
						+ "never exercised the ceiling, and the handler's stopping says nothing "
						+ "about it.");
	}

	/**
	 * Blocks until the handler thread has stopped writing, so that {@link #written} and
	 * {@link #peerReachedItsSafetyLimit} are final rather than sampled mid-flight.
	 */
	private void awaitPeer(String what) {
		try {
			assertTrue(peerFinished.await(PEER_EXIT_SECONDS, TimeUnit.SECONDS),
					what + ": the peer was still writing " + PEER_EXIT_SECONDS + " seconds after "
							+ "the call unwound, having got " + written.get() + " bytes onto the "
							+ "wire. A peer the module stopped reading fails on its next write, so "
							+ "this one was not stopped.");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while waiting for the peer to stop writing", e);
		}
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

	/** A non-2xx body well under {@link RemoteLlmEngine#MAX_ERROR_BODY_BYTES}, so nothing is cut. */
	private void shortError(HttpExchange exchange) throws IOException {
		respondOnce(exchange, 503, "application/json",
				SHORT_ERROR_BODY.getBytes(StandardCharsets.UTF_8));
	}

	/** A well-formed completion whose body is exactly {@code totalBytes}, sent once. */
	private void respondWithCompletionOf(HttpExchange exchange, long totalBytes) throws IOException {
		respondOnce(exchange, 200, "application/json",
				(COMPLETION_PREFIX + "z".repeat(paddingFor(totalBytes)) + COMPLETION_SUFFIX)
						.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * How much filler makes {@link #COMPLETION_PREFIX}, filler and {@link #COMPLETION_SUFFIX} add up
	 * to {@code totalBytes}.
	 */
	private static int paddingFor(long totalBytes) {
		return (int) totalBytes - COMPLETION_PREFIX.length() - COMPLETION_SUFFIX.length();
	}

	/** An SSE stream that never reaches {@code [DONE]} — one content chunk after another. */
	private void floodStream(HttpExchange exchange) throws IOException {
		byte[] chunk = (SSE_CONTENT_PREFIX + "x".repeat(1024) + "\"}}]}\n\n")
				.getBytes(StandardCharsets.UTF_8);
		respond(exchange, 200, "text/event-stream", chunk);
	}

	/**
	 * An SSE stream that opens a content chunk and then never emits the newline that would end
	 * its line: {@link #SSE_CONTENT_PREFIX}, then filler, and no line ever completed.
	 */
	private void endlessLine(HttpExchange exchange) throws IOException {
		byte[] opening = SSE_CONTENT_PREFIX.getBytes(StandardCharsets.UTF_8);
		byte[] filler = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
		respond(exchange, 200, "text/event-stream", opening, filler);
	}

	/**
	 * An endless run of two-byte lines that are not {@code data:} lines, so the parser reads
	 * each one and discards it. Written 4096 bytes at a time, the size {@link #floodBody}'s
	 * filler uses: how much the peer puts in one write is its own business, and what reaches
	 * {@code BufferedReader.readLine()} either way is a two-byte line.
	 */
	private void endlessShortLines(HttpExchange exchange) throws IOException {
		respond(exchange, 200, "text/event-stream",
				"a\n".repeat(2048).getBytes(StandardCharsets.UTF_8));
	}

	/** A single JSON body that never ends — a plausible completion envelope, then filler. */
	private void floodBody(HttpExchange exchange, int status) throws IOException {
		byte[] opening = COMPLETION_PREFIX.getBytes(StandardCharsets.UTF_8);
		byte[] filler = "y".repeat(4096).getBytes(StandardCharsets.UTF_8);
		respond(exchange, status, "application/json", opening, filler);
	}

	/**
	 * Answers with {@code status}, then writes every element of {@code parts} but the last once
	 * each and repeats the last until the client stops reading or {@link #SAFETY_LIMIT} is
	 * reached, counting every byte that left and recording WHICH of those two ended it. Reaching
	 * the limit is the unbounded verdict and is recorded before the stream is closed, so that a
	 * broken pipe raised by the close itself cannot erase it.
	 */
	private void respond(HttpExchange exchange, int status, String contentType, byte[]... parts)
			throws IOException {
		try {
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
				peerReachedItsSafetyLimit.set(true);
			}
			catch (IOException e) {
				// The client stopped reading — which is the whole point of the cases above.
			}
		}
		finally {
			peerFinished.countDown();
		}
	}

	/** Answers with {@code status} and exactly {@code body}, counting what left. */
	private void respondOnce(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		try {
			drainRequest(exchange);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
				written.addAndGet(body.length);
			}
		}
		finally {
			peerFinished.countDown();
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
