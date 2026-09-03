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

	/** Names no drug and carries no MEDICATIONS intent, so {@code SubjectMatter.coversActiveOrders}
	 *  is FALSE and {@code askedAbout.names(ref)} cannot hold for a prescription the question does not
	 *  write — which is what routes it to the SECOND of the arm's two call-site pairs (a
	 *  condition-shaped question with no medication cue routes there too; the allergy domain is the
	 *  one this fixture's records can be reached through). It is also the arm's canonical shape, the
	 *  one {@code ActiveOrderContraindicationTest}'s class javadoc names it for: "Is the patient
	 *  allergic to something they are already taking?" */
	private static final String ALLERGY_QUESTION = "Does she have any allergies?";

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

	/**
	 * The same finding, raised through the arm's OTHER call-site pair (issue #348, round 4 of this
	 * PR's hardening).
	 *
	 * <p><b>Why a second case and not symmetry.</b> {@code addActiveOrderContraindications} decides
	 * the referent once, per row, and then passes it at TWO pairs of call sites: the branch taken
	 * where the question's own subject matter names the prescription, and the fall-through taken
	 * where it does not. The case above drives the first — its question asks about medications, so
	 * {@code SubjectMatter.coversActiveOrders} holds and {@code names(ref)} is true of every active
	 * order. Nothing drove the second, in either direction: measured on the pushed head this case was
	 * added to, hardcoding the fall-through pair's referent to {@code true} and then to {@code false}
	 * left the whole api module green both times, while the same {@code false} at the FIRST pair
	 * reddened three cases.
	 * So one pair was pinned and one was reachable-but-unpinned, which is the shape CLAUDE.md states
	 * for the sibling decision on this same arm — {@code chartOrderBridges} takes its list as a
	 * parameter, "so each call site needs its own case".
	 *
	 * <p>An allergy-shaped question with no medication cue is what reaches it, and that is not a
	 * corner: it is the question this arm was built for (issue #143), so the unpinned pair was the
	 * one the reported defect's own wording — a refusal about a drug the patient is on, from a
	 * question that proposed nothing — would come back through first.
	 */
	@Test
	public void aContraindicationOnAnAllergyShapedQuestionStatesTheCurrentMedicationCall()
			throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin"),
				null, DrugReferenceTestSupport.set("simvastatin"), null),
			ALLERGY_QUESTION);

		assertTrue(finding.toLowerCase().contains("allerg"),
				"precondition: the order-driven contraindication arm is what raised this: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT),
				"the referent is a property of the ROW — nothing proposed this drug — so it does not "
						+ "depend on which of the arm's two call-site pairs the question routed to: "
						+ finding);
		assertFalse(finding.contains(WITHHOLD),
				"and this pair must not hand the model the prescribing refusal either: " + finding);
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

	/**
	 * The QUESTION-PAIR arm (issue #114) states the proposal call too — its two drugs are both drugs
	 * the question named, so the clinician put them to the module and a call about a proposal is what
	 * a finding there licenses.
	 *
	 * <p>Its own test class asserts nothing about the strength clause, so before this case the arm's
	 * answer to the referent question was pinned by nothing at all: it answers false by construction
	 * (it builds through a public constructor, which cannot set the flag), and "by construction" is
	 * a claim about today's code rather than a guard. A future arm reaching for the package-private
	 * factory reddens here.
	 */
	@Test
	public void aQuestionPairFindingStatesTheProposalCall() throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ctx(40, null, null, null, null, null),
			"Do simvastatin and clarithromycin interact?");

		assertTrue(finding.toLowerCase().contains("major"),
				"precondition: the question named both drugs of the Major pair and neither is an "
						+ "active order, so this is the question-pair arm: " + finding);
		assertTrue(finding.endsWith(WITHHOLD),
				"nothing the patient is taking is involved, so the call is about the proposal: "
						+ finding);
	}

	/**
	 * A SIBLING ROW of a substance the question proposed does not turn that substance's finding into a
	 * statement about current therapy.
	 *
	 * <p>Found by mutation-and-probe while hardening this change, not by the plan. The row skip that
	 * keeps this arm off a drug already in play is row-scoped on purpose — since issue #206 a
	 * substance only the orders resolved gets a group of its own — so a SIBLING row of an in-play
	 * substance still reaches the arm. {@code ContraindicationChips} then folds on the SUBSTANCE and
	 * keeps the strictly stronger RANK across its rows, so that sibling's sentence replaces the
	 * in-play row's; keyed on the ROW, the referent travelled with it and the record stated "a
	 * medication this patient is already taking" about a drug the clinician had just proposed. That is
	 * the fail-open direction — a refusal became a change-of-therapy statement — and it is ADR
	 * Decision 44's cross-row residue arriving on the referent axis, which that decision warns about
	 * in those words: adding a clause where the two channels agreed is how a disagreement gets made.
	 *
	 * <p>The arrangement is {@code UncorroboratedFindingProvenanceTest}'s rank-crossing fixture with
	 * the higher-ranked row made an ACTIVE ORDER, and a question that both proposes the drug and asks
	 * about medications — the second half is what opens {@code SubjectMatter}'s gate so this arm runs
	 * at all.
	 */
	@Test
	public void aSiblingRowOfAProposedSubstanceDoesNotMakeItsFindingAboutCurrentTherapy()
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(
				"chartsearchai-test/drug-reference-rule-rows-rank-crossing.json"));
		List<String> findings = DrugReferenceTestSupport.findingTexts(
			DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null,
					DrugReferenceTestSupport.set("Levoketoconazole (gel)"), null,
					DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null),
				"Is it safe to give her levo, and what other medications is she on?"));

		assertEquals(1, findings.size(),
				"one substance is one chip and one finding, was: " + findings);
		assertTrue(findings.get(0).contains("documented ketoconazole allergy"),
				"precondition: the ORDER row's higher-ranked sentence is the one that survived the "
						+ "fold — without that this case is about nothing: " + findings);
		assertTrue(findings.get(0).endsWith(WITHHOLD),
				"the question proposed this substance, so its finding states the proposal call however "
						+ "this arm reached the row whose sentence survived: " + findings);
	}

	/**
	 * And the OTHER direction at that same pair: a sibling row of a substance the question PROPOSED
	 * keeps the proposal call when the fall-through is what reached it (issue #348, round 4).
	 *
	 * <p>{@link #aSiblingRowOfAProposedSubstanceDoesNotMakeItsFindingAboutCurrentTherapy} holds this
	 * property at the arm's FIRST call-site pair; its question asks about medications, which is what
	 * opens {@code SubjectMatter}'s active-order gate and routes it there. Reached through the
	 * fall-through instead, the property was pinned by nothing: hardcoding the fall-through pair's
	 * referent to {@code true} left the whole api module green, and it reddens this case. That is the FAIL-OPEN direction — a prescribing refusal about a drug the
	 * clinician just proposed becomes a statement about changing her existing therapy — and it is
	 * the same cross-row residue ADR Decision 44 warns about, arriving at the second call site.
	 *
	 * <p>The arrangement is that case's fixture and chart with the medication cue removed from the
	 * question: {@code QueryScopeRouter}'s MEDICATIONS vocabulary is a word list ("medications",
	 * "medicines", "meds", "drugs", "prescriptions", "prescribed"), so <em>"Is it safe to give her
	 * levo? Does she have any allergies?"</em> carries none of it, the allergy domain is what widens
	 * the arm instead, and the ORDER row — the gel, which the question's "levo" does not name — falls
	 * through. Its substance is in play through the TABLETS row, which is what makes the referent
	 * false.
	 */
	@Test
	public void aSiblingRowReachedByTheFallThroughStillStatesTheProposalCall() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(
				"chartsearchai-test/drug-reference-rule-rows-rank-crossing.json"));
		List<String> findings = DrugReferenceTestSupport.findingTexts(
			DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null,
					DrugReferenceTestSupport.set("Levoketoconazole (gel)"), null,
					DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null),
				"Is it safe to give her levo? Does she have any allergies?"));

		assertEquals(1, findings.size(),
				"one substance is one chip and one finding, was: " + findings);
		assertTrue(findings.get(0).contains("documented ketoconazole allergy"),
				"precondition: the ORDER row's rule is the one that spoke — the gel's, which the "
						+ "question's \"levo\" does not name, so the fall-through pair is what raised "
						+ "it: " + findings);
		assertTrue(findings.get(0).endsWith(WITHHOLD),
				"the question proposed this substance, so its finding states the proposal call at "
						+ "either of the arm's call-site pairs: " + findings);
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
