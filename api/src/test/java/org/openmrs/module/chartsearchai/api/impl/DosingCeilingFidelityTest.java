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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnstatedDosingCeiling;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/276">#276</a>: the
 * injected record carries the stricter presentation-specific ceiling since issue #274, and the
 * answer still leads with the canonical row's laxer one. Measured live on a RefApp 3.7.1
 * standalone, deterministic across reruns — a patient charted on a low-dose aspirin presentation,
 * answered with the canonical row's 4000 mg/day, citing the record that stated both numbers.
 *
 * <p><b>The arrangement is the ticket's own.</b> {@code Acetylsalicylic acid} at 4000 mg/day and
 * {@code Acetylsalicylic acid (enteric-coated)} at 300 mg/day, one substance by
 * {@code substanceName}, both publishing the alias {@code aspirin} so that neither out-claims the
 * other — which is what made {@code DrugReferenceInjector.rowAttribution} correctly silent on the
 * reported answer, and is why this check asks which ceiling was quoted rather than which row is the
 * subject. The two ceilings are read off what the real injector wrote in {@link #setUp()} and never
 * from the fixture's description.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration over a chart the REAL {@code DrugReferenceInjector} produced from the REAL
 * {@code DrugReferenceService} over a JSON fixture. Only the model is stubbed: answer prose is not
 * reproducible on a live engine, and the answer is the one variable this check is about.
 *
 * <p><b>What is NOT here.</b> What the injector writes onto the record — which rows contribute a
 * ceiling and which skips drop one — is
 * {@code ReferenceRecordSubstanceCeilingsTest}'s, which owns that section. This file asks only what
 * the answer did with it.
 */
public class DosingCeilingFidelityTest {

	/** The ticket's own question, on the ticket's own drug. */
	private static final String QUESTION = "What is the maximum daily dose of aspirin for this patient?";

	private static final String CEILINGS =
			"chartsearchai-test/drug-reference-substance-dosing-ceilings.json";

	/** The fixture for the ways a ceiling COMPARISON can go wrong that the ticket's own dataset
	 *  cannot reach — an order that differs between numbers and spellings
	 *  ({@link #theCeilingsAreOrderedByNUMBERAndNotByTheirSpelling}), a non-integral ceiling whose
	 *  spelling is a suffix of a decimal
	 *  ({@link #aDecimalInTheAnswerDoesNotSTATEACeilingItMerelyENDSWith}), and a substance of THREE
	 *  rows, without which the walk's own rule decides nothing
	 *  ({@link #theSTRICTESTCeilingTheAnswerStatesIsTheOneReported}). Its own description is
	 *  canonical for what each arrangement is for; this list is a pointer and not a count. */
	private static final String EDGES =
			"chartsearchai-test/drug-reference-dosing-ceiling-edges.json";

	/** The presentation the patient is charted on — the low-dose row, as in the ticket. */
	private static final String CHARTED = "Acetylsalicylic acid (enteric-coated)";

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported" assertion,
	 *  so no other class's WARN can stand in for this check's. */
	private static final String CHECK = DosingCeilingFidelityCheck.class.getName();

	/** The package, for every assertion whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously ({@link LogCapture}'s javadoc), so those cases capture the package instead, where
	 *  {@code LlmInferenceService}'s own [timing] INFO line proves the capture is live. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private PatientChart chart;

	/** The reference record the real injector wrote, and the two ceilings it states. */
	private RecordMapping record;

	private String strictest;

	private String laxest;

	@BeforeEach
	public void setUp() throws IOException {
		chart = DrugReferenceTestSupport.injectedReferenceChartOver(CEILINGS, 30, QUESTION, CHARTED);
		record = soleRecordCarryingCeilings(chart);
		// The premise every case below rests on, asserted off what production wrote rather than
		// trusted from the fixture: ONE record, carrying TWO ceilings, strictest first. Without the
		// spread there is nothing to leave unstated and every case would pass for the wrong reason.
		assertEquals(2, record.getDosingCeilings().size(),
				"the real injector must have written both of this substance's ceilings onto one "
						+ "record — the arrangement issue #276 was measured on. Wrote: "
						+ record.getDosingCeilings());
		strictest = record.getDosingCeilings().get(0);
		laxest = record.getDosingCeilings().get(1);
		assertEquals(Arrays.asList("300 mg/day", "4000 mg/day"), record.getDosingCeilings(),
				"and they must be the ticket's own two numbers, spelled as the record spells them");
		assertTrue(record.getText().contains(strictest) && record.getText().contains(laxest),
				"both must be quotable OUT of the record's own text, or the check is comparing the "
						+ "answer against a string the model was never shown. Text was: "
						+ record.getText());
	}

	@Test
	public void theTicketsOwnAnswerReportsTheStricterCeilingItLeftUnstated() throws IOException {
		// The reported answer, in structure: the canonical row's ceiling, attributed to that row and
		// its band, citing the record that states both numbers. Every word of it is true — which is
		// why no sibling check can see it — and the number a clinician is given is the laxer one.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("The maximum daily dose of Acetylsalicylic acid for ages "
				+ "0-120 is " + laxest + " [" + record.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(Collections.singletonList(
					new UnstatedDosingCeiling(record.getIndex(), laxest, strictest)),
					answer.getUnstatedDosingCeilings(),
					"the citation and BOTH ceilings must reach the wire — the one the answer quoted "
							+ "and the stricter one from the same record it did not");
			assertTrue(capture.hasMessageAt(Level.WARN, "[" + record.getIndex() + "]", laxest,
					strictest, "patient=1"),
					"and the same triple must be reported in one line, carrying the patient so a "
							+ "maintainer reading a log with concurrent requests in it can "
							+ "reconstruct it. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerStatingTheStricterCeilingIsSilentAndPublishesAMeasurementOfNone()
			throws IOException {
		// The other half of the pair above: that one fails if the check accuses nothing, this one
		// fails if it accuses an answer that did put the stricter number in front of the clinician.
		// Neither alone discriminates. It states BOTH, which is the faithful answer the issue wanted.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("Acetylsalicylic acid publishes " + laxest
				+ ", but the enteric-coated presentation she is on publishes " + strictest
				+ " [" + record.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an answer that states the stricter ceiling is the shape this check exists to "
							+ "leave alone. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(),
					"and an empty list is a measurement of none, which is what a client reads to know "
							+ "the check ran");
		}
	}

	@Test
	public void anAnswerQuotingNoCeilingOfTheRecordIsSilent() throws IOException {
		// A question about that drug's warnings cites the same record and quotes no number. A check
		// that reported it would cry wolf on every answer that was never about dosing — which is the
		// failure its siblings guard hardest against.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("Acetylsalicylic acid is recorded for this patient ["
				+ record.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an answer quoting no ceiling has left none of them unstated in the sense this "
							+ "check means. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aDoseInMilligramsIsNotAQuotedCeiling() throws IOException {
		// The needle carries the record's own UNIT and not the bare number, which is what keeps this
		// check out of the dose arm's territory: `DrugSafetyValidator.LIMIT_CUE` exists so a recited
		// ceiling is not read as a prescribed dose, and the converse has to hold here — a milligram
		// dose that happens to equal a published ceiling is not a quotation of it.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("Give 4000 mg [" + record.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"\"4000 mg\" is not the ceiling \"" + laxest + "\". Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aLongerNumberMerelyCONTAININGTheCeilingIsNotQuotingIt() throws IOException {
		// The boundary half of the scan this check shares with its siblings. Without it "14000
		// mg/day" would be read as quoting a 4000 mg/day ceiling.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("The cumulative course total is 14000 mg/day ["
				+ record.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"\"14000 mg/day\" does not state the ceiling \"" + laxest + "\". Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aRecordTheAnswerDoesNotCiteIsNotJudged() throws IOException {
		// Only the citations the answer's own resolution admitted, which is CLAUDE.md's
		// inline-citation rule. An answer quoting the laxer ceiling and citing nothing at all has
		// nothing for this check to attach the claim to.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("The maximum daily dose is " + laxest + "."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an uncited record is not judged. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aSubstanceTheDatasetFilesAsOneRowCarriesNothingToCompare() throws IOException {
		// The fixture's own single-row control. Its record states ONE ceiling, so there is no
		// stricter sibling to have gone unstated and the walk must drop it before reading the answer
		// — the case that would break if the gate counted records rather than ceilings.
		PatientChart single = DrugReferenceTestSupport.injectedReferenceChartOver(CEILINGS, 30,
				"What is the maximum daily dose of cefadroxil?", "Cefadroxil");
		RecordMapping only = soleRecordCarryingCeilings(single);
		assertEquals(Collections.singletonList("2000 mg/day"), only.getDosingCeilings(),
				"the premise: a substance filed as one row states its own ceiling and no other");
		TestableService service = newService(single);
		service.setLlmProvider(answering("The maximum daily dose of Cefadroxil is 2000 mg/day ["
				+ only.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of cefadroxil?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a record with one ceiling cannot have left a stricter one unstated. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void theCeilingsAreOrderedByNUMBERAndNotByTheirSpelling() throws IOException {
		// The invariant the whole walk rests on, and the one the ticket's own fixture cannot pin:
		// every ceiling pair in it sorts the same way as text and as a number. Here 500 and 2000 do
		// not — as text "2000" comes first — so a list ordered by spelling puts the LAXER ceiling at
		// position 0, the check finds the answer states it, and the walk returns silent on exactly
		// the defect issue #276 reports. Sort the ceilings as strings in production and this is what
		// goes red.
		PatientChart ordered = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(ordered);
		assertEquals(Arrays.asList("500 mg/day", "2000 mg/day"), mapping.getDosingCeilings(),
				"the stricter ceiling is first even though its spelling sorts second");
		TestableService service = newService(ordered);
		service.setLlmProvider(answering("The maximum daily dose of Tinidazole is 2000 mg/day ["
				+ mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertEquals(Collections.singletonList(
					new UnstatedDosingCeiling(mapping.getIndex(), "2000 mg/day", "500 mg/day")),
					answer.getUnstatedDosingCeilings(),
					"and the answer quoting 2000 must be told the 500 it left out, not the reverse. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aDecimalInTheAnswerDoesNotSTATEACeilingItMerelyENDSWith() throws IOException {
		// The ceilings are the first NUMERIC needles this module scans an answer for, and a number has
		// a boundary its words do not: `ChartSearchAiUtils.statesWord` bounds on letters and digits
		// alone, so "2.5 mg/day" reads as stating "5 mg/day" — a decimal point is neither. That is a
		// false POSITIVE on a published key, which is the one direction this check must never fail
		// in: every other residue it carries silences a report, and this one INVENTS one, accusing an
		// answer of leaving out a ceiling it never quoted anything of.
		//
		// Reachable exactly where the feature is: an operator dataset with a non-integral ceiling on a
		// multi-row age-banded substance. Nothing bundled has one, and nor did any fixture until this
		// pair.
		PatientChart decimals = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of levothyroxine?", "Levothyroxine (paediatric)");
		RecordMapping mapping = soleRecordCarryingCeilings(decimals);
		assertEquals(Arrays.asList("0.5 mg/day", "5 mg/day"), mapping.getDosingCeilings(),
				"the premise: this substance's ceilings are non-integral, so the laxer one's spelling "
						+ "is a SUFFIX of a decimal an answer can contain");
		TestableService service = newService(decimals);
		service.setLlmProvider(answering("Her recorded dose is 2.5 mg/day ["
				+ mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of levothyroxine?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"\"2.5 mg/day\" states no ceiling of this record — it merely ENDS with the "
							+ "spelling of one. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(),
					"and nothing is published, or a client renders an accusation about a number the "
							+ "answer never quoted");
		}
	}

	@Test
	public void aThousandsSeparatorInTheAnswerDoesNotSTATEACeilingItMerelyENDSWith() throws IOException {
		// The decimal case's sibling, and the reason the rule names two separators rather than one: a
		// grouped total is as ordinary in clinical prose as a decimal dose, and "1,500 mg/day" ends
		// with the spelling of this record's STRICTER ceiling. Read as stating it, the walk returns at
		// the first test and the answer's quotation of the laxer 2000 goes unreported — a MISS rather
		// than a false accusation, which is the gentler failure but still the defect #276 is about.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		assertEquals(Arrays.asList("500 mg/day", "2000 mg/day"), mapping.getDosingCeilings(),
				"the premise: the stricter ceiling's spelling is a SUFFIX of a grouped number");
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("The maximum is 2000 mg/day [" + mapping.getIndex()
				+ "]; her cumulative course total is 1,500 mg/day."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertEquals(Collections.singletonList(
					new UnstatedDosingCeiling(mapping.getIndex(), "2000 mg/day", "500 mg/day")),
					answer.getUnstatedDosingCeilings(),
					"\"1,500 mg/day\" states no ceiling of this record, so the answer's quotation of "
							+ "the laxer 2000 is still reported. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aNakedDecimalDoesNotLetTheLaxerCeilingBeReadOutOfIt() throws IOException {
		// A clinician — and a model copying one — writes a sub-unit dose as ".5 mg/day" as readily as
		// "0.5 mg/day", and the record's own spelling is always the latter (`formatNumber` of a
		// double). What this pins is the half the check can secure: the LAXER ceiling must not be
		// read out of that ".5". Silence here, and it is silence rather than a match — the strictest
		// needle "0.5 mg/day" is not in this text either, so the walk quotes nothing and reports
		// nothing.
		//
		// What it does NOT pin, said so the name is not read as more than it is: an answer writing
		// the strictest naked AND the laxer in full IS reported, because the record's spelling of the
		// strictest is absent. That is the respelling residue DosingCeilingFidelityCheck enumerates,
		// and it is why the earlier "between two digits" wording had to go — under it the laxer WAS
		// read out of the ".5", which turned the same answer into an accusation with no ".5" needed.
		PatientChart decimals = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of levothyroxine?", "Levothyroxine (paediatric)");
		RecordMapping mapping = soleRecordCarryingCeilings(decimals);
		assertEquals(Arrays.asList("0.5 mg/day", "5 mg/day"), mapping.getDosingCeilings(),
				"the premise: the record spells the stricter ceiling WITH its leading zero");
		TestableService service = newService(decimals);
		service.setLlmProvider(answering("The maximum for this presentation is .5 mg/day ["
				+ mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of levothyroxine?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"NEITHER needle matches: the record spells the stricter ceiling "
							+ "\"0.5 mg/day\", which is absent from this text, and the laxer "
							+ "\"5 mg/day\" must not be read out of the \".5\". A respelt ceiling is "
							+ "an unclosed residue, not something this recognises. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(),
					"and nothing is published, or the answer is accused of dropping the very number "
							+ "it led with");
		}
	}

	@Test
	public void aSeparatorAFTERALetterLeavesAStatedCeilingStated() throws IOException {
		// The other side of the rule, and the one that decides how far it may reach. A separator a
		// LETTER precedes is punctuation — a comma between list items after a unit, a full stop
		// ending the sentence before the number — and leaves the ceiling stated. Refusing there
		// would accuse an answer of dropping a number it printed, which is the one direction this
		// check must never fail in. It is the complement of the three refusal cases above, and
		// between them they are why the rule is worded off the letter rather than off a digit.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("This substance publishes two ceilings: 2000 mg/day,"
				+ "500 mg/day [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer states BOTH ceilings — the comma punctuates the list, it does not make "
							+ "\"500 mg/day\" a fragment of a number. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aCommaAfterAPARENTHESISLeavesAStatedCeilingStated() throws IOException {
		// The third false-report shape found in this rule, and the one that settles its wording. A
		// thousands separator ALWAYS has a digit to its left; a comma that does not is punctuation,
		// whatever punctuation precedes IT. Keying the refusal on "no letter precedes" got that
		// wrong for every non-letter that is not a digit — a closing parenthesis, most obviously,
		// which is how an answer written by a model that annotates its rows reads.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("Ceilings: 2000 mg/day (route-unspecified),500 mg/day "
				+ "(suspension) [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer states BOTH ceilings; a comma with no digit before it is not a "
							+ "thousands separator. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aFullStopATTACHEDToWhatPrecedesItLeavesAStatedCeilingStated() throws IOException {
		// The fourth and last shape of this rule, and the one that decides its KIND. The three before
		// it each classified the CHARACTER before the separator, and each lost a character nobody had
		// thought of — a closing parenthesis here, a quote or an emphasis mark just as easily. The
		// question that ends that is not another character: it is whether the full stop BEGINS a
		// token. A decimal point does — it follows a space, or nothing. A sentence's full stop is
		// attached to the word, or bracket, or quote it ends.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("The ceiling is 2000 mg/day (route-unspecified).500 mg/day "
				+ "applies to the suspension [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer states BOTH ceilings; a full stop attached to a closing bracket ends "
							+ "a sentence, it does not begin a decimal. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aNakedDecimalAfterANONBREAKINGSpaceIsStillANakedDecimal() throws IOException {
		// "Begins a token" is asked as `Character.isWhitespace`, and that method answers FALSE for the
		// three spaces typeset copy actually uses to hold a number together — U+00A0, U+2007 and
		// U+202F. So the decimal point read as attached, the laxer ceiling was read out of the naked
		// decimal, and the answer was accused of dropping the number it led with. Narrow input, and
		// the severe direction, which is why it is fixed rather than documented: `isSpaceChar` admits
		// exactly those three and nothing else.
		PatientChart decimals = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of levothyroxine?", "Levothyroxine (paediatric)");
		RecordMapping mapping = soleRecordCarryingCeilings(decimals);
		TestableService service = newService(decimals);
		service.setLlmProvider(answering("The maximum for this presentation is\u00A0.5 mg/day ["
				+ mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of levothyroxine?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a non-breaking space before a naked decimal leaves it a naked decimal. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aCommaJOININGTwoWholeNumbersLeavesTheSecondOneStated() throws IOException {
		// Issue #425 item 1, and the shape none of the four cases above reaches: every one of them
		// puts a NON-digit before the separator, so the arm that asks after a digit was only ever
		// exercised by a genuine grouped number. A model listing a record's two ceilings elides the
		// unit on the first of them as readily as it repeats it — the case above is the repeated
		// form, "2000 mg/day,500 mg/day" — and with the unit elided the second ceiling sits behind a
		// digit and a comma. No grouped number can be spelled that way: a thousands separator's head
		// group is one to three digits, so a run of four before it belongs to no grouping at all.
		//
		// Refusing it accuses the answer of leaving out the number it printed, which is the one
		// direction this check must never fail in, and it is the SEVERE direction rather than the
		// gentle one because the walk turns an unstated STRICTEST into a report rather than silence.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		assertEquals(Arrays.asList("500 mg/day", "2000 mg/day"), mapping.getDosingCeilings(),
				"the premise: the strictest ceiling is the one written behind the comma, so refusing "
						+ "it leaves the laxer one at the top of the walk");
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("The suspension ceiling is 2000,500 mg/day; the tablet "
				+ "ceiling is 2000 mg/day [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the answer states BOTH ceilings — a run of four digits before the comma belongs "
							+ "to no grouped number, so \"500 mg/day\" is not a fragment of one. "
							+ "Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(),
					"and nothing is published, or the answer is accused of dropping the very number "
							+ "it printed behind the comma");
		}
	}

	@Test
	public void theMIDDLENumberOfACommaJoinedListIsNoFragmentEither() throws IOException {
		// The clause the case above does NOT reach, and the one place this rule declines to ask a
		// question it easily could. A separator inside a number has a group before it as well as
		// after, so "is the run before this comma itself preceded by a comma?" looks like a free
		// tightening — and it would refuse the MIDDLE number of a comma-joined list, which stands in
		// exactly that position. So the run's LENGTH is all that is asked. Add the tightening and
		// this case goes red while the two-item one still passes.
		PatientChart grouped = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of tinidazole?", "Tinidazole (oral suspension)");
		RecordMapping mapping = soleRecordCarryingCeilings(grouped);
		TestableService service = newService(grouped);
		service.setLlmProvider(answering("Doses recorded: 300,4000,500 mg/day for the suspension; "
				+ "the tablet ceiling is 2000 mg/day [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of tinidazole?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the strictest ceiling is stated, third in a comma-joined list — the run of four "
							+ "digits before it belongs to no grouping whether a comma precedes that "
							+ "run or not. Captured: " + capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and nothing is published");
		}
	}

	@Test
	public void aCommaDECIMALIsNoListHoweverLongItsIntegerPartIs() throws IOException {
		// The other half of the case above, and the half that decides how far its exception may
		// reach. A comma is a decimal point in most of the world, so "1000,5 mg/day" is a dose of
		// 1000.5 — and the laxer ceiling must not be read out of its FRACTION, which is the naked
		// decimal's defect (`aNakedDecimalDoesNotLetTheLaxerCeilingBeReadOutOfIt`) written with the
		// other separator. Admitting it here would accuse an answer of leaving out 0.5 mg/day when
		// the number it printed IS 0.5 mg/day, in the notation half the world writes it in.
		//
		// What keeps the two apart is the GROUP: a separator inside a number is followed by exactly
		// three digits, so "2000,500" can be a list of two numbers and "1000,5" cannot be anything
		// but one number. Drop that condition and this case goes red while the one above still
		// passes.
		PatientChart decimals = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of levothyroxine?", "Levothyroxine (paediatric)");
		RecordMapping mapping = soleRecordCarryingCeilings(decimals);
		assertEquals(Arrays.asList("0.5 mg/day", "5 mg/day"), mapping.getDosingCeilings(),
				"the premise: the laxer ceiling's spelling is a SUFFIX of this comma decimal's "
						+ "fraction, and the record's own spelling of the stricter one is absent");
		TestableService service = newService(decimals);
		service.setLlmProvider(answering("Her recorded dose is 1000,5 mg/day ["
				+ mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of levothyroxine?");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"\"1000,5 mg/day\" states no ceiling of this record — a single digit after the "
							+ "comma is no group, so this is one number and not two. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(),
					"and nothing is published, or the answer is accused of dropping the number its "
							+ "own comma decimal states");
		}
	}

	@Test
	public void theSTRICTESTCeilingTheAnswerStatesIsTheOneReported() throws IOException {
		// The walk's central rule, and degenerate on every other case in this file: they all run on a
		// TWO-ceiling record, where the one ceiling that can be reported is the only candidate. A
		// mutation reporting the LAST stated ceiling rather than the first passes all of them.
		//
		// With three, the rule has something to decide. An answer stating 200 and 600 while leaving
		// 60 unstated must be told (200, 60) and not (600, 60): the smallest true gap, which is what
		// makes UnstatedDosingCeiling's "strictly stricter" the useful statement rather than merely a
		// true one — a clinician reading (600, 60) would be shown a tenfold gap the answer did not
		// open.
		PatientChart three = DrugReferenceTestSupport.injectedReferenceChartOver(EDGES, 30,
				"What is the maximum daily dose of phenobarbital?", "Phenobarbital (neonatal)");
		RecordMapping mapping = soleRecordCarryingCeilings(three);
		assertEquals(Arrays.asList("60 mg/day", "200 mg/day", "600 mg/day"),
				mapping.getDosingCeilings(),
				"the premise: THREE ceilings, strictest first — and their spellings sort 200, 60, 600 "
						+ "(the space in \"60 mg/day\" ordering below the '0' of \"600\"), so a spelling "
						+ "sort would put a MIDDLE ceiling at position 0");
		TestableService service = newService(three);
		service.setLlmProvider(answering("The adult ceiling is 600 mg/day and the paediatric one is "
				+ "200 mg/day [" + mapping.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(),
					"What is the maximum daily dose of phenobarbital?");
			assertEquals(Collections.singletonList(
					new UnstatedDosingCeiling(mapping.getIndex(), "200 mg/day", "60 mg/day")),
					answer.getUnstatedDosingCeilings(),
					"the STRICTEST stated ceiling is reported against the strictest published one, and "
							+ "exactly one entry however many of a record's ceilings the answer states. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aBlankAnswerIsSilentAndPublishesAMeasurementOfNone() throws IOException {
		// Reachable rather than defensive: extractCitedReferences resolves the structured citations
		// array for a blank answer deliberately, so the walk can be reached with citations and no
		// prose. A degenerate output is not a fidelity defect.
		TestableService service = newService(chart);
		service.setLlmProvider(new StubProvider("",
				Collections.singletonList(Integer.valueOf(record.getIndex()))));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a blank answer states no ceiling and quotes none. Captured: "
							+ capture.describeAll());
			assertNotNull(answer.getUnstatedDosingCeilings(),
					"null would say the check failed; it ran");
			assertTrue(answer.getUnstatedDosingCeilings().isEmpty(), "and it found none");
		}
	}

	@Test
	public void theStreamingPathCarriesTheSameStatement() throws IOException {
		// The endpoint users actually hit. Both answer paths run every one of these checks, and a
		// statement wired into only one of them is the shape that reaches production silently.
		TestableService service = newService(chart);
		service.setLlmProvider(answering("The maximum daily dose of Acetylsalicylic acid is "
				+ laxest + " [" + record.getIndex() + "]."));
		List<ChartAnswer> ungrounded = new ArrayList<ChartAnswer>();
		ChartAnswer answer = service.searchStreaming(patient(), QUESTION, noTokens(), noTokens(),
				noCitations(), collectingInto(ungrounded));
		assertEquals(Collections.singletonList(
				new UnstatedDosingCeiling(record.getIndex(), laxest, strictest)),
				answer.getUnstatedDosingCeilings(),
				"the streaming path states it too");
		assertEquals(1, ungrounded.size(), "the premise: the early handoff fired");
		assertEquals(null, ungrounded.get(0).getUnstatedDosingCeilings(),
				"and the early `done` states NULL rather than an empty list — that answer is handed "
						+ "off before the check runs, so it has no measurement to report, and a "
						+ "client reading [] there would read it as a measurement of none");
	}

	@Test
	public void aCheckThatThrowsIsReportedAndTheAnswerStillReturns() {
		// The guard exists so a diagnostic can never break a clinical answer, and the mechanism has to
		// be a read THIS check makes and nothing before it does, or the throw lands somewhere else and
		// the case proves another class's catch. `extractCitedReferences` reads `getResourceType()`;
		// `ClassCodeFidelityCheck` and the prose check read `getText()`; the active-order check reads
		// `getOrderActive()`; the severity check reads `getFindingSeverity()` — all earlier, and none
		// of them reads `getDosingCeilings()`, which is why overriding that one reaches here.
		PatientChart throwing = new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Drug reference: Acetylsalicylic acid." + System.lineSeparator(),
				Arrays.<RecordMapping> asList(new RecordMapping(1,
						org.openmrs.module.chartsearchai.ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE,
						"acetylsalicylic-acid", null, "Drug reference: Acetylsalicylic acid.") {

					@Override
					public List<String> getDosingCeilings() {
						throw new IllegalStateException("dosing ceilings unavailable");
					}
				}),
				Collections.<Integer> emptyList());
		TestableService onThrowing = newService(throwing);
		onThrowing.setLlmProvider(answering("The maximum daily dose is 4000 mg/day [1]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = onThrowing.search(patient(), QUESTION);
			assertTrue(answer.getAnswer().contains("[1]"),
					"the answer must come back whatever the check does; got: " + answer.getAnswer());
			assertTrue(capture.hasMessageAt(Level.WARN, "Dosing-ceiling check failed"),
					"and the check's own failure must not be silent. Captured: "
							+ capture.describeAll());
			assertEquals(null, answer.getUnstatedDosingCeilings(),
					"a failed check states NO measurement, which is not a measurement of none — the "
							+ "distinction the wire preserves");
		}
	}

	/**
	 * @return the one mapping carrying dosing ceilings, selected by the FIELD and never by a resource
	 *         type — the same rule the check itself obeys, so a test that passed by naming
	 *         {@code drug_reference} could not tell a widened selection from a correct one
	 */
	private static RecordMapping soleRecordCarryingCeilings(PatientChart of) {
		List<RecordMapping> carrying = new ArrayList<RecordMapping>();
		for (RecordMapping mapping : of.getMappings()) {
			if (mapping.getDosingCeilings() != null) {
				carrying.add(mapping);
			}
		}
		assertEquals(1, carrying.size(),
				"exactly one record of this chart may carry ceilings, or the cases below are about "
						+ "an arrangement nobody described. Carrying: " + carrying.size());
		return carrying.get(0);
	}

	private static StubProvider answering(String answer) {
		return new StubProvider(answer);
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static Consumer<String> noTokens() {
		return new Consumer<String>() {

			@Override
			public void accept(String token) {
			}
		};
	}

	private static Consumer<List<RecordReference>> noCitations() {
		return new Consumer<List<RecordReference>>() {

			@Override
			public void accept(List<RecordReference> cited) {
			}
		};
	}

	private static Consumer<ChartAnswer> collectingInto(final List<ChartAnswer> into) {
		return new Consumer<ChartAnswer>() {

			@Override
			public void accept(ChartAnswer answer) {
				into.add(answer);
			}
		};
	}

	/** The same seam the sibling fidelity tests use, and defined here for the same reason they each
	 *  define their own: these are private harnesses, not a shared one. The injector is a passthrough
	 *  because the chart arrives ALREADY injected by the real one — this file's subject is what the
	 *  answer did with that chart, not how it was built. */
	private TestableService newService(PatientChart served) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question,
					ChartReadStatus readStatus) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing a shorter one instead leaves this stub
			// INERT — production would not reach it — which is why this names both parameters.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

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
