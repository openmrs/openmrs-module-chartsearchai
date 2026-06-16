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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared helper used by the AOP advice classes after patient data changes: invalidate the
 * patient's cached answers. This runs whenever the answer cache is on, regardless of retrieval
 * backend — querystore owns retrieval-index freshness (via core's #6084 service events), so the
 * advice no longer re-indexes anything; the answer cache is the only chartsearchai-side state that
 * a chart write must invalidate.
 */
final class IndexingHelper {

	private static final Logger log = LoggerFactory.getLogger(IndexingHelper.class);

	/** The router bean that owns the answer cache, resolved through the {@link ChartSearchService}
	 *  interface (the same way {@code WarmupExecutor} and the REST controller reference it). Looked
	 *  up lazily, not injected, because the AOP advice classes are plain POJOs instantiated by
	 *  OpenMRS, not Spring-managed beans. */
	private static ChartSearchService answerCache() {
		return Context.getRegisteredComponent(
				"chartSearchAi.chartSearchServiceRouter", ChartSearchService.class);
	}

	/**
	 * Whether the answer cache is on (TTL &gt; 0). Checked by the AOP write-advice <em>before</em>
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

	private IndexingHelper() {
	}
}
