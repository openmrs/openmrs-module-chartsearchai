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

import java.util.Objects;

import org.openmrs.Patient;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Submits patient-chart warmup work asynchronously so the REST endpoint can
 * return immediately and the user's chart page is not held back by the
 * llama-server prefill (tens of seconds on a cold KV cache).
 *
 * <p>Tasks run on OpenMRS daemon threads (via {@link Daemon#runInDaemonThread}),
 * which handle session opening / authentication for the background work that
 * touches the OpenMRS DB (chart serialization). User access is validated on
 * the request thread before {@link #submit} is ever called, so no privilege
 * is escalated by the daemon execution.
 *
 * <p>Concurrency is handled by {@link LocalLlmEngine}'s {@code synchronized}
 * monitor and by llama-server's {@code --parallel 1} configuration: only one
 * warmup or real query is in flight at a time. The stale-skip flag is
 * process-wide, not per-user, so concurrent clinicians on different patients
 * may see warmup hit-rate degrade — but the feature continues to function
 * (a stale-skip just turns warmup into a no-op for that submission).
 */
@Component("chartSearchAi.warmupExecutor")
public class WarmupExecutor {

	private static final Logger log = LoggerFactory.getLogger(WarmupExecutor.class);

	@Autowired
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

	private volatile DaemonToken daemonToken;

	private volatile Integer latestRequestedPatientId;

	public void setDaemonToken(DaemonToken token) {
		this.daemonToken = token;
	}

	/** Seam wrapping {@link PipelineSettings#queryScopedMode()} (fail-safe false); scoped mode
	 *  skips warmup submission entirely — see {@link #submit}. */
	protected boolean isQueryScopedMode() {
		return PipelineSettings.queryScopedMode();
	}

	public void submit(Patient patient) {
		if (patient == null || patient.getPatientId() == null) {
			return;
		}
		// Short-circuit before spawning a daemon thread, to avoid the Hibernate
		// session inside Daemon.runInDaemonThread when warmup is off — and equally when
		// chartMode=queryScoped, where the downstream LlmInferenceService gate makes every
		// warmup a guaranteed no-op: without this check, every chart open would still pay a
		// daemon session open + GP reads for work that can never have an effect.
		if (isQueryScopedMode()) {
			return;
		}
		if (!LlmInferenceService.isWarmupEnabled()) {
			return;
		}

		final Integer requestedId = patient.getPatientId();
		latestRequestedPatientId = requestedId;

		DaemonToken token = daemonToken;
		if (token == null) {
			log.warn("DaemonToken not yet available; skipping warmup for patient [id={}]",
					requestedId);
			return;
		}

		Daemon.runInDaemonThread(() -> {
			if (!Objects.equals(latestRequestedPatientId, requestedId)) {
				log.debug("Dropping stale warmup for patient [id={}]; latest is [id={}]",
						requestedId, latestRequestedPatientId);
				return;
			}
			try {
				chartSearchService.warmup(patient);
			}
			catch (Exception e) {
				log.warn("Warmup failed for patient [id={}]: {}", requestedId, e.getMessage());
			}
		}, token);
	}
}
