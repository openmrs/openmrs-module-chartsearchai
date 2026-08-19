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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * once the contraindication stated its strength. {@link #everyInjectedFindingStatesOneOfTheTwoStrengths}
 * is the property; the cases either side of it are the two shapes it has to hold for. An OVERDOSE
 * finding is the one that wants neither clause and cannot reach the renderer at all today, which is
 * why that sweep is also what reddens if a caller ever makes it reachable.
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

	@Test
	public void everyInjectedFindingStatesOneOfTheTwoStrengths() {
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
		boolean sawWithhold = false, sawCaution = false;
		for (RecordMapping finding : findings) {
			String text = finding.getText();
			sawWithhold |= text.contains(WITHHOLD);
			sawCaution |= text.contains(CAUTION);
			assertTrue(text.contains(WITHHOLD) ^ text.contains(CAUTION),
					"every injected finding states exactly one strength, because the prompt's two "
							+ "branches are keyed on those sentences and a finding matching neither "
							+ "falls through to whichever branch the model reaches for: " + text);
		}
		assertTrue(sawWithhold && sawCaution, "both strengths must be reached: " + findings);
	}
}
