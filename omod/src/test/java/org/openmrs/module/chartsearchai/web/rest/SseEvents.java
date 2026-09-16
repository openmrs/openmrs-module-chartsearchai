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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
		String type = null;
		StringBuilder data = new StringBuilder();
		for (String line : LINE_TERMINATORS.split(new String(out.toByteArray(), StandardCharsets.UTF_8), -1)) {
			if (line.isEmpty()) {
				// The dispatch line. An event with no "event:" field is untyped, which the
				// controller never writes, so it is dropped rather than given a name here.
				if (type != null) {
					events.add(new SseEvent(type, dispatched(data)));
				}
				type = null;
				data.setLength(0);
				continue;
			}
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
			events.add(new SseEvent(type, dispatched(data)));
		}
		return events;
	}

	/**
	 * The data buffer as a client would hand it to the page: each {@code data:} line appended with a
	 * trailing LF and the last LF then removed, which is what the specification's dispatch step does.
	 *
	 * <p>Appending the LF per line rather than joining is the difference that shows on an EMPTY first
	 * data line — the very shape a payload opening with a line terminator produces — where joining
	 * loses the leading break a client keeps.</p>
	 */
	private static String dispatched(StringBuilder data) {
		if (data.length() > 0 && data.charAt(data.length() - 1) == '\n') {
			return data.substring(0, data.length() - 1);
		}
		return data.toString();
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
