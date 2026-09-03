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

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * An injected {@code safety_finding} states what the finding LICENSES, and that follows the severity
 * the chip already carries.
 *
 * <p><b>Why this exists (issue #283).</b> The severity of an interaction is decided deterministically
 * — {@link DrugReference.Interaction#getSeverity()} travels onto the chip through
 * {@link SafetyWarning#getSeverity()} (issue #207) — but nothing downstream said what a given rating
 * licenses. The record handed to the model carried the severity as a WORD inside prose, and the
 * system prompt instructed that a finding naming the drug is "evidence against giving it" with no
 * gradation, so a Minor row produced the same refusal a Major one does. Measured on the standalone,
 * `main` @ b0cfe545: "Is gentamicin appropriate for this patient?" answered *"No — gentamicin should
 * not be given"* on a finding whose own mechanism text ends "No special precautions are necessary."
 *
 * <p><b>Why the ratings split where they do.</b> {@code minor} and {@code unknown} are the ratings
 * DDInter itself calls minimally significant; {@code moderate} and {@code major} are not.
 * <b>Unrated is not low-rated</b> — a null severity is a curated hand-authored rule, which
 * {@code DrugSafetyValidator.severityPriority} already sorts ABOVE {@code major} for exactly that
 * reason — so an unrated rule must license withholding, and this is the case a "no rating means
 * nothing serious" reading would get backwards.
 *
 * <p><b>Scope: every finding, and that is the correction rather than the starting point.</b> The
 * clause was first scoped to interaction findings, on the reasoning that a contraindication licenses
 * withholding without needing to say so. It does not, because the prompt's evidence-against claim is
 * now CONDITIONAL on the finding saying it, so a finding matching neither antecedent falls through to
 * whichever branch the model reaches for. Measured on the standalone against {@code main} @ b0cfe545,
 * one Severe recorded Aspirin allergy and one NSAID cross-reactivity chip: <em>"No — ibuprofen should
 * not be taken"</em> became <em>"Ibuprofen can be given, with one caution"</em>, 3 of 3, and came back
 * once the contraindication stated its strength. {@link #everyInjectedFindingStatesExactlyOneStrengthClause}
 * states the property; the other cases pin it one rating and one finding type at a time, the
 * {@code moderate} one being the BOUNDARY itself — a sweep found every rating either side of it
 * covered and the line between them free to move. An OVERDOSE finding is the one that wants neither
 * clause, and it is the PREMISE of its unreachability that is pinned rather than the conclusion — see
 * {@link #theTypeThatStatesNeitherClauseCannotReachTheRendererBeforeThereIsAnAnswer}, which also says
 * what remains uncovered. This javadoc claimed the property case reddens when a caller makes the arm
 * reachable; review found that wrong by reading the case, since it can only see the findings its own
 * arrangement produces and no arrangement of {@code injectRecords} produces an overdose one.
 *
 * <p>Every case here drives the real {@link DrugReferenceInjector#injectRecords} over a real dataset
 * parsed by the production parser, and asserts on the record text the model is actually handed.
 */
public class SafetyFindingSeverityStrengthTest {

	/** The literals, pinned rather than imported: a test comparing a constant to itself asserts
	 *  nothing about what the model reads (the lesson {@code ChartSearchAiAuditSearchModeTest}
	 *  records), and the clause IS the sentence the answer's strength now rests on. */
	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static String findingFor(DrugReferenceService service, String question, String activeOrder) {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(activeOrder), null,
						null, null),
				question);
		return DrugReferenceTestSupport.safetyFindingIn(chart).getText();
	}

	@Test
	public void aMinorRatedInteractionIsACautionAndSaysItIsNotAReasonToWithholdTheDrug() {
		String finding = findingFor(DrugReferenceTestSupport.ddinterServiceWithGroups(),
				"Is it safe to give omeprazole?", "Ciprofloxacin");

		assertTrue(finding.toLowerCase().contains("minor"),
				"the fixture pair must be the Minor-rated one this case is about: " + finding);
		assertTrue(finding.contains(CAUTION),
				"a Minor-rated finding must say it is a caution rather than a reason to withhold: " + finding);
	}

	@Test
	public void aMajorRatedInteractionSaysItIsAReasonToWithholdTheDrug() {
		String finding = findingFor(DrugReferenceTestSupport.ddinterServiceWithGroups(),
				"Is it safe to give sertraline?", "Tramadol");

		assertTrue(finding.toLowerCase().contains("major"),
				"the fixture pair must be the Major-rated one this case is about: " + finding);
		assertTrue(finding.contains(WITHHOLD),
				"a Major-rated finding must say it is a reason to withhold: " + finding);
		assertFalse(finding.contains(CAUTION),
				"a Major-rated finding must not be softened to a caution: " + finding);
	}

	@Test
	public void aModerateRatedInteractionSaysItIsAReasonToWithholdTheDrug() {
		String finding = findingFor(DrugReferenceTestSupport.ddinterServiceWithGroups(),
				"Is it safe to give omeprazole?", "Simvastatin");

		assertTrue(finding.toLowerCase().contains("moderate"),
				"the fixture pair must be the Moderate-rated one this case is about: " + finding);
		// THE BOUNDARY, and the reason this case exists separately from the Major one beside it.
		// ratingLicensesWithholding splits on `rank >= severityRank("moderate")`, and moving that to
		// "major" — i.e. softening Moderate to a caution — left the whole suite green: Minor, Major,
		// Unknown, unrated, the fold and the contraindication were all covered and the boundary
		// itself was not. ADR Decision 37 decides Moderate deliberately ("moderate still refuses.
		// Whether it should qualify instead is a clinical judgement this decision does not take"),
		// so it is a decision, not an accident, and it is pinned here.
		assertTrue(finding.contains(WITHHOLD),
				"a Moderate-rated finding must say it is a reason to withhold: " + finding);
		assertFalse(finding.contains(CAUTION),
				"the caution side of the split is minor and unknown only: " + finding);
	}

	@Test
	public void anUnratedCuratedRuleIsNotSoftenedToACaution() {
		String finding = findingFor(DrugReferenceTestSupport.curatedService(),
				"Is it safe to give ibuprofen?", "Warfarin");

		assertTrue(finding.contains(WITHHOLD),
				"an operator-authored rule carries no rating and outranks major, so it must license "
						+ "withholding: " + finding);
		// A curated note ends on the note, not on a full stop, so the clause has to be a NEW sentence
		// rather than run on from the detail — the reason renderFinding shares
		// DrugSafetyValidator.endSentence rather than concatenating.
		assertTrue(finding.contains("increased risk of GI bleeding. " + WITHHOLD),
				"the clause must open a sentence of its own even where the detail ends without a full "
						+ "stop: " + finding);
		assertFalse(finding.contains(CAUTION),
				"unrated is not low-rated — a curated rule must not be read as a mere caution: " + finding);
	}

	@Test
	public void aRecordedAllergyContraindicationSaysItIsAReasonToWithholdTheDrug() {
		String finding = DrugReferenceTestSupport.safetyFindingIn(
				DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.curatedService())
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
								DrugReferenceTestSupport.ctx(60, null, null, null,
										DrugReferenceTestSupport.set("ibuprofen"), null),
								"Is it safe to give ibuprofen?")).getText();

		assertTrue(finding.toLowerCase().contains("allerg"),
				"this case is about the recorded-allergy contraindication finding: " + finding);
		assertTrue(finding.contains(WITHHOLD),
				"a contraindication has to SAY it withholds, because since #283 the prompt's "
						+ "evidence-against claim is conditional on the finding saying so: " + finding);
		assertFalse(finding.contains(CAUTION),
				"and it is never a caution: " + finding);
	}

	@Test
	public void aCrossReactivityContraindicationSaysItIsAReasonToWithholdTheDrug() {
		String finding = DrugReferenceTestSupport.safetyFindingIn(
				DrugReferenceTestSupport.injectorWithSafety(
						DrugReferenceTestSupport.ddinterServiceWithGroups())
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
								DrugReferenceTestSupport.ctx(60, null, null, null,
										DrugReferenceTestSupport.set("Aspirin"), null),
								"Is it safe to give ibuprofen?")).getText();

		assertTrue(finding.contains("cross-reactivity"),
				"this case is the shape the regression was measured on — a curated-group "
						+ "cross-reactivity contraindication, not an identity allergy: " + finding);
		assertTrue(finding.contains(WITHHOLD),
				"the weakest-worded contraindication the module raises still withholds, and this is "
						+ "the one that flipped: " + finding);
		assertFalse(finding.contains(CAUTION),
				"a recorded allergy is never a caution: " + finding);
	}

	/**
	 * The PREMISE the empty default rests on, pinned: the one finding type that states neither clause
	 * cannot reach {@link DrugReferenceInjector#renderFinding} before an answer exists.
	 *
	 * <p>An OVERDOSE finding wants neither clause as written, being a reason to change the DOSE, which
	 * withholding overstates and a caution understates, so {@code strengthClause} leaves it the empty
	 * default. That is only safe while the arm cannot fire: {@code preAnswerFindings} validates with an
	 * EMPTY answer and the dose arm parses a stated dose out of the answer. This case asserts that,
	 * both ways round, so it reddens the moment the dose arm becomes reachable before an answer and a
	 * finding stating neither clause reaches the model.
	 *
	 * <p><b>Which is not what {@link #everyInjectedFindingStatesExactlyOneStrengthClause} does, and the
	 * javadoc used to claim it was.</b> That case iterates the findings ONE fixed arrangement produced,
	 * and no arrangement of {@code injectRecords} can produce an overdose finding, so it can never
	 * observe the type it was named as the guard for. It stays where it is for the property it does
	 * hold. Measured by mutation rather than argued: with {@code preAnswerFindings} validating against
	 * a stated dose instead of the empty string, THIS case reddens and names the clause-less record
	 * ("The stated Amoxicillin dose ~4000 mg/day exceeds …") while that one stays green. What is still
	 * uncovered, stated rather than left to be discovered: a caller that renders findings after an
	 * answer exists is a NEW path, and neither case runs it.
	 *
	 * <p>The non-vacuity is carried by the precondition rather than by the property. Asserting that no
	 * overdose record is injected would pass on an arrangement that could not raise one anyway, which
	 * is the fault {@code SubjectMatterScopedContraindicationTest} records of its own first version, so
	 * the same arrangement is first shown to raise exactly one through the real {@code validate}.
	 */
	@Test
	public void theTypeThatStatesNeitherClauseCannotReachTheRendererBeforeThereIsAnAnswer()
			throws Exception {
		// The curated schema is the only one carrying ageBands, so the dose arm needs this fixture
		// rather than either DDInter service above; the arrangement is OverdoseSubstanceCollapseTest's,
		// which is where the 4000 mg/day against a published 3000 mg/day ceiling comes from.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.fixtureEntries(
						"chartsearchai-test/drug-reference-substance-dosing-rows.json"));
		PatientClinicalContext context =
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null);
		String question = "what dose of amoxicillin?";

		List<SafetyWarning> withAnswer = DrugReferenceTestSupport.validator(service)
				.validate("Give amoxicillin 2000 mg twice daily.", question, context);
		assertEquals(1, withAnswer.size(),
				"precondition: WITH an answer stating the dose this arrangement must raise exactly "
						+ "one warning, or the property below passes on nothing: " + withAnswer);
		assertEquals(SafetyWarning.TYPE_OVERDOSE, withAnswer.get(0).getType(),
				"and it must be the overdose one, the type that states neither clause: " + withAnswer);

		List<RecordMapping> injected = DrugReferenceTestSupport.injectedFindings(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(), context, question));
		List<String> overdose = new ArrayList<String>();
		for (RecordMapping finding : injected) {
			if (finding.getResourceUuid().startsWith(SafetyWarning.TYPE_OVERDOSE + ":")) {
				overdose.add(finding.getText());
			}
		}
		assertTrue(overdose.isEmpty(),
				"the injection path validates with an EMPTY answer, so the dose arm cannot fire and "
						+ "the empty strength clause is unreachable. A finding here would reach the "
						+ "model stating neither clause, and the prompt's two branches are keyed on "
						+ "those sentences: " + overdose);
	}

	/**
	 * Every injected finding states exactly ONE strength clause, out of the four
	 * {@code strengthClause} can return since issue #348.
	 *
	 * <p>Written over the whole set rather than over the pair this arrangement reaches, and that is
	 * the point of the sweep: a finding stating NONE of them falls through to whichever lead the model
	 * reaches for, which is what issue #283 measured, and a finding stating TWO would put two calls in
	 * one record. Neither is visible to a case that only looks for the two clauses it expects — before
	 * #348 this case asked for exactly that, so a screening finding acquiring a third clause would
	 * have reddened it with a message about the wrong pair.
	 *
	 * <p>The arrangement is a PROPOSAL question, so the two clauses it must actually reach are the
	 * proposal pair; the current-medication pair is reached per arm by
	 * {@code CurrentMedicationFindingStrengthTest}, and by
	 * {@code .everyFindingOnAScreeningQuestionStatesOneVocabulary} over a whole response.
	 */
	@Test
	public void everyInjectedFindingStatesExactlyOneStrengthClause() {
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				DrugReferenceTestSupport.injectorWithSafety(
						DrugReferenceTestSupport.ddinterServiceWithGroups())
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
								DrugReferenceTestSupport.ctx(60, null,
										DrugReferenceTestSupport.set("Ciprofloxacin", "Methotrexate"),
										null, DrugReferenceTestSupport.set("Aspirin"), null),
								"Is it safe to give omeprazole and ibuprofen?"));

		assertTrue(findings.size() >= 3,
				"the arrangement must reach both finding types and both strengths, or the sweep below "
						+ "passes on too little: " + findings);
		String[] clauses = { WITHHOLD, CAUTION,
			DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION.trim(),
			DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION.trim() };
		boolean sawWithhold = false, sawCaution = false;
		for (RecordMapping finding : findings) {
			String text = finding.getText();
			sawWithhold |= text.contains(WITHHOLD);
			sawCaution |= text.contains(CAUTION);
			// A plain count, because the four CLAUSES are not substrings of one another — ADR Decision 37
			// makes that distinction and it is easy to lose: what nests is the phrase each clause names
			// its CLASS with ("a reason to withhold it", inside the caution clause negated), not the
			// clause. SafetyVerdictSeverityGradationTest
			// .theOnlyStrengthClassNamedInsideAnothersWordsIsTheOneDecision37Handles walks the cores;
			// this walks the clauses, and an earlier version of this loop conflated the two and
			// subtracted a match that was never there.
			int stated = 0;
			for (String clause : clauses) {
				if (text.contains(clause)) {
					stated++;
				}
			}
			assertEquals(1, stated,
					"every injected finding states exactly one strength clause, because the prompt's "
							+ "branches are keyed on those sentences: a finding matching NONE falls "
							+ "through to whichever branch the model reaches for, and one matching two "
							+ "states two calls: " + text);
		}
		assertTrue(sawWithhold && sawCaution,
				"and this arrangement is a proposal question, so both PROPOSAL strengths must be "
						+ "reached or the sweep above ran over one class: " + findings);
	}
}
