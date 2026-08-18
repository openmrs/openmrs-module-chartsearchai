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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issues #237 and #259 — the injected {@code drug_reference} record says WHICH ROW of a substance it
 * describes, whenever this response names that substance by another row.
 *
 * <p><b>The defect, one shape reaching two surfaces.</b> {@code DrugReferenceInjector.collect} picks the
 * row a record renders with {@link DrugReference#canonicalRow} alone, while every chip picks it with
 * {@code DrugSafetyValidator.interactionSubject} — the chart's own claim first
 * ({@link DrugReference#nameMatchStrength}), the fold only among rows tied on that (issues #187, #194,
 * #206). So wherever the patient's chart names a non-canonical row the record and the chips speak of one
 * substance under two names, in one prompt, both citable:
 * <pre>
 * [4] Drug reference — Dexamethasone (ATC …)
 * [5] Safety finding — Dexamethasone (ophthalmic): … interacts with active order phenytoin
 * </pre>
 * Measured 2026-08-14 over the shipped 19 MB KB by driving the real {@code injectRecords} and the real
 * {@code validate}: of its 129 multi-row substances, 104 could be posed with both a record and a chip and
 * <b>all 104 named the substance differently</b>. Re-measure before relying on the figure.
 *
 * <p><b>#259 is that reaching a NUMBER</b>, which is worse than a name: the record renders the canonical
 * row's age band, so a clinician reading the cited record sees a ceiling the warning beside it did not
 * use, with nothing saying so.
 *
 * <p><b>What was decided, and what deliberately was not.</b> Rendering the record from the charted row
 * instead was measured and declined: over the same KB, drawing the patient's partner from the canonical
 * row, a record rendered from the charted row fails to name that partner in <b>74 of 129</b> families
 * (against 0 for the canonical row), because the route-unspecified row is the one carrying the breadth.
 * That trades a naming fix for a coverage loss. So this changes no row's turn to be RENDERED — what it
 * changes is that the record SAYS which row it is, and says it by contrast ("for X, not for Y"), which is
 * the vocabulary issue #244 built and measured for exactly this problem one surface along (see
 * {@code DrugSafetyValidator.ceilingAttribution}, whose sibling-label guard this shares rather than
 * restates).
 *
 * <p>Every case drives the REAL {@code injectRecords} — and, where a chip is the other half of the claim,
 * the REAL {@code validate} — over fixtures parsed by the real production parsers.
 */
public class ReferenceRecordRowAttributionTest {

	/** Two Amoxicillin rows publishing DIFFERENT daily ceilings, plus the one-display-name guard pair
	 *  and a single-row control — see the fixture's own {@code description}. */
	private static final String CEILINGS =
			"chartsearchai-test/drug-reference-substance-dosing-ceilings.json";

	/**
	 * The clause's opening words, shared by every POSITIVE expectation and every NEGATIVE guard here so
	 * the two cannot come apart. They did: this file's silence cases were written against
	 * {@code "Published for"} and the production wording later became {@code "Published by this dataset
	 * for"} (a bare "published for X, not for Y" reads as a licensing claim — "indicated for X" — which
	 * is the opposite of what the sentence means). The positive cases went red and were updated; the
	 * seven {@code assertFalse}s went on passing, because a string production never emits is absent from
	 * every record. Seven guards that could not fail, green, until this constant tied them to the same
	 * words the positive cases assert.
	 */
	private static final String ATTRIBUTION_LEAD = "Published by this dataset for";

	private static PatientChart inject(DrugReferenceService service, PatientClinicalContext context,
			String question) {
		return DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}

	@Test
	public void theRecordSaysWhichRowItDescribesWhenTheChartNamesAnother() throws IOException {
		// #237, on rows that are verbatim slices of the shipped KB — so this is the shape a ddinter
		// deployment reaches, not a curated-only one. The chart names the ophthalmic row; the record is
		// rendered from the unqualified one and must say so.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 60, null, "Dexamethasone (ophthalmic)", "Phenytoin");
		String question = "Is it safe to give her dexamethasone?";

		// The premise, through production accessors: the two rows really are ONE substance, and the row
		// the record renders is NOT the row the chart names. Without this the case could pass on a
		// fixture where they coincide and the clause would be true but vacuous.
		List<DrugReference> rows = service.getAll();
		DrugReference unqualified = DrugReferenceTestSupport.row(rows, "Dexamethasone");
		DrugReference ophthalmic = DrugReferenceTestSupport.row(rows, "Dexamethasone (ophthalmic)");
		assertEquals(unqualified.substanceGroupKey(), ophthalmic.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");

		PatientChart chart = inject(service, context, question);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Dexamethasone");
		assertNotNull(record, "precondition: a record must be injected for the unqualified row, was: "
				+ DrugReferenceTestSupport.referenceTexts(chart));

		// startsWith, not contains: WHERE the clause sits is itself the decision. It qualifies the whole
		// record, so it goes in front of the content it qualifies — the same thing issue #208 item 2
		// measured for the contraindication reading, where a model reading forward needs the qualifier
		// before the list. A `contains` assertion is satisfied by a clause appended after the
		// interactions, which is the one place it cannot do its job; that variant was applied by mutation
		// and the whole suite stayed GREEN until this line said startsWith.
		assertTrue(record.startsWith("Drug reference — Dexamethasone (ATC A01AC02, C05AA09, D07AB19, "
				+ "D07XB05, D10AA03, H02AB02, R01AD03, S01BA01, S01CB01, S02BA06, S03BA01). "
				+ ATTRIBUTION_LEAD + " Dexamethasone, not for Dexamethasone (ophthalmic) "
				+ "— the row this patient's record names, filed separately for the same substance."),
				"the record must say which row it describes, and say it BEFORE what it qualifies, was: "
						+ record);

		// The other half of the claim: the chip names the row the chart records, and now the record
		// names both — so the response no longer calls one substance two things without saying so.
		List<SafetyWarning> chips =
				DrugReferenceTestSupport.validator(service).validate("", question, context);
		assertTrue(DrugReferenceTestSupport.has(chips, SafetyWarning.TYPE_INTERACTION,
				"Dexamethasone (ophthalmic)"),
				"precondition: the chip must name the charted row, or there is nothing to reconcile, "
						+ "was: " + chips);
	}

	@Test
	public void theRecordAttributesTheCeilingTheChipDidNotUse() throws IOException {
		// #259, verbatim: the record renders the 3000 row while the dose chip warns at the charted row's
		// 2000, and the record's number is the CITABLE one. This case is the CLAUSE's half of that — the
		// record names the row its 3000 belongs to. Naming the row settles whose number it is; it does not
		// put the chip's 2000 in the evidence, and that half is
		// ReferenceRecordSubstanceCeilingsTest.theRecordStatesTheCeilingTheChipQuotes, whose section this
		// record now also carries. The expectation below is the whole record either way, so both halves
		// fail here if either moves.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Amoxicillin (suspension)");
		String question = "What dose of amoxicillin?";

		// The premise: the two rows publish DIFFERENT ceilings, so the two surfaces really do carry two
		// numbers. On drug-reference-substance-dosing-rows.json they publish the same 3000 and this case
		// would assert nothing.
		List<DrugReference> rows = service.getAll();
		assertEquals(3000.0, DrugReferenceTestSupport.row(rows, "Amoxicillin").bandForAge(30)
				.getMaxDailyDoseMg(), 0.0, "precondition: the unqualified row publishes 3000");
		assertEquals(2000.0, DrugReferenceTestSupport.row(rows, "Amoxicillin (suspension)").bandForAge(30)
				.getMaxDailyDoseMg(), 0.0, "precondition: and the charted row publishes 2000");

		// The WHOLE record, which is the strictest form available and the one that says the clause
		// precedes the number it qualifies rather than merely appearing somewhere near it. Nothing else
		// is rendered for this entry, so an exact equality costs nothing in brittleness here and buys
		// the placement — a `contains` pair passed with the clause appended after the dosing sentence.
		String record = DrugReferenceTestSupport
				.referenceTextNaming(inject(service, context, question), "Amoxicillin");
		assertEquals("Drug reference — Amoxicillin (ATC J01CA04). " + ATTRIBUTION_LEAD
				+ " Amoxicillin, not for Amoxicillin (suspension) — the row this patient's record names, "
				+ "filed separately for the same substance. Dosing for ages 0-120: 15-30 mg/kg per dose, "
				+ "maximum 3000 mg/day. Also published for other rows of this substance: Amoxicillin "
				+ "(suspension) 15-30 mg/kg per dose, maximum 2000 mg/day (ages 0-120).",
				record, "the record keeps its own row's ceiling and says whose it is, in that order");

		// The chip's stricter number, which is the one the clinician is warned on — asserted so the case
		// fails if the two ever stop being two numbers.
		assertEquals("The stated Amoxicillin (suspension) dose ~2500 mg/day exceeds the 2000 mg/day "
				+ "maximum for ages 0-120",
				DrugReferenceTestSupport.overdoseDetail(DrugReferenceTestSupport.validator(service)
						.validate("Give amoxicillin 1250 mg twice daily.", question, context),
						"Amoxicillin (suspension)"),
				"precondition: the chip warns on the charted row's own 2000");
	}

	@Test
	public void aSubstanceThisResponseNamesByTheRenderedRowSaysNothingFurther() throws IOException {
		// The control that bounds the cost. With nothing in the chart naming a row, every row ties at
		// NAME_NO_MATCH and the response names the substance by the very row the record renders — so
		// there is nothing to attribute and the wording must not move at all. Without this a fix could
		// append the clause unconditionally and read "for Dexamethasone, not for Dexamethasone".
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.ctx(60, null, null, null, null, null),
						"Is it safe to give her dexamethasone?"), "Dexamethasone");

		assertNotNull(record, "precondition: the record must still be injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"a record this response names by its own row says nothing further, was: " + record);
	}

	@Test
	public void aSingleRowSubstanceSaysNothingFurther() throws IOException {
		// The other bound: a substance the dataset files as ONE row can never be named by another, so no
		// clause however the chart names it. This is every substance of every BUNDLED curated dataset.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Cefadroxil"),
						"What dose of cefadroxil?"), "Cefadroxil");

		assertNotNull(record, "precondition: the record must be injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"a one-row substance has no other row to be named by, was: " + record);
	}

	@Test
	public void twoRowsPublishingOneNameAreAttributedToNobody() throws IOException {
		// The operator-editable boundary, the same one DoseCeilingAttributionTest
		// .twoRowsPublishingOneNameAreAttributedToNobodyEither guards on the chip: a curated file may file
		// two rows under one display name, and "…for Ranitidine, not for Ranitidine" is a
		// contradiction shown to a clinician rather than a provenance. The record still renders, and says
		// nothing it cannot say.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		List<DrugReference> rows = service.getAll();
		DrugReference first = DrugReferenceTestSupport.row(rows, "Ranitidine");
		DrugReference second = DrugReferenceTestSupport.row(rows, "ranitidine");
		assertEquals(first.substanceGroupKey(), second.substanceGroupKey(),
				"precondition: one substance");
		assertEquals(300.0, first.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: whose two rows publish different ceilings, so a clause would have content");
		assertEquals(150.0, second.bandForAge(30).getMaxDailyDoseMg(), 0.0, "precondition: 150 against 300");
		// THE premise, and the reason the chart names `zantac` rather than `ranitidine` below. The two
		// rows must not TIE on the recorded name: if they do, interactionSubject returns the very row
		// canonicalRow picks, chartAnchoredSubject answers null, and this case is silenced by "the chart
		// named no row in particular" — never reaching the shared label guard it exists to test. Only the
		// second row publishes `zantac`, so it strictly out-claims its sibling and the subject really is
		// a DIFFERENT object from the rendered row, whose name happens to fold to the same string.
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, second.nameMatchStrength("zantac"),
				"precondition: the charted name must name the SECOND row");
		assertEquals(DrugReference.NAME_NO_MATCH, first.nameMatchStrength("zantac"),
				"precondition: and must not reach the first, or the two tie and the fold decides");

		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "zantac"),
						"What dose of ranitidine?"), "Ranitidine");

		assertNotNull(record, "precondition: the record must still be injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"two rows of one name are attributed to nobody, was: " + record);
	}

	@Test
	public void noChipMoves() throws IOException {
		// The constraint this change is bounded by: issue #206 settled the chips' side, so this may touch
		// the record and nothing else. Asserted as the WHOLE chip set of the arrangement that moves the
		// record most — a count alone would not catch a reworded sentence.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 60, null, "Dexamethasone (ophthalmic)", "Phenytoin");
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give her dexamethasone?", context);

		assertEquals(Arrays.asList("Dexamethasone (ophthalmic) interacts with active order phenytoin — "
				+ "Moderate. Phenytoin and other hydantoins may induce the CYP450 3A4 hepatic metabolism "
				+ "of corticosteroids and increase their clearance and decrease their half-lives, "
				+ "possibly reducing their therapeutic efficacy."),
				DrugReferenceTestSupport.details(chips),
				"the chips are #206's and this change may not move them, was: " + chips);
	}

	@Test
	public void theClauseCostsTheRecordItsOwnLengthAndNoRecordItDoesNotQualify() throws IOException {
		// Issue #229 — nothing bounds or observes the injected slice, so what this adds to the prompt is
		// asserted rather than assumed. The clause is the ONLY difference between the attributed record
		// and the same record with nothing to attribute, and it lands on no other record in the chart.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		String question = "Is it safe to give her dexamethasone?";
		String unattributed = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.ctx(60, null, null, null, null, null), question),
				"Dexamethasone");
		String attributed = DrugReferenceTestSupport.referenceTextNaming(inject(service,
				DrugReferenceTestSupport.contextNaming(service, 60, null, "Dexamethasone (ophthalmic)"),
				question), "Dexamethasone");

		assertNotNull(unattributed, "precondition: both arrangements inject a record");
		assertNotNull(attributed, "precondition: both arrangements inject a record");
		// The delta expressed as the clause itself rather than as a number: a magic constant would have
		// to be re-derived on every wording change and says nothing about WHAT was added, while this
		// fails if anything else in the record moved with it.
		String clause = " " + ATTRIBUTION_LEAD + " Dexamethasone, not for Dexamethasone "
				+ "(ophthalmic) — the row this patient's record names, filed separately for the same "
				+ "substance.";
		assertEquals(unattributed.length() + clause.length(), attributed.length(),
				"the clause is the whole cost, and it is one bounded sentence per record — attributed:\n"
						+ attributed + "\nunattributed:\n" + unattributed);
		// The COST MODEL rather than a ceiling: the clause is the two row names plus a fixed frame, so
		// what a deployment pays is bounded by names it already carries. A bare "< N" would have to be
		// re-derived on every wording change and would say nothing about how the cost scales — which is
		// the question issue #229 is open about, nothing bounding or observing the injected slice.
		assertEquals(121, clause.length() - "Dexamethasone".length()
				- "Dexamethasone (ophthalmic)".length(),
				"the clause is one sentence of fixed size around the two names it contrasts, was: "
						+ clause.length() + " chars for " + clause);
	}

	@Test
	public void aRecordForASubstanceTheChartDoesNotMentionIsUnchangedBesideOneThatIs() throws IOException {
		// The clause is decided PER SUBSTANCE, not once for the chart: a question can inject a record for
		// a drug the chart names a row of AND one it says nothing about, and marking the second would be
		// a claim about a patient whose record does not mention it. Sirolimus is the second substance the
		// route-variant slice files as two rows, and nothing here prescribes it.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		PatientChart chart = inject(service,
				DrugReferenceTestSupport.contextNaming(service, 60, null, "Dexamethasone (ophthalmic)"),
				"Is it safe to give her dexamethasone or sirolimus?");

		String dexamethasone = DrugReferenceTestSupport.referenceTextNaming(chart, "Dexamethasone");
		String sirolimus = DrugReferenceTestSupport.referenceTextNaming(chart, "Sirolimus");
		assertNotNull(dexamethasone, "precondition: both records must be injected, was: "
				+ DrugReferenceTestSupport.referenceTexts(chart));
		assertNotNull(sirolimus, "precondition: both records must be injected, was: "
				+ DrugReferenceTestSupport.referenceTexts(chart));

		assertTrue(dexamethasone.contains(ATTRIBUTION_LEAD + " Dexamethasone, not for Dexamethasone "
				+ "(ophthalmic)"), "the charted substance is attributed, was: " + dexamethasone);
		assertFalse(sirolimus.contains(ATTRIBUTION_LEAD),
				"and the one the chart says nothing about is not, was: " + sirolimus);
	}

	@Test
	public void theClauseNamesTheRowsTheRecordDoesAndNotTheirChipLabels() throws IOException {
		// Found by mutation, not by design: {@link DrugReference#displayLabel} is the synonym-augmented
		// CHIP label and its javadoc says it is "never used in prompt text — record rendering keeps
		// getName()", which DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText pins
		// for the REST of this record. That test cannot reach this clause — its aspirin entry is a
		// one-row substance, so nothing is ever attributed — so the leak would have shipped: every other
		// case in this file uses rows whose two vocabularies coincide.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		List<DrugReference> rows = service.getAll();
		DrugReference unqualified = DrugReferenceTestSupport.row(rows, "Acetylsalicylic acid");
		DrugReference coated = DrugReferenceTestSupport.row(rows, "Acetylsalicylic acid (enteric-coated)");

		// The premise, and the only thing that makes this case able to fail: the two vocabularies must
		// genuinely diverge on BOTH rows. displayLabel returns the bare name whenever the generic name
		// is contained in it either way, so a fixture whose genericName were "acetylsalicylic acid"
		// would render identically under both readings and assert nothing.
		assertEquals("Acetylsalicylic acid (aspirin)", unqualified.displayLabel(),
				"precondition: the rendered row's chip label must differ from its name");
		assertEquals("Acetylsalicylic acid (enteric-coated) (aspirin)", coated.displayLabel(),
				"precondition: and so must the charted row's");
		assertEquals(unqualified.substanceGroupKey(), coated.substanceGroupKey(),
				"precondition: one substance, or there is nothing to attribute");

		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0,
						"Acetylsalicylic acid (enteric-coated)"), "What dose of acetylsalicylic acid?"),
				"Acetylsalicylic acid");

		assertNotNull(record, "precondition: a record must be injected for the unqualified row");
		assertTrue(record.contains(ATTRIBUTION_LEAD + " Acetylsalicylic acid, not for "
				+ "Acetylsalicylic acid (enteric-coated) — the row this patient's record names, filed "
				+ "separately for the same substance."),
				"the clause names the rows as the record's own header names them, was: " + record);
		assertFalse(record.contains("(aspirin)"),
				"and the synonym-augmented chip label never enters prompt text, was: " + record);
	}

	@Test
	public void theSubjectIsChosenAmongEveryRowThePassResolvedNotOnlyTheInjectedOnes() throws IOException {
		// The clause must not depend on the INJECTION gates. matchingEntries decides what reaches the
		// prompt through injectFromQuery/injectFromOrders and the relevance rule; what a substance is
		// CALLED is a different question, and answering it over the injected rows alone loses the charted
		// row in exactly the case this feature exists for — the record then falls silent under a
		// non-default configuration, which is #259 reachable by config, the thing rowAttribution's own
		// javadoc argues must not happen.
		//
		// The arrangement makes the two sets genuinely different: the nasal row does not publish the bare
		// alias, so the question injects the unqualified row ALONE, while the patient's order resolves the
		// nasal row into the pass. Neither row carries ATC codes, so the relevance gate cannot inject the
		// nasal row and silently make the sets equal again.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		String charted = "Mupirocin (nasal)";
		String question = "What dose of mupirocin?";

		assertEquals("[Mupirocin]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery(question)).toString(),
				"precondition: the question must resolve ONLY the unqualified row, or the injected set "
						+ "already contains the charted one and the widening is untested");
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 30, 70.0, charted);
		assertTrue(DrugReferenceTestSupport.names(service.findForActiveOrders(context)).contains(charted),
				"precondition: while the patient's own order resolves the charted row, so the two sets "
						+ "really do differ — was: "
						+ DrugReferenceTestSupport.names(service.findForActiveOrders(context)));

		String record = DrugReferenceTestSupport
				.referenceTextNaming(inject(service, context, question), "Mupirocin");

		assertNotNull(record, "precondition: the unqualified row's record must be injected");
		assertTrue(record.contains(ATTRIBUTION_LEAD + " Mupirocin, not for Mupirocin (nasal) — the row "
				+ "this patient's record names, filed separately for the same substance."),
				"the subject comes from every row the pass resolved, not just the injected ones, was: "
						+ record);
	}

	@Test
	public void aSubjectTheFoldMovedRatherThanTheChartIsAttributedToNobody() throws IOException {
		// The sentence says "the row this patient's record names", so it may only be printed where a
		// recorded name actually out-claimed the other rows. `interactionSubject` composes two rankings
		// and always answers, so its answer alone cannot say which step decided — and once the subject
		// group is widened to every row the pass resolved (which it must be, or the clause becomes
		// dependent on injectFromOrders), the FOLD alone can move the subject off the rendered row.
		//
		// The arrangement: `clobex` is published only by the topical row, so the question injects that
		// row alone; the order display `Clobetasol 0.05%` ties BOTH rows on nameMatchStrength and
		// resolves both, so the group is wider than the injected set and canonicalRow moves the subject
		// to the unqualified row. The chart named no row in particular, so the record must say nothing.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		List<DrugReference> rows = service.getAll();
		DrugReference unqualified = DrugReferenceTestSupport.row(rows, "Clobetasol");
		DrugReference topical = DrugReferenceTestSupport.row(rows, "Clobetasol (topical)");

		// Every premise through a production accessor, because all four have to hold for this case to
		// exercise the guard rather than pass for some other reason.
		assertEquals(unqualified.substanceGroupKey(), topical.substanceGroupKey(),
				"precondition: one substance");
		assertEquals("[Clobetasol (topical)]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery("What dose of clobex?")).toString(),
				"precondition: the question must resolve ONLY the topical row, so the record is rendered "
						+ "from it and the unqualified row can only arrive through the order leg");
		assertEquals(unqualified.nameMatchStrength("Clobetasol 0.05%"),
				topical.nameMatchStrength("Clobetasol 0.05%"),
				"precondition: and the charted name must TIE them, so nothing the chart says picks a row "
						+ "and only canonicalRow can move the subject");
		assertSame(unqualified, DrugReference.canonicalRow(Arrays.asList(topical, unqualified)),
				"precondition: which it does — the fold prefers the route-unspecified row");
		// The premise its sibling asserts and this one used to omit: the order must actually bring the
		// UNQUALIFIED row into the pass, because that is the only thing making the subject group wider
		// than the injected set. Without it the group is one row, interactionSubject takes its short cut,
		// and the assertFalse below passes with the guard never reached.
		assertTrue(DrugReferenceTestSupport.names(service.findForActiveOrders(
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Clobetasol 0.05%"))).contains("Clobetasol"),
				"precondition: the order must resolve the unqualified row into the pass");

		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Clobetasol 0.05%"),
						"What dose of clobex?"), "Clobetasol (topical)");

		assertNotNull(record, "precondition: the topical row's record must be injected, so the case is "
				+ "about what that record SAYS rather than about whether it exists");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"a subject the FOLD moved is not a row the chart named, and the record may not say it "
						+ "was, was: " + record);
		// What this case does NOT claim, so it is not read as blessing the arrangement. Silence here is
		// the conservative half of a KNOWN RESIDUE, not agreement between the surfaces: the record is
		// headed "Clobetasol (topical)" while a chip about this substance would name it "Clobetasol",
		// because the chip layer folds the same union this method's guard folds. That is issue #237's
		// shape surviving in the one case chartAnchoredSubject deliberately stays quiet about, and
		// closing it needs a differently worded second clause — see chartAnchoredSubject's javadoc,
		// which records why that wording is not guessed here.
		assertEquals("Clobetasol (topical)", DrugReference.canonicalRow(Collections.singletonList(topical))
				.getName(), "the record really is headed by the qualified row, which is what makes the "
						+ "residue above a residue rather than a hypothetical");
	}

	@Test
	public void rowsGroupedOnlyByASharedIdAreAttributedToNobody() throws IOException {
		// The operator-editable boundary on the OTHER key matchingEntries groups by. A source publishing
		// no substanceName falls back to getId(), the curated parser drops an entry only for a blank id
		// or name, and no DrugReferenceValidity rule reports a duplicate id — so two rows can share one
		// group without the file ever having said they are one substance. The clause says "filed
		// separately for the same substance", which would then be a claim the data does not support.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-duplicate-id-rows.json"));
		List<DrugReference> rows = service.getAll();
		DrugReference unqualified = DrugReferenceTestSupport.row(rows, "Trimethoprim");
		DrugReference paediatric = DrugReferenceTestSupport.row(rows, "Trimethoprim (paediatric)");

		// The premises that make this able to fail: the two rows really are grouped (one id), the group
		// is NOT a declared substance, and their names differ — so a missing guard would have two names
		// to contrast and would print a clause.
		assertEquals(unqualified.getId(), paediatric.getId(), "precondition: the two rows share an id");
		assertEquals(null, unqualified.substanceKey(),
				"precondition: and neither declares a substance, so the group is the id fallback");
		assertEquals(null, paediatric.substanceKey(), "precondition: neither declares a substance");

		// And the premise that was MISSING, without which this case could not fail: the group must really
		// hold two rows. The ranked accessors return one row per NAME, so while `trimethoprim` was the
		// second row's only alias both legs resolved the FIRST row — the group was a single row,
		// interactionSubject took its rows.size() == 1 short cut, chartAnchoredSubject answered null, and
		// the case was silenced by "the chart named no row in particular" without the substanceKey() guard
		// it is named for ever being reached (found by mutating that guard away for issue #259's section:
		// nothing reddened). Driving the order by the second row's OWN alias fixes it; the two accessors
		// below are the ones matchingEntries unions.
		PatientClinicalContext context =
				DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "tmp-paediatric");
		String question = "What dose of trimethoprim?";
		assertEquals("[Trimethoprim]",
				DrugReferenceTestSupport.names(service.findImpliedByQuery(question)).toString(),
				"precondition: the question leg must resolve the unqualified row");
		assertEquals("[Trimethoprim (paediatric)]",
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)).toString(),
				"precondition: and the order leg the OTHER row, or the guard is never reached");

		String record = DrugReferenceTestSupport
				.referenceTextNaming(inject(service, context, question), "Trimethoprim");

		assertNotNull(record, "precondition: a record must still be injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"rows the file never called one substance are attributed to nobody, was: " + record);
	}

	@Test
	public void noBundledCuratedDatasetCanReachTheClauseAtAll() throws IOException {
		// The bound on what this changes in the field for a CURATED deployment, asserted over the shipped
		// seed rather than argued: no bundled dataset sets substanceName on a curated entry, so every
		// substance there is one row and no chart can name it by another. The ddinter half is the
		// opposite and is why the first case above exists — that source DOES set substanceName, so the
		// shipped 19 MB KB reaches this immediately.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReference ibuprofen = service.lookupByToken("ibuprofen");
		assertNotNull(ibuprofen, "precondition: the shipped seed must carry ibuprofen");
		assertEquals(null, ibuprofen.getSubstanceName(),
				"precondition: the seed publishes no substance name, which is WHY every substance in it "
						+ "is one row");

		String record = DrugReferenceTestSupport.referenceTextNaming(
				inject(service, DrugReferenceTestSupport.contextNaming(service, 30, 70.0, "Ibuprofen"),
						"What dose of ibuprofen?"), "Ibuprofen");
		assertNotNull(record, "precondition: the record must be injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"the shipped curated wording is unchanged, was: " + record);
	}

	@Test
	public void aNullContextStatesNoAttributionAtAll() throws IOException {
		// "Nothing known about the patient" is not "the chart names the canonical row" — a record that
		// cannot see the chart must not report which row it names, exactly as render() already refuses to
		// report a contraindication absence. Reached through the real injector, whose own null-context
		// path is pinned by OrderDrivenInjectionResolutionTest.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		String record = DrugReferenceTestSupport.referenceTextNaming(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(), null,
						"Is it safe to give her dexamethasone?"),
				"Dexamethasone");

		assertNotNull(record, "precondition: the question's own drug is still injected");
		assertFalse(record.contains(ATTRIBUTION_LEAD),
				"a record that cannot see the chart claims nothing about it, was: " + record);
	}

	@Test
	public void theAttributedRecordStillCitesTheRowItRenders() throws IOException {
		// What this change deliberately does NOT do. #237 lists "inject the charted row's own record" as
		// an option; it was declined on measurement (the route-unspecified row carries the breadth — 74
		// of 129 families lose the patient's own partner the other way), so the resourceId a citation
		// resolves must still be the rendered row's. A fix that swapped the row would pass every wording
		// case above and silently move every citation.
		DrugReferenceService service =
				DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		// The resource ids, extracted BEFORE the assertion so the failure message carries them:
		// RecordMapping defines no toString, so a message built from the mappings prints identity
		// hashes and a failure says nothing about which row was cited instead.
		List<String> cited = DrugReferenceTestSupport
				.injectedReferences(inject(service, DrugReferenceTestSupport.contextNaming(service, 60, null,
						"Dexamethasone (ophthalmic)"), "Is it safe to give her dexamethasone?"))
				.stream().map(m -> m.getResourceUuid()).collect(java.util.stream.Collectors.toList());

		assertEquals(Collections.singletonList("DDInter513"), cited,
				"one record per substance, citing the row it renders — DDInter515 is the ophthalmic row, "
						+ "i.e. the record followed the chart instead of saying which row it is");
	}
}
