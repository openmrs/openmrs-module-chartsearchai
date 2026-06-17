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

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive relevance cutoff for a ranked candidate list (querystore migration,
 * issue #51). Given each candidate's relevance score in descending order, it
 * decides how many to keep — or that none are relevant.
 *
 * <p>This is the small, self-contained "consumer-layer" filter that replaces the
 * embedding pipeline's full-corpus gap/z-score machinery once retrieval moves to
 * querystore: querystore generates the ranked candidates, this trims them for
 * precision and rejects absent-data queries. It is deliberately
 * <b>signal-agnostic</b> — the scores can be cosine similarities (the Phase 0
 * prototype) or querystore's own fused hybrid scores if/when that API exposes
 * them; the cut logic is identical either way.
 *
 * <p>Two mechanisms, mirroring the embedding pipeline's design:
 * <ul>
 * <li><b>Absent gate (top-anchored).</b> If the single best candidate does not
 * clear {@code absentTopFloor}, nothing stands out and the query is treated as
 * "no relevant records" → empty result. Anchoring on the top score (not the
 * whole set) decouples the absent decision from how much of the tail to keep,
 * which is what lets one threshold serve both purposes — the limitation the
 * global-threshold sweep exposed.</li>
 * <li><b>Tail trim (floor + gap).</b> Among candidates at or above
 * {@code keepFloor}, walk down from the top and stop at the first consecutive
 * score drop larger than {@code maxGap}, capped at {@code maxKeep}. A gradual
 * decay (many relevant records) keeps many; a sharp cliff (a few standouts)
 * keeps few — adaptive per query.</li>
 * </ul>
 */
final class RelevanceCutoff {

	private RelevanceCutoff() {
	}

	/** Immutable tuning for {@link #apply}. {@code keepFloor <= absentTopFloor}. */
	static final class Params {

		final double absentTopFloor;

		final double keepFloor;

		final double maxGap;

		final int maxKeep;

		Params(double absentTopFloor, double keepFloor, double maxGap, int maxKeep) {
			this.absentTopFloor = absentTopFloor;
			this.keepFloor = keepFloor;
			this.maxGap = maxGap;
			this.maxKeep = maxKeep;
		}
	}

	/**
	 * Applies the cutoff to candidates already sorted by score descending.
	 *
	 * @param candidatesDesc each entry is {@code [identifier, score]}; the identifier
	 *                       is opaque (returned as-is), score is the relevance signal,
	 *                       and the list must be sorted by score descending
	 * @param p              tuning parameters
	 * @return the identifiers of the kept candidates, in score-descending order;
	 *         empty when the absent gate fires or the input is empty
	 */
	static List<Integer> apply(List<double[]> candidatesDesc, Params p) {
		List<Integer> kept = new ArrayList<Integer>();
		if (candidatesDesc == null || candidatesDesc.isEmpty()) {
			return kept;
		}
		double topScore = candidatesDesc.get(0)[1];
		// Absent gate: the best candidate isn't good enough for anything to be relevant.
		if (topScore < p.absentTopFloor) {
			return kept;
		}
		double prev = topScore;
		for (double[] candidate : candidatesDesc) {
			double score = candidate[1];
			if (score < p.keepFloor) {
				break;
			}
			if (!kept.isEmpty()) {
				// Stop at the first cliff: a drop from the previous kept score larger than maxGap.
				if (prev - score > p.maxGap) {
					break;
				}
				if (kept.size() >= p.maxKeep) {
					break;
				}
			}
			kept.add((int) candidate[0]);
			prev = score;
		}
		return kept;
	}
}
