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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.InteractionClaimPairs;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
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
 * An answer stating <em>"X interacts with active order Y"</em> is held to the findings that relate X
 * and Y — issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/514">#514</a>.
 *
 * <p><b>The defect.</b> On the external DDI evaluation's demo patients, a question listing her
 * medications before asking about a new drug put findings about several subjects in the prompt, and
 * the answer gave one drug's finding to another — <em>"Metformin interacts with active order
 * Lamivudine / zidovudine … [353]"</em>, where [353] is Stavudine's — or stated a pair no finding
 * raised, citing nothing. Every published key read clean: {@code misattributedOrderCitations} judges
 * CHART citations, {@code unfaithfullyRenderedCitations} needs copied words, and
 * {@code unstatedFindingSeverities} judges ratings.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming} over a
 * chart whose {@code safety_finding} records the REAL injector wrote off the DDInter excerpt, and the
 * post-answer validator hands back the chips the REAL validator raises over the answer it is given —
 * so a drug only the answer names raises the chip production would raise for it. Only the model is
 * stubbed: answer prose is not reproducible on a live engine, and it is the input these cases vary.
 * Every record number an answer cites is read off the chart, never assumed.
 */
public class InteractionClaimPairFidelityTest {

	/** The ticket's question shape: her medications listed, then a new drug asked about — which puts
	 *  findings about several SUBJECTS in the prompt (#513). */
	private static final String LISTING_QUESTION = "The patient is currently on Warfarin, Simvastatin "
			+ "and Amiodarone, is it safe to give Clarithromycin?";

	/** A question naming only the drug asked about, so the findings the prompt carries are about it
	 *  alone and a current medication the ANSWER names raises chips no carried finding has. */
	private static final String SINGLE_DRUG_QUESTION = "Is it safe to start her on clarithromycin?";

	private static final Set<String> ORDERS = setOf("Warfarin", "Simvastatin", "Amiodarone", "Digoxin");

	private static final Set<String> ORDER_ATC = setOf("B01AA03", "C10AA01", "C01BD01", "C01AA05");

	private static final String CHECK = InteractionClaimPairFidelityCheck.class.getName();

	@Test
	public void aFindingAboutAnotherDrugCitedForTheClaimIsReportedAsMisattributed() {
		// The ticket's case 2, with the marker in the claim's own run: the finding cited is about
		// Simvastatin, and the sentence gives it to Clarithromycin.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		String answer = "No — Clarithromycin interacts with active order Amiodarone ["
				+ simvastatinsFinding + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertNotNull(pairs, "the check ran, so it states a measurement");
		assertEquals(Collections.singletonList(Integer.valueOf(simvastatinsFinding)),
				pairs.getMisattributedCitations(),
				"the finding cited is about another drug than the claim's subject, was: " + pairs);
		assertEquals(1, pairs.getJudged(), "one claim was judged, was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "and it cited a finding, so it is not unfounded: " + pairs);
	}

	@Test
	public void searchStreaming_reportsItOnTheAnswerItReturnsAndNotOnTheEarlyOne() {
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		String answer = "Clarithromycin interacts with active order Amiodarone [" + simvastatinsFinding
				+ "].";
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();

		ChartAnswer returned = arrangement.service(answer).searchStreaming(patient(), LISTING_QUESTION,
				token -> { }, reasoning -> { }, citations -> { }, early::add);

		assertEquals(1, early.size(), "the early-done consumer must have fired");
		assertNull(early.get(0).getInteractionClaimPairs(),
				"the early answer is handed off before the chips exist, so it states no measurement");
		assertNotNull(returned.getInteractionClaimPairs(), "the returned answer carries it");
		assertEquals(Collections.singletonList(Integer.valueOf(simvastatinsFinding)),
				returned.getInteractionClaimPairs().getMisattributedCitations(),
				"the streaming path runs the same check, was: " + returned.getInteractionClaimPairs());
	}

	@Test
	public void aClaimCitingItsOwnFindingIsNotReported() {
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin interacts with active order Amiodarone ["
				+ arrangement.finding("Clarithromycin", "Amiodarone") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(1, pairs.getJudged(), "the claim was judged and found related, was: " + pairs);
	}

	@Test
	public void aClaimStatingItsFindingsPairTheOtherWayRoundIsNotReported() {
		// An interaction relates two drugs whichever the sentence puts first; the check asks whether a
		// finding relates the two the claim names, not which of them the finding's sentence led with.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Amiodarone interacts with active order Clarithromycin ["
				+ arrangement.finding("Clarithromycin", "Amiodarone") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(1, pairs.getJudged(), "judged and found related, not left unjudged, was: " + pairs);
	}

	@Test
	public void aPairNoFindingRelatesStatedWithNoCitationIsUnfounded() {
		// The ticket's case 3: a pair no finding raised, and nothing cited for it. Heparin is not one of
		// her orders and no finding names it — the invented partner.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin can be given with care, and Clarithromycin interacts with active "
				+ "order Heparin.";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(1, pairs.getUnfounded(), "no finding relates the pair, was: " + pairs);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(1, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aFindingMarkerPastTheClaimsClauseLeavesTheClaimUncitedAndItIsStillJudged() {
		// The claim's marker run is the one ActiveOrderCitationFidelityCheck reads, and a marker past
		// the clause break is not in it. The finding past it is taken for the claim only where it names
		// the claim's PARTNER (the next case); the Simvastatin finding names no Heparin, so this claim is
		// judged as citing nothing, and a pair no finding relates is unfounded.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String unfounded = "Clarithromycin interacts with active order Heparin, which is a caution to "
				+ "note [" + arrangement.finding("Simvastatin", "Amiodarone") + "].";
		String faithful = "Clarithromycin interacts with active order Amiodarone, which is a reason to "
				+ "withhold it [" + arrangement.finding("Clarithromycin", "Amiodarone") + "].";

		InteractionClaimPairs reported = arrangement.service(unfounded).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();
		InteractionClaimPairs clean = arrangement.service(faithful).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(1, reported.getUnfounded(), "was: " + reported);
		assertEquals(Collections.<Integer> emptyList(), reported.getMisattributedCitations(),
				"the marker is past the claim's run, so it is not accused, was: " + reported);
		assertEquals(0, clean.getUnfounded(), "a pair a finding relates is not unfounded, was: " + clean);
		assertEquals(1, clean.getJudged(), "was: " + clean);
	}

	@Test
	public void aFindingMarkerPastTheClaimsClauseIsTheClaimsWhereTheFindingNamesItsPartner() {
		// The ticket's cases 2 and 4 put the marker after a comma — "…active order Lamivudine /
		// zidovudine, and this is a caution to note, not a reason to withhold it [353]", [353] being
		// Stavudine's finding against that order. Nothing between the clause break and the marker names
		// a drug, and the finding cited names the claim's partner: that is the evidence the marker is
		// the claim's, so the finding about another drug is reported rather than read as no citation.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		for (String tail : Arrays.asList(", and this is a caution to note, not a reason to withhold it [",
				", which is a reason to withhold it [")) {
			String answer = "Clarithromycin interacts with active order Amiodarone" + tail
					+ simvastatinsFinding + "].";

			InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
					.getInteractionClaimPairs();

			assertEquals(Collections.singletonList(Integer.valueOf(simvastatinsFinding)),
					pairs.getMisattributedCitations(), "was: " + pairs + " for: " + answer);
			assertEquals(0, pairs.getUnfounded(), "it cited a finding, was: " + pairs + " for: " + answer);
			assertEquals(1, pairs.getJudged(), "was: " + pairs + " for: " + answer);
		}
	}

	@Test
	public void aFindingMarkerPastTheClaimsClauseIsNotTheClaimsWhereTheWordsBeforeItNameAnotherDrug() {
		// A later clause about another drug, carrying its own correct citation, is not the claim's —
		// Decision 76's cry-wolf shape. The Simvastatin finding names the claim's partner Amiodarone, so
		// the partner gate alone would take it; the words before it name Simvastatin, so it is not taken.
		// The second answer is the plan's refutation example, a finding about the claim's own drug and a
		// different order. Both claims are then uncited, and a finding relates Clarithromycin × Amiodarone.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		for (String answer : Arrays.asList(
				"Clarithromycin interacts with active order Amiodarone, and Simvastatin's interaction with it "
						+ "is a caution [" + arrangement.finding("Simvastatin", "Amiodarone") + "].",
				"Clarithromycin interacts with active order Amiodarone, and its interaction with Digoxin is "
						+ "also a caution [" + arrangement.finding("Clarithromycin", "Digoxin") + "].")) {

			InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
					.getInteractionClaimPairs();

			assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
					"was: " + pairs + " for: " + answer);
			assertEquals(0, pairs.getUnfounded(), "was: " + pairs + " for: " + answer);
			assertEquals(1, pairs.getJudged(), "was: " + pairs + " for: " + answer);
		}
	}

	@Test
	public void aFindingMarkerPastTheClaimsClauseIsNotTheClaimsWhereTheFindingRelatesNoDrugToAnOrder() {
		// Only a finding relating a drug to an order is taken past the clause. A contraindication about
		// Clarithromycin names the claim's partner, but it is not about which of her orders Amiodarone
		// interacts with, so the claim stays uncited and is judged — Clarithromycin × Amiodarone being a
		// pair a finding relates — rather than silenced by what it cannot compare.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(baseChart(),
				LISTING_QUESTION, ORDERS, ORDER_ATC, setOf("Clarithromycin"));
		RecordMapping contraindication = null;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(ChartSearchAiUtils.findingType(finding))) {
				contraindication = finding;
			}
		}
		assertNotNull(contraindication, "the premise: a contraindication finding, chart was: " + chart.getText());
		String answer = "Amiodarone interacts with active order Clarithromycin, which is a caution ["
				+ contraindication.getIndex() + "].";

		InteractionClaimPairs pairs = service(chart, unused -> Collections.<SafetyWarning> emptyList(), answer)
				.search(patient(), LISTING_QUESTION).getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void anUncitedClaimAboutADrugAClassOnlyFindingIsAboutIsNotJudged() {
		// A class-only finding about ciprofloxacin names a class and not an order, so whether it meant
		// the claim's partner cannot be read — and an uncited claim about ciprofloxacin is judged against
		// every finding, that one included. It is left unjudged rather than called unfounded.
		String question = "is it safe to give ciprofloxacin?";
		Arrangement arrangement = new Arrangement(question, setOf("levofloxacin"), setOf("J01MA12"), null);
		assertTrue(DrugReferenceTestSupport.injectedFindings(arrangement.chart).stream()
				.anyMatch(finding -> finding.getFindingPartners().isEmpty()
						&& ChartSearchAiUtils.findingSubject(finding).equalsIgnoreCase("ciprofloxacin")),
				"the premise: a class-only finding about ciprofloxacin, chart was: " + arrangement.chart.getText());
		String answer = "Ciprofloxacin interacts with active order Heparin.";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), question)
				.getInteractionClaimPairs();

		assertEquals(0, pairs.getJudged(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
	}

	@Test
	public void aClaimWhoseSubjectNamesNoDrugAFindingNamesIsNotJudged() {
		// "It" names no drug, so the check cannot tell whose claim this is and says nothing.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "It interacts with active order Amiodarone ["
				+ arrangement.finding("Simvastatin", "Amiodarone") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(0, pairs.getJudged(), "was: " + pairs);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
	}

	@Test
	public void aClaimCitingAReferenceRecordIsNotCalledUnfounded() {
		// A drug_reference record states interactions no finding raises (#357 renders a sub-floor rule
		// naming her order in its tail), and this check reads no reference text, so a claim citing one
		// is one it cannot judge.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin interacts with active order Heparin ["
				+ DrugReferenceTestSupport.injectedReference(arrangement.chart).getIndex() + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(0, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aPairOnlyTheChipsRaisedIsNotUnfounded() {
		// The prompt carried Clarithromycin's findings alone; the answer names Amiodarone, one of her
		// orders, and the post-answer pass raises the Amiodarone × Warfarin chip no carried finding has.
		// The module DID raise that pair, so the claim is not one "no finding raised".
		Arrangement arrangement = new Arrangement(SINGLE_DRUG_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin interacts with active order Warfarin ["
				+ arrangement.finding("Clarithromycin", "Warfarin") + "]. Amiodarone interacts with active "
				+ "order Warfarin.";
		assertFalse(arrangement.hasFinding("Amiodarone", "Warfarin")
				|| arrangement.hasFinding("Warfarin", "Amiodarone"),
				"the premise: no carried finding relates the second pair, chart was: "
						+ arrangement.chart.getText());
		assertTrue(arrangement.chipsOver(answer).stream().anyMatch(chip -> relates(chip, "Amiodarone",
				"Warfarin")), "and the post-answer chips do, were: " + arrangement.chipsOver(answer));

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), SINGLE_DRUG_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(2, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aClaimNamingThePrescriptionTheFindingResolvedItsPartnerFromIsNotReported() {
		// A brand-named order: the finding names its partner Warfarin and states, in its chart-order
		// clause, that Warfarin was resolved from her order Coumadin 5mg (#349). A claim naming the
		// prescription is about the pair that finding relates.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-coumadin", "Coumadin 5mg",
						setOf("Coumadin 5mg"), setOf("B01AA03")),
				new PatientClinicalContext.ActiveDrugOrder("order-simvastatin", "Simvastatin 20mg",
						setOf("Simvastatin 20mg"), setOf("C10AA01")));
		Arrangement arrangement = new Arrangement(SINGLE_DRUG_QUESTION,
				setOf("Coumadin 5mg", "Simvastatin 20mg"), setOf("B01AA03", "C10AA01"), orders);
		int finding = arrangement.finding("Clarithromycin", "Warfarin");
		assertTrue(DrugReferenceTestSupport.findingAt(arrangement.chart, finding).getText().contains("Coumadin 5mg"),
				"the premise: the finding's record names the prescription, was: "
						+ DrugReferenceTestSupport.findingAt(arrangement.chart, finding).getText());
		String answer = "Clarithromycin interacts with active order Coumadin 5mg [" + finding + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), SINGLE_DRUG_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(1, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aClaimCitingAClassOnlyFindingWhosePartnerIsAClassIsNotJudged() {
		// A class-only finding names no active order structurally — its partner is a class — so the
		// check cannot say whether the claim's partner is the one the finding meant.
		Arrangement arrangement = new Arrangement("is it safe to give ciprofloxacin?",
				setOf("levofloxacin"), setOf("J01MA12"), null);
		RecordMapping classOnly = null;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(arrangement.chart)) {
			if (finding.getFindingPartners().isEmpty()) {
				classOnly = finding;
			}
		}
		assertNotNull(classOnly, "the premise: a finding naming no order, chart was: "
				+ arrangement.chart.getText());
		String answer = "Ciprofloxacin is in the same ATC class as active order levofloxacin ["
				+ classOnly.getIndex() + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(),
				"is it safe to give ciprofloxacin?").getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(0, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aClaimNamingNoPartnerIsNotJudged() {
		// Nothing after the noun but the marker: there is no partner to compare, so nothing is said.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin interacts with active order ["
				+ arrangement.finding("Simvastatin", "Amiodarone") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(0, pairs.getJudged(), "was: " + pairs);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void aVerbatimCopyOfTheAlreadyInSeveralOrdersFindingIsNotReported() throws Exception {
		// Issue #477's finding reads "Rifampicin is already in active orders A and B" — the noun in its
		// plural, and two of her orders after it. A model copying it verbatim states that finding's pair.
		SeveralOrders arrangement = new SeveralOrders();
		List<SafetyWarning> chips = arrangement.chips;
		PatientChart chart = arrangement.chart;
		RecordMapping alreadyIn = arrangement.alreadyIn;
		String question = SeveralOrders.QUESTION;
		String copied = null;
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().contains("is already in active orders")) {
				int end = chip.getDetail().indexOf(". ");
				copied = end < 0 ? chip.getDetail() : chip.getDetail().substring(0, end);
			}
		}
		assertNotNull(copied, "and its chip, were: " + chips);
		String answer = "No. " + copied + " [" + alreadyIn.getIndex() + "].";

		InteractionClaimPairs pairs = service(chart, unused -> chips, answer).search(patient(), question)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
				"the claim is the cited finding's own, was: " + pairs + " for: " + answer);
		assertEquals(1, pairs.getJudged(), "and it was judged, was: " + pairs + " for: " + answer);
	}

	@Test
	public void aClaimCitingAFindingThatRelatesNoDrugToAnOrderIsNotJudged() {
		// A contraindication finding relates the drug to her allergy, not to an order, so a claim citing
		// one offered something this check cannot compare — and it must not then be judged against the
		// other findings and called unfounded. The claim names neither side of the contraindication, so
		// no other silence can be what keeps it quiet.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(baseChart(),
				LISTING_QUESTION, ORDERS, ORDER_ATC, setOf("Clarithromycin"));
		RecordMapping contraindication = null;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(ChartSearchAiUtils.findingType(finding))) {
				contraindication = finding;
			}
		}
		assertNotNull(contraindication, "the premise: a contraindication finding, chart was: " + chart.getText());
		String answer = "Simvastatin interacts with active order Heparin [" + contraindication.getIndex()
				+ "].";

		InteractionClaimPairs pairs = service(chart, unused -> Collections.<SafetyWarning> emptyList(), answer)
				.search(patient(), LISTING_QUESTION).getInteractionClaimPairs();

		assertEquals(0, pairs.getJudged(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void anUncitedClaimIsNotSilencedByAContraindicationAboutItsDrug() {
		// Contraindication findings are not in the population an uncited claim is judged against: an
		// allergy to Clarithromycin says nothing about which of her orders it interacts with, so the
		// invented Heparin pair is still unfounded.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(baseChart(),
				LISTING_QUESTION, ORDERS, ORDER_ATC, setOf("Clarithromycin"));
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).stream().anyMatch(finding ->
				SafetyWarning.TYPE_CONTRAINDICATION.equals(ChartSearchAiUtils.findingType(finding))),
				"the premise: a contraindication finding about the claim's drug, chart was: " + chart.getText());
		String answer = "Clarithromycin interacts with active order Heparin.";

		InteractionClaimPairs pairs = service(chart, unused -> Collections.<SafetyWarning> emptyList(), answer)
				.search(patient(), LISTING_QUESTION).getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs);
		assertEquals(1, pairs.getUnfounded(), "was: " + pairs);
	}

	@Test
	public void aPrescriptionNamedByTheFrontOfItsDisplayIsNotReported() {
		// "Coumadin" for the order "Coumadin 5mg": the name a finding carries CONTAINS the claim's
		// partner, the other direction from a partner span running on past a name.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-coumadin", "Coumadin 5mg",
						setOf("Coumadin 5mg"), setOf("B01AA03")),
				new PatientClinicalContext.ActiveDrugOrder("order-simvastatin", "Simvastatin 20mg",
						setOf("Simvastatin 20mg"), setOf("C10AA01")));
		Arrangement arrangement = new Arrangement(SINGLE_DRUG_QUESTION,
				setOf("Coumadin 5mg", "Simvastatin 20mg"), setOf("B01AA03", "C10AA01"), orders);
		String answer = "Clarithromycin interacts with active order Coumadin ["
				+ arrangement.finding("Clarithromycin", "Warfarin") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), SINGLE_DRUG_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(1, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void theSubjectIsTheClaimsOwnClauseAndNotTheClauseBeforeIt() {
		// "Simvastatin can be given, but Clarithromycin interacts with …": the subject is the drug of the
		// claim's own clause. Read from the sentence start it would name Simvastatin too, the cited
		// Simvastatin finding would then relate the pair, and the swap would go unreported.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		String answer = "Simvastatin can be given, but Clarithromycin interacts with active order Amiodarone ["
				+ simvastatinsFinding + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.singletonList(Integer.valueOf(simvastatinsFinding)),
				pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void theSecondClaimOfASentenceDoesNotTakeTheFirstClaimsSubject() {
		// Two claims in one comma-free sentence: the second claim's subject starts where the first
		// claim ended, so it names Clarithromycin alone and the Simvastatin finding cited for it is
		// reported, while the first claim — the finding's own pair — is not.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		String answer = "Simvastatin interacts with active order Amiodarone [" + simvastatinsFinding
				+ "] and Clarithromycin interacts with active order Amiodarone [" + simvastatinsFinding + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(2, pairs.getJudged(), "was: " + pairs);
		assertEquals(Collections.singletonList(Integer.valueOf(simvastatinsFinding)),
				pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void anUncitedClaimNamingAPrescriptionAChipResolvedIsNotUnfounded() {
		// The answer names Amiodarone, not one of her orders here, so the post-answer pass raises its
		// chip against the brand-named order Coumadin 5mg, resolving Warfarin from it. No carried
		// finding relates the pair; the chip does, under the prescription's name.
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
				new PatientClinicalContext.ActiveDrugOrder("order-coumadin", "Coumadin 5mg",
						setOf("Coumadin 5mg"), setOf("B01AA03")),
				new PatientClinicalContext.ActiveDrugOrder("order-simvastatin", "Simvastatin 20mg",
						setOf("Simvastatin 20mg"), setOf("C10AA01")));
		Arrangement arrangement = new Arrangement(SINGLE_DRUG_QUESTION,
				setOf("Coumadin 5mg", "Simvastatin 20mg"), setOf("B01AA03", "C10AA01"), orders);
		String answer = "Amiodarone interacts with active order Coumadin 5mg.";
		assertFalse(arrangement.hasFinding("Amiodarone", "Warfarin"),
				"the premise: no carried finding relates the pair, chart was: " + arrangement.chart.getText());

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), SINGLE_DRUG_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs);
		assertEquals(0, pairs.getUnfounded(), "was: " + pairs);
	}

	@Test
	public void anOrderToOrderClaimCitingTheAlreadyInSeveralOrdersFindingIsNotReported() throws Exception {
		// Issue #477's finding relates two of her ORDERS to each other — both carry the drug — so a claim
		// pairing them states the finding's own relation, whichever order leads.
		SeveralOrders arrangement = new SeveralOrders();
		List<SafetyWarning> chips = arrangement.chips;
		PatientChart chart = arrangement.chart;
		RecordMapping alreadyIn = arrangement.alreadyIn;
		String question = SeveralOrders.QUESTION;
		String answer = SeveralOrders.RHZ + " interacts with active order " + SeveralOrders.RHZE + " ["
				+ alreadyIn.getIndex() + "].";

		InteractionClaimPairs pairs = service(chart, unused -> chips, answer).search(patient(), question)
				.getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs + " for: " + answer);
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
				"was: " + pairs + " for: " + answer);
	}

	@Test
	public void aSubjectOnlyAFindingsPartnerNamesIsStillJudged() {
		// Warfarin is one of her orders and a PARTNER of the carried findings, never their subject — the
		// screening shape, where both drugs are hers. A claim leading with it is judged: here it cites
		// Clarithromycin's Digoxin finding for a Warfarin × Digoxin pair, which that finding does not relate.
		Arrangement arrangement = new Arrangement(SINGLE_DRUG_QUESTION, ORDERS, ORDER_ATC, null);
		int finding = arrangement.finding("Clarithromycin", "Digoxin");
		for (RecordMapping carried : DrugReferenceTestSupport.injectedFindings(arrangement.chart)) {
			assertFalse(ChartSearchAiUtils.findingSubject(carried).equalsIgnoreCase("Warfarin"),
					"the premise: no carried finding is about Warfarin, chart was: " + arrangement.chart.getText());
		}
		List<SafetyWarning> questionChips = arrangement.chipsOver("");
		String answer = "Warfarin interacts with active order Digoxin [" + finding + "].";

		InteractionClaimPairs pairs = service(arrangement.chart, unused -> questionChips, answer)
				.search(patient(), SINGLE_DRUG_QUESTION).getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs);
		assertEquals(Collections.singletonList(Integer.valueOf(finding)), pairs.getMisattributedCitations(),
				"was: " + pairs);
	}

	@Test
	public void aSecondPartnerNoFindingRelatesToTheSubjectIsUnfoundedBesideTheOneItsCitationRelates() {
		// The ticket's case 1 — an invented partner — put in the active-order form: [6] is Simvastatin's
		// Amiodarone finding and is right for that half, and no finding or chip relates Simvastatin to
		// Digoxin, one of her orders. Containment of the one related name read the whole partner span as
		// related; every drug the partner span names must be one a finding relates to the subject.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		for (String answer : Arrays.asList(
				"Simvastatin interacts with active order Amiodarone and Digoxin [" + simvastatinsFinding + "].",
				"Simvastatin interacts with active order Digoxin and Amiodarone [" + simvastatinsFinding + "].",
				"Simvastatin interacts with active order Amiodarone and Digoxin.")) {
			assertFalse(arrangement.hasFinding("Simvastatin", "Digoxin") || arrangement.hasFinding("Digoxin",
					"Simvastatin"), "the premise: no finding relates the invented pair, chart was: "
							+ arrangement.chart.getText());
			assertFalse(arrangement.chipsOver(answer).stream().anyMatch(chip -> relates(chip, "Simvastatin",
					"Digoxin")), "nor does a chip, were: " + arrangement.chipsOver(answer));

			InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
					.getInteractionClaimPairs();

			assertEquals(1, pairs.getJudged(), "was: " + pairs + " for: " + answer);
			assertEquals(1, pairs.getUnfounded(), "no finding relates the second partner, was: " + pairs
					+ " for: " + answer);
			assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
					"the citation relates the partner it was cited beside, so it is not accused, was: " + pairs
							+ " for: " + answer);
		}
	}

	@Test
	public void aClaimNamingTwoPartnersFindingsRelateIsNotReportedWhicheverOfThemItCites() {
		// Both of Clarithromycin's pairs are findings; citing one of them leaves the other uncited, which
		// is not a pair "no finding raised".
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int amiodarone = arrangement.finding("Clarithromycin", "Amiodarone");
		int digoxin = arrangement.finding("Clarithromycin", "Digoxin");
		for (String answer : Arrays.asList(
				"Clarithromycin interacts with active order Amiodarone and Digoxin [" + amiodarone + "], ["
						+ digoxin + "].",
				"Clarithromycin interacts with active order Amiodarone and Digoxin [" + amiodarone + "].")) {

			InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
					.getInteractionClaimPairs();

			assertEquals(1, pairs.getJudged(), "was: " + pairs + " for: " + answer);
			assertEquals(0, pairs.getUnfounded(), "was: " + pairs + " for: " + answer);
			assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
					"was: " + pairs + " for: " + answer);
		}
	}

	@Test
	public void aSubjectClauseNamingSeveralDrugsIsNotJudgedWhereItsReadingsDisagree() {
		// No comma between the drug of a lead clause and the claim's own, so the subject span names both.
		// [6] is Simvastatin's Amiodarone finding: read as Clarithromycin each claim is a swap, read as
		// Simvastatin it is right. Taking any reading that relates published judged=1 over the swap
		// (round 2 of #514's review); the next case is why the reading NEAREST the noun is not taken.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		for (String answer : Arrays.asList(
				"Clarithromycin can be given alongside Simvastatin but Clarithromycin interacts with active "
						+ "order Amiodarone [" + simvastatinsFinding + "].",
				"Clarithromycin (like Simvastatin) interacts with active order Amiodarone ["
						+ simvastatinsFinding + "].")) {

			InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
					.getInteractionClaimPairs();

			assertEquals(0, pairs.getJudged(), "was: " + pairs + " for: " + answer);
			assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(),
					"was: " + pairs + " for: " + answer);
			assertEquals(0, pairs.getUnfounded(), "was: " + pairs + " for: " + answer);
		}
	}

	@Test
	public void aCorrectCitationBehindAPronounIsNotAccusedOnTheDrugNearestTheNoun() {
		// "it" is Clarithromycin and [13] is Clarithromycin's own Amiodarone finding, so the claim is right.
		// Its readings are the previous case's in the same positions with the verdicts the other way round:
		// the drug nearest the noun, Simvastatin, is the wrong one. So neither reading is taken over the
		// other, and the claim is left unjudged rather than [13] accused.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		String answer = "Clarithromycin can be given alongside Simvastatin but it interacts with active order "
				+ "Amiodarone [" + arrangement.finding("Clarithromycin", "Amiodarone") + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations(), "was: " + pairs);
		assertEquals(0, pairs.getJudged(), "was: " + pairs);
	}

	@Test
	public void aSubjectClauseNamingSeveralDrugsIsReportedWhereNoReadingRelatesItsCitation() {
		// Every drug the subject span names is read, and here none of them is related to Amiodarone by the
		// finding cited — Amiodarone's own Digoxin finding — so the readings agree and it is reported.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int amiodaronesFinding = arrangement.finding("Amiodarone", "Digoxin");
		String answer = "Warfarin can be given alongside Simvastatin but Clarithromycin interacts with active "
				+ "order Amiodarone [" + amiodaronesFinding + "].";

		InteractionClaimPairs pairs = arrangement.service(answer).search(patient(), LISTING_QUESTION)
				.getInteractionClaimPairs();

		assertEquals(1, pairs.getJudged(), "was: " + pairs);
		assertEquals(Collections.singletonList(Integer.valueOf(amiodaronesFinding)),
				pairs.getMisattributedCitations(), "was: " + pairs);
	}

	@Test
	public void anAnswerStatingNoClaimIsAMeasurementOfNone() {
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);

		InteractionClaimPairs pairs = arrangement.service("Clarithromycin should be avoided.")
				.search(patient(), LISTING_QUESTION).getInteractionClaimPairs();

		assertNotNull(pairs, "zero claims is a measurement, not an absence of one");
		assertEquals(0, pairs.getJudged());
		assertEquals(Collections.<Integer> emptyList(), pairs.getMisattributedCitations());
		assertEquals(0, pairs.getUnfounded());
	}

	@Test
	public void theWarnNamesTheCitationAndNoDrug() {
		// The names a claim carries are this patient's medications, and core ships org.openmrs at WARN
		// (ADR Decision 102, issue #439): the line carries the citation and the counts only.
		Arrangement arrangement = new Arrangement(LISTING_QUESTION, ORDERS, ORDER_ATC, null);
		int simvastatinsFinding = arrangement.finding("Simvastatin", "Amiodarone");
		String answer = "Clarithromycin interacts with active order Amiodarone [" + simvastatinsFinding
				+ "]. Clarithromycin interacts with active order Heparin.";

		try (LogCapture capture = LogCapture.on(CHECK)) {
			arrangement.service(answer).search(patient(), LISTING_QUESTION);

			assertTrue(capture.hasMessageAt(Level.WARN, "[" + simvastatinsFinding + "]"),
					"the WARN names the misattributed citation, captured: " + capture.describeAll());
			String logged = capture.messagesAt(Level.WARN).toString().toLowerCase();
			for (String drug : Arrays.asList("clarithromycin", "amiodarone", "heparin", "simvastatin")) {
				assertFalse(logged.contains(drug), "and names no drug (" + drug + "), was: " + logged);
			}
		}
	}

	/** Issue #477's arrangement: two of her orders carrying rifampicin, the finding that the drug is
	 *  already in both, and the chips the real validator raises over them. */
	private static final class SeveralOrders {

		private static final String FIXTURE = "chartsearchai-test/ddi-substance-in-several-orders.json";

		private static final String QUESTION = "Is it safe to give rifampicin?";

		private static final String RHZ = "Isoniazid / pyrazinamide / rifampin";

		private static final String RHZE = "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg";

		private final List<SafetyWarning> chips;

		private final PatientChart chart;

		/** The record of the finding that rifampicin is already in both orders. */
		private final RecordMapping alreadyIn;

		private SeveralOrders() throws Exception {
			chips = DrugReferenceTestSupport.chipsOverOrders(FIXTURE, QUESTION, RHZ, RHZE);
			chart = DrugReferenceTestSupport.findingsOverOrders(baseChart(), FIXTURE, QUESTION, RHZ, RHZE);
			RecordMapping found = null;
			for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
				if (finding.getText().contains("is already in active orders")) {
					found = finding;
				}
			}
			assertNotNull(found, "the premise: the injector wrote that finding, was: " + chart.getText());
			alreadyIn = found;
		}
	}

	/** Whether {@code chip} relates the two drugs, in either order. */
	private static boolean relates(SafetyWarning chip, String one, String other) {
		return (chip.getDrug().equalsIgnoreCase(one) && chip.namedPartners().contains(other))
				|| (chip.getDrug().equalsIgnoreCase(other) && chip.namedPartners().contains(one));
	}

	/** One patient: the chart the real injector wrote for {@code question} over her orders, and the
	 *  chips the real validator raises over whatever answer the model gives. */
	private static final class Arrangement {

		private final String question;

		private final Set<String> drugs;

		private final Set<String> atc;

		private final List<PatientClinicalContext.ActiveDrugOrder> orders;

		private final PatientChart chart;

		private Arrangement(String question, Set<String> drugs, Set<String> atc,
				List<PatientClinicalContext.ActiveDrugOrder> orders) {
			this.question = question;
			this.drugs = drugs;
			this.atc = atc;
			this.orders = orders;
			this.chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), question, drugs, atc,
					orders);
		}

		private List<SafetyWarning> chipsOver(String answer) {
			return DrugReferenceTestSupport.chipsOverAnswer(answer, question, drugs, atc, orders);
		}

		private boolean hasFinding(String subject, String partner) {
			return findingOrNull(subject, partner) != null;
		}

		/** The citation number of the injected finding about {@code subject} naming {@code partner}. */
		private int finding(String subject, String partner) {
			RecordMapping found = findingOrNull(subject, partner);
			if (found == null) {
				throw new IllegalStateException("no finding about " + subject + " naming " + partner
						+ " in: " + chart.getText());
			}
			return found.getIndex();
		}

		private RecordMapping findingOrNull(String subject, String partner) {
			for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
				if (ChartSearchAiUtils.findingSubject(finding).equalsIgnoreCase(subject)
						&& finding.getFindingPartners().contains(partner)) {
					return finding;
				}
			}
			return null;
		}

		/** The real inference service over this chart, with {@code modelAnswer} as the model's prose. */
		private LlmInferenceService service(String modelAnswer) {
			return InteractionClaimPairFidelityTest.service(chart, this::chipsOver, modelAnswer);
		}
	}

	/** The real inference service over {@code chart}, with {@code modelAnswer} as the model's prose and
	 *  {@code chips} deciding the post-answer chips from the answer the validator is handed. */
	private static LlmInferenceService service(PatientChart chart,
			java.util.function.Function<String, List<SafetyWarning>> chips, String modelAnswer) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(chart));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart built, Patient patient, String q,
					ChartReadStatus readStatus) {
				return built;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production calls: mappings-carrying (issue #105) and sink-carrying (issue
			// #336). The chips are the real validator's over the ANSWER it is handed.
			@Override
			public List<SafetyWarning> validate(String answer, String q, Patient patient,
					List<PatientChartSerializer.RecordMapping> mappings,
					PairChipExtent.Sink pairExtentSink) {
				return chips.apply(answer);
			}
		});
		created.setLlmProvider(new StubProvider(modelAnswer));
		return created;
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** Her own drug order, rendered by the REAL serializer — the chart the injector appends to. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Warfarin 5mg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
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

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}
	}
}
