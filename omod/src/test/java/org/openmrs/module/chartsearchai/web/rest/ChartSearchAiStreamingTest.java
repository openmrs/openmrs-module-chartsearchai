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
 * states and enforces that scope.</p>
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
	 * the keep-alive's, and {@code SseKeepAlive} must not touch {@code Context}. A thread that does
	 * OpenMRS work still fails here, which is what the original assertion was protecting.</p>
	 */
	@Test
	public void streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads() throws Exception {
		java.io.File file = resolveSourceFile();
		String source = new String(java.nio.file.Files.readAllBytes(file.toPath()));

		for (int at = source.indexOf("new Thread("); at >= 0; at = source.indexOf("new Thread(", at + 1)) {
			String creation = source.substring(at, Math.min(source.length(), at + 120));
			assertTrue(creation.contains("\"chartsearchai-sse-keepalive\""),
					"the only thread this controller may create is the SSE keep-alive's, because "
							+ "OpenMRS authentication is bound to the request thread; found: " + creation);
		}
		assertTrue(!nestedClassBody(source, "SseKeepAlive").contains("Context."),
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
		java.io.File file = resolveSourceFile();
		String source = new String(java.nio.file.Files.readAllBytes(file.toPath()));

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

	private static java.io.File resolveSourceFile() {
		String sourceFile = "omod/src/main/java/org/openmrs/module/chartsearchai"
				+ "/web/rest/ChartSearchAiRestController.java";
		java.io.File file = new java.io.File(sourceFile);
		if (!file.exists()) {
			file = new java.io.File("../" + sourceFile);
		}
		org.junit.jupiter.api.Assumptions.assumeTrue(file.exists(),
				"Source file not found at " + file.getAbsolutePath()
				+ " — skipping source-based test");
		return file;
	}
}
