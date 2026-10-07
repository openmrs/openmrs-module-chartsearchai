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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;

/**
 * A question asking only for the patient's allergies states, in the answer, which of her active orders
 * conflict with them, rather than leaving that to a drug-safety chip beside a list the clinician asked
 * for.
 *
 * <p><b>The case.</b> <em>"any allergies?"</em>, asked of a patient prescribed two drugs she is recorded
 * as allergic to, came back with a complete allergy list and two red contraindication chips repeating it,
 * each tagged "About a current medication", and a note about what the safety checks covered. The one fact
 * the chips carried that the list did not — that two of the allergens are drugs she is prescribed — was
 * stated nowhere a clinician reading the answer would read it.
 *
 * <p><b>Everything but the model is real</b>: patient 7 of the standard dataset, her aspirin order 111
 * read through the real {@code OrderService}, a free-text aspirin allergy recorded on her; the real
 * injector and validator over a verbatim slice of the shipped knowledge base; and the real
 * {@link LlmInferenceService#search} and {@code searchStreaming}. The model is a recorder.
 */
public class AllergyQuestionConflictingOrderContextTest extends BaseModuleContextSensitiveTest {

	private static final String SLICE = "chartsearchai-test/ddi-listed-medications-proposal.json";

	/** Aspirin and clopidogrel, which share ATC subgroup B01AC — see the slice's own note. */
	private static final String CROSS_REACTIVE_SLICE = "chartsearchai-test/ddi-allergy-cross-reactive-order.json";

	/** WEIGHT, a concept no drug resolves from, standing in for "other non-coded" for a second free-text allergy. */
	private static final int SECOND_PLACEHOLDER_CONCEPT = 5089;

	private static final String ALLERGY_QUESTION = "any allergies?";

	private static final String MODEL_ANSWER = "Yes — the patient has a recorded allergy to Aspirin [1].";

	/** Her order 111's display, as the chart spells it — the name a clinician finds in her medication list. */
	private static final String ORDER_DISPLAY = "ASPIRIN";

	/** A brand order 111's drug row is renamed to, standing for the ticket's {@code Advil 400mg}. */
	private static final String BRAND = "Brandolin 400mg";

	private Patient patient;

	/** The free-text aspirin allergy every case starts from. */
	private String aspirinAllergyUuid;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
		aspirinAllergyUuid = DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
	}

	private static TestableService serviceAnswering(String modelAnswer) throws IOException {
		return serviceAnswering(modelAnswer, SLICE);
	}

	private static TestableService serviceAnswering(String modelAnswer, String slice) throws IOException {
		DrugReferenceService references = DrugReferenceTestSupport.ddiFixtureService(slice);
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(DrugReferenceTestSupport.obsRecord(1, "BP 120/80")));
		service.setLlmProvider(new Recorder(modelAnswer));
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(references));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(references));
		return service;
	}

	/** The one contraindication chip about her aspirin order, which every case here raises. */
	private static SafetyWarning theAspirinChip(ChartAnswer answer) {
		List<SafetyWarning> about = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()) && chip.isAboutACurrentMedication()) {
				about.add(chip);
			}
		}
		assertEquals(1, about.size(), "precondition: exactly one current-medication contraindication chip, "
				+ "chips were: " + answer.getSafetyWarnings());
		return about.get(0);
	}

	@Test
	public void search_anAllergyQuestionStatesTheConflictingOrderInTheAnswer() throws IOException {
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER + " Currently prescribed despite a recorded allergy: " + ORDER_DISPLAY + ".",
				answer.getAnswer(),
				"an allergy recorded to the very drug she is prescribed is stated as one short line naming her order "
						+ "as her chart spells it, not as her order followed by the chip repeating the allergy list");
		assertTrue(chip.isStatedInTheAnswer(),
				"the chip says the answer states it, so a client need not render it a second time");
	}

	@Test
	public void searchStreaming_theFinalAnswerStatesTheConflictingOrderToo() throws IOException {
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).searchStreaming(patient, ALLERGY_QUESTION,
				token -> { }, reasoning -> { }, citations -> { }, early::add);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER + " Currently prescribed despite a recorded allergy: " + ORDER_DISPLAY + ".",
				answer.getAnswer(), "the streaming path completes the final answer the same way");
		assertTrue(chip.isStatedInTheAnswer(), "and marks the chip the same way");
		assertEquals(1, early.size(), "precondition: the early done fired");
		assertEquals(MODEL_ANSWER, early.get(0).getAnswer(),
				"the early done carries no chips, so it states nothing about them either");
	}

	/**
	 * The reported shape: two of her orders, each the drug of one of her recorded allergies. Patient 2 of the
	 * standard dataset holds an aspirin order and Triomune-30, which carries nevirapine; she is recorded as
	 * allergic to both. One line names both orders as her chart spells them, a semicolon between them, since
	 * a display can carry a comma.
	 */
	@Test
	public void search_twoOrdersEachConflictingWithItsOwnAllergyShareOneLine() throws IOException {
		Patient two = Context.getPatientService().getPatient(2);
		DrugReferenceTestSupport.recordFreeTextAllergy(two, 88, "Aspirin");
		DrugReferenceTestSupport.recordFreeTextAllergy(two, SECOND_PLACEHOLDER_CONCEPT, "Nevirapine");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(Context.getPatientService().getPatient(2),
				ALLERGY_QUESTION);

		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(chip.isStatedInTheAnswer(), "every chip is stated, was: " + chip);
		}
		assertEquals(MODEL_ANSWER + " Currently prescribed despite a recorded allergy: Triomune-30; ASPIRIN.",
				answer.getAnswer(), "both orders in one line, chips were: " + answer.getSafetyWarnings());
	}

	/**
	 * A chip raised by an allergy to a DIFFERENT drug of the same class is not "a recorded allergy" to her
	 * order, so the short line would say something the chart does not: her order is named and the chip's own
	 * sentence quoted after it, as before ADR Decision 167.
	 */
	@Test
	public void search_aCrossReactiveAllergyIsStatedInTheChipsOwnWords() throws IOException {
		Context.getPatientService().voidAllergy(Context.getPatientService().getAllergyByUuid(aspirinAllergyUuid),
				"this case's only allergy is clopidogrel");
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Clopidogrel");
		String modelAnswer = "Yes — the patient has a recorded allergy to Clopidogrel [1].";

		ChartAnswer answer = serviceAnswering(modelAnswer, CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertTrue(chip.getDetail().contains("same ATC class") && chip.getDetail().contains("Clopidogrel"),
				"precondition: the chip is the cross-reactivity finding, was: " + chip.getDetail());
		assertEquals(modelAnswer + " Currently prescribed: " + ORDER_DISPLAY + ". " + chip.getDetail() + ".",
				answer.getAnswer(), "the order is named and the finding quoted, claiming no allergy to the order itself");
		assertTrue(chip.isStatedInTheAnswer(), "and the chip is still marked stated");
	}

	/**
	 * Both kinds about ONE order: the order is named once, in the form that quotes every finding about it, so
	 * the cross-reactivity finding is not marked stated while the answer drops it.
	 */
	@Test
	public void search_anOrderWithACrossReactiveFindingBesideItsOwnAllergyQuotesEveryFinding() throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Clopidogrel");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER, CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		List<String> details = new ArrayList<String>();
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(chip.isStatedInTheAnswer(), "every chip is stated, was: " + chip);
			details.add(chip.getDetail());
		}
		assertEquals(2, details.size(), "precondition: her own aspirin allergy and the clopidogrel cross-reactivity "
				+ "both raise a chip, chips were: " + answer.getSafetyWarnings());
		assertEquals(MODEL_ANSWER + " Currently prescribed: " + ORDER_DISPLAY + ". " + details.get(0) + " "
				+ details.get(1) + ".", answer.getAnswer(), "the order once, then each finding about it in its own words");
	}

	@Test
	public void aMedicationQuestionKeepsTheFindingAsAChip() throws IOException {
		String question = "Are there any drug interactions with her current medications?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"a question about her medications asked for the drug-safety reading, so the chip stays the carrier");
		assertFalse(chip.isStatedInTheAnswer(), "and says the answer does not state it");
	}

	@Test
	public void anAllergyQuestionThatAlsoAsksAboutHerMedicationsKeepsTheFindingAsAChip() throws IOException {
		String question = "Is she allergic to any of her medications?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"asking whether her medications conflict with her allergies IS the drug-safety question");
		assertFalse(chip.isStatedInTheAnswer(), "so the chip stays the carrier");
	}

	@Test
	public void anAllergyQuestionAskingForASafetyReadingKeepsTheFindingAsAChip() throws IOException {
		String question = "Are any of her allergies a safety concern?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"a question asking whether something is safe asked for the drug-safety reading");
		assertFalse(chip.isStatedInTheAnswer(), "so the chip stays the carrier");
	}

	@Test
	public void aFindingOfADrugTheQuestionNamesKeepsEveryFindingAsAChip() throws IOException {
		// The question names her allergen, which puts the drug in play: its finding is the drug-in-play
		// arm's, a drug the question put in play, which ADR Decision 124 keeps a chip. Since issue #552 that
		// chip names her order on the wire, so the statement's gate is not "no order is known".
		String question = "Is she allergic to aspirin?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);
		String orderUuid = Context.getOrderService().getOrder(111).getUuid();

		assertFalse(answer.getSafetyWarnings().isEmpty(), "precondition: the question raised a finding, chips "
				+ "were: " + answer.getSafetyWarnings());
		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(Collections.singletonList(new SafetyWarning.CurrentMedicationOrder(ORDER_DISPLAY, orderUuid)),
				chip.currentMedicationOrders(), "the chip names the order it is about, as every current-medication "
						+ "contraindication chip does");
		assertEquals(MODEL_ANSWER, answer.getAnswer(), "a finding of a drug the question named is not stated");
		for (SafetyWarning each : answer.getSafetyWarnings()) {
			assertFalse(each.isStatedInTheAnswer(), "and no chip says it is, was: " + each);
		}
	}

	/**
	 * Issue #552: the chip itself names the order it is about, by her chart's display and the order's
	 * uuid, where the display is a brand the chip's {@code drug} does not spell. Order 111's drug row is
	 * renamed to a brand on its ASPIRIN concept, as {@code RecordedOrderNameBeyondItsDisplayTest} does, so
	 * the substance is resolved off the concept's name while the order displays the brand.
	 */
	@Test
	public void search_theChipNamesTheBrandedOrderItIsAboutByDisplayAndUuid() throws IOException {
		Context.getAdministrationService().executeSQL("update drug set name = '" + BRAND
				+ "' where drug_id = (select drug_inventory_id from drug_order where order_id = 111)", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		String orderUuid = Context.getOrderService().getOrder(111).getUuid();

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertFalse(chip.getDrug().contains(BRAND), "precondition: the chip names the substance, not her "
				+ "prescription, was: " + chip.getDrug());
		assertEquals(Collections.singletonList(new SafetyWarning.CurrentMedicationOrder(BRAND, orderUuid)),
				chip.currentMedicationOrders(),
				"the chip names the one order it is about, as her chart displays it and by its uuid");
	}

	/** A recorder standing in for the model: answers {@code answer}. */
	private static final class Recorder extends LlmProvider {

		private final String answer;

		private Recorder(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			tokenConsumer.accept(answer);
			return search(numberedRecords, focusIndices, question, cacheScope, cacheSeedRecords, enumerateFindings, referenceRecords,
					drugsAlreadyOrdered);
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
