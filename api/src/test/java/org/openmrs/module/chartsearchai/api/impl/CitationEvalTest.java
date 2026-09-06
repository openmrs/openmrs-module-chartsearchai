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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p><b>What the per-case check used to do (issue #215).</b> It read
 * {@code if (!knownDegraded) { assertTrue(f1 >= 0.5) }}, so for the cases tagged {@code missing} or
 * {@code malformed} no assertion executed at all. Its comment said F1=0 was the expected behaviour,
 * and nothing asserted that — so a response that began yielding <b>spurious</b> citations passed
 * silently, which is the fabrication failure issue #103 is about. The comment was also wrong on the
 * arithmetic: of the three cases it skipped, {@code citation-malformed-json} scores F1 <b>1.000</b>
 * (this harness defines {@code f1(∅,∅) = 1.0}) and {@code citation-trailing-comma-in-array} scores
 * <b>1.000</b> too, because the regex salvage path recovers both its citations. Only
 * {@code citation-missing} scores 0. Every case is asserted now, in one of two branches, and
 * {@link #everyCaseReachesTheRunAndBothAssertionBranchesAreOccupied} keeps both branches occupied.
 *
 * <p><b>Why the dataset carries two string-typed cases (issue #219).</b>
 * {@code citation-string-type-citations} cannot fail on the typing it is named for, and dropping the
 * {@code simulatedCitations} substitution does not change that: its prose anchors {@code [9]} and
 * {@code [10]} inline, and the inline markers alone resolve both references whether or not the array
 * parsed. Only a case whose prose anchors NOTHING isolates the array — and by the carve-outs in
 * {@code extractCitedReferences}, only a BLANK answer both anchors nothing and still lets the array
 * resolve, since real prose with no inline marker discards the array wholesale (the #76
 * unanchored-array guard). Hence {@code citation-string-type-citations-array-only}, the string-typed
 * twin of {@code citation-empty-answer-with-citations}: it scores F1 0.000 when the array is dropped
 * and 1.000 when it is read. That margin is the point — it does not depend on where the per-case
 * threshold sits, so the case cannot quietly stop discriminating if the threshold moves.
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

		if (isKnownDegraded(evalCase)) {
			// The property, asserted on the predicted set rather than on F1, because F1 cannot express
			// it (issue #215): f1(∅,∅) is 1.0 by this harness's own convention, so a case expecting no
			// citations scores a perfect 1.0 for producing none AND cannot distinguish that from a
			// wrong prediction, which also scores 0. "Produced nothing" is the behaviour under test.
			assertTrue(predictedIndices.isEmpty(),
					caseId + ": a response carrying no recoverable citation must yield NO references, but "
							+ "yielded " + predictedIndices + " — citations the response does not contain "
							+ "are fabricated, which is the failure this dataset's degraded cases exist to "
							+ "catch");
		} else {
			assertTrue(f1 >= 0.5,
					caseId + ": Citation F1 should be >= 0.5 but was " + String.format("%.3f", f1));
		}
	}

	/**
	 * Whether {@code evalCase}'s simulated response carries no citation the pipeline could recover, so
	 * the correct outcome is no references at all rather than a per-case F1 threshold.
	 *
	 * <p>Tag-driven, and the tags are load-bearing, so read them as classifying the OUTCOME and not
	 * the input's syntax. {@code citation-trailing-comma-in-array} is a malformed input whose citations
	 * the regex salvage path recovers in full (F1 1.000, measured), so it belongs on the normal
	 * threshold and no longer carries {@code malformed} — its {@code trailing-comma} tag already names
	 * the input shape. Leaving it in this bucket was how one of the three cases issue #215 found came
	 * to assert nothing while behaving perfectly.
	 */
	private static boolean isKnownDegraded(EvalCase evalCase) {
		return evalCase.getTags() != null
				&& (evalCase.getTags().contains("missing") || evalCase.getTags().contains("malformed"));
	}

	/**
	 * The guard that every case is asserted and both buckets above are occupied — the check the
	 * per-case method cannot make about itself.
	 *
	 * <p>Two ways this suite could go quiet without a count moving, both instances of the class issue
	 * #215 is the fourteenth of. A case added without a {@code simulatedLlmResponse} is dropped by
	 * {@link #citationCases()} silently, because a case a {@code @MethodSource} never yields is not
	 * reported as skipped (the issue #203 shape). And a bucket that empties — every degraded tag
	 * removed, or every case tagged degraded — retires one of the two branches with nothing to say so.
	 */
	@Test
	public void everyCaseReachesTheRunAndBothAssertionBranchesAreOccupied() {
		assertEquals(getDataset().getCases().size(), citationCases().count(),
				"every case must reach the run: a case with no simulatedLlmResponse is filtered out by "
						+ "the provider, and a filtered case is not a skipped case, so no count reports it");

		int degraded = 0;
		for (EvalCase evalCase : getDataset().getCases()) {
			if (isKnownDegraded(evalCase)) {
				degraded++;
			}
		}
		assertTrue(degraded > 0, "no case is tagged degraded, so the no-references assertion in "
				+ "citationAccuracy_perCase never runs");
		assertTrue(degraded < getDataset().getCases().size(), "every case is tagged degraded, so the F1 "
				+ "threshold in citationAccuracy_perCase never runs");
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

	/**
	 * The prediction under test: the raw simulated response through the real parse
	 * ({@code extractResponse}) and the real reference resolution
	 * ({@code extractCitedReferences}), with nothing substituted in between.
	 *
	 * <p><b>What this used to do (issue #219).</b> A case could carry a {@code simulatedCitations}
	 * array, and when it did this method used that instead of what {@code extractResponse} returned
	 * — so the case asserted against a value production never produced. In three of the four cases
	 * that carried one the two agreed, which is what made it look harmless; in the fourth,
	 * {@code citation-string-type-citations}, they differed, and the substitution hid the very defect
	 * the case is named for: {@code "citations": ["9","10"]} parsed to an EMPTY list. The field is
	 * gone from {@link EvalCase} as well as from the dataset, so the substitution cannot come back by
	 * someone filling in a field that still exists.
	 */
	private static List<Integer> computePredictedIndices(EvalCase evalCase) {
		LlmProvider.LlmResponse llmResponse = LlmProvider.extractResponse(
				evalCase.getSimulatedLlmResponse());

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				llmResponse.getAnswer(), llmResponse.getCitations(), getMappings());
		List<Integer> predictedIndices = new ArrayList<>();
		for (RecordReference ref : refs) {
			predictedIndices.add(ref.getIndex());
		}
		return predictedIndices;
	}
}
