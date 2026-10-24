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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/**
 * A child process that passes the engine's readiness checks and answers no call: health is ok, a
 * request without the module's key gets 401, a keyed completion 503 and any other keyed route an
 * empty object.
 * Launched through a {@code llama-server} script, so a test drives the engine's real start path.
 */
public final class StandInLlamaServer {

	private StandInLlamaServer() {
	}

	public static void main(String[] args) throws Exception {
		List<String> argv = Arrays.asList(args);
		String host = argv.get(argv.indexOf("--host") + 1);
		int port = Integer.parseInt(argv.get(argv.indexOf("--port") + 1));
		String bearer = "Bearer " + System.getenv("LLAMA_API_KEY");
		HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
		server.createContext("/", exchange -> {
			boolean health = exchange.getRequestURI().getPath().equals("/health");
			boolean keyed = bearer.equals(exchange.getRequestHeaders().getFirst("Authorization"));
			boolean completion = exchange.getRequestURI().getPath().equals("/v1/chat/completions");
			byte[] body = (health ? "{\"status\":\"ok\"}" : "{}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(health ? 200 : !keyed ? 401 : completion ? 503 : 200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}
}
