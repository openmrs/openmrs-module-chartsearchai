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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openmrs.api.APIException;

/**
 * Issue #445: the {@code llama-server} subprocess is launched with no authentication and the engine
 * addresses it by loopback port alone, so a local process can both drive the running server and —
 * by binding the port while the server is down — be adopted as the server and handed the system
 * prompt plus the patient's serialized chart.
 *
 * <p>Nothing here is a stand-in for the transport. Every assertion about a request drives the
 * production {@link LlamaServerEndpoint} against a REAL listener ({@link HttpServer} on the
 * loopback interface, answering 401 unless the bearer matches) over a real {@link HttpClient}, and
 * every assertion about the port drives the production check against a real {@link ServerSocket}.
 * Liveness arrives as the liveness of a real OS process — an exited child, or this JVM.
 * {@code llama-server} itself cannot be launched in CI (the binary is not in the checkout, and
 * {@code llama-server-natives} pins no version), which is why the two production entry points are
 * package-private statics taking everything they read — the convention {@code buildServerCommand},
 * {@code serverNeedsRestart} and {@code kvQueryAction} already follow in {@link LocalLlmEngine}.
 *
 * <p>The figures those entry points were designed against were measured on the bundled binary and
 * are recorded in {@code docs/adr.md} Decision 103, not here.
 */
public class LocalLlmServerAuthTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	// ---- the secret reaches the child, and only the child ----

	@Test
	public void theSecretReachesTheChildInItsEnvironmentAndNeverOnItsCommandLine() {
		LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(18085);
		ProcessBuilder builder = new ProcessBuilder("/bin/llama-server");

		endpoint.handOverTo(builder);
		String secret = builder.environment().get(LlamaServerEndpoint.API_KEY_ENV);

		assertTrue(secret != null && !secret.isEmpty(),
				"the child must be handed a key in its environment: a process's environment is "
				+ "readable only by its owner, so an unprivileged local user cannot learn it");
		List<String> command = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 18085, 32768, "/var/kvcache");
		for (String argument : command) {
			assertFalse(argument.contains(secret),
					"the key must never appear on the command line — an argument vector is "
					+ "world-readable through ps, which would hand it to the very local "
					+ "principal this change locks out; found in: " + argument);
		}
	}

	@Test
	public void handingTheKeyOverOverwritesOneTheJvmInherited() {
		LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(18085);
		ProcessBuilder builder = new ProcessBuilder("/bin/llama-server");
		builder.environment().put(LlamaServerEndpoint.API_KEY_ENV, "inherited-from-the-jvm");

		endpoint.handOverTo(builder);

		assertNotEquals("inherited-from-the-jvm",
				builder.environment().get(LlamaServerEndpoint.API_KEY_ENV),
				"a key inherited from the JVM's own environment must be overwritten, or the child "
				+ "enforces a secret this module does not send and every query fails 401");
	}

	@Test
	public void twoServerStartsDoNotShareASecret() {
		ProcessBuilder first = new ProcessBuilder("/bin/llama-server");
		ProcessBuilder second = new ProcessBuilder("/bin/llama-server");

		LlamaServerEndpoint.open(18085).handOverTo(first);
		LlamaServerEndpoint.open(18085).handOverTo(second);

		assertNotEquals(first.environment().get(LlamaServerEndpoint.API_KEY_ENV),
				second.environment().get(LlamaServerEndpoint.API_KEY_ENV),
				"each server start mints its own secret, so a key learned from one process is "
				+ "worthless against the next");
	}

	// ---- the command line ----

	@Test
	public void theServerCommandPinsTheBindToLoopback() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 18085, 32768);

		int idx = cmd.indexOf("--host");
		assertTrue(idx >= 0, "--host must be explicit: --host also reads LLAMA_ARG_HOST from the "
				+ "environment the child inherits from the JVM, so resting on llama.cpp's default "
				+ "leaves a server-wide variable able to widen the bind to every interface");
		assertEquals("127.0.0.1", cmd.get(idx + 1),
				"the local server must listen on loopback only");
	}

	@Test
	public void theServerCommandServesNoWebUi() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 18085, 32768);

		assertTrue(cmd.contains("--no-webui"),
				"the Web UI is enabled by default and its root is served outside the API-key "
				+ "middleware, so it is the one route on this port that answers an "
				+ "unauthenticated caller; nothing in this module renders it");
	}

	// ---- every request carries the key ----

	@Test
	public void everyRequestToTheLocalServerCarriesTheModulesKey() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			LlamaServerEndpoint endpoint = listener.endpoint();
			HttpClient client = HttpClient.newHttpClient();

			for (String url : List.of(endpoint.completionsUrl(), endpoint.healthUrl(),
					endpoint.propsUrl(), endpoint.slotUrl("save"))) {
				HttpRequest request = endpoint.request(url, TIMEOUT).GET().build();
				int status;
				try {
					status = client.send(request, HttpResponse.BodyHandlers.discarding())
							.statusCode();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				assertEquals(200, status, "a request the engine sends to " + url
						+ " must carry this start's key; a listener demanding it answered 401");
			}
			assertEquals(4, listener.authorizedRequests(),
					"every one of the four routes the engine speaks to must have presented the key");
		}
	}

	// ---- the port must be free before the child is launched ----

	@Test
	public void aPortAnotherProcessIsListeningOnFailsTheStartLoudly() throws IOException {
		try (ServerSocket squatter = new ServerSocket()) {
			squatter.setReuseAddress(true);
			squatter.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			int squatted = squatter.getLocalPort();

			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireLoopbackPortFree(squatted),
					"a listener already holding the port must fail the start: adopting it hands "
					+ "the system prompt and the patient's chart to another process");
			assertTrue(thrown.getMessage().contains(String.valueOf(squatted)),
					"the failure must name the port an operator has to free, not just that one "
					+ "was busy: " + thrown.getMessage());
		}
	}

	@Test
	public void aFreePortDoesNotFailTheStart() throws IOException {
		int free;
		try (ServerSocket probe = new ServerSocket()) {
			probe.setReuseAddress(true);
			probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			free = probe.getLocalPort();
		}

		LocalLlmEngine.requireLoopbackPortFree(free);
	}

	@Test
	public void aPortLeftInTimeWaitByThePreviousChildDoesNotFailTheStart() throws IOException {
		int recentlyClosed = portLeftInTimeWait();
		Assumptions.assumeTrue(recentlyClosed > 0,
				"could not leave a loopback port in TIME_WAIT on this host");

		// The restart path (stopServer then startServer back to back) and the crash path (restart
		// without stopServer at all) both leave the previous child's socket lingering. The check
		// must be no stricter than the child's own bind, which sets SO_REUSEADDR too, or it
		// refuses starts that would have succeeded.
		LocalLlmEngine.requireLoopbackPortFree(recentlyClosed);
	}

	// ---- readiness is the child's, and the key's ----

	/**
	 * The positive control, without which the three refusals below could all pass over a check that
	 * refuses everything. It is also, read honestly, the residue: this listener is NOT the spawned
	 * child, and it is accepted — because it demands the key and the child is alive, which is all
	 * the design can ask. A perfect mimic that wins the bind race is not refusable by these
	 * probes, and {@code docs/adr.md} Decision 103 states that rather than claiming otherwise.
	 */
	@Test
	public void aHealthyListenerEnforcingTheKeyBesideALiveChildIsReadiness() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			LocalLlmEngine.requireHealthyListenerIsTheSpawnedChild(listener.endpoint(),
					HttpClient.newHttpClient(), ProcessHandle.current()::isAlive);
		}
	}

	@Test
	public void aHealthyListenerIsNotReadinessWhenTheSpawnedChildHasExited() throws IOException {
		BooleanSupplier exitedChild = livenessOfAnExitedProcess();

		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireHealthyListenerIsTheSpawnedChild(
							listener.endpoint(), HttpClient.newHttpClient(), exitedChild),
					"a child that lost the bind race exits at once, so a healthy answer beside a "
					+ "dead child is another process answering — it must fail the start rather "
					+ "than leave a foreign listener in service");
			assertTrue(thrown.getMessage().contains("exited"),
					"the failure must say the spawned server is gone: " + thrown.getMessage());
		}
	}

	@Test
	public void aListenerThatServesAnUnauthenticatedInferenceCallIsNotReadiness()
			throws IOException {
		try (PermissiveListener listener = PermissiveListener.start()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireHealthyListenerIsTheSpawnedChild(
							listener.endpoint(), HttpClient.newHttpClient(),
							ProcessHandle.current()::isAlive),
					"a listener that answers an inference request with no credential is not "
					+ "enforcing the key this module minted, so it would have taken the chart "
					+ "from anyone — and there is no way to tell it from an impostor");
			assertTrue(thrown.getMessage().contains("unauthenticated"),
					"the failure must name what was not refused: " + thrown.getMessage());
		}
	}

	@Test
	public void aListenerThatRejectsThisModulesOwnKeyIsNotReadiness() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.refusingEveryKey()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireHealthyListenerIsTheSpawnedChild(
							listener.endpoint(), HttpClient.newHttpClient(),
							ProcessHandle.current()::isAlive),
					"a server that rejects this start's key must fail the start, not surface as a "
					+ "401 on a clinician's query");
			assertTrue(thrown.getMessage().contains("rejected"),
					"the failure must say the key was rejected: " + thrown.getMessage());
		}
	}

	@Test
	public void aListenerWithNoPropsRouteIsStillReadiness() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.withoutAPropsRoute()) {
			// The key leg asks whether the key was REFUSED, so a 404 — this build does not serve
			// that route — must not fail the start. Requiring 200 there would refuse a build for
			// something that says nothing about its authentication, and the unauthenticated leg
			// still proves the key is in force.
			LocalLlmEngine.requireHealthyListenerIsTheSpawnedChild(listener.endpoint(),
					HttpClient.newHttpClient(), ProcessHandle.current()::isAlive);
		}
	}

	// ---- real listeners ----

	/**
	 * A real loopback HTTP listener that demands the endpoint's bearer token, as
	 * {@code llama-server} launched with a key does: 401 without it or with the wrong one, and 200
	 * with it. {@code /health} answers {@code {"status":"ok"}} either way, because the real server
	 * serves that route publicly.
	 */
	private static final class KeyDemandingListener implements AutoCloseable {

		private final HttpServer server;

		private final LlamaServerEndpoint endpoint;

		private final List<String> authorized = new ArrayList<>();

		private KeyDemandingListener(HttpServer server, LlamaServerEndpoint endpoint) {
			this.server = server;
			this.endpoint = endpoint;
		}

		static KeyDemandingListener start() throws IOException {
			return start(true, true);
		}

		/** A listener that demands the key on inference but serves no {@code /props} route, the
		 *  shape of a build that simply does not have one. */
		static KeyDemandingListener withoutAPropsRoute() throws IOException {
			return start(true, false);
		}

		/** A listener that is up and demands a key but will not accept the one this start minted. */
		static KeyDemandingListener refusingEveryKey() throws IOException {
			return start(false, true);
		}

		private static KeyDemandingListener start(boolean acceptTheModulesKey, boolean servesProps)
				throws IOException {
			HttpServer server = HttpServer.create(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(server.getAddress().getPort());
			KeyDemandingListener listener = new KeyDemandingListener(server, endpoint);
			String expected = expectedBearer(endpoint);
			server.createContext("/", exchange -> {
				String presented = exchange.getRequestHeaders().getFirst("Authorization");
				boolean keyed = acceptTheModulesKey && expected.equals(presented);
				if (keyed) {
					listener.authorized.add(exchange.getRequestURI().getPath());
				}
				if (exchange.getRequestURI().getPath().equals("/health")) {
					respond(exchange, 200, "{\"status\":\"ok\"}");
					return;
				}
				if (!servesProps && exchange.getRequestURI().getPath().equals("/props")) {
					respond(exchange, 404, "{\"error\":\"not found\"}");
					return;
				}
				respond(exchange, keyed ? 200 : 401, keyed ? "{}" : "{\"error\":\"unauthorized\"}");
			});
			server.start();
			return listener;
		}

		LlamaServerEndpoint endpoint() {
			return endpoint;
		}

		int authorizedRequests() {
			return authorized.size();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	/**
	 * A real loopback listener that answers everything 200 with no credential — the shape a naive
	 * impostor takes, and the shape a {@code llama-server} build that ignored
	 * {@link LlamaServerEndpoint#API_KEY_ENV} would also take.
	 */
	private static final class PermissiveListener implements AutoCloseable {

		private final HttpServer server;

		private final LlamaServerEndpoint endpoint;

		private PermissiveListener(HttpServer server, LlamaServerEndpoint endpoint) {
			this.server = server;
			this.endpoint = endpoint;
		}

		static PermissiveListener start() throws IOException {
			HttpServer server = HttpServer.create(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> respond(exchange, 200,
					exchange.getRequestURI().getPath().equals("/health")
							? "{\"status\":\"ok\"}" : "{}"));
			server.start();
			return new PermissiveListener(server,
					LlamaServerEndpoint.open(server.getAddress().getPort()));
		}

		LlamaServerEndpoint endpoint() {
			return endpoint;
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/**
	 * The {@code Authorization} header the endpoint will actually send, read off the endpoint
	 * rather than reconstructed, so the listener cannot come to demand a header shape the
	 * production builder no longer produces.
	 */
	private static String expectedBearer(LlamaServerEndpoint endpoint) {
		return endpoint.request(endpoint.healthUrl(), TIMEOUT).GET().build()
				.headers().firstValue("Authorization").orElse("");
	}

	/**
	 * The liveness of a genuinely exited OS process: this JVM's own launcher, run with
	 * {@code -version} and waited for. {@code java.home} is contractually present, so this needs
	 * no assumption about how the OS reports a command line.
	 */
	private static BooleanSupplier livenessOfAnExitedProcess() throws IOException {
		Path launcher = javaLauncher();
		Assumptions.assumeTrue(launcher != null, "no java launcher under java.home");
		Process child = new ProcessBuilder(launcher.toString(), "-version")
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		try {
			child.waitFor();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		return child::isAlive;
	}

	private static Path javaLauncher() {
		Path bin = Paths.get(System.getProperty("java.home"), "bin");
		for (String name : List.of("java", "java.exe")) {
			Path candidate = bin.resolve(name);
			if (Files.isExecutable(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * A loopback port whose socket the OS is holding after a close — the state the previous
	 * llama-server child leaves behind on the restart and crash paths. Produced by connecting to a
	 * listener and closing the CLIENT side first, which puts the client's own local port into
	 * {@code TIME_WAIT}. Returns -1 where the host does not leave one, so the caller can skip.
	 */
	private static int portLeftInTimeWait() throws IOException {
		try (ServerSocket acceptor = new ServerSocket()) {
			acceptor.setReuseAddress(true);
			acceptor.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			try (java.net.Socket client = new java.net.Socket()) {
				client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
						acceptor.getLocalPort()));
				int local = client.getLocalPort();
				try (java.net.Socket accepted = acceptor.accept()) {
					// The CLIENT side closes first, which is what puts its local port into
					// TIME_WAIT; try-with-resources closes the accepted side after.
					client.close();
					return local;
				}
			}
		}
	}

}
