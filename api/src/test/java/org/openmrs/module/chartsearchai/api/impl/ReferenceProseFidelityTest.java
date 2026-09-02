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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #337: the model reproduces a deterministic safety finding's mechanism and then writes its
 * own words inside the sentence it was copying, deleting the hazard. Measured live on the 3.7.1
 * standalone: the chip said <em>"…oxidizing agents that can also <b>induce methemoglobinemia</b>
 * such as antimalarials…"</em> and the answer, citing that finding, said <em>"…oxidizing agents
 * that can also <b>increase the risk</b>"</em> — the risk of what is no longer in the sentence a
 * clinician reads. A second capture deleted <em>"aminoglycoside antibiotics"</em> from a botulinum
 * toxin warning whose partner IS an aminoglycoside.
 *
 * <p>Nothing in the pipeline could see it, and the README claimed something could. A
 * reference-group citation skips Tier-2 entailment entirely and Tier-1 cosine barely moves when one
 * phrase inside a long recitation changes; {@link ClassCodeFidelityCheck} compares one token shape
 * and says nothing about prose; the {@code safetyWarnings} chips are a parallel list nothing
 * reconciles against the answer.
 *
 * <p>What this file pins is the deterministic check that closes the mechanism gap: when an answer
 * REPRODUCES a stretch of a cited reference record and then, inside the same sentence on both
 * sides, states different words, that is reported at WARN. Everything else is silence — an answer
 * that reproduces nothing, one that stops copying and ends its sentence, one that reproduces a
 * record sentence whole and moves on, and one whose continuation ANOTHER cited record explains.
 * The answer prose is never rewritten and nothing reaches the wire.
 *
 * <p>Every case runs through the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration, and the ones about the defect run over REAL production-rendered records: the cited
 * finding and the drug-reference record beside it are what the real validate → injectRecords →
 * renderFinding chain produces off the bundled DDInter excerpt for a patient on tramadol asked
 * about sertraline, and their canned answers are sliced out of that record's own text at run time
 * rather than transcribed, so the arrangement cannot drift from the dataset. FOUR cases ASSEMBLE
 * their record instead — the chart-record scope case, the two pooling legs and the unreadable-record
 * case — because the shapes they need do not occur in a sixteen-entry excerpt; each says so where it
 * stands, and the check is a pure function of an answer and a record's TEXT, so an assembled operand
 * is the right one for them. The model is stubbed because answer prose is not reproducible on a live
 * engine; the chart builder, the injector and the validator are stubbed too, as in the sibling
 * suite, so the one variable under test is the answer.
 */
public class ReferenceProseFidelityTest {

	/** A Major interaction whose mechanism is five sentences long — the shape the defect needs.
	 *  {@code safetyFindingIn} takes the FIRST injected finding and asserts nothing about how many
	 *  there are, so a case here is about whichever finding this arrangement raises first. */
	private static final String QUESTION = "is it safe to give sertraline?";

	private static final String ACTIVE_DRUG = "tramadol";

	private static final String ACTIVE_ATC = "N02AX02";

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported"
	 *  assertion, so no other class's WARN can stand in for the check's. */
	private static final String CHECK = ReferenceProseFidelityCheck.class.getName();

	/** The package, for the assertions whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously (LogCapture's javadoc); the package capture also receives
	 *  {@code LlmInferenceService}'s own [timing] INFO line, so the capture can be shown live.
	 *
	 *  <p><b>It spans the sibling check too.</b> {@code ClassCodeFidelityCheck} logs into this same
	 *  package from this same {@code search()} call, so every silence case here would also redden on
	 *  one of ITS reports. That is inert today because no canned answer below states an ATC-shaped
	 *  token its cited records do not — the ones sliced from a real record carry only that record's
	 *  own, and the assembled ones carry none at all. Write a literal code into any of them and the
	 *  failure message will be about class codes.
	 *  {@code ClassCodeFidelityTest} carries the same note in the other direction. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private TestableService service;

	private PatientChart chart;

	private RecordMapping finding;

	private RecordMapping reference;

	/** The finding record's own text, split on whitespace — the slicing unit every canned answer
	 *  below is built from. Whitespace splitting is ARRANGEMENT, not a second copy of the check's
	 *  own tokeniser: it keeps the punctuation, which is what carries the sentence boundaries the
	 *  check reads, and a canned answer assembled from a lower-cased word list would carry none. */
	private String[] recordTokens;

	@BeforeEach
	public void setUp() {
		chart = DrugReferenceTestSupport.injectedSafetyFindingChart(QUESTION, ACTIVE_DRUG, ACTIVE_ATC);
		finding = DrugReferenceTestSupport.safetyFindingIn(chart);
		reference = DrugReferenceTestSupport.injectedReference(chart);
		recordTokens = finding.getText().split("\\s+");
		// The premises, asserted rather than assumed — every case below slices this text.
		assertTrue(finding.getText().contains(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"the premise: the real injected finding ends with the appended strength clause, which "
						+ "is what the record-sentence exit has to keep out of the comparison. Was: "
						+ finding.getText());
		assertTrue(reference.getText().contains(mechanismTail()),
				"the premise: the drug-reference record beside it carries the SAME mechanism string, "
						+ "which is what makes the pooling case below reachable. Was: "
						+ reference.getText());
		assertTrue(recordTokens.length > 120,
				"the premise: the mechanism is long enough for two runs of eight words with a "
						+ "substitution between them. Was " + recordTokens.length + " tokens");
		service = newService(chart);
	}

	@Test
	public void search_shouldReportAnAnswerThatSubstitutesItsOwnWordsInsideACopiedSentence() {
		// #337's own shape: the answer reproduces the finding verbatim and then, without ending the
		// sentence, writes something else where the record names the hazard.
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"an answer that reproduces a deterministic record and then states different words "
							+ "inside the sentence it was copying has degraded citable safety prose and "
							+ "must be reported. Captured: " + capture.describeAll());
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]", "patient=1"),
					"the WARN has to carry the record whose prose was degraded AND the patient, or a "
							+ "maintainer reading a log with concurrent requests in it cannot "
							+ "reconstruct it. Captured: " + capture.describeAll());
			// The two numbers are the only handle a maintainer has for locating the divergence in a
			// record the line deliberately does not quote, and neither is pinned by the assertion
			// above. This answer starts at the record's own first word, so the record position the
			// line names must be one past the count it reports — which holds only while the count is
			// the run's length and the position counts from one.
			Matcher numbers = Pattern.compile(
					"reproduces (\\d+) words .*? continues at its word (\\d+), counting from one")
					.matcher(capture.messagesAt(Level.WARN).get(0));
			assertTrue(numbers.find(), "the WARN must state both numbers in the documented wording. "
					+ "Captured: " + capture.describeAll());
			assertEquals(Integer.parseInt(numbers.group(1)) + 1, Integer.parseInt(numbers.group(2)),
					"a reproduction starting at the record's first word continues one word past its "
							+ "length. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void search_shouldStaySilentWhenTheAnswerReproducesTheRecordFaithfully() {
		// The other half of the pair above, on the same arrangement: that one fails if the check
		// never runs, this one fails if it accuses a faithful answer. Neither alone discriminates.
		service.setLlmProvider(answering(copiedThrough("may") + " potentiate the risk of serotonin "
				+ "syndrome [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a faithful recitation must stay quiet, or the check is noise every install learns "
							+ "to ignore. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aFaithfulFullMechanismQuotationIsNotReported() {
		// The shape a clinician's answer takes when the model gets this RIGHT: the whole mechanism
		// reproduced verbatim, a clause welded on, and both the finding and the drug-reference record
		// cited — which is what the live capture on #337 did. It must be silent, and it is the answer
		// this check would be worthless without, since every arm of the design trades a report away
		// to keep quiet about it.
		//
		// Named for the ANSWER and not for a leg, on purpose. It was called
		// "aContinuationAnotherCitedRecordExplains…" and pinned no such thing: a reviewer's mutation
		// sweep showed the record-sentence exit is what keeps it quiet, the reference record's ".); "
		// item seam carrying a terminator that ChartSearchAiUtils.mayEndASentence reads. The two
		// pooling legs have cases of their own below.
		service.setLlmProvider(answering(mechanismWithoutItsFinalStop()
				+ ", so avoid coadministration [" + reference.getIndex() + "], ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an answer that reproduced the whole mechanism faithfully and welded its own clause "
							+ "on states nothing the record does not, and this is the answer the whole "
							+ "design trades reports away to keep quiet about. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatEndsTheSentenceWhereItStoppedCopyingIsNotReported() {
		// Truncation, not substitution. The model stopped reproducing and closed its sentence; it
		// stated nothing the record does not. That under-reports #337's weaker cousin — a hazard
		// dropped by stopping early — and is the safe direction for a check whose failure mode is
		// being ignored. Reporting it would fire on every answer that quotes a clause of a
		// 150-word mechanism, which is most of them.
		service.setLlmProvider(answering(copiedThrough("may") + " [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"stopping is not substituting. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aRecordSentenceReproducedWholeAndFollowedByTheAnswersOwnIsNotReported() {
		// The record-sentence exit, on its own: the copy ends where a sentence of the RECORD ends,
		// and the answer carries on inside its own sentence. The model reproduced a unit of the
		// record faithfully; what it writes next is its own comment, not a rewriting of what it
		// was copying.
		//
		// This is also the exit that keeps the appended strength clause out of the check at the
		// seam: renderFinding runs the detail through DrugSafetyValidator.endSentence whenever a
		// clause is appended, so " This finding is a reason to withhold it." always opens a new
		// record sentence. It covers the SEAM and not the clause's interior — see the check's own
		// javadoc for the residue.
		// The trailing stop comes off for the reason the record-exhausted case gives: leave it on and
		// the answer-side exit stands in, and the case is green under either leg.
		service.setLlmProvider(answering(withoutTrailingStop(copiedThrough("receptors."))
				+ ", though this is rare [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a record sentence reproduced whole is a faithful copy however the answer goes on. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatReproducesNothingOfTheRecordIsNotChecked() {
		// The "nothing to copy" gate — ClassCodeFidelityCheck's, one operand along. An answer that
		// summarises in its own words has reproduced nothing, so there is no reproduction to be
		// unfaithful to, and every phrase it shares with the record by chance would otherwise be a
		// candidate. Asserted at DEBUG on the check's own logger, so the case pins that the check
		// RAN and declined rather than that it was never reached.
		service.setLlmProvider(answering("Sertraline and the patient's tramadol together carry a "
				+ "serotonin risk [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"with nothing reproduced there is no reproduction to be unfaithful to. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "reproduces no cited reference record"),
					"the gate that declined has to be identifiable. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aDivergenceInAnEarlierRunIsReportedThoughALaterRunIsLonger() {
		// Every reproduced run is its own candidate, not just the longest. The answer substitutes
		// inside a 15-word run and then reproduces a 60-word record sentence faithfully; an
		// implementation that examined only the longest run would look at the faithful one, find it
		// innocent, and report nothing — the degraded sentence would be exactly as invisible as it
		// is today.
		service.setLlmProvider(answering(mechanismFrom("Due") + " increase the risk of a rare "
				+ "condition. " + secondMechanismSentence() + " [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]"),
					"a substitution in an earlier run must be reported even where a later run is "
							+ "longer and faithful. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aQuotationTheAnswerClosedBeforeItsOwnNextSentenceIsNotReported() {
		// The false positive a Phase 2 reviewer measured, and the reason the gap predicate is the
		// WEAK one. The answer quotes the record verbatim, closes the quotation, and starts its own
		// sentence. Nothing was substituted — but the quote's closing mark stands between the full
		// stop and the space, so the strict boundary rule (a terminator followed IMMEDIATELY by
		// whitespace, which is what CitationGroundingVerifier splits on) does not see a sentence end
		// and the answer-side exit is unreachable. This module's own reference prose is full of
		// "(SSRIs)" and "(M1)", so ".)" and ".\"" are ordinary here.
		//
		// The reproduction deliberately ends MID record sentence, so the record-side exit cannot
		// stand in. It shares the answer-side leg with everyWayASentenceCanEndInTheSharedRule…, which
		// was added later; both redden when that leg is neutralised, and this comment claimed to be
		// the only one until a review measured it.
		service.setLlmProvider(answering("The finding states: \"" + copiedThrough("may")
				+ ".\" Monitor the patient closely [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a faithful quotation the answer closed before its own next sentence states "
							+ "nothing the record does not, and reporting it is the crying-wolf failure "
							+ "this check must not have. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void everyWayASentenceCanEndInTheSharedRuleEndsAnAnswerSentence() {
		// The shared rule itself, through the real pipeline, and spelled out as LITERALS rather than
		// iterated off the constant. Building the list FROM SENTENCE_TERMINATORS was the first
		// version of this case and it was vacuous in the one direction that matters: narrowing the
		// set to "." left the whole api suite green, this case included, because it then iterated one
		// ending and passed under a name promising every member. That is the shape
		// ChartSearchAiAuditSearchModeTest's four spellings exist to avoid — every other assertion
		// compares a constant to itself and cannot see it shrink.
		//
		// Deleting mayEndASentence's line-break arm reddens this case alone, and the line break is
		// not decoration: the system prompt asks for "numbered lines or simple newlines", so a
		// multi-item answer often carries no terminating punctuation at all.
		assertEquals(".!?", ChartSearchAiUtils.SENTENCE_TERMINATORS,
				"the members below are literals, so a change to the shared set has to arrive here too "
						+ "rather than passing silently");
		for (String ending : new String[] { ". ", "! ", "? ", "\n" }) {
			service.setLlmProvider(answering(withoutTrailingStop(copiedThrough("may")) + ending
					+ "Monitor the patient closely [" + finding.getIndex() + "]."));
			try (LogCapture capture = LogCapture.on(PACKAGE)) {
				service.search(patient(), QUESTION);
				assertFalse(capture.describeAll().isEmpty(),
						"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
				assertFalse(capture.hasEventAtOrAbove(Level.WARN),
						"\"" + ending.trim() + "\" must end an answer sentence, or the answer-side exit "
								+ "silently stops firing for it. Captured: " + capture.describeAll());
			}
		}
	}

	@Test
	public void aReproductionOneWordShortOfTheFloorIsNotReported() {
		// The floor from the other side. The case above reproduces exactly twelve words and must be
		// reported, which forbids raising the floor; this one reproduces eleven and must be silent,
		// which forbids lowering it. Neither alone pins the number: measured, a floor of eleven left
		// the whole api suite green.
		service.setLlmProvider(answering(wordsFrom("Due", 11)
				+ " agents and other drugs [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a reproduction one word short of the floor is not evidence of copying. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aReproductionTheAnswerReCasedIsStillReported() {
		// Words are compared lower-cased, and the fold is behaviour rather than tidiness: an answer
		// quoting a fragment from mid-sentence capitalises its first word, which costs that word off
		// the run. Here it costs exactly the one word that carries the reproduction over the floor.
		// Deleting the fold left the whole api suite green until this case existed.
		String quoted = wordsFrom("coadministration", 12);
		service.setLlmProvider(answering(Character.toUpperCase(quoted.charAt(0)) + quoted.substring(1)
				+ " outcome for this patient [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "reproduces 12 words", "[" + finding.getIndex() + "]"),
					"the answer re-cased the first word of what it reproduced; the comparison folds "
							+ "case, so the run is still twelve words. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatReproducesTheRecordToItsLastWordIsNotReported() {
		// The record-exhausted exit, on its own: the answer reproduces the whole record — appended
		// strength clause included, which the prompt teaches the model verbatim — and then carries on
		// inside its own sentence. There is no next record word to have substituted for.
		// The gap the answer carries on through must contain NO terminator, or the answer-side exit
		// stands in for the one this case is named after and the case is green under either.
		service.setLlmProvider(answering(withoutTrailingStop(finding.getText())
				+ ", so monitor closely [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a record reproduced to its last word has nothing left to diverge from. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aCitedChartRecordIsNeverComparedAgainstTheAnswer() {
		// The scope of the check, which is the classification and not a type name. A chart record is
		// the patient's own charted prose, not module-supplied reference material the answer is
		// expected to reproduce — and the WARN carries the patient id, which is the whole reason the
		// check quotes no prose. Widening the gate to every resource type leaves every other case in
		// this file green, so this is the one that holds it.
		PatientChart charted = chartRecordStating("She reports intermittent headache with photophobia "
				+ "and nausea in the mornings after waking, relieved by rest");
		TestableService onCharted = newService(charted);
		onCharted.setLlmProvider(answering("She reports intermittent headache with photophobia and "
				+ "nausea in the mornings after breakfast [1]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			onCharted.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a chart record is not this module's own reference prose and must not be compared. "
							+ "Captured: " + capture.describeAll());
		}
	}

	/** A one-record chart rendered by the REAL serializer over the real test-dataset helper, so the
	 *  record the check reads is a genuine chart line rather than a hand-assembled imitation. */
	private static PatientChart chartRecordStating(String text) {
		return new PatientChartSerializer().serialize(null,
				TestDatasetHelper.toSerializedRecords(
						new String[] { "Clinical observation: (2026-03-18) " + text }),
				Collections.<String> emptySet());
	}

	@Test
	public void aReproductionOfExactlyTheFloorIsStillReported() {
		// The floor is pinned from below by ClassCodeFidelityTest's package-scoped silence cases,
		// which redden at nine; neither that file nor the constant's javadoc counts them, because
		// the count published there went stale on the merge that added cases to that file.
		// Nothing pinned it from ABOVE until this case: raising it to thirteen left the whole api
		// suite green, and since the check's only value is recall, a floor raised silently disables
		// it. This answer reproduces exactly twelve words of the record and then substitutes.
		service.setLlmProvider(answering(wordsFrom("Due", 12)
				+ " agents and other drugs [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "reproduces 12 words", "[" + finding.getIndex() + "]"),
					"a reproduction of exactly MIN_REPRODUCED_WORDS must still be reported, or the "
							+ "floor can be raised and the check silenced with a green build. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aDivergenceARecordsOwnReproductionCarriesThroughIsNotReported() {
		// Reproductions.carriedThrough, which nothing pinned: a divergence at an answer position that
		// some reproduction of a record CARRIES ON past is not reported, because at that alignment
		// the answer's next word is the record's own. It needs a record that states one passage
		// twice, which the sixteen-entry DDInter excerpt does not — but a rendered reference record
		// whose partner was PROMOTED is a "; "-joined list of per-partner interaction items and
		// DDInter partners routinely share a mechanism string, so the shape is ordinary rather than
		// exotic. Promoted is the qualifier issue #355 added: with nothing promoted the record carries
		// no mechanism prose for this check to pool.
		//
		// The record is assembled here rather than injected for that reason, and it is the right
		// operand: this check is a pure function of an answer and a record's TEXT, and the cases
		// above already pin that it runs over production-rendered records on the real answer path.
		String passage = "alfa bravo charlie delta echo foxtrot golf hotel india juliett kilo lima";
		PatientChart repeated = referenceRecordStating(passage + " quebec. " + passage
				+ " romeo sierra tango uniform victor whiskey xray yankee zulu oscar papa");
		TestableService onRepeated = newService(repeated);
		onRepeated.setLlmProvider(answering(passage
				+ " romeo sierra tango uniform victor whiskey xray yankee zulu oscar papa mike [1]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			onRepeated.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer's next word IS the record's own at another alignment, so nothing was "
							+ "substituted. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aDivergenceAnotherCitedRecordEndsInnocentlyAtIsNotReported() {
		// Reproductions.explained — the cross-record half of the pooling, and the half the pass-2
		// gap-predicate fix left unreachable on the bundled data: the reference record's own
		// "; "-joined item seam now reads as a possible sentence end, so it explains its own
		// continuation and never needs a second record to do it. The leg still decides the case where
		// a second cited record ends where the first diverges, so it is pinned here rather than
		// deleted — it can only ever add silence.
		String passage = "alfa bravo charlie delta echo foxtrot golf hotel india juliett kilo lima";
		PatientChart pair = referenceRecordsStating(passage + " mike november oscar", passage);
		TestableService onPair = newService(pair);
		onPair.setLlmProvider(answering(passage + " papa quebec [1], [2]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			onPair.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the second cited record ends exactly where the first diverges, so the answer's "
							+ "continuation is its own words after a complete reproduction. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aDivergenceAtTheFirstWordOfAnotherRecordsReproductionIsNotReported() {
		// The boundary of Reproductions.carriedThrough: a reproduction covers its first answer word
		// too, so a divergence another record's run STARTS at is pooled. Its field javadoc asserted
		// that and nothing held it — tightening the loop to skip the first position left the whole
		// api suite green. Record [1] stops matching at the thirteenth word while record [2]'s own
		// reproduction begins exactly there and carries on, so nothing was substituted.
		String shared = "alfa bravo charlie delta echo foxtrot golf hotel india juliett kilo lima";
		PatientChart pair = referenceRecordsStating(shared + " zulu quebec romeo",
				"lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray");
		TestableService onPair = newService(pair);
		onPair.setLlmProvider(answering(shared
				+ " mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray "
				+ "[1], [2]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			onPair.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer's next word begins the second record's own reproduction, so nothing "
							+ "was substituted there. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitedReferenceRecordWithNoTextIsSkippedRatherThanRead() {
		// A record we could not read is one we cannot say the answer diverged from — and without the
		// guard its null text reaches the tokeniser and the check reports its own failure instead.
		PatientChart unreadable = new PatientChart(chart.getText(),
				Collections.<RecordMapping> singletonList(
						new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING,
								"00000000-0000-0000-0000-000000000001", null)),
				Collections.<Integer> emptyList());
		TestableService onUnreadable = newService(unreadable);
		onUnreadable.setLlmProvider(answering("The records address it and the finding is a reason to "
				+ "withhold it [1]."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			onUnreadable.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an unreadable cited record must be skipped, not read. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "cites no readable reference record"),
					"and which decline it was has to be identifiable. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void searchStreaming_shouldRunTheSameCheckOnThePrimaryProductionPath() {
		// /search/stream is the path users hit: a check wired only into search() would be absent
		// from production traffic while every non-streaming test stayed green.
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]"),
					"the streaming path must run the same check. Captured: " + capture.describeAll());
		}
	}

	/** The finding's own text from its first word up to and including the first token equal to
	 *  {@code lastToken} — a verbatim reproduction of a leading stretch of the record, punctuation
	 *  and all. */
	private String copiedThrough(String lastToken) {
		StringBuilder sb = new StringBuilder();
		for (String token : recordTokens) {
			sb.append(sb.length() == 0 ? "" : " ").append(token);
			if (token.equals(lastToken)) {
				return sb.toString();
			}
		}
		throw new IllegalStateException("no token \"" + lastToken + "\" in " + finding.getText());
	}

	/** The finding's text from the first token equal to {@code firstToken} up to the token before
	 *  the first {@code "potentiate"} — the earlier, shorter run the substitution case needs. */
	private String mechanismFrom(String firstToken) {
		String whole = copiedThrough("may");
		int at = whole.indexOf(firstToken);
		assertTrue(at > 0, "no token \"" + firstToken + "\" inside " + whole);
		return whole.substring(at);
	}

	/** The mechanism's second sentence, reproduced whole — 60-odd words, longer than any other run
	 *  in the answer that carries it. */
	private String secondMechanismSentence() {
		// The production constant, not a second spelling of it: this slice exists partly because two
		// copies of one boundary rule is issue #260's shape, and a test that spells its own is a
		// third copy that agrees with neither by construction.
		String[] sentences = ChartSearchAiUtils.SENTENCE_BOUNDARY.split(mechanism());
		assertTrue(sentences.length > 2, "the premise: the mechanism has a second sentence. Was: "
				+ mechanism());
		return sentences[1];
	}

	/** The mechanism prose the finding carries: its detail with the module's own appended clause
	 *  removed. Test-side slicing, deliberately — the check itself has no such notion and must not
	 *  grow one (the record-sentence exit is what keeps the clause out of the comparison). */
	private String mechanism() {
		String text = finding.getText();
		return text.substring(0, text.indexOf(DrugReferenceInjector.STRENGTH_WITHHOLD));
	}

	/** {@code count} words of the record's own text starting at the first token equal to
	 *  {@code firstToken} — an anchor inside a mechanism sentence, so the divergence after them falls
	 *  mid-sentence on the record's side too. */
	private String wordsFrom(String firstToken, int count) {
		int at = 0;
		while (at < recordTokens.length && !recordTokens[at].equals(firstToken)) {
			at++;
		}
		assertTrue(at + count <= recordTokens.length,
				"no " + count + " tokens from \"" + firstToken + "\" in " + finding.getText());
		StringBuilder sb = new StringBuilder();
		for (int taken = 0; taken < count; taken++) {
			sb.append(taken == 0 ? "" : " ").append(recordTokens[at + taken]);
		}
		return sb.toString();
	}

	/** A chart carrying one reference-typed record with {@code text}. Assembled rather than injected
	 *  because the shapes the pooling legs need do not occur in the bundled excerpt; see the cases
	 *  that use it for why that is the right operand here. */
	private static PatientChart referenceRecordStating(String text) {
		return referenceRecordsStating(text);
	}

	/** The same, for several records, numbered from one in the order given. */
	private static PatientChart referenceRecordsStating(String... texts) {
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();
		for (int at = 0; at < texts.length; at++) {
			mappings.add(new RecordMapping(at + 1,
					ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING,
					"00000000-0000-0000-0000-00000000000" + (at + 1), null, texts[at]));
		}
		return new PatientChart("chart", mappings, Collections.<Integer> emptyList());
	}

	/** {@code text} without a single trailing sentence terminator, so an answer built from it can
	 *  carry on inside one sentence. */
	private static String withoutTrailingStop(String text) {
		String trimmed = text.trim();
		return trimmed.isEmpty()
				|| ChartSearchAiUtils.SENTENCE_TERMINATORS.indexOf(trimmed.charAt(trimmed.length() - 1)) < 0
						? trimmed
						: trimmed.substring(0, trimmed.length() - 1);
	}

	/** The mechanism with its closing full stop replaced by nothing, so an answer can weld a clause
	 *  on and keep one sentence — the shape the pooling case needs. */
	private String mechanismWithoutItsFinalStop() {
		String text = mechanism();
		return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
	}

	/** The tail of the mechanism, used only as a premise assertion that the drug-reference record
	 *  carries the same string. */
	private String mechanismTail() {
		String text = mechanismWithoutItsFinalStop();
		return text.substring(Math.max(0, text.length() - 60));
	}

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

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	/** @return whether one DEBUG line carries {@code needle} — the three declines all produce
	 *  silence, and a case that claims one of them has to say which. */
	private static boolean debugStating(LogCapture capture, String needle) {
		return capture.hasMessageAt(Level.DEBUG, needle);
	}

	/** @return whether one WARN carries every one of {@code required} */
	private static boolean warnStating(LogCapture capture, String... required) {
		return capture.hasMessageAt(Level.WARN, required);
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

	/** The canned answer — the one thing that cannot be held still on a live engine. */
	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private StubProvider(String answer) {
			this.answer = answer;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}
}
