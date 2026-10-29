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

import java.io.Closeable;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The interrupt that cancels model inference belongs to that inference alone. {@code TurnCancellation.cancel()}
 * dequeues each bound resource and then closes it, so a cancel can take the interrupt off the list, close the
 * response body first, and only reach the interrupt after the inference thread has unwound past its own
 * cleanup. That late close must not interrupt the pooled request thread, which goes on to settle the turn and
 * is then reused for another request (an interrupt during a Lucene NIO read closes the shared channel).
 */
public class LlmProviderInferenceInterruptTest extends BaseModuleContextSensitiveTest {

	@Test
	public void anInterruptClosedAfterInferenceReturnedDoesNotReachTheRequestThread() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(LlamaServerEndpoint.LOOPBACK_HOST, 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			exchange.getRequestBody().readAllBytes();
			byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":"
					+ "\"{\\\"answer\\\":\\\"Recorded\\\",\\\"citations\\\":[]}\"}}]}\n\n"
					+ "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		// Holds what inference bound, as a cancel that dequeued it before inference unbound it would.
		List<Closeable> dequeuedByACancel = new ArrayList<>();
		CancellationSignal cancellation = new CancellationSignal() {

			@Override
			public boolean isCancelled() {
				return false;
			}

			@Override
			public void bindCloseable(Closeable closeable) {
				dequeuedByACancel.add(closeable);
			}
		};
		try {
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, "remote");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME, "test-model");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
					"http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
							+ "/v1/chat/completions");
			LlmProvider llm = new LlmProvider();
			ReflectionTestUtils.setField(llm, "remoteEngine", new RemoteLlmEngine());

			LlmProvider.LlmResponse response = llm.searchStreaming("[1] Medication: aspirin", Collections.singletonList(1),
					"What is she taking?", token -> { }, reasoning -> { }, null, null, false,
					LlmEngine.ReferenceRecords.ABSENT, Collections.emptyList(), cancellation);
			assertNotNull(response);
			assertFalse(dequeuedByACancel.isEmpty(), "precondition: inference bound its interrupt");

			for (Closeable late : dequeuedByACancel) {
				late.close();
			}
			assertFalse(Thread.currentThread().isInterrupted(),
					"a close that arrives after inference returned must not interrupt the request thread");
			assertEquals("Recorded", response.getAnswer());
		}
		finally {
			Thread.interrupted();
			server.stop(0);
		}
	}
}
