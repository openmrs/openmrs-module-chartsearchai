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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.chartsearchai.eval.EvalCase;
import org.openmrs.module.chartsearchai.eval.EvalDataset;
import org.openmrs.module.chartsearchai.eval.EvalMetrics;
import org.openmrs.module.chartsearchai.eval.EvalReporter;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eval suite for retrieval quality. Uses the real ONNX embedding model
 * (all-MiniLM-L6-v2) to test the scoring, gap detection, and keyword
 * refinement pipeline against the 153-record patient dataset.
 *
 * <p>Skipped automatically when the ONNX model files are not present.
 */
@Tag("eval")
public class RetrievalQualityEvalTest {

	private static final Logger log = LoggerFactory.getLogger(RetrievalQualityEvalTest.class);

	private static final String MODEL_PATH = TestDatasetHelper.MODEL_PATH;

	private static final String VOCAB_PATH = TestDatasetHelper.VOCAB_PATH;

	private static final String[] DATASET = TestDatasetHelper.FULL_PATIENT_DATASET;

	private static EvalDataset evalDataset;

	private static EmbeddingProvider embeddingProvider;

	private static List<ChartEmbedding> allEmbeddings;

	private static boolean embeddingModelFilesExist() {
		return TestDatasetHelper.modelFilesExist();
	}

	private static EvalDataset getEvalDataset() {
		if (evalDataset == null) {
			try {
				evalDataset = EvalDataset.load("eval/retrieval-eval-dataset.json");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			embeddingProvider = new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
			allEmbeddings = buildEmbeddings();
		}
		return evalDataset;
	}

	private static EmbeddingProvider getEmbeddingProvider() {
		getEvalDataset(); // ensures initialization
		return embeddingProvider;
	}

	private static List<ChartEmbedding> getAllEmbeddings() {
		getEvalDataset(); // ensures initialization
		return allEmbeddings;
	}

	private static List<ChartEmbedding> buildEmbeddings() {
		// Use the production EmbeddingIndexer.buildEmbeddings() to ensure
		// embedding text format (prefix, no date) matches what runs in prod.
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records =
				TestDatasetHelper.toSerializedRecords(DATASET);
		// Replace the default UUID with a 1-based, sparsely numbered UUID
		// (index * 100) — keeps eval-dataset fixtures distinguishable from
		// the default 0-based dataset indices used elsewhere.
		for (int i = 0; i < records.size(); i++) {
			records.set(i, new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
					records.get(i).getResourceType(),
					TestDatasetHelper.uuidForIndex((i + 1) * 100),
					records.get(i).getText(), null));
		}
		List<ChartEmbedding> embeddings =
				org.openmrs.module.chartsearchai.api.EmbeddingIndexer.buildEmbeddings(
						records, embeddingProvider);
		// Set embeddingId to 1-based index for eval metric matching
		for (int i = 0; i < embeddings.size(); i++) {
			embeddings.get(i).setEmbeddingId(i + 1);
		}
		return embeddings;
	}

	static Stream<Arguments> retrievalCases() {
		List<Arguments> args = new ArrayList<>();
		for (EvalCase evalCase : getEvalDataset().getCases()) {
			args.add(Arguments.of(evalCase.getId(), evalCase));
		}
		return args.stream();
	}

	private static List<Integer> retrieveTopK(EvalCase evalCase) {
		List<ChartEmbedding> results = LlmInferenceService.findSimilar(
				getAllEmbeddings(), getEmbeddingProvider(),
				evalCase.getQuestion(),
				ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX,
				PipelineConfig.defaults()).records;

		List<Integer> retrievedIndices = new ArrayList<>();
		for (ChartEmbedding ce : results) {
			retrievedIndices.add(ce.getEmbeddingId());
		}
		return retrievedIndices;
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("retrievalCases")
	public void retrievalRecall_perCase(String caseId, EvalCase evalCase) {
		org.junit.jupiter.api.Assumptions.assumeTrue(embeddingModelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);
		long start = System.currentTimeMillis();
		List<Integer> retrievedIndices = retrieveTopK(evalCase);
		long elapsed = System.currentTimeMillis() - start;

		double recall = EvalMetrics.recall(retrievedIndices, evalCase.getExpectedRecordIndices());

		log.info("[{}] recall@30={} latency={}ms expected={} top5={}",
				caseId, String.format("%.3f", recall), elapsed,
				evalCase.getExpectedRecordIndices(),
				retrievedIndices.subList(0, Math.min(5, retrievedIndices.size())));

		assertTrue(elapsed < 200,
				caseId + ": retrieval should complete in < 200ms but took " + elapsed + "ms");
	}

	@Test
	public void retrievalRecall_shouldMeetMinimumThreshold() {
		org.junit.jupiter.api.Assumptions.assumeTrue(embeddingModelFilesExist(),
				"Skipping: ONNX model files not found at " + MODEL_PATH);
		int totalCases = 0;
		double totalRecall = 0;

		for (EvalCase evalCase : getEvalDataset().getCases()) {
			totalCases++;

			List<Integer> retrievedIndices = retrieveTopK(evalCase);
			double recall = EvalMetrics.recall(retrievedIndices, evalCase.getExpectedRecordIndices());
			totalRecall += recall;

			log.info("[{}] recall@30={} expected={} top5={}",
					evalCase.getId(), String.format("%.3f", recall),
					evalCase.getExpectedRecordIndices(),
					retrievedIndices.subList(0, Math.min(5, retrievedIndices.size())));

			Map<String, Object> metrics = new LinkedHashMap<>();
			metrics.put("recall@30", String.format("%.3f", recall));
			EvalReporter.appendResult("retrieval", evalCase.getId(), metrics);
		}

		double avgRecall = totalCases > 0 ? totalRecall / totalCases : 0;
		log.info("Retrieval eval: avgRecall@30={} across {} cases",
				String.format("%.3f", avgRecall), totalCases);

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("avgRecall@30", String.format("%.3f", avgRecall));
		summary.put("totalCases", totalCases);
		EvalReporter.appendSummary("retrieval", summary);

		assertTrue(avgRecall >= 0.4,
				"Average retrieval recall@30 should be >= 0.4 but was "
				+ String.format("%.3f", avgRecall));
	}

	@Test
	public void keywordScoring_shouldMatchExpectedTerms() {
		String[] terms = {"cd4", "count"};
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Clinical observation: Test — CD4 Count: 988.0");
		assertTrue(score > 0, "Keyword score should be > 0 for matching terms");

		double noMatchScore = LlmInferenceService.computeKeywordScore(terms,
				"Clinical observation: Test — Weight (kg): 94.0");
		assertTrue(noMatchScore < score,
				"Non-matching record should score lower");
	}
}
