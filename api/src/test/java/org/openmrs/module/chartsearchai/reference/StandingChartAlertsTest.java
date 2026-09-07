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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The standing surface: "is this patient prescribed something her own chart contraindicates?", asked
 * of the chart rather than of a response (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/280">#280</a>).
 *
 * <p><b>What it is for.</b> Issue #143's active-order contraindication arm ran on every question, and
 * that was withdrawn — ADR Decision 77 carries the measurement. The arm is now bounded by
 * {@code DrugSafetyValidator.SubjectMatter}, and what that gives up is stated in the reversal it produced,
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
 * {@link #theStandingEntryGatesOnThePredicateItPublishes} and by
 * {@code StandingChartAlertsToggleContextTest}, which needs a real {@code Context} and so cannot live
 * here.
 *
 * <p><b>What no case here reaches, named rather than left to be discovered.</b> Nothing produces an
 * ALERT through the public {@code standingChartAlerts(Patient)} path end to end — that would need a
 * context-sensitive patient carrying both a real drug order and a real allergy the bundled dataset
 * relates, and the toggle class's public-entry case runs that path on the standard test patient, who
 * is prescribed nothing this dataset contraindicates, so it asserts the VERDICT and not a finding.
 * The fail-safe {@code catch} in that method is executed by nothing. And every chart here passes a
 * flattened name set with no per-order {@code ActiveDrugOrder} list, which production always builds
 * (issues #118, #290) — so the surface is measured over the shape the arm falls back to rather than
 * the one it usually gets.
 */
public class StandingChartAlertsTest {

	private static DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
	}

	private static List<SafetyWarning> contraindications(List<SafetyWarning> warnings) {
		return DrugReferenceTestSupport.contraindications(warnings);
	}

	/** The findings of a screened pass, asserting it WAS screened — so a case measuring an empty list
	 *  cannot be satisfied by a pass that never ran. {@link #aChartWhoseRecordsCouldNotBeReadIsNotScreened}
	 *  is the case that asserts the other verdict. */
	private static List<SafetyWarning> alertsOf(DrugSafetyValidator validator,
			PatientClinicalContext chart) {
		DrugSafetyValidator.StandingChartAlerts standing = validator.standingChartAlerts(chart);
		assertTrue(standing.isScreened(),
				"precondition: this chart must have been screened, or the findings below are the "
						+ "absence of a pass rather than the absence of a finding");
		return standing.getAlerts();
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
		List<SafetyWarning> alerts = alertsOf(curatedValidator(), 
				DrugReferenceTestSupport.prescribedIbuprofenChart(
						DrugReferenceTestSupport.set("ibuprofen"), null));

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
		List<SafetyWarning> alerts = alertsOf(curatedValidator(), 
				DrugReferenceTestSupport.prescribedIbuprofenChart(
						null, DrugReferenceTestSupport.set("peptic ulcer")));

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
		PatientClinicalContext chart = DrugReferenceTestSupport.prescribedIbuprofenChart(
				DrugReferenceTestSupport.set("ibuprofen"), null);

		List<SafetyWarning> onTheAnswer = curatedValidator().validate(
				"Her most recent blood pressure is 120/80 mmHg.", "What is her blood pressure?", chart);
		List<SafetyWarning> standing = alertsOf(curatedValidator(), chart);

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

		List<SafetyWarning> alerts = alertsOf(DrugReferenceTestSupport.validator(excerpt), screened);

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
		List<SafetyWarning> alerts = alertsOf(curatedValidator(), 
				DrugReferenceTestSupport.prescribedIbuprofenChart(null, null));

		assertEquals(0, alerts.size(),
				"nothing in the chart contraindicates the order, so there is nothing to alert on, "
						+ "was: " + alerts);
	}

	/**
	 * A chart whose allergy or condition read FAILED is not a screened chart, and must not be
	 * published as one.
	 *
	 * <p>This is the shape the surface is most exposed to and the one that has no other signal.
	 * {@code PatientClinicalContextBuilder} swallows a failed read into an EMPTY token set and logs at
	 * DEBUG — which core's shipped {@code log4j2.xml} discards, since it puts {@code org.openmrs} at
	 * WARN — so before this the endpoint answered {@code screened: true} with an empty array for a
	 * patient nobody had looked at. A role holding {@code AI Query Patient Data} without core's
	 * {@code Get Allergies} is exactly that role.
	 *
	 * <p>It is the rule {@code reference/CLAUDE.md} states as "a chart the module could not read is not
	 * a chart that records nothing", met on the one surface whose WHOLE payload can be empty. The
	 * fixture is {@code DrugReferenceTestSupport.unreadableRecordsCtx}, which is the context the real
	 * builder produces for that failure — its token sets are empty for that reason and cannot be
	 * supplied, which is what stops this case being an arrangement no production path reaches.
	 */
	/**
	 * The same rule on the OTHER side of the join: a chart whose ACTIVE-ORDER read failed is not a
	 * screened chart either.
	 *
	 * <p>Found one read short of the case above, by review. {@code getActiveOrders} is
	 * {@code @Authorized(GET_ORDERS)} in core and throws exactly as {@code getAllergies} does, and the
	 * builder degrades it to an empty LIST — so a role holding {@code AI Query Patient Data} without
	 * core's {@code Get Orders} looked exactly like a patient on no medication, and this surface, whose
	 * whole payload is the join between her orders and her records, published it as a clean chart.
	 *
	 * <p>The allergy is recorded and readable here, so this case fails if the seam asks only its
	 * sibling flag — which is what it did.
	 */
	@Test
	public void aChartWhoseActiveOrdersCouldNotBeReadIsNotScreenedEither() {
		DrugSafetyValidator.StandingChartAlerts standing = curatedValidator().standingChartAlerts(
				DrugReferenceTestSupport.unreadableOrdersCtx(
						DrugReferenceTestSupport.set("ibuprofen"), null));

		assertFalse(standing.isScreened(),
				"a chart whose prescriptions the module could not read must not be published as a "
						+ "screened one — an empty order list is then uninterpretable, not empty");
		assertTrue(standing.getAlerts().isEmpty(), "and it states no findings, having screened nothing");
	}

	@Test
	public void aChartWhoseRecordsCouldNotBeReadIsNotScreened() {
		DrugSafetyValidator.StandingChartAlerts standing = curatedValidator()
				.standingChartAlerts(DrugReferenceTestSupport.unreadableRecordsCtx(60, null));

		assertFalse(standing.isScreened(),
				"a chart the module could not read must not be published as a screened one");
		assertTrue(standing.getAlerts().isEmpty(),
				"and it states no findings, since it has none to state");
	}

	/** The one arity of {@code standingChartAlerts} the two global properties sit above. */
	private static final String STANDING_ENTRY =
			"public StandingChartAlerts standingChartAlerts(Patient patient) {";

	private static final String RELATIVE_SOURCE =
			"src/main/java/org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.java";

	/**
	 * The gate above the seam every other case here drives, pinned STRUCTURALLY because no behavioural
	 * case in this suite can reach it.
	 *
	 * <p>The cases above enter at {@code standingChartAlerts(PatientClinicalContext)}, which sits BELOW
	 * the gate exactly as {@code validate}'s own package-private seam does; the omod wire test cannot
	 * see it either, since it stubs the public method outright. So a public entry that forgot the gate,
	 * or that read its own combination of switches, would leave every one of them green while serving
	 * standing alerts on an install where the screen stands down. This case is the only thing in the
	 * suite that stops that.
	 *
	 * <p><b>Through {@link SourceScan}, which is not a convenience.</b> That class reads the file with
	 * its comments and string literals BLANKED, and a hand-rolled reader is why this guard failed both
	 * ways when it had one: a review agent measured that an explanatory {@code //} comment naming
	 * {@code isDrugReferenceEnabled()} inside this method turned the case red with no behaviour
	 * changed, and — the direction that matters — that deleting the gate outright and leaving
	 * {@code // gate: if (!reportsStandingChartAlerts()) {} in its place left the whole class GREEN.
	 * {@code SourceScan} also fails loudly on a needle that matches nothing or twice, which a
	 * "not found" answer would turn into a guard forbidding nothing.
	 *
	 * <p>What it asserts is that the entry gates on {@code reportsStandingChartAlerts()} and spells no
	 * switch of its own — which is the whole of the coupling worth pinning, because that predicate is
	 * also the {@code screened} value the response publishes, and what it MEANS is measured per switch
	 * by {@code StandingChartAlertsToggleContextTest}. Together the two say the flag a client reads is
	 * the condition the pass ran under. What it cannot see is a gate that calls the predicate AND
	 * short-circuits on something else first; mutate the body and read the failures.
	 */
	@Test
	public void theStandingEntryGatesOnThePredicateItPublishes() throws IOException {
		SourceScan scan = new SourceScan(RELATIVE_SOURCE);
		SourceScan.Region gate = scan.body(STANDING_ENTRY);

		assertTrue(scan.names(gate, "if (!reportsStandingChartAlerts()) {"),
				"the standing entry must gate on the predicate it publishes as `screened`, so the two "
						+ "cannot come apart (issue #280)");
		for (String switchOfItsOwn : new String[] { "ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS",
				"ChartSearchAiUtils.isDrugReferenceEnabled()" }) {
			assertFalse(scan.names(gate, switchOfItsOwn),
					"the standing entry must not re-spell " + switchOfItsOwn + " beside that predicate — "
							+ "a second spelling is how the gate and the published flag would diverge");
		}
	}

	/**
	 * The unbounded pass has ONE decider, and a second one cannot be added silently.
	 *
	 * <p>{@code reference/CLAUDE.md} states it as a directive — "the ONE unbounded pass is the standing
	 * surface, and there must never be a second" — and until this case nothing pinned it: the
	 * behavioural cases here would catch the CURRENT answer path going unbounded, but not a new
	 * answer-producing path added later, which is exactly what the directive is written against. The
	 * repo pins comparable directives with a count plus a body, and so does this.
	 *
	 * <p>Two namings, and they are different acts. {@code standingChartAlerts} DECIDES to run
	 * unbounded; {@code SubjectMatter}'s constructor merely translates the scope it was handed into the
	 * gate's own flag, and is reached by every caller. So the count alone would let a third site decide,
	 * and the bodies are what say which naming is which — this case moved the second needle once
	 * already, when the translation moved out of the factory and into the constructor to close a
	 * raw-boolean bypass, and it said so loudly rather than passing.
	 *
	 * <p>Over the source with comments and string literals blanked, so a {@code @link} to the constant
	 * is not a use of it. What it cannot see is a caller that reaches the unbounded gate without naming
	 * the constant — through a variable, or a scope handed down from elsewhere; nothing does that
	 * today, and the widest {@code validate} arity's own parameter is what a reader should follow.
	 */
	@Test
	public void nothingButTheStandingSurfaceDecidesToRunUnbounded() throws IOException {
		SourceScan scan = new SourceScan(RELATIVE_SOURCE);
		List<Integer> namings = scan.literalOffsets("SubjectMatterScope.UNBOUNDED");

		assertEquals(2, namings.size(),
				"SubjectMatterScope.UNBOUNDED must be named exactly twice in production — where the "
						+ "standing surface asks for it, and where SubjectMatter translates it — and was "
						+ "named at lines " + scan.linesOf(namings) + ". A third naming is a second "
						+ "unbounded pass, which is issue #143's over-reach (issue #280).");
		SourceScan.Region decider = scan.body(
				"StandingChartAlerts standingChartAlerts(PatientClinicalContext context) {");
		SourceScan.Region translator = scan.body(
				"private SubjectMatter(SubjectMatterScope scope, String question, String answer,");
		assertTrue(decider.contains(namings.get(0)),
				"the first naming must be the standing surface asking for the unbounded gate, and was at "
						+ "line " + scan.lineOf(namings.get(0)));
		assertTrue(translator.contains(namings.get(1)),
				"the second must be SubjectMatter translating the scope it was handed, and was at line "
						+ scan.lineOf(namings.get(1)) + " — a decider anywhere else is a second unbounded "
						+ "pass however the count reads");
	}
}
