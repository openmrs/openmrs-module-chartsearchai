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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

/**
 * Behavioral tests for the SSE keep-alive the streaming orchestration emits while the model is
 * still silent, driven through the real {@code streamAnswer} with a stubbed
 * {@link ChartSearchService} and a captured output stream — this test package's convention.
 *
 * <p>Why this exists: {@code searchStream} commits the response HEADERS and then writes nothing
 * until the LLM's first {@code thinking} or {@code token} event. A reverse proxy cannot tell that
 * silence from a hung origin, so it closes the connection on its read timeout — Cloudflare at
 * ~120s and nginx's {@code proxy_read_timeout} at 60s by default. Measured on the
 * chartsearchai.openmrs.org demo 2026-08-19: every Gemma 4 E4B query died at ~125s having
 * delivered <em>zero</em> bytes, while E2B (first bytes at 27-38s) completed at 149-154s — so what
 * decides whether a long answer survives is whether SOMETHING is written early, not whether the
 * answer finishes inside the window.</p>
 *
 * <p>The keep-alive is an SSE comment (a line opening with {@code :}), which the spec requires
 * clients to ignore, so no client change is needed and no phantom event can reach the UI.</p>
 *
 * <p>One test here is deliberately not behavioural:
 * {@link #theProductionIntervalSitsInsideEveryProxyReadTimeoutTheJavadocNames} reads the production
 * constant reflectively, because the interval every other test passes is a test parameter and no
 * behavioural case can check the shipped one without waiting it out.</p>
 */
public class ChartSearchAiStreamKeepAliveTest {

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		out = new ByteArrayOutputStream();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	private static User user() {
		return new User(3);
	}

	@Test
	public void aByteReachesTheClientBeforeGenerationStartsSoAProxyCannotTimeOutOnSilence() {
		SilentThenAnswerStub stub = new SilentThenAnswerStub(out);
		controller.setChartSearchService(stub);

		controller.streamAnswer(out, patient(), "any allergies?", user(), false);

		String beforeGeneration = stub.writtenAtEntry;
		assertFalse(beforeGeneration.isEmpty(),
				"the stream must carry bytes BEFORE the service starts generating; a proxy reading "
						+ "nothing for its whole read timeout closes the connection, which is how every "
						+ "E4B query on the demo died at ~125s with zero bytes delivered");
		assertTrue(beforeGeneration.startsWith(":"),
				"the early write must be an SSE comment so a spec-compliant client ignores it; got "
						+ quoted(beforeGeneration));
		assertFalse(beforeGeneration.contains("event:"),
				"the keep-alive must not fabricate an event — no client has a handler for one, and the "
						+ "UI would render it; got " + quoted(beforeGeneration));
		assertTrue(beforeGeneration.endsWith("\n\n"),
				"an SSE frame is terminated by a blank line, or the next real event is folded into this "
						+ "one by the client's parser; got " + quoted(beforeGeneration));
	}

	@Test
	public void keepAlivesKeepArrivingWhileTheModelStaysSilent() {
		SilentThenAnswerStub stub = SilentThenAnswerStub.awaitingComments(out, 3);
		controller.setChartSearchService(stub);

		controller.streamAnswer(out, patient(), "any allergies?", user(), false, 20L);

		int written = countKeepAlives(stub.writtenAtEntry);
		assertTrue(written >= 3,
				"the silence must carry several keep-alives, not just the opening one: a proxy's read "
						+ "timeout restarts on every byte, so one early byte does not save a prefill that "
						+ "outlasts the timeout — E4B's was ~194s against a ~120s window. Got " + written
						+ " in " + quoted(stub.writtenAtEntry));
	}

	@Test
	public void noKeepAliveIsWrittenOnceTheAnswerIsFinished() throws Exception {
		controller.setChartSearchService(new SilentThenAnswerStub(out));

		controller.streamAnswer(out, patient(), "any allergies?", user(), false, 20L);
		int settled = out.size();

		Thread.sleep(200L); // ten intervals; a live timer would have written several times over
		assertEquals(settled, out.size(),
				"the timer must be stopped when streamAnswer returns — a keep-alive written after the "
						+ "terminal event leaks a thread per request and writes into a response the "
						+ "container has already recycled for someone else");
	}

	/**
	 * One keep-alive per millisecond against 200 event writes, over a sink that can tear.
	 *
	 * <p>The sink is deliberately not a {@link ByteArrayOutputStream}: every one of its methods is
	 * synchronized, so a whole {@code write(byte[])} is already atomic there and this hazard cannot
	 * be observed through it — dropping the controller's own lock leaves such a test green, which is
	 * how this test was first written here and why it is not written that way now. A servlet output
	 * stream gives no such guarantee, so {@link TearingOutputStream} models the weaker contract the
	 * production code actually runs against.</p>
	 */
	@Test
	public void aKeepAliveNeverSplitsAnEventFrame() {
		TearingOutputStream tearing = new TearingOutputStream();
		controller.setChartSearchService(new ChattyStub(200));

		controller.streamAnswer(tearing, patient(), "any allergies?", user(), false, 1L);

		int tokens = 0;
		for (SseEvent event : SseEvents.parse(tearing.sink())) {
			if ("token".equals(event.type)) {
				tokens++;
				assertEquals("chunk", event.data,
						"a token event's payload must survive intact beside a concurrent keep-alive");
			}
		}
		assertEquals(200, tokens,
				"every token event must reach the client exactly once; a comment spliced into a frame "
						+ "would split it into a malformed pair and change this count");
		assertEveryFrameIsWellFormed(tearing.text());

		// Counted LAST, and that ordering is load-bearing. countKeepAlives finds comments at line
		// starts, and a comment spliced into a frame is no longer at one — so with the production lock
		// dropped this count reads 1, and asserted first it would report a timer that never fired
		// instead of the splice that actually happened, sending the next reader after the scheduler.
		int contending = countKeepAlives(tearing.text());
		assertTrue(contending >= 2,
				"a SCHEDULED keep-alive must actually have been written while the events were going out, "
						+ "or this test proves nothing: the synchronous one lands before generation starts "
						+ "and cannot interleave with anything, so with only that one the assertions above "
						+ "say no more than that 200 token frames are well formed, which the event-order "
						+ "tests already cover. Got " + contending);
	}

	@Test
	public void aKeepAliveThatCannotBeWrittenDoesNotFailTheAnswer() {
		RefusingSink refusing = new RefusingSink(false, 0, Integer.MAX_VALUE);
		controller.setChartSearchService(new SilentThenAnswerStub(new ByteArrayOutputStream()));

		controller.streamAnswer(refusing, patient(), "any allergies?", user(), false);

		assertTrue(refusing.refused > 0,
				"the sink must have refused a keep-alive, or this test proves nothing");
		assertNotNull(SseEvents.ofType(refusing.sink(), "done"),
				"a keep-alive that cannot be written means the client is probably gone, which the "
						+ "generation loop discovers on its own next write — failing the request over a "
						+ "comment nobody reads would abort an answer that was about to succeed");
		assertNotNull(SseEvents.ofType(refusing.sink(), "token"),
				"and the answer's own writes must be untouched by the keep-alive's failure");
	}

	@Test
	public void aKeepAliveThatThrowsDoesNotCancelTheRestOfTheSchedule() {
		// Refuse ONLY the first SCHEDULED comment, letting the synchronous one through. That is what
		// isolates the claim in this test's name: if the refusal hit the synchronous write instead, the
		// assertion that failed without the catch would be that one, and a silently unscheduled TIMER
		// would go unnoticed.
		RefusingSink refusing = new RefusingSink(true, 1, 1);
		// Waits for the two keep-alives it asserts on rather than for a fixed span, for the reason
		// SilentThenAnswerStub.awaitingComments gives: the refused write is never counted, so two in the
		// sink means the synchronous one plus one that got through AFTER the refusal.
		controller.setChartSearchService(SilentThenAnswerStub.awaitingComments(refusing.sink(), 2));

		controller.streamAnswer(refusing, patient(), "any allergies?", user(), false, 20L);

		assertEquals(1, refusing.refused,
				"a scheduled keep-alive must actually have been attempted and refused, or this test "
						+ "proves nothing");
		assertTrue(countKeepAlives(refusing.text()) >= 2,
				"a keep-alive after the refused one must still arrive: a scheduled task that throws is "
						+ "silently unscheduled, which would leave the rest of a long answer with no "
						+ "keep-alive and nothing in the log to say why. Got "
						+ countKeepAlives(refusing.text()) + " written");
	}

	/**
	 * The interval PRODUCTION uses must sit inside the read timeouts this module is deployed behind.
	 * That is the whole of why the keep-alive works, and nothing else in this class touches it: every
	 * other test here passes its own interval, precisely so the periodic writes can be observed
	 * without waiting a production one out. So without this, raising the constant above a proxy window
	 * restores the exact defect the class exists to prevent — a long answer cut mid-stream with no
	 * {@code error} event — and the whole suite stays green while it happens.
	 *
	 * <p>The bound asserted is the one {@code KEEP_ALIVE_INTERVAL_MS}' own javadoc states, no
	 * stricter: smaller than every read-timeout default it names, the tightest of which is nginx's
	 * {@code proxy_read_timeout} at 60s. Setting it to exactly 60000 fails here, which is the point —
	 * at an interval equal to the timeout the next write comes due exactly as the proxy gives up, so
	 * which of the two happens first is a scheduling race rather than a guarantee.</p>
	 *
	 * <p>Read by reflection rather than by widening the constant to package-private. A test seam on a
	 * Spring singleton is what the six-argument {@code streamAnswer} overload exists to avoid, and the
	 * same reasoning applies to reaching for one here. A rename makes this fail with
	 * {@link NoSuchFieldException} rather than silently stop checking.</p>
	 */
	@Test
	public void theProductionIntervalSitsInsideEveryProxyReadTimeoutTheJavadocNames() throws Exception {
		Field field = ChartSearchAiRestController.class.getDeclaredField("KEEP_ALIVE_INTERVAL_MS");
		field.setAccessible(true);
		// getLong, not a cast of get(): narrowing the constant to int is a harmless edit that a cast
		// would redden with a ClassCastException saying nothing about the interval, while getLong
		// widens and keeps checking the bound. A non-numeric type still fails, loudly.
		long millis = field.getLong(null);

		assertTrue(millis > 0L,
				"scheduleWithFixedDelay rejects a non-positive delay with IllegalArgumentException, and "
						+ "SseKeepAlive.start runs BEFORE streamAnswer's try block, so it escapes the whole "
						+ "request: no error event, no audit row, and the executor it just created is never "
						+ "stopped because keepAlive was never assigned for the finally to reach. Measured "
						+ "by setting the constant to 0. This bound is what keeps that call safe where it "
						+ "sits, which is why it is asserted and not assumed; got " + millis);
		assertTrue(millis < 60000L,
				"the production keep-alive interval must be smaller than every read-timeout default its "
						+ "own javadoc names — nginx proxy_read_timeout 60s, Cloudflare ~120s — or a "
						+ "silent gap longer than the timeout reopens and the connection is cut with no "
						+ "error event, which is the defect measured on the demo 2026-08-19. Got "
						+ millis + "ms");
	}

	/**
	 * A sink that refuses keep-alive frames — recognised by the leading {@code :} of an SSE comment —
	 * and accepts every event frame, so the keep-alive's failure paths can be driven without
	 * disturbing the answer's own writes.
	 *
	 * <p>Its counters are plain fields read by the test thread after {@code streamAnswer} returns.
	 * That is safe without further synchronization because every write here — the answer's and the
	 * keep-alive's alike — happens inside the {@code OutputStream} monitor, and {@code stop()} takes
	 * that same monitor before returning, which is the happens-before edge.</p>
	 *
	 */
	private static final class RefusingSink extends OutputStream {

		private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

		private final boolean runtimeFailure;

		private final int refuseFrom;

		private final int refuseCount;

		private int commentsSeen;

		int refused;

		/**
		 * @param runtimeFailure throw an unchecked exception rather than an {@link IOException}, to
		 *        reach the other of the two catches in {@code SseKeepAlive.write}
		 * @param refuseFrom index of the first comment frame to refuse, counting the synchronous one as
		 *        0, so a test can choose whether the synchronous or a scheduled write is the one denied
		 * @param refuseCount how many comment frames to refuse from that index on
		 */
		RefusingSink(boolean runtimeFailure, int refuseFrom, int refuseCount) {
			this.runtimeFailure = runtimeFailure;
			this.refuseFrom = refuseFrom;
			this.refuseCount = refuseCount;
		}

		@Override
		public void write(int b) {
			sink.write(b);
		}

		@Override
		public void write(byte[] frame, int off, int len) throws IOException {
			if (len > 0 && frame[off] == ':' && commentsSeen++ >= refuseFrom && refused < refuseCount) {
				refused++;
				if (runtimeFailure) {
					throw new IllegalStateException("response already recycled");
				}
				throw new IOException("client gone");
			}
			sink.write(frame, off, len);
		}

		ByteArrayOutputStream sink() {
			return sink;
		}

		String text() {
			return new String(sink.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * An {@link OutputStream} that writes one byte at a time and yields between bytes — permitted of
	 * a servlet output stream and not of a {@link ByteArrayOutputStream}, see
	 * {@link #aKeepAliveNeverSplitsAnEventFrame}. Accumulation is into a synchronized sink so the
	 * test observes interleaving rather than corruption of its own bookkeeping.
	 */
	private static final class TearingOutputStream extends OutputStream {

		private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

		@Override
		public void write(int b) {
			sink.write(b);
			Thread.yield();
		}

		ByteArrayOutputStream sink() {
			return sink;
		}

		String text() {
			return new String(sink.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Asserts the whole stream decomposes into frames that are each either a lone keep-alive comment
	 * or a well-formed event — the single invariant that a comment was never written into the middle
	 * of an event.
	 */
	private void assertEveryFrameIsWellFormed(String raw) {
		for (String frame : raw.split("\n\n")) {
			if (frame.isEmpty()) {
				continue;
			}
			String[] lines = frame.split("\n");
			if (lines[0].startsWith(":")) {
				assertEquals(1, lines.length,
						"a keep-alive frame must stand alone; got " + quoted(frame));
				continue;
			}
			assertTrue(lines[0].startsWith("event: "),
					"a frame must open with its event line; got " + quoted(frame));
			for (int i = 1; i < lines.length; i++) {
				assertTrue(lines[i].startsWith("data: "),
						"every later line of an event frame must be data — a keep-alive spliced into "
								+ "this event would show up here, splitting it in two for every client; got "
								+ quoted(frame));
			}
		}
	}

	/**
	 * Counts keep-alive comments at LINE STARTS, which makes this an UNDERCOUNT over a stream that can
	 * tear: a comment spliced into the middle of an event frame is no longer at a line start, so a run
	 * with the production lock dropped counts far fewer than were written — 1 against the ~35 measured
	 * with the lock in place. That is why {@link #aKeepAliveNeverSplitsAnEventFrame} counts last rather
	 * than first: asserted first it reports a timer that never fired instead of the splice that did.
	 * Callers over a whole-frame sink have no such problem.
	 *
	 * @return the number of lines in {@code written} that open an SSE comment
	 */
	private static int countKeepAlives(String written) {
		int count = 0;
		for (String line : written.split("\n")) {
			if (line.startsWith(":")) {
				count++;
			}
		}
		return count;
	}

	private static String quoted(String s) {
		return "\"" + s.replace("\n", "\\n") + "\"";
	}

	private static ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer("Has TB [8].",
				Arrays.asList(new ChartSearchService.RecordReference(8, "condition", "u8", null,
						Boolean.TRUE)));
	}

	/**
	 * Stands in for a model that thinks for a while before saying anything: it records everything
	 * already written to the stream at the moment generation begins — which is exactly the window a
	 * proxy is timing — optionally after waiting for keep-alives to pile up first.
	 */
	private static class SilentThenAnswerStub implements ChartSearchService {

		private final ByteArrayOutputStream sink;

		private final int minComments;

		String writtenAtEntry = "";

		SilentThenAnswerStub(ByteArrayOutputStream sink) {
			this(sink, 0);
		}

		/**
		 * Stays silent until {@code wanted} keep-alive comments have actually been written, rather than
		 * for a fixed span. Deterministic where a sleep is not: a timer starved by a loaded CI runner or
		 * a GC pause makes a sleep-based assertion fail spuriously, while this either observes the
		 * writes or gives up and lets the assertion report how many really arrived.
		 */
		static SilentThenAnswerStub awaitingComments(ByteArrayOutputStream sink, int wanted) {
			return new SilentThenAnswerStub(sink, wanted);
		}

		private SilentThenAnswerStub(ByteArrayOutputStream sink, int minComments) {
			this.sink = sink;
			this.minComments = minComments;
		}

		/**
		 * Returns once the sink holds {@code wanted} comments, or after a deadline far longer than any
		 * healthy timer needs. Never holds the stream's monitor while waiting.
		 */
		private void awaitComments(int wanted) {
			long deadline = System.currentTimeMillis() + 5000L;
			while (System.currentTimeMillis() < deadline
					&& countKeepAlives(new String(sink.toByteArray(), StandardCharsets.UTF_8)) < wanted) {
				try {
					Thread.sleep(5L);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			if (minComments > 0) {
				awaitComments(minComments);
			}
			writtenAtEntry = new String(sink.toByteArray(), StandardCharsets.UTF_8);
			tokenConsumer.accept("Has TB [8].");
			citationsConsumer.accept(answer().getReferences());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	/** Emits many token events in a tight loop, to contend with the keep-alive thread. */
	private static class ChattyStub extends SilentThenAnswerStub {

		private final int chunks;

		ChattyStub(int chunks) {
			super(new ByteArrayOutputStream());
			this.chunks = chunks;
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			for (int i = 0; i < chunks; i++) {
				tokenConsumer.accept("chunk");
			}
			citationsConsumer.accept(answer().getReferences());
			return answer();
		}
	}
}
