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
 * Before this issue the fold had ONE rung — prefer the row that
 * {@link DrugReference#namesNoRoute()} — and a fallback to the earliest row seen. Nothing preferred the row whose display name IS the name
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
		// written on the display name alone is blind to the other two: the tracer's own mechanism prose
		// reads "the radioactive diagnostic agent fluoroestradiol F 18 …", which
		// `contains("Fluoroestradiol f-18")` does not see.
		//
		// What it catches HERE is narrower than an earlier version of this comment claimed, and the
		// difference is worth stating rather than deleting. It refuses the tracer's name reaching this
		// detail by ANY route — the subject label, the partner label, an appended class sentence — and
		// over this slice the two assertions above already refuse the only route that exists, failing
		// first if the fold elects the tracer. What it cannot observe is bestRulePerPartner's POOLING of
		// every row of the substance, because this slice rates no rule on the tracer row at all, so there
		// is no tracer prose here to pool. That residue is real and is observed by
		// theChipNamesTheElectedRowWhereThePooledWinningRuleIsTheTracers below, over the one slice that
		// does file a rated rule on that row. See ADR 43's trade-offs.
		assertFalse(warnings.get(0).getDetail().toLowerCase(Locale.ROOT).contains(TRACER_STEM),
		    "and must not attribute an oestrogen's mechanism to a diagnostic tracer, nor carry the "
		            + "tracer's own prose, was: " + warnings.get(0).getDetail());
	}

	/** The verbatim slice that files a rated rule on the TRACER row — the estradiol slice above files
	 *  none, so it cannot pose a pooled tracer rule at all. Its own concern is
	 *  {@code DrugReferenceValidity}'s derivative-merged finding across six families
	 *  ({@code DrugReferenceValidityContextTest}), which is why the case below reads the ratings it
	 *  depends on off the real parse rather than assuming them: a change to those rows reddens it loudly,
	 *  naming what it found, instead of quietly asserting nothing. */
	private static final String MERGED_FAMILIES =
	        "chartsearchai-test/ddi-derivative-merged-into-one-substance.json";
	
	/** The active order that slice's tracer rule points at, verbatim from it — an oestrogen
	 *  agonist/antagonist, which is what the tracer's own mechanism prose is about. */
	private static final String TRACER_RULE_PARTNER = "Ospemifene";
	
	@Test
	public void theChipNamesTheElectedRowWhereThePooledWinningRuleIsTheTracers() throws Exception {
		// The residue ADR 43 records as a trade-off of this change, made observable in-suite. Two
		// assertions here and only the first is a property this module promises: the chip names the
		// ELECTED row (issue #250) even in the arrangement where the rule it renders came from a sibling
		// row — bestRulePerPartner pools every row of a substance, and severity leads its ranking, so the
		// tracer's Major rule outranks the substance row's Moderate one for the same partner. The second
		// pins the residue as it stands, so that a change to the pooling, to outranks' route or
		// note-length steps, or to this slice's ratings cannot widen or close it in silence. Closing it
		// would be an improvement, and the place to record that is ADR 43's trade-off together with this
		// assertion — not a deletion of it.
		//
		// Nothing in the suite could see this before: the arrangement needs a rated rule ON the tracer,
		// which the estradiol slice deliberately does not carry.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(MERGED_FAMILIES);
		DrugReference tracer = DrugReferenceTestSupport.row(entries, TRACER);
		DrugReference estradiol = DrugReferenceTestSupport.row(entries, "Estradiol");
		DrugReference.Interaction tracerRule = ruleFor(tracer, TRACER_RULE_PARTNER);
		DrugReference.Interaction substanceRule = ruleFor(estradiol, TRACER_RULE_PARTNER);
		
		assertEquals(tracer.substanceGroupKey(), estradiol.substanceGroupKey(),
		    "precondition: the two rows must be ONE substance in this slice too, or nothing pools");
		assertNotNull(tracerRule, "precondition: the tracer row must carry a rated rule for "
		        + TRACER_RULE_PARTNER + ", was: " + tracer.getInteractions());
		assertNotNull(substanceRule, "precondition: and the substance row must carry its own for that "
		        + "same partner, or there is no pool to choose from, was: " + estradiol.getInteractions());
		assertTrue(tracer.namesNoRoute() && estradiol.namesNoRoute(),
		    "precondition: neither row may name a route, or outranks' route step decides the pool");
		assertTrue(DrugSafetyValidator.outranksOnRule(tracerRule, substanceRule),
		    "precondition: and the tracer's rule must WIN that pool through the real ranking, or the chip "
		            + "renders the substance row's own prose and this case asserts nothing");
		assertTrue(tracerRule.getNote().toLowerCase(Locale.ROOT).contains(TRACER_STEM),
		    "precondition: the winning rule's note must name the tracer, or the residue assertion below "
		            + "cannot tell whose prose the chip carried, was: " + tracerRule.getNote());
		assertFalse(substanceRule.getNote().toLowerCase(Locale.ROOT).contains(TRACER_STEM),
		    "precondition: while the substance row's own note must not, was: " + substanceRule.getNote());
		
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(MERGED_FAMILIES);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(TRACER_RULE_PARTNER), null, null, null);
		
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
		        .validate("", ESTRADIOL_QUESTION, context);
		
		assertEquals(1, warnings.size(), "one substance, one partner, one chip, was: " + warnings);
		assertEquals("Estradiol", warnings.get(0).getDrug(),
		    "the chip must name the elected row even where the rule it renders is a sibling's, was: "
		            + warnings);
		assertTrue(warnings.get(0).getDetail().startsWith("Estradiol interacts with active order "
		        + tracerRule.getToken() + " — Major."),
		    "and its detail must lead with that same name at the winning rule's rating, was: "
		            + warnings.get(0).getDetail());
		// The residue, not a promise: the mechanism prose under that name is the tracer's own. Read the
		// detail in the failure message before changing this line.
		assertTrue(warnings.get(0).getDetail().toLowerCase(Locale.ROOT).contains(TRACER_STEM),
		    "the pooled winning rule's prose is the tracer's, which is ADR 43's recorded residue — if this "
		            + "no longer holds the residue has moved or closed, and that belongs in ADR 43 beside "
		            + "this assertion, was: " + warnings.get(0).getDetail());
	}
	
	/** @return the rule {@code row} carries for the active order named {@code partner}, matched by the
	 *          production predicate the chip arm itself gates on
	 *          ({@link PatientClinicalContext#hasActiveDrug}), so a premise assertion cannot admit a rule
	 *          {@code validate} would skip; null where the row files none. */
	private static DrugReference.Interaction ruleFor(DrugReference row, String partner) {
		PatientClinicalContext onlyThatOrder = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(partner), null, null, null);
		for (DrugReference.Interaction rule : row.getInteractions()) {
			if (onlyThatOrder.hasActiveDrug(rule.getToken(), rule.getAtc())) {
				return rule;
			}
		}
		return null;
	}
	
	@Test
	public void aFamilyWithAnUnqualifiedRowElectsOneAndNoOtherRowSpeaksForIt() throws Exception {
		// The KB-wide invariant every consumer of the fold rests on: where a substance has a row that
		// names no route, that row — not a presentation of it — speaks for the substance.
		// DrugReferenceInjector.matchingEntries widens its candidate set on exactly this ("canonicalRow
		// never moves AWAY from namesNoRoute()").
		//
		// TWO assertions, deliberately, because since issue #250 the first one alone can be satisfied by
		// construction. namesItsSubstance() now implies namesNoRoute(), so a family electing a row whose
		// display name carries a trailing parenthetical passes the first assertion whenever that
		// parenthetical is the name the data files the family under — which is what the A/Vietnam and
		// tick-borne families do. The second states the same invariant on RAW SYNTAX, so it cannot be
		// satisfied by the shape of the predicate it is about: an elected row may carry a trailing
		// parenthetical only where that parenthetical is its own substanceName. Weaken
		// namesItsSubstance() to compare display STEMS and the second reddens where the first does not.
		//
		// It no longer guards the rung ORDER, and that is a consequence of issue #250 rather than a gap.
		// It used to: a reader who placed the second rung ABOVE namesNoRoute() got a fourth renamed
		// family and broke this invariant. With namesNoRoute() corrected, rung two above rung one can
		// never elect a row rung one calls qualified, because namesItsSubstance() implies namesNoRoute()
		// — so the reorder is a no-op on any dataset and there is nothing left to pin. The precondition
		// that used to refuse vacuity here ("at least one family's ONLY self-naming row must carry a
		// qualifier") became unsatisfiable for the same reason, and is replaced by one that is not.
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
		int electedOnItsSubstanceName = 0;
		for (List<DrugReference> rows : families.values()) {
			if (rows.size() < 2) {
				continue;
			}
			multiRow++;
			DrugReference elected = DrugReference.canonicalRow(rows);
			boolean anyUnqualified = false;
			boolean anyPlain = false;
			for (DrugReference row : rows) {
				if (row.namesNoRoute()) {
					anyUnqualified = true;
				}
				if (carriesNoTrailingParenthetical(row)) {
					anyPlain = true;
				}
			}
			if (anyUnqualified) {
				assertTrue(elected.namesNoRoute(), "a family with an unqualified row must elect one, was "
				        + elected.getName() + " among " + DrugReferenceTestSupport.names(rows));
			}
			if (!anyPlain) {
				continue;
			}
			// On raw syntax throughout — displayStem and getSubstanceName, never namesNoRoute() or
			// namesItsSubstance() — so a weakening of either cannot satisfy this by definition.
			if (!carriesNoTrailingParenthetical(elected)) {
				electedOnItsSubstanceName++;
				assertEquals(DrugReference.normalizeName(elected.getSubstanceName()),
				    DrugReference.normalizeName(elected.getName()),
				    "a family holding a row with no trailing parenthetical may elect one that has a "
				            + "trailing parenthetical only where that IS the name the data files the "
				            + "family under, was " + elected.getName() + " among "
				            + DrugReferenceTestSupport.names(rows));
			}
		}
		assertTrue(multiRow > 1, "precondition: the shipped dataset must file some substance as several "
		        + "rows, or this asserts nothing — was " + multiRow);
		assertTrue(electedOnItsSubstanceName > 0,
		    "precondition: and at least one such family must elect a row whose trailing parenthetical is "
		            + "its own substance name, or the second assertion never runs and this case is "
		            + "vacuous on the half issue #250 added");
	}

	/** Whether {@code row}'s display name carries no TRAILING parenthetical — the raw syntax
	 *  {@link DrugReference#namesNoRoute()} reads, spelled out so a case can assert against the
	 *  predicate rather than in its own terms. Anchored at the end like the predicate's own pattern, so
	 *  a parenthetical mid-name ({@code … a/vietnam/1194/2004 (h5n1) antigen}) does not count. */
	private static boolean carriesNoTrailingParenthetical(DrugReference row) {
		String normalized = DrugReference.normalizeName(row.getName());
		return normalized != null && normalized.equals(DrugReference.displayStem(row.getName()));
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
		
		// Not "no qualified row may be elected" — every row of this family is qualified and one of them IS
		// elected, which is the 10-family case canonicalRow's own @return documents. What must not happen
		// is the OPHTHALMIC presentation taking the role: the fold decides nothing here today, and this is
		// what stops it starting to decide for that row.
		assertNotEquals(OPHTHALMIC_SILVER, DrugReference.canonicalRow(silverNitrate).getName(),
		    "the ophthalmic presentation must not be elected to speak for the substance, was elected from "
		            + DrugReferenceTestSupport.names(silverNitrate));
		assertNotEquals(OPHTHALMIC_SILVER, byCode.getName(),
		    "nor may it become the entry a class chip names for " + SILVER_CODE + ", was: "
		            + byCode.getName());
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

	/** The curated fixture whose two Clobetasol rows publish DIFFERENT alias sets, which is what the
	 *  floor's LEVEL needs. No family of the shipped KB poses that arrangement — rows of one substance
	 *  normally share their rxnorm and CIEL aliases, so a claim that is an alias-but-not-a-display-name
	 *  lands on every row at once and no row out-claims another; the measurement is on
	 *  {@code recordNamesMoreStrongly} itself, made through the real load rather than restated here.
	 *  This fixture's own concern is issue #259's two-ceiling record, so the two cases below read the
	 *  claims they depend on off the real parse: a change to those rows reddens them loudly, naming what
	 *  it found, rather than quietly asserting nothing. */
	private static final String DIFFERING_ALIASES =
	        "chartsearchai-test/drug-reference-substance-dosing-ceilings.json";
	
	/** The brand alias only that fixture's route-qualified Clobetasol row publishes — a recorded order
	 *  name that IS one row's other name and matches its sibling not at all, which is the shape that tells
	 *  {@link DrugReference#NAME_IS_ANOTHER_NAME} apart from
	 *  {@link DrugReference#NAME_IS_THE_DISPLAY_NAME} as the floor. That fixture's {@code zantac} pair has
	 *  the same shape and is deliberately not used here: its two rows share ONE display name, so the
	 *  printed clause is refused by {@code worthNamingApart} whatever the floor says, and only rows with
	 *  distinct display names can show the floor deciding the prose. */
	private static final String ALIAS_ONLY_ORDER = "Clobex";
	
	@Test
	public void aRowTheChartNamesByAnAliasIsARowTheChartNames() throws Exception {
		// The floor's LEVEL, which nothing pinned: raised from NAME_IS_ANOTHER_NAME to
		// NAME_IS_THE_DISPLAY_NAME the whole api suite stayed green, while the #237 clause silently stops
		// being printed wherever the chart records a row's rxnorm or CIEL name rather than its display
		// name — which per interactionSubject's own javadoc is the common shape, since an order
		// contributes its CONCEPT's name and rows of one substance share their aliases. An alias IS a
		// name, so that chart does name the row and the sentence is true.
		//
		// Asked at the gate here and on the printed record in the case below, split for the reason the
		// strictness pair below is split: folded together the gate assertion fails first and JUnit never
		// reaches the prose, so a change that keeps the gate honest while breaking the plumbing to the
		// sentence would be masked.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(DIFFERING_ALIASES);
		DrugReference named = DrugReferenceTestSupport.row(entries, "Clobetasol (topical)");
		DrugReference sibling = DrugReferenceTestSupport.row(entries, "Clobetasol");
		
		assertEquals(named.substanceGroupKey(), sibling.substanceGroupKey(),
		    "precondition: the two must be ONE substance, or no record of either contrasts with the other");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, named.nameMatchStrength(ALIAS_ONLY_ORDER),
		    "precondition: the recorded name must be one of this row's OTHER names — above the floor by "
		            + "exactly one rank, which is what makes the level observable at all");
		assertEquals(DrugReference.NAME_NO_MATCH, sibling.nameMatchStrength(ALIAS_ONLY_ORDER),
		    "precondition: while claiming the sibling not at all, so the comparison is strict either way");
		
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(ALIAS_ONLY_ORDER), null, null, null);
		assertTrue(DrugSafetyValidator.recordNamesMoreStrongly(named, sibling, context),
		    "a chart recording one of a row's other names NAMES that row, so the sentence is licensed");
		assertFalse(DrugSafetyValidator.recordNamesMoreStrongly(sibling, named, context),
		    "while the sibling that name does not reach is not the row the record may claim");
	}
	
	@Test
	public void theRecordSaysWhichRowItIsWhereTheChartNamesThatRowByAnAlias() throws Exception {
		// What the level costs when it is wrong, on the surface that prints — the same arrangement driven
		// through the real injectRecords. The question resolves both rows, so the record is rendered from
		// the fold's row while the chart names its sibling by an alias: raise the floor and this record
		// falls silent about which row it is, with the chips beside it still naming the other one, which is
		// exactly the divergence issue #237's clause exists to reconcile.
		DrugReferenceService service = DrugReferenceTestSupport
		        .serviceWith(DrugReferenceTestSupport.fixtureEntries(DIFFERING_ALIASES));
		String question = "Is it safe to give her clobetasol?";
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, 60.0,
		    DrugReferenceTestSupport.set(ALIAS_ONLY_ORDER), null, null, null);
		
		assertEquals(Arrays.asList("Clobetasol", "Clobetasol (topical)"),
		    DrugReferenceTestSupport.names(service.findImpliedByQuery(question)),
		    "precondition: the question must resolve BOTH rows, or the fold and the chart name one row "
		            + "and there is nothing to reconcile");
		assertSame(DrugReferenceTestSupport.row(service.getAll(), "Clobetasol (topical)"),
		    DrugSafetyValidator.interactionSubject(service.findImpliedByQuery(question), context),
		    "precondition: and the chart's alias must move the subject onto the row the record does NOT "
		            + "render, which is the claim the floor's level admits");
		
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
		        .injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Clobetasol");
		
		assertNotNull(record, "precondition: the record must be rendered from the fold's row, was: "
		        + DrugReferenceTestSupport.referenceTexts(chart));
		assertTrue(record.contains(DrugReferenceTestSupport.ROW_ATTRIBUTION_LEAD
		        + " Clobetasol, not for Clobetasol (topical) — the row this patient's record names"),
		    "a record whose sibling the chart names by an alias must say so, was: " + record);
	}

	@Test
	public void theRecordHasNothingToAttributeWhereTheChartNamesTheRowItRenders() throws Exception {
		// A consequence of the rename, on the surface that says WHICH row a record is: since the fold now
		// elects the row a chart naming the bare substance also names, the record renders the very row
		// every chip names and DrugReferenceInjector.rowAttribution has nothing to contrast — where before
		// the fold answered the tracer, the two disagreed and the record carried an attribution clause for
		// a divergence that was itself the defect. The sibling case is the control: a chart naming the
		// route-qualified row still moves the subject off the rendered row, so the clause is not simply
		// gone.
		//
		// The silence is READ OFF THE RECORD and not inferred from the fold agreeing with the chart, which
		// is the inference this clause stopped resting on: since issue #250 it is decided by
		// recordNamesMoreStrongly(subject, rendered), so "the fold agrees with the chart" no longer
		// implies it, and re-deriving the retired proxy in a test comment is the step that produced the
		// regression this change had to fix. What holds here is narrower and is exactly what the
		// assertions state: subject and rendered are the SAME row, so there is nothing to contrast.
		//
		// Two independent refusals keep that out of the prose and either is enough, so the printed
		// assertion reddens only when BOTH are broken. Measured: bypassing the gate (chartAnchoredSubject
		// returning its subject unconditionally) leaves this green, because worthNamingApart will not
		// print "for X, not for X"; mutating worthNamingApart alone leaves it green, because the gate
		// refuses a row against itself anyway — its `row == than` fast path, and behind that the strict
		// comparison, which is why that fast path's own comment says no mutation of IT changes an answer;
		// do both and the record reads "Published by this dataset for Estradiol, not for Estradiol" and
		// this reddens. So what this case cannot see is the gate's floor, its LEVEL or its strictness:
		// those are aRowTheChartMerelyCONTAINSIsNotARowTheChartNames,
		// aRowTheChartNamesByAnAliasIsARowTheChartNames and the two strictness cases below.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		List<DrugReference> group = service.findImpliedByQuery(ESTRADIOL_QUESTION);
		
		PatientClinicalContext onTheSubstance = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Estradiol", "Dexamethasone Injection vial 8mg"), null, null,
		    null);
		assertSame(DrugReference.canonicalRow(group),
		    DrugSafetyValidator.interactionSubject(group, onTheSubstance),
		    "a chart naming the bare substance must now agree with the fold, so the row the record renders "
		            + "IS the row the chips name");
		
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
		        .injectRecords(DrugReferenceTestSupport.oneRecordChart(), onTheSubstance,
		            ESTRADIOL_QUESTION);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Estradiol");
		
		assertNotNull(record, "precondition: a record must be rendered for the substance, was: "
		        + DrugReferenceTestSupport.referenceTexts(chart));
		assertFalse(record.contains(DrugReferenceTestSupport.ROW_ATTRIBUTION_LEAD),
		    "and it must attribute its row to nobody, since it renders the row the chips name, was: "
		            + record);
		
		PatientClinicalContext onTheTopicalRow = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Estradiol (topical)", "Dexamethasone Injection vial 8mg"), null,
		    null, null);
		assertEquals("Estradiol (topical)",
		    DrugSafetyValidator.interactionSubject(group, onTheTopicalRow).getName(),
		    "while a chart naming a route-qualified sibling still moves the subject off it");
	}

	/** The CIEL name BOTH botulinum rows of the slice publish — a recorded order name that is neither
	 *  row's display name, so it claims the two EQUALLY. The shipped KB writes it on both rows of that
	 *  family too; the slice carries it verbatim, which is what makes the two cases below reachable
	 *  without the 19 MB file. */
	private static final String EQUALLY_CLAIMING_ORDER = "Botulinum type A toxin-haemagglutinin complex";

	/** The question those two cases ask, verbatim — the one that resolves ONLY the row the fold does not
	 *  elect, which is what leaves the attribution clause something it could say. */
	private static final String DAXI_QUESTION = "Is it safe to give her daxibotulinumtoxina?";

	@Test
	public void aRowTheChartClaimsNoMoreStronglyThanItsSiblingIsNotARowTheChartPreferred() throws Exception {
		// The STRICTNESS of the comparison the floor case above bounds from below, and the OTHER half of
		// the same return expression: nothing pinned it, so relaxing `claim >` to `claim >=` left the whole
		// api suite green. The #237 sentence says the row it names is "the row this patient's record names"
		// — in preference to the row the record was published FOR — so it needs the chart to claim one row
		// MORE strongly than the other. Relaxed, the predicate answers true in BOTH directions for one pair,
		// which states no preference at all, and the clause then asserts a choice the chart did not make.
		//
		// Reachable rather than hypothetical: rows of one substance normally SHARE their rxnorm and CIEL
		// aliases, so a recorded order name that is no row's own display name lands on every row of the
		// family at once. Both preconditions below are read off the real parse of a verbatim slice.
		//
		// Asserted BOTH ways round, because one direction alone cannot see the mutation for what it is: a
		// single false is equally satisfied by the floor, by the row == than fast path and by a genuine
		// strict inequality, so it distinguishes "neither row is preferred" from "this row is not the
		// preferred one" only when its mirror is asserted beside it.
		List<DrugReference> entries = DrugReferenceTestSupport
		        .ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference daxi = DrugReferenceTestSupport.row(entries, "Daxibotulinumtoxina");
		DrugReference botoxA = DrugReferenceTestSupport.row(entries, "Botulinum toxin type A");

		assertEquals(daxi.substanceGroupKey(), botoxA.substanceGroupKey(),
		    "precondition: the two must be ONE substance, or no record of either contrasts with the other");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, daxi.nameMatchStrength(EQUALLY_CLAIMING_ORDER),
		    "precondition: the recorded name must claim this row as one of its other names");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, botoxA.nameMatchStrength(EQUALLY_CLAIMING_ORDER),
		    "precondition: and claim the sibling at the SAME rank — above the floor, so strictness is the "
		            + "only thing left that can decide this arrangement");

		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(EQUALLY_CLAIMING_ORDER), null, null, null);
		assertFalse(DrugSafetyValidator.recordNamesMoreStrongly(botoxA, daxi, context),
		    "a record may not say the chart PREFERRED a row it claims no more strongly than its sibling");
		assertFalse(DrugSafetyValidator.recordNamesMoreStrongly(daxi, botoxA, context),
		    "and the same must hold read the other way round, or the predicate has stopped meaning "
		            + "\"more strongly\"");
	}

	@Test
	public void aRecordAttributesItsRowToNobodyWhereTheChartClaimsBothRowsAlike() throws Exception {
		// What the case above costs when it is wrong, on the surface that prints — the same mutation driven
		// through the real injectRecords rather than asked of the gate. Separate case and not a tail on
		// that one, deliberately: folded together, the gate assertion fails first and JUnit never reaches
		// this, so a later change that keeps the gate honest while breaking the plumbing between it and the
		// prose would be masked. Each was measured to redden on `claim >=` on its own.
		DrugReferenceService service = DrugReferenceTestSupport
		        .ddiFixtureService(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		List<DrugReference> entries = DrugReferenceTestSupport
		        .ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference daxi = DrugReferenceTestSupport.row(entries, "Daxibotulinumtoxina");
		DrugReference botoxA = DrugReferenceTestSupport.row(entries, "Botulinum toxin type A");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set(EQUALLY_CLAIMING_ORDER), null, null, null);

		assertEquals(Arrays.asList("Daxibotulinumtoxina"),
		    DrugReferenceTestSupport.names(service.findImpliedByQuery(DAXI_QUESTION)),
		    "precondition: the question must resolve ONLY the row the fold does not elect, or the record "
		            + "and the fold name the same row and the clause is silent for a second reason");
		assertSame(botoxA, DrugSafetyValidator.interactionSubject(Arrays.asList(daxi, botoxA), context),
		    "precondition: and the fold must name the substance by the OTHER row, or this record has "
		            + "nothing it could wrongly attribute and the assertion below is vacuous");

		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
		        .injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, DAXI_QUESTION);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Daxibotulinumtoxina");

		assertNotNull(record, "precondition: the record must be rendered from the row the question named, "
		        + "was: " + DrugReferenceTestSupport.referenceTexts(chart));
		assertFalse(record.contains(DrugReferenceTestSupport.ROW_ATTRIBUTION_LEAD),
		    "and it must attribute its row to nobody, since the chart claims both rows alike, was: "
		            + record);
	}

	/** The verbatim slice for issue #250's remaining family — the two influenza A/Vietnam rows and the
	 *  one partner whose RENDERED note differs between them. See the fixture's own note. */
	private static final String TYPO_ROW_FIXTURE = "chartsearchai-test/ddi-typo-row-names-its-substance.json";

	/** The row the shipped KB spells with a dropped leading "I" — a #196 upstream data defect this does
	 *  NOT repair, and must not be read as repairing: what moves is which row is ELECTED. */
	private static final String TYPO_ROW = "Nfluenza a virus a/vietnam/1194/2004 (h5n1) antigen";

	/** Its correctly-spelled sibling, whose display name IS the name the data files the family under. */
	private static final String SUBSTANCE_ROW =
	        "Influenza A virus A/Vietnam/1194/2004 (H5N1) antigen (formaldehyde inactivated)";

	/** The clause only the correctly-spelled row's note carries — what makes the note difference
	 *  assertable as TEXT rather than as a length. */
	private static final String FULLER_NOTE_CLAUSE =
	        "Vaccination may be less effective during and for up to three months after discontinuation";

	@Test
	public void theRowTheDataNamesTheFamilyAfterIsElectedEvenWhenItsOwnNameCarriesAParenthetical()
	        throws Exception {
		// Issue #250's fourth family, and the half PR #311 left. `namesNoRoute()` is a SYNTACTIC proxy —
		// "carries no trailing parenthesised qualifier" — and it misreads a row whose trailing
		// parenthetical is not a qualifier at all but part of the name the data files that row's family
		// under. The A/Vietnam family is where that costs an election: the typo row's own parenthetical
		// `(h5n1)` sits MID-name and TRAILING_QUALIFIER is end-anchored, so the proxy calls the typo row
		// unqualified and the correctly-spelled row — whose whole display name IS its `substanceName` —
		// qualified, and rung one hands the family to the typo.
		//
		// Over the shipped dataset and not a slice, per shippedEntries()' own rule: this asserts which row
		// a real family elects, not any rendered text.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		List<DrugReference> family = new ArrayList<DrugReference>();
		String familyName = DrugReference.normalizeName(SUBSTANCE_ROW);
		for (DrugReference row : all) {
			if (familyName.equals(DrugReference.normalizeName(row.getSubstanceName()))) {
				family.add(row);
			}
		}

		assertEquals(2, family.size(), "precondition: the family must be the two shipped rows, was: "
		        + DrugReferenceTestSupport.names(family));
		DrugReference typo = DrugReferenceTestSupport.row(family, TYPO_ROW);
		DrugReference substance = DrugReferenceTestSupport.row(family, SUBSTANCE_ROW);
		assertEquals(typo.substanceGroupKey(), substance.substanceGroupKey(),
		    "precondition: and they must be ONE substance to this module");
		assertSame(typo, family.get(0),
		    "precondition: the typo row must be listed first, as in the shipped file, or dataset order "
		            + "would elect the right row for the wrong reason");
		assertTrue(substance.namesItsSubstance(),
		    "precondition: the correctly-spelled row's display name must BE the name the data files the "
		            + "family under, or there is nothing for this to prefer");
		assertNotEquals(DrugReference.normalizeName(substance.getName()),
		    DrugReference.displayStem(substance.getName()),
		    "precondition: and that display name must carry a TRAILING parenthetical, or the proxy never "
		            + "misread it and this case witnesses nothing");
		assertEquals(DrugReference.normalizeName(typo.getName()), DrugReference.displayStem(typo.getName()),
		    "precondition: while the typo row's must carry none, which is what wins it rung one today");

		assertEquals(SUBSTANCE_ROW, DrugReference.canonicalRow(family).getName(),
		    "the row the data files the family under must be elected, was elected from "
		            + DrugReferenceTestSupport.names(family));
	}

	@Test
	public void theChipNamesThatRowAndCarriesItsOwnNote() throws Exception {
		// The same correction on the surface a clinician reads, through the real validate, and it moves
		// two things at once because ONE predicate decides both: canonicalRow picks the name the chip is
		// subjected on, and DrugSafetyValidator.outranks — whose route step reads that same predicate to
		// decide whose MECHANISM PROSE the chip renders where two rows of a substance rate a partner
		// alike — stops preferring the typo row. Ozanimod is the one partner of this family whose two
		// rows carry different note text, which is why the fixture uses it: without the second half the
		// chip would print the correctly-spelled name over the typo row's shorter note.
		//
		// On a verbatim slice and not the shipped KB, per shippedEntries()' own rule, because this asserts
		// chip TEXT.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TYPO_ROW_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Ozanimod"), null, null, null);
		String question = "Is it safe to give her " + SUBSTANCE_ROW + "?";

		List<DrugReference> asked = service.findImpliedByQuery(question);
		assertEquals(Arrays.asList(TYPO_ROW, SUBSTANCE_ROW), DrugReferenceTestSupport.names(asked),
		    "precondition: the question must put BOTH rows in play, typo row first, or the fold has "
		            + "nothing to choose between");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
		        .validate("", question, context);

		assertEquals(1, warnings.size(), "one substance, one partner, one chip, was: " + warnings);
		assertEquals(SUBSTANCE_ROW, warnings.get(0).getDrug(),
		    "the chip must name the row the data files the family under, was: " + warnings);
		assertTrue(warnings.get(0).getDetail().startsWith(SUBSTANCE_ROW
		        + " interacts with active order ozanimod — Moderate."),
		    "and its detail must lead with that same name, was: " + warnings.get(0).getDetail());
		assertTrue(warnings.get(0).getDetail().contains(FULLER_NOTE_CLAUSE),
		    "and must carry that row's OWN note rather than the typo row's shorter one, was: "
		            + warnings.get(0).getDetail());
	}

	@Test
	public void aRowWhoseTrailingParentheticalIsItsOwnSubstanceNameCarriesNoQualifier() throws Exception {
		// What the correction claims about the shipped DATA, stated on RAW strings so that no clause here
		// can be satisfied by the shape of the predicate it is about. `displayStem` and `getSubstanceName`
		// only; `namesItsSubstance()` appears nowhere below.
		//
		// Three clauses, three different things they can fail on. The class must be NON-EMPTY, or the
		// correction has no witness in this dataset and everything resting on it is vacuous. Every member
		// must answer namesNoRoute(), which is the correction itself and reddens if it is removed. And
		// none may publish an ATC code — which is not decoration: DrugSafetyValidator.entryForAtcCode is
		// the one canonicalRow site whose row set is NOT one substance, so it is the site where a row's
		// answer about its OWN family could reach a fold across families. On this KB it cannot, because
		// these rows appear in no ATC row set at all. A refresh that gives one of them a code reddens here
		// and tells whoever reads it to measure that fold before trusting it, rather than leaving it to be
		// found in a chip.
		List<DrugReference> all = DrugReferenceTestSupport.shippedEntries();
		List<DrugReference> selfNamingAndParenthesised = new ArrayList<DrugReference>();
		for (DrugReference row : all) {
			String normalized = DrugReference.normalizeName(row.getName());
			String substance = DrugReference.normalizeName(row.getSubstanceName());
			if (normalized != null && normalized.equals(substance)
			        && !normalized.equals(DrugReference.displayStem(row.getName()))) {
				selfNamingAndParenthesised.add(row);
			}
		}

		assertFalse(selfNamingAndParenthesised.isEmpty(),
		    "precondition: the shipped dataset must file some row under a substance name that itself "
		            + "carries a trailing parenthetical, or nothing here witnesses the correction");
		for (DrugReference row : selfNamingAndParenthesised) {
			assertTrue(row.namesNoRoute(), "a row whose trailing parenthetical is the name the data files "
			        + "its family under carries no qualifier, was read as qualified: " + row.getName());
			assertTrue(row.normalizedAtcCodes().isEmpty(),
			    "and no such row may publish an ATC code without the cross-family fold in "
			            + "DrugSafetyValidator.entryForAtcCode being measured again — " + row.getName()
			            + " now publishes " + row.normalizedAtcCodes());
		}
	}


	/** The verbatim slice for the SECOND consumer of the corrected predicate — the question-PAIR arm's
	 *  use of {@code DrugSafetyValidator.outranks}. See the fixture's own note. */
	private static final String QUESTION_PAIR_FIXTURE = "chartsearchai-test/ddi-question-pair-subject.json";

	private static final String TICK_BORNE = "Tick-borne encephalitis vaccine (whole virus, inactivated)";

	/** The Moderna row that carries a trailing parenthetical — a PRESENTATION, and the rival whose
	 *  sentence moves. */
	private static final String MODERNA_PRESENTATION = "Moderna COVID-19 Vaccine (6m-5y)";

	/** The Moderna row that carries none, whose sentence does NOT move — what makes the case
	 *  discriminating rather than merely green. */
	private static final String MODERNA_SUBSTANCE = "Moderna covid-19 vaccine";

	@Test
	public void aQuestionPairSentenceIsOwnedByTheSubstanceRowAndNotByAPresentationOfTheRival()
	        throws Exception {
		// The other production consumer of the predicate issue #250 corrected. `outranks`' middle step
		// prefers the row that names no route, so that a chip does not render prose describing a
		// presentation nobody named — and in the question-PAIR arm the row it picks also decides which of
		// the two drugs OWNS the sentence, because `fromFirst` chooses `subject` and `partner`. Before the
		// correction the tick-borne substance row was read as route-qualified, so a paediatric COVID-19
		// vaccine presentation owned the sentence against it.
		//
		// Two pairs in one question and only one of them moves, which is what makes this discriminate:
		// the unqualified `Moderna covid-19 vaccine` ties on that step both before and after, so its
		// sentence stays where dataset order put it.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(QUESTION_PAIR_FIXTURE);
		String question = "Can " + TICK_BORNE + " be given with " + MODERNA_PRESENTATION + "?";
		// No active orders: this arm fires only for a pair the ACTIVE-ORDER arm has not already covered.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null, null, null);

		List<DrugReference> asked = service.findImpliedByQuery(question);
		assertTrue(DrugReferenceTestSupport.names(asked).contains(MODERNA_SUBSTANCE),
		    "precondition: the unqualified Moderna row must be in play too, or the case has only the "
		            + "pair that moves and cannot show that the other one does not — was: " + asked);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
		        .validate("", question, context);

		List<String> subjects = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			subjects.add(warning.getDrug());
		}
		assertEquals(Arrays.asList(MODERNA_SUBSTANCE, TICK_BORNE), subjects,
		    "the pair whose rival is a PRESENTATION must be owned by the row the data names the "
		            + "substance after, while the pair whose rival names no route is untouched — was: "
		            + warnings);
		assertTrue(warnings.get(1).getDetail()
		        .startsWith(TICK_BORNE + " interacts with " + MODERNA_PRESENTATION),
		    "and that sentence must name the presentation as the PARTNER, was: "
		            + warnings.get(1).getDetail());
	}
}
