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
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

public class LlmInferenceServiceTest {

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
	public void extractCitedReferences_shouldIncludeInlineMarkersMissingFromCitationsArray() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "program", uuid(1), null),
				new RecordMapping(8, "condition", uuid(8), null),
				new RecordMapping(9, "obs", uuid(9), null));

		// The LLM wrote [1] and [8] inline in the prose but listed only [9] in
		// its structured citations array. Every inline-cited record that exists
		// in the chart must still resolve to a clickable reference, otherwise the
		// answer text points at a citation the UI cannot render.
		String answer = "Diabetes program [1]. Active Tuberculosis [8]. CD4 988.0 [9].";

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				answer, Arrays.asList(9), mappings);

		List<Integer> indices = new ArrayList<Integer>();
		for (RecordReference ref : result) {
			indices.add(ref.getIndex());
		}
		Collections.sort(indices);
		assertEquals(Arrays.asList(1, 8, 9), indices);
	}

	@Test
	public void extractCitedReferences_shouldNotAddInlineMarkersWithNoMapping() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(9, "obs", uuid(9), null));

		// [8] is cited inline but there is no record 8 in the chart. A dangling
		// inline marker must not fabricate a reference.
		String answer = "Tuberculosis [8]. CD4 988.0 [9].";

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				answer, Arrays.asList(9), mappings);

		assertEquals(1, result.size());
		assertEquals(9, result.get(0).getIndex());
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
	public void searchStreaming_emitsCitationsOnTheCitationsChannelDuringTheCall() {
		// The async-grounding contract: the answer's citations are pushed to the citations channel
		// DURING the call (so the UI can render clickable citations immediately) — not only via the
		// returned answer — and they carry no grounding verdict yet (grounded == null). Exercises the
		// real production orchestration (buildChart -> generate -> extract citations -> ground) with
		// the chart/LLM collaborators stubbed; grounding is off (no context) so it is a clean no-op.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(11), null),
				new RecordMapping(2, "order", uuid(22), null));
		PatientChart chart = new PatientChart("records", mappings);

		LlmInferenceService service = new LlmInferenceService();
		service.setDrugReferenceInjector(new org.openmrs.module.chartsearchai.reference.DrugReferenceInjector() {

			@Override
			public org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart inject(
					org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart,
					org.openmrs.Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new org.openmrs.module.chartsearchai.reference.DrugSafetyValidator() {

			@Override
			public java.util.List<org.openmrs.module.chartsearchai.reference.SafetyWarning> validate(
					String answer, String question, org.openmrs.Patient patient) {
				return java.util.Collections.emptyList();
			}
		});
		service.setChartBuildingStrategy(new ChartBuildingStrategy() {

			@Override
			PatientChart buildChart(Patient patient, String question) {
				return chart;
			}
		});
		service.setLlmProvider(new LlmProvider() {

			// Production now calls the scope-aware 6-arg overload (it passes the patient UUID so the
			// local engine can restore/persist the patient's prefilled chart KV). Override that one;
			// the scope is irrelevant to this citations-channel assertion, so it is ignored.
			@Override
			public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
					String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
					String cacheScope) {
				tokenConsumer.accept("Finding A [1] and finding B [2].");
				return new LlmResponse("Finding A [1] and finding B [2].", Arrays.asList(1, 2));
			}
		});

		List<List<RecordReference>> captured = new ArrayList<List<RecordReference>>();
		ChartAnswer answer = service.searchStreaming(null, "any findings?",
				token -> { }, reasoning -> { }, captured::add);

		assertEquals(1, captured.size(), "citations channel must fire exactly once per answer");
		List<RecordReference> early = captured.get(0);
		assertEquals(2, early.size(), "both cited records must reach the citations channel");
		assertEquals(uuid(11), early.get(0).getResourceUuid());
		assertEquals(uuid(22), early.get(1).getResourceUuid());
		// Emitted before grounding -> no verdict yet; clients must render these as unverified.
		assertNull(early.get(0).getGrounded());
		assertNull(early.get(1).getGrounded());
		// The same citations come back on the returned answer.
		assertEquals(2, answer.getReferences().size());
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
		// in its patterns — Test orders are serialized with a "Test order:"
		// prefix, so hint stripping must recognize that.
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

	private static Date makeDate(int year, int month, int day) {
		Calendar cal = Calendar.getInstance();
		cal.set(year, month - 1, day, 0, 0, 0);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}
}
