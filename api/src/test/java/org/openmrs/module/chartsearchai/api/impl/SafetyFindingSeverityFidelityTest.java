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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnstatedFindingSeverity;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Issue #337, round three: the answer states a deterministic safety finding and drops the RATING the
 * module wrote into it. Measured live on a RefApp 3.7.1 standalone against the bundled knowledge
 * base, three runs byte-identical — five interaction findings enumerated flat in one clause, no
 * rating stated for any of them, two of them Major.
 *
 * <p>Nothing could see it, and correctly so. {@code ReferenceProseFidelityCheck} reports a
 * SUBSTITUTION inside a long reproduction and that answer reproduced nothing;
 * {@code unfaithfullyRenderedCitations} read {@code []} because the check ran and found no
 * divergence, not because it looked. That carve-out is right for that check — its own javadoc names
 * the residue — and this file pins the half of the residue a rating makes deterministic.
 *
 * <p><b>The arrangement is MIXED-severity, and that is load-bearing rather than incidental.</b> The
 * sibling {@link ActiveOrderCitationFidelityTest}'s partner list rates every finding Major, so a
 * case built on it cannot tell "this record's own rating" from "any rating word at all", and a
 * carrier replaced by the constant {@code "Major"} would stay green on it. The pinned DDInter
 * excerpt rates Clarithromycin's partners across all four classes, so one arrangement here yields
 * Major, Moderate and Minor findings together — asserted in {@link #setUp()}, never assumed — and
 * {@link #anAnswerStatingOneRatingAndNotTheOthersReportsOnlyTheOthers} is what that buys.
 *
 * <p><b>Every case that names a published entry names its RATING too, since issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/387">#387</a></b> — the
 * cases whose claim is an EMPTY statement or none still assert just that. The key published a bare
 * index list until then, and the mixed arrangement above is what makes the pairing checkable at
 * all: {@link #statementsFor} builds the expectation from the ratings the real injector
 * wrote, not from a word this file chose. Mutate the pairing and read the failures — a constant
 * rating and a pairing shifted by one each redden cases here, and on a single-rating arrangement
 * neither could. What this file does NOT hold is which SOURCE the carrier read the rating from: the
 * chip's value and the record's agree except on an operator dataset's padding, so that substitution
 * is caught by {@code SafetyFindingSeverityCarriedContextTest} and not by the spread here.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration over a chart the real {@link PatientChartSerializer} rendered and the real
 * {@code DrugSafetyValidator} &rarr; {@code injectRecords} &rarr; {@code renderFinding} chain
 * injected findings into. Only the model is stubbed: answer prose is not reproducible on a live
 * engine, and the answer is the one variable this check is about. What the injector WRITES onto each
 * finding, and which ratings it withholds, is {@code SafetyFindingSeverityCarriedContextTest}'s.
 */
public class SafetyFindingSeverityFidelityTest {

	/** The ticket's own question, on the ticket's own drug. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	/** Clarithromycin's partners in the pinned excerpt, chosen to span three rating classes:
	 *  Simvastatin and Digoxin are Major, Sertraline Moderate, Omeprazole Minor. The spread is the
	 *  point — {@link #setUp()} asserts it rather than trusting this comment. */
	private static final String[] PARTNERS = { "Simvastatin", "Digoxin", "Sertraline", "Omeprazole" };

	private static final String[] PARTNER_ATC = { "C10AA01", "C01AA05", "N06AB06", "A02BC05" };

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported" assertion,
	 *  so no other class's WARN can stand in for this check's. */
	private static final String CHECK = SafetyFindingSeverityFidelityCheck.class.getName();

	/** The package, for every assertion whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously ({@link LogCapture}'s javadoc), so those cases capture the package instead, where
	 *  {@code LlmInferenceService}'s own [timing] INFO line proves the capture is live. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private PatientChart chart;

	private TestableService service;

	/** Every injected finding that carries a rating, by citation index, in chart order — read off
	 *  what production wrote, never off a partner name or a severity this file chose. */
	private Map<Integer, String> ratedFindings;

	@BeforeEach
	public void setUp() {
		chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
				setOf(PARTNERS), setOf(PARTNER_ATC));
		ratedFindings = new LinkedHashMap<Integer, String>();
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (mapping.getFindingSeverity() != null) {
				ratedFindings.put(Integer.valueOf(mapping.getIndex()), mapping.getFindingSeverity());
			}
		}
		assertTrue(new LinkedHashSet<String>(ratedFindings.values())
				.containsAll(Arrays.asList("Major", "Moderate", "Minor")),
				"the premise every case below rests on: the real pipeline injects findings spanning "
						+ "three rating classes, so an answer stating one rating can be told from an "
						+ "answer stating all of them. Ratings were: " + ratedFindings);
		service = newService(chart);
	}

	@Test
	public void theTicketsOwnAnswerShapeReportsEveryFindingWhoseRatingItDropped() {
		// The reported shape, verbatim in structure: the findings enumerated flat in one clause,
		// each with its citation, and no rating word anywhere in the prose. The ticket's own comment
		// established that absence programmatically rather than by eye, and this is that answer.
		service.setLlmProvider(answering(enumerationCiting(ratedFindings.keySet())));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			List<String> markers = new ArrayList<String>();
			for (Integer index : ratedFindings.keySet()) {
				markers.add("[" + index + "]");
			}
			markers.add("patient=1");
			assertTrue(warnStating(capture, markers.toArray(new String[0])),
					"every finding whose rating the answer dropped must be reported in one line, "
							+ "carrying the patient so a maintainer reading a log with concurrent "
							+ "requests in it can reconstruct it. Captured: " + capture.describeAll());
			assertEquals(statementsFor(ratedFindings.keySet()),
					answer.getUnstatedFindingSeverities(),
					"and the same citations must reach the wire, each carrying its finding's own "
							+ "rating — the citation and the rating, never a word of either text");
		}
	}

	@Test
	public void anAnswerStatingOneRatingAndNotTheOthersReportsOnlyTheOthers() {
		// The discriminator the mixed arrangement buys, and the case this file exists for. An answer
		// that states Major and nothing else reports exactly the findings rated something else. It
		// reddens two mutations a single-rating arrangement cannot see: a check asking "does the
		// answer state ANY rating", and a carrier that writes one constant rating for every finding.
		service.setLlmProvider(answering("Major interactions were found. "
				+ enumerationCiting(ratedFindings.keySet())));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(indexesRatedOtherThan("Major")),
					answer.getUnstatedFindingSeverities(),
					"exactly the findings whose OWN rating the answer never states are reported, "
							+ "each carrying that rating. "
							+ "Ratings were: " + ratedFindings + "; captured: " + capture.describeAll());
			for (Integer major : indexesRated("Major")) {
				assertFalse(warnStating(capture, "[" + major + "]"),
						"and a finding whose rating the answer DOES state must not be reported, or "
								+ "the check cannot tell a carried rating from a dropped one. "
								+ "Captured: " + capture.describeAll());
			}
		}
	}

	@Test
	public void anAnswerCarryingEveryRatingIsSilentAndTheMeasurementIsPublishedAsNone() {
		// The other half of the pair above: that one fails if the check accuses nothing, this one
		// fails if it accuses a faithful answer. Neither alone discriminates.
		StringBuilder prose = new StringBuilder();
		for (Map.Entry<Integer, String> finding : ratedFindings.entrySet()) {
			prose.append("Clarithromycin interacts with active order X — ")
					.append(finding.getValue()).append(" [").append(finding.getKey()).append("]. ");
		}
		service.setLlmProvider(answering(prose.toString().trim()));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an answer that carries every finding's rating is the shape this check exists to "
							+ "leave alone. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedFindingSeverities().isEmpty(),
					"and an empty list is a measurement of none, which is what a client reads to know "
							+ "the check ran");
		}
	}

	@Test
	public void theRatingIsMatchedCaseInsensitivelyAndThroughMarkdownEmphasis() {
		// A model writes "major" and "**Moderate**" as readily as the module's own casing, and a
		// check that reported those would cry wolf on correct prose. Every rating this arrangement
		// carries is stated in a form that is not the module's own spelling.
		StringBuilder prose = new StringBuilder("Findings: ");
		for (String rating : new LinkedHashSet<String>(ratedFindings.values())) {
			prose.append("**").append(rating.toLowerCase()).append("**, ");
		}
		prose.append("as follows. ").append(enumerationCiting(ratedFindings.keySet()));
		service.setLlmProvider(answering(prose.toString()));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a lower-cased rating inside markdown emphasis is the rating stated. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedFindingSeverities().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aLongerWordMerelyContainingTheRatingDoesNotCountAsStatingIt() {
		// The other side of the boundary rule. Without it "the majority of her medications" would
		// silence every Major finding in the answer, which is the shape a substring test fails on.
		service.setLlmProvider(answering("The majority of her medications interact. "
				+ enumerationCiting(indexesRated("Major"))));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(indexesRated("Major")),
					answer.getUnstatedFindingSeverities(),
					"\"majority\" is not the word \"Major\". Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aWordENDINGInTheRatingDoesNotCountAsStatingItEither() {
		// The other half of the boundary, and it was unpinned until a review pass mutated it: the
		// case above only holds the RIGHT side, since "majority" extends past the rating. Nothing
		// held the LEFT side, so replacing it with `true` left the whole build green and the next
		// change could have deleted it for free. "immoderate" is the ordinary English word that
		// ends in one of these ratings.
		List<Integer> moderate = indexesRated("Moderate");
		assertFalse(moderate.isEmpty(), "the premise: this arrangement raises a Moderate finding");
		service.setLlmProvider(answering("Her response to therapy has been immoderate. "
				+ enumerationCiting(moderate)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(moderate), answer.getUnstatedFindingSeverities(),
					"\"immoderate\" is not the word \"Moderate\". Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aRatingWithADIGITAgainstItIsNotTheWordEither() {
		// The third axis of the same boundary, and the third time this slice has found one half of it
		// unpinned: `isLetterOrDigit` at both ends can be weakened to `isLetter` with the whole
		// reactor green. The digit half is what keeps this scan in step with
		// `DrugReference.boundedTokenIndex`, whose own javadoc argues the digit rule from measured
		// data — so weakening it silently ends the equivalence the shared-scan argument rests on.
		// It fails toward SILENCE, which is the direction hardest to notice in production.
		// BOTH edges, and that is the point rather than thoroughness: the first version of this case
		// glued the digit only on the LEFT, so mutating just the trailing `isLetterOrDigit` stayed
		// green — a fourth instance of the same half-pinned shape, found by a fourth review pass.
		// `boundedTokenIndex`'s own argument for the digit rule uses a TRAILING example
		// (`Aspirin81mg`), which is the side that was uncovered.
		List<Integer> major = indexesRated("Major");
		service.setLlmProvider(answering("Reviewed against the 2024Major formulary update, "
				+ "revision Major2024. " + enumerationCiting(major)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(major), answer.getUnstatedFindingSeverities(),
					"a rating with a digit glued to either end of it is not the word. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aRatingBothInsideALongerWordAndStatedOnItsOwnIsStated() {
		// The composition of the two boundary cases, and the shape most likely in real prose: an
		// answer that says "majority" somewhere AND states the rating properly. Nothing covered it,
		// so a scan that judged only the FIRST occurrence of the needle and stopped stayed green —
		// and under that mutant this correct answer is reported as having dropped its rating.
		List<Integer> major = indexesRated("Major");
		service.setLlmProvider(answering("The majority of her medications interact. "
				+ enumerationCiting(major) + " Each of those is Major."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(warnedByThisCheck(capture),
					"the rating IS stated, after an earlier word that merely contains it. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedFindingSeverities().isEmpty(),
					"and nothing is published against a correct answer");
		}
	}

	@Test
	public void theStatementIsInTHEANSWERSCitationOrderAndNotSortedByIndex() {
		// The published contract says "citation order", and every other case here happens to cite in
		// ascending index order — so a change that sorted the list would have shipped green while
		// contradicting the accessor. Cite the findings backwards and the statement must come back
		// backwards.
		List<Integer> descending = new ArrayList<Integer>(ratedFindings.keySet());
		Collections.reverse(descending);
		service.setLlmProvider(answering(enumerationCiting(descending)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(descending), answer.getUnstatedFindingSeverities(),
					"the order is the order the answer cites them in, not ascending index order. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitedChartRecordIsNeverAccused() {
		// A chart record carries no rating, so there is nothing for an answer to have dropped. The
		// citation beside the finding is the ordinary shape of one of these sentences and it must
		// pass through untouched.
		int order = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		Integer finding = indexesRated("Major").get(0);
		service.setLlmProvider(answering("Clarithromycin interacts with active order Simvastatin ["
				+ order + "] [" + finding + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(Collections.singletonList(finding)),
					answer.getUnstatedFindingSeverities(),
					"the finding is reported and the chart record beside it is not. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aBlankAnswerCarryingResolvedCitationsIsSilent() {
		// A degenerate output, and a REACHABLE one: extractCitedReferences resolves the structured
		// citations array for a blank answer on purpose — its javadoc calls that "the absence of an
		// answer" — so this check can be handed cited findings with no prose. Such an answer states
		// no rating and nothing else either; reporting it would make this the only check in the
		// family that accuses a degenerate output. Delete the blank arm and this reddens.
		Integer finding = indexesRated("Major").get(0);
		service.setLlmProvider(new StubProvider("   ",
				Collections.singletonList(finding)));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(warnedByThisCheck(capture),
					"a blank answer is not a dropped rating. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedFindingSeverities().isEmpty(),
					"and the measurement is a measurement of none, not a report");
			assertFalse(answer.getReferences().isEmpty(),
					"the premise: the structured array really did resolve for this blank answer, so "
							+ "the check was handed a cited finding and chose to stay silent");
		}
	}

	@Test
	public void searchStreaming_reportsItOnThePrimaryProductionPathToo() {
		// /search/stream is the path users hit: a check wired only into search() would be absent from
		// production traffic while every non-streaming case here stayed green.
		Integer finding = indexesRated("Major").get(0);
		service.setLlmProvider(answering(enumerationCiting(Collections.singletonList(finding))));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "[" + finding + "]"),
					"the streaming path must run the same check. Captured: " + capture.describeAll());
			assertEquals(statementsFor(Collections.singletonList(finding)),
					answer.getUnstatedFindingSeverities(),
					"and the answer this method RETURNS must carry the measurement");
		}
	}

	@Test
	public void searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		// The early `done` of the async-grounding path is built BEFORE this check runs, so it has no
		// measurement to state — and null says exactly that, where an empty list would tell a client
		// the findings had been examined and found faithful.
		//
		// The null assertion alone does NOT establish it: production hands the early answer an
		// explicit null in that argument, so it states null wherever the check runs. The log snapshot
		// at handoff is what makes moving the check above the handoff redden this case.
		Integer finding = indexesRated("Major").get(0);
		service.setLlmProvider(answering(enumerationCiting(Collections.singletonList(finding))));
		final List<List<UnstatedFindingSeverity>> early =
				new ArrayList<List<UnstatedFindingSeverity>>();
		final List<List<String>> loggedByHandoff = new ArrayList<List<String>>();

		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { },
					reasoning -> { }, citations -> { },
					ungrounded -> {
						early.add(ungrounded.getUnstatedFindingSeverities());
						loggedByHandoff.add(capture.describeAll());
					});

			assertEquals(1, early.size(), "the early-done consumer must have fired");
			assertEquals(null, early.get(0),
					"the check runs after the user-visible handoff, so the early answer states no "
							+ "measurement");
			assertTrue(capture.describeAll().toString().contains("states no rating"),
					"the capture must have received the check's own WARN by the end, or the negative "
							+ "below is satisfied by a capture that was never attached — the vacuity "
							+ "this file's PACKAGE constant names. Captured: " + capture.describeAll());
			assertFalse(loggedByHandoff.get(0).toString().contains("states no rating"),
					"and the check must not have RUN by then — moving it ahead of the handoff puts a "
							+ "comparison in front of the event a user sees. Captured at handoff: "
							+ loggedByHandoff.get(0));
			assertEquals(statementsFor(Collections.singletonList(finding)),
					answer.getUnstatedFindingSeverities(),
					"and the answer this method RETURNS carries it");
		}
	}

	@Test
	public void aCheckThatThrowsIsReportedAndTheAnswerStillReturns() {
		// The guard exists so a diagnostic can never break a clinical answer. The mechanism has to be
		// a read this check makes and nothing before it does: extractCitedReferences reads
		// getResourceType(), ClassCodeFidelityCheck and the prose check read getText(), all earlier,
		// so overriding one of those throws somewhere else. getFindingSeverity() is read by nothing
		// on this path before this check.
		PatientChart throwing = new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Safety finding: Clarithromycin" + System.lineSeparator(),
				Arrays.<RecordMapping> asList(new RecordMapping(1,
						ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING, "finding-uuid-throwing",
						null, "Safety finding: Clarithromycin") {

					@Override
					public String getFindingSeverity() {
						throw new IllegalStateException("finding rating unavailable");
					}
				}),
				Collections.<Integer> emptyList());
		TestableService onThrowing = newService(throwing);
		onThrowing.setLlmProvider(answering("Clarithromycin interacts with something [1]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = onThrowing.search(patient(), QUESTION);
			assertTrue(answer.getAnswer().contains("[1]"),
					"the answer must come back whatever the check does; got: " + answer.getAnswer());
			assertTrue(warnStating(capture, "Finding-severity check failed"),
					"and the check's own failure must not be silent. Captured: "
							+ capture.describeAll());
			assertEquals(null, answer.getUnstatedFindingSeverities(),
					"a failed check states NO measurement, which is not a measurement of none");
		}
	}

	@Test
	public void theStatementCarriesEachFindingsOwnRatingBesideItsCitation() {
		// Issue #387. Before it, this key published the citation alone and the rating was dropped on
		// the way out, although the check holds the `citation -> rating` map it decides FROM. A
		// client could not rebuild the pairing: the chips carry every rating and no citation index,
		// and on the issue's own reproduction all five findings were (interaction, Clarithromycin),
		// so (type, drug) identified none of them.
		//
		// This case rests on the MIXED arrangement setUp() asserts, and that is what makes it a
		// discriminator rather than a shape check: the ratings really do differ between findings, so
		// a carrier writing one constant rating and a pairing shifted by one both redden, and on a
		// single-rating arrangement neither could. The class javadoc names what the spread does not
		// reach.
		service.setLlmProvider(answering(enumerationCiting(ratedFindings.keySet())));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(statementsFor(ratedFindings.keySet()),
					answer.getUnstatedFindingSeverities(),
					"each published entry must carry the rating of the finding its citation names, "
							+ "read off what production injected rather than off a word this file "
							+ "chose. Ratings were: " + ratedFindings + "; captured: "
							+ capture.describeAll());
			// And again through the ACCESSORS, which is not belt and braces. The comparison above
			// goes through UnstatedFindingSeverity.equals, so on its own it pins the rating only for
			// as long as that method reads the rating — measured, before the two assertions below
			// existed, weakening equals to compare the citation alone left every assertion in this
			// file green. Reading the field back is what makes the severity pinned by this file
			// rather than by that method; the equality assertions further down then pin the method.
			for (UnstatedFindingSeverity published : answer.getUnstatedFindingSeverities()) {
				assertEquals(ratedFindings.get(Integer.valueOf(published.getCitation())),
						published.getRating(),
						"the entry for citation [" + published.getCitation() + "] states the rating "
								+ "its own record carries");
				// And the WARN carries the same pair, which was unpinned until here: no other case in
				// this file asserts a rating in the log, so a check that logged the citation alone
				// left the whole build green — measured, by logging just the citation.
				assertTrue(warnStating(capture,
						"[" + published.getCitation() + "] " + published.getRating()),
						"the maintainer's channel states the rating beside the citation too, or a "
								+ "reader triaging this cannot tell a dropped Major from a dropped "
								+ "Minor. Captured: " + capture.describeAll());
				// And the value equality this type defines is observed HERE and nowhere else:
				// measured, `equals` returning true unconditionally left the whole build green, so
				// the type carried published API that nothing exercised. Asked of the entry
				// PRODUCTION published rather than of a pair built here, so it stays a statement
				// about the real answer. Both fields have to matter — a consumer keying on these,
				// which is what defining equality invites, would otherwise collapse a Major entry
				// and a Moderate one for the same citation.
				assertEquals(
						new UnstatedFindingSeverity(published.getCitation(), published.getRating()),
						published, "an entry with the same citation and rating IS this one");
				assertEquals(
						new UnstatedFindingSeverity(published.getCitation(), published.getRating())
								.hashCode(),
						published.hashCode(), "and equal entries agree on hashCode, as the contract "
								+ "between the two requires");
				assertNotEquals(
						new UnstatedFindingSeverity(published.getCitation(),
								published.getRating() + "x"),
						published, "an entry differing only in its RATING is not this one");
				assertNotEquals(
						new UnstatedFindingSeverity(published.getCitation() + 1,
								published.getRating()),
						published, "nor is one differing only in its CITATION");
			}
		}
	}

	/** An answer that names each cited record in one flat clause with no rating anywhere — the
	 *  ticket's own shape, and the one the round-two check cannot see because it reproduces nothing
	 *  of the records it cites. */
	private static String enumerationCiting(Iterable<Integer> indexes) {
		StringBuilder prose = new StringBuilder("No — Clarithromycin should not be started");
		String separator = ": ";
		for (Integer index : indexes) {
			prose.append(separator).append("Clarithromycin interacts with an active order [")
					.append(index).append("]");
			separator = ", ";
		}
		return prose.append(".").toString();
	}

	private List<Integer> indexesRated(String rating) {
		List<Integer> out = new ArrayList<Integer>();
		for (Map.Entry<Integer, String> finding : ratedFindings.entrySet()) {
			if (rating.equals(finding.getValue())) {
				out.add(finding.getKey());
			}
		}
		return out;
	}

	private List<Integer> indexesRatedOtherThan(String rating) {
		List<Integer> out = new ArrayList<Integer>();
		for (Map.Entry<Integer, String> finding : ratedFindings.entrySet()) {
			if (!rating.equals(finding.getValue())) {
				out.add(finding.getKey());
			}
		}
		return out;
	}

	/** The statement production must publish for {@code indexes}: each citation paired with the
	 *  rating the REAL injector wrote onto that record, in the order given. Read off
	 *  {@code ratedFindings}, which {@link #setUp()} builds from what the pipeline produced — never
	 *  from a rating this file chose, or the assertion would pin the test's own opinion. */
	private List<UnstatedFindingSeverity> statementsFor(Iterable<Integer> indexes) {
		List<UnstatedFindingSeverity> out = new ArrayList<UnstatedFindingSeverity>();
		for (Integer index : indexes) {
			out.add(new UnstatedFindingSeverity(index.intValue(), ratedFindings.get(index)));
		}
		return out;
	}

	private int indexOfType(String resourceType) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceType.equals(mapping.getResourceType())) {
				return mapping.getIndex();
			}
		}
		throw new IllegalStateException("no " + resourceType + " record in: " + chart.getText());
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** @return whether one WARN carries every one of {@code required} */
	private static boolean warnStating(LogCapture capture, String... required) {
		return capture.hasMessageAt(Level.WARN, required);
	}

	/** @return whether THIS check warned, for a case that captures the package — so the pipeline's
	 *          own INFO line proves the capture is live — but claims silence only of this check.
	 *
	 *          <p>It asks for this check's own WARN by its wording rather than by composing two
	 *          package-wide questions. The composed form is what this helper first was, and it was
	 *          wrong for its name: "something warned AND nothing but this check warned" is false
	 *          whenever a NEIGHBOUR warns too, so the negative it serves would have passed on a
	 *          response where this check reported and a sibling reported beside it. */
	private static boolean warnedByThisCheck(LogCapture capture) {
		return capture.hasMessageAt(Level.WARN, "states no rating for cited finding");
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static StubProvider answering(String answer) {
		return new StubProvider(answer);
	}

	/** The base chart, rendered by the REAL serializer: the patient's own drug orders, so a citation
	 *  of one can be shown not to be accused. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Simvastatin 20mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Digoxin 125mcg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	/** The same seam the sibling fidelity tests use, and defined here for the same reason they each
	 *  define their own: these are private harnesses, not a shared one. */
	private TestableService newService(PatientChart served) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing a shorter one instead leaves this stub
			// INERT — production would not reach it — which is why this names both parameters. One
			// sibling of this file has drifted onto a shorter overload and passes for another reason.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private final List<Integer> citations;

		private StubProvider(String answer) {
			this(answer, Collections.<Integer> emptyList());
		}

		/** The structured-citations arity: the one arrangement that reaches this check with cited
		 *  records and no prose to read them in. */
		private StubProvider(String answer, List<Integer> citations) {
			this.answer = answer;
			this.citations = citations;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, citations);
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			return canned();
		}
	}
}
