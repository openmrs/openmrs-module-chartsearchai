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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
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
		SilentThenAnswerStub stub = new SilentThenAnswerStub(out, 0L);
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
		SilentThenAnswerStub stub = new SilentThenAnswerStub(out, 300L);
		controller.setChartSearchService(stub);

		controller.streamAnswer(out, patient(), "any allergies?", user(), false, 40L);

		int written = countKeepAlives(stub.writtenAtEntry);
		assertTrue(written >= 3,
				"a 300ms silence at a 40ms interval must carry several keep-alives, not just the "
						+ "opening one: a proxy's read timeout restarts on every byte, so one early byte "
						+ "does not save a prefill that outlasts the timeout — E4B's was ~194s against a "
						+ "~120s window. Got " + written + " in " + quoted(stub.writtenAtEntry));
	}

	@Test
	public void noKeepAliveIsWrittenOnceTheAnswerIsFinished() throws Exception {
		controller.setChartSearchService(new SilentThenAnswerStub(out, 0L));

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
	 * proxy is timing — optionally after staying silent for {@code silentMillis} first.
	 */
	private static class SilentThenAnswerStub implements ChartSearchService {

		private final ByteArrayOutputStream sink;

		private final long silentMillis;

		String writtenAtEntry = "";

		SilentThenAnswerStub(ByteArrayOutputStream sink, long silentMillis) {
			this.sink = sink;
			this.silentMillis = silentMillis;
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
			if (silentMillis > 0) {
				try {
					Thread.sleep(silentMillis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
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
			super(new ByteArrayOutputStream(), 0L);
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
