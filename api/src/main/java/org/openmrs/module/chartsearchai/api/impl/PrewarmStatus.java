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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable status snapshot the {@code /prewarmstatus} endpoint returns: the persisted
 * {@link PrewarmProgress} plus the live {@code running} flag and the on-disk pinned-entry count
 * (the durable warm corpus). {@link #toMap()} yields the REST response body.
 */
public class PrewarmStatus {

	private final PrewarmProgress progress;

	private final boolean running;

	private final int pinnedOnDisk;

	public PrewarmStatus(PrewarmProgress progress, boolean running, int pinnedOnDisk) {
		this.progress = progress;
		this.running = running;
		this.pinnedOnDisk = pinnedOnDisk;
	}

	public boolean isRunning() {
		return running;
	}

	public String getStatus() {
		return progress.status;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("status", progress.status);
		m.put("running", running);
		m.put("scope", progress.scope);
		m.put("total", progress.total);
		m.put("done", progress.done);
		m.put("failed", progress.failed);
		m.put("cursorPatientId", progress.cursorPatientId);
		m.put("currentPatientId", progress.currentPatientId);
		m.put("pinnedOnDisk", pinnedOnDisk);
		m.put("startedAt", progress.startedAt);
		m.put("updatedAt", progress.updatedAt);
		return m;
	}
}
