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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p>Threads and auth state are not the whole of it, and saying so is the point: this is the only
 * test class that reads the controller's own SOURCE, so it is also where the keep-alive's
 * source-level facts live: that the production entry point passes the interval CONSTANT, and that
 * the timer is scheduled at a fixed DELAY. Neither is about threads, and a javadoc naming only
 * threads and auth sends the next reader looking elsewhere for them.</p>
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
	 * <p>So the scope is now stated instead of assumed: a thread must be created here at all, every
	 * {@code new Thread(} and every {@code Executors} call in this controller must be the keep-alive's,
	 * {@code SseKeepAlive} must not touch {@code Context}, {@code Context} must stay QUALIFIED
	 * throughout this file, and {@code write} must READ the stop flag that {@code stop} sets. A thread
	 * created either of those two ways and doing OpenMRS work fails here, which is what the original
	 * assertion was protecting.</p>
	 *
	 * <p>Those two spellings are named rather than "every thread this controller creates" because they
	 * are what the scan actually reaches, and each is decided by WHERE the creation sits. Both halves of
	 * that were missing once and each was measured passing the whole suite. A factory-less
	 * {@code Executors.newSingleThreadExecutor} in this class running
	 * {@code Context.getAuthenticatedUser} went green because the default thread factory writes no
	 * {@code new Thread(} for the loop to find; and a raw
	 * {@code new Thread(runnable, "chartsearchai-sse-keepalive")} outside {@code SseKeepAlive}, running
	 * that same read, went green at 85 of 85 because the loop matches on the name literal, and reddened
	 * only once its name was changed. So the name is asserted for what a name is worth — a thread that
	 * identifies itself in a dump — and the two count assertions are what say a thread is the
	 * keep-alive's. A thread can still arrive by a route neither spelling reaches
	 * ({@code new Timer()}, {@code CompletableFuture.runAsync}, a Spring-managed pool), and stating
	 * that is the point — a guard whose message claims more than it checks is the same silent weakening
	 * as a region that reads short.</p>
	 *
	 * <p>The {@code Context} scan had a third hole of the same shape, and it is a SPELLING rather than
	 * a location: the needle is the text {@code Context.}, so a static import of
	 * {@code Context.getAuthenticatedUser} lets the keep-alive read authentication state as a bare
	 * {@code getAuthenticatedUser()} and the scan never sees it. Measured 2026-08-20, both directions:
	 * that read placed after the write in {@code SseKeepAlive.write} left omod green at 85 of 85, and
	 * the identical read spelled {@code Context.getAuthenticatedUser()} failed the scan and nothing
	 * else — so the scan works and it was the spelling that admitted it. The remedy is the import
	 * assertion below, which is deliberately over the WHOLE file rather than the class body, since an
	 * import sits above it; it needs no region canary, because the positive
	 * {@code source.contains("new Thread(")} at the top of this method already fails if the source ever
	 * reads empty. Placement matters to the measurement and not to the hole: the same read put BEFORE
	 * the write reddens six behavioural cases instead, because then no comment is ever written, which
	 * is the case {@code RestControllerContext}'s javadoc records.</p>
	 *
	 * <p>One route past the pair of them is left, and naming it is the same discipline as naming
	 * {@code new Timer()} above: a helper METHOD elsewhere in this controller that reads
	 * {@code Context} and is called from inside {@code SseKeepAlive}. The qualified read then sits
	 * outside the class body, so the scan scoped to that body does not see it, and no import is
	 * involved for the assertion below to catch. Closing that needs a call graph rather than a text
	 * scan, so it is stated instead.</p>
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

		// A PATTERN over the type name, never the literal "new Thread(". A background thread spelled
		// `new java.lang.Thread(` — or wrapped after `new` — satisfied the literal form while running
		// Context work off it, and left the whole omod suite green; measured by a review agent on
		// issue #378's harden round, which is the same escape that round found in two other guards.
		assertTrue(matches(source, THREAD_CREATION) > 0,
				"the keep-alive's thread must be created HERE, in the controller, or both thread "
						+ "assertions below pass on nothing: the loop iterates zero times, and the count "
						+ "matches zero against zero. An empty scan is indistinguishable from a compliant "
						+ "one. Extracting the thread factory to a shared helper is the refactor that does "
						+ "this — the module hand-rolls the same daemon factory in LocalLlmEngine and "
						+ "PrewarmRefreshExecutor, so it looks like obvious reuse — and it would leave the "
						+ "next background thread added to this controller unguarded while the suite stayed "
						+ "green. Measured: dropping the factory for a bare "
						+ "Executors.newSingleThreadScheduledExecutor() reddens this and nothing else in "
						+ "the module, because the two below then compare zero against zero");
		java.util.regex.Matcher creations =
				java.util.regex.Pattern.compile(THREAD_CREATION).matcher(source);
		while (creations.find()) {
			int at = creations.start();
			String creation = source.substring(at, Math.min(source.length(), at + 140));
			assertTrue(creation.contains("\"chartsearchai-sse-keepalive\""),
					"a thread this controller creates must carry the keep-alive's NAME, so that it "
							+ "identifies itself in a thread dump. That it IS the keep-alive's is the count "
							+ "assertion below and not this one, which a rogue thread reusing the name "
							+ "satisfies; found: " + creation);
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
		assertEquals(matches(keepAlive, THREAD_CREATION), matches(source, THREAD_CREATION),
				"every thread this controller creates must sit INSIDE SseKeepAlive, not merely wear its "
						+ "name: the loop above matches on the name literal, so a raw "
						+ "new Thread(runnable, \"chartsearchai-sse-keepalive\") started anywhere else in "
						+ "this class satisfies it. Measured before this assertion existed: such a thread "
						+ "running Context.getAuthenticatedUser left the whole module green at 85 of 85, "
						+ "and reddened the loop above only once its name was changed — the name equality "
						+ "was the whole of what admitted it. Nothing else reaches it either: the "
						+ "Executors. count below does not, because the thread is started directly, and "
						+ "the Context. scan does not, because it is scoped to the class body. Needs no "
						+ "region canary of its own, and a prose mention outside the class reddens it, "
						+ "both for the reasons the Executors. assertion gives");
		assertEquals(occurrences(keepAlive, "Executors."), occurrences(source, "Executors."),
				"every executor this controller creates must be the keep-alive's own: the default "
						+ "thread factory writes no `new Thread(` for either assertion above to find, so a "
						+ "pool built outside SseKeepAlive is a background thread with nothing at all "
						+ "guarding it. Measured before this assertion existed: an "
						+ "Executors.newSingleThreadExecutor in this class running "
						+ "Context.getAuthenticatedUser left the whole module green at 84 of 84. "
						+ "This assertion needs no region canary of its own — a short region drops the "
						+ "keep-alive's own call, the two counts stop matching, and it fails rather "
						+ "than passes. It matches call sites, so a mention of Executors in prose "
						+ "outside the class reddens it too; that is the safe direction");
		assertTrue(!keepAlive.contains("Context."),
				"the keep-alive thread must write bytes and nothing else — reading Context off it is "
						+ "exactly the unsafe sharing this test exists to prevent");
		assertTrue(!source.contains("import static org.openmrs.api.context.Context"),
				"Context must stay QUALIFIED in this controller: the assertion above matches the text "
						+ "\"Context.\", so a static import lets the keep-alive read authentication state "
						+ "as a bare getAuthenticatedUser() and the scan never sees it. Whole file rather "
						+ "than the class body, because an import is file-scoped");

		assertTrue(!source.contains("import org.springframework.web.servlet.mvc.method.annotation.SseEmitter"),
				"Streaming must not import SseEmitter");
		assertTrue(!source.contains("addProxyPrivilege"),
				"Streaming must not use proxy privileges");
		assertTrue(!source.contains("setUserContext"),
				"Must not share UserContext across threads");
	}

	/**
	 * @return how many times {@code needle} occurs in {@code haystack}, counting overlaps
	 *
	 *         <p>Package-visible because a second class in this package makes EXACT-count assertions
	 *         off it ({@code ChartSearchAiInteractionPairExtentTest}, issue #336). A copy there would
	 *         be one that could be "fixed" to step by {@code needle.length()} — the non-overlapping
	 *         form two api tests use — leaving the two guards counting differently with no compile
	 *         error and no failure.
	 */
	/** How a thread creation is FOUND — the type name with an optional qualifier and whitespace
	 *  anywhere Java allows it, never the literal {@code "new Thread("}. See the comment at the first
	 *  assertion in {@link #streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads}. */
	private static final String THREAD_CREATION = "new\\s+(?:\\w+\\s*\\.\\s*)*Thread\\s*\\(";

	static int occurrences(String haystack, String needle) {
		int count = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
			count++;
		}
		return count;
	}

	/**
	 * @return how many times {@code regex} matches — the counterpart of {@link #occurrences} for a
	 *         needle that must tolerate the whitespace Java allows.
	 *
	 *         <p>Added on issue #378's harden round, where a review agent got past THREE guards in
	 *         this family by exploiting exactly that: a construction spelled {@code new\n\t\tType(},
	 *         a call spelled {@code name\n\t\t(args)}, and a thread spelled
	 *         {@code new java.lang.Thread(}. Each satisfied a literal needle while being the very
	 *         thing its guard forbids, and each left the suite green. Prefer this wherever the needle
	 *         is a call or a construction; {@link #occurrences} is right for a wire KEY, which is a
	 *         string literal and admits no whitespace.
	 */
	static int matches(String haystack, String regex) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(haystack);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
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
	 * The keep-alive must be scheduled at a fixed DELAY, not a fixed rate.
	 *
	 * <p>Nothing held this one. Swapping {@code scheduleWithFixedDelay} for
	 * {@code scheduleAtFixedRate} left every other test in the module green (83 of 83, measured before
	 * this one existed), while dropping the {@code out} monitor reddens
	 * {@code ChartSearchAiStreamKeepAliveTest.aKeepAliveNeverSplitsAnEventFrame} and dropping the
	 * stop-flag read reddens {@link #streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads()}.
	 * The two schedule methods read as interchangeable, which is what makes the swap a plausible
	 * edit.</p>
	 *
	 * <p>What it would cost: a write to a slow client can block for longer than the interval, and at a
	 * fixed rate the executor then owes several runs and fires them back to back into the same
	 * congested socket, growing its queue for as long as the congestion lasts. A keep-alive answers
	 * "has anything been written lately", so the clock belongs after a write finishes.</p>
	 *
	 * <p>Matched on {@code timer.scheduleWithFixedDelay(} rather than on the bare method name, and that
	 * is load-bearing rather than fussy: {@code SseKeepAlive.write}'s own catch comment names
	 * {@code scheduleWithFixedDelay} in prose, so asserted on the name alone this PASSES with the call
	 * swapped — measured, the whole suite stayed green — which is exactly the vacuous guard it exists
	 * to be the opposite of. Whitespace-stripped so reformatting cannot redden it, and scoped to the
	 * nested class body so text elsewhere in the controller cannot satisfy it. It needs no region
	 * canary of the kind {@link #streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads()}
	 * carries: this is a positive containment, so a short region fails it rather than passing it.</p>
	 *
	 * <p>A behavioural version would need a write that blocks longer than the interval and then a
	 * judgment about what counts as back to back, so it would only redden probabilistically.</p>
	 */
	@Test
	public void theKeepAliveIsScheduledAtAFixedDelayAndNotAFixedRate() throws Exception {
		String keepAlive = nestedClassBody(controllerSource(), "SseKeepAlive");

		assertTrue(keepAlive.replaceAll("\\s+", "").contains("timer.scheduleWithFixedDelay("),
				"the keep-alive must be scheduled at a fixed DELAY, not a fixed rate: a write to a slow "
						+ "client can block for longer than the interval, and at a fixed rate the "
						+ "executor then owes several runs and fires them back to back into the same "
						+ "congested socket, growing its queue for as long as the congestion lasts. "
						+ "Measured: swapping the two leaves every other test in the module green");
	}

	/**
	 * Reads the controller's production source as UTF-8.
	 *
	 * <p>One reader for every source-scanning test that reads this controller — the ones in this
	 * class, which had each grown its own spelling of it, and since issue #336
	 * {@code ChartSearchAiInteractionPairExtentTest}'s guard as well. Stated as "every" rather than
	 * counted, for the reason {@link #resolveSourceFile()} gives one level up: a count here drifts the
	 * moment a test is added, as it already has.</p>
	 *
	 * <p>The charset is explicit because the file contains non-ASCII characters and
	 * {@code new String(byte[])} decodes with the platform default: every needle asserted here is
	 * ASCII, so a wrong default would not break them today, and stating the charset is what keeps
	 * that true of a needle someone adds later.</p>
	 *
	 * @return the whole file
	 */
	static String controllerSource() throws java.io.IOException {
		return new String(java.nio.file.Files.readAllBytes(resolveSourceFile().toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Locates the controller's production source, which every source-scanning assertion against that
	 * file reads — in this class, and since issue #336 in
	 * {@code ChartSearchAiInteractionPairExtentTest} too.
	 *
	 * <p>A file it cannot find FAILS rather than skips. It skipped until now, through
	 * {@code Assumptions.assumeTrue}, and that is the same defect as the short-region one above, one
	 * level further up: measured by pointing the path below at a directory that does not exist, omod
	 * built GREEN with two tests skipped, so every assertion resting on it stopped running and nothing
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
	static java.io.File resolveSourceFile() {
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
