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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #194 — the interaction chip's subject was {@link DrugReference#canonicalRow}'s pick, which is
 * the row naming no route and, where every row of a family names no route, simply the dataset's first.
 * So a patient ordered one presentation was told about another: live-measured on the 3.7.1 standalone,
 * a {@code Botulinum toxin type A} order was subjected on
 * {@code Daxibotulinumtoxina (botulinum toxin type a)}.
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
 * {@code canonicalRow} fallback unchanged.
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
		// The premise, through the production predicates: both rows name no route, so canonicalRow
		// cannot separate them and keeps the earliest — while the order's own name IS the second row's
		// display name and only an alias of the first. Without this the case could pass on a slice
		// where canonicalRow happened to pick the right row anyway.
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference daxi = DrugReferenceTestSupport.row(entries, "Daxibotulinumtoxina");
		DrugReference botoxA = DrugReferenceTestSupport.row(entries, BOTULINUM_A_ORDER);

		assertEquals(daxi.substanceGroupKey(), botoxA.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertTrue(daxi.namesNoRoute() && botoxA.namesNoRoute(),
				"precondition: neither row may name a route, or canonicalRow decides it on merit");
		assertSame(daxi, DrugReference.canonicalRow(Arrays.asList(daxi, botoxA)),
				"precondition: canonicalRow therefore keeps the trap row");
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
		// subject is a free choice between them and the chart is the only thing that can settle it.
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

		SafetyWarning interaction = null;
		SafetyWarning overdose = null;
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())) {
				interaction = warning;
			} else if (SafetyWarning.TYPE_OVERDOSE.equals(warning.getType())) {
				overdose = warning;
			}
		}
		assertNotNull(interaction, "precondition: the interaction arm must chip, was: " + warnings);
		assertNotNull(overdose, "precondition: the dose arm must warn, was: " + warnings);
		assertEquals("Amoxicillin (suspension)", interaction.getDrug(),
				"the interaction chip must name the charted row, was: " + warnings);
		assertEquals(interaction.getDrug(), overdose.getDrug(),
				"and the dose warning must call the substance the same thing, was: " + warnings);
		assertTrue(overdose.getDetail().startsWith("The stated Amoxicillin (suspension) dose "),
				"in its sentence as well as in its drug field, was: " + overdose.getDetail());
		// WHICH row's ceiling the sentence quotes is still addOverdose's own answer and is unchanged: the
		// row the answer's own wording attributed the dose to (issue #174 site 4 — "every row is still
		// tried", so a band on a sibling is never a lost warning), which here is the unqualified row and
		// its 3000 mg/day. What this line pins is that the NUMBER is still quoted, and that is the half
		// issue #208 deliberately left alone: preferring the subject row's own band would drop the
		// warning wherever that row publishes none, which is the one direction this layer never takes.
		//
		// What #208 DID settle — and what this comment used to say was nobody's decision — is that the
		// sentence now says WHOSE ceiling that is when the quoting row is not the row the warning names.
		// It is not visible here: the sentence appends the clause after this substring, so this assertion
		// reads the same either way. {@code DoseCeilingAttributionTest} is where the clause itself is
		// pinned, over this very fixture.
		//
		// The residue #208 measured and did not fix: the charted row publishes a STRICTER 2000 mg/day
		// ceiling that no arm reaches, because the answer said the bare word and only the unqualified
		// row's aliases resolve it — so a stated 2500 mg/day raises nothing at all here. That is an
		// under-warning rather than a mis-naming, it moves this very assertion, and it is filed on its
		// own issue with its own before/after to measure.
		assertTrue(overdose.getDetail().contains("3000 mg/day maximum"),
				"was: " + overdose.getDetail());
	}
}
