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
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * An answer that treats a drug the chart holds only as an ended order as current is completed by the
 * module with a sentence saying the chart records that order as no longer in force, and when it ended
 * (issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/472">#472</a>, ADR
 * Decision 110).
 *
 * <p><b>The defect.</b> Decision 110's live A/B recorded the shipped prompt's answer to <em>"Her current
 * medications are lamivudine, nevirapine and rifampicin. Any interactions?"</em> as <em>"Yes, there are
 * interactions recorded for these medications."</em> — the refusal gone, and the clinician's false
 * premise that rifampicin is current still confirmed. The chip said {@code aboutAnEndedOrder: true};
 * nothing a clinician reads said so. The module holds the fact structurally, on the chip, so it states
 * it the way ADR Decision 100 states an order the prose left unnamed: appended, never replacing, with
 * no model asked.
 *
 * <p><b>Everything but the model is real</b>: patient 7 of the standard dataset (active order 111,
 * ASPIRIN) read through the real {@code OrderService}, the real injector and validator over the DDInter
 * excerpt the tests load, and the real {@link LlmInferenceService#search}/{@code searchStreaming}. The
 * chart the stub strategy returns carries an ended Ibuprofen order in querystore's REAL rendered text.
 * The model is a recorder answering R1's recorded words.
 */
public class LlmInferenceServiceEndedOrderStatementContextTest extends BaseModuleContextSensitiveTest {

	/** R1's shape, on this dataset's drugs: the clinician lists the ended drug as current. */
	private static final String QUESTION = "Her current medications are aspirin and ibuprofen. Any interactions?";

	/**
	 * Decision 110's recorded arm-C lead to R1, verbatim, with a marker — then naming the finding's
	 * partner by the chip's own name for it, so ADR Decision 100's completion has nothing to add and
	 * the only sentence the module can append is this issue's.
	 */
	private static final String MODEL_ANSWER = "Yes, there are interactions recorded for these medications: "
			+ "ibuprofen interacts with Acetylsalicylic acid (aspirin) [1].";

	/** 2026-01-01 UTC. */
	private static final Date STOPPED = new Date(1767225600000L);

	private static final String STATEMENT = " The chart records Ibuprofen only as an order no longer in force "
			+ "(ended 2026-01-01), not as a current medication.";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	private static RecordMapping endedIbuprofen() {
		return DrugReferenceTestSupport.drugOrderRecord(2, "Ibuprofen 400mg", Boolean.FALSE, STOPPED);
	}

	private static TestableService serviceWith(String modelAnswer, RecordMapping... chartRecords) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(chartRecords));
		service.setLlmProvider(new AnsweringProvider(modelAnswer));
		service.setDrugReferenceInjector(
			DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups()));
		service.setDrugSafetyValidator(
			DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterServiceWithGroups()));
		return service;
	}

	private static void assertAnEndedChip(ChartAnswer answer) {
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.isAboutAnEndedOrder()) {
				return;
			}
		}
		throw new AssertionError("precondition: a chip is about the ended ibuprofen order, chips were: "
				+ answer.getSafetyWarnings());
	}

	@Test
	public void search_anAnswerTreatingAnEndedOrderAsCurrentIsCompletedWithWhatTheChartRecords() {
		ChartAnswer answer = serviceWith(MODEL_ANSWER, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(MODEL_ANSWER + STATEMENT, answer.getAnswer(),
				"the module appends what the chart records, and the verdict the model wrote is untouched");
	}

	@Test
	public void searchStreaming_theFinalAnswerIsCompletedTheSameWay() {
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceWith(MODEL_ANSWER, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).searchStreaming(patient, QUESTION, token -> { }, reasoning -> { },
				citations -> { }, early::add);

		assertAnEndedChip(answer);
		assertEquals(MODEL_ANSWER + STATEMENT, answer.getAnswer(), "the streaming path completes it too");
		assertEquals(1, early.size(), "precondition: the early done fired");
	}

	@Test
	public void anAnswerThatAlreadySaysTheOrderIsNoLongerInForceIsReturnedByteForByte() {
		String stated = "Ibuprofen's order is no longer in force [2]. It interacts with her Acetylsalicylic "
				+ "acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the answer already states it, so nothing is appended");
	}

	/**
	 * Review round 2 of PR #478: the chip's drug is {@code DrugReference.displayLabel()}, which appends a
	 * diverging generic — {@code "Acetylsalicylic acid (aspirin)"} here, {@code "Rifampicin (rifampin)"}
	 * on the ticket's own reproduction — and no model writes that label. Asked as a SUBSTRING of the
	 * sentence, an answer using exactly the prompt's words about "aspirin" read as unstated, and the
	 * module said it a second time. Patient 6 holds no active order, so both drugs the question names
	 * can be ones the chart records only as ended.
	 */
	@Test
	public void anAnswerNamingTheEndedDrugByANameItsChipLabelOnlyAppendsIsReturnedByteForByte() {
		String stated = "Aspirin's order is no longer in force, not as a current medication [2]. "
				+ "Ibuprofen's order is no longer in force, not as a current medication [3].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			DrugReferenceTestSupport.drugOrderRecord(2, "Aspirin 81mg", Boolean.FALSE, STOPPED),
			DrugReferenceTestSupport.drugOrderRecord(3, "Ibuprofen 400mg", Boolean.FALSE, STOPPED))
				.search(Context.getPatientService().getPatient(6), QUESTION);

		boolean labelledByTheGeneric = false;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			labelledByTheGeneric |= chip.isAboutAnEndedOrder()
					&& "Acetylsalicylic acid (aspirin)".equals(chip.getDrug());
		}
		assertTrue(labelledByTheGeneric, "precondition: an ended-order chip is labelled by the name and "
				+ "its appended generic, chips were: " + answer.getSafetyWarnings());
		assertEquals(stated, answer.getAnswer(), "the answer already states it of both, so nothing is appended");
	}

	/**
	 * Issue #482 item 1: the answer's "no longer in force" is about ANOTHER drug, named in the phrase's
	 * own clause. Asked as co-occurrence in one sentence, the ended ibuprofen read as stated and nothing
	 * was appended about it.
	 */
	@Test
	public void aPhraseWhoseClauseNamesAnotherDrugDoesNotStateThisOnesEnd() {
		String answer = "Yes, there are interactions recorded for these medications: ibuprofen interacts "
				+ "with Acetylsalicylic acid (aspirin) [1]; her metformin order is no longer in force.";
		ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(completed);
		assertEquals(answer + STATEMENT, completed.getAnswer(),
				"the phrase is about metformin, so the answer never said ibuprofen's order ended");
	}

	/** The same, where a comma and a conjunction join the clause about the other drug. */
	@Test
	public void aPhraseInACommaJoinedClauseNamingAnotherDrugDoesNotStateThisOnesEnd() {
		String answer = "Yes, there are interactions recorded for these medications: ibuprofen interacts "
				+ "with Acetylsalicylic acid (aspirin) [1], and her metformin order is no longer in force.";
		ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(completed);
		assertEquals(answer + STATEMENT, completed.getAnswer(),
				"the phrase is about metformin, so the answer never said ibuprofen's order ended");
	}

	/** After a colon or a dash, as after a semicolon, the drug named nearest before the phrase is metformin. */
	@Test
	public void aPhraseAfterAColonOrADashNamingAnotherDrugDoesNotStateThisOnesEnd() {
		for (String boundary : new String[] { ":", " —", " –", " -", " --" }) {
			String answer = "Yes, there are interactions recorded for these medications: ibuprofen interacts "
					+ "with Acetylsalicylic acid (aspirin) [1]" + boundary + " her metformin order is no longer "
					+ "in force.";
			ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				endedIbuprofen()).search(patient, QUESTION);

			assertAnEndedChip(completed);
			assertEquals(answer + STATEMENT, completed.getAnswer(),
					"after '" + boundary + "' the phrase is about metformin");
		}
	}

	/**
	 * Punctuation between the other drug's name and the phrase — an appositive, a dose range, a thousands
	 * comma, a parenthesis — names no drug. The phrase is about the nearest drug named before it,
	 * metformin, and not about the drug the sentence named first.
	 */
	@Test
	public void aBoundaryInsideTheOtherDrugsClauseStillLeavesThePhraseAboutThatDrug() {
		for (String tail : new String[] { "; her metformin order, started in 2024, is no longer in force.",
				"; her metformin 500 - 1000 mg order is no longer in force.",
				"; her metformin 1,000 mg order is no longer in force.",
				"; her metformin order (500 mg, twice daily) is no longer in force." }) {
			String answer = "Yes, there are interactions recorded for these medications: ibuprofen interacts "
					+ "with Acetylsalicylic acid (aspirin) [1]" + tail;
			ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				endedIbuprofen()).search(patient, QUESTION);

			assertAnEndedChip(completed);
			assertEquals(answer + STATEMENT, completed.getAnswer(), "the phrase is about metformin: " + tail);
		}
	}

	/**
	 * PR #487 review round 1: the other drug named between this one and the phrase, joined by no comma,
	 * semicolon, colon or dash — a plain "and", a pronoun, a parenthesis, a Unicode hyphen or minus sign
	 * written as a dash. Read clause by clause, the phrase's clause ran back to ibuprofen and the sentence
	 * read as stated. The phrase is about the drug named nearest before it, metformin.
	 */
	@Test
	public void anotherDrugNamedNearerThePhraseWithNoClauseBoundaryDoesNotStateThisOnesEnd() {
		for (String tail : new String[] { " and her metformin order is no longer in force.",
				" and with metformin, whose order is no longer in force.",
				" (her metformin order is no longer in force).",
				" ‐ her metformin order is no longer in force.",
				" − her metformin order is no longer in force." }) {
			String answer = "Yes, there are interactions recorded for these medications: ibuprofen interacts "
					+ "with Acetylsalicylic acid (aspirin) [1]" + tail;
			ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				endedIbuprofen()).search(patient, QUESTION);

			assertAnEndedChip(completed);
			assertEquals(answer + STATEMENT, completed.getAnswer(), "the phrase is about metformin: " + tail);
		}
	}

	/**
	 * Names joined into one combination name — by a hyphen, a slash or a plus sign — are one subject: the
	 * phrase after them is about each, so the nearest being the other drug does not take it from this one.
	 */
	@Test
	public void aPhraseAfterACombinationNameIncludingThisDrugStatesIt() {
		for (String joined : new String[] { "ibuprofen-metformin", "ibuprofen‐metformin",
				"ibuprofen/metformin", "ibuprofen / metformin", "ibuprofen + metformin",
				"ibuprofen/warfarin/metformin", "ibuprofen / warfarin / metformin" }) {
			String stated = "Her " + joined + " order is no longer in force [2]. It interacts with her "
					+ "Acetylsalicylic acid (aspirin) [1].";
			ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				endedIbuprofen()).search(patient, QUESTION);

			assertAnEndedChip(answer);
			assertEquals(stated, answer.getAnswer(), "the combination names ibuprofen: " + joined);
		}
	}

	/** Where no drug is named before the phrase, this drug named nearest after it still states it. */
	@Test
	public void aDrugNamedOnlyAfterThePhraseIsStillReadAsStated() {
		String stated = "The order no longer in force is her ibuprofen [2]. It interacts with her "
				+ "Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the sentence says it of ibuprofen, so nothing is appended");
	}

	/**
	 * Issue #489 item 1: where no drug is named before the phrase, it is about the drug named nearest
	 * AFTER it. Read by the sentence rule instead, the ibuprofen named later in the sentence made the
	 * answer read as having stated ibuprofen's end, and nothing was appended about it.
	 */
	@Test
	public void aPhraseAheadOfAnotherDrugDoesNotStateThisOnesEnd() {
		String answer = "The order no longer in force is her metformin; ibuprofen interacts with her "
				+ "Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer completed = serviceWith(answer, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(completed);
		assertEquals(answer + STATEMENT, completed.getAnswer(),
				"the phrase is about metformin, the drug named nearest after it");
	}

	/**
	 * Issue #489 item 1's tie: after the phrase, a name of this drug and another drug's name START at one
	 * position — the excerpt files the kit's name on both the Omeprazole and the Clarithromycin rows — and
	 * the tie goes to this drug, as it does before the phrase.
	 */
	@Test
	public void aNameThisDrugSharesWithAnotherAfterThePhraseStatesIt() {
		String stated = "The order no longer in force is her Clarithromycin / Esomeprazole / Levofloxacin "
				+ "combination kit [2]. Omeprazole interacts with her Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			DrugReferenceTestSupport.drugOrderRecord(2, "Omeprazole 20mg", Boolean.FALSE, STOPPED))
				.search(patient, "Her current medications are aspirin and omeprazole. Any interactions?");

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the kit's name is also this drug's, so nothing is appended");
	}

	/** A hyphen inside a word joins a combination name, which names ibuprofen as well as the nearer metformin. */
	@Test
	public void aHyphenInsideAWordDoesNotEndTheClause() {
		String stated = "Her ibuprofen-metformin order is no longer in force [2]. It interacts with her "
				+ "Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the clause names ibuprofen, so nothing is appended");
	}

	/** Every occurrence of the phrase is asked, not only the first one in its sentence. */
	@Test
	public void aLaterOccurrenceInTheSameSentenceThatIsAboutThisDrugStatesIt() {
		String stated = "Her metformin order is no longer in force; ibuprofen's order is no longer in force "
				+ "too [2]. It interacts with her Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the second clause states it, so nothing is appended");
	}

	/**
	 * ADR Decision 47's recorded live wording, a pronoun after a clause boundary — the form the prompt
	 * teaches ("say in the same sentence that its order is no longer in force"). The drug named nearest
	 * before the phrase is ibuprofen, so nothing is appended.
	 */
	@Test
	public void aPronounAfterAClauseBoundaryStillStatesTheDrugItsSentenceNames() {
		String stated = "Ibuprofen was prescribed, but its order is no longer in force [2]. It interacts "
				+ "with her Acetylsalicylic acid (aspirin) [1].";
		ChartAnswer answer = serviceWith(stated, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
			endedIbuprofen()).search(patient, QUESTION);

		assertAnEndedChip(answer);
		assertEquals(stated, answer.getAnswer(), "the answer already states it, so nothing is appended");
	}

	@Test
	public void aChartHoldingNoEndedOrderOfTheDrugAddsNothing() {
		ChartAnswer answer = serviceWith(MODEL_ANSWER, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"))
				.search(patient, QUESTION);

		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(!chip.isAboutAnEndedOrder(), "precondition: no chip is about an ended order");
		}
		assertEquals(MODEL_ANSWER, answer.getAnswer(), "nothing is ended, so nothing is appended");
	}

	private static final class AnsweringProvider extends LlmProvider {

		private final String answer;

		private AnsweringProvider(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings) {
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			tokenConsumer.accept(answer);
			return search(numberedRecords, focusIndices, question, enumerateFindings);
		}
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

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final RecordMapping[] records;

		private StubStrategy(RecordMapping... records) {
			this.records = records;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(records);
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return buildChart(patient, question);
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}
}
