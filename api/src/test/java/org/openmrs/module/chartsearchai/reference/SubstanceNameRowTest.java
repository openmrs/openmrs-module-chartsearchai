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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issue #250 — which row of a substance {@link DrugReference#canonicalRow} elects to represent it.
 * Before this issue the fold had two rungs: prefer the row that {@link DrugReference#namesNoRoute()},
 * and otherwise keep the earliest row seen. Nothing preferred the row whose display name IS the name
 * the data files the family under, so where two rows of one substance both name no route the tie fell
 * to dataset order — and on the shipped KB the row that wins that race is
 * {@code Fluoroestradiol f-18}, a diagnostic PET tracer at index 1282, against {@code Estradiol} at
 * 1927.
 *
 * <p>Reproduced live on a 3.7.1 standalone with the stock shipped dataset: <em>"Is it safe to give her
 * estradiol?"</em> was answered <em>"No — Fluoroestradiol f-18 should not be given: it interacts with
 * active order methylprednisolone …"</em>, over five interaction chips every one of which was subjected
 * on the tracer, with the word {@code estradiol} appearing nowhere except inside
 * {@code Fluoroestradiol}. So the wrong row does not stay in the chip: {@code renderFinding} carries
 * the chip detail into the prompt verbatim and the model answers from it.
 *
 * <p>The MERGE is not what this fixes. {@code Fluoroestradiol f-18} carries no {@code drugbank_id}, so
 * the DDInter parser files it under the estradiol family's resolved substance id — a data defect
 * {@code DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE} reports and deliberately
 * leaves as loaded, because splitting the rows would invent the fact the data is missing. Given the
 * merge, this is the other half: which of the merged rows speaks for the substance.
 *
 * <p>Two dataset sources, both read by the real {@link DdiDrugReferenceSource}, and which a case takes
 * follows from what it asserts rather than from a list here — an earlier version of this sentence
 * enumerated them and went stale twice in eight commits. A case asserting a property of the SHIPPED
 * dataset reads the shipped knowledge base ({@code DrugReferenceTestSupport.shippedEntries}); a case
 * asserting particular chip or record TEXT reads a verbatim slice, so a refresh that leaves one family
 * alone cannot rewrite what it expects. Every scenario runs a real production entry point with real
 * question strings and GP reads on their no-context defaults.
 */
public class SubstanceNameRowTest {
	
	/** The three estradiol rows the shipped KB merges into one substance, plus the corticosteroid
	 *  partner the live reproduction used. See the fixture's own note. */
	private static final String ESTRADIOL_FIXTURE = "chartsearchai-test/ddi-substance-name-row.json";
	
	/** The question from the live reproduction, verbatim. */
	private static final String ESTRADIOL_QUESTION = "Is it safe to give her estradiol?";
	
	private static final String TRACER = "Fluoroestradiol f-18";
	
	/** {@link #TRACER}'s stem, lower-cased — what a guard has to match on, since the shipped KB also
	 *  writes the tracer {@code fluoroestradiol F 18} inside its own mechanism prose. */
	private static final String TRACER_STEM = "fluoroestradiol";
	
	/** The ATC code the shipped silver-nitrate family publishes — the code whose class-chip entry the
	 *  stem weakening would hand to an ophthalmic presentation. */
	private static final String SILVER_CODE = "D08AL01";
	
	/** The presentation row a stem comparison would elect to speak for the silver-nitrate substance. */
	private static final String OPHTHALMIC_SILVER = "Silver nitrate (ophthalmic)";
	
	@Test
	public void theFixtureReallyTiesTheFoldOnTheTracer() throws Exception {
		// The premise, through the production predicates: the tracer and Estradiol are ONE substance to
		// this module and neither names a route, so the pre-#250 fold had nothing to separate them and
		// kept the tracer for being listed first. Without this the case could pass on a slice where the
		// fold happened to pick the right row anyway.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(ESTRADIOL_FIXTURE);
		DrugReference tracer = DrugReferenceTestSupport.row(entries, TRACER);
		DrugReference estradiol = DrugReferenceTestSupport.row(entries, "Estradiol");
		
		assertEquals(tracer.substanceGroupKey(), estradiol.substanceGroupKey(),
		    "precondition: the two rows must be ONE substance");
		assertTrue(tracer.namesNoRoute() && estradiol.namesNoRoute(),
		    "precondition: neither row may name a route, or the first rung decides it");
		assertSame(tracer, entries.get(0),
		    "precondition: and the tracer must be listed first, as in the shipped file");
		assertEquals(Arrays.asList("Fluoroestradiol f-18", "Estradiol", "Estradiol (topical)"),
		    DrugReferenceTestSupport.names(
		        DrugReferenceTestSupport.serviceWith(entries).findImpliedByQuery(ESTRADIOL_QUESTION)),
		    "precondition: and the question must put every row of the family in play, tracer first");
	}
	
	@Test
	public void theChipNamesTheRowTheDataNamesTheSubstanceAfter() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Dexamethasone Injection vial 8mg"), null, null, null);
		
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
		        .validate("", ESTRADIOL_QUESTION, context);
		
		assertEquals(1, warnings.size(), "one substance, one partner, one chip, was: " + warnings);
		assertEquals("Estradiol", warnings.get(0).getDrug(),
		    "the chip must name the row the data names the substance after, was: " + warnings);
		assertTrue(warnings.get(0).getDetail()
		        .startsWith("Estradiol interacts with active order dexamethasone — Moderate."),
		    "and its detail must lead with that same name, was: " + warnings.get(0).getDetail());
		// On the STEM and case-insensitively, because the KB spells the tracer three ways and a guard
		// written on the display name alone is blind to the other two: this substance's own rated rules
		// carry mechanism prose reading "the radioactive diagnostic agent fluoroestradiol F 18 …", which
		// `contains("Fluoroestradiol f-18")` does not see. That prose can still reach a chip about
		// Estradiol — bestRulePerPartner pools every row of the substance — so this guard is what makes
		// that residue observable in-suite rather than accidentally green. See ADR 43's trade-offs.
		assertFalse(warnings.get(0).getDetail().toLowerCase(Locale.ROOT).contains(TRACER_STEM),
		    "and must not attribute an oestrogen's mechanism to a diagnostic tracer, nor carry the "
		            + "tracer's own prose, was: " + warnings.get(0).getDetail());
	}
	
	@Test
	public void routeQualificationStillOutranksNamingTheSubstance() throws Exception {
		// The rung ORDER, over the shipped dataset — and the guard for the one decision in this change
		// that nothing else pins. Issue #250 reads as "add a third rung", and a reader who places the new
		// rung ABOVE namesNoRoute() gets a fourth renamed family, passes every other test in this suite,
		// and silently breaks this invariant: measured, moving it up reddens nothing but this case.
		//
		// What it costs is stated where the fold is defined. The family that discriminates the two
		// placements is the influenza A/Vietnam antigen, whose elected row carries a display name with a
		// dropped leading "I" — so this case is also what keeps that typo UNFIXED here, deliberately, as
		// issue #196's upstream handoff rather than something to repair by re-ranking rows.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		Map<Object, List<DrugReference>> families = new LinkedHashMap<Object, List<DrugReference>>();
		for (DrugReference row : all) {
			Object substance = row.substanceGroupKey();
			List<DrugReference> rows = families.get(substance);
			if (rows == null) {
				rows = new ArrayList<DrugReference>();
				families.put(substance, rows);
			}
			rows.add(row);
		}
		
		int multiRow = 0;
		int discriminating = 0;
		for (List<DrugReference> rows : families.values()) {
			if (rows.size() < 2) {
				continue;
			}
			multiRow++;
			boolean anyUnqualified = false;
			boolean unqualifiedSelfNamer = false;
			boolean qualifiedSelfNamer = false;
			for (DrugReference row : rows) {
				if (row.namesNoRoute()) {
					anyUnqualified = true;
					if (row.namesItsSubstance()) {
						unqualifiedSelfNamer = true;
					}
				} else if (row.namesItsSubstance()) {
					qualifiedSelfNamer = true;
				}
			}
			if (!anyUnqualified) {
				continue;
			}
			// The DISCRIMINATING shape, and it is narrower than "holds a qualified self-namer": with the
			// rungs reordered, a family that ALSO holds an unqualified self-namer still elects that row,
			// which names no route, so the invariant survives there and such a family proves nothing. Only
			// a family whose sole self-naming row carries a qualifier can witness the reorder.
			if (qualifiedSelfNamer && !unqualifiedSelfNamer) {
				discriminating++;
			}
			assertTrue(DrugReference.canonicalRow(rows).namesNoRoute(),
			    "a family with an unqualified row must elect one, was "
			            + DrugReference.canonicalRow(rows).getName() + " among "
			            + DrugReferenceTestSupport.names(rows));
		}
		assertTrue(multiRow > 1, "precondition: the shipped dataset must file some substance as several "
		        + "rows, or this asserts nothing — was " + multiRow);
		assertTrue(discriminating > 0,
		    "precondition: and at least one such family's ONLY self-naming row must carry a qualifier, or "
		            + "no family here can witness the reorder and the case is vacuous");
	}

	@Test
	public void theSubstanceNameIsMatchedAgainstTheWholeDisplayNameAndNotItsStem() throws Exception {
		// The other half of the predicate's definition, and until this case nothing pinned it: mutate
		// namesItsSubstance() to compare DrugReference.displayStem(name) instead of the whole name and the
		// entire api suite stays green, while two shipped families change their elected row. Both changes
		// are wrong in the way this module keeps having to undo.
		//
		// The tick-borne family is the one the predicate's javadoc exists for. Its substance name carries
		// a parenthetical of its own, so BOTH rows reduce to the same stem and a stem comparison separates
		// neither — the fold falls back to dataset order and re-elects the paediatric row, silently giving
		// back one of the three renames this issue delivers.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		List<DrugReference> tickBorne = new ArrayList<DrugReference>();
		for (DrugReference row : all) {
			if (row.getName().startsWith("Tick-borne encephalitis vaccine")) {
				tickBorne.add(row);
			}
		}
		
		assertEquals(2, tickBorne.size(), "precondition: the family must be the two shipped rows, was: "
		        + DrugReferenceTestSupport.names(tickBorne));
		assertEquals(DrugReference.displayStem(tickBorne.get(0).getName()),
		    DrugReference.displayStem(tickBorne.get(1).getName()),
		    "precondition: and both rows must reduce to ONE stem, or a stem comparison would separate "
		            + "them and this case would not discriminate");
		assertEquals("Tick-borne encephalitis vaccine (whole virus, inactivated)",
		    DrugReference.canonicalRow(tickBorne).getName(),
		    "the row the data files the family under must be elected, not the paediatric one");
		
	}

	@Test
	public void noRouteQualifiedPresentationIsElectedToSpeakForItsSubstance() throws Exception {
		// The second cost of comparing stems instead of whole names, and it is on the other side of the
		// same-substance gate — the ATC-code fold, which is the one canonicalRow site whose row set is not
		// one substance. No row of the shipped silver-nitrate family names its substance by its whole name,
		// so today the fold decides nothing there and the earliest row keeps the role. Compare stems and
		// `Silver nitrate (ophthalmic)` self-names, taking both the family and the code with it — an
		// ophthalmic presentation elected to speak for a substance, which is exactly the shape issue #174
		// site 1 removed when a systemic cyclosporine order was named "Cyclosporine (ophthalmic)" in a chip
		// about tacrolimus.
		//
		// Its own case rather than more assertions beside the tick-borne one: that case fails on this same
		// mutation and would stop before reaching these, so folded together only one of the two halves
		// would ever be observed reddening.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		List<DrugReference> silverNitrate = new ArrayList<DrugReference>();
		DrugReference byCode = null;
		for (DrugReference row : all) {
			if ("silver nitrate".equals(DrugReference.normalizeName(row.getSubstanceName()))) {
				silverNitrate.add(row);
			}
			if (row.normalizedAtcCodes().contains(SILVER_CODE)) {
				byCode = DrugReference.canonicalRow(byCode, row);
			}
		}
		
		assertTrue(silverNitrate.size() > 1, "precondition: the silver nitrate family must be several rows,"
		        + " was: " + DrugReferenceTestSupport.names(silverNitrate));
		assertNotNull(byCode, "precondition: the shipped dataset must cover " + SILVER_CODE);
		// The row the weakening would elect must EXIST, or both assertions below compare against a string
		// no row carries and the case is vacuously green. That was measured on this case BEFORE this line
		// existed: with the stem mutation applied and the constant pointed at a name nothing bears, it
		// passed while its two siblings failed. With this line it fails here instead, loudly and naming
		// the rows it did find, which is the whole point — re-run that pair of mutations if you want to
		// see it rather than trusting the sentence.
		assertTrue(DrugReferenceTestSupport.names(silverNitrate).contains(OPHTHALMIC_SILVER),
		    "precondition: the family must still carry " + OPHTHALMIC_SILVER + ", or nothing here can "
		            + "witness the stem weakening — was: " + DrugReferenceTestSupport.names(silverNitrate));
		
		assertNotEquals(OPHTHALMIC_SILVER, DrugReference.canonicalRow(silverNitrate).getName(),
		    "no route-qualified presentation may be elected to speak for the substance, was: "
		            + DrugReferenceTestSupport.names(silverNitrate));
		assertNotEquals(OPHTHALMIC_SILVER, byCode.getName(),
		    "nor may it become the entry a class chip names for " + SILVER_CODE);
	}

	@Test
	public void theInjectedRecordNamesThatRowToo() throws Exception {
		// The same row, on the surface the model actually cites — asserted on the RENDERED record text
		// through the real injectRecords, not on canonicalRow as a proxy for it. A fix that moved only the
		// chip would leave the prompt carrying the tracer's title beside a chip naming Estradiol, and only
		// reading the record can see that.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Dexamethasone Injection vial 8mg"), null, null, null);
		
		List<DrugReference> group = service.findImpliedByQuery(ESTRADIOL_QUESTION);
		assertEquals(3, group.size(), "precondition: the whole family must be in play, was: " + group);
		
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
		        .injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, ESTRADIOL_QUESTION);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Estradiol");
		
		assertNotNull(record, "a drug-reference record naming Estradiol must be injected, was: "
		        + DrugReferenceTestSupport.referenceTexts(chart));
		assertNull(DrugReferenceTestSupport.referenceTextNaming(chart, TRACER),
		    "and none naming the tracer, was: " + DrugReferenceTestSupport.referenceTexts(chart));
		assertEquals("Estradiol", DrugSafetyValidator.interactionSubject(group, context).getName(),
		    "and the row the chips name the substance by must be that same row, was: " + group);
	}
	
	@Test
	public void theRecordStillSaysWhichRowItIsWhereTheFoldNowAgreesWithTheChart() throws Exception {
		// The regression this rung would have caused on a surface two removes away, found by driving the
		// real injectRecords rather than by reading the fold. DrugReferenceInjector.rowAttribution prints
		// "Published by this dataset for X, not for Y — the row this patient's record names" so the model
		// can tell a record's row from the row every chip beside it names. Its gate used to infer "the
		// chart chose the subject" by comparing interactionSubject's row against canonicalRow's — a proxy
		// that held only while the fold could NOT reach the row the chart names. This rung makes it reach
		// exactly that row, so the proxy read the agreement as "the chart chose nothing" and the clause
		// vanished from the one arrangement that needs it: the question resolves only the non-elected row,
		// so the record renders Daxibotulinumtoxina while every chip names Botulinum toxin type A.
		//
		// Measured before the fix, on the shipped KB: the clause was printed with the rung disabled and
		// absent with it enabled — a strict regression, with the whole suite green on both sides.
		// On the verbatim slice and not the shipped KB, per shippedEntries()' own rule: this asserts record
		// TEXT, so a refresh touching that family must not be able to rewrite what it expects. The slice
		// carries the same two rows in the same order, and Botulinum Toxin Type B is its rated partner.
		DrugReferenceService service = DrugReferenceTestSupport
		        .ddiFixtureService(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Botulinum toxin type A", "Botulinum Toxin Type B"), null, null,
		    null);
		String question = "Is it safe to give her daxibotulinumtoxina?";
		
		List<DrugReference> asked = service.findImpliedByQuery(question);
		assertEquals(Arrays.asList("Daxibotulinumtoxina"), DrugReferenceTestSupport.names(asked),
		    "precondition: the question must resolve ONLY the row the fold no longer elects, or the "
		            + "record and the chip would name the same row and there would be nothing to say");
		
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
		        .injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Daxibotulinumtoxina");
		
		assertNotNull(record, "precondition: the record must be rendered from the row the question named, "
		        + "was: " + DrugReferenceTestSupport.referenceTexts(chart));
		assertTrue(record.contains(DrugReferenceTestSupport.ROW_ATTRIBUTION_LEAD),
		    "a record whose row the chart claims less strongly than a sibling must say so, was: " + record);
		assertTrue(record.contains("not for Botulinum toxin type A"),
		    "and must name the row the chart does claim, was: " + record);
	}

	/** The order name whose recorded string CONTAINS another substance's row name without being it —
	 *  the shipped KB's own witness for the weakest rank of {@link DrugReference#nameMatchStrength}. */
	private static final String CONTAINING_ORDER = "Insulin human (isophane)";
	
	@Test
	public void aRowTheChartMerelyCONTAINSIsNotARowTheChartNames() throws Exception {
		// The floor on the claim the #237 attribution clause may rest on. That sentence says "the row this
		// patient's record names", and NAME_TOKEN_INSIDE_A_NAME is bare containment — one of the entry's
		// names merely occurring inside the recorded string — so resting it on that rank is the overclaim
		// issue #269 removed from the section beside it, where `opium` matched an allergen recorded as
		// `Tiotropium`. Strictly-greater alone admits it: rank 0 beats a sibling's NAME_NO_MATCH.
		//
		// The shipped KB supplies the arrangement, and what makes the suppressed sentence FALSE rather than
		// merely weak is not the pair — those two rows are one substance, which is the precondition below
		// and the only reason a record of one contrasts with the other. It is the recorded ORDER: `Insulin
		// human (isophane)` is filed under `insulin isophane`, a THIRD substance, while the row whose name
		// that string merely contains is filed under `insulin, regular, human`. So without the floor the
		// clause says this patient's chart names a row of a substance the chart does not record.
		//
		// Asserted at the gate rather than on rendered prose, deliberately and with the reason stated: the
		// end-to-end path is silent here for a SECOND reason (the injector's relevance gate), so a prose
		// assertion would pass whether or not the floor exists and would pin nothing. The inputs are not
		// hand-crafted — they are real rows from the real shipped dataset and a real recorded order name,
		// the same shape as the premise assertions elsewhere in this package.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		DrugReference named = null;
		DrugReference sibling = null;
		for (DrugReference row : all) {
			if ("Insulin human".equals(row.getName())) {
				named = row;
			}
			if ("Insulin human (regular)".equals(row.getName())) {
				sibling = row;
			}
		}
		assertNotNull(named, "precondition: the shipped dataset must carry the Insulin human row");
		assertNotNull(sibling, "precondition: and its route-qualified sibling");
		
		assertEquals(named.substanceGroupKey(), sibling.substanceGroupKey(),
		    "precondition: the two must be ONE substance, or no record of either contrasts with the other");
		assertEquals(DrugReference.NAME_TOKEN_INSIDE_A_NAME, named.nameMatchStrength(CONTAINING_ORDER),
		    "precondition: the recorded name must CONTAIN this row's name without being it");
		assertEquals(DrugReference.NAME_NO_MATCH, sibling.nameMatchStrength(CONTAINING_ORDER),
		    "precondition: while claiming the sibling not at all — so strictly-greater alone would fire");
		
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(CONTAINING_ORDER), null, null, null);
		assertFalse(DrugSafetyValidator.recordNamesMoreStrongly(named, sibling, context),
		    "a record may not say the chart NAMES a row the chart merely contains");
		
		// The control, on the same pair: a chart that really does name it clears the floor.
		PatientClinicalContext naming = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Insulin human"), null, null, null);
		assertTrue(DrugSafetyValidator.recordNamesMoreStrongly(named, sibling, naming),
		    "while a chart that names it outright still licenses the sentence");
	}

	@Test
	public void theRecordHasNothingToAttributeWhereTheChartNamesTheRowItRenders() throws Exception {
		// A consequence of the rename, on the surface that says WHICH row a record is: since the fold now
		// elects the row a chart naming the bare substance also names, the two agree and
		// DrugReferenceInjector.rowAttribution has nothing to say — where before the fold answered the
		// tracer, they disagreed, and the record carried an attribution clause for a divergence that was
		// itself the defect. The sibling case is the control: a chart naming the route-qualified row still
		// moves the subject off the rendered row, so the clause is not simply gone.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		List<DrugReference> group = service.findImpliedByQuery(ESTRADIOL_QUESTION);
		
		PatientClinicalContext onTheSubstance = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Estradiol", "Dexamethasone Injection vial 8mg"), null, null,
		    null);
		assertSame(DrugReference.canonicalRow(group),
		    DrugSafetyValidator.interactionSubject(group, onTheSubstance),
		    "a chart naming the bare substance must now agree with the fold, so the record's attribution "
		            + "clause has nothing to report");
		
		PatientClinicalContext onTheTopicalRow = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Estradiol (topical)", "Dexamethasone Injection vial 8mg"), null,
		    null, null);
		assertEquals("Estradiol (topical)",
		    DrugSafetyValidator.interactionSubject(group, onTheTopicalRow).getName(),
		    "while a chart naming a route-qualified sibling still moves the subject off it");
	}
}
