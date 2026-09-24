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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.impl.LlmEngine.ReferenceRecords;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * {@link RemoteLlmEngine} sends the same request whatever {@link ReferenceRecords} it is handed
 * (issue #512, ADR Decision 117): it has never sent a repetition penalty, so there is nothing for
 * the value to switch off. Captured off the wire from a real {@link HttpServer} peer, because the
 * two arities under test are the engine's own methods and a body built beside them would not show
 * what they send.
 */
public class RemoteLlmEngineReferenceRecordsTest extends BaseModuleContextSensitiveTest {

	private HttpServer server;

	private final List<String> received = new ArrayList<String>();

	private final RemoteLlmEngine engine = new RemoteLlmEngine();

	@BeforeEach
	public void startPeer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/body", exchange -> respond(exchange, "application/json",
			"{\"choices\":[{\"message\":{\"content\":\"a\"}}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
		server.createContext("/stream", exchange -> respond(exchange, "text/event-stream",
			"data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\ndata: [DONE]\n\n"));
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
	public void theBlockingRequestIsTheSameWhateverThePromptCarries() {
		pointEngineAt("/body");
		engine.infer("system", "user", 60);
		engine.infer("system", "user", 60, ReferenceRecords.PRESENT);

		assertSameRequestTwice();
	}

	@Test
	public void theStreamingRequestIsTheSameWhateverThePromptCarries() {
		pointEngineAt("/stream");
		engine.inferStreaming("system", "user", 60, token -> { }, null, null);
		engine.inferStreaming("system", "user", 60, token -> { }, null, null, ReferenceRecords.PRESENT);

		assertSameRequestTwice();
	}

	private void assertSameRequestTwice() {
		assertEquals(2, received.size(), "the premise: both calls must have reached the peer");
		assertFalse(received.get(0).isEmpty(), "the premise: a request body was captured");
		assertFalse(received.get(0).contains("dry"),
			"the remote engine sends no DRY sampler, which is why it has nothing to switch off: "
					+ received.get(0));
		assertEquals(received.get(0), received.get(1),
			"a prompt carrying reference records must not change what the remote engine sends");
	}

	private void pointEngineAt(String path) {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
			"http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
	}

	private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			synchronized (received) {
				received.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
