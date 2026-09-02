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

import java.util.ArrayList;
import java.util.Arrays;
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

	/** The lowercased {@code Interactions:} section of a rendered record — {@link DrugReferenceTestSupport}'s
	 *  rule, which is where it lives now that a third class wanted it and its own copy had drifted. */
	private static String interactionsOf(RecordMapping record) {
		return DrugReferenceTestSupport.interactionsSectionOf(record);
	}

	/**
	 * How many NOTES in {@code section} are headed by {@code partner} — occurrences of the label
	 * followed by the rendering's own {@code " ("}, not of the bare name, because a mechanism
	 * paragraph legitimately mentions the drugs it is about ("…exposure to sirolimus, which is
	 * primarily metabolized…") and counting those would make this assert something else.
	 * {@link DrugReferenceTestSupport}'s rule, shared with the case that asks WHERE such a note begins
	 * rather than how many there are.
	 */
	private static int notesHeadedBy(String section, String partner) {
		return DrugReferenceTestSupport.notesHeadedBy(section, partner);
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
		// of an entry's partners. Before the collapse, the dataset tail spent the whole budget on full
		// notes in dataset order, so a repeated partner was a repeated paragraph: Voxelotor's two
		// sirolimus rows (Major and Moderate) were two separate mechanism paragraphs about one
		// co-medication. Since issue #355 that segment names a bounded handful of partners compactly
		// instead, where nothing patient-specific was shown. This case
		// constrains MAX_TAIL_PARTNERS_WHEN_NOTHING_PATIENT_SPECIFIC from below only as far as its own two
		// assertions reach — sirolimus and dexamethasone, of this entry's four partners — so it is
		// green at 3, where a partner it does not assert on goes unnamed.
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
		// claw back — a 659-character Moderate mechanism paragraph plus a compact repeat. Measured
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

	/**
	 * The operator-editable curated source, which is the only one that can pose the label questions:
	 * plain Jackson over a hand-authored file, so a rule token keeps whatever case and padding it was
	 * written with. Shared with {@code InteractionPartnerGroupingTest}, which asks the same questions
	 * of the CHIPS.
	 */
	private static final String CURATED_FIXTURE =
			"chartsearchai-test/drug-reference-partner-label-variants.json";

	private static RecordMapping curatedRecord(String question, PatientClinicalContext context)
			throws Exception {
		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport
						.serviceWith(DrugReferenceTestSupport.fixtureEntries(CURATED_FIXTURE)))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		return DrugReferenceTestSupport.injectedReferences(chart).get(0);
	}

	@Test
	public void twoCuratedRowsForOnePartnerRenderOnce() throws Exception {
		// The chip side of this has been one chip since issue #115 — hasActiveDrug folds case and
		// padding, so "Warfarin" and "  warfarin  " are one partner to the only predicate that decides
		// an interaction concerns this patient. The record listed both, at two severities, and printed
		// the padding: "warfarin   (Major. …)" beside a chip reading "active order warfarin".
		RecordMapping record = curatedRecord("is it safe to give fluconazole?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Warfarin 5mg"), null, null, null));
		String interactions = interactionsOf(record);

		assertEquals(1, notesHeadedBy(interactions, "warfarin"),
				"one partner is one note however the operator spelled the token: " + interactions);
		assertTrue(interactions.contains("warfarin (major."),
				"the label must be trimmed and the surviving row the Major one — the same row the chip "
						+ "reports: " + interactions);
	}

	@Test
	public void twoCuratedRowsIdentifyingOnePartnerByAtcAlsoRenderOnce() throws Exception {
		// The other half of the label, which no shipped dataset exercises: a rule carrying an ATC code
		// and no token at all. The fixture writes the same code as " b01aa03 " and "B01AA03".
		RecordMapping record = curatedRecord("is it safe to give rivaroxaban?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));
		String interactions = interactionsOf(record);

		assertEquals(1, notesHeadedBy(interactions, "b01aa03"),
				"one ATC-identified partner is one note whatever case and padding it carries: "
						+ interactions);
		assertTrue(interactions.contains("b01aa03 (major)"),
				"and the surviving row is the Major one — named in the compact form the dataset tail "
						+ "takes since issue #355, the severity being what a name can still carry: "
						+ interactions);
	}

	/**
	 * A curated entry filing ONE partner as two rows carrying the same token and DIFFERENT ATC codes
	 * — a shape in which two rows of one partner group answer
	 * {@link PatientClinicalContext#hasActiveDrug} differently, because sharing a token leaves only the
	 * ATC arm able to separate them. It used to say "the only shape" and no longer does: the
	 * order-driven grouping branch was never ruled out, and issue #357's sub-floor sibling of this
	 * fixture carries the same caveat. See the fixture's own description.
	 */
	private static final String ATC_SPLIT_FIXTURE =
			"chartsearchai-test/drug-reference-partner-atc-split-rows.json";

	/** On acenocoumarol ({@code B01AA07}) by ATC alone, so the rules' TOKEN arm matches neither row
	 *  and only the {@code B01AA07} row is the patient's. */
	private static PatientClinicalContext onAcenocoumarolByAtc() {
		return DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("B01AA07"),
				null, null);
	}

	@Test
	public void thePartnerTheChipWarnsAboutSurvivesTheCollapse() throws Exception {
		// The collapse runs BEFORE the promotion predicate, so the survivor rule decides which row's
		// (token, ATC) pair that predicate is then asked about. Where two rows of one partner carry
		// DIFFERENT ATC codes those answers differ, and the most severe row can be the one the
		// patient does not match — so a wrong choice here costs the partner its place in segment 1,
		// the segment that overrides MAX_INTERACTION_RENDER_CHARS. (Since issue #357 a de-promoted row
		// of a partner the chart names lands in the segment between segment 1 and the tail instead,
		// which is why that issue asks this same question of its own boundary; here both warfarin rows
		// are above the floor, so the tier that moves is still promotion's.)
		//
		// Before issue #355, that cost was eviction from the record: with something patient-specific
		// shown, the tail rendered exactly one representative, so a de-promoted partner competing for
		// that slot did not merely change wording — it left the record while the chip still warned
		// about it. Since #355 that branch is unchanged (still one representative where something
		// patient-specific was shown), but THIS fixture cannot reach it: rifabutin and carbamazepine
		// match neither row of onAcenocoumarolByAtc(), so nothing but warfarin is ever promotable here,
		// and a wrong collapse choice (surviving on the unmatched B01AA03 row) would leave the two
		// patient-specific segments both empty. Since #355 that means the compact, severity-ordered
		// tail capped at MAX_TAIL_PARTNERS_WHEN_NOTHING_PATIENT_SPECIFIC, not budget exhaustion on full
		// paragraphs — so a wrong choice here would now cost this fixture the WORDS (the wrong,
		// unmatched row's text) rather than warfarin's presence. Measured on the merged head
		// (2026-09-02): injectRecords over a context that, like the wrong survivor, matches none of the
		// three rows renders "Interactions: rifabutin (Major); carbamazepine (Major); warfarin (Major)."
		// — 73 characters, warfarin present under its wrong row, withheldInteractions 0.
		//
		// bestRulePerPartner cannot reach this shape because it filters on hasActiveDrug BEFORE
		// grouping, so only rows the patient matches are ever candidates. This asserts the collapse
		// makes the same choice.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(ATC_SPLIT_FIXTURE);
		PatientClinicalContext context = onAcenocoumarolByAtc();

		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(entries))
				.validate("", "is it safe to give voriconazole?", context);
		assertEquals(1, warnings.size(),
				"precondition: exactly one chip, for the partner the patient IS on, was: " + warnings);
		assertTrue(warnings.get(0).getDetail().contains("active order warfarin — Moderate."),
				"precondition: the chip must quote the row whose ATC the patient matches, was: "
						+ warnings.get(0).getDetail());

		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(entries))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
						"is it safe to give voriconazole?");
		String interactions =
				interactionsOf(DrugReferenceTestSupport.injectedReferences(chart).get(0));

		assertEquals(1, notesHeadedBy(interactions, "warfarin"),
				"the partner the chip warns about must be named exactly once in the record: "
						+ interactions);
		assertTrue(interactions.contains("warfarin (moderate."),
				"and under the rule the chip quotes, not the more severe row the patient does not "
						+ "match: " + interactions);
	}

	/**
	 * A curated slice carrying a {@code Warfarin} entry whose aliases are {@code warfarin} AND
	 * {@code coumadin}, and an {@code Ibuprofen} entry with one rule under each of those names — issue
	 * #136's shape, and issue #190 item 2's residue. Shared with
	 * {@code DrugSafetyQuestionPairInteractionTest}, which asks the chart-precedence question of it.
	 */
	private static final String TWO_NAME_PARTNER_FIXTURE =
			"chartsearchai-test/drug-reference-question-pairs.json";

	@Test
	public void twoNamesOfOnePartnerEntryRenderOneNote() throws Exception {
		// Issue #190 item 2, re-scoped by its own comment thread: the label key stays — keying the whole
		// grouping on a dataset-wide resolution merges genuinely distinct partners on 397 of the shipped
		// KB's 2283 entries (trastuzumab with trastuzumab deruxtecan) and in a RECORD that costs a
		// partner its name. What was missing is that where the rule resolves to one of the patient's own
		// ACTIVE ORDERS the chip already keys on that ENTRY, so two names of one order produced two notes
		// beside one chip. Adopting the chip's own two-tier key leaves the dataset tail on the label,
		// where the 397-entry over-merge lives, and cannot reach it.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(TWO_NAME_PARTNER_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Warfarin 5mg"), null, null, null);
		String question = "is it safe to give ibuprofen?";

		DrugReference warfarin = DrugReferenceTestSupport.row(entries, "Warfarin");
		assertTrue(warfarin.isNamed("warfarin") && warfarin.isNamed("coumadin"),
				"precondition: ONE entry must carry both names, or there is nothing to fold");
		List<String> tokens = new ArrayList<String>();
		for (DrugReference.Interaction rule : DrugReferenceTestSupport.row(entries, "Ibuprofen")
				.getInteractions()) {
			tokens.add(rule.getToken());
		}
		assertEquals(Arrays.asList("coumadin", "warfarin"), tokens,
				"precondition: and two rules must reach it, one under each name");

		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(entries))
				.validate("", question, context);
		assertEquals(1, warnings.size(),
				"precondition: the chip side is already ONE chip for this pair, was: " + warnings);

		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(entries))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		String interactions =
				interactionsOf(DrugReferenceTestSupport.injectedReferences(chart).get(0));

		assertEquals(1, notesHeadedBy(interactions, "warfarin") + notesHeadedBy(interactions, "coumadin"),
				"one partner entry is one note however many of its names the rules use: " + interactions);
	}

	@Test
	public void aSinglePartnerRecordIsUnchanged() throws Exception {
		// The control. Nothing may move for an entry whose partners are each filed once: the
		// DDInter excerpt's Lisinopril carries 15 partners and no repeats, so its record must
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
