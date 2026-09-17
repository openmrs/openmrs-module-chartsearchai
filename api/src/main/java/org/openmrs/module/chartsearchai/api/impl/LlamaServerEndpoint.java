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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that addresses and authenticates the {@code llama-server} subprocess
 * {@link LocalLlmEngine} spawns: it owns the loopback URL for every endpoint the module calls and
 * the per-server-start secret that both sides of the channel share.
 *
 * <p><b>Why it exists (issue #445).</b> The engine previously built its own {@link HttpRequest} at
 * each of five call sites and sent no credential with any of them, so the local server accepted
 * anything that could open a TCP connection to the port. A shared {@code getCompletionsUrl} helper
 * already existed, which is the point: one place for the URL is not one place for the REQUEST, and
 * it is the request that carries the credential. On the rated topology —
 * the {@code .omod} installed on an existing OpenMRS host — an unprivileged local OS user holds no
 * database or OpenMRS credential yet can reach that port, and could therefore run free inference
 * and call the {@code /slots} save/restore/erase endpoints on a patient's persisted KV cache. With
 * the addressing in five places there was nowhere authentication COULD be added once, and five
 * places to forget it. Every request to the local server is built here so that it cannot be
 * forgotten; {@code ArchitectureGuardTest.everyLocalServerRequestCarriesTheModulesKey} is what
 * keeps a sixth call site from being hand-rolled beside these.
 *
 * <p><b>The secret is handed to the child in its ENVIRONMENT, never on its command line.</b>
 * {@code llama-server} accepts the key three ways — {@code --api-key KEY}, {@code --api-key-file
 * FNAME}, and the {@code LLAMA_API_KEY} environment variable — and the command-line form is the one
 * that must not be used: an argument vector is world-readable through {@code ps}, so it would hand
 * the key to the very principal this change exists to lock out. A process's environment is not
 * exposed the same way — measured, {@code ps -E} lists the variables of a process this user owns
 * and none for a process owned by another — which is the same protection class as an owner-only key
 * file, with no file to write, restrict, or clean up on a failure path. See {@code docs/adr.md}
 * Decision 103 for the measurement behind each of those claims.
 *
 * <p><b>A bearer token authenticates the CLIENT to the server and never the server to the
 * client.</b> Between them {@link #unauthenticatedProbeStatus} and
 * {@link #doesNotRefuseThisModulesKey} establish exactly this much: SOME credential is demanded on
 * the route the chart travels, and ours was not actively refused. That is enough to catch a build
 * that ignored the environment variable, which is what they are for. It is NOT "the listener is
 * enforcing this start's key" — a listener that refuses unauthenticated calls and serves no
 * {@code /props} passes both legs while accepting the key nowhere — and it is not "the listener is
 * the child the engine spawned", which no bearer token can establish in this direction. What ties
 * readiness to the child is {@link LocalLlmEngine#requireLoopbackPortFree} plus the liveness
 * re-check in {@link LocalLlmEngine#requireListenerMayBeServed}. Do not write these two probes up
 * as peer authentication.
 *
 * <p>One instance per server start, discarded with the process it was minted for, so a secret is
 * never reused across two children.
 */
final class LlamaServerEndpoint {

	private static final Logger log = LoggerFactory.getLogger(LlamaServerEndpoint.class);

	/**
	 * The environment variable {@code llama-server} reads the API key from. Spelled here once and
	 * set on the child's {@link ProcessBuilder} environment by {@link #handOverTo}, which also
	 * overrides any value the JVM itself inherited.
	 */
	static final String API_KEY_ENV = "LLAMA_API_KEY";

	/**
	 * The host the child is told to bind and the host the engine dials — one spelling, because the
	 * two must agree and nothing else couples them. Passed as {@code --host} by
	 * {@link LocalLlmEngine#buildServerCommand} and used by {@link #baseUrl()} here.
	 *
	 * <p>It must stay a literal ADDRESS, never a name. The port check resolves it with
	 * {@code InetAddress.getByName}, which parses a dotted quad in ~100 ns but performs a real
	 * lookup for a name — measured at 30 s on this host for one that does not resolve, on the
	 * start path, bounded by nothing.
	 */
	static final String LOOPBACK_HOST = "127.0.0.1";

	/**
	 * How long each readiness probe may take. Both are loopback, and neither spends inference on a
	 * server that authenticates — the unauthenticated one is refused before its body is read, and
	 * the keyed one asks {@code /props}. The single case that decodes a token is a listener
	 * credentialing nothing, which is the case readiness exists to refuse.
	 *
	 * <p>Two seconds because these run AFTER {@code waitForServerReady}'s deadline has been
	 * checked, so whatever they take is spent past that budget, under the engine monitor. Measured
	 * against a real server they cost 1–2 ms, so this is a tail bound with three orders of
	 * magnitude of headroom rather than a routine cost.
	 */
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

	/**
	 * The body the unauthenticated probe posts: a VALID one-token completion request carrying no
	 * patient text. Valid rather than minimal on purpose. The measured build answers 401 before it
	 * validates a body at all, so either shape costs no inference there — but a build that
	 * validated the body first would answer a malformed probe with 400, and 400 is not the refusal
	 * this probe is asking about, so such a build would be refused for a reason that is not about
	 * its authentication. A valid body can only be answered 401 (refused, the expected case) or
	 * served (a build credentialing nothing, which readiness must refuse), and one token is the
	 * whole cost in that second case.
	 */
	private static final String PROBE_COMPLETION_BODY =
			"{\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";

	private static final SecureRandom SECRETS = new SecureRandom();

	private final int port;

	private final String apiKey;

	private LlamaServerEndpoint(int port, String apiKey) {
		this.port = port;
		this.apiKey = apiKey;
	}

	/**
	 * Mints the endpoint for one server start: a fresh 256-bit secret from {@link SecureRandom},
	 * base64url-encoded so it survives an environment variable and an HTTP header unescaped.
	 */
	static LlamaServerEndpoint open(int port) {
		byte[] secret = new byte[32];
		SECRETS.nextBytes(secret);
		return new LlamaServerEndpoint(port,
				Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
	}

	/**
	 * Puts this start's secret into the child's environment. Called before {@code start()} on the
	 * builder the engine is about to launch. An inherited {@code LLAMA_API_KEY} is overwritten, so
	 * the value the child reads is this endpoint's rather than one the JVM was started with.
	 */
	void handOverTo(ProcessBuilder builder) {
		builder.environment().put(API_KEY_ENV, apiKey);
	}

	String completionsUrl() {
		return baseUrl() + "/v1/chat/completions";
	}

	String healthUrl() {
		return baseUrl() + "/health";
	}

	String propsUrl() {
		return baseUrl() + "/props";
	}

	/** The single decode slot's save/restore/erase URL; the slot id is 0 because the server runs
	 *  {@code --parallel 1}. */
	String slotUrl(String action) {
		return baseUrl() + "/slots/0?action=" + action;
	}

	/**
	 * A request builder for {@code url} already carrying this start's key. Every request the engine
	 * sends comes from here; the guard named in the class javadoc, and the nested {@code CLAUDE.md}
	 * beside it, are what state that as a rule. (The unauthenticated readiness probe below builds
	 * its own, deliberately without the key — which is why the rule is written about the engine's
	 * requests rather than about every request in this file.)
	 */
	HttpRequest.Builder request(String url, Duration timeout) {
		return HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(timeout)
				.header("Authorization", "Bearer " + apiKey);
	}

	/**
	 * Whether a call carrying NO credential is refused, asked of the endpoint that carries the
	 * patient's chart. This is the negative control on the readiness path: it is the only thing
	 * that tells "the key this module minted is in force" apart from "the listener ignores keys
	 * and would have taken the chart from anyone".
	 *
	 * <p>It deliberately probes {@code /v1/chat/completions} rather than a cheaper protected
	 * endpoint: a future {@code llama-server} that reclassified THIS route as public is exactly one
	 * that would leave this module's own prompts unauthenticated, so a probe that reddens there is
	 * reporting the thing worth failing over. {@code /health} and {@code /v1/models} cannot serve
	 * as the control — they are public by design and answer 200 with no credential.
	 *
	 * <p>Fails CLOSED: an I/O error or an interrupt reads as "not refused", because a probe that
	 * could not establish the refusal has not established it — {@link #refusesCredentials} decides
	 * which codes count. It returns the STATUS rather than a boolean so the caller can say what it
	 * actually saw — a timeout ({@code -1}) and a served 200
	 * are both refusals of the start, and telling an operator their server answered a chart request
	 * unauthenticated when it never answered at all sends them after the wrong thing. Which codes
	 * COUNT as a refusal is {@link #refusesCredentials}, so both legs agree on it.
	 */
	int unauthenticatedProbeStatus(HttpClient client) {
		HttpRequest probe = HttpRequest.newBuilder()
				.uri(URI.create(completionsUrl()))
				.timeout(PROBE_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(PROBE_COMPLETION_BODY,
						StandardCharsets.UTF_8))
				.build();
		return statusOf(client, probe, "unauthenticated-probe");
	}

	/**
	 * Whether the listener does NOT reject this start's key, asked of {@code /props} — protected by
	 * the key, and the one protected route that neither runs inference nor needs a flag the
	 * engine's command line does not already pass.
	 *
	 * <p>The question is "was the key refused", so anything {@link #refusesCredentials} does not
	 * name passes. It deliberately does not require a 200: a build that does not serve
	 * {@code /props} at all answers 404, which says nothing about the key, and requiring 200 would
	 * refuse such a build for the wrong reason.
	 *
	 * <p>Unlike its sibling this leg fails OPEN: a probe that could not complete returns -1, which
	 * {@link #refusesCredentials} does not name, so it passes. That is what asking "was the key refused" means — an unreachable
	 * probe establishes no refusal. Readiness as a whole is not fail-open, because
	 * {@link #unauthenticatedProbeStatus} fails CLOSED on exactly the same condition, so a
	 * listener that cannot be probed at all is refused there.
	 */
	boolean doesNotRefuseThisModulesKey(HttpClient client) {
		HttpRequest probe = request(propsUrl(), PROBE_TIMEOUT).GET().build();
		return !refusesCredentials(statusOf(client, probe, "authenticated-probe"));
	}

	/**
	 * Whether {@code status} is a server REFUSING the credential it was given. Both 401 and 403:
	 * llama-server answers 401, but a build or a fronting proxy that answers 403 is refusing just
	 * as squarely, and requiring exactly 401 would have refused the start of a server that does
	 * demand a credential — the opposite of what the readiness gate is for. One predicate so the
	 * two legs cannot disagree about it.
	 */
	static boolean refusesCredentials(int status) {
		return status == 401 || status == 403;
	}

	/** The status code, or -1 when the probe could not complete. What -1 MEANS is the caller's
	 *  question and the two callers answer it differently — see each of them, and do not fold the
	 *  difference in here: {@link #unauthenticatedProbeStatus} requires a 401 so -1 fails, while
	 *  {@link #doesNotRefuseThisModulesKey} only excludes a refusal so -1 passes. */
	private int statusOf(HttpClient client, HttpRequest probe, String what) {
		try {
			HttpResponse<Void> response = client.send(probe, HttpResponse.BodyHandlers.discarding());
			return response.statusCode();
		}
		catch (IOException e) {
			log.debug("llama-server {} did not complete: {}", what, e.getMessage());
			return -1;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	/** {@code host:port}, for the operator-facing messages that name where the engine is looking. */
	String authority() {
		return authority(port);
	}

	/** As {@link #authority()}, for the callers that have a port but no endpoint yet — the
	 *  pre-launch port check runs before one is minted. */
	static String authority(int port) {
		return LOOPBACK_HOST + ":" + port;
	}

	private String baseUrl() {
		return "http://" + authority();
	}

}
