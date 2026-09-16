/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A response body that stops instead of growing: it hands on at most {@code limit} bytes and then
 * raises {@link ResponseTooLargeException}.
 *
 * <p>Issue #446. The peer behind {@code chartsearchai.llm.remote.endpointUrl} is untrusted, and
 * every way {@link RemoteLlmEngine} turns its answer into heap starts by pulling bytes out of
 * one of these — so bounding the stream is what bounds all of them at once. The ceiling the
 * ticket asks for "per line, per chunk and cumulative" falls out of the cumulative one rather
 * than needing three counters: {@code BufferedReader.readLine()} cannot buffer a line the
 * stream never yielded, an SSE chunk cannot exceed what the line carried, and the parser's
 * accumulated text cannot exceed the chunks. A peer sending one endless line and a peer
 * sending endless short ones are the same peer here.</p>
 *
 * <p>Closing this closes the underlying body, which is what cancels the exchange and stops the peer
 * — so the caller that reads through it must close it on the failure path too. Every caller does,
 * by reading inside a try-with-resources.</p>
 */
final class BoundedResponseStream extends FilterInputStream {

	/**
	 * Raised when the peer sent more than it was allowed to. An {@link IOException} so that it
	 * travels the read path a body read already declares, and its own type so that
	 * {@link RemoteLlmEngine} can tell it from a connection that merely broke and say so.
	 */
	static final class ResponseTooLargeException extends IOException {

		private static final long serialVersionUID = 1L;

		private final long limit;

		ResponseTooLargeException(long limit) {
			super("Response exceeded the " + limit + "-byte ceiling and was abandoned");
			this.limit = limit;
		}

		/** The ceiling that was exceeded, for a caller composing an operator-facing message. */
		long getLimit() {
			return limit;
		}
	}

	private final long limit;

	private long delivered;

	BoundedResponseStream(InputStream in, long limit) {
		super(in);
		this.limit = limit;
	}

	@Override
	public int read() throws IOException {
		int b = in.read();
		if (b >= 0) {
			count(1);
		}
		return b;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		// Ask for at most one byte beyond the ceiling: enough to notice the peer went past it, and
		// never enough for the overshoot to be the thing that fills the heap.
		int allowed = (int) Math.min(len, limit - delivered + 1);
		int n = in.read(b, off, allowed);
		if (n > 0) {
			count(n);
		}
		return n;
	}

	/**
	 * Records {@code n} more bytes and refuses the read once the total passes the ceiling. A body of
	 * exactly {@code limit} bytes is allowed through: the throw is for going PAST it, so a legitimate
	 * answer that happens to land on the boundary is not cut off.
	 */
	private void count(int n) throws ResponseTooLargeException {
		delivered += n;
		if (delivered > limit) {
			throw new ResponseTooLargeException(limit);
		}
	}
}
