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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * One mechanism, several partners: the finding is stated ONCE and names every active order it covers.
 *
 * <p><b>The measurement.</b> On the 3.7.1 standalone (patient Sarah Taylor, 2026-09-14) "Is aspirin
 * safe for her?" returned nine interaction chips carrying 5,717 characters of detail, of which 3,324
 * — 58% — were re-sent copies: four distinct mechanism texts, the corticosteroid one repeated five
 * times byte for byte and the NSAID one twice. The only thing that varied across those five was the
 * partner's name.
 *
 * <p><b>Not {@code StatedInteractionChips}, which answers a different question.</b> That collapse is
 * for a chip whose whole SENTENCE another chip already said — two rules about one prescription. Here
 * the sentences differ, because the partners differ; what repeats is the mechanism they share.
 *
 * <p><b>The pair count does not follow the chip count.</b> {@code PairChipExtent} counts above-floor
 * rule PAIRS, and the arm's return value feeds it, so collapsing chips must leave it untouched —
 * stated as its own case below because it is the one thing here that fails silently.
 */
public class SharedMechanismChipCollapseTest {

	/** What the excerpt's aspirin ROW is called — {@code DrugReference.displayLabel()}. */
	private static final String ASPIRIN = "Acetylsalicylic acid (aspirin)";

	/** The sentence DDInter files under mechanism group 2346, which both corticosteroid rows carry. */
	private static final String CORTICOSTEROID_MECHANISM = DrugReferenceTestSupport.SHARED_MECHANISM_TEXT;

	/** Through the shared arrangement, which is where it lives since issue #439 gave it a second
	 *  reader — {@code FindingPartnerLogDisclosureTest} drives these same chips through the real
	 *  inference path, and a copy of the fixture wiring here would let the two describe different
	 *  chips while both stayed green. */
	private static List<SafetyWarning> interactionsFor(PairChipExtent.Sink sink) throws IOException {
		return DrugReferenceTestSupport.sharedMechanismInteractionChips(sink);
	}

	@Test
	public void twoPartnersSharingAMechanismAreOneChipNamingBoth() throws IOException {
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());

		assertEquals(2, chips.size(),
				"one chip per MECHANISM and not per partner — the corticosteroid sentence does not vary"
						+ " between its two members, so a chip apiece re-sends it, was: "
						+ DrugReferenceTestSupport.details(chips));

		SafetyWarning shared = null;
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().contains(CORTICOSTEROID_MECHANISM)) {
				shared = chip;
			}
		}
		assertNotNull(shared, "the shared mechanism must still be stated, was: "
				+ DrugReferenceTestSupport.details(chips));
		// Lower-cased on both sides deliberately: what a chip CALLS a partner is issue #339's question
		// and has its own cases. This one is about the collapse, so it must not redden on the naming.
		String lower = shared.getDetail().toLowerCase(java.util.Locale.ROOT);
		assertTrue(lower.contains("prednisone") && lower.contains("methylprednisolone"),
				"and it must name EVERY active order it covers — collapsing may not drop a partner,"
						+ " was: " + shared.getDetail());
		assertEquals("Moderate", shared.getSeverity(),
				"the rating both rows carry survives the collapse, was: " + shared.getDetail());
	}

	@Test
	public void theSharedSentenceIsSentOnceAcrossTheWholeResponse() throws IOException {
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());

		int occurrences = 0;
		for (SafetyWarning chip : chips) {
			int from = chip.getDetail().indexOf(CORTICOSTEROID_MECHANISM);
			while (from >= 0) {
				occurrences++;
				from = chip.getDetail().indexOf(CORTICOSTEROID_MECHANISM, from + 1);
			}
		}
		assertEquals(1, occurrences,
				"the mechanism text is the payload, and a clinician reading it twice learns nothing the"
						+ " second time, was: " + DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aPartnerWithItsOwnMechanismKeepsItsOwnChip() throws IOException {
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());

		boolean heparin = false;
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().toLowerCase(java.util.Locale.ROOT).contains("heparin")) {
				heparin = !chip.getDetail().contains(CORTICOSTEROID_MECHANISM);
			}
		}
		assertTrue(heparin,
				"a partner the data files under a DIFFERENT mechanism states a different thing and must"
						+ " not be folded into the shared one, was: "
						+ DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aMergedChipCarriesTheOrdersItNamesStructurally() throws IOException {
		// The precondition FindingPartnerCoverageCheck rests on: it asks whether the ANSWER stated every
		// order a finding names, and it must read those names off the chip rather than recover them by
		// parsing the detail this module composed them into (the two-resolutions-that-agree shape issue
		// #151 forbids). EVERY interaction chip states them — one name for an ordinary chip, several
		// for a merged one — so no reader has to tell "carries no list" from "covers no order".
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());

		SafetyWarning merged = null;
		SafetyWarning single = null;
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().contains(CORTICOSTEROID_MECHANISM)) {
				merged = chip;
			}
			else {
				single = chip;
			}
		}
		assertNotNull(merged, "was: " + DrugReferenceTestSupport.details(chips));
		assertEquals(2, merged.namedPartners().size(),
				"the merged chip names both orders structurally, was: " + merged.namedPartners());
		String names = merged.namedPartners().toString().toLowerCase(java.util.Locale.ROOT);
		assertTrue(names.contains("prednisone") && names.contains("methylprednisolone"),
				"and they are the orders it covers, was: " + merged.namedPartners());
		assertNotNull(single, "was: " + DrugReferenceTestSupport.details(chips));
		assertEquals(1, single.namedPartners().size(),
				"an ordinary chip names its one order structurally too, was: " + single.namedPartners());
		assertTrue(single.namedPartners().get(0).toLowerCase(java.util.Locale.ROOT).contains("heparin"),
				"and it is the order that chip is about, was: " + single.namedPartners());
	}

	@Test
	public void eachFindingsRecordCarriesTheOrdersItsChipNames() throws IOException {
		// Issue #516: ADR Decision 100 names the orders of the findings an answer CITES, and a marker
		// reaches a record, never a chip — so each injected finding's record carries its own chip's
		// orders, written where the record is, and every other record carries none.
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());
		PatientChart chart = DrugReferenceTestSupport.sharedMechanismFindingsOver(
				DrugReferenceTestSupport.oneRecordChart());

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		assertEquals(chips.size(), findings.size(),
				"the premise: one record per chip, was: " + chart.getText());
		for (SafetyWarning chip : chips) {
			boolean merged = chip.getDetail().contains(CORTICOSTEROID_MECHANISM);
			RecordMapping record = null;
			for (RecordMapping finding : findings) {
				if (finding.getText().contains(CORTICOSTEROID_MECHANISM) == merged) {
					record = finding;
				}
			}
			assertNotNull(record, "was: " + chart.getText());
			assertEquals(chip.namedPartners(), record.getFindingPartners(),
					"the record carries its OWN finding's orders, every one of them, was: " + record.getText());
		}
		for (RecordMapping mapping : chart.getMappings()) {
			if (!findings.contains(mapping)) {
				assertTrue(mapping.getFindingPartners().isEmpty(),
						"a record that is not a finding names no order this way, was: "
								+ mapping.getResourceType() + " " + mapping.getFindingPartners());
			}
		}
	}

	@Test
	public void theCollapseDoesNotMoveThePairCount() throws IOException {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		interactionsFor(sink);

		PairChipExtent extent = sink.stated();
		assertNotNull(extent, "the drug-in-play arm screened this chart and states an extent");
		assertEquals(3, extent.getFound(),
				"three above-floor rule PAIRS were related, whatever number of chips state them —"
						+ " PairChipExtent counts pairs and is never the chip count");
		assertEquals(3, extent.getReported(),
				"and nothing cuts this arm, so reported equals found");
	}

	@Test
	public void everyChipStillNamesItsSubjectWithTheSharedPhrase() throws IOException {
		List<SafetyWarning> chips = interactionsFor(new PairChipExtent.Sink());

		for (SafetyWarning chip : chips) {
			assertTrue(chip.getDetail()
					.startsWith(ASPIRIN + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE),
					"a collapsed chip is still the same CLAIM, spelled with the one phrase the answer's"
							+ " own fidelity check recognises (issue #377), was: " + chip.getDetail());
		}
	}
}
