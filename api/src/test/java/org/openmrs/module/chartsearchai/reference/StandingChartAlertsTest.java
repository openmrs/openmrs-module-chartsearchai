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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * The standing surface: "is this patient prescribed something her own chart contraindicates?", asked
 * of the chart rather than of a response (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/280">#280</a>).
 *
 * <p><b>What it is for.</b> Issue #143's active-order contraindication arm ran on every question, and
 * that was withdrawn — ADR Decision 79 carries the measurement. The arm is now bounded by
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
 * {@link #theStandingEntryGatesOnTheSharedTogglePredicate} and by
 * {@code StandingChartAlertsToggleContextTest}, which needs a real {@code Context} and so cannot live
 * here.
 *
 * <p><b>What no case here reaches, named rather than left to be discovered.</b> Nothing produces an
 * ALERT through the public {@code standingChartAlerts(Patient)} path end to end — that would need a
 * context-sensitive patient carrying both a real drug order and a real allergy the bundled dataset
 * relates, and the toggle class's public-entry case runs that path on the standard test patient, who
 * is prescribed nothing this dataset contraindicates, so it asserts the VERDICT and not a finding.
 * The fail-safe {@code catch} in that method is executed by nothing HERE — that is
 * {@code StandingChartAlertsToggleContextTest.aPassThatThrowsReportsTheChartAsNotScreenedRatherThanAsClean},
 * which fails the dataset read the pass makes. And every chart here passes a
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

	/**
	 * A chart whose allergy or condition read FAILED is not a screened chart, and must not be published
	 * as one.
	 *
	 * <p>{@code PatientClinicalContextBuilder} swallows such a failure into an EMPTY token set and,
	 * until issue #247 raised those catches to WARN, logged it at DEBUG — which core's shipped
	 * {@code log4j2.xml} discards, putting {@code org.openmrs} at WARN — so before this the endpoint
	 * answered {@code screened: true} with an empty array for a patient nobody had looked at. A role
	 * holding {@code AI Query Patient Data} without core's
	 * {@code Get Allergies} is exactly that role.
	 *
	 * <p>It is {@code reference/CLAUDE.md}'s "a chart the module could not read is not a chart that
	 * records nothing", met on the surface whose WHOLE payload can be empty. The fixture is
	 * {@code unreadableRecordsCtx}, whose token sets are empty for that reason and cannot be supplied —
	 * which is what stops this being an arrangement no production path reaches. It carries no orders
	 * either, so it is narrower than the real failure, where the order read succeeds; that shape is
	 * {@code unreadableRecordsCtxWithOrders}, used by the enrichment case below.
	 */
	@Test
	public void aChartWhoseRecordsCouldNotBeReadIsNotScreened() {
		DrugSafetyValidator.StandingChartAlerts standing = curatedValidator()
				.standingChartAlerts(DrugReferenceTestSupport.unreadableRecordsCtx(60, null));

		assertFalse(standing.isScreened(),
				"a chart the module could not read must not be published as a screened one");
		assertTrue(standing.getAlerts().isEmpty(),
				"and it states no findings, since it has none to state");
	}

	/**
	 * The value object cannot be made to state something it was not built to state.
	 *
	 * <p>Three shapes, all found by review against earlier drafts of this class and all reachable
	 * because its factories are public — they are public so the omod wire tests can build a payload
	 * fixture, {@code omod/pom.xml} declaring no api test-jar. A caller adding to what
	 * {@code notScreened()} returned got an unscreened result carrying a finding; a caller mutating the
	 * list it handed {@code screened} changed the object afterwards; and {@code screened(null)} first
	 * returned an object whose {@code getAlerts()} was null against a javadoc saying "never null", then
	 * threw.
	 */
	@Test
	public void aStandingResultCannotBeMadeToStateWhatItWasNotBuiltToState() {
		DrugSafetyValidator.StandingChartAlerts unscreened = DrugSafetyValidator.StandingChartAlerts
				.notScreened();
		assertThrows(UnsupportedOperationException.class,
				() -> unscreened.getAlerts().add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION,
						"Ibuprofen", "added by a caller")),
				"an unscreened result must not be able to acquire a finding");

		List<SafetyWarning> callers = new ArrayList<SafetyWarning>();
		callers.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "the one"));
		DrugSafetyValidator.StandingChartAlerts screened =
				DrugSafetyValidator.StandingChartAlerts.screened(callers);
		callers.clear();
		assertEquals(1, screened.getAlerts().size(),
				"the result must be the findings it was built with, not a view of a list the caller "
						+ "still holds");

		assertTrue(DrugSafetyValidator.StandingChartAlerts.screened(null).getAlerts().isEmpty(),
				"and no factory may hand back an object whose findings are null, which getAlerts's own "
						+ "contract forbids");
	}

	/**
	 * A patient the module was never given is not a screened chart.
	 *
	 * <p>{@code PatientClinicalContextBuilder} answers a null patient from an early return that
	 * performs no read at all, and the shorter constructors default both read stamps to {@code true} —
	 * right for a caller assembling a context by hand, wrong for the one path that reads nothing. Until
	 * this, {@code standingChartAlerts(null)} certified a patient that does not exist as screened and
	 * clear. The validator is a Spring bean and the endpoint is not its only possible caller.
	 */
	@Test
	public void aPatientTheModuleWasNeverGivenIsNotAScreenedChart() {
		DrugSafetyValidator.StandingChartAlerts standing = curatedValidator()
				.standingChartAlerts(PatientClinicalContextBuilder.build(null));

		assertFalse(standing.isScreened(),
				"a chart nothing was read for must not be published as a screened one");
		assertTrue(standing.getAlerts().isEmpty(), "and it states no findings");
	}

	/**
	 * Both chart-read stamps survive the enrichment {@code validate} applies to the context, so a
	 * reader on the far side of the copy is asking the flag the chart read actually set.
	 *
	 * <p><b>What this is NOT.</b> It is not a failure mode of the published {@code screened} verdict:
	 * the seam reads both stamps off the RAW builder output and decides that verdict BEFORE
	 * {@code validate} performs the enrichment, so a stamp the copy dropped could not move it. Three
	 * drafts said otherwise. The reader that does ask a stamp of an ENRICHED context is
	 * {@code DrugReferenceInjector}, through
	 * {@code statesTheChartsContraindicationReading} — where a dropped
	 * {@code contraindicationRecordsRead} is lost silently and fail-OPEN, the shorter constructors
	 * defaulting it to {@code true}, and the record goes back to asserting "not recorded for this
	 * patient" about a chart nobody read. That consumer is driven on the production path by
	 * {@code InjectedContraindicationPatientReadingTest.aStampTheEnrichmentDroppedWouldPutTheNegativeClaimBack}.
	 *
	 * <p>What THIS case is for is the copy itself, over BOTH stamps: {@code standingChartAlerts} asks
	 * for both, so it is the instrument here rather than the subject. It is no longer the only reader
	 * that does — issue #247 added {@code PatientClinicalContext.chartReadForSafety()}, which both
	 * surfaces now share, and published its answer on the ANSWER; this javadoc previously said
	 * {@code activeDrugOrdersRead} had no enriched-context reader, and pinning the copy before one
	 * arrived is what makes that arrival cheap. This module's own instructions record the identical
	 * copy shape costing
	 * two regressions on a different stamp.
	 *
	 * <p><b>The fixture is what makes it discriminating, and the obvious one is not.</b>
	 * {@code withReferenceNames} returns the context UNTOUCHED where no order resolves a reference
	 * entry, so a chart with no orders never reaches the copy — measured: written over
	 * {@code unreadableOrdersCtx}, this case stayed green under a mutation that dropped the stamp
	 * outright. It needs orders the dataset resolves AND a failed record read, which is
	 * {@code unreadableRecordsCtxWithOrders} and is a real shape: the builder reads orders and
	 * allergies in separate try blocks.
	 *
	 * <p><b>Both stamps, and the second was once called unreachable here.</b> That claim — that an
	 * order read which threw leaves no orders and so no reference names, so the copy is never taken
	 * with that stamp false — was falsified by running the real builder: its order loop sits inside ONE
	 * {@code try}, so a throw partway through leaves the orders already collected in place and the
	 * stamp false. {@code partiallyReadOrdersCtx} is that shape.
	 *
	 * <p>Driven through the real enrichment the real pass performs — {@code findForActiveOrders} then
	 * {@code withReferenceNames} — rather than by calling the copy constructor, so it measures the
	 * production route.
	 */
	@Test
	public void bothChartReadStampsSurviveTheEnrichmentThePassApplies() {
		DrugReferenceService service = DrugReferenceTestSupport.curatedService();
		Set<String> order = DrugReferenceTestSupport.set(DrugReferenceTestSupport.IBUPROFEN_ORDER);

		for (PatientClinicalContext unreadable : new PatientClinicalContext[] {
				DrugReferenceTestSupport.unreadableRecordsCtxWithOrders(order),
				DrugReferenceTestSupport.partiallyReadOrdersCtx(order) }) {
			List<DrugReference> orderEntries = service.findForActiveOrders(unreadable);
			assertFalse(orderEntries.isEmpty(),
					"precondition: an order must resolve a reference entry, or withReferenceNames returns "
							+ "the context untouched and this case cannot reach the copy at all");

			PatientClinicalContext enriched = service.withReferenceNames(unreadable, orderEntries);

			assertFalse(DrugReferenceTestSupport.validator(service).standingChartAlerts(enriched)
					.isScreened(),
					"the enriched copy must still carry the stamp the raw context set — read back here "
							+ "through the one reader that asks for both");
		}
	}

	/**
	 * @return {@code source} with block and line comments blanked, which is the minimum needed to tell
	 *         a CALL from a javadoc reference — several classes in this package name the standing pass
	 *         in prose and none of them calls it. {@link SourceScan} does this properly, and per FILE;
	 *         this walk is over many files with no regions, so it takes the crude form deliberately
	 *         rather than constructing a scan per class, which asserts a minimum file size this tree
	 *         does not meet everywhere.
	 */
	private static String withoutComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
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
	 * switch of its own — which is the coupling worth pinning, because the published
	 * {@code screened} verdict rests on that same predicate and then NARROWS it. <b>The two are not
	 * equal, and this case's name once said they were</b>: {@code StandingChartAlerts.isScreened()}
	 * additionally requires the chart reads and the pass completing, so it is false in cases the
	 * toggles are fine for. What the predicate MEANS is measured per switch by
	 * {@code StandingChartAlertsToggleContextTest}. What this cannot see is a gate that calls the
	 * predicate AND short-circuits on something else first; mutate the body and read the failures.
	 */
	@Test
	public void theStandingEntryGatesOnTheSharedTogglePredicate() throws IOException {
		SourceScan scan = new SourceScan(RELATIVE_SOURCE);
		SourceScan.Region gate = scan.body(STANDING_ENTRY);

		assertTrue(scan.names(gate, "if (!reportsStandingChartAlerts()) {"),
				"the standing entry must gate on the shared toggle predicate, which is what the published "
						+ "verdict then narrows (issue #280)");
		for (String switchOfItsOwn : new String[] { "ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS",
				"ChartSearchAiUtils.isDrugReferenceEnabled()" }) {
			assertFalse(scan.names(gate, switchOfItsOwn),
					"the standing entry must not re-spell " + switchOfItsOwn + " beside that predicate. "
							+ "This forbids the two the answer surface's own gate names, not every spelling "
							+ "a gate could take; what the entry DOES honour, per switch, is measured by "
							+ "StandingChartAlertsToggleContextTest");
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

		// And the SEAM itself, which is the cheaper way in. Counting the constant catches a second site
		// that asks for UNBOUNDED; it does not catch one that calls standingChartAlerts(context), which
		// is package-private and reachable from DrugReferenceInjector, DrugReferenceService and every
		// other class in this package. Measured by a review agent: folding a standing pass into the
		// public ANSWER entry — issue #143's over-reach reinstated on every response — spelled no
		// UNBOUNDED and left the whole api suite green.
		List<Integer> seamNamings = scan.literalOffsets("standingChartAlerts(");
		assertEquals(3, seamNamings.size(),
				"standingChartAlerts must be named exactly three times in production — the two "
						+ "declarations and the public entry's one call to the seam — and was named at "
						+ "lines " + scan.linesOf(seamNamings) + ". A fourth is a second caller of the "
						+ "unbounded pass (issue #280).");
		// Which of the three is the CALL is decided by where it sits, not by its index: a body() region
		// runs from the opening brace, so a declaration's own text is outside its body and only the
		// delegation lands inside one.
		int insideTheEntry = 0;
		for (Integer at : seamNamings) {
			assertFalse(decider.contains(at),
					"the seam must not call itself, and line " + scan.lineOf(at) + " is inside it");
			if (scan.body(STANDING_ENTRY).contains(at)) {
				insideTheEntry++;
			}
		}
		assertEquals(1, insideTheEntry,
				"exactly one naming must be the public entry delegating to the seam, and " + insideTheEntry
						+ " were inside its body");
	}

	/**
	 * The TWO ways a class in this package could reach the unbounded pass, neither of which the
	 * source scan above can see. Both members are package-private — the seam
	 * {@code standingChartAlerts(PatientClinicalContext)} and {@code SubjectMatterScope} alike — so
	 * {@code DrugReferenceInjector}, which already calls {@code validate}, could call either without
	 * appearing in a scan of one file.
	 *
	 * <p>The second needle is the one that was missing: the case above counts
	 * {@code SubjectMatterScope.UNBOUNDED} inside {@code DrugSafetyValidator.java} ONLY, and this walk
	 * searched for the seam's name ONLY, so a sibling handing the seven-argument {@code validate} the
	 * unbounded scope itself was seen by neither.
	 */
	private static final String[] UNBOUNDED_PASS_NEEDLES = { "standingChartAlerts",
			"SubjectMatterScope.UNBOUNDED" };

	/**
	 * And no OTHER production class reaches the unbounded pass at all — by the seam's name or by the
	 * scope constant. See {@link #UNBOUNDED_PASS_NEEDLES} for why those are the two ways in.
	 *
	 * <p>The case above reads one file, and a second decider anywhere else is the directive
	 * {@code reference/CLAUDE.md} states as "there must never be a second". Walks the whole api source
	 * tree rather than a list of files, so a class added later is covered without this case changing.
	 *
	 * <p>It FAILS on an empty walk: a guard that discovers its own subject returns the same clean
	 * result whether the subject was compliant or absent. What it still cannot see is a caller that
	 * reaches either without naming it — through a variable, or a scope handed down from elsewhere.
	 */
	@Test
	public void noOtherProductionClassReachesTheUnboundedPass() throws IOException {
		final List<String> naming = new ArrayList<String>();
		final int[] scanned = { 0 };
		Path root = ModuleSourceRoot.apiRoot().resolve("src/main/java");
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!file.toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				scanned[0]++;
				if (file.getFileName().toString().equals("DrugSafetyValidator.java")) {
					return FileVisitResult.CONTINUE;
				}
				String source =
						withoutComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				for (String needle : UNBOUNDED_PASS_NEEDLES) {
					if (source.contains(needle)) {
						naming.add(root.relativize(file) + " names " + needle);
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});

		assertTrue(scanned[0] > 50, "only " + scanned[0] + " source files were walked under " + root
				+ "; a guard that reads nothing forbids nothing");
		assertEquals(Collections.<String> emptyList(), naming,
				"only DrugSafetyValidator may reach the unbounded pass — a caller of the seam or a "
						+ "second site asking for the unbounded scope is a second unbounded pass, and the "
						+ "count in the case above sees neither: the seam is package-private, and that "
						+ "count reads DrugSafetyValidator.java alone (issue #280)");
	}
}
