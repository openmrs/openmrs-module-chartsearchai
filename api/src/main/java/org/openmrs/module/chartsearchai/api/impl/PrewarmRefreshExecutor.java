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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openmrs.Patient;
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

/**
 * Keeps the pinned prewarm corpus fresh across chart edits. When a chart write touches a patient who
 * is <em>already pinned</em> (see {@link LocalLlmEngine#hasPinnedEntry}), this schedules a debounced,
 * single-patient re-pin ({@code warmup(patient, true)}) on an OpenMRS daemon thread — so the edited
 * patient's KV is recomputed under its new chart hash and re-added to the durable corpus, without a
 * manual re-sweep.
 *
 * <p>Driven from chart-write detection ({@code ChartSearchEventListener}) via {@code IndexingHelper.onChartWrite}, gated by
 * {@code chartsearchai.prewarm.refreshOnEdit} (default off). Two properties make this safe on the
 * write path:
 * <ul>
 *   <li><b>Membership-scoped:</b> only patients with an existing {@code .pin} are re-pinned; patients
 *       not in the corpus are left to the reactive chart-open warmup, so the corpus never grows here.</li>
 *   <li><b>Debounced:</b> a burst of writes to one patient (e.g. an encounter save writing many obs)
 *       collapses to a single re-pin fired after {@code refreshDebounceMs} of quiet, instead of one
 *       10–20s prefill per write.</li>
 * </ul>
 * The re-pin itself serializes on {@link LocalLlmEngine}'s monitor like all other inference, so it
 * interleaves with live traffic on the single slot.
 */
@Component("chartSearchAi.prewarmRefreshExecutor")
public class PrewarmRefreshExecutor {

	private static final Logger log = LoggerFactory.getLogger(PrewarmRefreshExecutor.class);

	@Autowired
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

	private volatile DaemonToken daemonToken;

	/** Single daemon-thread timer for debounce delays (it only schedules; the re-pin work itself runs
	 *  on a separate OpenMRS daemon thread via {@link #runRepin}). */
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "chartsearchai-prewarm-refresh");
		t.setDaemon(true);
		return t;
	});

	/** Patient id -> its pending (not-yet-fired) re-pin, so a new write can cancel and reschedule it. */
	private final Map<Integer, ScheduledFuture<?>> pending = new ConcurrentHashMap<Integer, ScheduledFuture<?>>();

	public void setDaemonToken(DaemonToken token) {
		this.daemonToken = token;
	}

	/** Test/wiring seam: inject the chart-search router (production autowires it). */
	void setChartSearchService(ChartSearchService chartSearchService) {
		this.chartSearchService = chartSearchService;
	}

	/**
	 * Called from the chart-write advice. No-op unless the feature is on AND the patient is already in
	 * the pinned corpus; otherwise schedules a debounced re-pin.
	 */
	public void onChartWrite(Patient patient) {
		if (patient == null || patient.getPatientId() == null) {
			return;
		}
		if (!isRefreshEnabled()) {
			return;
		}
		if (!isPinned(patient.getUuid())) {
			return;
		}
		scheduleRepin(patient, getDebounceMs());
	}

	/** Cancels any pending re-pin for this patient and schedules a fresh one {@code delayMs} out, so a
	 *  burst of writes coalesces into the last-scheduled single re-pin. */
	protected void scheduleRepin(final Patient patient, long delayMs) {
		final Integer id = patient.getPatientId();
		ScheduledFuture<?> prev = pending.get(id);
		if (prev != null) {
			prev.cancel(false);
		}
		ScheduledFuture<?> future = scheduler.schedule(new Runnable() {

			@Override
			public void run() {
				pending.remove(id);
				runRepin(patient);
			}
		}, delayMs, TimeUnit.MILLISECONDS);
		pending.put(id, future);
	}

	/** Runs the re-pin on an OpenMRS daemon thread (chart serialization touches the DB). */
	protected void runRepin(final Patient patient) {
		DaemonToken token = daemonToken;
		if (token == null) {
			log.warn("DaemonToken not yet available; skipping prewarm re-pin for patient [id={}]",
					patient.getPatientId());
			return;
		}
		Daemon.runInDaemonThread(new Runnable() {

			@Override
			public void run() {
				repin(patient);
			}
		}, token);
	}

	/** The re-pin action: a pinning warmup so the edited chart re-enters the durable corpus. */
	void repin(Patient patient) {
		try {
			chartSearchService.warmup(patient, true);
		}
		catch (Exception e) {
			log.warn("Prewarm re-pin failed for patient [id={}]: {}", patient.getPatientId(), e.getMessage());
		}
	}

	public void shutdown() {
		scheduler.shutdownNow();
	}

	// ---- seams (overridable in tests) ----------------------------------------------------------

	protected boolean isRefreshEnabled() {
		return ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_PREWARM_REFRESH_ON_EDIT,
				ChartSearchAiConstants.DEFAULT_PREWARM_REFRESH_ON_EDIT);
	}

	protected long getDebounceMs() {
		String v = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_PREWARM_REFRESH_DEBOUNCE_MS, null);
		if (v == null || v.trim().isEmpty()) {
			return ChartSearchAiConstants.DEFAULT_PREWARM_REFRESH_DEBOUNCE_MS;
		}
		try {
			return Long.parseLong(v.trim());
		}
		catch (NumberFormatException e) {
			return ChartSearchAiConstants.DEFAULT_PREWARM_REFRESH_DEBOUNCE_MS;
		}
	}

	/** Whether the patient (KV scope = UUID) currently has a pinned entry on disk. */
	protected boolean isPinned(String patientUuid) {
		String dir = LocalLlmEngine.resolveKvCacheDir(
				ChartSearchAiUtils.getStringGlobalProperty(ChartSearchAiConstants.GP_LLM_KV_CACHE_DIR, null),
				OpenmrsUtil.getApplicationDataDirectory());
		return dir != null && LocalLlmEngine.hasPinnedEntry(new File(dir), patientUuid);
	}
}
