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
 * A one-slot accumulator a caller supplies to hear whether the two STAMPED chart reads behind a
 * drug-reference pass happened — the contraindication records and the active orders (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a>).
 *
 * <p><b>Why a sink rather than a return value or an accessor.</b>
 * {@link DrugReferenceInjector#inject} already returns the enriched chart, and the verdict is not a
 * property of that chart — an unreadable chart and a chart with nothing to find produce the same
 * one. The thing that knows is {@link PatientClinicalContext}, whose stamps and whose builder are
 * package-private, so a caller outside this package cannot ask. Building a second context to ask
 * would be the two-resolutions-that-agree shape issue #151 records as failing silently and in one
 * direction, and it would double the chart reads on every request.
 *
 * <p>The same one-slot accumulator idiom as {@link PairChipExtent.Sink}, for the reasons its
 * javadoc gives — caller-owned, never a field on a Spring singleton (issue #172), and free to a
 * pass that supplies none.
 *
 * <p><b>What the three answers MEAN is not enumerated here.</b> It is one enumeration with one
 * home, {@code ChartSearchService.ChartAnswer.getChartReadForSafety()}, which is where a consumer
 * meets this value and where README sends one; a second copy beside the producer is how the two
 * come to disagree. What belongs here is only what this class DOES: it carries
 * {@link PatientClinicalContext#chartReadForSafety()} — the WHOLE pass, both stamps — unset until
 * a pass records one.
 */
public final class ChartReadStatus {

	private Boolean stated;

	/**
	 * States whether both stamped reads completed. Production has exactly one writer,
	 * {@link DrugReferenceInjector#inject}; a second producer anywhere would be issue #151's shape.
	 *
	 * @param read {@link PatientClinicalContext#chartReadForSafety()} for the context the pass built
	 */
	void record(boolean read) {
		stated = Boolean.valueOf(read);
	}

	/**
	 * @return the verdict, or {@code null} where the pass stated none.
	 *         {@code ChartSearchService.ChartAnswer.getChartReadForSafety()} is canonical for what
	 *         each of the three answers does and does not assert
	 */
	public Boolean stated() {
		return stated;
	}
}
