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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

/**
 * Exercises the real {@link PrewarmRefreshExecutor} debounce/gating/membership logic, with the
 * Context-backed seams (the enabled flag, the on-disk pinned check) overridden to in-memory fakes —
 * the same seam pattern the other executor tests use. The scheduling, coalescing, and re-pin
 * dispatch are the production code under test.
 */
public class PrewarmRefreshExecutorTest {

	private static Patient patient(int id) {
		Patient p = new Patient();
		p.setPatientId(id);
		p.setUuid("uuid-" + id);
		return p;
	}

	@Test
	public void onChartWrite_shouldDoNothing_whenRefreshDisabled() throws Exception {
		TestableRefreshExecutor ex = new TestableRefreshExecutor();
		ex.enabled = false;
		ex.pinned = true;

		ex.onChartWrite(patient(1));

		Thread.sleep(120);
		assertEquals(0, ex.repins.get(), "refresh off must schedule nothing");
	}

	@Test
	public void onChartWrite_shouldDoNothing_whenPatientNotPinned() throws Exception {
		TestableRefreshExecutor ex = new TestableRefreshExecutor();
		ex.enabled = true;
		ex.pinned = false; // not a corpus member

		ex.onChartWrite(patient(1));

		Thread.sleep(120);
		assertEquals(0, ex.repins.get(),
				"a patient not already in the pinned corpus must not be re-pinned (corpus does not grow here)");
	}

	@Test
	public void onChartWrite_shouldRepinAPinnedPatient_afterTheDebounceWindow() throws Exception {
		TestableRefreshExecutor ex = new TestableRefreshExecutor();
		ex.enabled = true;
		ex.pinned = true;
		ex.debounceMs = 50;

		ex.onChartWrite(patient(7));

		Thread.sleep(250);
		assertEquals(1, ex.repins.get(), "an enabled, pinned patient must be re-pinned once");
		assertEquals(java.util.Collections.singletonList(7), ex.repinnedIds, "the right patient is re-pinned");
	}

	@Test
	public void onChartWrite_shouldCoalesceABurstOfWritesIntoOneRepin() throws Exception {
		TestableRefreshExecutor ex = new TestableRefreshExecutor();
		ex.enabled = true;
		ex.pinned = true;
		ex.debounceMs = 80;

		// Five rapid writes to the same patient (e.g. an encounter save writing many obs).
		for (int i = 0; i < 5; i++) {
			ex.onChartWrite(patient(3));
		}

		Thread.sleep(300);
		assertEquals(1, ex.repins.get(),
				"a burst of writes to one patient must coalesce into a single re-pin");
	}

	@Test
	public void repin_shouldCallWarmupWithPinTrue() {
		RecordingChartSearchService recorder = new RecordingChartSearchService();
		TestableRefreshExecutor ex = new TestableRefreshExecutor();
		ex.setChartSearchService(recorder);

		ex.repin(patient(9));

		assertEquals(java.util.Collections.singletonList(9), recorder.warmedPatientIds,
				"the re-pin must warm the patient");
		assertTrue(recorder.allPinned, "the re-pin must pass pin=true so the entry rejoins the durable corpus");
	}

	// ---- test doubles --------------------------------------------------------------------------

	private static final class TestableRefreshExecutor extends PrewarmRefreshExecutor {

		boolean enabled;

		boolean pinned;

		long debounceMs = 50;

		final AtomicInteger repins = new AtomicInteger();

		final List<Integer> repinnedIds = new CopyOnWriteArrayList<Integer>();

		@Override
		protected boolean isRefreshEnabled() {
			return enabled;
		}

		@Override
		protected boolean isPinned(String patientUuid) {
			return pinned;
		}

		@Override
		protected long getDebounceMs() {
			return debounceMs;
		}

		// Count the scheduled re-pin instead of dispatching to an OpenMRS daemon thread (no Context).
		@Override
		protected void runRepin(Patient patient) {
			repins.incrementAndGet();
			repinnedIds.add(patient.getPatientId());
		}
	}

	private static final class RecordingChartSearchService implements ChartSearchService {

		final List<Integer> warmedPatientIds = new CopyOnWriteArrayList<Integer>();

		boolean allPinned = true;

		@Override
		public void warmup(Patient patient, boolean pin) {
			warmedPatientIds.add(patient.getPatientId());
			if (!pin) {
				allPinned = false;
			}
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			throw new UnsupportedOperationException();
		}
	}
}
