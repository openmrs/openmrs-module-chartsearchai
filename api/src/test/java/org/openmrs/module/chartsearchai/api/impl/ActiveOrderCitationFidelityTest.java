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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.PatientClinicalContext;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Issue #377: an answer sentence reproducing this module's own finding — <em>"X interacts with
 * active order Y"</em> — attaches a CHART citation to it that points at a record which is not that
 * order. Measured on a real standalone, three of five such sentences in one answer cited a
 * condition, a visit and an encounter; a clinician following the citation behind <em>"interacts
 * with active order Methylprednisolone"</em> was shown <em>Benign neoplasm of thyroid gland</em>.
 *
 * <p>Nothing could see it. Every citation in that response serialized {@code grounded: null},
 * because a chart citation whose sentence also rests on a {@code safety_finding} has its entailment
 * negative withheld (issue #284) — a carve-out {@link CitationGroundingVerifier}'s own javadoc
 * names, along with the residue it accepts. The two exact comparisons that ran before this one
 * compare what the answer states about the REFERENCE records it cites, not which CHART record a
 * sentence was attached to.
 *
 * <p>What this file pins is the deterministic check that closes the reported class: where the
 * answer states the module's own active-order phrase, the chart citations offered for that claim
 * must point at records that can describe a medication order at all, and must not point at an order
 * the chart itself marks as no longer in force. It reports; it never rewrites; and its answer is
 * published so a client can tell a good citation from a bad one, which the wire could not before.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration over a chart the real {@link PatientChartSerializer} rendered and the real
 * {@code DrugSafetyValidator} → {@code injectRecords} → {@code renderFinding} chain injected
 * findings into, off the SHIPPED knowledge base — which carries the ticket's own pairs, so the
 * arrangement is the reported one rather than an imitation of it. Only the model is stubbed: answer
 * prose is not reproducible on a live engine, and the answer is the one variable this check is
 * about.
 */
public class ActiveOrderCitationFidelityTest {

	/** The ticket's own question, on the ticket's own drug. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	/** The patient's active orders. The pinned DDInter excerpt rates every one of these Major against
	 *  Clarithromycin, so the drug-in-play arm raises a rule finding for each — asserted in
	 *  {@link #setUp()}, never assumed. The ticket's own five partners are corticosteroids the
	 *  EXCERPT does not carry; the shipped knowledge base does rate three of them against
	 *  Clarithromycin, and pointing this file at 2283 substances would make it a test of the prompt
	 *  budget's truncation instead (the reason {@code DrugReferenceTestSupport.ddinterService} exists).
	 *  What the ticket contributes here is the SHAPE of the answer, which is what the check reads. */
	private static final String[] PARTNERS = { "Simvastatin", "Digoxin", "Amiodarone", "Warfarin" };

	private static final String[] PARTNER_ATC = { "C10AA01", "C01AA05", "C01BD01", "B01AA03" };

	/** Read off production, so no case can pass against a phrase no finding carries. */
	private static final String PHRASE = DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE;

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported"
	 *  assertion, so no other class's WARN can stand in for this check's. */
	private static final String CHECK =
			"org.openmrs.module.chartsearchai.api.impl.ActiveOrderCitationFidelityCheck";

	/** The package, for every assertion whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously ({@link LogCapture}'s javadoc), so those cases capture the package instead, where
	 *  {@code LlmInferenceService}'s own [timing] INFO line proves the capture is live. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private PatientChart chart;

	private TestableService service;

	@BeforeEach
	public void setUp() {
		chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
				setOf(PARTNERS), setOf(PARTNER_ATC));
		assertTrue(findingsStatingThePhrase().size() >= 3,
				"the premise: the real pipeline injects one active-order interaction finding per "
						+ "partner, stating the phrase production renders. Chart was: "
						+ chart.getText());
		service = newService(chart);
	}

	@Test
	public void theTicketsOwnAnswerReportsTheThreeMisattributedCitationsAndNotTheTwoCorrectOnes() {
		// The reported shape, verbatim in structure: five findings in ONE sentence, each followed by
		// a chart citation and the finding's own. Three of the chart citations are a condition, a
		// visit and an encounter; two are the patient's own drug orders. A check whose unit is the
		// SENTENCE cannot separate them — the sentence cites two correct drug orders — so this case
		// is what fails if the unit is not the citation run following each phrase occurrence.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		int visit = indexOfType("visit");
		int encounter = indexOfType("encounter");
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		List<Integer> findings = findingsStatingThePhrase();
		service.setLlmProvider(answering("No — Clarithromycin should not be started: The patient has "
				+ "a recorded allergy to Clarithromycin [" + findings.get(0) + "]. Furthermore, "
				+ sentenceFragment("Simvastatin", condition, findings.get(0)) + ", "
				+ sentenceFragment("Digoxin", visit, findings.get(1)) + ", "
				+ sentenceFragment("Amiodarone", encounter, findings.get(2)) + ", "
				+ sentenceFragment("Warfarin", orders.get(0), findings.get(0)) + ", and "
				+ sentenceFragment("Metformin", orders.get(1), findings.get(1)) + "."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + condition + "]", "[" + visit + "]",
					"[" + encounter + "]", "patient=1"),
					"all three misattributed citations must be reported in one line, carrying the "
							+ "patient so a maintainer reading a log with concurrent requests in it "
							+ "can reconstruct it. Captured: " + capture.describeAll());
			assertFalse(warnStating(capture, "[" + orders.get(0) + "]"),
					"the citation that DOES point at one of her drug orders must not be reported, or "
							+ "the check cannot tell a good citation from a bad one. Captured: "
							+ capture.describeAll());
			assertEquals(Arrays.asList(condition, visit, encounter),
					answer.getMisattributedOrderCitations(),
					"and the same three must reach the wire, in the order the answer states them — "
							+ "the citation and never a word of either text");
		}
	}

	@Test
	public void aCitationOfHerOwnDrugOrderIsSilentAndTheMeasurementIsPublishedAsNone() {
		// The other half of the pair above, on the same arrangement: that one fails if the check
		// never runs, this one fails if it accuses a correct citation. Neither alone discriminates.
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", orders.get(0),
				findingsStatingThePhrase().get(0)) + "."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a citation that points at the patient's own drug order is the shape this check "
							+ "exists to leave alone. Captured: " + capture.describeAll());
			assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
					"and an empty list is a measurement of none, which is what a client reads to "
							+ "know the check ran");
		}
	}

	@Test
	public void searchStreaming_reportsItOnThePrimaryProductionPathToo() {
		// /search/stream is the path users hit: a check wired only into search() would be absent from
		// production traffic while every non-streaming case here stayed green.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", condition,
				findingsStatingThePhrase().get(0)) + "."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "[" + condition + "]"),
					"the streaming path must run the same check. Captured: " + capture.describeAll());
			assertEquals(Collections.singletonList(Integer.valueOf(condition)),
					answer.getMisattributedOrderCitations(),
					"and the answer this method RETURNS must carry the measurement");
		}
	}

	@Test
	public void searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		// The early `done` of the async-grounding path is built BEFORE this check runs, so it has no
		// measurement to state — and null says exactly that, where an empty list would tell a client
		// the citations had been examined and found sound. That PRODUCTION states nothing there is
		// this case's claim; the controller half of it is the wire test's.
		//
		// The null assertion alone does NOT establish it, and review measured that: the early answer
		// is built from a shorter constructor that has no such field, so it states null wherever the
		// check runs, and moving the check ABOVE the handoff left every case here green. What the
		// check's POSITION is pinned by is the log snapshot — at handoff time the WARN must not have
		// been emitted yet.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", condition,
				findingsStatingThePhrase().get(0)) + "."));
		final List<List<Integer>> early = new ArrayList<List<Integer>>();
		final List<List<String>> loggedByHandoff = new ArrayList<List<String>>();

		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { },
					reasoning -> { }, citations -> { },
					ungrounded -> {
						early.add(ungrounded.getMisattributedOrderCitations());
						loggedByHandoff.add(capture.describeAll());
					});

			assertEquals(1, early.size(), "the early-done consumer must have fired");
			assertEquals(null, early.get(0),
					"the check runs after the user-visible handoff, so the early answer states no "
							+ "measurement");
			assertFalse(loggedByHandoff.get(0).toString().contains("active drug order"),
					"and the check must not have RUN by then — moving it ahead of the handoff puts a "
							+ "comparison in front of the event a user sees. Captured at handoff: "
							+ loggedByHandoff.get(0));
			assertEquals(Collections.singletonList(Integer.valueOf(condition)),
					answer.getMisattributedOrderCitations(),
					"and the answer this method RETURNS carries it");
		}
	}

	@Test
	public void aCitedRecordWhoseTypeTheModuleCouldNotReadIsNotAccused() {
		// The allow-list refuses an unrecognised type, and a type that was never READ is not an
		// unrecognised one — referenceGroup's fail-safe calls it chart evidence, so without its own
		// guard this record is reported as "null record", which is an accusation about metadata
		// nobody read. Silence is the direction this check must fail in.
		PatientChart untyped = new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Simvastatin 20mg" + System.lineSeparator(),
				Arrays.<RecordMapping> asList(
						new RecordMapping(1, null, "record-uuid-untyped", null, "Simvastatin 20mg")),
				Collections.<Integer> emptyList());
		TestableService onUntyped = newService(untyped);
		onUntyped.setLlmProvider(answering(sentenceFragment("Simvastatin", 1, 1) + "."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = onUntyped.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a record with no readable type cannot be said not to be an order. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
					"and nothing reaches the wire either");
		}
	}

	@Test
	public void anAnswerThatNeverStatesThePhraseIsNotChecked() {
		// The gate. Without it this stops being a rule about active-order claims and becomes a rule
		// about which records an answer may cite at all — every condition cited in any answer would
		// be reported.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering("She has a benign thyroid neoplasm on the problem list ["
				+ condition + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an answer stating no active-order claim offers no chart citation for one. "
							+ "Captured: " + capture.describeAll());
			assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
					"the check ran and measured none; it is not the absence of a measurement");
		}
	}

	@Test
	public void aChartCitationInALaterClauseOfTheSameSentenceIsNotAttributedToTheClaim() {
		// Why the unit is the citation RUN and not the sentence. The allergy clause after the run is
		// its own claim with its own citation; attributing it to the active-order claim would report
		// a correct citation, which is the crying-wolf direction this check must not fail in.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", orders.get(0),
				findingsStatingThePhrase().get(0)) + ", and her thyroid neoplasm [" + condition
				+ "] is unrelated."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"only the run of markers immediately after the phrase is offered for the claim. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitationInTheNEXTSentenceIsNotAttributedToTheClaim() {
		// The cross-sentence twin of the later-clause case below, and what pins the per-sentence
		// split. Review measured that replacing that split with one whole-answer pass left every
		// other case in this file green while changing 1,856 of 66,429 generated arrangements: the
		// claim here carries no markers of its own, so without the split the next sentence's
		// citation becomes the claim's — a correct citation of a condition, reported. Silence is the
		// direction this check must fail in.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin. She also has a "
				+ "benign thyroid neoplasm [" + condition + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a citation in the following sentence is that sentence's. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aLoneCitationInAClaimWithNoRunOfItsOwnIsAttributedToIt() {
		// Pins a residue rather than a defect, at the shape review found it in. The run is the FIRST
		// one after the phrase and nothing bounds the gap — the partner's name sits there and a name
		// has no fixed length, so a budget on it could only be arbitrary. So where the claim carries
		// no markers of its own, the next citation in the sentence is read as offered for it. That
		// reading is defensible (a lone citation at the end of a sentence is conventionally offered
		// for the sentence, and this sentence asserts the order), but it is wider than the shape the
		// ticket measured, so it is recorded here as a decision. Narrowing it later must argue with
		// this case rather than drift past it.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin and she also has a "
				+ "benign thyroid neoplasm [" + condition + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + condition + "]"),
					"today this is reported; the case exists so that stops being a surprise and "
							+ "starts being a decision. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void theFindingsOwnCitationInTheRunIsNotReported() {
		// The reference-group half. Every one of these runs carries the module's own safety_finding
		// beside the chart citation — that is the shape the ticket measured — and reporting it would
		// make the check fire on every correct answer it sees.
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		int finding = findingsStatingThePhrase().get(0);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", orders.get(0),
				finding) + "."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertFalse(warnStating(capture, "[" + finding + "]"),
					"the module's own finding record is reference material, not chart evidence about "
							+ "an order, and is legitimately cited here. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void thisModulesOwnRecordForAnUnsubstantiatedActiveOrderIsAdmitted() {
		// The #118 record. It is INJECTED, so an admissibility test keyed on "did querystore retrieve
		// it" would refuse the module's own authoritative read of one of her prescriptions — and it
		// is the only chart record such an order has, the retrieved chart carrying none. Built by the
		// real injector off an order the base chart cannot substantiate, not by typing a record
		// active_drug_order by hand.
		PatientChart withInjectedOrder = DrugReferenceTestSupport.injectedFindingsOver(baseChart(),
				QUESTION, setOf(PARTNERS), setOf(PARTNER_ATC),
				Collections.singletonList(new PatientClinicalContext.ActiveDrugOrder(
						"order-uuid-unsubstantiated", "Simvastatin Co 20mg", setOf("Simvastatin"))));
		int injected = -1;
		for (RecordMapping mapping : withInjectedOrder.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(
					mapping.getResourceType())) {
				injected = mapping.getIndex();
			}
		}
		assertTrue(injected > 0, "the premise: the real injector minted an active_drug_order record "
				+ "for the order the chart cannot substantiate. Chart was: "
				+ withInjectedOrder.getText());
		TestableService onInjected = newService(withInjectedOrder);
		int finding = -1;
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedFindings(withInjectedOrder)) {
			if (mapping.getText() != null && mapping.getText().contains(PHRASE)) {
				finding = mapping.getIndex();
				break;
			}
		}
		onInjected.setLlmProvider(answering(sentenceFragment("Simvastatin", injected, finding) + "."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = onInjected.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the module's own record of an active order IS evidence of one. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
					"and nothing reaches the wire either");
		}
	}

	@Test
	public void anOrderTheChartMarksAsNoLongerInForceIsReported() {
		// The second rule. The sentence claims an ACTIVE order, so a record that IS one of her drug
		// orders and that the chart says is over cannot be evidence of it — issue #317's three-valued
		// mark, read through the mapping the chart carries. Only FALSE reports: null is "the module
		// cannot say" and stays silent, which is what the record below's live sibling asserts by
		// being silent in every other case in this file.
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-stopped", "Simvastatin 20mg tablet, stopped 2026-01-04", null,
				Collections.<String> emptyList(), null, null, Boolean.FALSE));
		PatientChart stopped = DrugReferenceTestSupport.injectedFindingsOver(
				new PatientChartSerializer().serialize(null, records,
						Collections.<String> emptySet()),
				QUESTION, setOf(PARTNERS), setOf(PARTNER_ATC));
		TestableService onStopped = newService(stopped);
		int order = -1;
		int finding = -1;
		for (RecordMapping mapping : stopped.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER.equals(mapping.getResourceType())) {
				order = mapping.getIndex();
			}
			else if (finding < 0 && mapping.getText() != null && mapping.getText().contains(PHRASE)) {
				finding = mapping.getIndex();
			}
		}
		assertEquals(Boolean.FALSE, stopped.getMappings().get(order - 1).getOrderActive(),
				"the premise: the real serializer carried the order-currency mark through");
		onStopped.setLlmProvider(answering(sentenceFragment("Simvastatin", order, finding) + "."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = onStopped.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + order + "]", "no longer in force"),
					"an ended order cannot be the ACTIVE order the sentence names, and the WARN has "
							+ "to say which of the two rules refused. Captured: "
							+ capture.describeAll());
			assertEquals(Collections.singletonList(Integer.valueOf(order)),
					answer.getMisattributedOrderCitations(),
					"and it reaches the wire like any other");
		}
	}

	@Test
	public void aCheckThatThrowsIsReportedAndTheAnswerStillReturns() {
		// The guard exists so a diagnostic can never break a clinical answer. The mechanism has to be
		// a read this check makes and nothing before it does: extractCitedReferences reads
		// getResourceType() and ClassCodeFidelityCheck reads getText(), both earlier, so overriding
		// either throws somewhere else. getOrderActive() is read by nothing on this path before the
		// check — the injector is stubbed here and grounding runs after — so the throw lands in it.
		PatientChart throwing = new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Drug order: Simvastatin 20mg" + System.lineSeparator(),
				Arrays.<RecordMapping> asList(new RecordMapping(1,
						ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "order-uuid-throwing", null,
						"Drug order: Simvastatin 20mg") {

					@Override
					public Boolean getOrderActive() {
						throw new IllegalStateException("order currency unavailable");
					}
				}),
				Collections.<Integer> emptyList());
		TestableService onThrowing = newService(throwing);
		onThrowing.setLlmProvider(answering(sentenceFragment("Simvastatin", 1, 1) + "."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = onThrowing.search(patient(), QUESTION);
			assertTrue(answer.getAnswer().contains(PHRASE),
					"the answer must come back whatever the check does; got: " + answer.getAnswer());
			assertTrue(warnStating(capture, "Active-order citation check failed"),
					"and the check's own failure must not be silent. Captured: "
							+ capture.describeAll());
			assertEquals(null, answer.getMisattributedOrderCitations(),
					"a failed check states NO measurement, which is not a measurement of none");
		}
	}

	/** "Clarithromycin interacts with active order X [chart] [finding]" — production's own phrase,
	 *  read off the constant the renderer builds the chip detail from. */
	private static String sentenceFragment(String partner, int chartIndex, int findingIndex) {
		return "Clarithromycin" + PHRASE + partner + " [" + chartIndex + "] [" + findingIndex + "]";
	}

	/** The base chart, rendered by the REAL serializer: three of the patient's own drug orders and
	 *  the three record types the ticket's answer wrongly cited. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Warfarin 5mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Metformin 500mg tablet, twice daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
				"condition-uuid-1", "Benign neoplasm of thyroid gland", null));
		records.add(new SerializedRecord("visit", "visit-uuid-1", "Home Visit at Site 42", null));
		records.add(new SerializedRecord("encounter", "encounter-uuid-1", "Consultation", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	/** The injected findings that really state the active-order phrase, by citation index and in
	 *  chart order — read off the record text production rendered, never off a partner name this
	 *  file chose. */
	private List<Integer> findingsStatingThePhrase() {
		List<Integer> out = new ArrayList<Integer>();
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (mapping.getText() != null && mapping.getText().contains(PHRASE)) {
				out.add(Integer.valueOf(mapping.getIndex()));
			}
		}
		return out;
	}

	private int indexOfType(String resourceType) {
		List<Integer> found = indexesOfType(resourceType);
		if (found.isEmpty()) {
			throw new IllegalStateException("no " + resourceType + " record in: " + chart.getText());
		}
		return found.get(0).intValue();
	}

	private List<Integer> indexesOfType(String resourceType) {
		List<Integer> out = new ArrayList<Integer>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceType.equals(mapping.getResourceType())) {
				out.add(Integer.valueOf(mapping.getIndex()));
			}
		}
		return out;
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
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
