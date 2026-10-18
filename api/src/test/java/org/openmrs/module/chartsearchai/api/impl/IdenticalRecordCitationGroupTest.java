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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A compact citation group the model's citations array does not corroborate is split where every number in it is a
 * record of the chart and those records are ONE record apart from their dates (ADR Decision 211).
 *
 * <p>Measured on the public demo (E2B, 2026-10-14): Kamwara's <em>"What medications is she currently taking?"</em>
 * was answered <em>"1. Stavudine (Order status: in force) [2, 37, 67]"</em> — her three identical Stavudine orders — with
 * an array that left them out, so the bracket stayed a "value", Stavudine was published citing nothing and was
 * screened as a drug the answer PROPOSED. The chart here is {@link TestDatasetHelper#FULL_PATIENT_DATASET} through
 * the real serializer, each record keeping the date its dataset string carries, and the model's raw output through
 * the real {@link LlmAnswerExtractor}: [13] (2025-10-30), [135] and [136] (both 2021-07-20) are one
 * "Primary Diagnosis: Tuberculosis" observation, recorded more than once.
 */
public class IdenticalRecordCitationGroupTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "What are her diagnoses?";

	@Test
	public void aGroupOfOneRecordOnSeveralDatesIsSplitThoughTheArrayLeavesItOut() {
		ChartAnswer answer = serviceAnswering(
			"{\"answer\": \"Her diagnoses: Tuberculosis [13, 135, 136], and anemia [30].\", \"citations\": [30]}")
				.search(patient(), QUESTION);

		assertEquals("Her diagnoses: Tuberculosis [13], [135], [136], and anemia [30].", answer.getAnswer());
		assertEquals(listOf(13, 30, 135, 136), referenceIndexes(answer));
	}

	@Test
	public void aSlashGroupOfOneRecordIsSplitToo() {
		ChartAnswer answer = serviceAnswering(
			"{\"answer\": \"Her diagnoses: Tuberculosis [13/135/136].\", \"citations\": []}").search(patient(), QUESTION);

		assertEquals("Her diagnoses: Tuberculosis [13], [135], [136].", answer.getAnswer());
		assertEquals(listOf(13, 135, 136), referenceIndexes(answer));
	}

	@Test
	public void aGroupOfDifferentRecordsIsLeftAsTheModelWroteIt() {
		// [24] is a diastolic pressure and [25] a pulse: nothing but the model's array could say the group cites them.
		String text = "Her vitals were recorded [24, 25], and she has anemia [30].";
		ChartAnswer answer = serviceAnswering("{\"answer\": \"" + text + "\", \"citations\": [30]}")
				.search(patient(), QUESTION);

		assertEquals(text, answer.getAnswer());
		assertEquals(listOf(30), referenceIndexes(answer));
	}

	@Test
	public void aBracketedValueWhoseNumbersAreRecordIndexesIsNeverSplit() {
		// The refused alternative's own example: on a chart this size, 80 and 120 are both record indexes. They are two
		// different records, so the pair stays the value it is.
		PatientChart chart = chart();
		assertNotEquals(undated(chart, 80), undated(chart, 120), "precondition: [80] and [120] are different records");
		String text = "Her blood pressure was [120, 80], and she has anemia [30].";
		ChartAnswer answer = serviceAnswering("{\"answer\": \"" + text + "\", \"citations\": [30]}")
				.search(patient(), QUESTION);

		assertEquals(text, answer.getAnswer());
		assertEquals(listOf(30), referenceIndexes(answer));
	}

	@Test
	public void aGroupNamingANumberNoRecordHasIsLeftAsTheModelWroteIt() {
		String text = "Her diagnoses: Tuberculosis [13, 999], and anemia [30].";
		ChartAnswer answer = serviceAnswering("{\"answer\": \"" + text + "\", \"citations\": [30]}")
				.search(patient(), QUESTION);

		assertEquals(text, answer.getAnswer());
	}

	@Test
	public void theStreamingPathPublishesTheSplitAnswerOnBothItsAnswers() {
		List<ChartAnswer> ungrounded = new ArrayList<ChartAnswer>();
		ChartAnswer produced = serviceAnswering(
			"{\"answer\": \"Her diagnoses: Tuberculosis [13, 135, 136], and anemia [30].\", \"citations\": [30]}")
				.searchStreaming(patient(), QUESTION, token -> { }, reasoning -> { }, citations -> { },
					a -> ungrounded.add(a));

		assertEquals("Her diagnoses: Tuberculosis [13], [135], [136], and anemia [30].", produced.getAnswer());
		assertEquals(listOf(13, 30, 135, 136), referenceIndexes(produced));
		assertEquals(1, ungrounded.size(), "precondition: the early answer was handed over");
		assertEquals(produced.getAnswer(), ungrounded.get(0).getAnswer(), "the answer a streaming user reads first");
	}

	private static List<Integer> listOf(Integer... values) {
		List<Integer> list = new ArrayList<Integer>();
		Collections.addAll(list, values);
		return list;
	}

	private static List<Integer> referenceIndexes(ChartAnswer answer) {
		List<Integer> indexes = new ArrayList<Integer>();
		for (RecordReference reference : answer.getReferences()) {
			indexes.add(reference.getIndex());
		}
		Collections.sort(indexes);
		return indexes;
	}

	private static PatientChart chart() {
		PatientChart chart = new PatientChartSerializer().serialize(null,
			TestDatasetHelper.toDatedSerializedRecords(TestDatasetHelper.FULL_PATIENT_DATASET),
			Collections.<String> emptySet());
		for (int index : new int[] { 13, 135, 136 }) {
			assertTrue(textOf(chart, index).contains("Primary Diagnosis: Tuberculosis"),
				"precondition: " + textOf(chart, index));
		}
		// Dated, and not on one date: the texts differ by their date prefix alone.
		assertTrue(textOf(chart, 13).startsWith("("), "precondition: " + textOf(chart, 13));
		assertNotEquals(textOf(chart, 13), textOf(chart, 135), "precondition: [13] and [135] carry different dates");
		assertTrue(textOf(chart, 24).contains("Diastolic Blood Pressure"), "precondition: " + textOf(chart, 24));
		assertTrue(textOf(chart, 25).contains("Pulse"), "precondition: " + textOf(chart, 25));
		assertTrue(textOf(chart, 30).contains("Anemia"), "precondition: " + textOf(chart, 30));
		return chart;
	}

	private static RecordMapping mapping(PatientChart chart, int index) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (mapping.getIndex() == index) {
				return mapping;
			}
		}
		throw new AssertionError("no record [" + index + "]");
	}

	private static String textOf(PatientChart chart, int index) {
		return mapping(chart, index).getText();
	}

	private static String undated(PatientChart chart, int index) {
		return mapping(chart, index).getUndatedText();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** The chart is the real one and so is the extraction of the model's output; only the model and the safety layer
	 *  stand in. */
	private static LlmInferenceService serviceAnswering(String rawModelOutput) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(chart()));
		created.setLlmProvider(new StubProvider(rawModelOutput));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question, ChartReadStatus readStatus) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public DrugSafetyValidator.SafetyCheckResult validateWithStatus(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return new DrugSafetyValidator.SafetyCheckResult(DrugSafetyValidator.STATUS_CHECKED, Collections.<SafetyWarning>emptyList());
			}
		});
		return created;
	}

	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		private final String rawModelOutput;

		private StubProvider(String rawModelOutput) {
			this.rawModelOutput = rawModelOutput;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings,
				LlmEngine.ReferenceRecords referenceRecords,
				List<PatientChartSerializer.AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return LlmAnswerExtractor.extractResponse(rawModelOutput);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<PatientChartSerializer.AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return LlmAnswerExtractor.extractResponse(rawModelOutput);
		}
	}
}
