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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decodes the SSE wire format the controller writes, for the streaming tests in this package.
 *
 * <p>Shared because two test classes had each grown their own decoder and they had already
 * drifted: one stripped the single space after {@code data:} and the other kept it, and they split
 * events differently (blank-line blocks versus scanning to the next {@code event:}). Jackson
 * tolerated the difference, so nothing failed — which is exactly why it needed removing rather
 * than fixing twice.
 *
 * <p><b>It decodes the way the event-stream specification says a client must, and that is the
 * point of it rather than a detail.</b> The decoder this replaced recognised only LF as a line
 * terminator, while the spec recognises CRLF, CR and LF alike — so a lone CR written into a frame's
 * payload ends the {@code data:} line for every real client and turns whatever follows into further
 * field lines of that same event, and a LF-only decoder cannot see it happen. That is the finding
 * {@link ChartSearchAiSseFrameInjectionTest} pins, and it was invisible to this package until this
 * class was the thing a conforming client would do. Field parsing (name up to the first colon, one
 * optional leading space dropped from the value, {@code data:} lines joined with LF, a line opening
 * with {@code :} skipped as a comment) follows the same specification for the same reason.
 *
 * <p>So when a test here asserts an event's type, it is asserting what a client PARSES, not what
 * the controller passed to {@code writeSseEvent} — which is the only form of that assertion worth
 * anything on a payload the model wrote.
 *
 * <p><b>It is not a general-purpose SSE client, and two deviations are named here so nobody reads it
 * as one.</b> A frame with no {@code event:} field is dropped, where the specification dispatches it
 * as {@code message}; and a typed frame with no {@code data:} line is dispatched, where the
 * specification does not. Neither is reachable from the writer under test, which always emits an
 * {@code event:} line and at least one {@code data:} line — so closing them would add branches no test
 * could discriminate.
 */
final class SseEvents {

	/**
	 * Every line terminator the event-stream grammar recognises. CRLF is first so it is consumed as
	 * ONE terminator rather than two, which is what the specification requires and what keeps a
	 * frame from appearing to carry a blank dispatch line it does not have.
	 */
	private static final Pattern LINE_TERMINATORS = Pattern.compile("\r\n|\r|\n");

	private SseEvents() {
	}

	/** Every event written to {@code out} so far, in emission order. */
	static List<SseEvent> parse(ByteArrayOutputStream out) {
		List<SseEvent> events = new ArrayList<SseEvent>();
		for (List<String> frame : frames(text(out))) {
			String type = null;
			StringBuilder data = new StringBuilder();
			for (String line : frame) {
				if (line.charAt(0) == ':') {
					continue; // a comment — the keep-alive
				}
				int colon = line.indexOf(':');
				String field = colon < 0 ? line : line.substring(0, colon);
				String value = colon < 0 ? "" : line.substring(colon + 1);
				if (value.startsWith(" ")) {
					value = value.substring(1);
				}
				if ("event".equals(field)) {
					type = value;
				} else if ("data".equals(field)) {
					data.append(value).append('\n');
				}
			}
			if (type != null) {
				// A frame with no "event:" field is untyped, which the controller never writes, so it
				// is dropped rather than given a name here.
				events.add(new SseEvent(type, dispatched(data)));
			}
		}
		return events;
	}

	/**
	 * The stream's frames, each as its own raw lines, split where a CLIENT splits them — and the one
	 * place this package decides where a line and a frame end.
	 *
	 * <p>Both readers here need that decision, and need it identically: {@link #parse} reads the fields
	 * out of a frame, {@link #assertEveryFrameIsWellFormed} asks what KIND each line is. They
	 * were written as two walkers, which is the state the class javadoc above records this package
	 * having already paid for once — and the drift on offer was that one of them could be "simplified"
	 * to a regex over the frame separator while the other went on walking.</p>
	 *
	 * <p>Which is why the blank LINE ending a frame is walked rather than matched as a pair of
	 * terminators: {@code (\r\n|\r|\n){2}} matches a single CRLF — first alternative, then
	 * backtracking to {@code \r} and {@code \n} — so a regex for "two terminators" reports a frame
	 * boundary in the middle of one ordinary line break.</p>
	 */
	private static List<List<String>> frames(String raw) {
		List<List<String>> frames = new ArrayList<List<String>>();
		List<String> frame = new ArrayList<String>();
		for (String line : LINE_TERMINATORS.split(raw, -1)) {
			if (line.isEmpty()) {
				if (!frame.isEmpty()) {
					frames.add(frame);
					frame = new ArrayList<String>();
				}
				continue;
			}
			frame.add(line);
		}
		if (!frame.isEmpty()) {
			frames.add(frame); // a stream that ended without its terminating blank line
		}
		return frames;
	}

	/** The one UTF-8 decode of a captured stream in this package. */
	static String text(ByteArrayOutputStream out) {
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * Asserts the whole stream decomposes into frames that are each either a lone keep-alive comment
	 * or a well-formed event: after the {@code event:} line, every line is a {@code data:} line, over
	 * the lines as a CLIENT splits them — per {@link #frames}.
	 *
	 * <p>One assertion, two ways to break it. A keep-alive spliced into a frame breaks it from the
	 * outside; a line terminator left inside a payload breaks it from the inside, and until the writer
	 * split on the whole terminator set this method could not see the second case, because it split on
	 * LF like the writer did.</p>
	 *
	 * <p><b>It catches a payload terminator whose next line is not itself {@code data:}</b>, which is
	 * every field a forgery needs — {@code event:}, {@code id:}, {@code retry:} — and not a payload
	 * that opens a further {@code data:} line of its own, which only appends to the same event's data,
	 * something the model can do with its own text anyway. Measured: {@code data: real<CR>data: more}
	 * passes here, and the claim is stated this way because it was first written as the universal and
	 * that universal is false.</p>
	 */
	static void assertEveryFrameIsWellFormed(ByteArrayOutputStream out) {
		for (List<String> frame : frames(text(out))) {
			assertFrameIsWellFormed(frame);
		}
	}

	/**
	 * One frame, which {@link #frames} guarantees is non-empty. Every message is a {@code Supplier},
	 * because the eager form rendered and quoted the whole frame once per LINE of it.
	 *
	 * <p>The frame is reported with every terminator shown as LF, and the message says so: the lines
	 * reaching here have already been split, so a CR that caused the failure is no longer in them and
	 * would otherwise print as an ordinary line break — on the one finding where which terminator it
	 * was is the whole point.</p>
	 */
	private static void assertFrameIsWellFormed(List<String> lines) {
		Supplier<String> frame = () -> "got, terminators shown as LF: "
				+ quoted(String.join("\n", lines));
		if (lines.get(0).startsWith(":")) {
			assertEquals(1, lines.size(),
					() -> "a keep-alive frame must stand alone; " + frame.get());
			return;
		}
		assertTrue(lines.get(0).startsWith("event: "),
				() -> "a frame must open with its event line; " + frame.get());
		for (int i = 1; i < lines.size(); i++) {
			assertTrue(lines.get(i).startsWith("data: "),
					() -> "every later line of an event frame must be data — a keep-alive spliced into "
							+ "this event, or a line terminator left inside a payload, would show up "
							+ "here, splitting it in two for every client; " + frame.get());
		}
	}

	/**
	 * A stream or frame for a failure message, with its line terminators visible — both of them, since
	 * a CR is the one this package's tests are about and an unescaped one would overwrite the message
	 * it appears in.
	 */
	static String quoted(String s) {
		return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
	}

	/**
	 * The data buffer as a client would hand it to the page: each {@code data:} line appended with a
	 * trailing LF and the last LF then removed, which is what the specification's dispatch step does.
	 * Equivalently {@code String.join("\n", values)} — written over the buffer {@link #parse} already
	 * accumulates into, not because the two differ.
	 *
	 * <p>What DOES differ, and is the reason this is its own method, is the formulation it replaced:
	 * appending a separator only when the buffer is already non-empty. That drops the leading break on
	 * an EMPTY first data line — the very shape a payload opening with a line terminator produces —
	 * and a client keeps it. Measured over both, they agree on every case except that one.</p>
	 */
	private static String dispatched(StringBuilder data) {
		// Every appended value carries a trailing LF, so the only question here is whether anything
		// was appended at all — a frame with an event line and no data line, which this writer never
		// emits. A test for the last CHARACTER would read as a claim about it that nothing can falsify.
		return data.length() == 0 ? "" : data.substring(0, data.length() - 1);
	}

	/** The event types in emission order, for asserting event ordering. */
	static List<String> types(ByteArrayOutputStream out) {
		List<String> types = new ArrayList<String>();
		for (SseEvent e : parse(out)) {
			types.add(e.type);
		}
		return types;
	}

	/** The first event of the given type, or null when it was never emitted. */
	static SseEvent ofType(ByteArrayOutputStream out, String type) {
		for (SseEvent e : parse(out)) {
			if (e.type.equals(type)) {
				return e;
			}
		}
		return null;
	}

	/**
	 * The first event of the given type, with its {@code data} parsed — failing with a message rather
	 * than an NPE where the event was never emitted.
	 *
	 * <p>Here for the reason this class exists at all: three classes had grown a verbatim
	 * {@code eventData(String)} of their own over {@link #ofType}, and the class javadoc above records
	 * what happened the last time this package kept two copies of one decoder. The mapper is the
	 * caller's, since each test class already holds one.
	 */
	static JsonNode dataOfType(ByteArrayOutputStream out, String type, ObjectMapper mapper)
			throws Exception {
		SseEvent event = ofType(out, type);
		assertNotNull(event, "no '" + type + "' event was emitted");
		return mapper.readTree(event.data);
	}
}
