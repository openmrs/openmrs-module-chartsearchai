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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the SSE wire format the controller writes, for the streaming tests in this package.
 *
 * <p>Shared because two test classes had each grown their own decoder and they had already
 * drifted: one stripped the single space after {@code data:} and the other kept it, and they split
 * events differently (blank-line blocks versus scanning to the next {@code event:}). Jackson
 * tolerated the difference, so nothing failed — which is exactly why it needed removing rather
 * than fixing twice. The implementation below is the original blank-line-block decoder, moved
 * verbatim so the assertions that already relied on it cannot shift.
 *
 * <p>The single space after {@code event:}/{@code data:} is part of the field delimiter the
 * controller emits ({@code writeSseEvent} writes {@code "data: "}), not payload, so it is dropped.
 */
final class SseEvents {

	private SseEvents() {
	}

	/** Every event written to {@code out} so far, in emission order. */
	static List<SseEvent> parse(ByteArrayOutputStream out) {
		List<SseEvent> events = new ArrayList<SseEvent>();
		for (String block : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n\n")) {
			String type = null;
			StringBuilder data = new StringBuilder();
			for (String line : block.split("\n")) {
				if (line.startsWith("event: ")) {
					type = line.substring(7).trim();
				} else if (line.startsWith("data: ")) {
					data.append(line.substring(6));
				}
			}
			if (type != null) {
				events.add(new SseEvent(type, data.toString()));
			}
		}
		return events;
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
}
