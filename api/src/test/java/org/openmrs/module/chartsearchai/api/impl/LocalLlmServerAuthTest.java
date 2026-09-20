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
 * are recorded in {@code docs/adr.md} Decision 107, not here.
 */
public class LocalLlmServerAuthTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/**
	 * One client for the whole class, and the PRODUCTION one: each fresh
	 * {@code HttpClient.newHttpClient()} starts a selector thread and workers that are not
	 * reclaimed until GC, and this is the only test class here that opens sockets — six of them
	 * left ~20 daemon threads and ~25 descriptors per run. Taking the engine's own client also
	 * keeps this class out of {@code ArchitectureGuardTest.onlyOneClientTalksToTheLocalServer},
	 * which is the rule rather than an exception to it.
	 */
	private static final HttpClient CLIENT = new LocalLlmEngine().getHttpClient();

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

	/**
	 * Nothing this module sends to its own subprocess may be routed through a proxy. Measured with
	 * a control: with {@code http.proxyHost} set and {@code http.nonProxyHosts} emptied, the
	 * production {@code /health} request reached the proxy rather than the server and carried
	 * {@code Authorization: Bearer <secret>} — the credential handed to a third party, on the same
	 * client the completions POST uses to send the chart.
	 *
	 * <p>It sets those two properties, because asking the selector in a clean JVM cannot tell the
	 * fix from the leak: {@code ProxySelector.getDefault()} answers {@code [DIRECT]} there, and
	 * {@code List.of(Proxy.NO_PROXY)} equals {@code [DIRECT]} — so an earlier form of this test
	 * passed when the client was handed a property-honouring selector, which is the likelier
	 * regression than deleting the call. Restored in a {@code finally}, the shape
	 * {@link #theProbeIsNotRoutedThroughAConfiguredProxy} uses and which was measured to restore
	 * completely.
	 */
	@Test
	public void theClientTalkingToTheLocalServerUsesNoProxy() {
		String host = System.setProperty("http.proxyHost", "192.0.2.1");
		String port = System.setProperty("http.proxyPort", "3128");
		String skip = System.setProperty("http.nonProxyHosts", "");
		try {
			HttpClient client = new LocalLlmEngine().getHttpClient();

			assertTrue(client.proxy().isPresent(),
					"the client must carry an explicit proxy selector; with none it falls back to "
					+ "ProxySelector.getDefault(), which honours http.proxyHost");
			assertEquals(java.util.List.of(java.net.Proxy.NO_PROXY),
					client.proxy().get().select(java.net.URI.create(
							"http://" + LlamaServerEndpoint.LOOPBACK_HOST + ":18085/health")),
					"and that selector must choose NO_PROXY for the local server even with a proxy "
					+ "configured and loopback not excluded — which is the configuration measured "
					+ "to send this module's own key, and its patients' charts, to the proxy");
		}
		finally {
			restore("http.proxyHost", host);
			restore("http.proxyPort", port);
			restore("http.nonProxyHosts", skip);
		}
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

	/**
	 * The two halves of the channel are the SAME secret, asserted against each other. Nothing else
	 * here does that: the environment tests read what {@code handOverTo} wrote and stop there,
	 * while the test above derives what its listener demands FROM the endpoint, so whatever
	 * {@code request} sends is accepted by construction. A bearer built from anything other than
	 * the field is therefore invisible to both — measured, with {@code "Bearer " + API_KEY_ENV} on
	 * the wire (the constant sits a hundred lines above the field in the same small class) the
	 * whole build stayed green, while what shipped would present a credential the child does not
	 * hold: every start refused at readiness's keyed leg, on the default engine, re-paid per query.
	 *
	 * <p>It spells the header out rather than only looking for the secret inside it, which
	 * {@code expectedBearer} deliberately does not do — the difference is that this test's subject
	 * IS the relation between the two, and a containment check is vacuous if the secret is ever
	 * empty.
	 */
	@Test
	public void theBearerOnTheWireIsTheSecretTheChildWasHanded() {
		LlamaServerEndpoint endpoint = LlamaServerEndpoint.open(9999);
		ProcessBuilder child = new ProcessBuilder("/bin/llama-server");

		endpoint.handOverTo(child);
		String handedToTheChild = child.environment().get(LlamaServerEndpoint.API_KEY_ENV);
		String sentOnTheWire = endpoint.request(endpoint.completionsUrl(), TIMEOUT).GET().build()
				.headers().firstValue("Authorization").orElse("");

		assertEquals("Bearer " + handedToTheChild, sentOnTheWire,
				"the credential this endpoint puts on the wire must be the one it handed the same "
				+ "start's child: they are the two ends of one shared secret, and a module "
				+ "presenting anything else is refused by its own server on every query");
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
	 * review round measured shipping twice. A listener whose accept queue is full answers neither
	 * with a connection nor with a refusal — the probe times out, which establishes no refusal and
	 * must refuse the start.
	 *
	 * <p>The queue is SATURATED by connecting until a connect of the probe's own shape fails,
	 * rather than by assuming {@code listen(1)} admits one, because that depth is an OS parameter.
	 * macOS admits exactly one, measured 100 times; Linux compares
	 * {@code sk_ack_backlog > sk_max_ack_backlog} and so admits backlog+1, dropping the overflow
	 * SYN silently rather than refusing it. So on Linux — which CI runs — a hard-coded single
	 * filler would leave the queue NOT full, the probe would connect, and this test would redden
	 * accusing production of reporting "already listening": the branch it is not about. That red
	 * is DERIVED from the kernel predicate quoted above and from the macOS count, not observed
	 * here; the one-filler form is green on this host, which is exactly why the loop is not
	 * optional.
	 */
	@Test
	public void aPortHeldByAListenerThatAcceptsNothingFailsTheStart() throws IOException {
		InetAddress loopback = InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST);
		List<java.net.Socket> fillers = new ArrayList<>();
		try (ServerSocket blackHole = new ServerSocket(0, 1, loopback)) {
			int held = blackHole.getLocalPort();
			boolean saturated = false;
			for (int attempt = 0; attempt < 256 && !saturated; attempt++) {
				java.net.Socket filler = new java.net.Socket(java.net.Proxy.NO_PROXY);
				try {
					filler.connect(new InetSocketAddress(loopback, held), 250);
					fillers.add(filler);
				}
				catch (IOException queueIsFull) {
					filler.close();
					saturated = true;
				}
			}
			assertTrue(saturated,
					"could not fill the accept queue of a backlog-1 listener in 256 connects, so "
					+ "this test never reached the state it is about — a fixture that asserts an "
					+ "outcome it did not produce is the failure mode this loop exists to remove");

			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireLoopbackPortFree(held),
					"a probe that neither connects nor is refused has established nothing, so "
					+ "the start must be refused — reading it as a free port is how this check "
					+ "fails OPEN, which is the one direction it exists to prevent");
			assertTrue(thrown.getMessage().contains("Could not establish"),
					"and say that is what happened, rather than claiming a listener was found: "
					+ thrown.getMessage());
		}
		finally {
			for (java.net.Socket filler : fillers) {
				filler.close();
			}
		}
	}

	/**
	 * The probe is built with {@code Proxy.NO_PROXY}, and nothing pinned that either: reverting it
	 * to {@code new Socket()} left the full suite green. The no-arg constructor is proxy-aware, so
	 * a JVM with {@code socksProxyHost} set would route a LOOPBACK probe through a proxy and answer
	 * about the proxy rather than about the port.
	 */
	@Test
	public void theProbeIsNotRoutedThroughAConfiguredProxy() throws IOException {
		int free;
		try (ServerSocket reserved = new ServerSocket()) {
			reserved.setReuseAddress(true);
			reserved.bind(new InetSocketAddress(
					InetAddress.getByName(LlamaServerEndpoint.LOOPBACK_HOST), 0));
			free = reserved.getLocalPort();
		}
		// 192.0.2.0/24 is TEST-NET-1 and routes nowhere, so a proxied probe cannot succeed.
		String host = System.setProperty("socksProxyHost", "192.0.2.1");
		String port = System.setProperty("socksProxyPort", "1080");
		String skip = System.setProperty("socksNonProxyHosts", "");
		try {
			LocalLlmEngine.requireLoopbackPortFree(free);
		}
		finally {
			restore("socksProxyHost", host);
			restore("socksProxyPort", port);
			restore("socksNonProxyHosts", skip);
		}
	}

	private static void restore(String key, String previous) {
		if (previous == null) {
			System.clearProperty(key);
		}
		else {
			System.setProperty(key, previous);
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
				"a 256-bit secret is exactly 43 base64url characters; fewer is a key a local "
				+ "process can search, and the environment it travels in does not make it safe, "
				+ "while more means the encoding changed and this assertion is what says so: "
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
	 * The positive control, without which the refusals below could all pass over a check that
	 * refuses everything. It is also, read honestly, part of the residue: this listener is NOT the
	 * spawned child, and it is accepted — the probes cannot tell, and no bearer token could. What
	 * the gate has instead is the bind window, which this control satisfies by leaving the child
	 * alive right through it; a mimic that won the port would have killed the child and be refused
	 * on that leg, which is
	 * {@link #aHealthyReplyInsideTheChildsBindWindowIsRefusedRatherThanAdopted}. What is left is a
	 * mimic that takes the port WITHOUT the child noticing — a host slow enough that the window
	 * closes before the child has tried, or a build that survives a failed bind — and
	 * {@code docs/adr.md} Decision 107 states that rather than claiming otherwise.
	 */
	@Test
	public void aHealthyListenerEnforcingTheKeyBesideALiveChildIsReadiness() throws Exception {
		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(),
					CLIENT, ProcessHandle.current()::isAlive, System.nanoTime());
		}
	}

	@Test
	public void aHealthyListenerIsNotReadinessWhenTheSpawnedChildHasExited() throws IOException {
		BooleanSupplier exitedChild = livenessOfAnExitedProcess();

		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireListenerMayBeServed(
							listener.endpoint(), CLIENT, exitedChild,
							System.nanoTime()),
					"a healthy answer beside a child that is already gone is another process "
					+ "answering, and must fail the start rather than leave a foreign listener in "
					+ "service. This leg is the cheap one: it catches the case the module can see "
					+ "without waiting, and says so, rather than reporting the stranger's "
					+ "credentials");
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
							ProcessHandle.current()::isAlive, System.nanoTime()),
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
							ProcessHandle.current()::isAlive, System.nanoTime()),
					"a server that rejects this start's key must fail the start, not surface as a "
					+ "401 on a clinician's query");
			assertTrue(thrown.getMessage().contains("rejected"),
					"the failure must say the key was rejected: " + thrown.getMessage());
		}
	}

	@Test
	public void aListenerWithNoPropsRouteIsStillReadiness() throws Exception {
		try (KeyDemandingListener listener = KeyDemandingListener.withoutAPropsRoute()) {
			// The key leg asks whether the key was REFUSED, so a 404 — this build does not serve
			// that route — must not fail the start. Requiring 200 there would refuse a build for
			// something that says nothing about its authentication, and the unauthenticated leg
			// still proves the key is in force.
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(),
					CLIENT, ProcessHandle.current()::isAlive, System.nanoTime());
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
	public void aListenerRefusingWith403RatherThan401IsStillReadiness() throws Exception {
		try (KeyDemandingListener listener = KeyDemandingListener.refusingWith403()) {
			// 403 is a server refusing the credential just as squarely as 401 — a fronting proxy's
			// answer. Requiring exactly 401 would refuse the start of a server that DOES demand a
			// credential, which is the opposite of what this gate is for.
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(), CLIENT,
					ProcessHandle.current()::isAlive, System.nanoTime());
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
						ProcessHandle.current()::isAlive, System.nanoTime()),
				"a listener that cannot be probed has not refused anything, so readiness must "
				+ "refuse the start rather than take silence for a credential check");
		assertTrue(thrown.getMessage().contains("could not be completed"),
				"and say that is what happened: " + thrown.getMessage());
	}

	// ---- the child must outlive its own bind window ----

	/**
	 * The launch-window race, driven in the order production drives it. An impostor that binds the
	 * port in the instant after the pre-launch port check returns answers the FIRST health poll —
	 * which production sends a few milliseconds after {@code pb.start()} — while the doomed child
	 * is still alive and still pre-bind, so a liveness check taken at that reply reads TRUE. The
	 * child then loses its bind and exits. Readiness must not have committed by then.
	 *
	 * <p>Everything here is real: a {@link HttpServer} impostor that demands a bearer and answers
	 * {@code {"status":"ok"}} publicly on {@code /health} (which satisfies the other two legs — a
	 * bearer cannot authenticate the server to the client), a real OS child process, and that
	 * child's real exit. It is killed rather than made to exit on its own so that WHEN it stops
	 * being alive is the one thing the test controls; the module reads {@code isAlive} and never
	 * the exit code on this path, so the two are the same observation to it.
	 */
	@Test
	public void aHealthyReplyInsideTheChildsBindWindowIsRefusedRatherThanAdopted() throws Exception {
		Process child = launchARealChildProcess();
		java.util.concurrent.ScheduledExecutorService losesTheBind =
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		try (KeyDemandingListener impostor = KeyDemandingListener.start()) {
			long launchedAt = System.nanoTime();
			assertTrue(child.isAlive(), "the child must still be alive as readiness begins, which "
					+ "is the whole case: a gate that reads liveness only here cannot tell this "
					+ "impostor from the server");
			// 250 ms is a LITERAL and not derived from the production constant: it is past the
			// 0.138 s bind and the ~0.06 s exit Decision 107 rows 7 and 9 measured, so it is when
			// a child that lost this port really stops being alive. Deriving it from the constant
			// would let the constant be set to zero and leave this green.
			losesTheBind.schedule(child::destroyForcibly, 250,
					java.util.concurrent.TimeUnit.MILLISECONDS);

			APIException thrown = assertThrows(APIException.class,
					() -> LocalLlmEngine.requireListenerMayBeServed(
							impostor.endpoint(), CLIENT, child::isAlive, launchedAt),
					"a listener that answered healthy while the child was still pre-bind must not "
					+ "be adopted on that reply: the child loses the bind moments later, and by "
					+ "then this start has already been handed the system prompt and the chart");
			assertTrue(thrown.getMessage().contains("no longer alive"),
					"and the failure must name the observation that refused it — the child was "
					+ "gone once its bind attempt had been decided: " + thrown.getMessage());
		}
		finally {
			losesTheBind.shutdownNow();
			child.destroyForcibly();
		}
	}

	/**
	 * The other side of the same leg: a healthy reply that arrives well after the bind window —
	 * which is every start that actually loaded a model, the window being half a second and a
	 * model load seconds — must be adopted without waiting any further. The wait is counted from
	 * the LAUNCH for exactly this reason, and a leg that slept unconditionally instead would put
	 * its whole delay on every start.
	 *
	 * <p>Measured after a warmup call, because the first HTTP send in a JVM pays the client's
	 * one-time initialisation — measured in the hundreds of milliseconds — and that is not the
	 * wait this asserts.
	 */
	@Test
	public void aHealthyReplyLongAfterTheBindWindowIsAdoptedWithoutWaitingFurther()
			throws Exception {
		try (KeyDemandingListener listener = KeyDemandingListener.start()) {
			long longSinceLaunched = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS
					.toNanos(10 * LocalLlmEngine.CHILD_BIND_SETTLE_MS);
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(), CLIENT,
					ProcessHandle.current()::isAlive, longSinceLaunched);

			long before = System.nanoTime();
			LocalLlmEngine.requireListenerMayBeServed(listener.endpoint(), CLIENT,
					ProcessHandle.current()::isAlive, longSinceLaunched);
			long spent = java.util.concurrent.TimeUnit.NANOSECONDS
					.toMillis(System.nanoTime() - before);

			// 250 ms is a LITERAL, not a fraction of the production constant: that constant is
			// the thing this asserts is NOT being spent, so a bound derived from it moves
			// whenever it does — measured, with the constant set to 0 the derived form asserted
			// "2 < 0" and failed for no reason of its own. Two warm loopback probes were measured
			// at 2 ms, so this leaves two orders of magnitude of headroom and still catches a
			// wait paid unconditionally.
			assertTrue(spent < 250,
					"readiness must spend nothing on the bind window once it has passed; two "
					+ "loopback probes cost single-digit milliseconds and this took " + spent
					+ " ms, so the wait is being paid unconditionally rather than counted from "
					+ "the launch");
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

	/**
	 * A real OS process that stays alive until the test stops it: this JVM's own launcher running
	 * {@link SleepingChild} off the very class directory the suite is running from. Real because
	 * the only thing the module ever asks about its child is whether the OS still has it, and a
	 * stand-in for that would stand in for the thing these tests exist to pin. {@code java.home}
	 * is contractually present, the same assumption {@link #livenessOfAnExitedProcess} makes.
	 */
	private static Process launchARealChildProcess() throws IOException {
		Path launcher = javaLauncher();
		Assumptions.assumeTrue(launcher != null, "no java launcher under java.home");
		return new ProcessBuilder(launcher.toString(), "-cp", ownClassDirectory(),
				SleepingChild.class.getName())
						.redirectErrorStream(true)
						.redirectOutput(ProcessBuilder.Redirect.DISCARD)
						.start();
	}

	private static String ownClassDirectory() {
		try {
			return Paths.get(LocalLlmServerAuthTest.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI()).toString();
		}
		catch (java.net.URISyntaxException e) {
			throw new IllegalStateException("cannot locate this class's own directory", e);
		}
	}

	/**
	 * Does nothing but stay alive, bounded so that a test JVM killed mid-run leaves nothing behind
	 * for long. Public with a {@code main} because it is launched as a separate OS process.
	 */
	public static class SleepingChild {

		public static void main(String[] args) throws InterruptedException {
			Thread.sleep(Duration.ofSeconds(30).toMillis());
		}
	}

}
