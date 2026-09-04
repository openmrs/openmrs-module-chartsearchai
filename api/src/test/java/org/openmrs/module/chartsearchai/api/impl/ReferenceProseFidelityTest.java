/*
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
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
 * The answer prose is never rewritten. What DOES reach the wire, since the second round of #337, is
 * the citation the check named — not a word of either text; the cases at the top of this file pin
 * that statement and {@code ChartSearchAiUnfaithfulRenderingTest} pins its wire shape.
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
	 *  failure message will be about class codes. <b>That reason no longer covers every case in this
	 *  file</b>: since issue #338's captured answer arrived here, one answer states only its record's
	 *  own code and still trips the sibling, by stating it four times inside one parenthetical (ADR
	 *  Decision 59's own rule 1). That case captures {@link #CHECK} instead, and says so.
	 *  {@code ClassCodeFidelityTest} carries the same note in the other direction. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	/** The question issue #338 captured its answer on. */
	private static final String ISSUE_338_QUESTION = "Can I give her hydrocortisone?";

	/** The verbatim DDInter slice the two #338 cases read: the two rows whose shared level-4 subgroup
	 *  raises the recorded-allergy cross-reactivity sentence that answer opens on. A slice and not the
	 *  shipped knowledge base, because these cases read the rendered TEXT. */
	private static final String ISSUE_338_FIXTURE =
			"chartsearchai-test/ddi-issue338-allergy-cross-reactivity.json";

	/** The allergen the slice's other row records, as a chart token. */
	private static final String ISSUE_338_ALLERGY = "dexamethasone";

	/** The opening CLAUSE of the answer issue #338 captured, word for word from the issue body — its
	 *  own lead, its own fourfold class code, its own truncated allergen and the gloss after it. Three
	 *  things are not the capture's: the citation marker's number, because this arrangement numbers
	 *  its records differently; the five clauses after it, dropped because they cite records this
	 *  arrangement does not carry; and the comma that joined this clause to the next, re-terminated
	 *  with a full stop so the answer ends a sentence rather than trailing. That last one is not
	 *  cosmetic in a check whose exits read sentence boundaries — the divergence is at the allergen,
	 *  before the marker, so it is inert here, and the case below would still hold if it were not,
	 *  because the control reports the same words with the repetition collapsed. */
	private static final String ISSUE_338_CAPTURE =
			"No — Hydrocortisone should not be given: Hydrocortisone is in the same ATC class "
					+ "(H02AB, H02AB, H02AB, H02AB) as the patient's allergy to Dexamethason "
					+ "(Dexamethasone) [353].";

	private static final String ISSUE_338_CAPTURE_MARKER = "[353]";

	private static final String ISSUE_338_REPEATED_CODE = "(H02AB, H02AB, H02AB, H02AB)";

	/** The clause the module's own recorded-allergy cross-reactivity sentence names its allergen
	 *  after — the anchor every #338 slice below reads, asserted as a premise before it is used. */
	private static final String ALLERGY_TO = "allergy to ";

	/** What that sentence relates the two by, and the premise that tells it from the recorded-allergy
	 *  sentence the same arm can raise. */
	private static final String CROSS_REACTIVITY = "cross-reactivity";

	/** The answer's own opening, ahead of the stretch it reproduces. Its longest overlap with the
	 *  record is one word, so it contributes no run of its own. */
	private static final String LEAD = "No — it should not be given: ";

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

	/**
	 * Issue #337's remaining half: the divergence the check finds has to reach a client, or the
	 * clinician reads the degraded sentence with nothing anywhere in the response saying so. Same
	 * answer as the case above — this one asks what the response STATES rather than what the log
	 * says.
	 */
	@Test
	public void search_statesTheCitationWhoseRenderingItFoundUnfaithful() {
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));

		ChartAnswer answer = service.search(patient(), QUESTION);

		assertEquals(Collections.singletonList(Integer.valueOf(finding.getIndex())),
				answer.getUnfaithfullyRenderedCitations(),
				"the response has to name the citation whose rendering diverged from the record, or "
						+ "the only thing that knows is the server log");
	}

	/**
	 * A record the answer diverges from TWICE is ONE unfaithful citation. The issue's own comment
	 * records that shape live — two WARNs on record [253] from a single answer — and what a client
	 * can act on is "this citation's rendering is not the record's words", once.
	 */
	@Test
	public void aRecordDivergedFromTwiceIsStatedOnce() {
		// Two maximal reproductions of the SAME record, each diverging mid-sentence: twelve words
		// from the record's third sentence, then the record's own opening carried through to its
		// first "may". Both are reported — the WARNs stay per divergence — and the statement is one.
		service.setLlmProvider(answering(wordsFrom("Symptoms", 12) + " nothing at all. "
				+ copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));

		ChartAnswer answer;
		try (LogCapture capture = LogCapture.on(CHECK)) {
			answer = service.search(patient(), QUESTION);
			// The premise, or this case is a second copy of the one above: TWO divergences were
			// reported, and it is the STATEMENT that collapses them rather than the check.
			assertEquals(2, capture.messagesAt(Level.WARN).size(),
					"the premise: this answer diverges from the record twice. Captured: "
							+ capture.describeAll());
		}

		assertEquals(Collections.singletonList(Integer.valueOf(finding.getIndex())),
				answer.getUnfaithfullyRenderedCitations(),
				"one record is one unfaithful citation however many times the answer diverged from "
						+ "it, or a client renders the same mark twice");
	}

	/**
	 * THREE records diverged from, stated in the order the check reported them, which is neither the
	 * order the chart numbers them in nor its reverse. Three and not two, and that is the whole
	 * arrangement: the statement comes back {@code [2, 3, 1]}, so it separates report order from BOTH
	 * orderings a set could impose. A two-record version was written first and its {@code [2, 1]} is
	 * descending — it passed under a reversed {@code TreeSet}, which is exactly the coincidence a
	 * control has to rule out.
	 *
	 * <p><b>What "report order" is, stated because an earlier draft of this javadoc got it wrong.</b>
	 * It is RECORD-major: {@code examine} runs once per cited record, in the order
	 * {@code citedReferenceProse} returns them — which is {@code extractCitedReferences}' order, not
	 * the answer's. Answer position orders the divergences WITHIN one record. In THIS case the two
	 * coincide and the expectation is read straight off the marker order, because the stub returns an
	 * empty structured {@code citations} array and injected reference records are undated, so nothing
	 * reorders the markers — reorder them and the expectation moves. This arrangement cannot
	 * tell the two apart, because its marker order and its divergence order coincide; an answer that
	 * cites {@code [1]} before {@code [2]} while diverging from {@code [2]} first states
	 * {@code [1, 2]}, and nothing here pins that.
	 *
	 * <p>Assembled rather than injected: the two records the real injector produces for one question
	 * carry the SAME mechanism string, so they diverge together and pool, and the shape this needs
	 * does not occur in a sixteen-entry excerpt — the reason the pooling cases below build their own
	 * records. The check is a pure function of an answer and a record's text, so an assembled operand
	 * is the right one here.
	 *
	 * <p>Without this the ordering the accessor and the check both promise is unpinned: with one
	 * record there is nothing to order.
	 */
	@Test
	public void severalRecordsDivergedFromAreStatedInTheOrderTheyWereReported() {
		String one = "Aspirin and warfarin together raise the risk of serious bleeding in patients "
				+ "who are elderly and frail.";
		String two = "Metformin should be withheld before contrast imaging in any patient because "
				+ "renal impairment can precipitate lactic acidosis.";
		String three = "Amiodarone prolongs the QT interval and should not be combined with other "
				+ "agents that delay cardiac repolarisation.";
		TestableService overThree = newService(referenceRecordsStating(one, two, three));
		overThree.setLlmProvider(answering(
				"Metformin should be withheld before contrast imaging in any patient because "
						+ "renal impairment can cause trouble [2]. Amiodarone prolongs the QT "
						+ "interval and should not be combined with other medicines [3]. Aspirin and "
						+ "warfarin together raise the risk of serious bleeding in patients who are "
						+ "unwell [1]."));

		ChartAnswer answer = overThree.search(patient(), QUESTION);

		assertEquals(
				Arrays.asList(Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(1)),
				answer.getUnfaithfullyRenderedCitations(),
				"the statement is in the order the divergences were reported, and neither the chart's "
						+ "numbering nor its reverse");
	}

	/**
	 * Empty is a measurement and null is not — the distinction {@code getReferenceSlice()} and
	 * {@code PairChipExtent} already draw. A faithful answer must state that the check RAN and
	 * related nothing, not that nobody looked.
	 */
	@Test
	public void aFaithfulAnswerStatesAnEmptyListRatherThanNothing() {
		service.setLlmProvider(answering(copiedThrough("may") + " potentiate the risk of serotonin "
				+ "syndrome [" + finding.getIndex() + "]."));

		ChartAnswer answer;
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			answer = service.search(patient(), QUESTION);
			// Which silence this is, asserted rather than assumed. An empty list is also what the
			// two DECLINE gates return — an answer citing no readable reference record, and one
			// reproducing nothing — so without this the case would pass on an arrangement where the
			// check never compared anything, which is the reading the accessor's javadoc warns a
			// client about and would be no better inside its own test.
			assertTrue(debugStating(capture, "every reproduction of a cited reference record is faithful"),
					"the premise: the check reproduced this record and found the reproduction "
							+ "faithful, rather than declining before it compared anything. Captured: "
							+ capture.describeAll());
		}

		assertNotNull(answer.getUnfaithfullyRenderedCitations(),
				"null says the producer stated no measurement; a check that ran and found nothing "
						+ "has made one");
		assertTrue(answer.getUnfaithfullyRenderedCitations().isEmpty(),
				"a faithful recitation names no citation. Was: "
						+ answer.getUnfaithfullyRenderedCitations());
	}

	/**
	 * The statement is a POST-answer measurement, so it is absent from the early {@code done} the
	 * async-grounding path emits — the check runs after the user-visible handoff, deliberately, and
	 * moving it ahead would put a word-level dynamic program in front of that event. This pins the
	 * boundary rather than papering over it: {@code interactionPairs} behaves the same way and
	 * {@code unresolvedDrugClass}, known before the model is called, does not.
	 */
	@Test
	public void searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));
		final List<List<Integer>> early = new ArrayList<List<Integer>>();

		ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { },
				reasoning -> { }, citations -> { },
				ungrounded -> early.add(ungrounded.getUnfaithfullyRenderedCitations()));

		assertEquals(1, early.size(), "the early-done consumer must have fired");
		assertNull(early.get(0),
				"the check runs after the user-visible handoff, so the early answer has no "
						+ "measurement to state — and null says exactly that, where an empty list "
						+ "would claim the answer was checked and found faithful");
		assertEquals(Collections.singletonList(Integer.valueOf(finding.getIndex())),
				answer.getUnfaithfullyRenderedCitations(),
				"and the answer the classic shape emits carries it");
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
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"with nothing reproduced there is no reproduction to be unfaithful to. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "reproduces no cited reference record"),
					"the gate that declined has to be identifiable. Captured: " + capture.describeAll());
			// And what the DECLINE states on the response. Empty, not null: the check ran. Returning
			// null here would publish a failed-check reading for an answer that was simply never
			// compared, which is the one thing the key's client contract forbids a consumer to infer
			// in the other direction — so the two decline gates are pinned as well as the compare.
			assertEquals(Collections.emptyList(), answer.getUnfaithfullyRenderedCitations(),
					"a gate that declined has still MEASURED: it names no citation, and says so with "
							+ "an empty list rather than with the absence of a measurement");
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
		// exotic. Promoted is the qualifier issue #355 added: with nothing patient-specific to show the
		// record normally carries names and severities rather than mechanism prose for this check to pool — normally,
		// not always, since a rule with no token and no ATC has no name to shorten to and renders its
		// paragraph anyway.
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
			ChartAnswer answer = onUnreadable.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an unreadable cited record must be skipped, not read. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "cites no readable reference record"),
					"and which decline it was has to be identifiable. Captured: "
							+ capture.describeAll());
			assertEquals(Collections.emptyList(), answer.getUnfaithfullyRenderedCitations(),
					"and this decline states an empty list too — the same statement a stock install "
							+ "makes on every answer, and not the absence of a measurement");
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

	/**
	 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/338">#338</a>'s
	 * captured answer, word for word to the end of its opening clause: its repeated class code and its
	 * truncated allergen, in one sentence.
	 * The repetition cuts this answer's agreement with the record it is about into runs below the
	 * floor, so the check declines at its gate and states nothing about it — the residue ADR
	 * Decision 61 records as a substitution inside a reproduction shorter than twelve words, met here
	 * by the defect that sits earlier in the same sentence. It is not a claim about every answer
	 * carrying both defects: one that also diverged from the record inside a long enough reproduction
	 * elsewhere would be reported for that.
	 *
	 * <p>The answer is TRANSCRIBED rather than sliced out of the record at run time, because it is
	 * the historical capture the decision is about. What holds it to the dataset is the control
	 * below: collapse the repetition and the same words must be REPORTED, which they can only be
	 * by reproducing the record. Let the rendered sentence drift and the control reddens.
	 *
	 * <p><b>The clause, against a two-row slice — which is not the whole capture against the whole
	 * chart, and the gate differs.</b> Here nothing of the record survives at the floor, so the check
	 * declines before comparing. On the full six-clause answer over the arrangement ADR Decision 59
	 * describes, its sixth clause DOES reproduce one other finding's record to the floor, so the check
	 * compares and finds no divergence — the run the truncated allergen ends being seven words, under
	 * the floor. Two gates, one silence, and the section says which is which.
	 *
	 * <p>WHICH silence it is, asserted rather than assumed, as the three declining cases above do.
	 * It captures the check's own logger and not {@link #PACKAGE}, which the other silence cases
	 * here use: {@code ClassCodeFidelityCheck} REPORTS this answer, under Decision 59's own
	 * repeated-code rule, so a package-wide silence assertion would fail on a sibling's correct
	 * finding. The {@code PACKAGE} note above covers a literal code written into an answer; a code
	 * repeated out of the record trips that check too.
	 */
	@Test
	public void theCapturedAnswerOfIssue338IsBelowTheFloorAndTheSameSentenceWithoutItsRepetitionIsReported()
			throws Exception {
		PatientChart chart = DrugReferenceTestSupport.injectedAllergyFindingChart(ISSUE_338_FIXTURE,
				ISSUE_338_QUESTION, Arrays.asList(ISSUE_338_ALLERGY));
		RecordMapping record = crossReactivityFinding(chart);
		TestableService local = newService(chart);
		String captured = ISSUE_338_CAPTURE.replace(ISSUE_338_CAPTURE_MARKER,
				"[" + record.getIndex() + "]");
		// The transcribed answer is held to the record in three ways, because the control below holds
		// only the leading stretch: spell the allergen correctly in the constant, or state the code
		// twice instead of four times, and every assertion here still passed before these.
		String sentence = findingSentence(record);
		String code = parenthesisedCode(sentence);
		String allergen = allergenNamed(sentence);
		assertTrue(captured.contains("(" + code + ", " + code + ", " + code + ", " + code + ")"),
				"the premise: the capture states the record's own class code four times inside one "
						+ "parenthetical, which is defect 1. Record: " + sentence);
		assertTrue(captured.contains(ALLERGY_TO + allergen.substring(0, allergen.length() - 1) + " ("),
				"the premise: it names the record's allergen a letter short and glosses it, which is "
						+ "defect 2. Record: " + sentence);
		assertFalse(captured.contains(ALLERGY_TO + allergen),
				"and it does not also name it in full there, or defect 2 is not in this answer");

		local.setLlmProvider(answering(captured));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			ChartAnswer answer = local.search(patient(), ISSUE_338_QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the check states nothing about the captured answer. Captured: "
							+ capture.describeAll());
			// And what the silence reaches a CLIENT as. Empty, not null: the check ran and named no
			// citation. ADR Decision 59 rests a paragraph on this being what a consumer of #338's own
			// answer would read, and Decision 74's key contract on an empty list not being a
			// certificate — the answer here carries two defects and the list is still empty.
			assertEquals(Collections.emptyList(), answer.getUnfaithfullyRenderedCitations(),
					"the decline states an empty list on the response, not the absence of a measurement");
			assertTrue(debugStating(capture, "reproduces no cited reference record"),
					"and the repeated code leaves no run of the record at the floor, so the gate that "
							+ "declined has to be identifiable — as it does for the three declining "
							+ "cases above. Captured: " + capture.describeAll());
		}

		local.setLlmProvider(answering(captured.replace(ISSUE_338_REPEATED_CODE, "(H02AB)")));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			local.search(patient(), ISSUE_338_QUESTION);
			assertTrue(warnStating(capture, "[" + record.getIndex() + "]"),
					"the control: collapse the repetition and the same captured words ARE reported, so "
							+ "the silence above is the floor and not this arrangement — and the answer "
							+ "really does reproduce this record. Captured: " + capture.describeAll());
		}
	}

	/**
	 * And where the rest of the reproduction is faithful, the shortened name is what the report turns
	 * on. The pair differs in nothing else: the same record sentence, reproduced to its own end both
	 * times.
	 *
	 * <p><b>It demonstrates rather than discriminates.</b> The check compares whole words and has no
	 * notion of a drug name, so nothing about the COMPARISON separates this pair from the two cases
	 * either half resembles —
	 * {@link #search_shouldStaySilentWhenTheAnswerReproducesTheRecordFaithfully} and
	 * {@link #search_shouldReportAnAnswerThatSubstitutesItsOwnWordsInsideACopiedSentence}, on the
	 * other arrangement. (The FLOOR does separate them: raise it far enough and this case reddens
	 * while both of those stay green, because its runs are shorter than theirs. That is a fact about
	 * this record's length, not about what the check can tell apart.) It is here because ADR
	 * Decision 59 rests a statement on this pair — that a report from this check is not evidence a
	 * name was mangled, while a name IS what the report turns on where the answer diverges in nothing
	 * else — and a statement in a decision with no case behind it is one nothing re-measures.
	 */
	@Test
	public void aTruncatedDrugNameDecidesTheReportWhereTheRestOfTheReproductionIsFaithful()
			throws Exception {
		PatientChart chart = DrugReferenceTestSupport.injectedAllergyFindingChart(ISSUE_338_FIXTURE,
				ISSUE_338_QUESTION, Arrays.asList(ISSUE_338_ALLERGY));
		RecordMapping record = crossReactivityFinding(chart);
		TestableService local = newService(chart);
		String sentence = findingSentence(record);
		String marker = " [" + record.getIndex() + "].";

		local.setLlmProvider(answering(LEAD + sentence + marker));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			local.search(patient(), ISSUE_338_QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the record's own sentence reproduced whole states nothing the record does not. "
							+ "Captured: " + capture.describeAll());
		}

		local.setLlmProvider(answering(LEAD + truncateAllergen(sentence) + marker));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			local.search(patient(), ISSUE_338_QUESTION);
			assertTrue(warnStating(capture, "[" + record.getIndex() + "]"),
					"shorten the allergen and nothing else, and it is reported. Captured: "
							+ capture.describeAll());
		}
	}

	/** The recorded-allergy CROSS-REACTIVITY finding in {@code chart} — selected by what it relates the
	 *  two substances by, never by injection order. Defensive on this slice, which raises exactly one
	 *  finding: dropping either conjunct leaves the whole class green today.
	 *  It is written this way because the same arm raises a DIRECT recorded-allergy sentence that also
	 *  carries the words "allergy to" — an allergen naming the subject row rather than the partner is
	 *  all it takes — and a case reading a ROUTE-QUALIFIED one of those would slice a different
	 *  sentence with every premise here still green. (For a bare one a slicing helper fails first
	 *  instead — which one depends on the case, so the failure names the slicing rather than the
	 *  premise either way.) */
	private static RecordMapping crossReactivityFinding(PatientChart chart) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())
					&& mapping.getText().contains(CROSS_REACTIVITY)
					&& mapping.getText().contains(ALLERGY_TO)) {
				assertTrue(mapping.getText().contains(DrugReferenceInjector.STRENGTH_WITHHOLD),
						"the premise: the finding ends with the appended strength clause, which the "
								+ "record-sentence exit keeps out of the comparison. Was: "
								+ mapping.getText());
				return mapping;
			}
		}
		throw new IllegalStateException("the slice raised no recorded-allergy cross-reactivity finding: "
				+ chart.getText());
	}

	/** The finding's own sentence: its detail with the record prefix and the appended clause removed.
	 *  Sliced from the record at run time rather than transcribed, so the cases cannot drift from the
	 *  dataset, and off the production constants rather than a second spelling of them. */
	private static String findingSentence(RecordMapping record) {
		String text = record.getText();
		String detail = text.substring(0, text.indexOf(DrugReferenceInjector.STRENGTH_WITHHOLD));
		int subject = detail.indexOf(DrugReferenceInjector.FINDING_PREFIX)
				+ DrugReferenceInjector.FINDING_PREFIX.length();
		// The premises are about the RECORD, not about the slice: asserting the slice is free of what
		// this method just removed cannot fail. What can is the rendering changing shape under it —
		// and then the case built on the slice compares an answer against a record it no longer
		// resembles, silently.
		assertTrue(text.startsWith(DrugReferenceInjector.FINDING_PREFIX),
				"the premise: the record opens with the finding prefix this slices off. Was: " + text);
		assertTrue(detail.indexOf(": ", subject) > subject,
				"the premise: the subject label is followed by the detail this slices out. Was: " + text);
		return detail.substring(detail.indexOf(": ", subject) + 2);
	}

	/** The token the record parenthesises — its class code, taken as the record's own text between its
	 *  first brackets. Slicing, not a second spelling of {@code ClassCodeFidelityCheck}'s pattern:
	 *  nothing here decides what a class code LOOKS like. */
	private static String parenthesisedCode(String sentence) {
		int open = sentence.indexOf('(');
		int close = sentence.indexOf(')', open);
		assertTrue(open > 0 && close > open, "no parenthesised code in " + sentence);
		return sentence.substring(open + 1, close);
	}

	/** The allergen {@code sentence} names, as the record spells it. */
	private static String allergenNamed(String sentence) {
		int at = sentence.indexOf(ALLERGY_TO) + ALLERGY_TO.length();
		int end = sentence.indexOf(' ', at);
		assertTrue(at > ALLERGY_TO.length() && end > at, "no allergen named in " + sentence);
		return sentence.substring(at, end);
	}

	/** {@code sentence} with the allergen it names shortened by its last letter — #338's own
	 *  {@code Dexamethason} for {@code Dexamethasone}, derived from the record rather than typed. */
	private static String truncateAllergen(String sentence) {
		int at = sentence.indexOf(ALLERGY_TO) + ALLERGY_TO.length();
		String allergen = allergenNamed(sentence);
		return sentence.substring(0, at) + allergen.substring(0, allergen.length() - 1)
				+ sentence.substring(at + allergen.length());
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
