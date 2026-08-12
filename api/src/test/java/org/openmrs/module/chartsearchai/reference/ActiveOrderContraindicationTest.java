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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * That 2 is <b>1</b> since issue #146 — it was the curated rule and the identity check reporting one
 * allergy twice, not two findings — which changes none of the reasoning above and every ALLERGY count
 * below (a condition rule cannot fold, so that case is untouched).
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
 * <p>Every case runs the real pipeline — the real {@code validate} overloads production calls, and,
 * for the prompt half, the real injector wired to the real validator — with no mock and no
 * reimplementation anywhere. Two details vary by case, deliberately:
 * <ul>
 *   <li>The cases that need echo scoping to be ACTIVE pass real querystore-shaped chart records and
 *       the mappings overload, because scoping is what those cases are about; the three that do not
 *       ({@link #aConditionContraindicatingAnActiveOrderIsRaisedToo},
 *       {@link #everyActiveOrderIsCheckedRatherThanOnlyTheFirst},
 *       {@link #thePatientsOwnContraindicationsLeadTheScreensPairChips}) pass {@code null} mappings,
 *       the documented no-scoping shape, so nothing about them depends on it.</li>
 *   <li>All but one run on the real bundled curated dataset (the production default
 *       {@code sourceFormat=json}, whose ibuprofen entry carries both a curated allergy rule and an
 *       identity-resolvable name).
 *       {@link #aRecitedPartnerThePatientIsNotTakingGainsNoContraindicationCheck} needs the bundled
 *       DDInter sample instead: the partner its answer recites has to be an ENTRY in the loaded
 *       dataset, or no scoping carve-out could have chipped it and the case would assert nothing.
 *       The curated four carry no lisinopril.</li>
 * </ul>
 */
public class ActiveOrderContraindicationTest {

	/** The order name as a chart carries it, and what {@code getActiveDrugNames} holds. */
	private static final String IBUPROFEN_ORDER = "Ibuprofen 400mg";

	/** A question that resolves NO reference drug and is not an interaction screen, so neither
	 *  question-driven arm nor the screen has an anchor — the shape the defect needs. */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	/** The question that opens the interaction screen's gate (issue #113) — it resolves no reference
	 *  drug, which is exactly what that arm requires, so it is the one question shape under which the
	 *  new arm and the screen both run. See {@link #thePatientsOwnContraindicationsLeadTheScreensPairChips}. */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

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

		assertEquals(1, contraindications(warnings).size(),
				"an allergy to a drug the patient is PRESCRIBED must be raised, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "documented ibuprofen allergy"),
				"the curated allergy rule fires, was: " + warnings);
		// The identity check issue #135 un-suppressed reaches this arm too, and since issue #146 it is
		// the SAME chip: ibuprofen's curated rule names ibuprofen, so the two arms report one fact once
		// and the operator's note is the wording that survives. The case below keeps that arm asserted
		// here in its own right, on an allergen no curated rule matches.
	}

	@Test
	public void thePrescribedDrugIsCheckedByTheIdentityArmToo() {
		// The half of this arm that carries no curated rule — issue #135's identity check, on the
		// echo-scoped prescribed-drug path. Brufen is one of the bundled ibuprofen entry's aliases but
		// is NOT any of its rule tokens, so hasAllergyToken matches nothing and only
		// findImpliedSubstances can reach the drug. Split out of the case above when issue #146 folded
		// the two arms' chips there into one: without it, nothing on this path would still assert that
		// the identity arm reaches a prescribed drug at all.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		PatientClinicalContext context = ctx(DrugReferenceTestSupport.set("brufen"), null);
		for (DrugReference.Contraindication rule : service.lookupByToken("ibuprofen")
				.getContraindications()) {
			assertFalse(context.hasAllergyToken(rule.getToken()),
					"precondition: no curated rule may match this allergen, or the chip below is not the "
							+ "identity arm's — " + rule.getToken());
		}

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				ECHOING_ANSWER, NO_DRUG_QUESTION, context, chartWithTheOrderRecord().getMappings());

		assertEquals(1, contraindications(warnings).size(),
				"the identity arm alone must still raise the prescribed drug, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Ibuprofen.",
				contraindications(warnings).get(0).getDetail(),
				"in the identity arm's own wording, was: " + warnings);
	}

	@Test
	public void aPrescribedAllergyIsRaisedEvenWhenNeitherQuestionNorAnswerNamesTheDrug() {
		// The half no scoping tweak can reach, and the reason the missing arm is the fix rather than
		// a contraindications-are-exempt carve-out: the prescribing error is in the chart whether or
		// not the LLM's prose happens to mention the drug. Pre-fix: 0 chips.
		List<SafetyWarning> warnings = validator().validate(
				"Her most recent blood pressure is 120/80 mmHg [1].", "What is her blood pressure?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null), chartWithTheOrderRecord().getMappings());

		assertEquals(1, contraindications(warnings).size(),
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
		// validate with an EMPTY answer, so before this arm existed a question like this one — naming no
		// drug AND not asking to be screened — put no finding in the prompt at all. (A question naming no
		// drug that DOES ask to be screened already contributed one, through the #113 arm; that is why
		// this case uses NO_DRUG_QUESTION rather than SCREENING_QUESTION.) Real injector wired to the
		// real validator.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						ctx(DrugReferenceTestSupport.set("ibuprofen"), null), NO_DRUG_QUESTION));

		assertEquals(1, findings.size(), "the chip must be injected as a citable record, was: " + findings);
		List<String> texts = new ArrayList<String>();
		for (RecordMapping finding : findings) {
			texts.add(finding.getText());
		}
		assertTrue(texts.contains(DrugReferenceInjector.FINDING_PREFIX + "Ibuprofen: Ibuprofen is "
				+ "contraindicated by an active allergy: documented ibuprofen allergy"),
				"a record must carry the chip's own detail verbatim, was: " + texts);
	}

	@Test
	public void aDrugAlreadyInPlayIsNotCheckedTwice() {
		// Composition with the question-driven arms, and with the identity check of issue #135: the
		// same patient
		// asked ABOUT the drug by name. Question-named drugs were never echo-scoped out, so the
		// in-play loop already raises the chip — the new arm must skip an entry it has covered
		// rather than double every one of them. One, not two.
		List<SafetyWarning> warnings = validator().validate("Ibuprofen is not appropriate here.",
				"Is it safe to give her ibuprofen?", ctx(DrugReferenceTestSupport.set("ibuprofen"), null),
				chartWithTheOrderRecord().getMappings());

		assertEquals(1, contraindications(warnings).size(),
				"a prescribed drug the question also names must be checked once, not twice, was: "
						+ warnings);
	}

	@Test
	public void everyActiveOrderIsCheckedRatherThanOnlyTheFirst() {
		// The arm's subject is the order LIST, so a patient on several medications has every one of
		// them compared — the live shape behind this fix's headline capture (one patient, an allergy,
		// and two different orders each raising their own chip). Deliberately arranged so the
		// contraindicated orders are NOT first: paracetamol resolves ahead of them, so an arm that
		// checked one order and stopped would find nothing at all here while still passing every other
		// case in this class (mutation-verified — `break` after the first order left all 889 tests
		// green before this case existed). Paracetamol is the discriminator in the other direction
		// too: an arm that chipped from "this patient has an allergy AND has orders", without
		// comparing the two, would raise something about the one drug neither allergy relates to.
		List<SafetyWarning> warnings = validator().validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Paracetamol 500mg", IBUPROFEN_ORDER,
								"Amoxicillin 500mg"),
						null, DrugReferenceTestSupport.set("ibuprofen", "penicillin"), null),
				null);

		assertEquals(2, warnings.size(),
				"two of the three orders are contraindicated — one chip each since issue #146 — and only "
						+ "contraindications may be raised here, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "documented ibuprofen allergy"),
				"the second order's curated allergy rule fires, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Amoxicillin", "penicillin-class hypersensitivity"),
				"the THIRD order's curated rule fires too — the allergy names a class, not the drug, "
						+ "so only the curated arm can reach it, was: " + warnings);
		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_CONTRAINDICATION,
				"Paracetamol"), "the order neither allergy relates to must raise nothing, was: " + warnings);
	}

	@Test
	public void thePatientsOwnContraindicationsLeadTheScreensPairChips() {
		// The one question shape under which BOTH order-driven arms run: a screening question resolves
		// no reference drug, which opens the screen's gate (issue #113) while leaving the new arm's
		// subjects unchanged. Two properties, neither previously exercised together.
		//
		// ORDER. The new arm is called between the drug-in-play loop and the pairwise arms
		// deliberately: a check against her own allergy and condition records is read before a pair the
		// reference data merely relates. (Not because the screen's pairs are less about her — they are
		// her own orders on both sides — but because they are a lookup over her medication list rather
		// than a finding against her records, and they are the ones a cap can truncate.) It is not only a
		// chip-strip concern: since issue #110 every chip is also injected into the prompt as a citable
		// finding IN THIS ORDER, so this list decides what the model reads first. No measurement here
		// claims a size for that effect — eval/drift-metric/README.md's "render ordering" arm varied the
		// order of partners WITHIN one rendered record, which is a different lever, and that file warns
		// that any wording claim on that host needs repeats. What is pinned is therefore the deliberate
		// choice, not a measured gain: moving this call after the pairwise arms would reverse it silently
		// and nothing else would notice.
		//
		// COMPOSITION. The screen stands down only from a pair a drug-in-play INTERACTION chip already
		// covers (DrugSafetyValidator.InteractionPairs, keyed on the pair's identity), so these
		// contraindications cannot suppress it and it must add its pair. That used to be an argument about
		// the chips' rendered text — the two arms' chip types differ — and is now one about what the
		// ledger records; the outcome asserted below is the same either way.
		List<SafetyWarning> warnings = validator().validate("", SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set(IBUPROFEN_ORDER, "Warfarin 5mg"), null,
						DrugReferenceTestSupport.set("ibuprofen"), null),
				null);

		assertEquals(2, warnings.size(),
				"the contraindication and the screened pair must both be raised, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType(),
				"the patient's own contraindication must lead, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_INTERACTION, warnings.get(1).getType(),
				"with the reference lookup about a pair last, was: " + warnings);
		assertTrue(warnings.get(1).getDetail().toLowerCase().contains("warfarin"),
				"precondition: the screen must really have chipped the ibuprofen x warfarin pair, "
						+ "or the ordering above is asserted over the wrong arm, was: " + warnings);
	}

	@Test
	public void anActiveOrderThePatientIsNotAllergicToRaisesNothing() {
		// The no-false-positive direction, with the arm's body genuinely executing: the patient has a
		// recorded allergy and a recorded condition, so the precondition is met, but neither bears on
		// the drug she is taking. The allergen is one the dataset RESOLVES — asserted below, because an
		// unresolvable token would exit the per-allergen loop before any comparison and the case would
		// silently stop testing them. An arm that chipped from the presence of an active order alone, or
		// that warned on any resolved allergen rather than a related one, fails here.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		assertNotNull(service.lookupByToken("gentamicin"),
				"precondition: the allergen must resolve, so the class comparisons really run");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				ECHOING_ANSWER, NO_DRUG_QUESTION,
				ctx(DrugReferenceTestSupport.set("gentamicin"), DrugReferenceTestSupport.set("hypertension")),
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
