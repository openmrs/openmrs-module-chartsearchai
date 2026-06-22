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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

/**
 * Exercises the real {@link PrewarmBootstrapService#runSweep} and on-disk progress persistence,
 * with the OpenMRS-Context seams (patient enumeration, patient lookup, GP reads, KV dir) overridden
 * to in-memory fakes — the same seam pattern {@code LlmInferenceServiceWarmupIntegrationTest} uses.
 * The sweep orchestration itself is the production code under test, not a reimplementation.
 */
public class PrewarmBootstrapServiceTest {

	private File tempDir;

	private RecordingChartSearchService recorder;

	private TestablePrewarmBootstrapService service;

	@BeforeEach
	public void setUp() throws Exception {
		tempDir = java.nio.file.Files.createTempDirectory("prewarm-test").toFile();
		recorder = new RecordingChartSearchService();
		service = new TestablePrewarmBootstrapService(tempDir);
		service.setChartSearchService(recorder);
	}

	@AfterEach
	public void tearDown() {
		File[] files = tempDir.listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
		tempDir.delete();
	}

	@Test
	public void runSweep_shouldPinEveryPatientInCursorOrderAndCompleteAtTotal() {
		service.patientIds = new ArrayList<>(java.util.Arrays.asList(1L, 2L, 3L, 4L, 5L));

		service.runSweep(PrewarmBootstrapService.SCOPE_ALL, 0L, 0L, 0L);

		assertEquals(java.util.Arrays.asList(1, 2, 3, 4, 5), recorder.warmedPatientIds,
				"every patient must be warmed exactly once, in ascending cursor order");
		assertTrue(recorder.allPinned, "the bootstrap must warm with pin=true so entries join the durable corpus");
		PrewarmStatus status = service.getStatus();
		assertEquals(PrewarmBootstrapService.STATUS_COMPLETED, status.getStatus());
		assertEquals(5L, service.loadProgress().done, "done must reach the patient total");
		assertEquals(5L, service.loadProgress().cursorPatientId, "the cursor must end at the last patient");
	}

	@Test
	public void runSweep_shouldResumeFromCursor_skippingAlreadyProcessedPatients() {
		// Simulates a restart mid-sweep: the cursor is at patient 3, so only ids > 3 are processed,
		// and the carried done count continues rather than resetting.
		service.patientIds = new ArrayList<>(java.util.Arrays.asList(1L, 2L, 3L, 4L, 5L));

		service.runSweep(PrewarmBootstrapService.SCOPE_ALL, 3L, 3L, 0L);

		assertEquals(java.util.Arrays.asList(4, 5), recorder.warmedPatientIds,
				"resuming at cursor 3 must process only patients beyond it");
		assertEquals(5L, service.loadProgress().done, "carried done (3) plus the 2 new must total 5");
	}

	@Test
	public void runSweep_shouldStopPinningWhenMaxPinnedEntriesReached() {
		service.patientIds = new ArrayList<>(java.util.Arrays.asList(1L, 2L, 3L, 4L, 5L));
		service.maxPinned = 2;
		service.pinnedOnDisk = 2; // already at the cap before the first patient

		service.runSweep(PrewarmBootstrapService.SCOPE_ALL, 0L, 0L, 0L);

		assertTrue(recorder.warmedPatientIds.isEmpty(),
				"at the pinned cap the sweep must stop without pinning any further entry");
		assertEquals(PrewarmBootstrapService.STATUS_STOPPED, service.getStatus().getStatus(),
				"hitting the cap is a stop, not a completion");
	}

	@Test
	public void persistProgress_then_loadProgress_shouldRoundTrip() {
		PrewarmProgress p = PrewarmProgress.running("all", 7L, 7L, 1L);
		service.persistProgress(p);

		PrewarmProgress loaded = service.loadProgress();
		assertEquals(PrewarmBootstrapService.STATUS_RUNNING, loaded.status);
		assertEquals(7L, loaded.cursorPatientId);
		assertEquals(7L, loaded.done);
		assertEquals(1L, loaded.failed);
	}

	@Test
	public void getStatus_shouldSurfacePersistedCursorOnAFreshProcess() {
		// A brand-new service instance (in-memory state IDLE) must reflect a cursor left on disk by a
		// prior run, so a status check after restart shows real progress, not a bare IDLE.
		service.persistProgress(PrewarmProgress.running("all", 42L, 42L, 0L));

		TestablePrewarmBootstrapService fresh = new TestablePrewarmBootstrapService(tempDir);
		assertEquals(42L, fresh.getStatus().toMap().get("cursorPatientId"),
				"a fresh process must read the persisted cursor for its status");
		assertFalse(fresh.getStatus().isRunning());
	}

	// ---- test doubles --------------------------------------------------------------------------

	private static final class TestablePrewarmBootstrapService extends PrewarmBootstrapService {

		private final File dir;

		List<Long> patientIds = new ArrayList<>();

		int maxPinned = 0;

		int pinnedOnDisk = 0;

		TestablePrewarmBootstrapService(File dir) {
			this.dir = dir;
		}

		@Override
		protected List<Long> enumeratePatientIdsAfter(long afterId, int limit) {
			List<Long> out = new ArrayList<>();
			for (Long id : patientIds) {
				if (id > afterId && out.size() < limit) {
					out.add(id);
				}
			}
			return out;
		}

		@Override
		protected long countPatients() {
			return patientIds.size();
		}

		@Override
		protected Patient getPatient(long id) {
			Patient p = new Patient();
			p.setPatientId((int) id);
			p.setUuid("uuid-" + id);
			return p;
		}

		@Override
		protected long getThrottleMs() {
			return 0L; // no real sleeping in tests
		}

		@Override
		protected int getMaxPinnedEntries() {
			return maxPinned;
		}

		@Override
		protected int countPinnedEntries() {
			return pinnedOnDisk;
		}

		@Override
		protected File progressFile() {
			return new File(dir, PROGRESS_FILENAME);
		}
	}

	private static final class RecordingChartSearchService implements ChartSearchService {

		final List<Integer> warmedPatientIds = new ArrayList<>();

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
			throw new UnsupportedOperationException("prewarm sweep must never call search");
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer) {
			throw new UnsupportedOperationException("prewarm sweep must never call searchStreaming");
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			throw new UnsupportedOperationException("prewarm sweep must never call searchStreaming");
		}
	}
}
