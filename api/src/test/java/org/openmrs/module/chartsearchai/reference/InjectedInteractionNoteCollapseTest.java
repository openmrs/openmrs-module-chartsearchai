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

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #174 site 2 — {@code DrugReferenceInjector.orderedInteractionNotes} emitted one note per
 * partner ROW, so one injected record named the same partner several times.
 *
 * <p><b>The measurement.</b> Over the shipped 19 MB KB (2283 entries, 590,312 expanded interaction
 * rows after the issue #152 self-pair guard drops 28): 1876 entries carry at least one repeated
 * partner and 19,316 rows are surplus, {@code Ozanimod} carrying the largest single surplus at 49
 * (measured 2026-08-07 against the standalone's {@code ddi_knowledge_base.json}; re-measure before
 * relying on the figures). It is invisible from the REST response, which returns only CITED
 * references — the same reason issue #163's equivalent had to be measured off the injected slice
 * rather than off the wire.
 *
 * <p><b>Why it is not merely untidy.</b> Segment 1 of {@code render} deliberately overrides the
 * character budget so a partner the patient is actually on is never invisible, so a partner filed
 * under three rows spends three notes of budget that the budget cannot claw back — near-duplicate
 * text crowding out the chart records the answer needs (issues #95, #99), and several
 * differently-worded copies of one fact handed to a model that miscopies them (#142). It also put a
 * severity in the prompt that the chip deliberately discarded: {@code DrugSafetyValidator}'s
 * {@code bestRulePerPartner} has collapsed several rules naming one partner into a single
 * most-severe chip since issue #115, while this path still listed all of them.
 *
 * <p>Every scenario runs the REAL production path: verbatim DDInter KB slices parsed by the real
 * {@link DdiDrugReferenceSource}, the real {@code injectRecords} entry point, GP reads on their
 * no-context defaults (severity floor {@code minor}).
 */
public class InjectedInteractionNoteCollapseTest {

	/**
	 * The verbatim KB slice whose Voxelotor entry carries SEVEN interaction rows naming FOUR
	 * partners: three rows against the dexamethasone family (Major, Moderate, Moderate — the
	 * measured case {@code orderedInteractionNotes} names in its own javadoc), two against the
	 * sirolimus family (Major, Moderate), one lapatinib and one phenytoin. Both multi-row families
	 * publish one {@code rxnorm_name} across their rows, which is the match token a rule carries and
	 * the label a note prints, so the rows are indistinguishable in the rendered record.
	 */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_ROUTE_VARIANTS;

	private static DrugReferenceInjector injector() throws Exception {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}

	/** The record the real injector emits for the drug the question names. */
	private static RecordMapping injectedRecord(String question, PatientClinicalContext context)
			throws Exception {
		PatientChart chart = injector().injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				context, question);
		List<RecordMapping> references = DrugReferenceTestSupport.injectedReferences(chart);
		assertEquals(1, references.size(),
				"precondition: exactly one reference record must be injected, was: " + chart.getText());
		return references.get(0);
	}

	/** The lowercased {@code Interactions:} section of a rendered record. */
	private static String interactionsOf(RecordMapping record) {
		String text = record.getText();
		int start = text.indexOf("Interactions:");
		assertTrue(start >= 0, "precondition: the record must render an Interactions section: " + text);
		return text.substring(start).toLowerCase(Locale.ROOT);
	}

	/**
	 * How many NOTES in {@code section} are headed by {@code partner} — occurrences of the label
	 * followed by the rendering's own {@code " ("}, not of the bare name, because a mechanism
	 * paragraph legitimately mentions the drugs it is about ("…exposure to sirolimus, which is
	 * primarily metabolized…") and counting those would make this assert something else.
	 */
	private static int notesHeadedBy(String section, String partner) {
		String needle = partner + " (";
		int count = 0;
		for (int at = section.indexOf(needle); at >= 0; at = section.indexOf(needle, at + 1)) {
			count++;
		}
		return count;
	}

	@Test
	public void aPartnerFiledAsSeveralRowsIsNamedOnceInTheInjectedRecord() throws Exception {
		// The patient is on dexamethasone, so all three of Voxelotor's dexamethasone rows are promoted
		// into segment 1 — the segment that overrides the character budget. Before this fix the record
		// read "dexamethasone (Major. …); dexamethasone (Moderate. …); dexamethasone (Moderate. …)"
		// beside a single Major chip.
		RecordMapping record = injectedRecord("is it safe to give voxelotor?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Dexamethasone 4mg"), null, null, null));
		String interactions = interactionsOf(record);

		assertEquals(1, notesHeadedBy(interactions, "dexamethasone"),
				"one substance is one partner, however many rows the KB files it as: " + interactions);
	}

	@Test
	public void theSurvivingNoteIsTheOneTheChipReports() throws Exception {
		// Which row survives is not a free choice: the chip for this pair is the MAJOR one
		// (DrugSafetyValidator.bestRulePerPartner, most severe wins), so a record naming the Moderate
		// mechanism beside it would put a severity in the prompt that the deterministic layer
		// deliberately discarded — the chip-versus-prose divergence this module keeps removing.
		//
		// The two mechanism texts are distinguishable: the Major row (mechanism group 3595) opens
		// "Coadministration with potent or moderate inducers of CYP450 3A4", the two Moderate rows
		// (group 3270) open "Coadministration with inhibitors of CYP450 3A4".
		RecordMapping record = injectedRecord("is it safe to give voxelotor?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Dexamethasone 4mg"), null, null, null));
		String interactions = interactionsOf(record);

		assertTrue(interactions.contains("dexamethasone (major."),
				"the promoted note must carry the severity the chip reports: " + interactions);
		assertFalse(interactions.contains("dexamethasone (moderate"),
				"and must not also carry the severity the chip discarded: " + interactions);
	}

	@Test
	public void theDatasetTailNamesEachPartnerOnceToo() throws Exception {
		// The same collapse with NOTHING promoted — the common shape, since most patients are on none
		// of an entry's partners. Segment 2 then spends the whole budget on full notes in dataset
		// order, so a repeated partner is a repeated paragraph: Voxelotor's two sirolimus rows
		// (Major and Moderate) are two separate mechanism paragraphs about one co-medication.
		RecordMapping record = injectedRecord("is it safe to give voxelotor?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));
		String interactions = interactionsOf(record);

		assertEquals(1, notesHeadedBy(interactions, "sirolimus"),
				"the dataset tail must name each partner once as well: " + interactions);
		assertEquals(1, notesHeadedBy(interactions, "dexamethasone"),
				"including the family the patient is not on: " + interactions);
	}

	@Test
	public void theWithheldCountIsPartnersNotRows() throws Exception {
		// RenderedReference.withheldInteractions is documented as "interaction partners the text does
		// not name", and a client renders it beside the citation chip as honest truncation. Counted
		// over ROWS it did not describe that: Voxelotor's 7 rows name 4 partners, and with
		// dexamethasone promoted the record named 2 of them (dexamethasone and the tail
		// representative lapatinib) while declaring 3 withheld — one more than the 2 partners
		// (phenytoin, sirolimus) it actually leaves out.
		//
		// Asserted against the section itself rather than against a literal, so the invariant is
		// "the count equals what is missing" rather than a number that has to be re-derived whenever
		// the budget or the fixture moves.
		RecordMapping record = injectedRecord("is it safe to give voxelotor?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Dexamethasone 4mg"), null, null, null));
		String interactions = interactionsOf(record);

		int unnamed = 0;
		for (String partner : new String[] { "dexamethasone", "lapatinib", "phenytoin", "sirolimus" }) {
			if (notesHeadedBy(interactions, partner) == 0) {
				unnamed++;
			}
		}
		assertEquals(2, unnamed,
				"precondition: this record must leave exactly two of the four partners unnamed: "
						+ interactions);
		assertEquals(unnamed, record.getWithheldInteractions(),
				"the withheld count must be the partners the text does not name, not the rows: "
						+ interactions);
	}

	@Test
	public void collapsingTheRowsShortensTheRecordTheModelReads() throws Exception {
		// The prompt-budget claim, measured on this slice rather than asserted in prose. Segment 1
		// overrides MAX_INTERACTION_RENDER_CHARS for every promoted partner, so the two surplus
		// dexamethasone rows were 703 characters of near-duplicate text that the budget could not
		// claw back — a 649-character Moderate mechanism paragraph plus a compact repeat. Measured
		// 2026-08-07 through this test: the record was 1124 characters and is now 421.
		//
		// Pinned as an exact length rather than an inequality because the number IS the finding — an
		// inequality would still pass if the collapse kept one of the two surplus rows.
		RecordMapping record = injectedRecord("is it safe to give voxelotor?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Dexamethasone 4mg"), null, null, null));

		assertEquals(421, record.getText().length(),
				"the injected record's character cost must fall to the collapsed rendering: "
						+ record.getText());
	}

	@Test
	public void aSinglePartnerRecordIsUnchanged() throws Exception {
		// The control. Nothing may move for an entry whose partners are each filed once: the
		// bundled DDInter sample's Lisinopril carries 15 partners and no repeats, so its record must
		// render byte-for-byte as it did before the collapse. Pinned as the exact string rather than
		// a length, because a collapse keyed wrongly (on the note, say, or on the severity) would
		// change WHICH partner survives while leaving the length alone.
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("ibuprofen"), null, null, null),
						"is it safe to give lisinopril?");
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).get(0);

		assertEquals("Drug reference — Lisinopril (ATC C09AA03). Interactions: ibuprofen (Moderate. "
				+ "Nonsteroidal anti-inflammatory drugs (NSAIDs) may attenuate the antihypertensive "
				+ "effects of ACE inhibitors. The proposed mechanism is NSAID-induced inhibition of "
				+ "renal prostaglandin synthesis, which results in unopposed pressor activity "
				+ "producing hypertension. In addition, NSAIDs can cause fluid retention, which also "
				+ "affects blood pressure. Concomitant use of NSAIDs and ACE inhibitors may also "
				+ "cause deterioration in renal function, particularly in patients who are elderly "
				+ "or volume-depleted (including those on diuretic therapy) or have compromised "
				+ "renal function. Acute renal failure may occur, although effects are usually "
				+ "reversible. Chronic use of NSAIDs alone may be associated with renal toxicities, "
				+ "including elevations in serum creatinine and BUN, tubular necrosis, glomerulitis, "
				+ "renal papillary necrosis, acute interstitial nephritis, nephrotic syndrome, and "
				+ "renal failure.); metformin (Moderate).",
				record.getText(),
				"a single-partner entry's record must be byte-identical: " + record.getText());
		assertEquals(13, record.getWithheldInteractions(),
				"and its withheld count must not move either: " + record.getText());
	}
}
