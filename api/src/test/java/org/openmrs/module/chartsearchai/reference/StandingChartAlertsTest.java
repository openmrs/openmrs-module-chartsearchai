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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The standing surface: "is this patient prescribed something her own chart contraindicates?", asked
 * of the chart rather than of a response (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/280">#280</a>).
 *
 * <p><b>What it is for.</b> Issue #143's active-order contraindication arm ran on every question, and
 * that was withdrawn: measured live on the 3.7.1 standalone, four questions about allergies,
 * interactions, cancer and a date of birth returned the same two contraindication chips byte for byte,
 * which is an alert riding whatever answer a clinician happened to ask for. The arm is now bounded by
 * {@code DrugSafetyValidator.SubjectMatter}. What that gives up is stated in the reversal it produced,
 * {@code ActiveOrderContraindicationTest.aPrescribedAllergyIsNotRaisedWhereTheResponseIsAboutSomethingElse}:
 * a clinician who never asks a drug-shaped question is no longer told that the patient is actively
 * prescribed the drug she is recorded as allergic to. This class covers the surface that gives it back
 * — one a client ASKS for, so nothing rides an unrelated answer.
 *
 * <p><b>The mechanism, and why the fix is one gate rather than a second arm.</b> On a pass with no
 * question and no answer every other arm is silent by its OWN anchor, not by anything this change
 * added: {@code findByQuery} answers an empty list for a null question, so the drug-in-play, dose and
 * interaction arms never enter their loop over the in-play set; {@code addQuestionPairInteractions}
 * requires two question drugs; and the screening arm requires
 * {@code QueryScopeRouter.isInteractionScreening} of a null question, which is false. So
 * {@code addActiveOrderContraindications} is the only arm such a pass can reach, and the only thing
 * withholding its findings is {@code SubjectMatter}, which on a surface that is not a response has no
 * referent to answer about. {@link #theStandingSurfaceReportsNoInteractionsEvenBetweenInteractingActiveOrders}
 * is what keeps that composite true rather than leaving it to this paragraph.
 *
 * <p>Every case drives the real validator over a real parsed dataset through
 * {@link DrugReferenceTestSupport}, with no mock and no reimplementation. The seam is
 * {@code standingChartAlerts(PatientClinicalContext)}, which is where {@code validate}'s own
 * package-private seam sits and what every contextless case in
 * {@code ActiveOrderContraindicationTest} already drives; the two GPs above it are covered by
 * {@link #theGateOnTheStandingSurfaceIsTheOneTheAnswerSurfaceReads} and by
 * {@code StandingChartAlertsToggleContextTest}, which need a real {@code Context} and so cannot live
 * here.
 */
public class StandingChartAlertsTest {

	/** The order name as a chart carries it, and what {@code getActiveDrugNames} holds — the same
	 *  prescription {@code ActiveOrderContraindicationTest} measures the answer surface on, so the two
	 *  classes differ in the surface and in nothing else. */
	private static final String IBUPROFEN_ORDER = "Ibuprofen 400mg";

	private static DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
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

	/**
	 * THE case, in the arrangement the issue names: a patient actively prescribed the drug her chart
	 * records an allergy to, and no response at all.
	 *
	 * <p>It fails on the answer surface by design — the identical chart and dataset raise nothing from
	 * {@code validate} for a question and an answer about something else, which is
	 * {@link #theAnswerSurfaceStillWithholdsTheSameFindingFromAResponseAboutSomethingElse} below. The
	 * pair is the whole point: one arrangement, two surfaces, opposite answers.
	 */
	@Test
	public void aPrescribedDrugTheChartRecordsAnAllergyToIsAStandingAlert() {
		List<SafetyWarning> alerts = curatedValidator().standingChartAlerts(
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(IBUPROFEN_ORDER),
						null, DrugReferenceTestSupport.set("ibuprofen"), null));

		assertEquals(1, contraindications(alerts).size(),
				"a prescribed drug the chart records an allergy to must be a standing alert, was: "
						+ alerts);
		assertTrue(DrugReferenceTestSupport.detailContains(alerts,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "documented ibuprofen allergy"),
				"in the curated allergy rule's own wording — the same sentence the answer surface "
						+ "raises when the response IS about her medications, was: " + alerts);
	}

	/**
	 * The condition leg, so the surface is not allergy-only. The curated arm's two legs were both
	 * suppressed by the scoping this endpoint answers, and restoring only the allergy one would leave
	 * "she is on ibuprofen and has an active peptic ulcer" as silent on the standing surface as it is
	 * on the answer one.
	 */
	@Test
	public void aPrescribedDrugTheChartRecordsAContraindicatingConditionForIsAStandingAlertToo() {
		List<SafetyWarning> alerts = curatedValidator().standingChartAlerts(
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(IBUPROFEN_ORDER),
						null, null, DrugReferenceTestSupport.set("peptic ulcer")));

		assertEquals(1, contraindications(alerts).size(),
				"the condition rule for the active order must reach the standing surface, was: "
						+ alerts);
		assertTrue(DrugReferenceTestSupport.detailContains(alerts,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "active condition",
				"active peptic ulcer disease"), "worded as the arm words it, was: " + alerts);
	}

	/**
	 * The same chart and the same dataset, on the ANSWER surface, still answer nothing — so this class
	 * cannot pass by having re-widened what issue #280 exists to keep narrow.
	 *
	 * <p>Asserted here rather than left to
	 * {@code ActiveOrderContraindicationTest.aPrescribedAllergyIsNotRaisedWhereTheResponseIsAboutSomethingElse},
	 * which asserts it of its own fixture: a change that widened the response path in order to serve
	 * the standing one would have to redden both, and a reader of THIS class needs to see that the two
	 * surfaces are what differ.
	 */
	@Test
	public void theAnswerSurfaceStillWithholdsTheSameFindingFromAResponseAboutSomethingElse() {
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(IBUPROFEN_ORDER), null,
				DrugReferenceTestSupport.set("ibuprofen"), null);

		List<SafetyWarning> onTheAnswer = curatedValidator().validate(
				"Her most recent blood pressure is 120/80 mmHg.", "What is her blood pressure?", chart);
		List<SafetyWarning> standing = curatedValidator().standingChartAlerts(chart);

		assertEquals(0, contraindications(onTheAnswer).size(),
				"a response about her blood pressure must still carry no chips about her "
						+ "prescriptions, was: " + onTheAnswer);
		assertNotEquals(contraindications(onTheAnswer).size(), contraindications(standing).size(),
				"and the standing surface must be what differs — if both answer nothing this class "
						+ "is measuring a chart that raises nothing, was: " + standing);
	}

	/**
	 * The bound issue #280 is scoped to: the standing surface reports CONTRAINDICATIONS and not every
	 * deterministic finding the module can compute.
	 *
	 * <p>Measured on the six-order screening chart, whose six drugs the DDInter excerpt relates 15 ways
	 * — so an implementation that reached the interaction arms on this pass would report a great many
	 * findings rather than one, and the assertion is on the TYPE rather than on a count for that
	 * reason. The allergy is carried by the identity arm (issue #135) rather than by a curated rule,
	 * which is what lets this run on the excerpt at all: DDInter publishes no hand-authored allergy or
	 * condition rule.
	 *
	 * <p>This is the case that keeps the class javadoc's "every other arm is silent by its own anchor"
	 * true. Widen the pass — give it a question, or reach an interaction arm from it — and it reddens.
	 */
	@Test
	public void theStandingSurfaceReportsNoInteractionsEvenBetweenInteractingActiveOrders() {
		DrugReferenceService excerpt = DrugReferenceTestSupport.ddinterService();
		PatientClinicalContext screened = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin", "Warfarin", "Ciprofloxacin",
						"Clarithromycin", "Fluconazole", "Amiodarone"),
				DrugReferenceTestSupport.set("C10AA01", "B01AA03", "J01MA02", "J01FA09", "J02AC01",
						"C01BD01"),
				DrugReferenceTestSupport.set("warfarin"), null);

		List<SafetyWarning> alerts = DrugReferenceTestSupport.validator(excerpt)
				.standingChartAlerts(screened);

		assertEquals(1, contraindications(alerts).size(),
				"precondition: the recorded warfarin allergy must reach her warfarin order, or this "
						+ "case asserts nothing about what it excludes, was: " + alerts);
		assertEquals(contraindications(alerts).size(), alerts.size(),
				"the standing surface states the chart's own contraindications and never an "
						+ "interaction between two of her orders — that screen is gated on a question "
						+ "asking for it (issue #113) and there is no question here, was: " + alerts);
	}

	/**
	 * A chart recording neither an allergy nor a condition raises nothing, and the empty list is a
	 * measurement rather than a degraded pass.
	 *
	 * <p>The discriminator is the case above: it runs the same code over a chart that DOES record one
	 * and gets a chip, so an implementation that returned empty unconditionally cannot satisfy both.
	 */
	@Test
	public void aChartRecordingNothingToBeContraindicatedByRaisesNoStandingAlert() {
		List<SafetyWarning> alerts = curatedValidator().standingChartAlerts(
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(IBUPROFEN_ORDER),
						null, null, null));

		assertEquals(0, alerts.size(),
				"nothing in the chart contraindicates the order, so there is nothing to alert on, "
						+ "was: " + alerts);
	}

	/**
	 * The two global properties above the seam every other case here drives, pinned STRUCTURALLY
	 * because no behavioural case in this suite can reach them.
	 *
	 * <p>The cases above enter at {@code standingChartAlerts(PatientClinicalContext)}, which sits BELOW
	 * the gate exactly as {@code validate}'s own package-private seam does — so a public entry that
	 * forgot the gate, or that read {@code drugReference.enabled} alone, would leave every one of them
	 * green while serving standing alerts on an install that had switched the drug-safety validator
	 * off. The omod wire test cannot see it either: it stubs the public method outright.
	 *
	 * <p>So this asserts the shape rather than the behaviour, which is what this repo does when a rule
	 * is real and unobservable ({@code OrderPartnerNameSourceWritePathTest} scans for a write-path
	 * shape for the same reason). It asks that the standing entry names the SAME two gate expressions
	 * the public {@code validate} entry names, taken from that method's own body rather than written
	 * out here — so a change to the answer surface's gate that left this one behind reddens, which a
	 * pair of literals could not do. It cannot check that the two are COMBINED the same way; mutate an
	 * {@code ||} to an {@code &&} and nothing here answers.
	 */
	@Test
	public void theGateOnTheStandingSurfaceIsTheOneTheAnswerSurfaceReads() throws IOException {
		String source = validatorSource();
		String answerGate = bodyOf(source, "public List<SafetyWarning> validate(String answer, String question, Patient patient,\n"
				+ "\t\t\tList<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {");
		String standingGate = bodyOf(source,
				"public List<SafetyWarning> standingChartAlerts(Patient patient) {");

		for (String expression : new String[] { "ChartSearchAiUtils.isDrugReferenceEnabled()",
				"ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS" }) {
			assertTrue(answerGate.contains(expression),
					"precondition: the answer surface's own gate must name " + expression
							+ ", or this case is comparing against nothing — its body was: " + answerGate);
			assertTrue(standingGate.contains(expression),
					"the standing surface must be gated on " + expression + " exactly as the answer "
							+ "surface is (issue #280), and its body was: " + standingGate);
		}
	}

	/** @return {@code DrugSafetyValidator}'s production source. Fails rather than skips when it cannot
	 *          be found: a source scan that silently reads nothing passes, which is the failure this
	 *          module has met before ({@code ChartSearchAiStreamingTest.resolveSourceFile}). */
	private static String validatorSource() throws IOException {
		String relative = "src/main/java/org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.java";
		for (Path candidate : new Path[] { Paths.get(relative), Paths.get("api").resolve(relative) }) {
			if (Files.isRegularFile(candidate)) {
				return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
			}
		}
		throw new IllegalStateException("could not locate " + relative + " from "
				+ Paths.get("").toAbsolutePath() + " — this guard must fail rather than assert about "
				+ "an empty string");
	}

	/** @return the source between {@code declaration}'s opening brace and the closing brace in the
	 *          first column of a member. Fails naming the declaration when it is absent, so a rename
	 *          cannot leave this reading an empty string and passing. */
	private static String bodyOf(String source, String declaration) {
		int at = source.indexOf(declaration);
		assertNotEquals(-1, at, "no method declared \"" + declaration + "\" in DrugSafetyValidator — "
				+ "this guard would otherwise assert about an empty body and pass");
		assertEquals(-1, source.indexOf(declaration, at + 1),
				"\"" + declaration + "\" is declared more than once, so this guard cannot say which "
						+ "body it read");
		int open = source.indexOf('{', at + declaration.length() - 1);
		int close = source.indexOf("\n\t}", open);
		assertTrue(open >= 0 && close > open, "could not delimit the body of \"" + declaration + "\"");
		return source.substring(open, close);
	}
}
