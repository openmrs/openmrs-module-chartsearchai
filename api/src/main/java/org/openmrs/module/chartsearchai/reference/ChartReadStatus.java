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
 * A one-slot accumulator a caller supplies to hear whether the chart reads behind a drug-reference
 * pass actually happened (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a>).
 *
 * <p><b>Why a sink rather than a return value or an accessor.</b>
 * {@link DrugReferenceInjector#inject} already returns the enriched chart, and the verdict is not a
 * property of that chart — an unreadable chart and a chart with nothing to find produce the same
 * one. The thing that knows is {@link PatientClinicalContext}, which is package-private here along
 * with its stamps and its builder, so a caller outside this package cannot ask. Building a second
 * context to ask would be the two-resolutions-that-agree shape issue #151 records as failing
 * silently and in one direction, and it would double the chart reads on every request.
 *
 * <p>Caller-supplied and never a field on the injector: that bean is a Spring singleton, so a field
 * would be one slot shared by every concurrent request (issue #172). A pass with no sink records
 * nothing and costs nothing.
 *
 * <p><b>What {@link #stated()} means, and this is the one place it is said.</b>
 * <ul>
 * <li>{@code TRUE} — every chart read that pass made completed. It does NOT say a contraindication
 * was screened, that the loaded dataset had a rule to ask ({@code conditionRuleCoverage} is that
 * question), or that any finding was raised.</li>
 * <li>{@code FALSE} — at least one read failed, so the emptiness of anything derived from the chart
 * is uninterpretable rather than a measurement. An empty chip list beside this is not a clear
 * chart.</li>
 * <li>{@code null} — no measurement: the pass did not run (the feature is off, or there was no
 * chart), or it threw before the context was built. Never read {@code null} as either verdict.</li>
 * </ul>
 *
 * <p>The verdict written here is {@link PatientClinicalContext#chartReadForSafety()} — the WHOLE
 * pass, both stamps. A records-only verdict was the first shape and it reads {@code true} on a
 * request whose active-order read failed, which is the defect ADR Decision 79 records one surface
 * over.
 */
public final class ChartReadStatus {

	private Boolean stated;

	/**
	 * States whether the pass's chart reads completed. Production has exactly one writer,
	 * {@link DrugReferenceInjector#inject}; a second producer anywhere would be issue #151's shape.
	 *
	 * @param read {@link PatientClinicalContext#chartReadForSafety()} for the context the pass built
	 */
	void record(boolean read) {
		stated = Boolean.valueOf(read);
	}

	/**
	 * @return the verdict, or {@code null} where the pass stated none — the class javadoc above
	 *         enumerates what each of the three answers does and does not assert, and is the only
	 *         place that enumeration lives
	 */
	public Boolean stated() {
		return stated;
	}
}
