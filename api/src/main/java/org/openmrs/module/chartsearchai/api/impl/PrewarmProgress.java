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

/**
 * Serializable cursor state of the prewarm sweep, persisted to
 * {@code <appdata>/chartsearchai/prewarm-progress.json} after each patient so a crash or restart
 * resumes from {@link #cursorPatientId} rather than re-prefilling from scratch. Public fields +
 * a no-arg constructor keep it trivially Jackson-(de)serializable. The transform methods return
 * fresh instances so the owning service can publish it via a single {@code volatile} reference.
 */
public class PrewarmProgress {

	public String status = PrewarmBootstrapService.STATUS_IDLE;

	public String scope = PrewarmBootstrapService.SCOPE_ALL;

	public long total;

	public long done;

	public long failed;

	/** Highest patient id already processed; the sweep resumes at {@code patient_id > cursorPatientId}. */
	public long cursorPatientId;

	/** Patient currently being prefilled (0 when idle/finished). */
	public long currentPatientId;

	public long startedAt;

	public long updatedAt;

	public PrewarmProgress() {
	}

	static PrewarmProgress idle() {
		PrewarmProgress p = new PrewarmProgress();
		p.status = PrewarmBootstrapService.STATUS_IDLE;
		return p;
	}

	static PrewarmProgress running(String scope, long cursor, long done, long failed) {
		PrewarmProgress p = new PrewarmProgress();
		p.status = PrewarmBootstrapService.STATUS_RUNNING;
		p.scope = scope;
		p.cursorPatientId = cursor;
		p.done = done;
		p.failed = failed;
		p.startedAt = System.currentTimeMillis();
		p.updatedAt = p.startedAt;
		return p;
	}

	PrewarmProgress withCurrent(long currentId, long total, long done, long failed) {
		PrewarmProgress p = copy();
		p.currentPatientId = currentId;
		p.total = total;
		p.done = done;
		p.failed = failed;
		p.updatedAt = System.currentTimeMillis();
		return p;
	}

	PrewarmProgress advanced(long cursor, long total, long done, long failed) {
		PrewarmProgress p = copy();
		p.cursorPatientId = cursor;
		p.total = total;
		p.done = done;
		p.failed = failed;
		p.updatedAt = System.currentTimeMillis();
		return p;
	}

	PrewarmProgress finished(String finalStatus, long total, long done, long failed) {
		PrewarmProgress p = copy();
		p.status = finalStatus;
		p.currentPatientId = 0L;
		p.total = total;
		p.done = done;
		p.failed = failed;
		p.updatedAt = System.currentTimeMillis();
		return p;
	}

	// NOTE: copies every field. When adding a field above, add it here too — a field left out would
	// silently reset to its default on every progress update (the transform methods all go through copy()).
	private PrewarmProgress copy() {
		PrewarmProgress p = new PrewarmProgress();
		p.status = status;
		p.scope = scope;
		p.total = total;
		p.done = done;
		p.failed = failed;
		p.cursorPatientId = cursorPatientId;
		p.currentPatientId = currentPatientId;
		p.startedAt = startedAt;
		p.updatedAt = updatedAt;
		return p;
	}
}
