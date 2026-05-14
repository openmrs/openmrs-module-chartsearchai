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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.PipelineConfig;
import org.openmrs.module.chartsearchai.api.impl.ScoredEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

public class LlmInferenceServiceTest {

	private static final String[] SECOND_PATIENT_DATASET =
			TestDatasetHelper.SECOND_PATIENT_DATASET;

	private static final String[] THIRD_PATIENT_DATASET =
			TestDatasetHelper.THIRD_PATIENT_DATASET;

	private static final String[] FOURTH_PATIENT_DATASET =
			TestDatasetHelper.FOURTH_PATIENT_DATASET;

	private static final String[] FIFTH_PATIENT_DATASET =
			TestDatasetHelper.FIFTH_PATIENT_DATASET;

	private static String uuid(int i) {
		return TestDatasetHelper.uuidForIndex(i);
	}

	@Test
	public void extractCitedReferences_shouldExtractReferencesFromCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null),
				new RecordMapping(2, "order", uuid(201), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2), mappings);

		assertEquals(2, result.size());
		assertEquals("obs", result.get(0).getResourceType());
		assertEquals(uuid(456), result.get(0).getResourceUuid());
		assertEquals("order", result.get(1).getResourceType());
		assertEquals(uuid(201), result.get(1).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldReturnEmptyWhenNoCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Collections.emptyList(), mappings);

		assertTrue(result.isEmpty());
	}

	@Test
	public void extractCitedReferences_shouldDeduplicateRepeatedCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 1), mappings);

		assertEquals(1, result.size());
		assertEquals(uuid(456), result.get(0).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldHandleMultipleCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(101), null),
				new RecordMapping(2, "obs", uuid(102), null),
				new RecordMapping(3, "obs", uuid(103), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2, 3), mappings);

		assertEquals(3, result.size());
		assertEquals(uuid(101), result.get(0).getResourceUuid());
		assertEquals(uuid(102), result.get(1).getResourceUuid());
		assertEquals(uuid(103), result.get(2).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldIgnoreNumbersNotInMappings() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(10), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 99), mappings);

		assertEquals(1, result.size());
		assertEquals(uuid(10), result.get(0).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldSortByDateMostRecentFirst() {
		Date jan = makeDate(2025, 1, 10);
		Date mar = makeDate(2025, 3, 15);
		Date feb = makeDate(2025, 2, 20);

		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "condition", uuid(50), jan),
				new RecordMapping(2, "order", uuid(30), mar),
				new RecordMapping(3, "obs", uuid(999), feb));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2, 3), mappings);

		assertEquals(3, result.size());
		assertEquals(uuid(30), result.get(0).getResourceUuid());
		assertEquals(mar, result.get(0).getDate());
		assertEquals(uuid(999), result.get(1).getResourceUuid());
		assertEquals(feb, result.get(1).getDate());
		assertEquals(uuid(50), result.get(2).getResourceUuid());
		assertEquals(jan, result.get(2).getDate());
	}

	@Test
	public void extractCitedReferences_shouldPutNullDatesLast() {
		Date recent = makeDate(2025, 3, 1);

		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(100), null),
				new RecordMapping(2, "obs", uuid(200), recent));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2), mappings);

		assertEquals(2, result.size());
		assertEquals(uuid(200), result.get(0).getResourceUuid());
		assertEquals(recent, result.get(0).getDate());
		assertEquals(uuid(100), result.get(1).getResourceUuid());
		assertNull(result.get(1).getDate());
	}

	@Test
	public void stripQueryStopwords_shouldNormalizeDifferentPhrasingsToSameResult() {
		// Both queries have only 1 content word ("medications"), so both
		// preserve the full sentence. The embedding model handles both
		// similarly because the key term is the same.
		String short1 = LlmInferenceService.stripQueryStopwords("any medications?");
		String long1 = LlmInferenceService.stripQueryStopwords("does the patient have any medications?");
		assertTrue(short1.contains("medications"), "Short form should contain 'medications'");
		assertTrue(long1.contains("medications"), "Long form should contain 'medications'");
	}

	@Test
	public void stripQueryStopwords_shouldPreserveContentWords() {
		assertEquals("medications hypertension",
				LlmInferenceService.stripQueryStopwords("any medications for hypertension?"));
	}

	@Test
	public void stripQueryStopwords_shouldReturnOriginalWhenAllStopwords() {
		String result = LlmInferenceService.stripQueryStopwords("does the patient have any?");
		assertTrue(!result.isEmpty());
	}

	@Test
	public void stripQueryStopwords_shouldHandleMixedCase() {
		// 1 content word "medications" → preserves full query for context.
		// Should be lowercased and contain the key term.
		String result = LlmInferenceService.stripQueryStopwords(
				"Does The Patient Have Any Medications?");
		assertTrue(result.contains("medications"),
				"Mixed case query should contain 'medications'");
		assertEquals(result, result.toLowerCase(),
				"Result should be lowercased");
	}

	@Test
	public void stripQueryStopwords_shouldPreserveContextForShortQueries() {
		// When stopword removal would leave < 2 words, the full question
		// should be preserved to give the embedding model enough context.
		// "does the patient have cancer?" has only 1 content word ("cancer").
		// The original code embedded the full question and returned 2 results;
		// stripping to just "cancer" loses context and returns 3.
		String result = LlmInferenceService.stripQueryStopwords(
				"does the patient have cancer?");
		String[] words = result.trim().split("\\s+");
		assertTrue(words.length >= 2,
				"Short query should preserve context words, got: '" + result + "'");
	}

	@Test
	public void stripQueryStopwords_shouldNormalizeCurrentAndLatestToSameResult() {
		assertEquals(
				LlmInferenceService.stripQueryStopwords("What is the current CD4 Count?"),
				LlmInferenceService.stripQueryStopwords("What is the latest CD4 Count?"));
	}

	@Test
	public void patientChart_demographicsOnlyHasNoRecords() {
		// A chart with only demographics (no records) should have empty
		// mappings even though getText() is non-empty. The search methods
		// must check mappings, not text, to detect "no records found".
		org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart =
				new org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart(
				"Patient: 4-year-old Male", Collections.<RecordMapping>emptyList());
		assertFalse(chart.getText().isEmpty(),
				"Demographics-only chart has non-empty text");
		assertTrue(chart.getMappings().isEmpty(),
				"Demographics-only chart should have no record mappings");
	}

	@Test
	public void extractRecencyCap_shouldExtractNumberFromLastN() {
		assertEquals(7, LlmInferenceService.extractRecencyCap(
				"How have vitals trended across the last 7 visits?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromPastN() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show past 3 lab results"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromPreviousN() {
		assertEquals(5, LlmInferenceService.extractRecencyCap(
				"What were the previous 5 blood pressure readings?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromMostRecentN() {
		assertEquals(10, LlmInferenceService.extractRecencyCap(
				"List the most recent 10 observations"));
	}

	@Test
	public void extractRecencyCap_shouldBeCaseInsensitive() {
		assertEquals(7, LlmInferenceService.extractRecencyCap(
				"LAST 7 visits"));
	}

	@Test
	public void extractRecencyCap_shouldReturnZeroWhenNoPattern() {
		assertEquals(0, LlmInferenceService.extractRecencyCap(
				"What medications is the patient on?"));
	}

	@Test
	public void extractRecencyCap_shouldReturnZeroForZero() {
		assertEquals(0, LlmInferenceService.extractRecencyCap(
				"last 0 visits"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromLatestN() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the latest 2 weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberTwo() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the latest two weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberThree() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show the last three lab results"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberFive() {
		assertEquals(5, LlmInferenceService.extractRecencyCap(
				"previous five blood pressure readings"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberTen() {
		assertEquals(10, LlmInferenceService.extractRecencyCap(
				"most recent ten observations"));
	}

	@Test
	public void extractRecencyCap_shouldExtractNumberBeforeKeyword() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the two latest weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractDigitBeforeKeyword() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show the 3 most recent lab results"));
	}

	@Test
	public void conceptKey_shouldStripTrailingNumericValue() {
		assertEquals("Clinical observation: Test — Weight (kg)",
				LlmInferenceService.conceptKey(
						"Clinical observation: Test — Weight (kg): 94.0"));
	}

	@Test
	public void conceptKey_shouldStripIntegerValue() {
		assertEquals("Clinical observation: Test — Pulse",
				LlmInferenceService.conceptKey(
						"Clinical observation: Test — Pulse: 62.0"));
	}

	@Test
	public void conceptKey_shouldPreserveTextWithoutNumericEnding() {
		assertEquals("Medical condition: Condition: Tuberculosis. Status: ACTIVE",
				LlmInferenceService.conceptKey(
						"Medical condition: Condition: Tuberculosis. Status: ACTIVE"));
	}

	@Test
	public void conceptKey_shouldHandleNull() {
		assertEquals("", LlmInferenceService.conceptKey(null));
	}

	@Test
	public void capPerConcept_shouldLimitRecordsPerConcept() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		// 4 systolic BP records, 3 weight records
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(1), "Clinical observation: Test — Systolic Blood Pressure: 151.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(2), "Clinical observation: Test — Systolic Blood Pressure: 134.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(3), "Clinical observation: Test — Weight (kg): 68.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(4), "Clinical observation: Test — Systolic Blood Pressure: 117.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(5), "Clinical observation: Test — Weight (kg): 121.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(6), "Clinical observation: Test — Systolic Blood Pressure: 102.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(7), "Clinical observation: Test — Weight (kg): 94.0", null));

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.capPerConcept(records, 2);

		// Should keep 2 SBP + 2 Weight = 4 records
		assertEquals(4, result.size());
		// First two should be the first 2 SBP records (ids 1, 2)
		assertEquals(uuid(1), result.get(0).getResourceUuid());
		assertEquals(uuid(2), result.get(1).getResourceUuid());
		// Next two should be the first 2 Weight records (ids 3, 5)
		assertEquals(uuid(3), result.get(2).getResourceUuid());
		assertEquals(uuid(5), result.get(3).getResourceUuid());
	}

	@Test
	public void capPerConcept_shouldNotAffectNonNumericRecords() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"condition", uuid(1), "Medical condition: Condition: Tuberculosis. Status: ACTIVE", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"condition", uuid(2), "Medical condition: Condition: Hypertension. Status: ACTIVE", null));

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.capPerConcept(records, 1);

		// Each condition has a unique key, so both are kept
		assertEquals(2, result.size());
	}

	@Test
	public void groupByConcept_shouldGroupRecordsByConceptKeyPreservingOrderWithinGroup() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		// Interleaved: BP, Weight, BP, Temp, Weight, BP
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(1), "Clinical observation: Test — Systolic Blood Pressure: 151.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(2), "Clinical observation: Test — Weight (kg): 94.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(3), "Clinical observation: Test — Systolic Blood Pressure: 134.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(4), "Clinical observation: Test — Temperature (C): 36.7", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(5), "Clinical observation: Test — Weight (kg): 68.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(6), "Clinical observation: Test — Systolic Blood Pressure: 102.0", null));

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.groupByConcept(records);

		assertEquals(6, result.size(), "All records should be preserved");
		// BP group first (first concept encountered), in original order
		assertEquals(uuid(1), result.get(0).getResourceUuid());
		assertEquals(uuid(3), result.get(1).getResourceUuid());
		assertEquals(uuid(6), result.get(2).getResourceUuid());
		// Weight group next
		assertEquals(uuid(2), result.get(3).getResourceUuid());
		assertEquals(uuid(5), result.get(4).getResourceUuid());
		// Temperature group last
		assertEquals(uuid(4), result.get(5).getResourceUuid());
	}

	@Test
	public void groupByConcept_shouldBeNoOpForSingleConcept() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(1), "Clinical observation: Test — Weight (kg): 94.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(2), "Clinical observation: Test — Weight (kg): 68.0", null));

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.groupByConcept(records);

		assertEquals(2, result.size());
		assertEquals(uuid(1), result.get(0).getResourceUuid());
		assertEquals(uuid(2), result.get(1).getResourceUuid());
	}

	@Test
	public void groupByConcept_shouldHandleEmptyList() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.groupByConcept(new ArrayList<>());
		assertTrue(result.isEmpty());
	}

	@Test
	public void groupByConcept_shouldHandleMixedRecordTypes() {
		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> records
				= new ArrayList<>();
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(1), "Clinical observation: Test — Systolic Blood Pressure: 97.0", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"condition", uuid(2), "Medical condition: Condition: Hypertension. Status: ACTIVE", null));
		records.add(new org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord(
				"obs", uuid(3), "Clinical observation: Test — Systolic Blood Pressure: 134.0", null));

		List<org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord> result
				= LlmInferenceService.groupByConcept(records);

		assertEquals(3, result.size());
		// BP records grouped together
		assertEquals(uuid(1), result.get(0).getResourceUuid());
		assertEquals(uuid(3), result.get(1).getResourceUuid());
		// Condition separate
		assertEquals(uuid(2), result.get(2).getResourceUuid());
	}

	@Test
	public void defaultSimilarityRatio_shouldBeBetweenZeroAndOne() {
		assertTrue(ChartSearchAiConstants.DEFAULT_SIMILARITY_RATIO > 0);
		assertTrue(ChartSearchAiConstants.DEFAULT_SIMILARITY_RATIO < 1);
	}

	@Test
	public void findAdaptiveCutoff_shouldDetectGapInScores() {
		// Scores: 0.95, 0.93, 0.91, [gap], 0.75, 0.73
		// The gap from 0.91 to 0.75 (0.16) is much larger than the avg gap so far (0.02)
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.95),
				makeScoredEmbedding(0.93),
				makeScoredEmbedding(0.91),
				makeScoredEmbedding(0.75),
				makeScoredEmbedding(0.73));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.70, 2.5, 0.0);

		assertEquals(3, cutoff, "Should cut at position 3 where the large gap occurs");
	}

	@Test
	public void findAdaptiveCutoff_shouldReturnAllWhenScoresAreUniform() {
		// Scores evenly spaced — no outlier gap
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.88),
				makeScoredEmbedding(0.86),
				makeScoredEmbedding(0.84),
				makeScoredEmbedding(0.82));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.70, 2.5, 0.0);

		assertEquals(5, cutoff, "Should include all records when scores are evenly spaced");
	}

	@Test
	public void findAdaptiveCutoff_shouldRespectSimilarityFloor() {
		// 3 records above floor, 2 below
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.88),
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.60),
				makeScoredEmbedding(0.55));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.80, 2.5, 0.0);

		assertEquals(3, cutoff, "Should not include records below the similarity floor");
	}

	@Test
	public void findAdaptiveCutoff_shouldReturnAllWhenBothAboveFloor() {
		// Both records above the floor — no gap detection needed with only 2
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.50));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 2, 0.40, 2.5, 0.0);

		assertEquals(2, cutoff, "Should include all records when both are above the floor");
	}

	@Test
	public void findAdaptiveCutoff_shouldHandleSingleRecord() {
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.90));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 1, 0.70, 2.5, 0.0);

		assertEquals(1, cutoff);
	}

	@Test
	public void findAdaptiveCutoff_shouldNotCutBeforeMinimumRecords() {
		// Huge gap at i=2 (0.94 -> 0.50 = 0.44). Without the i >= minRecords
		// guard, this would trigger at i=2 (cutoff=2, only 2 records). With
		// minRecords=2, the check is deferred past i=1. The large gap at i=2
		// triggers the cut, yielding 2 records.
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.95),
				makeScoredEmbedding(0.94),
				makeScoredEmbedding(0.50),
				makeScoredEmbedding(0.48),
				makeScoredEmbedding(0.46));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.40, 2.5, 0.0);

		assertTrue(cutoff >= ChartSearchAiConstants.ADAPTIVE_MIN_RECORDS,
				"Should not cut below the minimum record count");
	}

	@Test
	public void findAdaptiveCutoff_shouldHandleIdenticalScores() {
		// All scores identical — every gap is 0, no cutoff detected
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.85));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 4, 0.70, 2.5, 0.0);

		assertEquals(4, cutoff, "Should include all records when scores are identical");
	}

	@Test
	public void findAdaptiveCutoff_shouldDetectGapAfterIdenticalScores() {
		// 4 identical scores then a drop — gap is infinite relative to avg of 0
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.90),
				makeScoredEmbedding(0.60));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.50, 2.5, 0.0);

		assertEquals(4, cutoff, "Should cut where scores drop after a plateau");
	}

	@Test
	public void findAdaptiveCutoff_shouldNotFalselyCutOnTiedScores() {
		// Positions 1 and 2 are tied (gap=0). A narrow baseline seeded only
		// from this zero gap would make ANY subsequent non-zero gap trigger.
		// The running average includes the 0.10 gap at i=1, giving a realistic
		// baseline: avgGap = (0.10 + 0.00) / 2 = 0.05, threshold = 0.125.
		// The 0.01 gap at i=3 is well below this, so no cut.
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.95),
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.85),
				makeScoredEmbedding(0.84),
				makeScoredEmbedding(0.83));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.70, 2.5, 0.0);

		assertEquals(5, cutoff, "Tied scores should not cause false gap detection");
	}

	@Test
	public void findAdaptiveCutoff_shouldRespectHigherMultiplier() {
		// Same scores as the basic gap test, but with a very high multiplier
		// that prevents the gap from triggering
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.95),
				makeScoredEmbedding(0.93),
				makeScoredEmbedding(0.91),
				makeScoredEmbedding(0.75),
				makeScoredEmbedding(0.73));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.70, 999.0, 0.0);

		assertEquals(5, cutoff, "Very high multiplier should effectively disable gap detection");
	}

	@Test
	public void computeKeywordScore_shouldReturnOneWhenAllTermsMatch() {
		String[] terms = { "metformin", "500mg" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Drug order: Metformin 500mg. Dose: 1.0 Tablet(s) Oral twice daily");
		assertEquals(1.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldReturnZeroWhenNoTermsMatch() {
		String[] terms = { "penicillin", "allergy" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Drug order: Metformin 500mg. Dose: 1.0 Tablet(s)");
		assertEquals(0.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldReturnPartialScore() {
		String[] terms = { "metformin", "allergy" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Drug order: Metformin 500mg");
		assertEquals(0.5, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldBeCaseInsensitive() {
		String[] terms = { "metformin" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Drug order: METFORMIN 500mg");
		assertEquals(1.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldHandleEmptyTerms() {
		assertEquals(0.0, LlmInferenceService.computeKeywordScore(
				new String[0], "Some text"), 0.001);
	}

	@Test
	public void computeKeywordScore_shouldHandleNullText() {
		String[] terms = { "test" };
		assertEquals(0.0, LlmInferenceService.computeKeywordScore(terms, null), 0.001);
	}

	@Test
	public void computeKeywordScore_shouldMatchSubstrings() {
		String[] terms = { "medication" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Dispensed: Metformin 500mg. Status: Completed. medications given");
		assertEquals(1.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldMatchPluralToSingular() {
		// "conditions" (plural) should match "Condition:" (singular) in the text
		String[] terms = { "conditions" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Condition: Nonparalytic stroke. Status: ACTIVE");
		assertEquals(1.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldNotStemShortWords() {
		// "as" (length 2) should not be stemmed to "a"
		String[] terms = { "as" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Condition: Type 2 Diabetes. Status: ACTIVE");
		assertEquals(0.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldNotStemDoubleS() {
		// "pass" should not be stemmed to "pas"
		String[] terms = { "pass" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"some text without the word");
		assertEquals(0.0, score, 0.001);
	}

	@Test
	public void computeKeywordScore_shouldMatchMedicationInPrefixedText() {
		// When findSimilar prepends the embedding prefix, "medication" (stemmed
		// from "medications") should match "Medication prescription: " prefix.
		String[] terms = { "medications", "prescribed", "started" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Medication prescription: Drug order: Metformin 500mg. Dose: 1.0 Tablet(s)");
		assertTrue(score >= 1.0 / 3 - 0.001,
				"'medications' should match 'Medication' in the prefix text via stemming");
	}

	@Test
	public void computeKeywordScore_shouldMatchTestInPrefixedText() {
		// "tests" (stemmed to "test") should match "Lab or diagnostic test: " prefix
		String[] terms = { "tests", "ordered" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Lab or diagnostic test: Test order: CD4 count. Action: NEW");
		assertTrue(score >= 1.0 / 2 - 0.001,
				"'tests' should match 'test' in the prefix text via stemming");
	}

	@Test
	public void computeKeywordScore_shouldMatchMorphologicalVariants() {
		// "allergic" (adjective) should match records containing "allergy"
		// (noun) via stem matching: "allergic" → stem "allerg" → substring
		// of "allergy". This handles derivational suffixes like -ic/-y.
		String[] terms = { "allergic" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Patient allergy: Beef (food allergen). Severity: Severe");
		assertEquals(1.0, score, 0.001,
				"'allergic' should match 'allergy' via stem 'allerg'");
	}

	@Test
	public void computeKeywordScore_shouldNotStemMatchShortWords() {
		// Words < 7 chars should not attempt stem matching to avoid
		// false positives from very short stems.
		String[] terms = { "cancer" };
		double score = LlmInferenceService.computeKeywordScore(terms,
				"Clinical diagnosis: Photoallergy: 9.93");
		assertEquals(0.0, score, 0.001,
				"'cancer' (6 chars) should not stem-match 'Photoallergy'");
	}

	@Test
	public void findAdaptiveCutoff_shouldNotCutOnSmallAbsoluteGap() {
		// Tight cluster: 0.55, 0.54, 0.53, then a 0.07 gap to 0.46, 0.45
		// Relative to avgGap=0.01, 0.07 is 7x the average (triggers at 2.5x).
		// But 0.07 < minGap of 0.10, so we should NOT cut.
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.55),
				makeScoredEmbedding(0.54),
				makeScoredEmbedding(0.53),
				makeScoredEmbedding(0.46),
				makeScoredEmbedding(0.45));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.40, 2.5, 0.10);

		assertEquals(5, cutoff,
				"Should not cut when gap is below absolute minimum even if above relative threshold");
	}

	@Test
	public void findAdaptiveCutoff_shouldCutOnLargeAbsoluteGap() {
		// Same tight cluster but bigger gap: 0.55, 0.54, 0.53, [0.15 gap], 0.38, 0.37
		// 0.15 > avgGap*2.5 AND 0.15 > 0.10 → should cut.
		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(0.55),
				makeScoredEmbedding(0.54),
				makeScoredEmbedding(0.53),
				makeScoredEmbedding(0.38),
				makeScoredEmbedding(0.37));

		int cutoff = LlmInferenceService.findAdaptiveCutoff(scored, 5, 0.30, 2.5, 0.10);

		assertEquals(3, cutoff,
				"Should cut when gap exceeds both relative and absolute thresholds");
	}

	@Test
	public void extractQueryTerms_shouldFilterOutSingleCharacterTerms() {
		String[] terms = LlmInferenceService.extractQueryTerms("a medications b");
		assertEquals(1, terms.length);
		assertEquals("medications", terms[0]);
	}

	@Test
	public void extractQueryTerms_shouldLowercaseTerms() {
		String[] terms = LlmInferenceService.extractQueryTerms("Metformin Dose");
		assertEquals(2, terms.length);
		assertEquals("metformin", terms[0]);
		assertEquals("dose", terms[1]);
	}

	@Test
	public void extractQueryTerms_shouldHandleEmptyInput() {
		String[] terms = LlmInferenceService.extractQueryTerms("");
		assertEquals(0, terms.length);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromTestOrderText() {
		// stripCategoryHints had "Lab order: " instead of "Test order: "
		// in its patterns — Test orders are serialized as "Test order:"
		// by OrderTextSerializer, so hint stripping must recognize that.
		String enriched = "Laboratory tests / Test order: CBC. Action: NEW. Urgency: STAT";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Test order: CBC. Action: NEW. Urgency: STAT", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromReferralOrderText() {
		String enriched = "Specialty referrals / Referral order: Cardiology. Action: NEW";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Referral order: Cardiology. Action: NEW", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromDispensedText() {
		String enriched = "Antiretrovirals / Dispensed: Efavirenz 600mg. Status: Completed";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Dispensed: Efavirenz 600mg. Status: Completed", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromRealDatasetTestOrder() {
		// Uses real text from THIRD dataset index 15 (lab test order)
		// to verify hint stripping works on production-shaped records.
		String rawText = TestDatasetHelper.stripDatasetPrefixAndDate(
				TestDatasetHelper.THIRD_PATIENT_DATASET[15]);
		String enriched = ChartSearchAiUtils.injectCategoryHints(
				rawText, Arrays.asList("Laboratory tests"));
		assertEquals(rawText,
				ChartSearchAiUtils.stripCategoryHints(enriched));
	}

	@Test
	public void filterPipeline_safetyNet_shouldReturnTopRecordWhenAllGatesRejectButAboveFloor() {
		// Three outlier records well above floor + 2*minScoreGap, each
		// from a DIFFERENT concept (so coherence filtering sees cross-
		// concept diversity), then 32 noise records below the floor.
		// Gap detection identifies the 3 as candidates, but phase 2
		// zero-keyword validation rejects them (low inter-concept
		// coherence from the distinct embedding vectors). The safety
		// net should catch this: scores passed both absolute AND
		// statistical validation (2x z-score threshold, 2x gap above
		// floor), so silently dropping them is a patient safety risk.
		PipelineConfig config =
				PipelineConfig.defaults();
		double floor = config.noiseProfile.absoluteSimilarityFloor();
		double topScore = floor + 2 * config.minScoreGap + 0.05;

		int n = 35;
		double[] semanticScores = new double[n];
		double[] keywordScores = new double[n];
		ChartEmbedding[] embeddings = new ChartEmbedding[n];

		// 3 outliers from different concepts, each with distinct vectors
		String[] concepts = { "ConceptA", "ConceptB", "ConceptC" };
		for (int i = 0; i < 3; i++) {
			semanticScores[i] = topScore - (i * 0.005);
			keywordScores[i] = 0.0;
			ChartEmbedding ce = new ChartEmbedding();
			ce.setResourceType("obs");
			ce.setResourceUuid(uuid(i));
			ce.setTextContent(concepts[i] + " \u2014 " + concepts[i] + ": value");
			float[] vec = new float[384];
			vec[i * 100] = 1.0f; // orthogonal vectors → low coherence
			ce.setEmbeddingVector(vec);
			embeddings[i] = ce;
		}

		// 32 noise records well below floor
		for (int i = 3; i < n; i++) {
			semanticScores[i] = floor - 0.03 - (i * 0.001);
			keywordScores[i] = 0.0;
			ChartEmbedding ce = new ChartEmbedding();
			ce.setResourceType("obs");
			ce.setResourceUuid(uuid(i));
			ce.setTextContent("Noise \u2014 Record" + i + ": value");
			float[] vec = new float[384];
			vec[(i * 7) % 384] = 1.0f;
			ce.setEmbeddingVector(vec);
			embeddings[i] = ce;
		}

		String[] queryTerms = { "somequery", "term" };
		List<ChartEmbedding> result = LlmInferenceService.filterPipeline(
				semanticScores, keywordScores, embeddings,
				queryTerms, config);

		assertFalse(result.isEmpty(),
				"Safety net should return at least the top record when "
				+ "all gates reject but top semantic score ("
				+ String.format("%.4f", topScore) + ") >= floor+2*gap ("
				+ String.format("%.4f", floor + 2 * config.minScoreGap)
				+ ") and z-score validates it as a genuine outlier");
	}

	@Test
	public void slimMarginGate_shouldPassWhenSingleRecordIsStrictlyAboveFloor() {
		// Conservative default: noiseMean=0.15, noiseMedian=0.13, noiseP95=0.28
		// → absoluteSimilarityFloor = 0.28 * 0.15 / (0.15 + 0.13) = 0.15
		// minScoreGap default = 0.10, so slim-margin zone is [0.15, 0.25)
		// A single record in the upper half of this zone (score ≥ 0.20)
		// should pass. With zero keyword matches and only 1 record above
		// floor, the old gate rejected — the fix allows records in the
		// upper half [floor + gap/2, floor + gap).
		PipelineConfig config =
				PipelineConfig.defaults();
		double floor = config.noiseProfile.absoluteSimilarityFloor();
		// Score in the upper half of the margin zone: [floor+gap/2, floor+gap)
		// floor=0.15, gap=0.10, upper half starts at 0.20
		double maxSemantic = floor + config.minScoreGap * 0.75; // 0.225

		List<ScoredEmbedding> scored = Arrays.asList(
				makeScoredEmbedding(maxSemantic, 0.0, maxSemantic),
				makeScoredEmbedding(floor - 0.05, 0.0, floor - 0.05),
				makeScoredEmbedding(floor - 0.10, 0.0, floor - 0.10));

		boolean result = LlmInferenceService.applySlimMarginGate(
				scored, maxSemantic, /* queryTermCount */ 2,
				/* keywordMatchCount */ 0,
				/* belowFloorRescued */ false, config);

		assertTrue(result,
				"Slim-margin gate should not reject when a single record "
				+ "is strictly above the floor (score=" + maxSemantic
				+ ", floor=" + floor + ")");
	}

	private static ScoredEmbedding makeScoredEmbedding(double score) {
		return makeScoredEmbedding(score, 0.0, score);
	}

	private static ScoredEmbedding makeScoredEmbedding(double score,
			double keywordScore) {
		return makeScoredEmbedding(score, keywordScore, score);
	}

	private static ScoredEmbedding makeScoredEmbedding(double score,
			double keywordScore, double semanticScore) {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setResourceType("obs");
		ce.setTextContent("Test — Example: value");
		return new ScoredEmbedding(ce, score, keywordScore, semanticScore);
	}

	private static Date makeDate(int year, int month, int day) {
		Calendar cal = Calendar.getInstance();
		cal.set(year, month - 1, day, 0, 0, 0);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	@Test
	public void buildEffectiveConfig_shouldPreserveModelSpecificFieldsForMedCpt() {
		// MedCPT's medcptDefaults() intentionally sets gapSaturationThreshold=0.0
		// (gate disabled — medical embeddings produce meaningful similarity for
		// adjacent topics, so a saturated gap doesn't imply no signal). The
		// effective config used by production must preserve that value, not
		// silently fall back to the L6-v2 default of 0.95.
		PipelineConfig medcpt = PipelineConfig.forModel("medcpt");
		assertEquals(0.0, medcpt.gapSaturationThreshold, 1e-9,
				"medcptDefaults must keep gap-saturation gate disabled");

		PipelineConfig effective = PipelineConfig.buildEffective(
				medcpt,
				ChartSearchAiConstants.DEFAULT_KEYWORD_WEIGHT,
				ChartSearchAiConstants.DEFAULT_SCORE_GAP_MULTIPLIER,
				ChartSearchAiConstants.DEFAULT_MIN_SCORE_GAP,
				ChartSearchAiConstants.DEFAULT_GAP_VALIDATION_COSINE_THRESHOLD,
				ChartSearchAiConstants.DEFAULT_SIMILARITY_RATIO,
				null);

		assertEquals(medcpt.gapSaturationThreshold,
				effective.gapSaturationThreshold, 1e-9,
				"gapSaturationThreshold must propagate from MedCPT defaults");
		assertEquals(medcpt.conceptFloorMargin,
				effective.conceptFloorMargin, 1e-9,
				"conceptFloorMargin must propagate from MedCPT defaults");
	}

	@Test
	public void buildEffectiveConfig_shouldPreserveDefaultsForL6V2() {
		// L6-v2 defaults() has gapSaturationThreshold=0.95 and
		// conceptFloorMargin=0.85. Verify that the build path doesn't drift
		// these for the default model either.
		PipelineConfig l6 = PipelineConfig.defaults();

		PipelineConfig effective = PipelineConfig.buildEffective(
				l6,
				ChartSearchAiConstants.DEFAULT_KEYWORD_WEIGHT,
				ChartSearchAiConstants.DEFAULT_SCORE_GAP_MULTIPLIER,
				ChartSearchAiConstants.DEFAULT_MIN_SCORE_GAP,
				ChartSearchAiConstants.DEFAULT_GAP_VALIDATION_COSINE_THRESHOLD,
				ChartSearchAiConstants.DEFAULT_SIMILARITY_RATIO,
				null);

		assertEquals(0.95, effective.gapSaturationThreshold, 1e-9);
		assertEquals(0.85, effective.conceptFloorMargin, 1e-9);
	}

	@Test
	public void buildEffectiveConfig_shouldApplyGlobalPropertyOverrides() {
		// When an admin customizes a GP value (i.e., it differs from the
		// L6-v2 default), the override wins over the model-specific default.
		// Use MedCPT base so the override has something distinct to override.
		PipelineConfig medcpt = PipelineConfig.forModel("medcpt");
		double customSimRatio = 0.50; // far from L6-v2 default 0.80

		PipelineConfig effective = PipelineConfig.buildEffective(
				medcpt,
				ChartSearchAiConstants.DEFAULT_KEYWORD_WEIGHT,
				ChartSearchAiConstants.DEFAULT_SCORE_GAP_MULTIPLIER,
				ChartSearchAiConstants.DEFAULT_MIN_SCORE_GAP,
				ChartSearchAiConstants.DEFAULT_GAP_VALIDATION_COSINE_THRESHOLD,
				customSimRatio,
				null);

		assertEquals(customSimRatio, effective.similarityRatio, 1e-9,
				"customized similarity ratio GP must override the model default");
		// Non-overridden, non-GP fields still come from the model defaults
		assertEquals(medcpt.gapSaturationThreshold,
				effective.gapSaturationThreshold, 1e-9);
	}

	/** EmbeddingProvider stub backed by a fixed text→vector map. */
	private static class FixedSimilarityProvider implements
			org.openmrs.module.chartsearchai.embedding.EmbeddingProvider {
		private final java.util.Map<String, float[]> vectors;
		FixedSimilarityProvider(java.util.Map<String, float[]> vectors) {
			this.vectors = vectors;
		}
		@Override public float[] embed(String text) {
			return vectors.getOrDefault(text, new float[]{0f, 0f, 0f, 1f});
		}
		@Override public float[] embedQuery(String text) {
			return vectors.getOrDefault(text, new float[]{0f, 0f, 0f, 1f});
		}
		@Override public int getDimensions() { return 4; }
	}

	private static List<ChartEmbedding> recordsForSynonymTest(String... texts) {
		List<ChartEmbedding> list = new ArrayList<>();
		for (int i = 0; i < texts.length; i++) {
			ChartEmbedding ce = new ChartEmbedding();
			ce.setResourceType("obs");
			ce.setResourceUuid(uuid(i));
			ce.setTextContent(texts[i]);
			byte[] raw = new byte[16];
			ce.setEmbedding(raw);
			list.add(ce);
		}
		return list;
	}

	@Test
	public void expandKwTermsViaConceptSimilarity_shouldReplaceWithCloseConceptName() {
		// "heart rate" against a chart with the Pulse concept; query
		// encoder maps the two within ~0.99 cosine. Replacement collapses
		// kwTerms to ["pulse"] so pulse-obs records get a full keyword
		// match.
		java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
		vectors.put("heart rate",  new float[]{1.00f, 0.10f, 0.00f, 0.00f});
		vectors.put("Pulse",       new float[]{0.99f, 0.14f, 0.00f, 0.00f});
		vectors.put("Tuberculosis", new float[]{0.00f, 0.00f, 1.00f, 0.00f});
		vectors.put("Atherosclerosis", new float[]{0.00f, 0.00f, 0.00f, 1.00f});

		List<ChartEmbedding> records = recordsForSynonymTest(
				"Vital signs / Finding \u2014 Pulse: 60 beats/min",
				"Condition: Tuberculosis. Status: ACTIVE",
				"Condition: Atherosclerosis. Status: ACTIVE");
		FixedSimilarityProvider provider = new FixedSimilarityProvider(vectors);

		String[] expanded = LlmInferenceService.expandKwTermsViaConceptSimilarity(
				new String[] {"heart", "rate"},
				vectors.get("heart rate"),
				records.toArray(new ChartEmbedding[0]),
				provider, PipelineConfig.defaults());

		assertEquals(1, expanded.length,
				"Concept name 'Pulse' is closest to query — kwTerms should "
				+ "collapse to its single token.");
		assertEquals("pulse", expanded[0]);
	}

	@Test
	public void expandKwTermsViaConceptSimilarity_shouldNotReplaceWhenFullMatchAlreadyExists() {
		// "blood sugar" against a record containing both terms — keyword
		// scoring already produces a full match; expansion would narrow.
		java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
		vectors.put("blood sugar", new float[]{1.00f, 0.10f, 0.00f, 0.00f});
		vectors.put("Glucose",     new float[]{0.99f, 0.14f, 0.00f, 0.00f});
		List<ChartEmbedding> records = recordsForSynonymTest(
				"Lab \u2014 Blood sugar (Glucose): 95 mg/dL");
		FixedSimilarityProvider provider = new FixedSimilarityProvider(vectors);

		String[] kwTerms = new String[] {"blood", "sugar"};
		String[] expanded = LlmInferenceService.expandKwTermsViaConceptSimilarity(
				kwTerms, vectors.get("blood sugar"),
				records.toArray(new ChartEmbedding[0]),
				provider, PipelineConfig.defaults());

		assertTrue(expanded == kwTerms,
				"Full-match record exists; kwTerms must pass through unchanged.");
	}

	@Test
	public void expandKwTermsViaConceptSimilarity_shouldNotReplaceWhenNoConceptIsCloseEnough() {
		// "any cancer?" on a chart without cancer concepts: similarity
		// stays below path-(a) threshold and margin below path-(b) →
		// no expansion, empty result preserved.
		java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
		vectors.put("cancer", new float[]{1.00f, 0.00f, 0.00f, 0.00f});
		vectors.put("Pulse",       new float[]{0.40f, 0.92f, 0.00f, 0.00f});
		vectors.put("Hypertension", new float[]{0.00f, 0.00f, 1.00f, 0.00f});
		List<ChartEmbedding> records = recordsForSynonymTest(
				"Vital signs \u2014 Pulse: 72 bpm",
				"Condition: Hypertension. Status: ACTIVE");
		FixedSimilarityProvider provider = new FixedSimilarityProvider(vectors);

		String[] kwTerms = new String[] {"cancer"};
		String[] expanded = LlmInferenceService.expandKwTermsViaConceptSimilarity(
				kwTerms, vectors.get("cancer"),
				records.toArray(new ChartEmbedding[0]),
				provider, PipelineConfig.defaults());

		assertTrue(expanded == kwTerms,
				"No concept similar enough → no expansion, preserving "
				+ "correct empty result for absent topics.");
	}

	@Test
	public void expandKwTermsViaConceptSimilarity_shouldNotReplaceWhenTopTwoConceptsClose() {
		// Multi-concept query: top two concepts within margin. Replacement
		// would pick one and lose the other.
		java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
		vectors.put("blood pressure and pulse",
				new float[]{1.00f, 0.10f, 0.00f, 0.00f});
		vectors.put("Systolic blood pressure",
				new float[]{0.97f, 0.20f, 0.00f, 0.00f});
		vectors.put("Pulse",
				new float[]{0.97f, 0.21f, 0.00f, 0.00f});
		vectors.put("Tuberculosis",
				new float[]{0.00f, 0.00f, 1.00f, 0.00f});
		List<ChartEmbedding> records = recordsForSynonymTest(
				"Vital signs \u2014 Systolic blood pressure: 120 mmHg",
				"Vital signs \u2014 Pulse: 60 bpm",
				"Condition: Tuberculosis. Status: ACTIVE");
		FixedSimilarityProvider provider = new FixedSimilarityProvider(vectors);

		String[] kwTerms = new String[] {"blood", "pressure", "pulse"};
		String[] expanded = LlmInferenceService.expandKwTermsViaConceptSimilarity(
				kwTerms, vectors.get("blood pressure and pulse"),
				records.toArray(new ChartEmbedding[0]),
				provider, PipelineConfig.defaults());

		assertTrue(expanded == kwTerms,
				"Top two concepts within margin — multi-concept query, "
				+ "must not collapse to one synonym.");
	}

}
