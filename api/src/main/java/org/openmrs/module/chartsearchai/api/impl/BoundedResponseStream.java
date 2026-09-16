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
 * both ways {@link RemoteLlmEngine} turns a SUCCESSFUL response into heap start by pulling bytes
 * out of one of these — so bounding the stream is what bounds both at once. A non-2xx body is
 * read by {@code RemoteLlmEngine.readTruncatedErrorBody} under its own, tighter ceiling and does
 * not come through here. The ceiling the
 * ticket asks for "per line, per chunk and cumulative" falls out of the cumulative one rather
 * than needing three counters: {@code BufferedReader.readLine()} cannot buffer a line the
 * stream never yielded, an SSE chunk cannot exceed what the line carried, and the parser's
 * accumulated text cannot exceed the chunks. A peer sending one endless line and a peer
 * sending endless short ones are the same peer here.</p>
 *
 * <p>Closing this closes the underlying body, which is what cancels the exchange and stops the peer
 * — so the caller that reads through it must close it on the failure path too. Every caller does,
 * by reading inside a try-with-resources.</p>
 *
 * <p><b>Several clauses here are defence in depth and the suite does not discriminate them</b>,
 * said once rather than at each: the {@code len} clamp, the post-throw guard in each read, and
 * the single-byte {@link #read()} override ENTIRE — its counting included, not merely its guard.
 * Measured against a loopback peer, production reaches this class through exactly two callers,
 * and neither skips, reads a byte at a time, asks for more than 16384 at once, or reads on after
 * the throw; each of those clauses can be deleted with every test still green. They are kept
 * because each closes a hole a future caller could open in the count, and named here because a
 * clause no test discriminates is one the next change can delete for free without knowing it. The
 * list is what was measured, not a proof that nothing else here is undiscriminated.</p>
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
		if (delivered > limit) {
			throw new ResponseTooLargeException(limit);
		}
		int b = in.read();
		if (b >= 0) {
			count(1);
		}
		return b;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (delivered > limit) {
			// Only reachable if a caller kept reading after the throw. Saying so beats the `0` the
			// clamp below would otherwise compute, which every caller would read as end-of-stream.
			throw new ResponseTooLargeException(limit);
		}
		// Ask for at most one byte beyond the ceiling: enough to notice the peer went past it,
		// and never enough for the overshoot to be the thing that fills the heap. See the class
		// javadoc for why no test discriminates this.
		//
		// Only NARROW the caller's request, never widen it and never floor it: `readAllBytes`
		// passes len == 0 to probe for end-of-stream against a full buffer, and answering that
		// with a read of 1 overruns the buffer — measured. And `room` is checked positive because
		// it overflows negative for a limit near Long.MAX_VALUE, which made `allowed` 0 and left
		// `readAllBytes` spinning on zero-length reads rather than throwing — measured at 780 s
		// of CPU. An unusable ceiling stops clamping; it does not stop the read.
		long room = limit - delivered + 1;
		int allowed = (room > 0 && room < len) ? (int) room : len;
		int n = in.read(b, off, allowed);
		if (n > 0) {
			count(n);
		}
		return n;
	}

	/**
	 * Counted, not delegated. {@link FilterInputStream#skip} would hand straight to the peer and
	 * leave those bytes out of the total — harmless for heap, since skipping allocates nothing,
	 * but it would let a peer put arbitrarily many bytes past the ceiling and keep the stream
	 * open. See the class javadoc for why no test discriminates this.
	 */
	@Override
	public long skip(long n) throws IOException {
		long skipped = in.skip(Math.max(0L, Math.min(n, limit - delivered + 1)));
		if (skipped > 0) {
			delivered += skipped;
			if (delivered > limit) {
				throw new ResponseTooLargeException(limit);
			}
		}
		return skipped;
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
