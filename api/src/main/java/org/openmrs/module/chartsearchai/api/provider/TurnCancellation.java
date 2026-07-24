/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CancellationSignal} that can also forcibly interrupt whatever blocking resource a
 * provider is currently waiting on. {@code isCancelled()} alone is enough for the "check at my
 * next natural checkpoint" contract {@link CancellationSignal} documents, but a provider blocked
 * inside one long synchronous call (e.g. {@link HttpHubStreamTransport} reading an SSE response
 * body) has no such checkpoint to poll — it has to be unblocked from another thread. Binding the
 * open {@link Closeable} here lets {@link #cancel()} do exactly that: closing it causes the
 * blocked read to fail with an {@code IOException}, which also tears down the underlying HTTP
 * connection to the far side (letting it observe the disconnect and free its own resources).
 */
public final class TurnCancellation implements CancellationSignal {

	private static final Logger log = LoggerFactory.getLogger(TurnCancellation.class);

	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private final AtomicReference<Closeable> resource = new AtomicReference<>();

	@Override
	public boolean isCancelled() {
		return cancelled.get();
	}

	/**
	 * Registers the resource to force-close if this turn is cancelled. If this turn was already
	 * cancelled before anything was bound (a fast preempt racing the provider's own setup), the
	 * resource is closed immediately instead of being held.
	 */
	public void bindCloseable(Closeable closeable) {
		resource.set(closeable);
		if (cancelled.get()) {
			closeQuietly(resource.getAndSet(null));
		}
	}

	/** Cancels this turn and force-closes whatever resource is currently bound, if any. Idempotent. */
	public void cancel() {
		if (cancelled.compareAndSet(false, true)) {
			closeQuietly(resource.getAndSet(null));
		}
	}

	private static void closeQuietly(Closeable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (IOException e) {
			log.debug("Ignoring close failure while cancelling a turn", e);
		}
	}
}
