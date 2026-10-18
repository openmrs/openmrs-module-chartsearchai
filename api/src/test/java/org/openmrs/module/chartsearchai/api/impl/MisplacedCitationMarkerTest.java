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
 * A marker the model attached to one item of a list while the record it cites is what ANOTHER item of the answer
 * names, under that item's own marker too, is removed from the item it does not belong to (ADR Decision 186).
 *
 * <p>Measured on the 3.7.1 standalone: Sarah Taylor's <em>"any allergies?"</em> was answered <em>"... Personal history
 * of allergy to shellfish [229] [232], and Allergy to sufentanil [231] [232]."</em> — [232] is the shellfish diagnosis,
 * so the sufentanil item cited a record about shellfish. The chart here is {@link TestDatasetHelper#FULL_PATIENT_DATASET}
 * through the real serializer: [8] is the Tuberculosis condition, [40] and [41] the HIV Disease diagnosis and
 * assessment.
 */
public class MisplacedCitationMarkerTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "What conditions does she have?";

	@Test
	public void aMarkerAnotherItemNamesUnderItsOwnMarkerIsRemovedFromTheItemItDoesNotBelongTo() {
		ChartAnswer answer = serviceAnswering(
			"Her conditions: Tuberculosis [8] [40], and HIV Disease [40] [41].").search(patient(), QUESTION);

		assertEquals("Her conditions: Tuberculosis [8], and HIV Disease [40] [41].", answer.getAnswer());
		assertEquals(listOf(8, 40, 41), referenceIndexes(answer), "[40] is still cited, by the item that names it");
	}

	@Test
	public void theStreamingPathPublishesTheSameAnswerOnBothItsAnswers() {
		List<ChartAnswer> ungrounded = new ArrayList<ChartAnswer>();
		ChartAnswer produced = serviceAnswering("Her conditions: Tuberculosis [8] [40], and HIV Disease [40] [41].")
				.searchStreaming(patient(), QUESTION, token -> { }, reasoning -> { }, citations -> { },
					a -> ungrounded.add(a));

		assertEquals("Her conditions: Tuberculosis [8], and HIV Disease [40] [41].", produced.getAnswer());
		assertEquals(1, ungrounded.size(), "precondition: the early answer was handed over");
		assertEquals(produced.getAnswer(), ungrounded.get(0).getAnswer(), "the answer a streaming user reads first");
	}

	@Test
	public void aMarkerAloneOnItsItemIsKept() {
		// Removing it would leave the item citing nothing; the grounding verdict is what speaks to it.
		String text = "Her conditions: Tuberculosis [40], and HIV Disease [40] [41].";
		assertEquals(text, serviceAnswering(text).search(patient(), QUESTION).getAnswer());
	}

	@Test
	public void aMarkerWhoseRecordItsOwnItemAlsoNamesIsKept() {
		// "HIV" is said by both items, and only the first says "Disease": the first names [40] at least as well.
		String text = "Her conditions: HIV Disease with Tuberculosis [8] [40], and HIV [40] [41].";
		assertEquals(text, serviceAnswering(text).search(patient(), QUESTION).getAnswer());
	}

	@Test
	public void aMarkerItsOwnItemNamesByAWordTheOtherItemDoesNotSayIsKept() {
		// [40] is the CONFIRMED HIV Disease diagnosis: "confirmed" ties it to the first item as "Disease" ties it to
		// the second, so the first cannot be said to be the item it does not belong to. The second carries [40]
		// alone, so the rule never weighs removing it there.
		String text = "Her conditions: Tuberculosis with confirmed HIV [8] [40], and HIV Disease [40].";
		assertEquals(text, serviceAnswering(text).search(patient(), QUESTION).getAnswer());
	}

	@Test
	public void anItemIsNeverLeftCitingNothing() {
		// Each of the first item's markers is named better by another item ("active" for [8], "Disease" for [40]),
		// and each is the other's sibling, so the rule alone would take both off and leave the item uncited.
		String text = "Her conditions: Tuberculosis HIV [8] [40], active Tuberculosis [8], and HIV Disease [40].";
		assertEquals(text, serviceAnswering(text).search(patient(), QUESTION).getAnswer());
	}

	@Test
	public void aMarkerCitedOnceIsKept() {
		// Nothing else in the answer claims [40], so nothing says it belongs elsewhere.
		String text = "Her conditions: Tuberculosis [8] [40], and gastroenteritis [22].";
		assertEquals(text, serviceAnswering(text).search(patient(), QUESTION).getAnswer());
	}

	@Test
	public void aRecordThatCannotBeReadLeavesTheAnswerAsTheModelWroteIt() {
		// The rewrite must never cost the clinician the answer: a record whose text throws when read is reached here,
		// [2] being in two runs, and the answer comes back unchanged.
		List<RecordMapping> unreadable = new ArrayList<RecordMapping>();
		for (int index = 1; index <= 2; index++) {
			unreadable.add(new RecordMapping(index, "obs", "00000000-0000-0000-0000-00000000000" + index, null) {

				@Override
				public String getText() {
					throw new IllegalStateException("record text unavailable");
				}
			});
		}
		String text = "Her conditions: Tuberculosis [1] [2], and HIV Disease [2].";
		LlmInferenceService service = serviceAnswering(text);
		service.setChartBuildingStrategy(new StubStrategy(new PatientChart("[1] obs\n[2] obs", unreadable,
				Collections.<Integer> emptyList())));
		assertEquals(text, service.search(patient(), QUESTION).getAnswer());
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
			TestDatasetHelper.toSerializedRecords(TestDatasetHelper.FULL_PATIENT_DATASET), Collections.<String> emptySet());
		assertTrue(textOf(chart, 8).contains("Condition: Tuberculosis"), "precondition: " + textOf(chart, 8));
		assertTrue(textOf(chart, 40).contains("Diagnosis: HIV Disease"), "precondition: " + textOf(chart, 40));
		assertTrue(textOf(chart, 41).contains("HIV Disease"), "precondition: " + textOf(chart, 41));
		return chart;
	}

	private static String textOf(PatientChart chart, int index) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (mapping.getIndex() == index) {
				return mapping.getText();
			}
		}
		return "no record [" + index + "]";
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** The seam the sibling tests use: the chart is the real one, only the model and the safety layer stand in. */
	private static LlmInferenceService serviceAnswering(String answer) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(chart()));
		created.setLlmProvider(new StubProvider(answer));
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

		private final String answer;

		private StubProvider(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings,
				LlmEngine.ReferenceRecords referenceRecords,
				List<PatientChartSerializer.AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<PatientChartSerializer.AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}
	}
}
