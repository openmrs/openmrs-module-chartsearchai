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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issue #357 — a partner the patient is ACTUALLY ON, whose row the severity floor filtered, was
 * ordered with the drugs she has never taken and so was rendered behind them, or not at all.
 *
 * <p><b>What was reproduced.</b> Live on the 3.7.1 standalone over the bundled knowledge base
 * (2026-09-01, {@code main} @ {@code 9aca5790}, run twice identical), a patient on Lamivudine,
 * Nevirapine and Stavudine asked what the drug reference says about Metformin interactions got a
 * record naming {@code ketotifen} and {@code labetalol} — {@code Unknown}-rated rows with no
 * mechanism text, about drugs she is not on — while Metformin's rows against her own three drugs,
 * which are the same {@code Unknown}/no-text shape, were not rendered at all. "The render stopped
 * two positions short of Lamivudine in the entry's own partner order."
 *
 * <p><b>What the module now does.</b> {@code orderedInteractionNotes} ranks on relevance and rating
 * as two questions rather than one: the promoted segment is unchanged (the chart names the partner
 * AND the floor admits the rule), then the rules the chart names that the floor filtered, then the
 * dataset tail. Nothing about promotion, the segment-1 budget override, or any chip moves — the
 * ticket's own control is that no {@code Unknown} row may become a chip with a rating the source
 * does not give it.
 *
 * <p>Every case runs the REAL production path: the pinned 16-drug DDInter excerpt (and, for the
 * ticket's own drugs, the SHIPPED knowledge base) parsed by the real {@link DdiDrugReferenceSource},
 * the real {@code injectRecords} and the real {@code validate}, GP reads on their no-context
 * defaults (severity floor {@code minor}).
 */
public class InjectedInteractionRelevanceOrderTest {

	private static final String METFORMIN_QUESTION = "is it safe to give metformin?";

	/**
	 * The excerpt's Metformin x Fluconazole row is {@code Unknown} with mechanism id {@code -1}, i.e.
	 * no mechanism text — the shape the ticket is about — and it sits at dataset position 13 of
	 * Metformin's fifteen partners, behind six Moderate rows carrying full mechanism paragraphs.
	 */
	private static final String SUB_FLOOR_PARTNER = "fluconazole";

	/** The excerpt rates Metformin x Warfarin {@code Moderate}, so it is promoted. */
	private static final String ABOVE_FLOOR_PARTNER = "warfarin";

	/** The lowercased {@code Interactions:} section of the record rendered for {@code drug} — selected
	 *  by name and never assumed, because a question about one drug can inject an order-driven record
	 *  for another. Both the selector and the extraction are {@link DrugReferenceTestSupport}'s, which
	 *  is where this project's instructions require a test helper of this kind to live. */
	private static String interactionsFor(String question, PatientClinicalContext context, String drug) {
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		return DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, drug));
	}

	private static int noteAt(String section, String partner) {
		return DrugReferenceTestSupport.noteAt(section, partner);
	}

	@Test
	public void aPartnerThePatientIsOnIsNamedEvenWhereTheFloorFilteredItsRule() {
		// The ticket's own shape on the pinned excerpt. Nothing is promoted — the only rule about a
		// drug this patient is on is Unknown, below the default minor floor — so before this fix the
		// record spent its whole budget walking the dataset from the head and stopped short of her.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Fluconazole"),
						null, null, null),
				"Metformin");

		assertTrue(noteAt(interactions, SUB_FLOOR_PARTNER) >= 0,
				"the partner this patient is actually on must be named, whatever the source rates it: "
						+ interactions);
	}

	@Test
	public void thePartnerThePatientIsOnLeadsTheStrangersRatherThanTrailingThem() {
		// Naming her partner somewhere is not the fix: the ticket's second criterion is that the
		// Unknown STRANGERS must not be named ahead of her own drugs. The budget is finite, so a
		// partner ordered behind fifteen strangers is a partner the cut removes again the moment the
		// dataset is the shipped 19 MB one rather than this excerpt.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Fluconazole"),
						null, null, null),
				"Metformin");

		assertEquals(0, noteAt(interactions, SUB_FLOOR_PARTNER) - "interactions: ".length(),
				"her own partner must lead the section, not trail the drugs she has never taken: "
						+ interactions);
	}

	@Test
	public void theFloorFilteredRuleStillRaisesNoChip() {
		// The control the ticket says must not be got wrong: "the module must NOT invent a rating or
		// a mechanism for a row the source rates and describes as nothing". Ordering the record is a
		// statement about which of the KB's own sentences reach the prompt; it is not a promotion,
		// and the chip path is untouched.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterService())
				.validate("Metformin may be given.", METFORMIN_QUESTION,
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Fluconazole"), null, null, null));

		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "metformin"),
				"a sub-floor rule must raise no chip, exactly as before: " + warnings);
	}

	@Test
	public void allThreeSegmentsRenderWhenThePatientHasAPartnerInEach() {
		// The whole shape in one record: the rated partner leads with the mechanism prose the budget
		// override exists for, the filtered partner follows with the source's own sentence about it,
		// and the dataset tail still gets its one compact representative — which is the breadth this
		// record owes the model about the drug in general, and which is lost the moment the middle
		// segment is allowed to spend the tail's slot.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Warfarin", "Fluconazole"), null, null, null),
				"Metformin");

		assertTrue(interactions.startsWith("interactions: warfarin (moderate. coadministration"),
				"the rated partner leads, in the promoted segment: " + interactions);
		assertTrue(interactions.contains("vitamin k antagonists"),
				"and keeps the mechanism prose the budget override exists for: " + interactions);
		assertTrue(interactions.contains("; " + SUB_FLOOR_PARTNER
				+ " (unknown severity interaction (ddinter 2.0; no mechanism description on file).); "),
				"the filtered partner follows it with what the source actually says about the pair — a "
						+ "rating and an admission that no mechanism is on file: " + interactions);
		assertTrue(interactions.endsWith("; lisinopril (moderate)."),
				"and the dataset tail keeps its own representative behind both: " + interactions);
	}

	@Test
	public void severalFilteredPartnersStateTheSharedSentenceOnceAndThenJustTheirNames() {
		// What promotion still buys, and the case that makes it observable. Segment 1 renders every
		// promoted note in full while the budget allows; this segment renders the FIRST in full and
		// the rest compact, because at the shipped floor every note in it is the same sentence and
		// repeating it is how a real polypharmacy record spends its whole budget saying one thing —
		// ADR Decision 65 carries the measurement and the arrangement it was taken on. Sertraline sits
		// ahead of Fluconazole in the entry's own partner order, so it is the one that carries the
		// sentence.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Warfarin", "Sertraline", "Fluconazole"), null, null,
						null),
				"Metformin");

		assertTrue(interactions.contains(
				"; sertraline (unknown severity interaction (ddinter 2.0; no mechanism description on "
						+ "file).); " + SUB_FLOOR_PARTNER + " (unknown); "),
				"the first filtered partner states the shared sentence and the next is named with its "
						+ "rating alone: " + interactions);
		assertEquals(1, interactions.split("no mechanism description on file", -1).length - 1,
				"and the sentence they share is stated once, not once per partner: " + interactions);
	}

	@Test
	public void aRecordAboutNoOneInParticularKeepsDatasetOrder() {
		// The control for everything this must not move. With no partner the chart names, all three
		// segments but the last are empty and the record is what it always was.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null), "Metformin");

		assertEquals(0, noteAt(interactions, "lisinopril") - "interactions: ".length(),
				"an entry with nothing patient-specific to say still renders its own dataset order: "
						+ interactions);
	}

	/**
	 * A curated entry filing ONE partner as two rows carrying the same token and DIFFERENT ATC codes,
	 * BOTH rated {@code Unknown} — the sub-floor counterpart of the fixture
	 * {@code InjectedInteractionNoteCollapseTest} poses the promotable version of. See its own
	 * description for why only a curated dataset can pose it.
	 */
	private static final String SUB_FLOOR_ATC_SPLIT_FIXTURE =
			"chartsearchai-test/drug-reference-partner-atc-split-subfloor-rows.json";

	@Test
	public void withNothingPromotedTheWholeSectionIsHerPartnersAndOneRepresentative() {
		// The crux of this issue, asserted as the WHOLE section rather than as a position. When the
		// floor admits none of her rules the record still owes two different things — which of her own
		// drugs this entry is filed against, and enough breadth that it does not read as if her
		// overlap were the drug's only interaction — and they come from two different segments. Keying
		// the tail on the promoted count instead of on "anything patient-specific was shown" sends
		// this arrangement back through the full-note budget loop, which renders her two partners a
		// second time and then walks the dataset; every position-shaped assertion in this class stays
		// green through that, because her partner is still at the front.
		String interactions = interactionsFor(METFORMIN_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Sertraline", "Fluconazole"), null, null, null),
				"Metformin");

		assertEquals("interactions: sertraline (unknown severity interaction (ddinter 2.0; no mechanism "
				+ "description on file).); " + SUB_FLOOR_PARTNER + " (unknown); lisinopril (moderate).",
				interactions,
				"her two partners once each, the source's sentence once, and one representative of "
						+ "everything else: " + interactions);
	}

	@Test
	public void theCollapseKeepsTheRowTheChartNamesEvenWhereTheFloorFilteredBoth() throws IOException {
		// One partner, two rows, one collapse — and the collapse runs BEFORE the ordering, so the row
		// it elects is the row the ordering is then asked about. Where two rows of one partner carry
		// different ATC codes those rows answer hasActiveDrug differently, and with both of them
		// sub-floor the survivor rule falls through to note length, which here elects the row this
		// patient is NOT on. The partner then looks like a stranger, lands in the dataset tail behind
		// two filler notes that exceed the budget between them, and leaves the record altogether —
		// this issue's own defect surviving inside the ordering added to close it.
		//
		// The same invariant the floor half of this collapse already has: it must not discard the very
		// row that decides where the partner sits.
		List<DrugReference> entries =
				DrugReferenceTestSupport.fixtureEntries(SUB_FLOOR_ATC_SPLIT_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null,
				DrugReferenceTestSupport.set("B01AA07"), null, null);

		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(entries))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
						"is it safe to give voriconazole?");
		String interactions = DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, "Voriconazole"));

		assertEquals(0, noteAt(interactions, "warfarin") - "interactions: ".length(),
				"the partner must be led by the row whose drug the chart records, not dropped because "
						+ "a sibling row the chart does not record won the collapse: " + interactions);
		assertTrue(interactions.contains("the patient is on this row's drug"),
				"and the note rendered must be that row's own: " + interactions);
	}

	@Test
	public void theTicketsOwnRegimenLeadsItsRecordOnTheShippedKnowledgeBase() {
		// The excerpt poses the shape; only the shipped knowledge base answers whether the ticket's
		// own case moves, and its head is a different shape (the live record led with ketoconazole,
		// ketoprofen and ketorolac, ~700-char Moderate paragraphs, against this excerpt's 215-char
		// lisinopril). Stated as the ORDERING property rather than as record text, so a knowledge-base
		// refresh that re-rates one of these pairs cannot make it assert something else: every note
		// naming a drug she is on precedes every note naming one she is not.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(38, null,
				DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null, null, null);
		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.shippedEntries()))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, METFORMIN_QUESTION);
		String interactions = DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, "Metformin"));

		int lastHers = -1;
		for (String hers : new String[] { "lamivudine", "nevirapine", "stavudine" }) {
			lastHers = Math.max(lastHers, noteAt(interactions, hers));
		}
		assertTrue(lastHers >= 0,
				"the ticket's first criterion, on the data the module ships: the record must name at "
						+ "least one of the three drugs she is actually on: " + interactions);

		int firstStranger = Integer.MAX_VALUE;
		for (String stranger : new String[] { "ketoconazole", "ketoprofen", "ketorolac", "ketotifen",
				"labetalol" }) {
			int at = noteAt(interactions, stranger);
			if (at >= 0) {
				firstStranger = Math.min(firstStranger, at);
			}
		}
		assertTrue(lastHers < firstStranger,
				"every partner this patient is on must be named ahead of every partner she is not: "
						+ interactions);
	}
}
