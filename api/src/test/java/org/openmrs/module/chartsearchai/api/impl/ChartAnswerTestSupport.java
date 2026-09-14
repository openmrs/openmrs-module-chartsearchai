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

import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;

/**
 * What a published {@link ChartAnswer} says, read off the answer itself for the cases whose PREMISE
 * is what production produced rather than what the arrangement asked for.
 *
 * <p>Here rather than copied per suite for the rule CLAUDE.md states of {@code TestDatasetHelper}
 * and {@code DrugReferenceTestSupport}: a reader of the published answer is a statement about the
 * wire contract, and three copies of one are three places a change to that contract has to reach.
 * Extracted when a third suite in this package needed the reference-index reading (issue #409 round
 * two): the earlier two each held their own copy of it, so leaving it per-suite would have made
 * three readers of one wire contract.
 *
 * <p>This is a reader of production output, never a stand-in for any part of the pipeline. Nothing
 * here re-derives what a check computes — every case still drives the real
 * {@link LlmInferenceService} and asserts on the answer it published.
 */
final class ChartAnswerTestSupport {

	private ChartAnswerTestSupport() {
	}

	/**
	 * The citation indexes of {@code answer}'s reference list, in the order
	 * {@code LlmInferenceService.extractCitedReferences} resolved them.
	 *
	 * <p>Its callers assert on this as a PREMISE: that a citation resolved into {@code references[]}
	 * at all, which since issue #409 is a different population from the findings the answer's own
	 * prose anchored — a case reading only a count could not show the two apart. Read off the
	 * published answer rather than off the stub, so the premise is a statement about what production
	 * produced.
	 *
	 * @param answer a published answer
	 * @return the indexes, in reference order; empty where the answer carries no reference
	 */
	static List<Integer> referenceIndexes(ChartAnswer answer) {
		List<Integer> indexes = new ArrayList<Integer>();
		for (RecordReference reference : answer.getReferences()) {
			indexes.add(Integer.valueOf(reference.getIndex()));
		}
		return indexes;
	}
}
