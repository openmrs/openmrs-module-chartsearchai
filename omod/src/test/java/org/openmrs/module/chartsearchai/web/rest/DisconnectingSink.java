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
import java.io.IOException;
import java.io.OutputStream;

/**
 * A client that goes away part-way through the stream: it accepts a chosen number of EVENT frames
 * and refuses every later one, so a case can choose where in the stream the peer disappeared.
 *
 * <p>An event frame is recognised by NOT opening with the {@code :} of an SSE comment, rather than
 * by matching {@code event:}, so a future frame shape the controller writes is refused too instead
 * of quietly turning a case green. Keep-alive comments pass through, which is what keeps the
 * interval out of the arithmetic — and what lets a case see whether the timer went on writing after
 * the generation loop unwound.
 *
 * <p>Shared rather than nested per test class, and the reason is the one
 * {@code CapturingAuditLogService}'s javadoc records about itself: issue #450 was about to add a
 * second copy of this, differing from {@code ChartSearchAiStreamKeepAliveTest}'s by one threshold
 * field, so it was promoted here instead. What two copies could drift about is the one thing both
 * of their javadocs said must not drift — what counts as a frame.
 */
final class DisconnectingSink extends OutputStream {

	private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

	private final int acceptEventFrames;

	private int eventFramesSeen;

	/**
	 * How many frames were refused, read by a case after {@code streamAnswer} returns as its canary
	 * that the disconnect actually happened.
	 *
	 * <p>Needs no synchronization: only event frames are refused, and only the calling thread writes
	 * those. That is a narrower argument than the one {@code ChartSearchAiStreamKeepAliveTest}'s
	 * {@code RefusingSink} needs for its counters, which the keep-alive thread touches.
	 */
	int refused;

	/** @param acceptEventFrames event frames to accept before refusing; 0 refuses every one */
	DisconnectingSink(int acceptEventFrames) {
		this.acceptEventFrames = acceptEventFrames;
	}

	@Override
	public void write(int b) {
		sink.write(b);
	}

	@Override
	public void write(byte[] frame, int off, int len) throws IOException {
		if (len > 0 && frame[off] != ':' && eventFramesSeen++ >= acceptEventFrames) {
			refused++;
			throw new IOException("client gone");
		}
		sink.write(frame, off, len);
	}

	ByteArrayOutputStream sink() {
		return sink;
	}

	String text() {
		return SseEvents.text(sink);
	}
}
