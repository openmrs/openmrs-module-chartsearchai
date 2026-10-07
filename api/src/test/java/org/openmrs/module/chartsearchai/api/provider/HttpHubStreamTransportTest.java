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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.LogCapture;
import org.apache.logging.log4j.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * Pins the med-agent-hub request envelope and SSE framing used by
 * {@link HttpHubStreamTransport}. Most cases exercise pure request and parse seams; cancellation
 * uses a loopback HTTP server so the production response-body close behavior is tested directly.
 */
public class HttpHubStreamTransportTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private HttpServer server;

	private ExecutorService executor;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	public void aStalledHeaderWaitEndsOnItsDeadlineOrCancellation(boolean cancel) throws Exception {
		CountDownLatch received = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			try {
				exchange.getRequestBody().readAllBytes();
				received.countDown();
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		TurnCancellation cancellation = new TurnCancellation();
		// A cancelled request must finish before the longer header deadline.
		HubClinicalAnswerProvider provider = httpProvider(cancel ? Duration.ofSeconds(30) : Duration.ofSeconds(1));
		executor = Executors.newSingleThreadExecutor();
		Future<TurnResult> completed = executor.submit(() -> {
			TurnResult result = provider.execute(turn(), event -> { }, cancellation).toCompletableFuture().get();
			assertTrue(!Thread.currentThread().isInterrupted(), "cancellation must not poison the request thread");
			return result;
		});
		try {
			assertTrue(received.await(5, TimeUnit.SECONDS));
			if (cancel) cancellation.cancel();
			TurnResult result = completed.get(3, TimeUnit.SECONDS);
			assertEquals(cancel ? "cancelled" : "provider_failure", result.getProblemCode());
		} finally {
			release.countDown();
		}
	}

	@Test
	public void theHeaderDeadlineDoesNotLimitTheAnswerAndReviewBody() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write("event: answer_done\ndata: {\"answer\":\"Initial answer\"}\n\n".getBytes(StandardCharsets.UTF_8));
				body.flush();
				new CountDownLatch(1).await(2, TimeUnit.SECONDS);
				body.write("event: done\ndata: {\"answer\":\"Checked answer\"}\n\n".getBytes(StandardCharsets.UTF_8));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
		List<Closeable> registered = new ArrayList<>();
		List<Closeable> active = new ArrayList<>();
		CancellationSignal cancellation = new CancellationSignal() {
			@Override public boolean isCancelled() { return false; }
			@Override public void bindCloseable(Closeable resource) {
				registered.add(resource);
				active.add(resource);
			}
			@Override public void unbindCloseable(Closeable resource) { active.remove(resource); }
		};
		TurnResult result = httpProvider(Duration.ofSeconds(1)).execute(turn(), event -> { },
				cancellation).toCompletableFuture().get();
		assertTrue(active.isEmpty(), "completed HTTP work must be removed from cancellation tracking");
		try {
			// A cancellation callback already dequeued elsewhere can arrive after the request finishes.
			for (Closeable resource : registered) resource.close();
			assertTrue(!Thread.currentThread().isInterrupted(), "late cancellation must not interrupt subsequent work");
		} finally {
			Thread.interrupted();
		}
		assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
		assertEquals("Checked answer", result.getAnswer().getText());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	public void aBrokenAnswerTailWarnsUnlessTheUserCancelled(boolean cancel) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(("event: answer_done\ndata: {\"answer\":\"Retained answer\"}\n\n"
						+ "event: answer_validation\ndata: invalid-json\n\n").getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
		TurnCancellation cancellation = new TurnCancellation();
		try (LogCapture capture = LogCapture.on(HubClinicalAnswerProvider.class.getName())) {
			TurnResult result = httpProvider(Duration.ofSeconds(5)).execute(turn(), event -> {
				if (cancel && event.getType() == TurnEventType.ANSWER_DONE) cancellation.cancel();
			}, cancellation).toCompletableFuture().get();
			assertEquals(TurnEventType.TURN_DONE, result.getTerminalState());
			assertEquals("Retained answer", result.getAnswer().getText());
			assertEquals(cancel ? 0 : 1, capture.messagesAt(Level.WARN).size());
			if (!cancel) assertTrue(capture.hasMessageAt(Level.WARN, "request-1", "JsonParseException"));
			assertTrue(capture.describeAll().stream().noneMatch(message -> message.contains("invalid-json")),
					"diagnostics must not log the malformed response text");
		}
	}

	private HubClinicalAnswerProvider httpProvider(Duration timeout) {
		HttpHubStreamTransport transport = new HttpHubStreamTransport() {
			@Override protected String apiKey() { return null; }
			@Override protected Duration headerTimeout() { return timeout; }
		};
		return new HubClinicalAnswerProvider(transport) {
			@Override protected String gp(String key, String fallback) {
				return "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
			}
		};
	}

	private static TurnRequest turn() {
		Patient patient = new Patient(7);
		patient.setUuid("patient-1");
		return new TurnRequest(patient, "question", "conversation-1", "request-1",
				ProviderMode.QUERY_SCOPED, "profile", Collections.emptyList());
	}

	private static final class CloseTrackingInputStream extends ByteArrayInputStream {

		private boolean closed;

		private CloseTrackingInputStream(byte[] body) {
			super(body);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	@AfterEach
	public void tearDown() {
		if (server != null) {
			server.stop(0);
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void requestJsonCarriesProfilePatientPriorsAndProductContext() throws Exception {
		HubCallRequest request = new HubCallRequest(
				"http://hub.example/v1/chat/completions", "product-a", "patient-1",
				"conversation-1", "request-1", "current question",
				Collections.singletonList(new PriorClinicalTurn("earlier q", "earlier a")));

		String json = HttpHubStreamTransport.requestJson(request);
		Map<String, Object> root = MAPPER.readValue(json, Map.class);

		assertEquals("product-a", root.get("model"));
		assertEquals(Boolean.TRUE, root.get("stream"));
		assertEquals("patient-1", root.get("patient"));
		List<Map<String, Object>> messages = (List<Map<String, Object>>) root.get("messages");
		assertEquals(3, messages.size());
		assertEquals("user", messages.get(0).get("role"));
		assertEquals("earlier q", messages.get(0).get("content"));
		assertEquals("assistant", messages.get(1).get("role"));
		assertEquals("earlier a", messages.get(1).get("content"));
		assertEquals("user", messages.get(2).get("role"));
		assertEquals("current question", messages.get(2).get("content"));

		Map<String, Object> context = (Map<String, Object>) root.get("context");
		assertEquals(Boolean.TRUE, context.get("require_product_profile"));
		assertEquals("conversation-1", context.get("session"));
		assertEquals("request-1", context.get("request_id"));
	}

	@Test
	public void sseParserRelaysHeartbeatsSoTheBrowserConnectionIsProbed() throws Exception {
		String sse = ""
				+ ": hb\n\n"
				+ "event: answer_done\n"
				+ "data: {\"answer\":\"Aspirin\",\"references\":[]}\n"
				+ "\n"
				+ "event: done\n"
				+ "data: {\"answer\":\"Aspirin\",\"references\":[]}\n"
				+ "\n";
		List<HubWireEvent> events = new ArrayList<>();
		try (InputStream in = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8))) {
			HttpHubStreamTransport.parseSse(in, events::add);
		}
		assertEquals(3, events.size());
		assertEquals("heartbeat", events.get(0).getEvent());
		assertEquals("answer_done", events.get(1).getEvent());
		assertEquals("Aspirin", events.get(1).getPayload().get("answer"));
		assertEquals("done", events.get(2).getEvent());
	}

	@Test
	public void cancellingAnInFlightStreamUnblocksItInsteadOfRunningToTheHubsOwnCompletion() throws Exception {
		// Reproduces the actual production hang this class exists to fix: a preempted turn must
		// not keep reading from the hub until the hub decides it's done. Binding the open response
		// body to a TurnCancellation (see HttpHubStreamTransport#stream) and closing it from another
		// thread has to unblock the reader's BufferedReader#readLine() promptly.
		CountDownLatch firstEventSent = new CountDownLatch(1);
		CountDownLatch serverMayFinish = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write("event: reasoning_delta\ndata: {\"delta\":\"thinking\"}\n\n"
						.getBytes(StandardCharsets.UTF_8));
				body.flush();
				firstEventSent.countDown();
				// Hold the connection open — a real hub still generating In-Depth. If cancellation
				// does not actually unblock the client, the test below times out waiting on
				// streamReturned, proving the bug rather than merely asserting it away.
				serverMayFinish.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();

		HttpHubStreamTransport transport = new HttpHubStreamTransport();
		HubCallRequest request = new HubCallRequest(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/stream", "product-a",
				"patient-1", "conversation-1", "request-1", "question", Collections.emptyList());
		TurnCancellation cancellation = new TurnCancellation();
		List<HubWireEvent> received = Collections.synchronizedList(new ArrayList<>());

		executor = Executors.newSingleThreadExecutor();
		Future<Exception> streamReturned = executor.submit(() -> {
			try {
				transport.stream(request, received::add, cancellation);
				return null;
			}
			catch (Exception e) {
				return e;
			}
		});

		assertTrue(firstEventSent.await(5, TimeUnit.SECONDS), "server never sent its first event");
		cancellation.cancel();

		Exception thrown;
		try {
			thrown = streamReturned.get(5, TimeUnit.SECONDS);
		}
		catch (java.util.concurrent.TimeoutException e) {
			fail("cancel() did not unblock the in-flight hub read within 5s — the connection to the "
					+ "hub was not actually torn down, so a preempted turn would keep running to the "
					+ "hub's own completion instead of freeing its router slot");
			return;
		}
		assertTrue(thrown != null, "the interrupted read should surface as a failure, not a silent success");
		serverMayFinish.countDown();
	}


	@ParameterizedTest
	@ValueSource(strings = { "done", "error" })
	public void terminalEventReleasesTheConnectionWithoutWaitingForServerClose(String terminal) throws Exception {
		CountDownLatch terminalSent = new CountDownLatch(1);
		CountDownLatch serverMayFinish = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(("event: " + terminal + "\ndata: {\"answer\":\"Finished\","
						+ "\"code\":\"upstream_unavailable\"}\n\n").getBytes(StandardCharsets.UTF_8));
				body.flush();
				terminalSent.countDown();
				serverMayFinish.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
		HubCallRequest request = new HubCallRequest(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/stream", "product-a",
				"patient-1", "conversation-1", "request-1", "question", Collections.emptyList());
		List<HubWireEvent> received = Collections.synchronizedList(new ArrayList<>());
		executor = Executors.newSingleThreadExecutor();
		Future<?> completed = executor.submit(() -> new HttpHubStreamTransport()
				.stream(request, received::add, CancellationSignal.NONE));
		try {
			assertTrue(terminalSent.await(5, TimeUnit.SECONDS), "server did not send terminal event");
			completed.get(2, TimeUnit.SECONDS);
			assertEquals(1, received.size());
			assertEquals(terminal, received.get(0).getEvent());
		} finally {
			serverMayFinish.countDown();
		}
	}


	@ParameterizedTest
	@ValueSource(ints = { 200, 422 })
	public void oversizedHubBodiesAreBoundedThroughTheRealHttpPath(int status) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stream", exchange -> {
			exchange.sendResponseHeaders(status, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				if (status == 200) body.write("event: done\ndata: ".getBytes(StandardCharsets.UTF_8));
				byte[] padding = new byte[8192];
				java.util.Arrays.fill(padding, (byte) ' ');
				for (int i = 0; i < 1024; i++) body.write(padding);
				body.write("{\"answer\":\"oversized\"}\n\n".getBytes(StandardCharsets.UTF_8));
			} catch (IOException expectedAfterClientCloses) {
				// A bounded reader abandons this response before the server finishes writing it.
			}
		});
		server.start();
		HubCallRequest request = new HubCallRequest(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/stream", "product-a",
				"patient-1", "conversation-1", "request-1", "question", Collections.emptyList());
		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> new HttpHubStreamTransport().stream(request, event -> { }, CancellationSignal.NONE));
		if (status == 200) {
			assertTrue(failure.getMessage().contains("ceiling"), "oversized SSE must fail at the byte ceiling");
		} else {
			assertTrue(failure instanceof HubTransportException);
			assertTrue(((HubTransportException) failure).getBody().length() <= 8192,
					"error response must be truncated before being buffered");
		}
	}

	@Test
	public void nonSuccessStatusThrowsHubTransportExceptionWithBody() {
		HubTransportException failure = assertThrows(HubTransportException.class,
				() -> HttpHubStreamTransport.requireSuccess(422, "{\"detail\":{\"code\":\"x\"}}"));
		assertEquals(422, failure.getStatusCode());
		assertTrue(failure.getBody().contains("\"code\":\"x\""));
	}

	@Test
	public void readingANonSuccessBodyAlwaysClosesTheResponseStream() throws Exception {
		CloseTrackingInputStream body = new CloseTrackingInputStream(
				"{\"detail\":{\"code\":\"x\"}}".getBytes(StandardCharsets.UTF_8));

		String content = HttpHubStreamTransport.readTruncatedErrorBody(body);

		assertTrue(body.closed, "the non-success response body must release its HTTP connection");
		assertTrue(content.contains("\"code\":\"x\""));
	}
}
