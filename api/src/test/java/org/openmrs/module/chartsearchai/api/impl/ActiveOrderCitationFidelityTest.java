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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ActiveOrderClaims;
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
		service.setLlmProvider(answering(ticketsFiveClaimAnswer()));
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
			assertFalse(capture.hasEventAtOrAbove(Level.WARN, SafetyFindingSeverityFidelityCheck.class),
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
		// The null assertion alone does NOT establish it, and review measured that: production hands
		// the early answer an explicit null in that argument, so it states null wherever the check
		// runs, and before the log snapshot below was added, moving the check ABOVE the handoff left
		// every case here green. It no longer does — that mutation reddens this case now, and the
		// snapshot is why: at handoff time the WARN must not have been emitted yet.
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
			assertTrue(capture.describeAll().toString().contains("active drug order"),
					"the capture must have received the check's own WARN by the end, or the negative "
							+ "below is satisfied by a capture that was never attached — the vacuity "
							+ "this file's PACKAGE constant names. Captured: " + capture.describeAll());
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
		TestableService onUntyped = newService(untypedRecordChart());
		onUntyped.setLlmProvider(answering(claimCitingRecordOne()));
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
			assertFalse(capture.hasEventAtOrAbove(Level.WARN, SafetyFindingSeverityFidelityCheck.class),
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
	public void aCommaSeparatedRunKeepsItsSecondMarker() {
		// Round 2 of this PR's review: the comma member of RUN_SEPARATORS was the one mechanism in
		// the check that no case pinned — deleting it left the whole build green. It is not
		// decoration. `LlmAnswerExtractor.rewriteShorthand` joins the markers it rewrites with a
		// comma and a space, so a model that writes the compact group `[finding, chart]` reaches
		// this check as `[finding], [chart]`; without the comma the run stops at the finding, which
		// is reference-group and passes, and the misattributed chart citation — the ticket's own
		// defect — is dropped from the WARN and from the wire with nothing to say so.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		int finding = findingsStatingThePhrase().get(0);
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin [" + finding
				+ "], [" + condition + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + condition + "]"),
					"the second marker of a comma-separated run is part of that run. Captured: "
							+ capture.describeAll());
			assertEquals(Collections.singletonList(Integer.valueOf(condition)),
					answer.getMisattributedOrderCitations(), "and it reaches the wire");
		}
	}

	@Test
	public void aCitationInTheNEXTCLAUSEOfTheSameSentenceIsNotAttributedToTheClaim() {
		// Round 1 of this PR's review, verbatim: the citation belongs to the clause it sits in, and
		// attributing it to the order claim reports a CORRECT citation — into a wire key a client
		// renders, which is #201's harm in a new place. The run may only BEGIN before the claim's own
		// clause ends.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering(claimWhoseOnlyCitationIsInTheNextClause(condition)));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a citation in the clause after the claim's own is that clause's. Captured: "
							+ capture.describeAll());
			assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
					"and nothing reaches the wire either");
		}
	}

	@Test
	public void theTicketsOwnRunStillBeginsBeforeItsClauseEnds() {
		// The other half of the clause bound, and what stops it from silencing the defect this check
		// exists for: in the reported answer the markers sit between the partner's name and the comma
		// that ends the claim, so the bound never reaches them.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", condition,
				findingsStatingThePhrase().get(0)) + ", Clarithromycin" + PHRASE + "Digoxin."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + condition + "]"),
					"the markers precede the comma, so the claim's own run carries them. Captured: "
							+ capture.describeAll());
			assertEquals(Collections.singletonList(Integer.valueOf(condition)),
					answer.getMisattributedOrderCitations(), "and it reaches the wire");
		}
	}

	@Test
	public void aLoneCitationInAClaimWithNoRunOfItsOwnIsAttributedToIt() {
		// Pins a residue rather than a defect, at the shape review found it in. The run is the FIRST
		// one after the phrase and nothing bounds the gap — the partner's name sits there and a name
		// has no fixed length, so a budget on it could only be arbitrary. Round 1 of this PR's review
		// narrowed it with a CLAUSE bound, which a length budget is not — but punctuation is all that
		// bound reads, so a coordinating conjunction with no comma before it, as here, still annexes
		// the next citation. That
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
			assertFalse(capture.hasEventAtOrAbove(Level.WARN, SafetyFindingSeverityFidelityCheck.class),
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
			assertEquals(null, answer.getActiveOrderClaims(),
					"and neither key is a measurement, because one walk produced both — a zeroed "
							+ "statement here would say the check ran and the answer stated no "
							+ "active-order claim (issue #379)");
		}
	}

	@Test
	public void anAnswerWhoseActiveOrderClaimsCiteOnlyTheirOwnFindingCountsEveryOneOfThemUncited() {
		// Issue #379. The shape the maintainer measured with
		// chartsearchai.drugSafety.citeOrderRecords ON: a lead sentence citing the allergy, then one
		// claim per sentence, each offering the module's own safety_finding and no chart record. The
		// other half of this check reads EMPTY there — there is nothing left to misattribute — and
		// that empty is what "reads like success" meant on the ticket. Both halves are asserted in
		// one case precisely because the defect is that one of them was readable without the other.
		//
		// FOUR claims and FIVE orders named: the last sentence reads "active order Dexamethasone and
		// active order Hydrocortisone", and the second name carries no "interacts with" before it. A
		// case asserting five here would be asserting a number the measured answer does not produce.
		List<Integer> findings = findingsStatingThePhrase();
		// The lead sentence carries a chart citation of its own and states no active-order claim, so
		// it must contribute to neither number — the measured answer opened the same way.
		int leadRecord = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		service.setLlmProvider(answering("No — Clarithromycin should not be started: she is already "
				+ "prescribed a drug that interacts with it [" + leadRecord + "]. Furthermore, "
				+ "Clarithromycin" + PHRASE + "Simvastatin because of an interaction ["
				+ findings.get(0) + "]. Clarithromycin also" + PHRASE + "Digoxin because of an "
				+ "interaction [" + findings.get(1) + "]. Additionally, Clarithromycin" + PHRASE
				+ "Amiodarone because of an interaction [" + findings.get(2) + "]. Finally, "
				+ "Clarithromycin" + PHRASE + "Warfarin and active order Metformin because of an "
				+ "interaction [" + findings.get(0) + "], [" + findings.get(1) + "]."));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
				"the premise: with no chart citation offered for any claim there is nothing to "
						+ "misattribute, so the older key reads as it does on a clean answer");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(4, claims.getStated(),
				"four occurrences of the module's own phrase, not the five orders the answer names "
						+ "— the compound attribution in the last sentence is ONE claim");
		assertEquals(4, claims.getUncited(),
				"and not one of them offered a chart record, which is what the empty list above "
						+ "could not say. Answer was: " + answer.getAnswer());
	}

	@Test
	public void oneSentenceWhoseClaimsAreCitedUnevenlyCountsOnlyTheUncitedOnes() {
		// The discriminator, and the reason the unit here is the marker RUN and not the sentence.
		// This answer states two claims in ONE sentence: the first offers a drug order, the second
		// offers only its finding. A sentence-scoped count reads zero — the sentence does cite a
		// drug order — and that is the same refutation ADR Decision 76 records for the other half,
		// on the ticket's own one-sentence reproduction. The maintainer's second comment names this
		// shape as a requirement rather than an edge: "any fix has to keep working when one sentence
		// carries several attributions".
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		List<Integer> findings = findingsStatingThePhrase();
		service.setLlmProvider(answering(sentenceFragment("Simvastatin", orders.get(0),
				findings.get(0)) + ", and Clarithromycin" + PHRASE + "Digoxin ["
				+ findings.get(1) + "]."));
		ActiveOrderClaims claims = service.search(patient(), QUESTION).getActiveOrderClaims();
		assertEquals(2, claims.getStated(), "two claims in one sentence");
		assertEquals(1, claims.getUncited(),
				"and exactly the one that offered no chart record. A sentence-scoped count reads 0 "
						+ "here, which is the unit ADR Decision 76 measured and rejected");
	}

	@Test
	public void aClaimWithNoMarkersOfItsOwnDoesNotTakeTheNextClaimsCitation() {
		// The bound the scan takes from the NEXT occurrence of the phrase, which #377 measured as
		// byte-identical for the ACCUSATION and which the claim count made load-bearing. This
		// sentence carries no comma, so clauseBound stops nothing and the first claim's region ends
		// where the second claim begins: replace that bound with sentence.length() and the first
		// claim reaches the SECOND's chart citation, reading uncited 0 here. Two claims, one
		// citation, and it belongs to the claim it follows — mutate the bound and read the failures.
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin and Clarithromycin"
				+ PHRASE + "Digoxin [" + orders.get(0) + "]."));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
				"the premise: the one citation offered is her own drug order, so nothing here is "
						+ "accused and this case discriminates the claim count alone");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(2, claims.getStated(), "two claims in one sentence");
		assertEquals(1, claims.getUncited(),
				"and the first offered no chart record of its own — a later claim's citation is not "
						+ "evidence for the one before it. Answer was: " + answer.getAnswer());
	}

	@Test
	public void aClaimCitingAChartRecordIsNotUncitedEvenWhereThatRecordIsMisattributed() {
		// The two halves count different things — a CLAIM here, a CITATION there — and this is the
		// case that shows it: the ticket's own five-claim arrangement, three of whose chart citations
		// cannot be an order. Every claim OFFERED something, so none is uncited; three offered the
		// wrong thing, which is the other key's answer. Collapsing the two — counting a misattributed
		// claim as uncited — reddens here.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		int visit = indexOfType("visit");
		int encounter = indexOfType("encounter");
		service.setLlmProvider(answering(ticketsFiveClaimAnswer()));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertEquals(Arrays.asList(condition, visit, encounter),
				answer.getMisattributedOrderCitations(),
				"the premise: three of the five chart citations cannot be an order");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(5, claims.getStated(), "five claims, in one sentence");
		assertEquals(0, claims.getUncited(),
				"and none of them uncited — a claim that cited the WRONG record still offered one, "
						+ "and saying otherwise would count one failure twice");
	}

	@Test
	public void aClaimWhoseOnlyChartCitationSitsInTheNextClauseIsCountedUncited() {
		// The residue, pinned rather than left to be found. The run may only BEGIN before the claim's
		// own clause ends, so the citation after the comma is that clause's — which is why the other
		// half stays silent here (asserted, so this case cannot pass by the check simply not
		// running). For THIS half the same unit yields a report, and the report is defensible: no
		// chart record was offered inside the claim's own clause. What must never be read off it is
		// that the ANSWER cites nothing.
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		service.setLlmProvider(answering(claimWhoseOnlyCitationIsInTheNextClause(condition)));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
				"the premise: the other half attributes nothing to a claim whose clause carries no "
						+ "markers, which is what keeps it from accusing a correct citation");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(1, claims.getStated(), "one claim");
		assertEquals(1, claims.getUncited(),
				"and it offered no chart record in its own clause. The residues of the two halves "
						+ "run opposite ways over one unit, which is stated rather than closed");
	}

	@Test
	public void aClaimWhoseOnlyMarkerIsNoCitationOfThisAnswerIsCountedUncited() {
		// The admitted-index gate, which nothing discriminated before this case: the check considers
		// only indexes the answer's OWN resolution turned into references (CLAUDE.md's inline-citation
		// rule — an index is not a citation until that resolution admits it). A bracketed number the
		// chart has no record for is a clinical value, not evidence, so the claim offered nothing.
		// Disabling the gate makes this read uncited 0, because the missing mapping then reaches
		// offersChartEvidence's null arm, which answers "evidence" — the two fail safe in opposite
		// directions and only this case holds them apart.
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin [9999]."));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
				"the premise: an index the answer resolved no reference for is not accused either");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(1, claims.getStated(), "one claim");
		assertEquals(1, claims.getUncited(),
				"and it offered no chart record — a bracket the chart has no record for is a "
						+ "clinical value, never a citation");
	}

	@Test
	public void anAnswerThatNeverStatesThePhraseStatesNoClaimsRatherThanNoMeasurement() {
		// The short-circuit must not become a silent absence: zero says the check ran and the answer
		// stated no active-order claim, which is the overwhelmingly common response and is exactly
		// what a client needs to tell from the failed check's null.
		service.setLlmProvider(answering("Her most recent haemoglobin was 11.2 g/dL [1]."));
		ActiveOrderClaims claims = service.search(patient(), QUESTION).getActiveOrderClaims();
		assertEquals(0, claims.getStated(), "no claim was stated");
		assertEquals(0, claims.getUncited(), "so none of them is uncited");
	}

	@Test
	public void searchStreaming_statesTheClaimsOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		// /search/stream is the path users hit, and the async early `done` is built BEFORE any check
		// runs — so it has no measurement to state, and null says that where a zeroed statement
		// would tell a client the claims had been examined and every one found cited.
		List<Integer> findings = findingsStatingThePhrase();
		service.setLlmProvider(answering("Clarithromycin" + PHRASE + "Simvastatin ["
				+ findings.get(0) + "]."));
		final List<ActiveOrderClaims> early = new ArrayList<ActiveOrderClaims>();
		ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { },
				reasoning -> { }, citations -> { },
				ungrounded -> early.add(ungrounded.getActiveOrderClaims()));
		assertEquals(1, early.size(), "the early-done consumer must have fired, or the null below is "
				+ "the absence of a callback rather than the absence of a measurement");
		assertEquals(null, early.get(0),
				"the early answer states NO measurement: it is handed off before the check runs");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(1, claims.getStated(), "and the answer this method RETURNS carries it");
		assertEquals(1, claims.getUncited(),
				"the claim offered only its own finding, which is reference material and not a "
						+ "chart record");
	}

	@Test
	public void aCitedRecordWhoseTypeTheModuleCouldNotReadSilencesItsClaim() {
		// The conservatism of THIS half runs opposite to refusal()'s and must be stated once rather
		// than inferred: where the module cannot read a cited record's type it must not say the claim
		// offered nothing. referenceGroup fails safe to chart evidence for an unreadable type, so the
		// claim is not counted — the same record the other half declines to ACCUSE.
		TestableService onUntyped = newService(untypedRecordChart());
		onUntyped.setLlmProvider(answering(claimCitingRecordOne()));
		ChartAnswer answer = onUntyped.search(patient(), QUESTION);
		assertTrue(answer.getMisattributedOrderCitations().isEmpty(),
				"the premise: an unreadable type is not accused");
		ActiveOrderClaims claims = answer.getActiveOrderClaims();
		assertEquals(1, claims.getStated(), "one claim");
		assertEquals(0, claims.getUncited(),
				"and it is not counted uncited either — a record the module could not read is one "
						+ "it cannot say anything about, in EITHER direction");
	}

	/** "Clarithromycin interacts with active order X [chart] [finding]" — production's own phrase,
	 *  read off the constant the renderer builds the chip detail from. */
	private static String sentenceFragment(String partner, int chartIndex, int findingIndex) {
		return "Clarithromycin" + PHRASE + partner + " [" + chartIndex + "] [" + findingIndex + "]";
	}

	/** The ticket's own reported answer: five active-order claims in ONE sentence, three citing a
	 *  record that cannot be an order and two citing her own drug orders, behind a lead sentence
	 *  citing the finding. Two cases drive it — that the three are accused, and that none of the five
	 *  is counted uncited — and ADR Decision 81's "conjoining the two reddens exactly one case" rests
	 *  on their being ONE arrangement, so they must not be able to drift apart. */
	private String ticketsFiveClaimAnswer() {
		int condition = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION);
		int visit = indexOfType("visit");
		int encounter = indexOfType("encounter");
		List<Integer> orders = indexesOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		List<Integer> findings = findingsStatingThePhrase();
		return "No — Clarithromycin should not be started: The patient has "
				+ "a recorded allergy to Clarithromycin [" + findings.get(0) + "]. Furthermore, "
				+ sentenceFragment("Simvastatin", condition, findings.get(0)) + ", "
				+ sentenceFragment("Digoxin", visit, findings.get(1)) + ", "
				+ sentenceFragment("Amiodarone", encounter, findings.get(2)) + ", "
				+ sentenceFragment("Warfarin", orders.get(0), findings.get(0)) + ", and "
				+ sentenceFragment("Metformin", orders.get(1), findings.get(1)) + ".";
	}

	/** A claim whose own clause carries no marker, the sentence's only citation sitting in the clause
	 *  after it. Two cases drive it — that the accusation stays silent and that the claim IS counted
	 *  uncited — and that pair is what ADR Decision 81's "published and not logged" rests on, so they
	 *  must stay one arrangement. */
	private static String claimWhoseOnlyCitationIsInTheNextClause(int condition) {
		return "Clarithromycin" + PHRASE + "Simvastatin, which she has "
				+ "been taking since 2024 for her benign thyroid neoplasm [" + condition + "].";
	}

	/** The claim the two untyped-chart cases state: one active-order claim citing record [1] twice.
	 *  Extracted for the same reason {@code untypedRecordChart()} was — an arrangement is its answer
	 *  as well as its records, and those two cases must stay one arrangement. */
	private static String claimCitingRecordOne() {
		return sentenceFragment("Simvastatin", 1, 1) + ".";
	}

	/** A chart of ONE record whose resource type the module could not read. The two cases that use
	 *  it are the two halves of one arrangement — that such a record is neither ACCUSED nor counted
	 *  as offering nothing — so they must stay the same arrangement. */
	private static PatientChart untypedRecordChart() {
		return new PatientChart(
				"Patient" + System.lineSeparator() + System.lineSeparator()
						+ "[1] Simvastatin 20mg" + System.lineSeparator(),
				Arrays.<RecordMapping> asList(
						new RecordMapping(1, null, "record-uuid-untyped", null, "Simvastatin 20mg")),
				Collections.<Integer> emptyList());
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
