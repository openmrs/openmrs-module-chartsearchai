/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/**
 * Tests for the streaming search endpoint controller structure.
 * Verifies that the controller writes SSE events directly to the response
 * on the request thread, and shares no auth state with any other thread.
 *
 * <p>"On the request thread" rather than "without background threads": the SSE keep-alive runs a
 * timer that writes comment frames, and what matters is that no thread but the request thread does
 * OpenMRS work — see {@link #streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads}, which
 * states and enforces that scope. Letting that timer in added a second thing to guard, so the same
 * method also pins the mechanism that keeps its writes inside the request: the stop flag must be
 * read, not merely set.</p>
 */
public class ChartSearchAiStreamingTest {

	@Test
	public void controller_shouldNotStoreUserContextAsField() {
		for (Field field : ChartSearchAiRestController.class.getDeclaredFields()) {
			assertTrue(
					!field.getType().getName().contains("UserContext"),
					"Controller must not store UserContext as a field");
		}
	}

	@Test
	public void searchStreamMethod_shouldExist() throws NoSuchMethodException {
		assertNotNull(
				ChartSearchAiRestController.class.getMethod("searchStream",
						java.util.Map.class, javax.servlet.http.HttpServletResponse.class),
				"searchStream method should exist");
	}

	/**
	 * No thread but the request thread may do OpenMRS work, and no authentication state may be shared
	 * across threads.
	 *
	 * <p>This test forbade {@code new Thread(} outright until the SSE keep-alive was added. The ban
	 * was narrowed rather than dropped, because the reason for it is the one the other three
	 * assertions here name: OpenMRS binds authentication to the request thread, so work done off that
	 * thread either loses its {@code Context} or shares it unsafely. A timer that only writes bytes to
	 * the response carries none of that risk, and forbidding it cost the streaming endpoint its only
	 * defence against a reverse proxy's read timeout — measured on the chartsearchai.openmrs.org demo
	 * 2026-08-19, a query whose first output lands after that window is closed having delivered ZERO
	 * bytes, with no error event and no audit row: silent, and indistinguishable from a hung origin.
	 * Cloudflare cuts at ~120s, stock nginx at 60s.</p>
	 *
	 * <p>So the scope is now stated instead of assumed: every thread this controller creates must be
	 * the keep-alive's, {@code SseKeepAlive} must not touch {@code Context}, and its {@code write}
	 * must READ the stop flag that {@code stop} sets. A thread that does OpenMRS work still fails
	 * here, which is what the original assertion was protecting.</p>
	 *
	 * <p>The stop-flag assertion is a text check rather than a behavioural one because the property
	 * cannot be reddened behaviourally: with the read gone, a task parked on the {@code out} monitor
	 * during the terminal write is free to write after {@code streamAnswer} has returned, but whether
	 * it does turns on a monitor race against {@code stop()} that no test can force, so a behavioural
	 * case would fail only probabilistically. Before this assertion existed, deleting
	 * {@code if (stopped)} from {@code SseKeepAlive.write} left the omod suite green at 81/81 — the
	 * flag's write half pinned by the region canary and its read half by nothing, which is exactly the
	 * silent weakening that canary's own message names.</p>
	 */
	@Test
	public void streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads() throws Exception {
		String source = controllerSource();

		for (int at = source.indexOf("new Thread("); at >= 0; at = source.indexOf("new Thread(", at + 1)) {
			String creation = source.substring(at, Math.min(source.length(), at + 120));
			assertTrue(creation.contains("\"chartsearchai-sse-keepalive\""),
					"the only thread this controller may create is the SSE keep-alive's, because "
							+ "OpenMRS authentication is bound to the request thread; found: " + creation);
		}
		String keepAlive = nestedClassBody(source, "SseKeepAlive");
		assertTrue(keepAlive.contains("stopped = true"),
				"the extracted class body must reach SseKeepAlive.stop, or the region is short and the "
						+ "assertions below are passing on text they never read — a guard that weakens "
						+ "in silence is worse than no guard");
		assertTrue(keepAlive.replaceAll("\\s+", "").contains("synchronized(out){stopped=true;"),
				"stop() must set the flag INSIDE the out monitor, and volatile is not an alternative: "
						+ "taking the monitor is also what waits for a keep-alive write already in flight, "
						+ "which is what lets streamAnswer's final flush run without the lock. Written "
						+ "outside it the field is a plain race as well, so a task can read a stale false "
						+ "and write after streamAnswer has returned");
		assertTrue(keepAlive.contains("if (stopped)"),
				"stop() setting the flag is worthless unless write() reads it: without the read, a task "
						+ "parked on the monitor writes after streamAnswer returns, into a response the "
						+ "container may already have recycled, and shutdownNow cannot stop it because a "
						+ "thread blocked entering a synchronized block ignores interrupt");
		assertTrue(!keepAlive.contains("Context."),
				"the keep-alive thread must write bytes and nothing else — reading Context off it is "
						+ "exactly the unsafe sharing this test exists to prevent");

		assertTrue(!source.contains("import org.springframework.web.servlet.mvc.method.annotation.SseEmitter"),
				"Streaming must not import SseEmitter");
		assertTrue(!source.contains("addProxyPrivilege"),
				"Streaming must not use proxy privileges");
		assertTrue(!source.contains("setUserContext"),
				"Must not share UserContext across threads");
	}

	/**
	 * @return the body of the named nested class, brace-matched from its declaration, so an assertion
	 *         about that class cannot be satisfied or broken by code outside it
	 */
	private static String nestedClassBody(String source, String simpleName) {
		int declaration = source.indexOf("class " + simpleName);
		assertTrue(declaration >= 0, "nested class " + simpleName + " must exist in the controller");
		int open = source.indexOf('{', declaration);
		assertTrue(open >= 0, "nested class " + simpleName + " must have a body");
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return source.substring(open, i + 1);
				}
			}
		}
		throw new AssertionError("unbalanced braces after " + simpleName);
	}

	@Test
	public void authorizationCheck_shouldHappenBeforeStreaming() throws Exception {
		String source = controllerSource();

		int streamMethodIdx = source.indexOf("public void searchStream");
		assertTrue(streamMethodIdx >= 0, "searchStream method must exist");

		int requirePriv = source.indexOf(
				"Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)",
				streamMethodIdx);
		int canAccess = source.indexOf("patientAccessCheck.canAccess(",
				streamMethodIdx);
		int searchStreaming = source.indexOf("searchStreaming(", streamMethodIdx);

		assertTrue(requirePriv >= 0, "Must check PRIV_QUERY_PATIENT_DATA");
		assertTrue(canAccess >= 0, "Must check patient access");
		assertTrue(searchStreaming >= 0, "Must call searchStreaming");

		assertTrue(requirePriv < searchStreaming,
				"Privilege check must happen before streaming");
		assertTrue(canAccess < searchStreaming,
				"Patient access check must happen before streaming");
	}

	/**
	 * The production entry point must pass {@code KEEP_ALIVE_INTERVAL_MS} to {@code streamAnswer}, not
	 * a literal.
	 *
	 * <p>{@code ChartSearchAiStreamKeepAliveTest} bounds that constant reflectively, and its javadoc
	 * claims to bound "the interval PRODUCTION uses" — true only while the five-argument overload
	 * really delegates with it. Swap it there for a literal and the bound still passes, against a
	 * constant nothing reads, so the shipped interval is unbounded again with the suite green and that
	 * other test's stated scope quietly false. The halves sit in different classes because only this
	 * one reads the source, and neither is sufficient alone.</p>
	 *
	 * <p>Whitespace-stripped so reformatting cannot redden it, and matched on the constant as a
	 * trailing argument rather than on the full call, which would couple the guard to a parameter
	 * name. The {@code @link} references to the constant in the controller's own javadoc do not match:
	 * none is preceded by a comma.</p>
	 */
	@Test
	public void theProductionEntryPointPassesTheKeepAliveConstantAndNotALiteral() throws Exception {
		String source = controllerSource();

		assertTrue(source.replaceAll("\\s+", "").contains(",KEEP_ALIVE_INTERVAL_MS)"),
				"the keep-alive interval must reach streamAnswer as the constant, so the reflective bound "
						+ "in ChartSearchAiStreamKeepAliveTest bounds what production actually uses; a "
						+ "literal here leaves that test checking a constant nothing reads");
	}

	/**
	 * Reads the controller's production source as UTF-8.
	 *
	 * <p>One reader for all three source-scanning tests, which each had their own spelling of it.
	 * The charset is explicit because the file contains non-ASCII characters and
	 * {@code new String(byte[])} decodes with the platform default: every needle asserted here is
	 * ASCII, so a wrong default would not break them today, and stating the charset is what keeps
	 * that true of a needle someone adds later.</p>
	 *
	 * @return the whole file
	 */
	private static String controllerSource() throws java.io.IOException {
		return new String(java.nio.file.Files.readAllBytes(resolveSourceFile().toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Locates the controller's production source, which every source-scanning assertion in this class
	 * reads.
	 *
	 * <p>A file it cannot find FAILS rather than skips. It skipped until now, through
	 * {@code Assumptions.assumeTrue}, and that is the same defect as the short-region one above, one
	 * level further up: measured by pointing the path below at a directory that does not exist, omod
	 * built GREEN with two tests skipped, so every assertion in this class stopped running and nothing
	 * said so. Stated as "every" rather than listed, because a list here drifts the moment an
	 * assertion is added — as it already had.</p>
	 *
	 * <p>The skip guarded nothing it could not have failed on instead. omod publishes no test-jar, so
	 * these tests only ever run against this source tree, and the file goes missing only under a
	 * working directory or a module layout this resolver has not been taught — worth a red build and a
	 * message naming both, not a green one. That is the opposite of the endpoint-reachability and
	 * opt-in-property assumptions elsewhere in the suite, which skip because the thing they need
	 * genuinely may not exist.</p>
	 *
	 * @return the source file, which exists
	 */
	private static java.io.File resolveSourceFile() {
		String sourceFile = "omod/src/main/java/org/openmrs/module/chartsearchai"
				+ "/web/rest/ChartSearchAiRestController.java";
		java.io.File file = new java.io.File(sourceFile);
		if (!file.exists()) {
			file = new java.io.File("../" + sourceFile);
		}
		assertTrue(file.exists(),
				"the controller source must be readable or every guard in this class asserts nothing: "
						+ "not found at " + file.getAbsolutePath() + ", working directory "
						+ new java.io.File(".").getAbsolutePath() + " — teach this resolver the layout "
						+ "rather than letting the guards skip, which is green and silent");
		return file;
	}
}
