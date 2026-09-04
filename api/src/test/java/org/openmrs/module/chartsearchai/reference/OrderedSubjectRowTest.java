/*
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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #194 — the interaction chip's subject was {@link DrugReference#canonicalRow}'s pick, and where
 * the rows of a family TIE on that fold's rungs it can only keep the dataset's first. So a patient
 * ordered one presentation was told about another: live-measured on the 3.7.1 standalone, a
 * {@code Botulinum toxin type A} order was subjected on
 * {@code Daxibotulinumtoxina (botulinum toxin type a)}, because at the time the fold had one rung and
 * both rows answered it.
 *
 * <p>That family no longer ties. Issue #250 gave the fold a second rung — the row the data files the
 * family under — which is {@code Botulinum toxin type A}, so the fold now reaches this file's answer
 * unaided and the botulinum cases below can no longer fail if the chart-anchoring step is removed. The
 * trap moved to the COVID pair in the same fixture, whose rows tie on both rungs; see
 * {@link #theOrderNamedRowIsNamedWhereTheFoldCannotReachIt}.
 *
 * <p>Not the same defect as issue #176/#192, which was {@code lookupByToken}'s resolution of a
 * recorded ALLERGEN name, and not #188's collapse of the injector's notes. This one arrives through
 * the choice of representative row for an ORDER, and the constraint it has to respect is the one
 * issue #187 settled and #192 re-measured: naming the row the CHART records is what makes a finding
 * truthful, so {@code canonicalRow} may not simply be applied to a recorded name (it renames a
 * charted {@code Ketorolac (ophthalmic)} allergy). The rule is therefore "prefer the row the
 * patient's own record names most strongly, and fall back to the canonical row when nothing does" —
 * the ranking issue #192 introduced ({@link DrugReference#nameMatchStrength}), applied to the order
 * side.
 *
 * <p>The fallback half is already covered elsewhere and deliberately not re-asserted here: every
 * order name in {@code ScreeningSubjectLabelTest} and in
 * {@code InteractionRouteVariantTest.aSubstanceWithNoRouteUnspecifiedRowStillRaisesOneChip} carries a
 * strength or a form suffix, so no row's own name IS the recorded name and those cases exercise the
 * {@code canonicalRow} fallback. Still unchanged by issue #250's second rung, and checked rather than
 * assumed: that rung needs a row whose display name IS its own {@code substanceName}, and the families
 * those two cases turn on have none reachable — the oxymetazoline rows are all route-qualified, and the
 * chloroprocaine pair is decided by the FIRST rung before the second is consulted.
 *
 * <p>Every scenario runs the REAL production path: a verbatim DDInter KB slice parsed by the real
 * {@link DdiDrugReferenceSource}, the real {@code validate} entry point, real question strings, GP
 * reads on their no-context defaults.
 */
public class OrderedSubjectRowTest {

	/** The canonical screening question, verbatim from issue #113 — it must name no drug. */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** The order name the demo dictionary's concept 4259 carries, verbatim — no strength suffix, which
	 *  is what {@link PatientClinicalContextBuilder} attaches for an order with no drug row. */
	private static final String BOTULINUM_A_ORDER = "Botulinum toxin type A";

	private static PatientClinicalContext bothSerotypes() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(BOTULINUM_A_ORDER, "Botulinum Toxin Type B"),
				null, null, null);
	}

	private static DrugReferenceService service() throws Exception {
		return DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
	}

	@Test
	public void theFixtureReallyPutsTheTrapRowFirstAndTiesTheCanonicalFold() throws Exception {
		// The premise, through the production predicates: both rows name no route, so canonicalRow's first
		// rung cannot separate them — while the order's own name IS the second row's display name and
		// only an alias of the first. Which row the fold then keeps changed with issue #250; the
		// assertion below says what it keeps now and why that costs the two cases after it their teeth.
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference daxi = DrugReferenceTestSupport.row(entries, "Daxibotulinumtoxina");
		DrugReference botoxA = DrugReferenceTestSupport.row(entries, BOTULINUM_A_ORDER);

		assertEquals(daxi.substanceGroupKey(), botoxA.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertTrue(daxi.namesNoRoute() && botoxA.namesNoRoute(),
				"precondition: neither row may name a route, or canonicalRow decides it on merit");
		// Issue #250 gave the fold a second rung — among rows of ONE substance agreeing on namesNoRoute,
		// the row whose display name IS the name the data files the family under — and Botulinum toxin
		// type A is that row. So for THIS family the fold now reaches the same row the chart does, and
		// the assertion that stood here (that it keeps the trap row) was a fact about the pre-#250 rungs.
		// What that costs is the two cases below: each still asserts something true, but neither can now
		// fail if the chart-anchoring step is removed, because the fold alone would answer the same. The
		// family that still supplies the trap is the COVID pair in this same fixture — neither of its rows
		// names its own substance, so the second rung cannot reach it either — and it is asserted in
		// theOrderNamedRowIsNamedWhereTheFoldCannotReachIt below.
		assertSame(botoxA, DrugReference.canonicalRow(Arrays.asList(daxi, botoxA)),
				"precondition: since issue #250 the fold reaches the charted row here on its own");
		assertEquals(DrugReference.NAME_IS_THE_DISPLAY_NAME, botoxA.nameMatchStrength(BOTULINUM_A_ORDER),
				"precondition: and the charted order name IS the other row's display name");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, daxi.nameMatchStrength(BOTULINUM_A_ORDER),
				"precondition: which the trap row claims only as one of its other names");
	}

	@Test
	public void theScreenNamesTheRowThePatientsOwnOrderNames() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = bothSerotypes();

		assertTrue(service.findByQuery(SCREENING_QUESTION).isEmpty(),
				"precondition: the screening question must name no drug, or the screen never runs");
		assertEquals(Arrays.asList("Daxibotulinumtoxina", BOTULINUM_A_ORDER, "Botulinum Toxin Type B"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: the row the chart does NOT name must resolve first");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, context);

		assertEquals(1, warnings.size(), "one pair, one chip, was: " + warnings);
		assertEquals(BOTULINUM_A_ORDER, warnings.get(0).getDrug(),
				"the chip must name the row the patient's own order names, was: " + warnings);
		assertTrue(warnings.get(0).getDetail()
				.startsWith(BOTULINUM_A_ORDER + " interacts with active order botulinum toxin type b — "),
				"and its detail must lead with that same name, was: " + warnings.get(0).getDetail());
		assertFalse(warnings.get(0).getDetail().contains("Daxibotulinumtoxina"),
				"and must not assert a presentation the chart does not record, was: "
						+ warnings.get(0).getDetail());
	}

	@Test
	public void theDrugInPlayArmNamesThatRowToo() throws Exception {
		// The same choice on the arm the question drives, so one build cannot call the substance two
		// things. Here the question resolves BOTH rows (they share the alias the question uses), so the
		// subject is a free choice between them. Since issue #250 the fold would settle it the same way
		// unaided — see the precondition case — so what this asserts is the agreement rather than the
		// chart's power to override; the case that still proves the override is
		// theOrderNamedRowIsNamedWhereTheFoldCannotReachIt.
		DrugReferenceService service = service();
		PatientClinicalContext context = bothSerotypes();

		assertEquals(Arrays.asList("Daxibotulinumtoxina", BOTULINUM_A_ORDER),
				DrugReferenceTestSupport.names(
						service.findByQuery("Is it safe to give her botulinum toxin type A?")),
				"precondition: the question must resolve both rows, trap row first");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give her botulinum toxin type A?", context);

		assertEquals(1, warnings.size(), "one pair, one chip, was: " + warnings);
		assertEquals(BOTULINUM_A_ORDER, warnings.get(0).getDrug(),
				"the drug-in-play chip must name the charted row as well, was: " + warnings);
		assertFalse(warnings.get(0).getDetail().contains("Daxibotulinumtoxina"),
				"was: " + warnings.get(0).getDetail());
	}

	/**
	 * A curated file whose two {@code Amoxicillin} rows are one substance but only one of which the bare
	 * word resolves, so the rows the TEXT resolves and the rows the CHART resolves genuinely differ —
	 * and which carries age bands as well as an interaction rule, so one response can carry a dose
	 * warning and an interaction chip about the same substance. See the fixture's own description.
	 */
	private static final String CHARTED_ROW_FIXTURE =
			"chartsearchai-test/drug-reference-charted-substance-row.json";

	@Test
	public void everyArmOfOneResponseNamesOneSubstanceOneWay() throws Exception {
		// The invariant the whole cluster exists for, asserted across two arms at once rather than per
		// arm. It is also the regression anchoring only the interaction arms would have introduced: the
		// dose arm names its subject through the same chooser, so it has to be handed the same rows —
		// otherwise a hand-authored deployment gets "Amoxicillin (suspension) interacts with active order
		// warfarin" beside "The stated Amoxicillin dose …" for one substance in one response.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);

		assertEquals(DrugReferenceTestSupport.row(entries, "Amoxicillin").substanceGroupKey(),
				DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)").substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertEquals(Arrays.asList("Amoxicillin"),
				DrugReferenceTestSupport.names(service.findByQuery("Is amoxicillin safe for her?")),
				"precondition: the bare word must resolve the UNQUALIFIED row alone");

		PatientClinicalContext context = DrugReferenceTestSupport.ctx(30, 70.0,
				DrugReferenceTestSupport.set("Amoxicillin (suspension)", "Warfarin 5mg"), null, null,
				null);
		assertEquals(Arrays.asList("Amoxicillin", "Amoxicillin (suspension)", "Warfarin"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: while the ORDER's name resolves both rows");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				"Give amoxicillin 2000 mg twice daily.", "Is amoxicillin safe for her?", context);

		// Through the shared selector rather than a local last-match-wins loop, which could not see a
		// DUPLICATE warning of either type — the shape issues #162/#173/#206 keep removing, and the
		// property this case is about. See DrugReferenceTestSupport.onlyOfType.
		SafetyWarning interaction = DrugReferenceTestSupport.onlyOfType(warnings,
				SafetyWarning.TYPE_INTERACTION);
		SafetyWarning overdose = DrugReferenceTestSupport.onlyOfType(warnings,
				SafetyWarning.TYPE_OVERDOSE);
		assertEquals("Amoxicillin (suspension)", interaction.getDrug(),
				"the interaction chip must name the charted row, was: " + warnings);
		assertEquals(interaction.getDrug(), overdose.getDrug(),
				"and the dose warning must call the substance the same thing, was: " + warnings);
		assertTrue(overdose.getDetail().startsWith("The stated Amoxicillin (suspension) dose "),
				"in its sentence as well as in its drug field, was: " + overdose.getDetail());
		// WHICH row's ceiling the sentence quotes: the CHARTED row's own, which is the same row the whole
		// case is about. It reads that way because the stated dose is attributed to the SUBSTANCE rather
		// than to whichever row's alias the answer's wording used (issue #245) — so the charted row, tried
		// first as the subject, genuinely has a dose to compare against its own 2000 mg/day band.
		//
		// This assertion used to read 3000, the unqualified sibling's number, and the paragraph here used
		// to defend that as the only safe choice: preferring the subject row's own band would drop the
		// warning wherever that row publishes none, which is the one direction this layer never takes.
		// The premise stopped being true. Reading the dose per substance is a SUPERSET of reading it per
		// row — every row of a substance sees at least the doses it saw before — so the subject's ceiling
		// is quoted when the stated dose exceeds it, without giving up the fallback: a dose that clears
		// the subject's band and exceeds a sibling's is still reported against the sibling's, and a
		// subject publishing no band at all still falls through to one that does. That fallback is pinned
		// where it belongs rather than argued here — {@code OverdoseSubstanceCollapseTest
		// .aBandOnlyASiblingRowPublishesStillWarns} and {@code DoseCeilingBySubstanceTest
		// .aSubjectPublishingNoBandStillFallsBackToASiblingAndSaysWhoseCeilingItIs}.
		//
		// So the provenance clause issue #208 added is correctly ABSENT here now: it says whose ceiling a
		// sentence quotes only when that is not the row the warning names, and here the two are one row.
		// {@code DoseCeilingAttributionTest} pins the clause itself, on a substance whose named row
		// publishes no band and therefore still needs it.
		assertTrue(overdose.getDetail().contains("2000 mg/day maximum"),
				"was: " + overdose.getDetail());
	}

	/** The order name the CIEL-aliased second row of the COVID substance carries as its own display
	 *  name, verbatim from the shipped KB — the trap the fold cannot reach after issue #250. */
	private static final String COVID_ORDER = "Pfizer-BioNTech Covid-19 Vaccine";

	@Test
	public void theFixtureStillCarriesATrapTheFoldCannotReach() throws Exception {
		// The premise for the case below, and the reason it exists at all. Issue #250's second rung asks
		// which row of a family the DATA names the family after; this pair answers "neither", because both
		// rows are filed under an rxnorm_name that is neither row's display name. So the fold is back to
		// keeping the earliest, the charted row is the later one, and the chart-anchoring step is once
		// again the only thing that can produce the right subject.
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference tozinameran = DrugReferenceTestSupport.row(entries, "Tozinameran");
		DrugReference charted = DrugReferenceTestSupport.row(entries, COVID_ORDER);

		assertEquals(tozinameran.substanceGroupKey(), charted.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertTrue(tozinameran.namesNoRoute() && charted.namesNoRoute(),
				"precondition: neither row may name a route, or the first rung decides it");
		assertFalse(tozinameran.namesItsSubstance() || charted.namesItsSubstance(),
				"precondition: and neither may name its own substance, or issue #250's rung decides it");
		assertSame(tozinameran, DrugReference.canonicalRow(Arrays.asList(tozinameran, charted)),
				"precondition: so the fold still keeps the row the chart does NOT name");
		assertEquals(DrugReference.NAME_IS_THE_DISPLAY_NAME, charted.nameMatchStrength(COVID_ORDER),
				"precondition: while the charted order name IS the later row's display name");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, tozinameran.nameMatchStrength(COVID_ORDER),
				"precondition: which the trap row claims only as one of its CIEL names");
	}

	/** The typhoid concept's own CIEL name, verbatim — the second order's name, and what makes the pair
	 *  reachable at all: the COVID rows' rule token IS this string, while neither row's rule carries an
	 *  ATC code, so a context naming that order any other way leaves the arm with no partner to match. */
	private static final String TYPHOID_ORDER = "Salmonella typhi Ty21a live antigen";

	@Test
	public void theOrderNamedRowIsNamedWhereTheFoldCannotReachIt() throws Exception {
		// Issue #194's property, re-pinned on the family issue #250's rung cannot reach: remove the
		// chart-anchoring step from interactionSubject and this reddens, which is no longer true of the
		// botulinum cases above.
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference charted = DrugReferenceTestSupport.row(entries, COVID_ORDER);
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(COVID_ORDER, TYPHOID_ORDER), null, null, null);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, context);

		assertEquals(1, warnings.size(), "one pair, one chip, was: " + warnings);
		assertEquals(charted.displayLabel(), warnings.get(0).getDrug(),
				"the chip must name the row the patient's own order names, was: " + warnings);
		assertTrue(warnings.get(0).getDetail()
				.startsWith(charted.displayLabel() + " interacts with active order "),
				"and its detail must lead with that same name, was: " + warnings.get(0).getDetail());
		assertFalse(warnings.get(0).getDetail().contains("Tozinameran"),
				"and must not assert a product the chart does not record, was: "
						+ warnings.get(0).getDetail());
	}

	/** The marker that tells the question-PAIR chip from every other interaction chip: it is the only
	 *  one whose sentence is a claim about the question rather than about the chart. */
	private static final String PAIR_MARKER = ", also named in the question";

	@Test
	public void theQuestionPairChipNamesTheSubstanceTheOtherArmsName() throws Exception {
		// Issue #236. The same invariant as everyArmOfOneResponseNamesOneSubstanceOneWay above, extended
		// to the FIFTH arm — the question-pair arm, which resolves its subject over the question's OWN
		// rows while every other arm folds the rows the question, the answer AND the chart resolved.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		String question = "Is amoxicillin safe with warfarin?";

		assertEquals(DrugReferenceTestSupport.row(entries, "Amoxicillin").substanceGroupKey(),
				DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)").substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		// findImpliedByQuery, not findByQuery: the two answer different questions (CLAUDE.md) and this is
		// the one validate() itself builds questionDrugs from, so the precondition guards the predicate
		// production actually uses rather than the unranked primitive underneath it.
		assertEquals(Arrays.asList("Amoxicillin", "Warfarin"),
				DrugReferenceTestSupport.names(service.findImpliedByQuery(question)),
				"precondition: the question must name TWO drugs, or the pair arm never runs — and the "
						+ "bare word must resolve the UNQUALIFIED row alone");

		// ONE order, and deliberately not a warfarin one: with the pair's rule naming an active order
		// the chart arm owns the pair and this arm stands down, so the divergence could not be seen.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(30, 70.0,
				DrugReferenceTestSupport.set("Amoxicillin (suspension)"), null, null, null);
		assertEquals(Arrays.asList("Amoxicillin", "Amoxicillin (suspension)"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: while the ORDER's name resolves both rows");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("Give amoxicillin 2000 mg twice daily.", question, context);

		SafetyWarning pair = null;
		SafetyWarning overdose = null;
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())
					&& warning.getDetail().contains(PAIR_MARKER)) {
				pair = warning;
			} else if (SafetyWarning.TYPE_OVERDOSE.equals(warning.getType())) {
				overdose = warning;
			}
		}
		assertNotNull(pair, "precondition: the question-pair arm must chip, was: " + warnings);
		assertNotNull(overdose, "precondition: the dose arm must warn, was: " + warnings);
		assertEquals(overdose.getDrug(), pair.getDrug(),
				"one response must call one substance one thing, was: " + warnings);
		assertEquals("Amoxicillin (suspension)", pair.getDrug(),
				"and that one thing is the row the patient's own order names, was: " + warnings);
		assertTrue(pair.getDetail().startsWith("Amoxicillin (suspension) interacts with Warfarin"),
				"in its sentence as well as in its drug field, was: " + pair.getDetail());
	}
}
