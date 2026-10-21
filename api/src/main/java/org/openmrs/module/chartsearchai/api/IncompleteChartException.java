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

/** The chart read is incomplete because retrieval was truncated or source indexing is unfinished. */
public class IncompleteChartException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** Which of the two incompleteness conditions the read met; callers treat them differently. */
	public enum Reason {
		/** This one chart exceeds the read store's retrieval cap: a fact about the patient, stable across restarts. */
		CHART_TRUNCATED,
		/** The read store has not finished indexing: transient, and true of every later chart as well. */
		INDEXING_UNFINISHED
	}

	private final Reason reason;

	/** An unfinished index, the condition a caller must stop on; the builder states the other explicitly. */
	public IncompleteChartException(String message) {
		this(message, Reason.INDEXING_UNFINISHED);
	}

	public IncompleteChartException(String message, Reason reason) {
		super(message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
