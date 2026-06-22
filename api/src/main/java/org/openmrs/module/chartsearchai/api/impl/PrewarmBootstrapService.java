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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Background "prewarm bootstrap": sweeps patients one-by-one and pre-fills (and pins) each one's
 * chart KV cache, so the first AI query on a patient never visited this process still lands warm.
 * It is the LLM-prefill analog of querystore's index bootstrap — a daemon-thread sweep with a
 * resumable cursor persisted to {@code <appdata>/chartsearchai/prewarm-progress.json}, so a crash
 * or restart continues where it stopped rather than re-prefilling from scratch.
 *
 * <p>Distinct from {@link WarmupExecutor} (which warms ONE patient on chart open, ad-hoc, LRU-capped):
 * this sweep pins every entry ({@code warmup(patient, true)}) so the durable corpus is exempt from
 * {@code kvCacheMaxEntries}. On hosts with disk for every patient that keeps them all warm; the
 * optional {@link ChartSearchAiConstants#GP_LLM_KV_CACHE_MAX_PINNED_ENTRIES} valve bounds the size of
 * the pinned corpus when a host wants a ceiling on it.
 *
 * <p>Concurrency: at most one sweep runs at a time (guarded by {@link #running}); each
 * {@code warmup} serializes on {@link LocalLlmEngine}'s monitor against live traffic, and
 * {@code throttleMs} widens the gap so clinician queries interleave. The patient enumeration is a
 * lightweight, batched, id-only SQL cursor ({@code select patient_id ... where patient_id > ?}) so
 * it scales to large facilities without loading every {@link Patient} into memory at once.
 */
@Component("chartSearchAi.prewarmBootstrapService")
public class PrewarmBootstrapService {

	private static final Logger log = LoggerFactory.getLogger(PrewarmBootstrapService.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int ENUMERATION_BATCH = 500;

	static final String PROGRESS_FILENAME = "prewarm-progress.json";

	public static final String STATUS_IDLE = "IDLE";

	public static final String STATUS_RUNNING = "RUNNING";

	public static final String STATUS_COMPLETED = "COMPLETED";

	public static final String STATUS_STOPPED = "STOPPED";

	public static final String SCOPE_ALL = "all";

	@Autowired
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

	private volatile DaemonToken daemonToken;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private volatile boolean cancelRequested = false;

	/** Live progress; also mirrored to disk after each patient for crash/restart resume. */
	private volatile PrewarmProgress progress = PrewarmProgress.idle();

	public void setDaemonToken(DaemonToken token) {
		this.daemonToken = token;
	}

	/** Test/wiring seam: inject the chart-search router (production autowires it). */
	void setChartSearchService(ChartSearchService chartSearchService) {
		this.chartSearchService = chartSearchService;
	}

	/**
	 * Trigger or steer the sweep. {@code action}:
	 * <ul>
	 *   <li>{@code start} (default) — resume from the persisted cursor, or begin if none.</li>
	 *   <li>{@code restart} — clear the cursor and sweep from the beginning.</li>
	 *   <li>{@code stop} — request cancellation of a running sweep.</li>
	 * </ul>
	 * Returns the current status snapshot immediately (the sweep itself runs on a daemon thread);
	 * starting an already-running sweep is a no-op that just returns the live status.
	 */
	public synchronized PrewarmStatus trigger(String scope, String action) {
		String act = action == null || action.trim().isEmpty() ? "start" : action.trim().toLowerCase();
		if ("stop".equals(act)) {
			cancelRequested = true;
			return getStatus();
		}
		if (running.get()) {
			return getStatus();
		}
		if (!isPrewarmEnabled()) {
			return getStatus();
		}
		PrewarmProgress resumeFrom = "restart".equals(act) ? null : loadProgress();
		final long startCursor = resumeFrom == null ? 0L : resumeFrom.cursorPatientId;
		final long carriedDone = resumeFrom == null ? 0L : resumeFrom.done;
		final long carriedFailed = resumeFrom == null ? 0L : resumeFrom.failed;
		final String useScope = scope == null || scope.trim().isEmpty() ? SCOPE_ALL : scope.trim();

		DaemonToken token = daemonToken;
		if (token == null) {
			log.warn("DaemonToken not yet available; cannot start prewarm sweep");
			return getStatus();
		}
		cancelRequested = false;
		running.set(true);
		progress = PrewarmProgress.running(useScope, startCursor, carriedDone, carriedFailed);
		persistProgress(progress);

		Daemon.runInDaemonThread(() -> {
			try {
				runSweep(useScope, startCursor, carriedDone, carriedFailed);
			}
			catch (Exception e) {
				log.warn("Prewarm sweep aborted: {}", e.getMessage(), e);
			}
			finally {
				running.set(false);
			}
		}, token);
		return getStatus();
	}

	void runSweep(String scope, long startCursor, long carriedDone, long carriedFailed) {
		long cursor = startCursor;
		long done = carriedDone;
		long failed = carriedFailed;
		long total = countPatients();
		long throttleMs = getThrottleMs();
		int maxPinned = getMaxPinnedEntries();

		List<Long> batch;
		while (!cancelRequested && !(batch = enumeratePatientIdsAfter(cursor, ENUMERATION_BATCH)).isEmpty()) {
			for (Long id : batch) {
				if (cancelRequested) {
					break;
				}
				if (maxPinned > 0 && countPinnedEntries() >= maxPinned) {
					log.warn("Prewarm reached maxPinnedEntries={}; stopping (no further pinning)", maxPinned);
					cancelRequested = true;
					break;
				}
				progress = progress.withCurrent(id, total, done, failed);
				try {
					Patient patient = getPatient(id);
					if (patient != null) {
						chartSearchService.warmup(patient, true);
					}
					done++;
				}
				catch (Exception e) {
					failed++;
					log.warn("Prewarm failed for patient [id={}]: {}", id, e.getMessage());
				}
				cursor = id;
				progress = progress.advanced(cursor, total, done, failed);
				persistProgress(progress);
				sleep(throttleMs);
			}
		}
		progress = progress.finished(cancelRequested ? STATUS_STOPPED : STATUS_COMPLETED, total, done, failed);
		persistProgress(progress);
		log.warn("Prewarm sweep {}: done={} failed={} cursor={}",
				progress.status, done, failed, cursor);
	}

	public PrewarmStatus getStatus() {
		PrewarmProgress p = progress;
		// On a fresh process before any trigger, surface the persisted cursor so a caller sees prior
		// progress (and autostart resume potential) rather than a bare IDLE.
		if (p.status.equals(STATUS_IDLE)) {
			PrewarmProgress saved = loadProgress();
			if (saved != null) {
				p = saved;
			}
		}
		return new PrewarmStatus(p, running.get(), countPinnedEntries());
	}

	// ---- seams (overridable in tests) ----------------------------------------------------------

	/** Patient ids ordered ascending, strictly greater than {@code afterId}, at most {@code limit}. */
	protected List<Long> enumeratePatientIdsAfter(long afterId, int limit) {
		String sql = "select patient_id from patient where voided = 0 and patient_id > " + afterId
				+ " order by patient_id limit " + limit;
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(sql, true);
		List<Long> ids = new ArrayList<>(rows.size());
		for (List<Object> row : rows) {
			ids.add(((Number) row.get(0)).longValue());
		}
		return ids;
	}

	protected long countPatients() {
		List<List<Object>> rows = Context.getAdministrationService()
				.executeSQL("select count(*) from patient where voided = 0", true);
		return rows.isEmpty() ? 0L : ((Number) rows.get(0).get(0)).longValue();
	}

	protected Patient getPatient(long id) {
		return Context.getPatientService().getPatient((int) id);
	}

	protected boolean isPrewarmEnabled() {
		return ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_PREWARM_ENABLED, ChartSearchAiConstants.DEFAULT_PREWARM_ENABLED);
	}

	protected long getThrottleMs() {
		return parseLong(ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_PREWARM_THROTTLE_MS, null),
				ChartSearchAiConstants.DEFAULT_PREWARM_THROTTLE_MS);
	}

	protected int getMaxPinnedEntries() {
		return (int) parseLong(ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_LLM_KV_CACHE_MAX_PINNED_ENTRIES, null),
				ChartSearchAiConstants.DEFAULT_LLM_KV_CACHE_MAX_PINNED_ENTRIES);
	}

	/** Count of pinned KV entries (.bin files with a .pin sidecar) currently on disk. */
	protected int countPinnedEntries() {
		File dir = pinnedDir();
		if (dir == null || !dir.isDirectory()) {
			return 0;
		}
		File[] pins = dir.listFiles((d, name) -> name.endsWith(LocalLlmEngine.PIN_SUFFIX));
		return pins == null ? 0 : pins.length;
	}

	/** The effective KV-cache directory, or null when disk KV persistence is disabled. */
	protected File pinnedDir() {
		String configured = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_LLM_KV_CACHE_DIR, null);
		String resolved = LocalLlmEngine.resolveKvCacheDir(configured,
				OpenmrsUtil.getApplicationDataDirectory());
		return resolved == null ? null : new File(resolved);
	}

	protected File progressFile() {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		return new File(dir, PROGRESS_FILENAME);
	}

	protected void sleep(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cancelRequested = true;
		}
	}

	// ---- progress persistence ------------------------------------------------------------------

	void persistProgress(PrewarmProgress p) {
		File f = progressFile();
		try {
			File parent = f.getParentFile();
			if (parent != null && !parent.isDirectory()) {
				parent.mkdirs();
			}
			MAPPER.writeValue(f, p);
		}
		catch (IOException e) {
			log.warn("Could not persist prewarm progress to {}: {}", f, e.getMessage());
		}
	}

	PrewarmProgress loadProgress() {
		File f = progressFile();
		if (!f.isFile()) {
			return null;
		}
		try {
			return MAPPER.readValue(f, PrewarmProgress.class);
		}
		catch (IOException e) {
			log.warn("Could not read prewarm progress from {}: {}", f, e.getMessage());
			return null;
		}
	}

	private static long parseLong(String v, long fallback) {
		if (v == null || v.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(v.trim());
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}
}
