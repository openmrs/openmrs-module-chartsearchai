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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
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

	/** Aspirin and diflunisal, which share ATC subgroup N02BA, salicylic acid and derivatives — see the slice's own note
	 *  (it was aspirin and clopidogrel until ADR Decision 283 read their B01AC as naming a purpose). */
	private static final String CROSS_REACTIVE_SLICE = "chartsearchai-test/ddi-allergy-cross-reactive-order.json";

	/** WEIGHT, a concept no drug resolves from, standing in for "other non-coded" for a second free-text allergy. */
	private static final int SECOND_PLACEHOLDER_CONCEPT = 5089;

	private static final String ALLERGY_QUESTION = "any allergies?";

	private static final String MODEL_ANSWER = "Yes — the patient has a recorded allergy to Aspirin [1].";

	/** What opens the module's statement, set apart from the model's answer by a blank line. */
	private static final String HEADING = "\n\nCurrent orders that directly conflict with the patient's records:";

	/** What an order whose every finding is an allergy recorded to its very drug is followed by. */
	private static final String OWN_ALLERGY = ": recorded allergy to this drug.";

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
		return serviceAnswering(modelAnswer, slice, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
	}

	/** As {@link #serviceAnswering(String, String)}, over a chart of {@code records}. */
	private static TestableService serviceAnswering(String modelAnswer, String slice, RecordMapping... records)
			throws IOException {
		DrugReferenceService references = DrugReferenceTestSupport.ddiFixtureService(slice);
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(records));
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

	/** The one reference the answer carries to the record {@code orderUuid}'s order IS. */
	private static RecordReference theOrderRecord(ChartAnswer answer, String orderUuid) {
		List<RecordReference> found = new ArrayList<RecordReference>();
		List<String> uuids = new ArrayList<String>();
		for (RecordReference reference : answer.getReferences()) {
			uuids.add(reference.getIndex() + ":" + reference.getResourceUuid());
			if (orderUuid.equals(reference.getResourceUuid())) {
				found.add(reference);
			}
		}
		assertEquals(1, found.size(), "precondition: one reference to the order's own record, references were: "
				+ uuids);
		return found.get(0);
	}

	/**
	 * ADR Decision 168: the record the line cites reaches the references as one the MODULE attached, for the
	 * finding the line states — so a clinician can open her order from the answer.
	 */
	private static void assertTheModuleAttachedHerOrder(ChartAnswer answer) {
		SafetyWarning chip = theAspirinChip(answer);
		assertTrue(chip.getFindingCitation() != null, "precondition: the chip names its finding's record");
		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertTrue(order.isAttachedByTheModule(), "the module cited it, not the model");
		assertEquals(Collections.singletonList(chip.getFindingCitation()), order.getAttachedFor(),
				"attached for the finding the line states");
	}

	/**
	 * Whether she is allergic to one named thing is answered about that thing alone (ADR Decision 386): no list of her
	 * orders that conflict with her other allergies, and no chip about an allergy the question does not name. On the 3.7.1
	 * standalone "Is she allergic to oranges?" was answered for Sarah Taylor "No allergy to oranges is recorded." followed
	 * by two orders conflicting with her steroid allergies and ten chips about them — none of it what was asked.
	 */
	@Test
	public void search_whetherSheIsAllergicToOneNamedThingIsAnsweredAboutThatThingAlone() throws IOException {
		String modelAnswer = "No allergy to oranges is recorded.";
		ChartAnswer oranges = serviceAnswering(modelAnswer).search(patient, "Is she allergic to oranges?");
		assertEquals(modelAnswer, oranges.getAnswer(), "the answer about oranges, and nothing else");
		for (SafetyWarning chip : oranges.getSafetyWarnings()) {
			assertFalse(SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()),
				"no chip about an allergy the question does not name: " + chip.getDetail());
		}
		// The allergen named is what it is about: her aspirin allergy, and her aspirin order beside it, stand.
		ChartAnswer aspirin = serviceAnswering(MODEL_ANSWER).search(patient, "Is she allergic to aspirin?");
		assertNotNull(theAspirinChip(aspirin), "the chip about the allergen the question names");
		// And a question for her allergies as a whole keeps its statement (ADR Decision 124).
		assertTrue(serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION).getAnswer().contains(HEADING),
			"precondition: the general allergy question still lists her conflicting order");
	}

	/**
	 * A question about one drug carries nothing about her other medications alone — neither in the answer nor as a chip
	 * (ADR Decision 387). On the 3.7.1 standalone "Is her metronidazole safe?" was answered for Agnes Adams, on aspirin
	 * and recorded allergic to NSAIDs, with "Found as well, about this patient's other medications:" and her aspirin
	 * conflict, and a chip about it: nothing about metronidazole.
	 */
	@Test
	public void search_aQuestionAboutOneDrugCarriesNothingAboutHerOtherMedicationsAlone() throws IOException {
		ChartAnswer answer = serviceAnswering("No interaction with fluconazole is recorded.").search(patient,
			"Is her fluconazole safe?");
		assertFalse(answer.getAnswer().contains("Found as well, about this patient's other medications:")
				|| answer.getAnswer().contains("recorded allergy") || answer.getAnswer().contains(HEADING.trim()),
			"nothing about her aspirin allergy: " + answer.getAnswer());
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertFalse(SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()),
				"no chip about her aspirin allergy: " + chip.getDetail());
		}
		// The drug asked about is what it is about: her aspirin, asked of, keeps its conflict.
		ChartAnswer aspirin = serviceAnswering(MODEL_ANSWER).search(patient, "Is her aspirin safe?");
		assertNotNull(theAspirinChip(aspirin), "the chip about the drug the question names");
	}

	@Test
	public void search_anAllergyQuestionStatesTheConflictingOrderInTheAnswer() throws IOException {
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(MODEL_ANSWER + HEADING + "\n- " + ORDER_DISPLAY + " [" + order.getIndex() + "]" + OWN_ALLERGY,
				answer.getAnswer(),
				"an allergy recorded to the very drug she is prescribed is stated under its own heading, one short item "
						+ "naming her order as her chart spells it and citing the record it is, not her order followed by "
						+ "the chip repeating the allergy list");
		assertTheModuleAttachedHerOrder(answer);
		assertTrue(chip.isStatedInTheAnswer(),
				"the chip says the answer states it, so a client need not render it a second time");
	}

	/**
	 * The line names her order and cites it, and cites the ALLERGY too where the chart carries its record: the allergy
	 * is the other half of the conflict, and without its marker a clinician could open the prescription from the answer
	 * but not the record that makes it a conflict (Betty Williams, 2026-10-14). The record cited is the one the finding
	 * fired on, the module's own provenance (ADR Decision 80), attached for the finding the line states.
	 */
	@Test
	public void search_theLineCitesTheRecordedAllergyAsWellAsHerOrder() throws IOException {
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER, SLICE, DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				DrugReferenceTestSupport.allergyRecord(2, aspirinAllergyUuid, "Allergy: Aspirin")).search(patient,
						ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(MODEL_ANSWER + HEADING + "\n- " + ORDER_DISPLAY + " [" + order.getIndex() + "]"
				+ ": recorded allergy to this drug [2].", answer.getAnswer(),
				"the item cites the allergy record beside the order it conflicts with");
		RecordReference allergy = theOrderRecord(answer, aspirinAllergyUuid);
		assertEquals(2, allergy.getIndex());
		assertTrue(allergy.isAttachedByTheModule(), "the module cited the allergy, not the model");
		assertEquals(Collections.singletonList(chip.getFindingCitation()), allergy.getAttachedFor(),
				"attached for the finding the line states");
		assertTheModuleAttachedHerOrder(answer);
	}

	/**
	 * A record the MODEL already cited stays the model's citation, as a record the model cited stays its own
	 * under issue #305's attachment: the module's marker beside it claims nothing the model did not.
	 */
	@Test
	public void search_anOrderRecordTheModelCitedStaysTheModelsCitation() throws IOException {
		int number = theOrderRecord(serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION),
				Context.getOrderService().getOrder(111).getUuid()).getIndex();
		String modelAnswer = "Yes — the patient has a recorded allergy to Aspirin [1], and takes aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient, ALLERGY_QUESTION);

		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(number, order.getIndex(), "precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer + HEADING + "\n- " + ORDER_DISPLAY + " [" + number + "]" + OWN_ALLERGY,
				answer.getAnswer(), "the item still cites it");
		assertFalse(order.isAttachedByTheModule(), "and the reference stays the one the model emitted");
	}

	@Test
	public void searchStreaming_theFinalAnswerStatesTheConflictingOrderToo() throws IOException {
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).searchStreaming(patient, ALLERGY_QUESTION,
				token -> { }, reasoning -> { }, citations -> { }, early::add);

		SafetyWarning chip = theAspirinChip(answer);
		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(MODEL_ANSWER + HEADING + "\n- " + ORDER_DISPLAY + " [" + order.getIndex() + "]" + OWN_ALLERGY,
				answer.getAnswer(), "the streaming path completes the final answer the same way");
		assertTheModuleAttachedHerOrder(answer);
		assertTrue(chip.isStatedInTheAnswer(), "and marks the chip the same way");
		assertEquals(1, early.size(), "precondition: the early done fired");
		assertEquals(MODEL_ANSWER, early.get(0).getAnswer(),
				"the early done carries no chips, so it states nothing about them either");
	}

	/**
	 * The reported shape: two of her orders, each the drug of one of her recorded allergies. Patient 2 of the
	 * standard dataset holds an aspirin order and Triomune-30, which carries nevirapine; she is recorded as
	 * allergic to both. Each order is its own item under the heading, as her chart spells it. Triomune-30 is TWO of her orders, so no one record is the item it names, and
	 * it cites none — the injector's rule, which numbers a display only where it is one record.
	 */
	@Test
	public void search_twoOrdersEachConflictingWithItsOwnAllergyAreEachAnItem() throws IOException {
		Patient two = Context.getPatientService().getPatient(2);
		DrugReferenceTestSupport.recordFreeTextAllergy(two, 88, "Aspirin");
		DrugReferenceTestSupport.recordFreeTextAllergy(two, SECOND_PLACEHOLDER_CONCEPT, "Nevirapine");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(Context.getPatientService().getPatient(2),
				ALLERGY_QUESTION);

		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			assertTrue(chip.isStatedInTheAnswer(), "every chip is stated, was: " + chip);
		}
		RecordReference aspirin = theOrderRecord(answer, Context.getOrderService().getOrder(444).getUuid());
		assertEquals(MODEL_ANSWER + HEADING + "\n- Triomune-30" + OWN_ALLERGY + "\n- ASPIRIN [" + aspirin.getIndex() + "]"
				+ OWN_ALLERGY, answer.getAnswer(),
				"each order its own item, the one that is one record citing it, chips were: " + answer.getSafetyWarnings());
		for (RecordReference reference : answer.getReferences()) {
			assertTrue(!reference.isAttachedByTheModule() || reference.getIndex() == aspirin.getIndex(),
					"no record is attached for the item no one record is, was: " + reference.getIndex() + " "
							+ reference.getResourceUuid());
		}
	}

	/**
	 * A chip raised by an allergy to a DIFFERENT drug of the same class is not an answer to "any allergies?": her
	 * order is not one of her allergies, so the answer is the model's alone and the finding stays a chip, drawn apart
	 * from the answer (ADR Decision 188, which leaves a cross-reactivity finding out of Decision 124's statement).
	 */
	/** A concept of the standard test dataset standing in for a severity concept: CD4 COUNT, which no drug resolves from. */
	private static final int SEVERITY_PLACEHOLDER_CONCEPT = 5497;

	/** Voids the aspirin allergy every case starts from and records {@code allergen} with a severity in its place. */
	private void recordInsteadOfAspirin(String allergen, String severityProperty) {
		Context.getPatientService().voidAllergy(Context.getPatientService().getAllergyByUuid(aspirinAllergyUuid),
				"this case records its own allergy");
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, allergen,
				SEVERITY_PLACEHOLDER_CONCEPT, severityProperty);
	}

	/**
	 * A cross-reactivity finding names the allergy's RECORDED severity: a possible cross-reactivity with a severe
	 * allergy is the fact a clinician weighs first, and it reached neither the answer, the chip nor the finding's record
	 * (Betty Williams' severe aspirin allergy and ibuprofen, 2026-10-14; ADR Decision 226).
	 */
	@Test
	public void search_aCrossReactivityFindingNamesTheAllergysRecordedSeverity() throws IOException {
		recordInsteadOfAspirin("Diflunisal", "allergy.concept.severity.severe");

		ChartAnswer answer = serviceAnswering("Yes — the patient has a recorded allergy to Diflunisal [1].",
				CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals("Acetylsalicylic acid (aspirin) is in the same ATC class (N02BA) as the patient's severe allergy to Diflunisal"
				+ " — possible cross-reactivity", chip.getDetail());
	}

	/** And so does the identity finding, an allergy recorded to the very drug she is prescribed. */
	@Test
	public void search_anIdentityFindingNamesTheAllergysRecordedSeverity() throws IOException {
		recordInsteadOfAspirin("Aspirin", "allergy.concept.severity.moderate");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		assertEquals("The patient has a recorded moderate allergy to Acetylsalicylic acid (aspirin).", theAspirinChip(answer).getDetail());
	}

	/** A severity concept the install does not map to mild, moderate or severe names no severity at all. */
	@Test
	public void search_aSeverityTheInstallDoesNotMapIsNotNamed() throws IOException {
		recordInsteadOfAspirin("Aspirin", null);

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, ALLERGY_QUESTION);

		assertEquals("The patient has a recorded allergy to Acetylsalicylic acid (aspirin).", theAspirinChip(answer).getDetail());
	}

	@Test
	public void search_aCrossReactiveAllergyAloneIsLeftToItsChip() throws IOException {
		Context.getPatientService().voidAllergy(Context.getPatientService().getAllergyByUuid(aspirinAllergyUuid),
				"this case's only allergy is diflunisal");
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Diflunisal");
		String modelAnswer = "Yes — the patient has a recorded allergy to Diflunisal [1].";

		ChartAnswer answer = serviceAnswering(modelAnswer, CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertTrue(chip.getDetail().contains("same ATC class") && chip.getDetail().contains("Diflunisal"),
				"precondition: the chip is the cross-reactivity finding, was: " + chip.getDetail());
		assertEquals(modelAnswer, answer.getAnswer(), "nothing is appended to the allergy list");
		assertFalse(chip.isStatedInTheAnswer(), "the finding stays a chip the clinician has not read in the answer");
		assertTrue(chip.isAboutAnotherOfHerMedications(),
			"and is drawn apart from the answer, as a finding about her medications rather than what was asked");
		for (RecordReference reference : answer.getReferences()) {
			assertFalse(reference.isAttachedByTheModule(), "no order is cited by the module, was: " + reference.getIndex());
		}
	}

	/**
	 * Both kinds about ONE order: the order is named for the allergy recorded to its own drug, and only that finding
	 * is stated and cited; the cross-reactivity finding stays a chip (ADR Decision 188).
	 */
	@Test
	public void search_anOrderWithACrossReactiveFindingBesideItsOwnAllergyStatesOnlyTheOwnAllergy() throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Diflunisal");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER, CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		SafetyWarning own = null;
		SafetyWarning crossReactive = null;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.getDetail().contains("same ATC class")) {
				crossReactive = chip;
			} else {
				own = chip;
			}
		}
		assertEquals(2, answer.getSafetyWarnings().size(), "precondition: her own aspirin allergy and the diflunisal "
				+ "cross-reactivity both raise a chip, chips were: " + answer.getSafetyWarnings());
		assertNotNull(own, "precondition: one chip is her allergy to aspirin itself");
		assertNotNull(crossReactive, "precondition: one chip is the cross-reactivity");
		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(MODEL_ANSWER + HEADING + "\n- " + ORDER_DISPLAY + " [" + order.getIndex() + "]" + OWN_ALLERGY,
			answer.getAnswer(), "the order once, cited, for the allergy to its own drug alone");
		assertTrue(own.isStatedInTheAnswer(), "the own-drug allergy is stated");
		assertFalse(crossReactive.isStatedInTheAnswer(), "the cross-reactivity is not, so it stays a chip");
		assertTrue(crossReactive.isAboutAnotherOfHerMedications(), "drawn apart from the answer");
		assertFalse(own.isAboutAnotherOfHerMedications(), "the finding the answer states is not moved");
		assertTrue(order.isAttachedByTheModule(), "the module cited it");
		assertEquals(Collections.singletonList(own.getFindingCitation()), order.getAttachedFor(),
			"for the one finding the line states");
	}

	@Test
	public void aMedicationQuestionKeepsTheFindingAsAChip() throws IOException {
		// Asked until ADR Decision 303 as "Are there any drug interactions with her current medications?", which the module
		// now answers itself.
		String question = "Are any of her medications unsafe for her kidneys?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(MODEL_ANSWER, answer.getAnswer(),
				"a question about her medications asked for the drug-safety reading, so the chip stays the carrier");
		assertFalse(chip.isStatedInTheAnswer(), "and says the answer does not state it");
	}

	/** Whether she is allergic to any of her medications is the module's to answer, from the finding itself (ADR
	 *  Decision 295): the model read it as "does she have drug allergies". */
	@Test
	public void anAllergyQuestionThatAlsoAsksAboutHerMedicationsIsAnsweredFromTheFinding() throws IOException {
		String question = "Is she allergic to any of her medications?";
		ChartAnswer answer = serviceAnswering(MODEL_ANSWER).search(patient, question);

		theAspirinChip(answer);
		assertTrue(answer.isAnsweredByTheModule(), "the module answers: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith(
				"Yes — this patient has a recorded allergy to one of the medications this patient is taking:\n"),
				"was: " + answer.getAnswer());
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

	/**
	 * A question about her medications, neither about her allergies nor for a drug-safety reading, whose answer the model
	 * writes. Sarah Taylor's "Is she on more than one drug from the same class?" on the 3.7.1 standalone was this case's
	 * question until the module came to answer a duplicate-therapy question itself (ADR Decision 294); a listing of her
	 * medications is still the model's, and is the shape Decision 188 measured gaining the same statement.
	 */
	private static final String MEDICATION_QUESTION = "What medications is she on?";

	/**
	 * The record her aspirin order IS on this chart over {@code slice}, read off the allergy question's statement,
	 * which cites it. Each case using it asserts its own answer cites the same record, so a chart numbering the
	 * order otherwise for the other question fails as a precondition rather than passing on a stray number.
	 */
	private int theAspirinOrderRecordNumber(String slice) throws IOException {
		return theOrderRecord(serviceAnswering(MODEL_ANSWER, slice).search(patient, ALLERGY_QUESTION),
				Context.getOrderService().getOrder(111).getUuid()).getIndex();
	}

	/**
	 * ADR Decision 210: Sarah Taylor's "Is she on more than one drug from the same class?" was answered with her five
	 * corticosteroids, citing each order — two of them drugs she is recorded allergic to — and the answer said
	 * nothing of either allergy. Where the model's answer itself names an order her records directly conflict with,
	 * the statement Decision 124 writes for an allergy question follows it.
	 */
	@Test
	public void search_anAnswerNamingAnOrderHerRecordsDirectlyConflictWithStatesTheConflict() throws IOException {
		int number = theAspirinOrderRecordNumber(SLICE);
		String modelAnswer = "No — her one antiplatelet is aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient, MEDICATION_QUESTION);

		RecordReference order = theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid());
		assertEquals(number, order.getIndex(), "precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer + HEADING + "\n- " + ORDER_DISPLAY + " [" + number + "]" + OWN_ALLERGY,
				answer.getAnswer(), "the answer named her aspirin order, so its conflict with her own allergy record follows "
						+ "it, as on an allergy question");
		assertTrue(theAspirinChip(answer).isStatedInTheAnswer(), "and the chip says the answer states it");
		assertFalse(order.isAttachedByTheModule(), "the record stays the model's citation");
	}

	/**
	 * Sarah Taylor's "Which steroids is she on?" resolved nothing before the answer — no drug, no class, no finding —
	 * so the injector handed the chart back unrebuilt and numbered none of her orders, and an answer citing her
	 * dexamethasone order got no statement. Since ADR Decision 379 "steroid" names a class, so the case asks a question
	 * that still resolves nothing: "Which antimalarials is she on?". The record each of her orders is, is a fact about
	 * her chart, not about a finding (ADR Decision 210).
	 */
	@Test
	public void search_anAnswerNamingTheConflictingOrderStatesItWhereNothingResolvedBeforeTheAnswer() throws IOException {
		int number = theAspirinOrderRecordNumber(SLICE);
		String modelAnswer = "Her one steroid-sparing drug is aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient, "Which antimalarials is she on?");

		assertEquals(0, answer.getFindingCitationExtent().getCarried(),
				"precondition: the pass before the answer carried no finding, so the chart was not rebuilt");
		assertEquals(number, theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid()).getIndex(),
				"precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer + HEADING + "\n- " + ORDER_DISPLAY + " [" + number + "]" + OWN_ALLERGY, answer.getAnswer(),
				"the answer named her aspirin order, so its conflict follows it");
		assertTrue(theAspirinChip(answer).isStatedInTheAnswer(), "and the chip says the answer states it");
	}

	/**
	 * And where her chart already holds a record of every active order of hers, as a full chart does, the injector hands
	 * the chart back without rebuilding it — the path Sarah Taylor's question took on the standalone.
	 */
	@Test
	public void search_anAnswerNamingTheConflictingOrderStatesItWhereTheChartAlreadyHoldsEveryOrderOfHers()
			throws IOException {
		List<RecordMapping> records = new ArrayList<RecordMapping>();
		records.add(DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		int number = 0;
		for (Order order : Context.getOrderService().getActiveOrders(patient, null, null, null)) {
			if (order instanceof DrugOrder) {
				records.add(DrugReferenceTestSupport.drugOrderRecord(records.size() + 1, order.getUuid(),
					order.getConcept().getDisplayString()));
				if (order.getOrderId().intValue() == 111) {
					number = records.size();
				}
			}
		}
		assertTrue(number > 0, "precondition: her aspirin order is active and has a record, records were: " + records);
		String modelAnswer = "Her one steroid-sparing drug is aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer, SLICE, records.toArray(new RecordMapping[0])).search(patient,
			"Which antimalarials is she on?");

		assertEquals(0, answer.getFindingCitationExtent().getCarried(), "precondition: the pass before the answer carried "
				+ "no finding");
		assertEquals(modelAnswer + HEADING + "\n- " + ORDER_DISPLAY + " [" + number + "]" + OWN_ALLERGY, answer.getAnswer(),
				"the answer named her aspirin order, so its conflict follows it");
		assertTrue(theAspirinChip(answer).isStatedInTheAnswer(), "and the chip says the answer states it");
	}

	/** The anchor is the answer naming the order: an answer to the same question that does not leaves it a chip. */
	@Test
	public void search_anAnswerToAMedicationQuestionNotNamingTheConflictingOrderLeavesItAChip() throws IOException {
		String modelAnswer = "No — no class is duplicated [1].";

		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient, MEDICATION_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertEquals(modelAnswer, answer.getAnswer(), "nothing is appended to an answer that names no conflicting order");
		assertFalse(chip.isStatedInTheAnswer(), "the chip stays the carrier");
		assertFalse(chip.isAboutAnotherOfHerMedications(), "and is not drawn apart: the question asked about her medications");
	}

	/**
	 * Both kinds about the order the answer names: the allergy to its own drug is stated, and the cross-reactivity
	 * finding is drawn apart beside it — the owner ruled the box it kept open noise (ADR Decision 210). Not as "another
	 * of her medications", which would be false of a drug the answer names, but as what it is.
	 */
	@Test
	public void search_anAnswerNamingAnOrderDrawsItsCrossReactivityFindingApartBesideTheStatement() throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Diflunisal");
		int number = theAspirinOrderRecordNumber(CROSS_REACTIVE_SLICE);
		String modelAnswer = "No — her one antiplatelet is aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer, CROSS_REACTIVE_SLICE).search(patient, MEDICATION_QUESTION);

		SafetyWarning own = null;
		SafetyWarning crossReactive = null;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.getDetail().contains("same ATC class")) {
				crossReactive = chip;
			} else {
				own = chip;
			}
		}
		assertEquals(2, answer.getSafetyWarnings().size(), "precondition: her own aspirin allergy and the diflunisal "
				+ "cross-reactivity both raise a chip, chips were: " + answer.getSafetyWarnings());
		assertNotNull(own, "precondition: one chip is her allergy to aspirin itself");
		assertNotNull(crossReactive, "precondition: one chip is the cross-reactivity");
		assertEquals(number, theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid()).getIndex(),
				"precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer + HEADING + "\n- " + ORDER_DISPLAY + " [" + number + "]" + OWN_ALLERGY, answer.getAnswer(),
				"the order once, for the allergy to its own drug alone");
		assertTrue(own.isStatedInTheAnswer(), "the own-drug allergy is stated");
		assertFalse(own.isCrossReactivityBesideHerStatedConflicts(), "and is not drawn apart");
		assertFalse(crossReactive.isStatedInTheAnswer(), "the cross-reactivity is not stated");
		assertTrue(crossReactive.isCrossReactivityBesideHerStatedConflicts(),
				"it is drawn apart, as a cross-reactivity finding beside the conflicts the answer states");
		assertFalse(crossReactive.isAboutAnotherOfHerMedications(),
				"and not as another of her medications: the answer names it");
	}

	/** An order only a cross-reactivity finding is about does not directly conflict, so naming it states nothing. */
	@Test
	public void search_anAnswerNamingAnOrderOnlyACrossReactivityFindingIsAboutStatesNothing() throws IOException {
		int number = theAspirinOrderRecordNumber(CROSS_REACTIVE_SLICE);
		Context.getPatientService().voidAllergy(Context.getPatientService().getAllergyByUuid(aspirinAllergyUuid),
				"this case's only allergy is diflunisal");
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Diflunisal");
		String modelAnswer = "No — her one antiplatelet is aspirin [" + number + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer, CROSS_REACTIVE_SLICE).search(patient, MEDICATION_QUESTION);

		SafetyWarning chip = theAspirinChip(answer);
		assertTrue(chip.getDetail().contains("same ATC class") && chip.getDetail().contains("Diflunisal"),
				"precondition: the chip is the cross-reactivity finding, was: " + chip.getDetail());
		assertEquals(number, theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid()).getIndex(),
				"precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer, answer.getAnswer(), "nothing is appended");
		assertFalse(chip.isStatedInTheAnswer(), "the chip stays the carrier");
		assertFalse(chip.isAboutAnotherOfHerMedications(), "in the safety box");
		assertFalse(chip.isCrossReactivityBesideHerStatedConflicts(), "with no stated conflict to be beside");
	}

	/**
	 * On her allergy question the cross-reactivity finding keeps Decision 188's own line, as another of her medications
	 * beside her allergy list: the key Decision 210 adds is the medication answer's alone.
	 */
	@Test
	public void search_anAllergyAnswersCrossReactivityFindingKeepsDecision188sLine() throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, SECOND_PLACEHOLDER_CONCEPT, "Diflunisal");

		ChartAnswer answer = serviceAnswering(MODEL_ANSWER, CROSS_REACTIVE_SLICE).search(patient, ALLERGY_QUESTION);

		SafetyWarning crossReactive = null;
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (chip.getDetail().contains("same ATC class")) {
				crossReactive = chip;
			}
		}
		assertNotNull(crossReactive, "precondition: the diflunisal allergy raises a cross-reactivity chip, chips were: "
				+ answer.getSafetyWarnings());
		assertTrue(crossReactive.isAboutAnotherOfHerMedications(), "drawn apart as Decision 188 draws it");
		assertFalse(crossReactive.isCrossReactivityBesideHerStatedConflicts(), "and not under the medication answer's key");
	}

	/**
	 * Decision 124's own boundary holds where the answer names her order too: an allergy question asking for the
	 * drug-safety reading keeps the chip its carrier (ADR Decision 210). Asked until ADR Decision 295 as "Is she allergic
	 * to any of her medications?", which the module now answers itself.
	 */
	@Test
	public void anAllergyQuestionAskingForASafetyReadingKeepsTheFindingAChipWhereTheAnswerNamesHerOrder()
			throws IOException {
		int number = theAspirinOrderRecordNumber(SLICE);
		String modelAnswer = "Yes — she takes aspirin [" + number + "], and is recorded allergic to it [1].";

		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient, "Are any of her allergies a safety concern?");

		assertEquals(number, theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid()).getIndex(),
				"precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer, answer.getAnswer(), "the chip stays the carrier");
		assertFalse(theAspirinChip(answer).isStatedInTheAnswer(), "and says the answer does not state it");
	}

	/** And a question asking for the drug-safety reading keeps its chips the carrier where the answer names her order. */
	@Test
	public void aDrugSafetyReadingKeepsTheFindingAChipWhereTheAnswerNamesHerOrder() throws IOException {
		int number = theAspirinOrderRecordNumber(SLICE);
		String modelAnswer = "Her aspirin [" + number + "] interacts with nothing else she takes.";

		// Asked until ADR Decision 303 as "Are there any drug interactions with her current medications?", which the module
		// now answers itself.
		ChartAnswer answer = serviceAnswering(modelAnswer).search(patient,
				"Are any of her medications unsafe for her kidneys?");

		assertEquals(number, theOrderRecord(answer, Context.getOrderService().getOrder(111).getUuid()).getIndex(),
				"precondition: the same chart numbers the order alike");
		assertEquals(modelAnswer, answer.getAnswer(), "the chips carry a drug-safety reading");
		assertFalse(theAspirinChip(answer).isStatedInTheAnswer(), "and say the answer does not state them");
	}

	@Test
	public void aFindingOfADrugTheQuestionNamesKeepsEveryFindingAsAChip() throws IOException {
		// The question names her allergen, which puts the drug in play: its finding is the drug-in-play
		// arm's, a drug the question put in play, which ADR Decision 124 keeps a chip. Since issue #552 that
		// chip names her order on the wire, so the statement's gate is not "no order is known". Asked until ADR Decision 327
		// as "Is she allergic to aspirin?", which the module now answers itself.
		String question = "When was her aspirin allergy recorded?";
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
