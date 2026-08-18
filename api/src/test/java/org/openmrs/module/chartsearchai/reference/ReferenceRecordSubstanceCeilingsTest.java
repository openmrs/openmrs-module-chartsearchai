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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Issue #259's NUMERIC half — the injected {@code drug_reference} record states the dosing the
 * substance's other resolved rows publish, so no dose chip can quote a ceiling the citable record does
 * not carry.
 *
 * <p><b>What issue #237's clause left open.</b> That fix made the record say WHICH ROW it describes
 * ({@code DrugReferenceInjector.rowAttribution}), which removes the ambiguity in a NAME. It does not
 * remove a contradiction in a NUMBER: the record still rendered one row's band alone, so a response
 * could carry
 * <pre>
 * [4] Drug reference — Amoxicillin … maximum 3000 mg/day       (the row the record renders)
 * [5] Safety finding — … exceeds the 2000 mg/day maximum       (the row the chart names)
 * </pre>
 * with only the 3000 citable. A clinician reading the cited record still sees a ceiling the warning
 * beside it did not use — which is issue #259 as reported, and the reason it says a number is worse than
 * a name.
 *
 * <p><b>Why the record is the surface that has to move.</b> The chip arm already reads the whole
 * substance: {@code DrugSafetyValidator.addOverdose} tries the subject row's band and then EVERY sibling
 * row's, and {@code anyActionableBand} lets it act when the rendered row publishes no band at all. The
 * record read exactly one of those rows. That asymmetry is the defect — not which row either surface
 * prefers — so the fix is to give the record the same row set the chips fold, and no row's turn to be
 * RENDERED changes (issue #237 measured and declined that: the route-unspecified row carries the
 * breadth, and rendering the charted one loses the patient's own interaction partner in 74 of the
 * shipped KB's 129 multi-row families).
 *
 * <p><b>The guard is deliberately NOT {@code worthNamingApart}</b>, which the attribution clause shares
 * with {@code DrugSafetyValidator.ceilingAttribution} — see
 * {@link #twoRowsPublishingOneNameStillStateTheirOwnCeilings}. That guard answers "would CONTRASTING
 * these two rows say anything", and "for X, not for X" is a contradiction. This section ENUMERATES, and
 * an item under a name the record already uses still says something a reader can act on: a different
 * number. Asking one question with the other's predicate is the conflation CLAUDE.md's ATC bullet exists
 * to forbid, one feature along.
 *
 * <p><b>Reachability.</b> {@code DdiDrugReferenceSource} sets no {@code ageBands} at all, so no ddinter
 * file — the shipped 19 MB KB included — can reach any of this; and every bundled curated substance is
 * one row ({@link #noBundledCuratedDatasetCanReachTheSectionAtAll}). It is reachable for a deployment
 * authoring per-presentation dosing, which is the normal reason to author a curated file, and nothing
 * tested it before this file.
 *
 * <p>Every case drives the REAL {@code injectRecords} — and, where a chip is the other half of the
 * claim, the REAL {@code validate} — over fixtures parsed by the real production parsers.
 */
public class ReferenceRecordSubstanceCeilingsTest {

	/** Two Amoxicillin rows publishing DIFFERENT ceilings (3000 against 2000), plus the one-display-name
	 *  pair, the {@code genericName} pair and a single-row control — see the fixture's own
	 *  {@code description}. */
	private static final String CEILINGS =
			"chartsearchai-test/drug-reference-substance-dosing-ceilings.json";

	/** The SAME-ceiling control (two Amoxicillin rows both publishing 3000), which is also the fixture
	 *  whose {@code Cefalexin} pair files the band on the row the record does NOT render. */
	private static final String ROWS = "chartsearchai-test/drug-reference-substance-dosing-rows.json";

	/**
	 * The section's lead, shared by every POSITIVE expectation and every NEGATIVE guard here so the two
	 * cannot come apart — the failure {@code ReferenceRecordRowAttributionTest.ATTRIBUTION_LEAD} records
	 * having actually happened: seven silence guards written against wording production later stopped
	 * emitting passed unconditionally, green, for as long as nothing tied them to the positive cases.
	 */
	private static final String SECTION_LEAD = "Also published for other rows of this substance: ";

	/** The clause issue #237 added, asserted here only where this section must be able to speak WITHOUT
	 *  it (and vice versa) — the two are independent and one guard must not silence the other. */
	private static final String ATTRIBUTION_LEAD = "Published by this dataset for";

	/** The injected reference record naming {@code drug}, through the real injector. */
	private static String record(DrugReferenceService service, PatientClinicalContext context,
			String question, String drug) {
		String text = DrugReferenceTestSupport.referenceTextNaming(
				DrugReferenceTestSupport.injectorWithSafety(service)
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question),
				drug);
		assertNotNull(text, "precondition: a record must be injected for " + drug);
		return text;
	}

	/** @return the daily ceiling a dose chip quoted, read out of the chip's own sentence — so a case can
	 *          require the RECORD to carry the number the WARNING used without either being hardcoded
	 *          against the other. */
	private static String quotedCeiling(String chipDetail) {
		Matcher m = Pattern.compile("exceeds the ([0-9.]+) mg/day maximum").matcher(chipDetail);
		assertTrue(m.find(), "precondition: the chip must quote a daily ceiling, was: " + chipDetail);
		return m.group(1);
	}

	@Test
	public void theRecordStatesTheCeilingTheChipQuotes() throws IOException {
		// #259 as reported, and the whole point of the change: the record renders the 3000 row while the
		// chip warns at the charted row's 2000, and the record is the CITABLE one. Both numbers now.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Amoxicillin (suspension)");
		String question = "What dose of amoxicillin?";

		// The premise: the two rows publish DIFFERENT ceilings, so there really are two numbers. On
		// drug-reference-substance-dosing-rows.json they both publish 3000 and this case asserts nothing.
		List<DrugReference> rows = service.getAll();
		assertEquals(3000.0, DrugReferenceTestSupport.row(rows, "Amoxicillin").bandForAge(30)
				.getMaxDailyDoseMg(), 0.0, "precondition: the rendered row publishes 3000");
		assertEquals(2000.0, DrugReferenceTestSupport.row(rows, "Amoxicillin (suspension)").bandForAge(30)
				.getMaxDailyDoseMg(), 0.0, "precondition: and the charted row publishes 2000");

		String record = record(service, context, question, "Amoxicillin");

		// The WHOLE record, which is the strictest form available here and the one that pins WHERE the
		// section sits: after the sentence it extends, because it is more of the same kind of content
		// rather than a qualifier on it (the attribution clause is the qualifier, and issue #208's rule
		// puts THAT in front). Nothing else is rendered for this entry, so exact equality costs no
		// brittleness and buys the placement.
		assertEquals("Drug reference — Amoxicillin (ATC J01CA04). Published by this dataset for "
				+ "Amoxicillin, not for Amoxicillin (suspension) — the row this patient's record names, "
				+ "filed separately for the same substance. Dosing for ages 0-120: 15-30 mg/kg per dose, "
				+ "maximum 3000 mg/day. " + SECTION_LEAD
				+ "Amoxicillin (suspension) 15-30 mg/kg per dose, maximum 2000 mg/day (ages 0-120).",
				record, "the record keeps its own row's ceiling, says whose it is, and states the other "
						+ "row's — in that order");

		// THE property, derived rather than restated: whatever ceiling the chip quotes must be in the
		// record. A fix that stated some other row's number, or that stated this one under a different
		// rounding, passes the equality above only by luck and fails here.
		String chip = DrugReferenceTestSupport.overdoseDetail(DrugReferenceTestSupport.validator(service)
				.validate("Give amoxicillin 1250 mg twice daily.", question, context),
				"Amoxicillin (suspension)");
		assertTrue(record.contains("maximum " + quotedCeiling(chip) + " mg/day"),
				"the record must carry the ceiling the chip quoted — #259 is the two surfaces stating two "
						+ "numbers with only one of them citable. Chip: " + chip + "; record: " + record);
	}

	@Test
	public void aRenderedRowPublishingNoBandStillStatesASiblingsCeiling() throws IOException {
		// The starker shape of the same defect, and the one no wording fix reaches: the row the record
		// renders publishes NO band for this patient, so the record carried no number at all while
		// DrugSafetyValidator.anyActionableBand let the chip warn on the sibling's. Silence beside a
		// number is not a contradiction a reader can even see.
		//
		// No active order and nothing recorded, deliberately: this section is a fact about the DATASET,
		// so unlike issue #237's clause it must speak with the chart saying nothing — asserted below by
		// requiring the clause to be ABSENT from the very record the section fills.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(ROWS));
		PatientClinicalContext context =
				DrugReferenceTestSupport.ctx(5, 18.0, null, null, null, null);
		String question = "What dose of cefalexin?";

		List<DrugReference> rows = service.getAll();
		DrugReference rendered = DrugReferenceTestSupport.row(rows, "Cefalexin");
		DrugReference paediatric = DrugReferenceTestSupport.row(rows, "Cefalexin (paediatric)");
		assertEquals(rendered.substanceGroupKey(), paediatric.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertEquals(null, rendered.bandForAge(5),
				"precondition: the row the record renders publishes NO band for this patient");
		assertEquals(1000.0, paediatric.bandForAge(5).getMaxDailyDoseMg(), 0.0,
				"precondition: while its sibling publishes 1000 for this age");

		String record = record(service, context, question, "Cefalexin");

		assertEquals("Drug reference — Cefalexin (ATC J01DB01). " + SECTION_LEAD
				+ "Cefalexin (paediatric) 12.5-25 mg/kg per dose, maximum 1000 mg/day (ages 0-11).",
				record, "a record whose own row publishes nothing still states the row that does");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"and states it without claiming anything about a chart that named no row, was: " + record);

		String chip = DrugReferenceTestSupport.overdoseDetail(DrugReferenceTestSupport.validator(service)
				.validate("Give cefalexin 750 mg twice daily.", question, context), "Cefalexin");
		assertTrue(record.contains("maximum " + quotedCeiling(chip) + " mg/day"),
				"the record must carry the ceiling the chip quoted. Chip: " + chip + "; record: " + record);
	}

	@Test
	public void aSiblingBandOutsideThePatientsAgeIsNotStated() throws IOException {
		// The bound that keeps this from becoming a formulary dump, and it is the record's OWN existing
		// rule rather than a new one: numeric dosing is rendered only for a band matching the patient's
		// age (config.xml — "a pediatric maximum is never surfaced for an adult query"). The sibling's
		// band is 0-11, this patient is 30, and no chip can quote it either — so there is nothing to
		// reconcile and nothing to say.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(ROWS));
		PatientClinicalContext context =
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null);
		String question = "What dose of cefalexin?";

		assertEquals(null, DrugReferenceTestSupport.row(service.getAll(), "Cefalexin (paediatric)")
				.bandForAge(30), "precondition: the sibling's band must not cover this patient's age");

		String record = record(service, context, question, "Cefalexin");

		assertEquals("Drug reference — Cefalexin (ATC J01DB01).", record,
				"a band this patient's age does not reach is not this patient's business");
		assertEquals("", DrugReferenceTestSupport.overdoseDetail(DrugReferenceTestSupport.validator(service)
				.validate("Give cefalexin 750 mg twice daily.", question, context), "Cefalexin"),
				"precondition: nor can any chip quote it, which is why the silence is right");
	}

	@Test
	public void theSectionStatesEveryRowThePassResolvedNotOnlyTheInjectedOnes() throws IOException {
		// The hazard issue #237's own harden pass found for the clause, which reaches this section by the
		// same route: the rows the injector decides to INJECT are the resolved rows filtered by the
		// injectFromQuery/injectFromOrders toggles and by the relevance gate, so a section built from them
		// would fall silent on a sibling the patient is actually on — a NUMBER the record states or
		// withholds depending on configuration. The fixture's Mupirocin pair is built for exactly this:
		// the nasal row publishes no bare alias, so the question injects the unqualified row ALONE, and
		// neither row carries an ATC code so the relevance gate cannot inject the nasal one either.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Mupirocin (nasal)");
		String question = "What dose of mupirocin?";

		// The premise, through the production accessor: the question really does resolve ONE row, so the
		// other row is in this section only because the pass resolved it from the patient's own order.
		assertEquals("[Mupirocin]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery(question)).toString(),
				"precondition: the question must resolve the unqualified row alone");
		assertEquals(30.0, DrugReferenceTestSupport.row(service.getAll(), "Mupirocin (nasal)")
				.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: whose sibling publishes a different ceiling, or there is nothing to state");

		String record = record(service, context, question, "Mupirocin");

		assertTrue(record.contains(SECTION_LEAD
				+ "Mupirocin (nasal) 1-2 mg/kg per dose, maximum 30 mg/day (ages 0-120)."),
				"what a substance's rows publish cannot depend on which of them reached the prompt, was: "
						+ record);
	}

	@Test
	public void rowsPublishingTheSameDosingSayNothingFurther() throws IOException {
		// The control that bounds the prompt cost (issue #229): where the substance's rows agree, the
		// section states nothing, because restating the number the record already carries spends budget
		// to say what it said. Without this a fix could enumerate unconditionally and pay per row on
		// every multi-row substance, which is the crowding-out issue #163 exists to prevent.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(ROWS));
		List<DrugReference> rows = service.getAll();
		assertEquals(DrugReferenceTestSupport.row(rows, "Amoxicillin").bandForAge(30).getMaxDailyDoseMg(),
				DrugReferenceTestSupport.row(rows, "Amoxicillin (suspension)").bandForAge(30)
						.getMaxDailyDoseMg(),
				0.0, "precondition: this fixture's two rows publish the SAME ceiling");

		String record = record(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0,
				"Amoxicillin (suspension)"), "What dose of amoxicillin?", "Amoxicillin");

		// The whole record, so the case also pins that issue #237's clause is untouched by this: a
		// substance whose rows agree on the numbers can still disagree on the NAME, and it still says so.
		assertEquals("Drug reference — Amoxicillin (ATC J01CA04). Published by this dataset for "
				+ "Amoxicillin, not for Amoxicillin (suspension) — the row this patient's record names, "
				+ "filed separately for the same substance. Dosing for ages 0-120: 15-30 mg/kg per dose, "
				+ "maximum 3000 mg/day.",
				record, "rows that publish one ceiling state it once");
		assertFalse(record.contains(SECTION_LEAD),
				"a section restating the record's own numbers says nothing, was: " + record);
	}

	@Test
	public void aSingleRowSubstanceStatesNoOtherRowsDosing() throws IOException {
		// The other bound: a substance the dataset files as ONE row has no other row to state, whatever
		// the chart says. This is every substance of every BUNDLED curated dataset and every entry of
		// every ddinter file, which is what keeps the field impact of this section to deployments that
		// author per-presentation dosing themselves.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		String record = record(service,
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Cefadroxil"),
				"What dose of cefadroxil?", "Cefadroxil");

		assertEquals("Drug reference — Cefadroxil (ATC J01DB05). Dosing for ages 0-120: 15-30 mg/kg per "
				+ "dose, maximum 2000 mg/day.", record,
				"a one-row substance renders exactly as it did before issue #259");
		assertFalse(record.contains(SECTION_LEAD),
				"a one-row substance has no other row, was: " + record);
	}

	@Test
	public void twoRowsPublishingOneNameStillStateTheirOwnCeilings() throws IOException {
		// The deliberate divergence from issue #237's clause, and the reason this section does NOT share
		// DrugSafetyValidator.worthNamingApart. Two rows of one substance under ONE display name
		// (Ranitidine/ranitidine, 300 against 150) make the CONTRAST unsayable — "for Ranitidine, not for
		// Ranitidine" is a contradiction, so the clause stays silent, which
		// ReferenceRecordRowAttributionTest.twoRowsPublishingOneNameAreAttributedToNobody pins.
		//
		// The ENUMERATION is still sayable and still needed: the chip can quote 150, so silence here
		// would keep exactly the defect this file is about, in the one dataset shape where the chip's own
		// attribution is ALSO silent — i.e. where the record is the reader's only route to the number.
		// The lead is what carries the sense ("other rows of this substance"), so a second item under a
		// name the record already used reads as an oddly-named sibling rather than as a contradiction.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		List<DrugReference> rows = service.getAll();
		DrugReference first = DrugReferenceTestSupport.row(rows, "Ranitidine");
		DrugReference second = DrugReferenceTestSupport.row(rows, "ranitidine");
		assertEquals(first.substanceGroupKey(), second.substanceGroupKey(),
				"precondition: one substance");
		assertEquals(DrugReference.normalizeName(first.getName()),
				DrugReference.normalizeName(second.getName()),
				"precondition: whose two rows carry ONE name by this module's identity rule");
		assertEquals(150.0, second.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: and publish different ceilings, 150 against the rendered row's 300");

		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "zantac");
		String question = "What dose of ranitidine?";
		String record = record(service, context, question, "Ranitidine");

		assertTrue(record.contains(SECTION_LEAD
				+ "ranitidine 2-4 mg/kg per dose, maximum 150 mg/day (ages 0-120)."),
				"the number is stated even where the CONTRAST cannot be, was: " + record);
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"precondition: while the contrast itself stays silent — the two guards are not one guard, "
						+ "was: " + record);

		String chip = DrugReferenceTestSupport.overdoseDetail(DrugReferenceTestSupport.validator(service)
				.validate("Give ranitidine 100 mg twice daily.", question, context), "Ranitidine");
		assertTrue(record.contains("maximum " + quotedCeiling(chip) + " mg/day"),
				"and it is the chip's own number. Chip: " + chip + "; record: " + record);
	}

	@Test
	public void theSectionNamesTheRowsTheRecordDoesAndNotTheirChipLabels() throws IOException {
		// DrugReference.displayLabel's javadoc says verbatim: "Never used in prompt text — record
		// rendering keeps getName() — so this is a chip-display concern only", and
		// DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText pins that for the rest
		// of the record but cannot reach here (its aspirin entry is a one-row substance, so no sibling is
		// ever named). This section names a row, which is exactly where the synonym-augmented label
		// leaks: mutation found that in issue #237's clause, not review.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		List<DrugReference> rows = service.getAll();
		DrugReference coated = DrugReferenceTestSupport.row(rows, "Acetylsalicylic acid (enteric-coated)");
		assertEquals("Acetylsalicylic acid (enteric-coated) (aspirin)", coated.displayLabel(),
				"precondition: the sibling's CHIP label differs from its name, or nothing can leak");

		String record = record(service, DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null),
				"What dose of acetylsalicylic acid?", "Acetylsalicylic acid");

		assertTrue(record.contains(SECTION_LEAD + "Acetylsalicylic acid (enteric-coated) 1-2 mg/kg per "
				+ "dose, maximum 300 mg/day (ages 0-120)."),
				"the section names the row the way the record names rows, was: " + record);
		assertFalse(record.contains("(aspirin)"),
				"a chip label must not reach prompt text, was: " + record);
	}

	@Test
	public void rowsGroupedOnlyByASharedIdStateNothing() throws IOException {
		// The same operator-editable boundary issue #237's clause refuses to speak across. A source
		// publishing no substanceName falls back to getId(), the curated parser drops an entry only for a
		// blank id or name, and no DrugReferenceValidity rule reports a duplicate id — so two rows can
		// share a group without the file having said they are one substance. "Other rows of this
		// substance" would then claim what the data does not support, about a NUMBER.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-duplicate-id-rows.json"));
		List<DrugReference> rows = service.getAll();
		DrugReference unqualified = DrugReferenceTestSupport.row(rows, "Trimethoprim");
		DrugReference paediatric = DrugReferenceTestSupport.row(rows, "Trimethoprim (paediatric)");
		assertEquals(unqualified.getId(), paediatric.getId(), "precondition: the two rows share an id");
		assertEquals(null, unqualified.substanceKey(),
				"precondition: and neither declares a substance, so the group is the id fallback");
		assertEquals(400.0, unqualified.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: their ceilings differ, so a section WOULD have content but for the guard");
		assertEquals(200.0, paediatric.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: 200 against 400");

		// THE precondition, and the one whose absence made this case unable to fail. The group is the
		// union of the two legs, and the ranked accessors return one row per NAME — so while
		// `trimethoprim` was the second row's only alias, BOTH legs resolved the first row, the group was
		// a single row, and this case passed on a group that could never have reached the guard. Asserted
		// through the same two production accessors matchingEntries unions, so a fixture edit that
		// re-blinds it reddens here rather than silently.
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "tmp-paediatric");
		String question = "What dose of trimethoprim?";
		assertEquals("[Trimethoprim]",
				DrugReferenceTestSupport.names(service.findImpliedByQuery(question)).toString(),
				"precondition: the question leg must resolve the unqualified row");
		assertEquals("[Trimethoprim (paediatric)]",
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)).toString(),
				"precondition: and the order leg the OTHER row, or the group is one row and the guard is "
						+ "never reached");

		String record = record(service, context, question, "Trimethoprim");

		assertFalse(record.contains(SECTION_LEAD),
				"rows the file never called one substance state nothing for each other, was: " + record);
	}

	@Test
	public void noBundledCuratedDatasetCanReachTheSectionAtAll() throws IOException {
		// The bound on what this changes in the field, asserted over the shipped seed rather than argued:
		// no bundled curated entry sets substanceName, so every substance there is one row. The ddinter
		// half needs no case — DdiDrugReferenceSource sets no ageBands at all, so no row of the shipped
		// 19 MB KB publishes a ceiling for any row to disagree with.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReference ibuprofen = service.lookupByToken("ibuprofen");
		assertNotNull(ibuprofen, "precondition: the shipped seed must carry ibuprofen");
		assertEquals(null, ibuprofen.getSubstanceName(),
				"precondition: the seed publishes no substance name, which is WHY every substance in it "
						+ "is one row");

		String record = record(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0,
				"Ibuprofen"), "What dose of ibuprofen?", "Ibuprofen");

		assertFalse(record.contains(SECTION_LEAD),
				"the shipped curated wording is unchanged, was: " + record);
	}

	@Test
	public void theSectionCostsItsLeadAndOneItemPerRowItNames() throws IOException {
		// Issue #229's question, answered as a cost MODEL rather than as a ceiling — the same form
		// ReferenceRecordRowAttributionTest.theClauseCostsTheRecordItsOwnLengthAndNoRecordItDoesNotQualify
		// takes for the clause. What the record spends is the lead once, plus each named row's own name
		// and numbers; a fix that repeated the lead per row, or that named rows it did not have to,
		// fails this without anyone having to guess a character budget.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		String record = record(service,
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Amoxicillin (suspension)"),
				"What dose of amoxicillin?", "Amoxicillin");

		String name = "Amoxicillin (suspension)";
		String numbers = "15-30 mg/kg per dose, maximum 2000 mg/day";
		String ages = " (ages 0-120)";
		assertTrue(record.endsWith(" " + SECTION_LEAD + name + " " + numbers + ages + "."),
				"precondition: the section is the last thing this record renders, was: " + record);

		// Measured off the RENDERED text, never off a string this case assembles from the same pieces it
		// then sums — that version could not fail, which is the defect this whole file's ATTRIBUTION_LEAD
		// note records having shipped once already.
		int at = record.indexOf(" " + SECTION_LEAD);
		assertTrue(at > 0, "precondition: the record must carry a section to cost, was: " + record);
		assertEquals(1 + SECTION_LEAD.length() + name.length() + 1 + numbers.length() + ages.length() + 1,
				record.length() - at, "cost model: one space, the lead ONCE, then per row its name, its "
						+ "numbers and its own age band — and one full stop for the section, not one per row");
	}
}
