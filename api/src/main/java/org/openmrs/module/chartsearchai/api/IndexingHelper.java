/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.impl.PrewarmRefreshExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared helper invoked by {@link ChartSearchEventListener} after a patient-data change: invalidate
 * the patient's cached answers and, when enabled, schedule a prewarm re-pin. Chart writes are now
 * detected via core's #6084 service events (not the former per-service AOP advice) — querystore owns
 * retrieval-index freshness via the same events, so chartsearchai re-indexes nothing here; the answer
 * cache and the pinned prewarm corpus are the only chartsearchai-side state a chart write must touch.
 */
final class IndexingHelper {

	private static final Logger log = LoggerFactory.getLogger(IndexingHelper.class);

	/** The router bean that owns the answer cache, resolved through the {@link ChartSearchService}
	 *  interface (the same way {@code WarmupExecutor} and the REST controller reference it). Looked up
	 *  lazily through {@code Context} because this is a static helper, not a Spring-managed bean. */
	private static ChartSearchService answerCache() {
		return Context.getRegisteredComponent(
				"chartSearchAi.chartSearchServiceRouter", ChartSearchService.class);
	}

	/**
	 * Whether the answer cache is on (TTL &gt; 0). Checked by the event listener <em>before</em>
	 * resolving the affected patient, so the default cache-off hot path skips patient extraction.
	 * The answer cache must be invalidated on every chart write regardless of which retrieval
	 * backend is active.
	 */
	static boolean isAnswerCacheEnabled() {
		try {
			ChartSearchService cache = answerCache();
			return cache != null && cache.isCacheEnabled();
		}
		catch (Exception e) {
			log.error("Failed to read answer-cache state; assuming off", e);
			return false;
		}
	}

	/**
	 * Whether per-edit prewarm refresh is on ({@code chartsearchai.prewarm.refreshOnEdit}). A second
	 * reason — besides the answer cache — for the event listener to act on a chart change. Read via the
	 * fail-safe GP helper so a missing context resolves to off.
	 */
	static boolean isPrewarmRefreshEnabled() {
		return ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_PREWARM_REFRESH_ON_EDIT,
				ChartSearchAiConstants.DEFAULT_PREWARM_REFRESH_ON_EDIT);
	}

	/**
	 * The combined cheap gate the event listener checks before resolving the affected patient: proceed
	 * if EITHER the answer cache or per-edit prewarm refresh is on. Replaces the old answer-cache-only
	 * gate so enabling refresh alone is enough to act (the answer cache stays off by default).
	 */
	static boolean shouldHandleChartWrite() {
		return isAnswerCacheEnabled() || isPrewarmRefreshEnabled();
	}

	/**
	 * Reacts to a chart write: invalidate the patient's cached answers (when the answer cache is on)
	 * and schedule a debounced prewarm re-pin (when refresh is on and the patient is already pinned).
	 * Each sub-action is independently gated, so callers need only the {@link #shouldHandleChartWrite}
	 * pre-gate.
	 */
	static void onChartWrite(Patient patient) {
		if (patient == null) {
			return;
		}
		if (isAnswerCacheEnabled()) {
			invalidateAnswerCache(patient);
		}
		if (isPrewarmRefreshEnabled()) {
			maybeRefreshPrewarm(patient);
		}
	}

	/**
	 * Evicts a patient's cached answers after a write to their chart. The answer cache sits in front
	 * of every retrieval backend, so a stale entry would survive an edit no matter which backend
	 * produced it.
	 */
	static void invalidateAnswerCache(Patient patient) {
		if (patient == null) {
			return;
		}
		try {
			ChartSearchService cache = answerCache();
			if (cache != null) {
				cache.invalidatePatient(patient.getUuid());
			}
		}
		catch (Exception e) {
			log.error("Failed to invalidate answer cache for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	/** Hands the chart write to the prewarm refresh executor (POJO-friendly bean lookup, like
	 *  {@link #answerCache}); the executor itself decides whether the patient is a pinned-corpus
	 *  member worth re-pinning. */
	private static void maybeRefreshPrewarm(Patient patient) {
		try {
			PrewarmRefreshExecutor executor = Context.getRegisteredComponent(
					"chartSearchAi.prewarmRefreshExecutor", PrewarmRefreshExecutor.class);
			if (executor != null) {
				executor.onChartWrite(patient);
			}
		}
		catch (Exception e) {
			log.error("Failed to schedule prewarm re-pin for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	private IndexingHelper() {
	}
}
