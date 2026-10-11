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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class TurnCancellationTest {

	private static final class CountingCloseable implements Closeable {

		final AtomicInteger closes = new AtomicInteger();

		@Override
		public void close() throws IOException {
			closes.incrementAndGet();
		}
	}

	@Test
	public void startsNotCancelled() {
		assertFalse(new TurnCancellation().isCancelled());
	}

	@Test
	public void cancelClosesTheBoundResourceAndFlipsTheFlag() {
		TurnCancellation cancellation = new TurnCancellation();
		CountingCloseable resource = new CountingCloseable();
		cancellation.bindCloseable(resource);

		cancellation.cancel();

		assertTrue(cancellation.isCancelled());
		assertEquals(1, resource.closes.get());
	}

	@Test
	public void cancelIsIdempotentAndOnlyClosesOnce() {
		TurnCancellation cancellation = new TurnCancellation();
		CountingCloseable resource = new CountingCloseable();
		cancellation.bindCloseable(resource);

		cancellation.cancel();
		cancellation.cancel();

		assertEquals(1, resource.closes.get());
	}

	@Test
	public void bindingAfterCancellationClosesImmediately() {
		// A fast preempt can race the provider's own setup — cancel() may run before the
		// provider has anything open to bind yet.
		TurnCancellation cancellation = new TurnCancellation();
		cancellation.cancel();

		CountingCloseable resource = new CountingCloseable();
		cancellation.bindCloseable(resource);

		assertEquals(1, resource.closes.get());
	}

	@Test
	public void neverCancelledNeverTouchesTheBoundResource() {
		TurnCancellation cancellation = new TurnCancellation();
		CountingCloseable resource = new CountingCloseable();
		cancellation.bindCloseable(resource);

		assertEquals(0, resource.closes.get());
	}
}
