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
 * constant reflectively, because no behavioural case here can check the shipped interval's VALUE
 * without waiting it out. The split is whether a case's assertion depends on the interval at all:
 * the five that do pass their own, and the two that call the five-argument overload therefore run on
 * the shipped constant, but they assert only on the synchronous first write, which
 * {@code SseKeepAlive.start} performs before scheduling and which never reads it.</p>
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
						+ "outlasts the timeout, as E4B's did on the demo. Got " + written
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
	 * The exit that is NOT a return: a client that has gone away unwinds through
	 * {@code streamAnswer}'s catch, and the timer has to be stopped there too.
	 *
	 * <p>{@link #noKeepAliveIsWrittenOnceTheAnswerIsFinished} above covers only the happy path, where
	 * the tail of the try block runs anyway, so it cannot tell a {@code finally} from a statement at
	 * that tail. Every other exit leaves through a catch instead, and that is most of them: a
	 * disconnect, a chart too large, a misconfiguration, an LLM timeout. Measured before this test
	 * existed — moving {@code keepAlive.stop()} out of the {@code finally} into the tail of the try
	 * block left every other test in the module green, 84 of 84.</p>
	 *
	 * <p>What that costs: the request never reaches {@code stop()}, so {@code stopped} stays false and
	 * {@code shutdownNow} is never called either, and one daemon thread per disconnected request goes
	 * on writing every interval for the life of the JVM. Both catches in {@code SseKeepAlive.write}
	 * keep it alive through whatever the recycled response throws at it, and in the case that actually
	 * hurts the write SUCCEEDS instead of throwing, putting 14 bytes into whichever request owns that
	 * stream object next.</p>
	 */
	@Test
	public void aClientDisconnectStopsTheTimerToo() throws Exception {
		DisconnectedClientSink gone = new DisconnectedClientSink();
		// Waits for the keep-alives it asserts on rather than a fixed span, for the reason
		// SilentThenAnswerStub.awaitingComments gives.
		controller.setChartSearchService(SilentThenAnswerStub.awaitingComments(gone.sink(), 2));

		controller.streamAnswer(gone, patient(), "any allergies?", user(), false, 20L);
		int settled = gone.sink().size();

		assertTrue(gone.refused >= 1,
				"a frame must actually have been refused, or this test proves nothing: with nothing "
						+ "refused streamAnswer RETURNS instead of unwinding, and this case silently "
						+ "becomes a second copy of noKeepAliveIsWrittenOnceTheAnswerIsFinished, which "
						+ "covers that path already. Measured: with the refusal removed the omod suite "
						+ "stays green at 85 of 85");
		assertTrue(countKeepAlives(gone.text()) >= 2,
				"the timer must have been running when the disconnect happened, or this test proves "
						+ "nothing: with only the synchronous comment written there is no schedule left to "
						+ "leak. Got " + countKeepAlives(gone.text()));
		Thread.sleep(200L); // ten intervals; a timer still running would have written several times
		assertEquals(settled, gone.sink().size(),
				"a client disconnect leaves through a catch, not a return, so only a finally stops the "
						+ "timer: unstopped it writes into a response the container has already recycled "
						+ "for someone else, and shutdownNow cannot reach a task parked on the monitor");
	}

	/**
	 * One keep-alive per millisecond against a stream of event writes, over a sink that can tear.
	 *
	 * <p>{@link ChattyStub} keeps emitting until the SCHEDULED keep-alives have actually contended,
	 * rather than emitting a fixed count and hoping. Both directions need that. A machine fast enough
	 * to finish a fixed loop inside one interval would leave only the synchronous comment written, and
	 * the {@code contending} canary below would then fail with nothing wrong in the production code;
	 * and the splice this test exists to catch can only happen while a comment and a frame are in
	 * flight together, so ending the loop before any scheduled comment lands is also what makes the
	 * detection miss. Measured: over the fixed 200-write loop this replaced, dropping the keep-alive's
	 * own lock was caught in 4 of 5 runs and dropping {@code writeSseEvent}'s in 2 of 3; waiting for
	 * five scheduled comments catches both in 5 of 5, at the same runtime. Five and not two, and that
	 * is measured rather than reasoned: at two the wait is ALREADY satisfied by the time the minimum is
	 * reached, so the second loop runs ZERO iterations and creates none of the contention it exists
	 * for, which is what left {@code writeSseEvent}'s lock at 2 of 3. At five it always runs on past the
	 * minimum. How far is machine-dependent and deliberately not recorded here; the rate above is what
	 * the choice rests on.</p>
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
		ChattyStub stub = new ChattyStub(tearing.sink(), 200, 5);
		controller.setChartSearchService(stub);

		controller.streamAnswer(tearing, patient(), "any allergies?", user(), false, 1L);

		int tokens = 0;
		for (SseEvent event : SseEvents.parse(tearing.sink())) {
			if ("token".equals(event.type)) {
				tokens++;
				assertEquals("chunk", event.data,
						"a token event's payload must survive intact beside a concurrent keep-alive");
			}
		}
		assertEquals(stub.emitted, tokens,
				"every token event must reach the client exactly once; a comment spliced into a frame "
						+ "would split it into a malformed pair and change this count");
		assertEveryFrameIsWellFormed(tearing.text());

		// Counted LAST, and that ordering is load-bearing. countKeepAlives finds comments at line
		// starts, and a comment spliced into a frame is no longer at one — so with the production lock
		// dropped this count is short by however many were spliced, and asserted first it could report
		// a timer that never fired instead of the splice that actually happened, sending the next
		// reader after the scheduler.
		int contending = countKeepAlives(tearing.text());
		assertTrue(contending >= 2,
				"a SCHEDULED keep-alive must actually have been written while the events were going out, "
						+ "or this test proves nothing: the synchronous one lands before generation starts "
						+ "and cannot interleave with anything, so with only that one the assertions above "
						+ "say no more than that the emitted token frames are well formed, which the "
						+ "event-order tests already cover. Got " + contending);
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
	 * The interval PRODUCTION uses must sit inside the read timeouts this module is deployed
	 * behind. That is the whole of why the keep-alive works, and no behavioural case here can check
	 * its value: every case whose assertion depends on the interval passes its own, precisely so
	 * the periodic writes can be observed without waiting a production one out, and the two that
	 * run on the shipped constant assert only on the synchronous first write, which happens before
	 * scheduling and never reads it. So without this, raising the constant above a proxy window
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
	 *
	 * <p>This bounds the constant; it cannot see whether production still reads it. That half is
	 * {@code ChartSearchAiStreamingTest.theProductionEntryPointPassesTheKeepAliveConstantAndNotALiteral},
	 * which is where the controller source is already in hand. Without it, "the interval PRODUCTION
	 * uses" above would be a claim this test does not check.</p>
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
	 * A client that has gone away: the inverse of {@link RefusingSink}, in that the ANSWER's frames are
	 * the ones refused, so the generation loop unwinds through {@code writeSseEventOrThrow} exactly as a
	 * mid-stream disconnect does. Comment frames still land, which is what lets
	 * {@link #aClientDisconnectStopsTheTimerToo} see whether the timer kept writing after the unwind.
	 *
	 * <p>An event frame is recognised by NOT opening with the {@code :} of an SSE comment, rather than by
	 * matching {@code event:}, so a future frame shape the controller writes is refused too instead of
	 * quietly turning this test green.</p>
	 */
	private static final class DisconnectedClientSink extends OutputStream {

		private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

		/**
		 * Read by the test after {@code streamAnswer} returns, so the disconnect can be asserted. Needs
		 * no synchronization, and for a simpler reason than {@link RefusingSink}'s counters: only event
		 * frames are refused here and only the calling thread writes those, so this is incremented on
		 * the test's own thread. That sibling's cross-thread happens-before argument is about its
		 * counters being touched by the keep-alive thread, which these are not.
		 */
		int refused;

		@Override
		public void write(int b) {
			sink.write(b);
		}

		@Override
		public void write(byte[] frame, int off, int len) throws IOException {
			if (len > 0 && frame[off] != ':') {
				refused++;
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
	 * with the production lock dropped counts short by however many were spliced. Two callers depend on
	 * that. {@link #aKeepAliveNeverSplitsAnEventFrame} counts last rather than first, because asserted
	 * first it could report a timer that never fired instead of the splice that did; and
	 * {@link ChattyStub} bounds its wait rather than waiting on this alone, because a run where every
	 * comment is spliced can never satisfy it. Callers over a whole-frame sink have no such problem.
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

		/** @return everything written to the stream so far, decoded. */
		final String streamText() {
			return new String(sink.toByteArray(), StandardCharsets.UTF_8);
		}

		/**
		 * @return the keep-alives written so far that still START a line, which is an UNDERCOUNT over a
		 *         torn stream for the reason {@link #countKeepAlives} gives, so a caller waiting on this
		 *         needs a bound of its own
		 */
		final int commentsWritten() {
			return countKeepAlives(streamText());
		}

		/**
		 * Returns once the sink holds {@code wanted} comments, or after a deadline far longer than any
		 * healthy timer needs. Never holds the stream's monitor while waiting.
		 */
		private void awaitComments(int wanted) {
			long deadline = System.currentTimeMillis() + 5000L;
			while (System.currentTimeMillis() < deadline && commentsWritten() < wanted) {
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
			writtenAtEntry = streamText();
			tokenConsumer.accept("Has TB [8].");
			citationsConsumer.accept(answer().getReferences());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	/**
	 * Emits token events in a tight loop to contend with the keep-alive thread, and keeps emitting past
	 * {@code minChunks} until {@code wantedComments} scheduled keep-alives have actually been written.
	 * See {@link #aKeepAliveNeverSplitsAnEventFrame} for why waiting rather than counting matters in
	 * both directions.
	 *
	 * <p>Bounded twice, because with the production lock dropped a spliced comment no longer starts a
	 * line and {@link #countKeepAlives} therefore undercounts it, so the wait can never be satisfied:
	 * five times {@code minChunks}, or the same deadline {@code awaitComments} uses. Reaching either
	 * bound is not failed — the canary reports what actually arrived.</p>
	 */
	private static class ChattyStub extends SilentThenAnswerStub {

		private final int minChunks;

		private final int wantedComments;

		/** Token events actually emitted, which the test asserts all arrived intact. */
		int emitted;

		ChattyStub(ByteArrayOutputStream sink, int minChunks, int wantedComments) {
			// The real sink, so the inherited commentsWritten() reads the stream this contends on.
			super(sink);
			this.minChunks = minChunks;
			this.wantedComments = wantedComments;
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			while (emitted < minChunks) {
				tokenConsumer.accept("chunk");
				emitted++;
			}
			long deadline = System.currentTimeMillis() + 5000L;
			int cap = minChunks * 5;
			while (commentsWritten() < wantedComments && emitted < cap
					&& System.currentTimeMillis() < deadline) {
				tokenConsumer.accept("chunk");
				emitted++;
			}
			citationsConsumer.accept(answer().getReferences());
			return answer();
		}
	}
}
