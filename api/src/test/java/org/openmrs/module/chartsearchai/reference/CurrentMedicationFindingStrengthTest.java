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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A finding about a medication the patient is ALREADY TAKING states a call about that medication,
 * never a call about giving a drug (issue #348).
 *
 * <p><b>The defect.</b> Asked a screening question — one that names no drug and proposes nothing —
 * the answer came back as a prescribing refusal about a drug the patient is on: <em>"No — Salicylic
 * acid should not be given: it interacts with active order Methotrexate, a Major problem [61]."</em>
 * The finding and the severity were right; nobody asked whether to give salicylic acid. What made it
 * one was the clause the record carried: {@code STRENGTH_WITHHOLD} names an ACT — withholding — that
 * presupposes a proposal, and {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} turns that literal into
 * "open with \"No\" and what to avoid". The two ORDER-DRIVEN arms have no proposal to withhold, so
 * they state the counterpart call instead: the strength is unchanged and only the act moves, which is
 * what the issue asked for.
 *
 * <p><b>Why the counterpart is a clause and not words beside one.</b> ADR Decision 44 measured an
 * ADDITIVE clause on this very record type, six runs, and the answer was byte-identical: a clause
 * nothing in the prompt keys on is inert, and its own reasoning says why ("The clause introduces no
 * new call for {@code DEFAULT_SYSTEM_PROMPT} to teach"). What moved the call, 3 of 3 with the chip
 * byte-identical, was changing the clause the prompt DOES key on (ADR Decision 37). So this replaces
 * the strength clause for these findings rather than qualifying it, and the prompt teaches both new
 * classes in the record's own words — {@code SafetyVerdictSeverityGradationTest} owns that half.
 *
 * <p><b>Both order-driven arms, because the condition is one condition.</b> The interaction SCREEN
 * (issue #113) and the allergy-and-condition join against the patient's own prescriptions (issue
 * #143) are the two arms ADR Decision 37 groups as "the arms whose subject is a drug the patient is
 * already on", and they CO-OCCUR on the very question shape this issue reports:
 * {@code QueryScopeRouter.isInteractionScreening} requires the MEDICATIONS intent, which is the same
 * classification {@code SubjectMatter} widens the order-driven contraindication arm by. Fixing one
 * would leave the other holding the phrase the prompt's ranking sentence keys on, so the refusal lead
 * would simply move to it — {@link #everyFindingOnAScreeningQuestionStatesOneVocabulary} is that
 * arrangement, and it is why the scope is the condition rather than the reported arm.
 */
public class CurrentMedicationFindingStrengthTest {

	/** Verbatim DDInter excerpt. Simvastatin × Clarithromycin is Major (mechanism 2085) and
	 *  Spironolactone × Salicylic acid is Minor (7943), so one fixture carries both strength classes
	 *  and neither pair is hand-authored. */
	private static final String FIXTURE = "chartsearchai-test/ddi-alias-drug-names.json";

	private static final String SCREENING_QUESTION =
			"are there any drug interactions with her current medications?";

	/** Names no drug AND does not ask to be screened, so the interaction screen stands down and the
	 *  order-driven contraindication arm is the only arm that can speak — the same question shape
	 *  {@code ActiveOrderContraindicationTest} uses for that reason. */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	/**
	 * The four clauses as LITERALS, not read off {@code DrugReferenceInjector}: these sentences are
	 * what the prompt keys on to decide how an answer opens, so a case comparing the constant to
	 * itself would stay green through a reword that changed the call the model reads. The same
	 * arrangement {@code FoldedFindingStrengthTest} and {@code SafetyFindingSeverityStrengthTest}
	 * already keep for the older two.
	 */
	private static final String CHANGE_CURRENT =
			"This finding is a reason to change a medication this patient is already taking.";

	private static final String CAUTION_CURRENT = "This finding is a caution about a medication this "
			+ "patient is already taking, not a reason to change it.";

	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static List<String> findings(PatientClinicalContext context, String question)
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		return DrugReferenceTestSupport.findingTexts(DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question));
	}

	private static String onlyFinding(PatientClinicalContext context, String question)
			throws IOException {
		List<String> findings = findings(context, question);
		assertEquals(1, findings.size(),
				"one pair is one citable record, and a second would let the wrong one answer for this "
						+ "case: " + findings);
		return findings.get(0);
	}

	/** Her own two prescriptions, named the way the chart names them, so no chart-order bridge is
	 *  raised and the record under test is the plain shape (issue #349's clause is a separate
	 *  concern). */
	private static PatientClinicalContext onTwoInteractingDrugs(String first, String second) {
		return DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set(first, second),
			null, null, null);
	}

	@Test
	public void aScreenedPairOfHerOwnMedicationsStatesTheCurrentMedicationCall() throws IOException {
		String finding = onlyFinding(onTwoInteractingDrugs("Simvastatin", "Clarithromycin"),
			SCREENING_QUESTION);

		assertTrue(finding.toLowerCase().contains("major"),
				"precondition: this is the withholding-class pair, or the case below is about the "
						+ "caution branch instead: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT),
				"a screened pair of the patient's own prescriptions proposes nothing, so it states the "
						+ "call about her current medication — and states it LAST, where the prompt's "
						+ "two format demonstrations put the call: " + finding);
		assertFalse(finding.contains(WITHHOLD),
				"and it must not also carry the proposal call, which is what made the answer a "
						+ "prescribing refusal: " + finding);
	}

	@Test
	public void aScreenedPairBelowTheWithholdBarStatesTheCurrentMedicationCaution() throws IOException {
		String finding = onlyFinding(onTwoInteractingDrugs("Spironolactone", "Salicylic acid"),
			SCREENING_QUESTION);

		assertTrue(finding.toLowerCase().contains("minor"),
				"precondition: this is the caution-class pair: " + finding);
		assertTrue(finding.endsWith(CAUTION_CURRENT),
				"the caution class needs the counterpart too: left on the proposal caution, this pair "
						+ "reaches the prompt branch that opens by stating the drug CAN BE GIVEN — a "
						+ "presence-shaped permission about a drug she is already on: " + finding);
		assertFalse(finding.contains(CAUTION),
				"so it must not carry the proposal caution: " + finding);
	}

	@Test
	public void aFindingAboutADrugTheQuestionProposedStillStatesTheProposalCall() throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Clarithromycin"),
				null, null, null),
			"Is it safe to give simvastatin?");

		assertTrue(finding.endsWith(WITHHOLD),
				"a drug the QUESTION put in play was proposed, so withholding is exactly the act the "
						+ "finding licenses and nothing about this arm moves: " + finding);
	}

	@Test
	public void aContraindicationAboutHerOwnPrescriptionStatesTheCurrentMedicationCall()
			throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin"),
				null, DrugReferenceTestSupport.set("simvastatin"), null),
			NO_DRUG_QUESTION);

		assertTrue(finding.toLowerCase().contains("allerg"),
				"precondition: the order-driven contraindication arm is what raised this, not an "
						+ "interaction: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT),
				"a contraindication against one of her OWN prescriptions is a reason to change that "
						+ "prescription, not to withhold a drug nobody proposed: " + finding);
	}

	@Test
	public void aContraindicationAboutTheDrugAskedAboutStillStatesTheProposalCall() throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ctx(40, null, null, null,
				DrugReferenceTestSupport.set("simvastatin"), null),
			"Can I give her simvastatin?");

		assertTrue(finding.endsWith(WITHHOLD),
				"the strongest refusal this module makes is untouched: the drug was proposed, so "
						+ "withholding is the act: " + finding);
	}

	/**
	 * The arrangement that decides the SCOPE. A screening question against a patient on two
	 * interacting drugs, one of which her chart also records an allergy to, raises findings from BOTH
	 * order-driven arms into ONE prompt.
	 *
	 * <p>Scoped to the reported arm alone, the contraindication finding would keep
	 * {@code STRENGTH_WITHHOLD} — the phrase the prompt's ranking sentence keys on — while the
	 * interaction finding lost it, so the ranking would hand the lead straight back to a prescribing
	 * refusal and the reported defect would survive its own fix. One vocabulary per response is
	 * therefore the property, and it is asserted over EVERY finding rather than over a chosen one.
	 */
	@Test
	public void everyFindingOnAScreeningQuestionStatesOneVocabulary() throws IOException {
		List<String> findings = findings(
			DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set("Simvastatin", "Clarithromycin"), null,
				DrugReferenceTestSupport.set("simvastatin"), null),
			SCREENING_QUESTION);

		assertTrue(findings.size() >= 2,
				"precondition: both order-driven arms must speak here, or this case cannot see the "
						+ "arrangement it is about: " + findings);
		for (String finding : findings) {
			assertTrue(finding.endsWith(CHANGE_CURRENT) || finding.endsWith(CAUTION_CURRENT),
					"every finding in a response that proposed no drug states a current-medication "
							+ "call: " + finding);
			assertFalse(finding.contains(WITHHOLD) || finding.contains(CAUTION),
					"and none of them states a proposal call, or the prompt's ranking sentence hands "
							+ "the lead to the one that did: " + finding);
		}
	}

	@Test
	public void theTwoClausesAreTheWordsAModelReads() {
		assertEquals(" " + CHANGE_CURRENT, DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION,
			"this clause is what the prompt keys on to decide how the answer opens; a reword is a "
					+ "behaviour change and must fail here");
		assertEquals(" " + CAUTION_CURRENT, DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION,
			"and so is this one");
		assertFalse(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION.contains(
			"a reason to withhold it"),
			"neither counterpart may reproduce the phrase the proposal branch names its class with, "
					+ "or a finding about a current medication is read as a refusal by an antecedent "
					+ "matched shallowly");
		assertFalse(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION.contains(
			"a reason to withhold it"), "the same, for the caution counterpart");
	}
}
