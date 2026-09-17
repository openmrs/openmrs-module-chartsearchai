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
 * {@code llama-server-natives} pins no version), which is why the readiness and port entry points
 * are package-private statics taking everything they read — the convention
 * {@code buildServerCommand}, {@code serverNeedsRestart} and {@code kvQueryAction} already follow
 * in {@link LocalLlmEngine}. {@code beginServerOutputCapture}, {@code rememberServerOutput} and
 * {@code lastServerOutput} are package-private for the same reason, and their tests drive the
 * composed path — publish, write, read — rather than any one of them alone.
 *
 * <p>The figures those entry points were designed against were measured on the bundled binary and
 * are recorded in {@code docs/adr.md} Decision 103, not here.
 */
public class LocalLlmServerAuthTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** One client for the whole class. Each {@code CLIENT} starts a selector
	 *  thread and workers that are not reclaimed until GC, and this is the only test class here
	 *  that opens sockets — six of them left ~20 daemon threads and ~25 descriptors per run. */
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	// ---- the secret reaches the child, and only the child ----

	@Test
	public void theSecretReachesTheChildInItsEnvironmentAndNeverOnItsCommandLine() {
		LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(9999);
		ProcessBuilder builder = new ProcessBuilder(LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768, "/var/kvcache"));

		endpoint.handOverTo(builder);
		String secret = builder.environment().get(LlamaServerEndpoint.API_KEY_ENV);

		assertTrue(secret != null && !secret.isEmpty(),
				"the child must be handed a key in its environment: a process's environment is "
				+ "not exposed to another user the way an argument vector is, so an unprivileged "
				+ "local user cannot learn it");

		// The list that actually becomes the child's argv is the BUILDER's, not what
		// buildServerCommand returned — a static that never receives the key and so could not
		// leak it. Asserting on the builder is what makes a key argument added at the assembly
		// point (the one regression that would re-publish the secret to ps) visible here.
		for (String argument : builder.command()) {
			assertFalse(argument.contains(secret),
					"the key must never reach the child's argument vector, which is readable by "
					+ "any local user through ps — that would hand it to the very principal this "
					+ "change locks out; found in: " + argument);
		}
		assertFalse(String.join(" ", builder.command()).contains("--api-key"),
				"no key argument may be assembled onto the command line at all: llama-server "
				+ "reads " + LlamaServerEndpoint.API_KEY_ENV + " from the environment instead");
	}

	@Test
	public void handingTheKeyOverOverwritesOneTheJvmInherited() {
		LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(9999);
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

		LlamaServerEndpoint.open(9999).handOverTo(first);
		LlamaServerEndpoint.open(9999).handOverTo(second);

		assertNotEquals(first.environment().get(LlamaServerEndpoint.API_KEY_ENV),
				second.environment().get(LlamaServerEndpoint.API_KEY_ENV),
				"each server start mints its own secret, so a key learned from one process is "
				+ "worthless against the next");
	}

	// ---- the command line ----

	@Test
	public void theServerCommandPinsTheBindToLoopback() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

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
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertTrue(cmd.contains("--no-webui"),
				"the Web UI is enabled by default and its root is served outside the API-key "
				+ "middleware; it is not the only route answering an unauthenticated caller "
				+ "(/health and /v1/models are public by design) but it is the only one this "
				+ "module can close and does not use");
	}

	// ---- every request carries the key ----

	@Test
	public void everyRequestToTheLocalServerCarriesTheModulesKey() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			LlamaServerEndpoint endpoint = listener.endpoint();

			for (String url : List.of(endpoint.completionsUrl(), endpoint.healthUrl(),
					endpoint.propsUrl(), endpoint.slotUrl("save"))) {
				HttpRequest request = endpoint.request(url, TIMEOUT).GET().build();
				int status;
				try {
					status = CLIENT.send(request, HttpResponse.BodyHandlers.discarding())
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
			// The address the ENGINE dials, spelled independently of the code under test — sharing
			// InetAddress.getLoopbackAddress() with it hid a probe of ::1 on a JVM that prefers
			// IPv6, which reported a real 127.0.0.1 squatter as free.
			squatter.bind(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0));
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
	public void aPortHeldByAWildcardBoundListenerAlsoFailsTheStart() throws IOException {
		try (ServerSocket squatter = new ServerSocket()) {
			squatter.setReuseAddress(true);
			// Bound to ALL interfaces, which is how a daemon holding this port normally binds —
			// and the shape a bind probe reports as FREE, because SO_REUSEADDR grants a
			// specific-address bind over a wildcard holder.
			squatter.bind(new InetSocketAddress("0.0.0.0", 0));
			int squatted = squatter.getLocalPort();

			assertThrows(APIException.class,
					() -> LocalLlmEngine.requireLoopbackPortFree(squatted),
					"a listener on the wildcard address holds this port for loopback traffic too, "
					+ "so the start must be refused just as loudly as for a loopback-bound one");
		}
	}

	/**
	 * The refusal branch, which nothing pinned: reverting it to the fail-open form that read every
	 * {@code IOException} as "nothing listening" left the FULL suite green, and that form is what a
	 * review round measured shipping twice. A listener that never accepts saturates its backlog, so
	 * the probe neither connects nor is refused — it times out, which establishes no refusal and
	 * must refuse the start. It is the only shape in which a non-{@code ConnectException} is
	 * reachable on a healthy host, so it is the test for the whole branch.
	 */
	@Test
	public void aPortHeldByAListenerThatAcceptsNothingFailsTheStart() throws IOException {
		// Backlog 1, and never accept: the first pending connection fills the queue.
		try (ServerSocket blackHole = new ServerSocket(0, 1,
				InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST))) {
			int held = blackHole.getLocalPort();
			try (java.net.Socket filler = new java.net.Socket(java.net.Proxy.NO_PROXY)) {
				filler.connect(new InetSocketAddress(
						InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), held), 2000);

				APIException thrown = assertThrows(APIException.class,
						() -> LocalLlmEngine.requireLoopbackPortFree(held),
						"a probe that neither connects nor is refused has established nothing, so "
						+ "the start must be refused — reading it as a free port is how this check "
						+ "fails OPEN, which is the one direction it exists to prevent");
				assertTrue(thrown.getMessage().contains("Could not establish"),
						"and say that is what happened, rather than claiming a listener was found: "
						+ thrown.getMessage());
			}
		}
	}

	/**
	 * The secret's size, which nothing pinned: shrinking it to one byte left the full suite green.
	 * An 8-bit key is guessable in 256 tries by the local process this whole change exists to lock
	 * out, so the length is part of the security property and not an implementation detail.
	 */
	@Test
	public void theSecretIsLongEnoughToBeWorthDemanding() {
		ProcessBuilder builder = new ProcessBuilder("/bin/llama-server");
		LlamaServerEndpoint.open(9999).handOverTo(builder);
		String secret = builder.environment().get(LlamaServerEndpoint.API_KEY_ENV);

		// 32 random bytes, base64url without padding: ceil(32 * 4 / 3) = 43 characters.
		assertEquals(43, secret.length(),
				"a 256-bit secret is 43 base64url characters; anything shorter is a key a local "
				+ "process can search, and the environment it travels in does not make it safe: "
				+ secret.length() + " characters");
		assertTrue(secret.matches("[A-Za-z0-9_-]+"),
				"and base64url throughout, so it survives an environment variable and an HTTP "
				+ "header without escaping: " + secret);
	}

	@Test
	public void aFreePortDoesNotFailTheStart() throws IOException {
		int free;
		try (ServerSocket probe = new ServerSocket()) {
			probe.setReuseAddress(true);
			probe.bind(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0));
			free = probe.getLocalPort();
		}

		LocalLlmEngine.requireLoopbackPortFree(free);
	}

	@Test
	public void aPortLeftInTimeWaitByThePreviousChildDoesNotFailTheStart() throws IOException {
		int recentlyClosed = portLeftInTimeWait();

		// The restart path (stopServer then startServer back to back) and the crash path (restart
		// without stopServer at all) both leave the previous child's socket lingering. Nothing
		// accepts a connection on it, so the start must proceed — a check that refused here would
		// refuse the ordinary restart and blame the module's own dead child.
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
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(),
					CLIENT, ProcessHandle.current()::isAlive);
		}
	}

	@Test
	public void aHealthyListenerIsNotReadinessWhenTheSpawnedChildHasExited() throws IOException {
		BooleanSupplier exitedChild = livenessOfAnExitedProcess();

		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireListenerMayBeServed(
							listener.endpoint(), CLIENT, exitedChild),
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
		try (KeyDemandingListener listener = KeyDemandingListener.demandingNoKeyAtAll()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireListenerMayBeServed(
							listener.endpoint(), CLIENT,
							ProcessHandle.current()::isAlive),
					"a listener that answers an inference request with no credential is not "
					+ "enforcing the key this module minted, so it would have taken the chart "
					+ "from anyone — and there is no way to tell it from an impostor");
			assertTrue(thrown.getMessage().contains("no credential")
					&& thrown.getMessage().contains("HTTP 200"),
					"the failure must name what was not refused AND what the listener actually "
					+ "answered — a timeout and a served 200 are both refusals of the start, and "
					+ "telling an operator their server served a chart request when it never "
					+ "answered sends them after the wrong thing: " + thrown.getMessage());
		}
	}

	@Test
	public void aListenerThatRejectsThisModulesOwnKeyIsNotReadiness() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.refusingEveryKey()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireListenerMayBeServed(
							listener.endpoint(), CLIENT,
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
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(),
					CLIENT, ProcessHandle.current()::isAlive);
		}
	}

	/**
	 * The child's last output is captured by the DRAIN thread and read by the thread that holds the
	 * engine monitor for the whole of a start. Guarded by the engine, the drain thread blocked on
	 * its first line and the failure message always read "(none captured)" — so this drives the
	 * real methods with the monitor held exactly as {@code waitForServerReady} holds it, and it
	 * enters the path where production enters it: {@code beginServerOutputCapture} publishes the
	 * deque, the drain thread writes THAT deque, and the failure message reads it.
	 */
	@Test
	public void theChildsLastOutputIsCapturedWhileTheEngineMonitorIsHeld() throws Exception {
		LocalLlmEngine engine = new LocalLlmEngine();
		java.util.Deque<String> capture = engine.beginServerOutputCapture();
		java.util.concurrent.CountDownLatch appended = new java.util.concurrent.CountDownLatch(1);
		String quoted;

		synchronized (engine) {
			Thread drain = new Thread(() -> {
				LocalLlmEngine.rememberServerOutput(capture, "error: invalid argument: --no-webui");
				appended.countDown();
			}, "test-drain");
			drain.setDaemon(true);
			drain.start();

			assertTrue(appended.await(5, java.util.concurrent.TimeUnit.SECONDS),
					"the drain thread must be able to record a line while the engine monitor is "
					+ "held — every entry point is synchronized and the monitor is held unbroken "
					+ "across the whole start, so a ring guarded by the engine can never append "
					+ "before the failure message reads it");
			quoted = engine.lastServerOutput();
		}

		assertTrue(quoted.contains("--no-webui"),
				"the startup-failure message must quote what the child actually said; an argument "
				+ "this build does not accept is the shape #445 introduced, and it is printed "
				+ "directly rather than through the log system that --log-disable silences: "
				+ quoted);
	}

	/**
	 * Each start captures into its OWN deque. The predecessor's drain thread is never joined, so a
	 * line it had not yet read when the next start began must not be quoted as the new child's —
	 * which is what a shared-and-cleared deque did.
	 */
	@Test
	public void alineFromThePreviousStartIsNotQuotedAsTheNewChilds() {
		LocalLlmEngine engine = new LocalLlmEngine();

		java.util.Deque<String> firstStart = engine.beginServerOutputCapture();
		LocalLlmEngine.rememberServerOutput(firstStart, "error: from the FIRST child");
		java.util.Deque<String> secondStart = engine.beginServerOutputCapture();
		// The predecessor's drain thread, still running, reads one more line out of the old pipe.
		LocalLlmEngine.rememberServerOutput(firstStart, "error: also from the FIRST child");

		assertNotEquals(firstStart, secondStart, "each start must get its own deque");
		assertEquals("(none captured)", engine.lastServerOutput(),
				"the new start has captured nothing yet, so its failure message must say so "
				+ "rather than quote the dead child's words as this one's");

		LocalLlmEngine.rememberServerOutput(secondStart, "error: from the SECOND child");
		assertTrue(engine.lastServerOutput().contains("SECOND"), "and then quote its own");
		assertFalse(engine.lastServerOutput().contains("FIRST"),
				"never the predecessor's: " + engine.lastServerOutput());
	}

	@Test
	public void aListenerRefusingWith403RatherThan401IsStillReadiness() throws IOException {
		try (KeyDemandingListener listener = KeyDemandingListener.refusingWith403()) {
			// 403 is a server refusing the credential just as squarely as 401 — a fronting proxy's
			// answer. Requiring exactly 401 would refuse the start of a server that DOES demand a
			// credential, which is the opposite of what this gate is for.
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(), CLIENT,
					ProcessHandle.current()::isAlive);
		}
	}

	/**
	 * The statuses {@code refusesCredentials} names, spelled as LITERALS. Both readiness legs and
	 * three other sites turn on it, and none of them can be driven against a live llama-server
	 * here — so the members are written out rather than derived, the way
	 * {@code ReferenceProseFidelityTest} spells the shared terminator set.
	 */
	@Test
	public void aStatusThatIsNotARefusalIsNotTreatedAsOne() {
		assertTrue(LlamaServerEndpoint.refusesCredentials(401), "401 is a refused credential");
		assertTrue(LlamaServerEndpoint.refusesCredentials(403),
				"so is 403 — a build or a fronting proxy refusing that way is still refusing");
		assertFalse(LlamaServerEndpoint.refusesCredentials(200),
				"a SERVED request is what readiness refuses the START over, not a refusal");
		assertFalse(LlamaServerEndpoint.refusesCredentials(404),
				"a route this build does not serve says nothing about the credential");
		assertFalse(LlamaServerEndpoint.refusesCredentials(-1),
				"a probe that could not complete establishes NO refusal — reading it as one would "
				+ "invert the unauthenticated leg from fail-closed to fail-open, and a listener "
				+ "the module cannot probe at all would then be served a patient's chart");
	}

	/**
	 * The negative control's own direction. A listener that answers nothing at all must REFUSE the
	 * start, not pass it: {@code unauthenticatedProbeStatus} returns -1 for a probe that could not
	 * complete, and only {@code refusesCredentials} refusing to name -1 keeps that fail-closed.
	 */
	@Test
	public void aListenerThatCannotBeProbedAtAllIsNotReadiness() throws IOException {
		int deadPort;
		try (ServerSocket reserved = new ServerSocket()) {
			reserved.setReuseAddress(true);
			reserved.bind(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0));
			deadPort = reserved.getLocalPort();
		}
		LlamaServerEndpoint nobodyThere = LlamaServerEndpoint.open(deadPort);

		APIException thrown = assertThrows(APIException.class,
				() -> LocalLlmEngine.requireListenerMayBeServed(nobodyThere, CLIENT,
						ProcessHandle.current()::isAlive),
				"a listener that cannot be probed has not refused anything, so readiness must "
				+ "refuse the start rather than take silence for a credential check");
		assertTrue(thrown.getMessage().contains("could not be completed"),
				"and say that is what happened: " + thrown.getMessage());
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

		private volatile int refusalStatus = 401;

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

		/** A listener that refuses a credential with 403 rather than 401 — a fronting proxy's
		 *  answer, and the shape {@code refusesCredentials} was widened to admit. */
		static KeyDemandingListener refusingWith403() throws IOException {
			KeyDemandingListener listener = start(true, true);
			listener.refusalStatus = 403;
			return listener;
		}

		/** A listener that answers everything 200 with no credential — the shape of a naive
		 *  impostor, and of a build that ignored {@link LlamaServerEndpoint#API_KEY_ENV}. */
		static KeyDemandingListener demandingNoKeyAtAll() throws IOException {
			return start(true, true, false);
		}

		private static KeyDemandingListener start(boolean acceptTheModulesKey, boolean servesProps)
				throws IOException {
			return start(acceptTheModulesKey, servesProps, true);
		}

		private static KeyDemandingListener start(boolean acceptTheModulesKey, boolean servesProps,
				boolean demandsAKey) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0), 0);
			LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(server.getAddress().getPort());
			KeyDemandingListener listener = new KeyDemandingListener(server, endpoint);
			String expected = expectedBearer(endpoint);
			server.createContext("/", exchange -> {
				String presented = exchange.getRequestHeaders().getFirst("Authorization");
				boolean keyed = !demandsAKey || (acceptTheModulesKey && expected.equals(presented));
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
				respond(exchange, keyed ? 200 : listener.refusalStatus,
						keyed ? "{}" : "{\"error\":\"unauthorized\"}");
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
	 * A loopback LISTENING port whose socket the OS is still holding after the listener closed —
	 * the state the previous llama-server child leaves behind on the restart and crash paths.
	 * Built the way a restart actually produces it: a connection is accepted ON that port and the
	 * SERVER side is closed first, which is what puts the listening port's own socket into
	 * {@code TIME_WAIT}. (Closing the CLIENT first instead leaves the client's ephemeral port in
	 * TIME_WAIT, which is a different port and not the shape this check meets.)
	 */
	private static int portLeftInTimeWait() throws IOException {
		int port;
		try (ServerSocket acceptor = new ServerSocket()) {
			acceptor.setReuseAddress(true);
			acceptor.bind(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0));
			port = acceptor.getLocalPort();
			try (java.net.Socket client = new java.net.Socket()) {
				client.connect(new InetSocketAddress(
						InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), port));
				java.net.Socket accepted = acceptor.accept();
				accepted.close();
			}
		}
		return port;
	}

}
