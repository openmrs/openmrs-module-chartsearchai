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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;

/**
 * Pins the med-agent-hub request envelope and SSE framing used by
 * {@link HttpHubStreamTransport}. Network I/O is not exercised here — only the pure request and
 * parse seams the production transport shares with the old relay.
 */
public class HttpHubStreamTransportTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private HttpServer server;

	private ExecutorService executor;

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
	public void sseParserEmitsEventsAndIgnoresHeartbeats() throws Exception {
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
		assertEquals(2, events.size());
		assertEquals("answer_done", events.get(0).getEvent());
		assertEquals("Aspirin", events.get(0).getPayload().get("answer"));
		assertEquals("done", events.get(1).getEvent());
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

	@Test
	public void nonSuccessStatusThrowsHubTransportExceptionWithBody() {
		HubTransportException failure = assertThrows(HubTransportException.class,
				() -> HttpHubStreamTransport.requireSuccess(422, "{\"detail\":{\"code\":\"x\"}}"));
		assertEquals(422, failure.getStatusCode());
		assertTrue(failure.getBody().contains("\"code\":\"x\""));
	}
}
