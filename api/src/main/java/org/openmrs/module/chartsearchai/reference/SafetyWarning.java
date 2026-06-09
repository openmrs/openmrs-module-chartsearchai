/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

/**
 * A non-blocking advisory raised by {@link DrugSafetyValidator} after the LLM
 * answers. A warning <em>annotates</em> the answer — it never rewrites or
 * suppresses it. The clinician decides. Carried on
 * {@code ChartSearchService.ChartAnswer} and rendered as a chip below the
 * answer in the frontend.
 */
public class SafetyWarning {

	/** Overdose: a parsed daily dose exceeds the reference maximum for the patient's age band. */
	public static final String TYPE_OVERDOSE = "overdose";

	/** Interaction: a drug named in the answer interacts with one of the patient's active orders. */
	public static final String TYPE_INTERACTION = "interaction";

	/** Contraindication: a drug named in the answer is contraindicated by an active allergy or condition. */
	public static final String TYPE_CONTRAINDICATION = "contraindication";

	private final String type;

	private final String drug;

	private final String detail;

	public SafetyWarning(String type, String drug, String detail) {
		this.type = type;
		this.drug = drug;
		this.detail = detail;
	}

	/** One of {@link #TYPE_OVERDOSE}, {@link #TYPE_INTERACTION}, {@link #TYPE_CONTRAINDICATION}. */
	public String getType() {
		return type;
	}

	/** The reference drug the warning is about (display name). */
	public String getDrug() {
		return drug;
	}

	/** Human-readable detail, e.g. "exceeds 1200 mg/day max for ages 2-11" or "interacts with warfarin". */
	public String getDetail() {
		return detail;
	}

	@Override
	public String toString() {
		return type + ":" + drug + ":" + detail;
	}
}
