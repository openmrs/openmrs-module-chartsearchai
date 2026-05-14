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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.eval.EvalCase;
import org.openmrs.module.chartsearchai.eval.EvalDataset;
import org.openmrs.module.chartsearchai.eval.EvalMetrics;
import org.openmrs.module.chartsearchai.eval.EvalReporter;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eval suite for citation accuracy. Tests that the LLM response parsing
 * pipeline ({@code extractResponse} + {@code extractCitedReferences})
 * correctly extracts citations from simulated LLM JSON output.
 */
public class CitationEvalTest {

	private static final Logger log = LoggerFactory.getLogger(CitationEvalTest.class);

	private static EvalDataset dataset;

	private static List<RecordMapping> mappings;

	private static EvalDataset getDataset() {
		if (dataset == null) {
			try {
				dataset = EvalDataset.load("eval/citation-eval-dataset.json");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return dataset;
	}

	private static List<RecordMapping> getMappings() {
		if (mappings == null) {
			mappings = new ArrayList<>();
			for (EvalDataset.EvalRecord record : getDataset().getRecords()) {
				mappings.add(new RecordMapping(record.getIndex(), record.getResourceType(),
						record.getResourceUuid(), null));
			}
		}
		return mappings;
	}

	static Stream<Arguments> citationCases() {
		List<Arguments> args = new ArrayList<>();
		for (EvalCase evalCase : getDataset().getCases()) {
			if (evalCase.getSimulatedLlmResponse() != null) {
				args.add(Arguments.of(evalCase.getId(), evalCase));
			}
		}
		return args.stream();
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("citationCases")
	public void citationAccuracy_perCase(String caseId, EvalCase evalCase) {
		List<Integer> predictedIndices = computePredictedIndices(evalCase);
		List<Integer> expected = evalCase.getExpectedRecordIndices();
		double f1 = EvalMetrics.f1(predictedIndices, expected);

		log.info("[{}] F1={} predicted={} expected={}",
				caseId, String.format("%.3f", f1), predictedIndices, expected);

		// Cases tagged "missing" or "malformed" test known degraded inputs where
		// F1=0 is the expected (correct) system behavior — skip per-case threshold
		boolean knownDegraded = evalCase.getTags() != null
				&& (evalCase.getTags().contains("missing") || evalCase.getTags().contains("malformed"));
		if (!knownDegraded) {
			assertTrue(f1 >= 0.5,
					caseId + ": Citation F1 should be >= 0.5 but was " + String.format("%.3f", f1));
		}
	}

	@Test
	public void citationAccuracy_shouldMeetMinimumF1() {
		int totalCases = 0;
		int exactMatches = 0;
		double totalF1 = 0;

		for (EvalCase evalCase : getDataset().getCases()) {
			if (evalCase.getSimulatedLlmResponse() == null) {
				continue;
			}
			totalCases++;

			List<Integer> predictedIndices = computePredictedIndices(evalCase);
			List<Integer> expected = evalCase.getExpectedRecordIndices();
			double f1 = EvalMetrics.f1(predictedIndices, expected);
			boolean exact = EvalMetrics.exactMatch(predictedIndices, expected);

			totalF1 += f1;
			if (exact) {
				exactMatches++;
			}

			log.info("[{}] F1={} exact={} predicted={} expected={}",
					evalCase.getId(), String.format("%.3f", f1), exact,
					predictedIndices, expected);

			Map<String, Object> metrics = new LinkedHashMap<>();
			metrics.put("f1", String.format("%.3f", f1));
			metrics.put("exactMatch", exact);
			EvalReporter.appendResult("citation", evalCase.getId(), metrics);
		}

		double avgF1 = totalCases > 0 ? totalF1 / totalCases : 0;
		log.info("Citation eval: avgF1={} exactMatch={}/{}", String.format("%.3f", avgF1),
				exactMatches, totalCases);

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("avgF1", String.format("%.3f", avgF1));
		summary.put("exactMatches", exactMatches);
		summary.put("totalCases", totalCases);
		EvalReporter.appendSummary("citation", summary);

		assertTrue(avgF1 >= 0.8,
				"Citation F1 should be >= 0.8 but was " + String.format("%.3f", avgF1));
	}

	private static List<Integer> computePredictedIndices(EvalCase evalCase) {
		LlmProvider.LlmResponse llmResponse = LlmProvider.extractResponse(
				evalCase.getSimulatedLlmResponse());

		List<Integer> predictedCitations;
		if (evalCase.getSimulatedCitations() != null) {
			predictedCitations = evalCase.getSimulatedCitations();
		} else {
			predictedCitations = llmResponse.getCitations();
		}

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				predictedCitations, getMappings());
		List<Integer> predictedIndices = new ArrayList<>();
		for (RecordReference ref : refs) {
			predictedIndices.add(ref.getIndex());
		}
		return predictedIndices;
	}
}
