/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.LogCapture;

/**
 * The drug-reference load is lazy, cached for the life of the bean, and reached from every answer, so
 * the first several queries after a restart can enter it at once. Issue #149 gave that load two side
 * effects beyond filling the cache: a WARN when it yields nothing, and a retained
 * {@link DrugReferenceLoad} that {@link DrugReferenceService#getLoadStatus()} and the status endpoint
 * report. Both must happen exactly ONCE per bean however many callers race, and every caller must be
 * handed the same outcome as the entries it describes.
 *
 * <p>What would go wrong otherwise: a WARN per racing caller turns the one line that says
 * "drug-safety checking is inert" into a burst an operator learns to filter, a second load re-reads
 * the dataset (19 MB for the DDInter knowledge base) on the query thread, and a status rebuilt per
 * reader could describe a load other than the one whose entries the safety layer is using — the exact
 * confusion the retained outcome exists to remove.
 *
 * <p>Load count is taken from the source itself rather than from the log, so these tests do not
 * depend on which dataset the global properties happen to select in the shared test JVM (the DDInter
 * parser logs one more INFO line than the curated one). The source is the module's own documented
 * test seam; everything under test — the double-checked load, the WARN and the retained outcome — is
 * production code reached through {@link DrugReferenceService#getAll()} and
 * {@link DrugReferenceService#getLoadStatus()}.
 *
 * <p>The pairing itself — a populated cache is never accompanied by a status saying nothing was loaded —
 * used to rest on the ORDER of two volatile writes, which is correct by the Java memory model and
 * survives any test: the window is a single volatile write wide and cannot be hit reliably from one
 * (issue #158). Since that issue the two are published through a single immutable holder, so no order
 * exists to get wrong, and what is left to assert is the pairing the holder carries — which is
 * {@link #aCompletedLoadIsNeverPublishedWithAStatusThatSaysNothingWasLoaded}.
 */
public class DrugReferenceLoadConcurrencyTest {

	private static final int RACERS = 8;

	@Test
	public void racedFirstLoadHappensOnceAndEveryCallerSeesTheOneRetainedOutcome() throws Exception {
		// The real curated dataset through its real parser, so the entries are the shipped ones.
		final List<DrugReference> entries = new JsonDrugReferenceSource().load();
		assertFalse(entries.isEmpty(), "the bundled curated dataset should have loaded");
		AtomicInteger loads = new AtomicInteger();
		DrugReferenceService service = countingService(loads, entries);

		List<DrugReferenceLoad> observed;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			observed = raceGetAllThenReadStatus(service);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a load that produced entries is healthy and must stay quiet. Captured: "
							+ capture.describeAll());
		}

		assertEquals(1, loads.get(),
				"the dataset must be read ONCE however many callers raced the lazy load");
		assertEquals(entries.size(), service.getAll().size(), "the race must have produced the entries");
		for (DrugReferenceLoad seen : observed) {
			assertSame(observed.get(0), seen,
					"every caller must be handed the ONE retained outcome, not a per-reader rebuild");
			assertTrue(seen.isLoaded());
			assertFalse(seen.isInert());
			assertEquals(service.getAll().size(), seen.getEntryCount(),
					"the reported count must be the count of the entries actually in force");
		}
		assertSame(observed.get(0), service.getLoadStatus(),
				"a later reader gets the same retained outcome, so repeated status reads do no work");
	}

	/**
	 * The inert case, which is the one with a consequence: the WARN says the whole drug-safety feature
	 * is off, and it must be said once rather than once per racing caller.
	 */
	@Test
	public void racedLoadOfASourceThatYieldsNothingWarnsExactlyOnce() throws Exception {
		AtomicInteger loads = new AtomicInteger();
		DrugReferenceService service = countingService(loads, Collections.<DrugReference> emptyList());

		List<DrugReferenceLoad> observed;
		List<String> warnings;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			observed = raceGetAllThenReadStatus(service);
			warnings = capture.messagesAt(Level.WARN);
		}

		assertEquals(1, loads.get(), "the load must still happen only once");
		assertEquals(1, warnings.size(),
				"a raced inert load must warn ONCE — one WARN per caller would make the only signal "
						+ "that drug-safety checking is off into noise. Captured: " + warnings);
		for (DrugReferenceLoad seen : observed) {
			assertSame(observed.get(0), seen);
			assertTrue(seen.isInert(), "a configured source that yielded nothing is inert");
			assertEquals(0, seen.getEntryCount());
		}
	}

	/**
	 * Issue #158. The status exists so that "what is actually loaded" cannot be read stale, so the one
	 * state it must never be in is the one that defeats it: entries in the cache alongside a status
	 * reporting that nothing was loaded. Asserted for every racing reader, all of which reach the cache
	 * through the lock-free fast path — the reader the issue is about.
	 *
	 * <p>What this can and cannot prove. Publishing the entries and the outcome through one immutable
	 * holder makes the torn state unreachable BY CONSTRUCTION rather than unlikely, and that is the fix;
	 * no test can demonstrate the absence of a race whose window is one volatile write wide. What this
	 * pins is the remaining way to reach the same torn state — pairing a populated cache with the wrong
	 * outcome as the holder is built — which is a construction error rather than a race, and therefore
	 * deterministic. Verified by mutation: publishing the holder with
	 * {@link DrugReferenceLoad#notLoaded()} reddens this and nothing else in the suite.
	 */
	@Test
	public void aCompletedLoadIsNeverPublishedWithAStatusThatSaysNothingWasLoaded() throws Exception {
		final List<DrugReference> entries = new JsonDrugReferenceSource().load();
		assertFalse(entries.isEmpty(), "the bundled curated dataset should have loaded");
		DrugReferenceService service = countingService(new AtomicInteger(), entries);

		for (DrugReferenceLoad seen : raceGetAllThenReadStatus(service)) {
			assertTrue(seen.isLoaded(),
					"a reader that can see the entries must see a status describing them, not one saying "
							+ "nothing was loaded — that state is what the retained status exists to rule out");
			assertEquals(entries.size(), seen.getEntryCount(),
					"and it must describe THOSE entries, not a different load's");
		}
		assertFalse(service.getAll().isEmpty(), "the entries are in force for the safety layer");
		assertTrue(service.getLoadStatus().isLoaded());
	}

	/** A service whose source counts how many times the lazy load actually read the dataset. */
	private static DrugReferenceService countingService(final AtomicInteger loads,
			final List<DrugReference> entries) {
		DrugReferenceService service = new DrugReferenceService();
		service.setSource(() -> {
			loads.incrementAndGet();
			return entries;
		});
		return service;
	}

	/**
	 * Starts {@link #RACERS} threads that all enter the lazy load together, then have each read the
	 * status the load retained.
	 *
	 * @return what each thread observed, in thread order
	 */
	private static List<DrugReferenceLoad> raceGetAllThenReadStatus(final DrugReferenceService service)
			throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(RACERS);
		try {
			final CyclicBarrier allSetOff = new CyclicBarrier(RACERS);
			List<Future<DrugReferenceLoad>> futures = new ArrayList<Future<DrugReferenceLoad>>();
			for (int i = 0; i < RACERS; i++) {
				futures.add(pool.submit(new Callable<DrugReferenceLoad>() {

					@Override
					public DrugReferenceLoad call() throws Exception {
						allSetOff.await(30, TimeUnit.SECONDS);
						service.getAll();
						return service.getLoadStatus();
					}
				}));
			}
			List<DrugReferenceLoad> observed = new ArrayList<DrugReferenceLoad>();
			for (Future<DrugReferenceLoad> future : futures) {
				observed.add(future.get(30, TimeUnit.SECONDS));
			}
			return observed;
		}
		finally {
			pool.shutdownNow();
		}
	}
}
