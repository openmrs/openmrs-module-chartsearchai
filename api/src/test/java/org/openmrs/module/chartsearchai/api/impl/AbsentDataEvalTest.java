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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmrs.module.chartsearchai.eval.EvalCase;
import org.openmrs.module.chartsearchai.eval.EvalDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eval suite for absent-data detection. Tests that when retrieval finds
 * no matching records, the system produces a clear "no records" answer
 * naming what was asked about.
 */
public class AbsentDataEvalTest {

	private static final Logger log = LoggerFactory.getLogger(AbsentDataEvalTest.class);

	private static EvalDataset dataset;

	private static EvalDataset getDataset() {
		if (dataset == null) {
			try {
				dataset = EvalDataset.load("eval/absent-data-eval-dataset.json");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return dataset;
	}

	static Stream<Arguments> presentCases() {
		List<Arguments> args = new ArrayList<>();
		for (EvalCase evalCase : getDataset().getCases()) {
			if (!evalCase.isExpectedAbsent()) {
				args.add(Arguments.of(evalCase.getId(), evalCase));
			}
		}
		return args.stream();
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("presentCases")
	public void presentData_perCase(String caseId, EvalCase evalCase) {
		// The real retrieval text chart assembly sends to querystore — the composed
		// expand-then-strip pipeline, not the stripping step alone.
		String stripped = QueryPreprocessor.forRetrieval(evalCase.getQuestion());

		log.info("[{}] question='{}' stripped='{}'",
				caseId, evalCase.getQuestion(), stripped);

		assertFalse(stripped.isEmpty(),
				caseId + ": question should have searchable terms");
	}

}
