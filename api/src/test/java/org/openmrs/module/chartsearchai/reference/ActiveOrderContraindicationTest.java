/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * "Is the patient allergic to something they are already taking?" — the contraindication question
 * nothing in {@link DrugSafetyValidator} asked (issue #143).
 *
 * <p><b>The defect.</b> Every contraindication arm was keyed on a drug IN PLAY: one the question
 * resolved, or one the answer named. Echo scoping (issue #105) removes an answer-named drug from
 * that set when a record the answer cites already names it — and a drug the patient is PRESCRIBED
 * appears in a {@code drug_order} chart record, which is exactly the record a good answer cites when
 * asked about medications. So an active ibuprofen order plus an ibuprofen allergy, a question naming
 * no drug and an answer citing the real order record raised <b>0 chips</b>, where the identical call
 * with {@code mappings=null} raised <b>2</b>. Measured on the default {@code sourceFormat=json}.
 *
 * <p>{@code isEchoOfCitedRecord} justified that residual risk by asserting a proposal-worthy drug is
 * "usually question-named (always validated) or actively ordered (checked directly by the
 * order-driven arms)". The second half was false: counted over the whole class, the order-driven arms
 * ({@code addInteractionWarnings}, {@code addQuestionPairInteractions},
 * {@code addActiveOrderPairInteractions}) read the allergy list ZERO times — they check INTERACTIONS.
 * The contraindication arms read allergies but only ever about the drug in play. Nothing joined the
 * two.
 *
 * <p><b>Why the missing arm rather than a scoping exemption.</b> Exempting contraindications from
 * echo scoping would fix the measured case and nothing beyond it: it still needs the ANSWER to name
 * the drug, so the shape in {@link #aPrescribedAllergyIsRaisedEvenWhenNeitherQuestionNorAnswerNamesTheDrug}
 * — a prescribing error nobody happened to write down — stays invisible. It would also widen #105's
 * over-reach onto the contraindication surface, which
 * {@link #aRecitedPartnerThePatientIsNotTakingGainsNoContraindicationCheck} pins against: a drug the
 * answer merely recites out of a cited record is still not contraindication-checked, because the new
 * arm's subject set is the patient's own active orders and nothing else. Adding the arm makes the
 * comment's claim true instead of relaxing the scoping that #105 measured.
 *
 * <p>Every case runs the real pipeline: the real bundled curated dataset (the production default
 * {@code sourceFormat=json}, whose ibuprofen entry carries both a curated allergy rule and an
 * identity-resolvable name), real querystore-shaped chart records, the real {@code validate}
 * overload production calls with the chart's mappings, and — for the prompt half — the real injector
 * wired to the real validator.
 */
public class ActiveOrderContraindicationTest {

	/** The order name as a chart carries it, and what {@code getActiveDrugNames} holds. */
	private static final String IBUPROFEN_ORDER = "Ibuprofen 400mg";

	/** A question that resolves NO reference drug and is not an interaction screen, so neither
	 *  question-driven arm nor the screen has an anchor — the shape the defect needs. */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	/** The patient's real querystore {@code drug_order} chart record for that order — the record an
	 *  answer about her medications cites, and so the record echo scoping attributes the mention to. */
	private static final RecordMapping ORDER_RECORD =
			DrugReferenceTestSupport.drugOrderRecord(2, "order-uuid-1", IBUPROFEN_ORDER);

	/** An answer that names the drug ONLY by reciting the cited drug-order record — the echo the
	 *  scoping attributes to that record, and so the answer shape the defect needs. */
	private static final String ECHOING_ANSWER = "Her only active medication is " + IBUPROFEN_ORDER
			+ " [" + ORDER_RECORD.getIndex() + "].";

	private static DrugSafetyValidator validator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.bundledService());
	}

	/** Context: one active ibuprofen order, plus whatever allergy/condition tokens a case needs. */
	private static PatientClinicalContext ctx(Set<String> allergies, Set<String> conditions) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(IBUPROFEN_ORDER),
				null, allergies, conditions);
	}

	/** A chart holding an obs and that drug-order record, as the serializer numbers them. */
	private static PatientChart chartWithTheOrderRecord() {
		return DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"), ORDER_RECORD);
	}

	private static List<SafetyWarning> contraindications(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(warning.getType())) {
				out.add(warning);
			}
		}
		return out;
	}

	@Test
	public void aPrescribedDrugTheAnswerOnlyEchoesIsStillCheckedAgainstTheAllergyList() {
		// THE defect, in the shape the issue measured. The answer cites the drug_order record and
		// names the drug out of it, so echo scoping takes ibuprofen out of play; the question names
		// no drug, so nothing puts it back. Pre-fix: 0 chips.
		PatientChart chart = chartWithTheOrderRecord();
		assertTrue(ORDER_RECORD.getText().toLowerCase().contains("ibuprofen"),
				"precondition: the cited drug_order record must name the drug, or nothing is echoed");

		List<SafetyWarning> warnings = validator().validate(ECHOING_ANSWER, NO_DRUG_QUESTION,
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null), chart.getMappings());

		assertEquals(2, contraindications(warnings).size(),
				"an allergy to a drug the patient is PRESCRIBED must be raised, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "documented ibuprofen allergy"),
				"the curated allergy rule fires, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen",
				"The patient has a recorded allergy to Ibuprofen."),
				"and so does the identity check issue #140 added, was: " + warnings);
	}

	@Test
	public void aPrescribedAllergyIsRaisedEvenWhenNeitherQuestionNorAnswerNamesTheDrug() {
		// The half no scoping tweak can reach, and the reason the missing arm is the fix rather than
		// a contraindications-are-exempt carve-out: the prescribing error is in the chart whether or
		// not the LLM's prose happens to mention the drug. Pre-fix: 0 chips.
		List<SafetyWarning> warnings = validator().validate(
				"Her most recent blood pressure is 120/80 mmHg [1].", "What is her blood pressure?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null), chartWithTheOrderRecord().getMappings());

		assertEquals(2, contraindications(warnings).size(),
				"the allergy to the active order must be raised with no drug named anywhere, was: "
						+ warnings);
	}

	@Test
	public void aConditionContraindicatingAnActiveOrderIsRaisedToo() {
		// The curated arm's OTHER leg. Echo scoping suppressed both contraindication arms for a
		// prescribed drug, so restoring only the allergy one would leave "she is on ibuprofen and has
		// an active peptic ulcer" as silent as before. It also pins that the new arm's precondition
		// reads BOTH token sets: guarded on allergies alone, this case would return early.
		List<SafetyWarning> warnings = validator().validate("", NO_DRUG_QUESTION,
				ctx(null, DrugReferenceTestSupport.set("peptic ulcer")), null);

		assertEquals(1, contraindications(warnings).size(),
				"the condition rule for the active order must fire, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "active condition",
				"active peptic ulcer disease"), "worded as the in-play arm words it, was: " + warnings);
	}

	@Test
	public void theFindingReachesThePromptAsACitableRecord() {
		// The other half of "it reaches the clinician" (issue #110): the pre-answer pass runs the same
		// validate with an EMPTY answer, so before this arm existed a question naming no drug put no
		// finding in the prompt at all and the deterministic layer contributed nothing. Real injector
		// wired to the real validator.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						ctx(DrugReferenceTestSupport.set("ibuprofen"), null), NO_DRUG_QUESTION));

		assertEquals(2, findings.size(), "both chips must be injected as citable records, was: " + findings);
		boolean identity = false;
		for (RecordMapping finding : findings) {
			identity |= finding.getText().equals(DrugReferenceInjector.FINDING_PREFIX
					+ "Ibuprofen: The patient has a recorded allergy to Ibuprofen.");
		}
		assertTrue(identity, "a record must carry the identity chip's own detail verbatim, was: " + findings);
	}

	@Test
	public void aDrugAlreadyInPlayIsNotCheckedTwice() {
		// Composition with the question-driven arms, and with #140's identity check: the same patient
		// asked ABOUT the drug by name. Question-named drugs were never echo-scoped out, so the
		// in-play loop already raises both chips — the new arm must skip an entry it has covered
		// rather than double every one of them. Two, not four.
		List<SafetyWarning> warnings = validator().validate("Ibuprofen is not appropriate here.",
				"Is it safe to give her ibuprofen?", ctx(DrugReferenceTestSupport.set("ibuprofen"), null),
				chartWithTheOrderRecord().getMappings());

		assertEquals(2, contraindications(warnings).size(),
				"a prescribed drug the question also names must be checked once, not twice, was: "
						+ warnings);
	}

	@Test
	public void anActiveOrderThePatientIsNotAllergicToRaisesNothing() {
		// The no-false-positive direction, with the arm's body genuinely executing: the patient has a
		// recorded allergy and a recorded condition, so the precondition is met, but neither bears on
		// the drug she is taking. An arm that chipped from the presence of an active order alone — or
		// that warned on any resolved allergen the way the in-loop class guard forbids — fails here.
		List<SafetyWarning> warnings = validator().validate(
				ECHOING_ANSWER, NO_DRUG_QUESTION,
				ctx(DrugReferenceTestSupport.set("penicillin"), DrugReferenceTestSupport.set("hypertension")),
				chartWithTheOrderRecord().getMappings());

		assertTrue(contraindications(warnings).isEmpty(),
				"an unrelated allergy and condition must raise nothing about the active order, was: "
						+ warnings);
	}

	@Test
	public void withNoAllergiesAndNoConditionsTheArmIsInert() {
		// The precondition itself, which is what bounds the new arm's blast radius: with neither
		// token set populated both contraindication arms are provably no-ops, so a patient on a drug
		// gains no chip from being on it. This is the common case — every question about every
		// patient carrying no allergy and no condition record.
		List<SafetyWarning> warnings = validator().validate(
				ECHOING_ANSWER, NO_DRUG_QUESTION,
				ctx(null, null), chartWithTheOrderRecord().getMappings());

		assertTrue(warnings.isEmpty(), "an active order alone must raise nothing, was: " + warnings);
	}

	@Test
	public void aRecitedPartnerThePatientIsNotTakingGainsNoContraindicationCheck() {
		// Issue #105's contract, on the surface this change touches, and the case that separates the
		// missing arm from a "contraindications are exempt from echo scoping" carve-out. The answer
		// recites lisinopril out of the cited drug-reference record; the patient is allergic to
		// lisinopril but is NOT on it. Exempting contraindications from scoping would chip "the
		// patient has a recorded allergy to Lisinopril" off a drug nobody proposed — #105's over-reach
		// on a new surface. Keying the new arm on the patient's own orders cannot: lisinopril is not
		// one of them.
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		PatientChart chart = DrugReferenceTestSupport.injector(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("N02BA01"), null, null),
				"Can she take ibuprofen?");
		RecordMapping reference = DrugReferenceTestSupport.injectedReference(chart);
		assertTrue(reference.getText().toLowerCase().contains("lisinopril"),
				"precondition: the real rendered ibuprofen record must name lisinopril");

		String answer = "Ibuprofen interacts with lisinopril [" + reference.getIndex() + "].";
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(answer,
				"Can she take ibuprofen?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("N02BA01"),
						DrugReferenceTestSupport.set("lisinopril"), null),
				chart.getMappings());

		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_CONTRAINDICATION,
				"lisinopril"), "a recited drug the patient is not taking must gain no contraindication "
						+ "check, was: " + warnings);
		assertTrue(contraindications(warnings).isEmpty(),
				"and the aspirin she IS taking is unrelated to that allergy, was: " + warnings);
	}
}
