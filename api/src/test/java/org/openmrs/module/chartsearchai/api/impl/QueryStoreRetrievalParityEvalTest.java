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

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.eval.EvalMetrics;
import org.openmrs.module.chartsearchai.eval.EvalReporter;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl;
import org.openmrs.module.querystore.backend.Filter;
import org.openmrs.module.querystore.backend.Hit;
import org.openmrs.module.querystore.backend.SchemaSpec;
import org.openmrs.module.querystore.backend.SearchRequest;
import org.openmrs.module.querystore.backend.SearchResult;
import org.openmrs.module.querystore.backend.lucene.LuceneBackendStore;
import org.openmrs.module.querystore.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Querystore retrieval measurement + smoke harness (issue #51): measures how well
 * querystore's real retrieval ({@link QueryStoreServiceImpl#searchByPatient}, wired with
 * the embeddable Lucene backend + the all-MiniLM-L6-v2 ONNX model) reproduces the frozen
 * 485-case retrieval baseline ({@code eval/enriched-retrieval-eval.json}).
 *
 * <p>The migration is complete — chartsearchai's own embedding/Lucene/Elasticsearch
 * pipeline was removed (Phases 2–3). This harness originally measured querystore vs. that
 * pipeline to justify the deletion; it now reports parity for a human to read and asserts
 * only <b>sanity floors</b> (every baseline case processed; macro-recall and best-F1 above
 * zero; predicted-set size monotonic across the cutoff sweep). It deliberately does NOT
 * enforce a numeric parity bar — so it catches a gross regression or a broken harness
 * (recall collapses to zero, cases dropped), not a small drift; calibrate a threshold here
 * if you want a hard gate. It runs the actual production querystore code path (no
 * mock/reimplementation); the only fixture work is constructing {@link QueryDocument}s from
 * the shared test datasets and indexing them through the production
 * {@link QueryStoreServiceImpl#index} method.
 *
 * <p>It is <b>opt-in</b>: it skips unless the all-MiniLM model + vocab are present (see
 * {@code TestDatasetHelper.MODEL_PATH}, overridable via
 * {@code -Dchartsearchai.embedding.model.dir=...}), so normal builds don't need the model.
 *
 * <p><b>What "gold" means here.</b> The baseline {@code resultIndices} were captured from
 * the (now-removed) embedding pipeline, so recall/precision measure <i>agreement with that
 * frozen baseline</i>, not absolute clinical correctness — the bar is that querystore keeps
 * covering the records that baseline expects.
 *
 * <p><b>Known asymmetries (conservative to querystore).</b> querystore is fed the
 * clean record text only — no category-hint / synonym enrichment, which the
 * embedding pipeline used when it generated the baseline; and querystore returns a
 * fixed top-K rather than the embedding pipeline's adaptive gap-based cutoff, so
 * precision on absent-data queries is reported separately as an over-return rate.
 * Both levers (hints as querystore synonyms, a relevance cutoff) are follow-ups if
 * the as-is numbers fall short.
 */
public class QueryStoreRetrievalParityEvalTest {

	private static final Logger log = LoggerFactory.getLogger(QueryStoreRetrievalParityEvalTest.class);

	private static final String BASELINE = "eval/enriched-retrieval-eval.json";

	/** Result cap for searchByPatient. Mirrors a generous production top-K so recall
	 *  is not capped by an artificially small K; override with -Dchartsearchai.qsparity.topk. */
	private static final int TOP_K = Integer.getInteger("chartsearchai.qsparity.topk", 30);

	/** Candidate pool for the cutoff prototype: querystore generates this many candidates,
	 *  then the cosine cutoff trims them. Generous so the post-cutoff recall ceiling isn't
	 *  the candidate K. */
	private static final int CUTOFF_CANDIDATE_K = 50;

	/** Cosine thresholds swept by the cutoff prototype. T=0.0 is the no-cutoff reference
	 *  (== recall@CUTOFF_CANDIDATE_K). */
	private static final double[] CUTOFF_THRESHOLDS =
			{ 0.0, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50 };

	/** Records carry no date in the test datasets; querystore wants a non-null date. Fixed
	 *  so runs are deterministic (the eval never filters or sorts by date). */
	private static final LocalDate FIXED_DATE = LocalDate.of(2000, 1, 1);

	private static final String[][] DATASETS = {
		TestDatasetHelper.FULL_PATIENT_DATASET,
		TestDatasetHelper.SECOND_PATIENT_DATASET,
		TestDatasetHelper.THIRD_PATIENT_DATASET,
		TestDatasetHelper.FOURTH_PATIENT_DATASET,
		TestDatasetHelper.FIFTH_PATIENT_DATASET,
	};

	private static final String[] DATASET_NAMES = { "FULL", "SECOND", "THIRD", "FOURTH", "FIFTH" };

	@SuppressWarnings("unchecked")
	private static final Map<Integer, List<String>>[] DATASET_HINTS = new Map[] {
		TestDatasetHelper.FULL_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.SECOND_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.THIRD_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.FOURTH_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.FIFTH_DATASET_CATEGORY_HINTS,
	};

	private static OnnxEmbeddingProvider provider;

	private static QueryStoreServiceImpl[] services;

	private static LuceneBackendStore[] backends;

	private static Path[] indexRoots;

	private static String[] patientUuids;

	/** Per-dataset map of resourceUuid -> stored document embedding, captured at index time.
	 *  searchByPatient returns QueryDocuments without a guaranteed embedding on read-back, so the
	 *  cutoff prototype re-scores candidates against this map rather than trusting the read path. */
	private static Map<String, float[]>[] docEmbeddings;

	private static final Map<String, float[]> queryEmbedCache = new HashMap<>();

	private static boolean initialized;

	private static synchronized void ensureInitialized() {
		if (initialized) {
			return;
		}
		Assumptions.assumeTrue(
				new File(TestDatasetHelper.MODEL_PATH).exists()
						&& new File(TestDatasetHelper.VOCAB_PATH).exists(),
				"Skipping: embedding model not found at " + TestDatasetHelper.MODEL_PATH);

		provider = new OnnxEmbeddingProvider(TestDatasetHelper.MODEL_PATH, TestDatasetHelper.VOCAB_PATH);
		int dims = provider.embed("dimension probe").length;

		services = new QueryStoreServiceImpl[DATASETS.length];
		backends = new LuceneBackendStore[DATASETS.length];
		indexRoots = new Path[DATASETS.length];
		patientUuids = new String[DATASETS.length];
		@SuppressWarnings("unchecked")
		Map<String, float[]>[] embByDataset = new Map[DATASETS.length];
		docEmbeddings = embByDataset;

		try {
			for (int d = 0; d < DATASETS.length; d++) {
				indexRoots[d] = Files.createTempDirectory("qsparity-" + d + "-");
				LuceneBackendStore backend = new LuceneBackendStore(indexRoots[d]);
				QueryStoreServiceImpl service = new QueryStoreServiceImpl();
				service.setBackend(backend);
				service.setEmbeddingProvider(provider);

				String patientUuid = "qsparity-patient-" + d;
				List<SerializedRecord> records =
						TestDatasetHelper.toSerializedRecords(DATASETS[d], DATASET_HINTS[d]);

				// One Lucene index per resource type — ensure each writer exists before upsert.
				TreeSet<String> resourceTypes = new TreeSet<>();
				for (SerializedRecord r : records) {
					resourceTypes.add(r.getResourceType());
				}
				for (String type : resourceTypes) {
					backend.ensureSchema(type, SchemaSpec.builder(dims).build());
				}

				Map<String, float[]> embForDataset = new HashMap<>();
				for (SerializedRecord r : records) {
					QueryDocument doc = new QueryDocument();
					doc.setPatientUuid(patientUuid);
					doc.setResourceType(r.getResourceType());
					doc.setResourceUuid(r.getResourceUuid());
					doc.setText(r.getText());
					doc.setDate(FIXED_DATE);
					// Embed exactly what production querystore embeds: getEmbeddingInput().
					// With only text set (no synonym/obs-group metadata) this is the record text.
					float[] vec = provider.embed(doc.getEmbeddingInput());
					doc.setEmbedding(vec);
					embForDataset.put(r.getResourceUuid(), vec);
					service.index(doc);
				}
				docEmbeddings[d] = embForDataset;

				backends[d] = backend;
				services[d] = service;
				patientUuids[d] = patientUuid;
				log.info("Indexed dataset {} ({} records, {} types) into querystore",
						DATASET_NAMES[d], records.size(), resourceTypes.size());
			}
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to build in-process querystore index", e);
		}
		initialized = true;
	}

	@AfterAll
	public static void tearDown() {
		if (provider != null) {
			try {
				provider.close();
			}
			catch (Exception ignored) {
			}
		}
		if (backends != null) {
			for (LuceneBackendStore b : backends) {
				if (b != null) {
					try {
						b.close();
					}
					catch (Exception ignored) {
					}
				}
			}
		}
		if (indexRoots != null) {
			for (Path root : indexRoots) {
				deleteRecursive(root);
			}
		}
	}

	private static void deleteRecursive(Path root) {
		if (root == null) {
			return;
		}
		try {
			Files.walk(root)
					.sorted(java.util.Comparator.reverseOrder())
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						}
						catch (IOException ignored) {
						}
					});
		}
		catch (IOException ignored) {
		}
	}

	/** Runs querystore's production searchByPatient for one case and returns the hit
	 *  dataset indices, mirroring how {@code QueryStoreChartBuilder} preprocesses the
	 *  query (stopword strip) before calling searchByPatient in preFilter mode. */
	private static List<Integer> runQuery(String query, int datasetIndex) {
		String preprocessed = QueryPreprocessor.stripQueryStopwords(query);
		List<Integer> indices = new ArrayList<>();
		if (preprocessed == null || preprocessed.trim().isEmpty()) {
			return indices;
		}
		List<QueryDocument> hits =
				services[datasetIndex].searchByPatient(patientUuids[datasetIndex], preprocessed, TOP_K);
		for (QueryDocument doc : hits) {
			if (doc != null && doc.getResourceUuid() != null) {
				indices.add(TestDatasetHelper.indexForUuid(doc.getResourceUuid()));
			}
		}
		Collections.sort(indices);
		return indices;
	}

	private static float[] queryEmbedding(String preprocessed) {
		float[] cached = queryEmbedCache.get(preprocessed);
		if (cached != null) {
			return cached;
		}
		float[] vec = provider.embedQuery(preprocessed);
		queryEmbedCache.put(preprocessed, vec);
		return vec;
	}

	/** querystore candidate generation (searchByPatient, CUTOFF_CANDIDATE_K) followed by a
	 *  cosine re-score of each candidate against the query embedding. Returns
	 *  (datasetIndex, cosine) pairs sorted by cosine descending — the input the cutoff
	 *  prototype thresholds. */
	private static List<double[]> scoredCandidates(String query, int datasetIndex) {
		String preprocessed = QueryPreprocessor.stripQueryStopwords(query);
		List<double[]> scored = new ArrayList<>();
		if (preprocessed == null || preprocessed.trim().isEmpty()) {
			return scored;
		}
		float[] queryVec = queryEmbedding(preprocessed);
		List<QueryDocument> hits = services[datasetIndex].searchByPatient(
				patientUuids[datasetIndex], preprocessed, CUTOFF_CANDIDATE_K);
		for (QueryDocument doc : hits) {
			if (doc == null || doc.getResourceUuid() == null) {
				continue;
			}
			float[] docVec = docEmbeddings[datasetIndex].get(doc.getResourceUuid());
			if (docVec == null) {
				continue;
			}
			double cosine = ChartSearchAiUtils.cosineSimilarity(queryVec, docVec);
			scored.add(new double[] { TestDatasetHelper.indexForUuid(doc.getResourceUuid()), cosine });
		}
		scored.sort((a, b) -> Double.compare(b[1], a[1]));
		return scored;
	}

	/**
	 * Cutoff prototype: sweeps a cosine relevance threshold over querystore's candidates and
	 * reports, per threshold, the macro recall/precision/F1 on gold-bearing cases and the
	 * absent-data rejection rate. Answers the Phase 0 question — is there an operating point
	 * where a cheap cutoff on querystore's ranking recovers the embedding pipeline's precision
	 * and "no relevant records" behaviour without sacrificing recall?
	 */
	@Test
	public void measureCosineCutoffSweep() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null, "Skipping: embedding model not found");

		JsonNode cases = loadBaseline().get("cases");
		int t = CUTOFF_THRESHOLDS.length;

		double[] sumRecall = new double[t];
		double[] sumPrecision = new double[t];
		double[] sumF1 = new double[t];
		long[] sumPredSize = new long[t];
		int goldCases = 0;
		int absentCases = 0;
		int[] absentRejected = new int[t];

		for (JsonNode c : cases) {
			int dsIdx = c.has("datasetIndex") ? c.get("datasetIndex").asInt() : 0;
			String query = c.get("query").asText();
			List<Integer> gold = new ArrayList<>();
			if (c.has("resultIndices")) {
				for (JsonNode idx : c.get("resultIndices")) {
					gold.add(idx.asInt());
				}
				Collections.sort(gold);
			}

			List<double[]> candidates = scoredCandidates(query, dsIdx);
			boolean absent = gold.isEmpty();
			if (absent) {
				absentCases++;
			}
			else {
				goldCases++;
			}

			for (int i = 0; i < t; i++) {
				double threshold = CUTOFF_THRESHOLDS[i];
				List<Integer> predicted = new ArrayList<>();
				for (double[] pair : candidates) {
					if (pair[1] >= threshold) {
						predicted.add((int) pair[0]);
					}
				}
				Collections.sort(predicted);

				if (absent) {
					if (predicted.isEmpty()) {
						absentRejected[i]++;
					}
				}
				else {
					sumRecall[i] += EvalMetrics.recall(predicted, gold);
					sumPrecision[i] += EvalMetrics.precision(predicted, gold);
					sumF1[i] += EvalMetrics.f1(predicted, gold);
					sumPredSize[i] += predicted.size();
				}
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%n==== Cosine-cutoff sweep over querystore candidates "
				+ "(candidateK=%d, model=%s) ====%n", CUTOFF_CANDIDATE_K, provider.getModelName()));
		sb.append(String.format("gold cases=%d  absent cases=%d%n", goldCases, absentCases));
		sb.append(String.format("%8s | %8s | %9s | %6s | %11s | %s%n",
				"cosineT", "recall", "precision", "F1", "avgPredSize", "absentRejected"));
		for (int i = 0; i < t; i++) {
			double r = goldCases == 0 ? 0 : sumRecall[i] / goldCases;
			double p = goldCases == 0 ? 0 : sumPrecision[i] / goldCases;
			double f = goldCases == 0 ? 0 : sumF1[i] / goldCases;
			double avgPred = goldCases == 0 ? 0 : (double) sumPredSize[i] / goldCases;
			sb.append(String.format("  %5.2f  |  %6.3f  |  %7.3f  | %5.3f | %10.1f  | %d/%d%n",
					CUTOFF_THRESHOLDS[i], r, p, f, avgPred, absentRejected[i], absentCases));

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("cosineThreshold", CUTOFF_THRESHOLDS[i]);
			row.put("recall", r);
			row.put("precision", p);
			row.put("f1", f);
			row.put("avgPredSize", avgPred);
			row.put("absentRejected", absentRejected[i]);
			row.put("absentCases", absentCases);
			EvalReporter.appendSummary("querystore-cutoff-T" + CUTOFF_THRESHOLDS[i], row);
		}
		sb.append("recall/precision are macro-averaged over gold-bearing cases; "
				+ "absentRejected = absent-data queries the cutoff correctly emptied.%n");
		log.info(sb.toString());

		// Smoke: the sweep ran and the cutoff demonstrably trims (higher T => fewer predicted).
		assertTrue(goldCases > 0, "expected gold-bearing cases");
		assertTrue(sumPredSize[t - 1] <= sumPredSize[0],
				"a higher cosine threshold must not predict more records than a lower one");
	}

	/**
	 * Adaptive cutoff prototype: runs the production {@link RelevanceCutoff} (top-anchored
	 * absent gate + floor + gap trim) over querystore's cosine-scored candidates and sweeps
	 * its parameters. Compares against the global-threshold curve (§3b) to see whether an
	 * adaptive, per-query cutoff recovers precision at higher recall and rejects absent-data
	 * queries that a single global threshold could not.
	 */
	@Test
	public void measureAdaptiveCutoffSweep() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null, "Skipping: embedding model not found");

		// Focused grid: absent gate anchored on the top score, a lower keep-floor for the tail,
		// and a gap that trims the tail at a score cliff. maxKeep generous so the gap, not K, cuts.
		double[] absentTopFloors = { 0.30, 0.35, 0.40 };
		double[] keepFloors = { 0.20, 0.25 };
		double[] maxGaps = { 0.08, 0.12, 0.50 };
		int maxKeep = 30;

		List<RelevanceCutoff.Params> grid = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (double atf : absentTopFloors) {
			for (double kf : keepFloors) {
				for (double mg : maxGaps) {
					grid.add(new RelevanceCutoff.Params(atf, kf, mg, maxKeep));
					labels.add(String.format("atf=%.2f kf=%.2f gap=%.2f", atf, kf, mg));
				}
			}
		}

		int g = grid.size();
		double[] sumRecall = new double[g];
		double[] sumPrecision = new double[g];
		double[] sumF1 = new double[g];
		long[] sumPredSize = new long[g];
		int goldCases = 0;
		int absentCases = 0;
		int[] absentRejected = new int[g];

		JsonNode cases = loadBaseline().get("cases");
		for (JsonNode c : cases) {
			int dsIdx = c.has("datasetIndex") ? c.get("datasetIndex").asInt() : 0;
			String query = c.get("query").asText();
			List<Integer> gold = new ArrayList<>();
			if (c.has("resultIndices")) {
				for (JsonNode idx : c.get("resultIndices")) {
					gold.add(idx.asInt());
				}
				Collections.sort(gold);
			}

			List<double[]> candidates = scoredCandidates(query, dsIdx);
			boolean absent = gold.isEmpty();
			if (absent) {
				absentCases++;
			}
			else {
				goldCases++;
			}

			for (int i = 0; i < g; i++) {
				List<Integer> predicted = RelevanceCutoff.apply(candidates, grid.get(i));
				Collections.sort(predicted);
				if (absent) {
					if (predicted.isEmpty()) {
						absentRejected[i]++;
					}
				}
				else {
					sumRecall[i] += EvalMetrics.recall(predicted, gold);
					sumPrecision[i] += EvalMetrics.precision(predicted, gold);
					sumF1[i] += EvalMetrics.f1(predicted, gold);
					sumPredSize[i] += predicted.size();
				}
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%n==== Adaptive cutoff sweep (RelevanceCutoff over querystore "
				+ "candidates, candidateK=%d, model=%s) ====%n",
				CUTOFF_CANDIDATE_K, provider.getModelName()));
		sb.append(String.format("gold cases=%d  absent cases=%d%n", goldCases, absentCases));
		sb.append(String.format("%-26s | %6s | %9s | %6s | %11s | %s%n",
				"params", "recall", "precision", "F1", "avgPredSize", "absentRejected"));
		int bestIdx = -1;
		double bestF1 = -1;
		for (int i = 0; i < g; i++) {
			double r = goldCases == 0 ? 0 : sumRecall[i] / goldCases;
			double p = goldCases == 0 ? 0 : sumPrecision[i] / goldCases;
			double f = goldCases == 0 ? 0 : sumF1[i] / goldCases;
			double avgPred = goldCases == 0 ? 0 : (double) sumPredSize[i] / goldCases;
			sb.append(String.format("%-26s | %6.3f | %9.3f | %6.3f | %11.1f | %d/%d%n",
					labels.get(i), r, p, f, avgPred, absentRejected[i], absentCases));
			if (f > bestF1) {
				bestF1 = f;
				bestIdx = i;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("params", labels.get(i));
			row.put("recall", r);
			row.put("precision", p);
			row.put("f1", f);
			row.put("avgPredSize", avgPred);
			row.put("absentRejected", absentRejected[i]);
			row.put("absentCases", absentCases);
			EvalReporter.appendSummary("querystore-adaptive[" + labels.get(i) + "]", row);
		}
		sb.append(String.format("best F1 = %.3f at [%s]%n", bestF1, labels.get(bestIdx)));
		sb.append(String.format("global-threshold best for reference: F1 0.590 "
				+ "(recall 0.828, precision 0.577, absent 92/161) at cosineT=0.30%n"));
		log.info(sb.toString());

		assertTrue(goldCases > 0, "expected gold-bearing cases");
		assertTrue(bestF1 > 0.0, "adaptive cutoff produced no usable operating point");
	}

	/** Top score of a backend result under {@link Hit#getRawScore()}; NaN when empty. */
	private static double maxRawScore(SearchResult result) {
		double max = Double.NaN;
		for (Hit h : result.getHits()) {
			if (Double.isNaN(max) || h.getRawScore() > max) {
				max = h.getRawScore();
			}
		}
		return max;
	}

	/**
	 * Absent-gate signal diagnostic. The adaptive cutoff (§3c) showed the binding constraint is
	 * absent-data separation, and cosine on the top candidate can't cleanly do it. This measures
	 * how well each available top-of-list signal separates gold-bearing from absent-data queries:
	 * cosine, querystore's RRF-fused hybrid score, raw BM25 score, and BM25 hit-presence. For each
	 * signal it sweeps the gate threshold and reports the frontier of (absent rejected, gold kept) —
	 * "gold kept" = gold queries the gate does NOT wrongly silence. The signal with the best frontier
	 * is the one worth exposing from querystore's search API. (Fused RRF is rank-based, so it is
	 * expected to wash out the keyword-absence signal that raw BM25 carries — this quantifies that.)
	 */
	@Test
	public void measureAbsentGateSignals() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null, "Skipping: embedding model not found");

		List<Double> goldCosine = new ArrayList<>();
		List<Double> absentCosine = new ArrayList<>();
		List<Double> goldFused = new ArrayList<>();
		List<Double> absentFused = new ArrayList<>();
		List<Double> goldBm25 = new ArrayList<>();
		List<Double> absentBm25 = new ArrayList<>();
		int goldBm25Present = 0, goldTotal = 0;
		int absentBm25Present = 0, absentTotal = 0;

		JsonNode cases = loadBaseline().get("cases");
		for (JsonNode c : cases) {
			int dsIdx = c.has("datasetIndex") ? c.get("datasetIndex").asInt() : 0;
			String query = c.get("query").asText();
			boolean absent = !c.has("resultIndices") || c.get("resultIndices").size() == 0;

			String pp = QueryPreprocessor.stripQueryStopwords(query);
			if (pp == null || pp.trim().isEmpty()) {
				continue;
			}
			float[] qv = queryEmbedding(pp);
			Filter scope = Filter.patientScope(patientUuids[dsIdx]);
			LuceneBackendStore backend = backends[dsIdx];

			List<double[]> cos = scoredCandidates(query, dsIdx);
			double topCosine = cos.isEmpty() ? Double.NaN : cos.get(0)[1];

			SearchResult bm25 = backend.bm25(SearchRequest.builder()
					.queryText(pp).filter(scope).limit(CUTOFF_CANDIDATE_K).build());
			SearchResult hybrid = backend.hybrid(SearchRequest.builder()
					.queryText(pp).queryVector(qv).filter(scope).limit(CUTOFF_CANDIDATE_K).build());
			double topBm25 = maxRawScore(bm25);
			double topFused = maxRawScore(hybrid);
			boolean bm25Present = !bm25.getHits().isEmpty();

			if (absent) {
				absentTotal++;
				if (!Double.isNaN(topCosine)) absentCosine.add(topCosine);
				if (!Double.isNaN(topFused)) absentFused.add(topFused);
				if (!Double.isNaN(topBm25)) absentBm25.add(topBm25);
				if (bm25Present) absentBm25Present++;
			}
			else {
				goldTotal++;
				if (!Double.isNaN(topCosine)) goldCosine.add(topCosine);
				if (!Double.isNaN(topFused)) goldFused.add(topFused);
				if (!Double.isNaN(topBm25)) goldBm25.add(topBm25);
				if (bm25Present) goldBm25Present++;
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%n==== Absent-gate signal separation (gold=%d, absent=%d) ====%n",
				goldTotal, absentTotal));
		appendFrontier(sb, "cosine (top candidate)", goldCosine, absentCosine);
		appendFrontier(sb, "fused RRF (top hybrid)", goldFused, absentFused);
		appendFrontier(sb, "raw BM25 (top)", goldBm25, absentBm25);
		sb.append(String.format("%nBM25 hit-presence gate (absent iff zero keyword hits):%n"));
		sb.append(String.format("  goldKept   = %d/%d (%.3f)  [gold queries with >=1 BM25 hit]%n",
				goldBm25Present, goldTotal, goldTotal == 0 ? 0 : (double) goldBm25Present / goldTotal));
		sb.append(String.format("  absentRej  = %d/%d (%.3f)  [absent queries with zero BM25 hits]%n",
				absentTotal - absentBm25Present, absentTotal,
				absentTotal == 0 ? 0 : (double) (absentTotal - absentBm25Present) / absentTotal));
		log.info(sb.toString());

		assertTrue(goldTotal > 0 && absentTotal > 0, "expected both gold and absent cases");
	}

	/** Sweeps a gate threshold over the pooled signal values and appends a (threshold ->
	 *  absentRejected, goldKept) frontier. goldKept = P(goldTop >= T); absentRejected = P(absentTop < T). */
	private static void appendFrontier(StringBuilder sb, String name,
			List<Double> gold, List<Double> absent) {
		List<Double> pooled = new ArrayList<>(gold);
		pooled.addAll(absent);
		Collections.sort(pooled);
		sb.append(String.format("%n-- %s --   (gold n=%d, absent n=%d)%n", name, gold.size(), absent.size()));
		sb.append(String.format("%10s | %9s | %8s%n", "threshold", "absentRej", "goldKept"));
		if (pooled.isEmpty()) {
			sb.append("   (no data)\n");
			return;
		}
		double bestKeepAt80 = -1, bestThreshAt80 = Double.NaN;
		for (int q = 1; q <= 9; q++) {
			double t = pooled.get(Math.min(pooled.size() - 1, (int) Math.round((q / 10.0) * (pooled.size() - 1))));
			double absentRej = absent.isEmpty() ? 0 : absent.stream().filter(v -> v < t).count() / (double) absent.size();
			double goldKept = gold.isEmpty() ? 0 : gold.stream().filter(v -> v >= t).count() / (double) gold.size();
			sb.append(String.format("%10.4f | %9.3f | %8.3f%n", t, absentRej, goldKept));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("signal", name);
			row.put("threshold", t);
			row.put("absentRej", absentRej);
			row.put("goldKept", goldKept);
			EvalReporter.appendSummary("absentgate", row);
			if (absentRej >= 0.80 && goldKept > bestKeepAt80) {
				bestKeepAt80 = goldKept;
				bestThreshAt80 = t;
			}
		}
		if (bestKeepAt80 >= 0) {
			sb.append(String.format("  best goldKept at absentRej>=0.80: %.3f (T=%.4f)%n",
					bestKeepAt80, bestThreshAt80));
		}
		else {
			sb.append("  absentRej never reaches 0.80 at any swept threshold\n");
		}
	}

	@Test
	public void measureQueryStoreParityAgainstEmbeddingBaseline() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null, "Skipping: embedding model not found");

		JsonNode cases = loadBaseline().get("cases");

		// Per-dataset and overall accumulators, split into gold-bearing vs absent (empty-gold) cases.
		double[] sumRecall = new double[DATASETS.length];
		double[] sumPrecision = new double[DATASETS.length];
		double[] sumF1 = new double[DATASETS.length];
		int[] goldCases = new int[DATASETS.length];
		int[] absentCases = new int[DATASETS.length];
		int[] absentWithHits = new int[DATASETS.length];

		int processed = 0;
		for (JsonNode c : cases) {
			int dsIdx = c.has("datasetIndex") ? c.get("datasetIndex").asInt() : 0;
			String query = c.get("query").asText();
			List<Integer> gold = new ArrayList<>();
			if (c.has("resultIndices")) {
				for (JsonNode idx : c.get("resultIndices")) {
					gold.add(idx.asInt());
				}
				Collections.sort(gold);
			}

			List<Integer> predicted = runQuery(query, dsIdx);
			processed++;

			if (gold.isEmpty()) {
				absentCases[dsIdx]++;
				if (!predicted.isEmpty()) {
					absentWithHits[dsIdx]++;
				}
				EvalReporter.appendResult("querystore-parity",
						DATASET_NAMES[dsIdx] + ":absent:" + query,
						metricRow(gold.size(), predicted.size(), Double.NaN, Double.NaN, Double.NaN));
				continue;
			}

			double recall = EvalMetrics.recall(predicted, gold);
			double precision = EvalMetrics.precision(predicted, gold);
			double f1 = EvalMetrics.f1(predicted, gold);
			sumRecall[dsIdx] += recall;
			sumPrecision[dsIdx] += precision;
			sumF1[dsIdx] += f1;
			goldCases[dsIdx]++;

			EvalReporter.appendResult("querystore-parity",
					DATASET_NAMES[dsIdx] + ":" + query,
					metricRow(gold.size(), predicted.size(), recall, precision, f1));
		}

		// --- Report ---
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%n==== QueryStore retrieval parity vs embedding-pipeline baseline ====%n"));
		sb.append(String.format("model=%s  topK=%d  baseline=%s%n",
				provider.getModelName(), TOP_K, BASELINE));
		sb.append(String.format("%-7s | %5s | %s | %s | %s | %5s | %s%n",
				"dataset", "gold", "recall@K", " prec@K ", "  F1@K ", "absnt", "absentOverReturn"));

		int totGold = 0, totAbsent = 0, totAbsentHits = 0;
		double totRecall = 0, totPrecision = 0, totF1 = 0;
		for (int d = 0; d < DATASETS.length; d++) {
			double r = goldCases[d] == 0 ? 0 : sumRecall[d] / goldCases[d];
			double p = goldCases[d] == 0 ? 0 : sumPrecision[d] / goldCases[d];
			double f = goldCases[d] == 0 ? 0 : sumF1[d] / goldCases[d];
			sb.append(String.format("%-7s | %5d |  %6.3f |  %6.3f |  %6.3f | %5d | %d/%d%n",
					DATASET_NAMES[d], goldCases[d], r, p, f, absentCases[d],
					absentWithHits[d], absentCases[d]));
			totGold += goldCases[d];
			totAbsent += absentCases[d];
			totAbsentHits += absentWithHits[d];
			totRecall += sumRecall[d];
			totPrecision += sumPrecision[d];
			totF1 += sumF1[d];
		}
		double macroRecall = totGold == 0 ? 0 : totRecall / totGold;
		double macroPrecision = totGold == 0 ? 0 : totPrecision / totGold;
		double macroF1 = totGold == 0 ? 0 : totF1 / totGold;
		sb.append(String.format("%-7s | %5d |  %6.3f |  %6.3f |  %6.3f | %5d | %d/%d%n",
				"ALL", totGold, macroRecall, macroPrecision, macroF1,
				totAbsent, totAbsentHits, totAbsent));
		sb.append(String.format("recall@K = fraction of embedding-selected records querystore surfaces in top-%d%n", TOP_K));
		sb.append(String.format("absentOverReturn = absent-data queries where querystore returned >=1 record "
				+ "(no adaptive cutoff)%n"));
		log.info(sb.toString());

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("model", provider.getModelName());
		summary.put("topK", TOP_K);
		summary.put("goldCases", totGold);
		summary.put("macroRecall", macroRecall);
		summary.put("macroPrecision", macroPrecision);
		summary.put("macroF1", macroF1);
		summary.put("absentCases", totAbsent);
		summary.put("absentOverReturn", totAbsentHits);
		EvalReporter.appendSummary("querystore-parity", summary);

		// Smoke assertions — this harness measures parity; it does not gate on a bar.
		// What it guards: every baseline case ran, and querystore actually retrieved
		// against gold-bearing queries (a wiring failure would yield zero recall).
		assertEquals(cases.size(), processed, "every baseline case should be evaluated");
		assertTrue(totGold > 0, "expected gold-bearing cases in the baseline");
		assertTrue(macroRecall > 0.0,
				"querystore returned nothing for every gold-bearing query — retrieval wiring is broken");
	}

	private static Map<String, Object> metricRow(int goldSize, int predictedSize,
			double recall, double precision, double f1) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("goldSize", goldSize);
		m.put("predictedSize", predictedSize);
		m.put("recall", recall);
		m.put("precision", precision);
		m.put("f1", f1);
		return m;
	}

	private static JsonNode loadBaseline() {
		try {
			return new ObjectMapper().readTree(
					new InputStreamReader(
							QueryStoreRetrievalParityEvalTest.class.getClassLoader()
									.getResourceAsStream(BASELINE),
							StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to load eval baseline: " + BASELINE, e);
		}
	}
}
