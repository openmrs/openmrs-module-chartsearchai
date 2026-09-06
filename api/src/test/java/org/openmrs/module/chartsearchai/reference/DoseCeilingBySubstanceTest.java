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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #245 — a stated dose was attributed to the reference ROW whose alias the answer's own wording
 * happened to use, so a SIBLING row of the same substance publishing a stricter ceiling was never
 * compared against and the warning simply did not appear.
 *
 * <p><b>Why this is the serious half of issue #208.</b> #208 item 1 was the same row/subject split
 * producing a warning that NAMED the wrong row's ceiling — a truthfulness defect in a chip that still
 * appeared, and #244 settled it by making the sentence say whose ceiling it quotes. The same mechanism
 * also produces a MISSING warning, which is the direction the whole drug-safety layer exists to prevent
 * and the one that leaves no trace: no chip, no log line, nothing separating "within the ceiling" from
 * "compared against the wrong ceiling".
 *
 * <p><b>The fix, and the trap it has to clear.</b> Rows of one substance are not competitors for a
 * dose — they are one drug — so the attribution walk asks which SUBSTANCE a stated dose belongs to
 * ({@code DrugSafetyValidator.attributedDoses} over the substance's resolved rows) rather than which
 * row's alias sits nearest it. What it deliberately does NOT do is prefer the subject row's own band:
 * #208 rejected that as a whole-cloth answer because it drops the warning wherever the subject row
 * publishes none. Both halves therefore hold at once, and they are two different tests below —
 * {@code OverdoseSubstanceCollapseTest.aBandOnlyASiblingRowPublishesStillWarns} and
 * {@code DoseCeilingAttributionTest.thePerKilogramCeilingSaysItToo} pin the fallback from the other
 * side.
 *
 * <p><b>What the widening cannot do is fire more often than the data says.</b> The gate moves from
 * "this row's alias owns the dose" to "this substance's nearest alias owns the dose, and no OTHER
 * substance's sits closer" — a superset over the rows of one substance and unchanged between
 * substances — so {@code aDoseUnderEveryPublishedCeilingWarnsAboutNothing} below is the half that
 * bounds it.
 *
 * <p>Hand-authored fixtures only, and not for convenience: a dose warning needs {@code ageBands} and
 * the grouping needs {@code substanceName}, and no bundled dataset carries both — the {@code ddinter}
 * source publishes substance names but no dosing, the shipped curated seed publishes dosing but no
 * substance names. So no shipped configuration can pose this shape at all, which
 * {@code DoseCeilingAttributionTest.noShippedConfigurationCanReachTheAttributionAtAll} asserts over the
 * seed rather than argues.
 *
 * <p>Every case runs the REAL production path: the fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, real question and answer
 * strings, GP reads on their no-context defaults.
 */
public class DoseCeilingBySubstanceTest {

	/** Shared with {@code OrderedSubjectRowTest} and {@code DoseCeilingAttributionTest}: two
	 *  {@code Amoxicillin} rows that are ONE substance publishing DIFFERENT daily ceilings (3000 against
	 *  2000), only the unqualified one of which lists the bare word as an alias. That asymmetry is the
	 *  defect's whole mechanism — an answer saying "amoxicillin" resolved the lax row alone. */
	private static final String CHARTED_ROW_FIXTURE =
			"chartsearchai-test/drug-reference-charted-substance-row.json";

	/** The question throughout: it names the substance by the bare word, which is what puts only the
	 *  unqualified row in play from the TEXT side and leaves the strict row reachable only through the
	 *  patient's own order. */
	private static final String QUESTION = "Is amoxicillin safe for her?";

	private static DrugReferenceService service(List<DrugReference> entries) {
		return DrugReferenceTestSupport.serviceWith(entries);
	}

	/** The patient of every case: 30 years, 70 kg, on the SUSPENSION and on warfarin. The suspension
	 *  order is what makes the strict row a resolved row of the substance at all — see
	 *  {@code DrugSafetyValidator.resolvedSubstanceRows} (issue #175) — and warfarin is the fixture's
	 *  interacting order, present so that one response carries an interaction chip beside the dose
	 *  warning exactly as {@code OrderedSubjectRowTest} drives it. */
	private static PatientClinicalContext onTheSuspension() {
		return DrugReferenceTestSupport.ctx(30, 70.0,
				DrugReferenceTestSupport.set("Amoxicillin (suspension)", "Warfarin 5mg"), null, null,
				null);
	}

	@Test
	public void theTwoRowsReallyDisagreeAndOnlyTheLaxOneAnswersTheBareWord() throws Exception {
		// The premise, through the production predicates, so that no case below can pass on a fixture
		// where the rows happen to agree or where the bare word happens to resolve both — either of which
		// would make the whole defect unexpressible while every assertion still went green.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);
		DrugReference unqualified = DrugReferenceTestSupport.row(entries, "Amoxicillin");
		DrugReference suspension = DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)");

		assertEquals(unqualified.substanceGroupKey(), suspension.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertEquals(3000.0, unqualified.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: the row the bare word resolves publishes the LAXER ceiling");
		assertEquals(2000.0, suspension.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: and its sibling the stricter one, or there is nothing to reach");
		assertTrue(unqualified.matchesText("give amoxicillin 1250 mg twice daily"),
				"precondition: the bare word is the lax row's own alias");
		assertFalse(suspension.matchesText("give amoxicillin 1250 mg twice daily"),
				"precondition: and NOT the strict row's — which is why the dose never reached it");

		assertEquals(Arrays.asList("Amoxicillin", "Amoxicillin (suspension)", "Warfarin"),
				DrugReferenceTestSupport.names(service(entries).findForActiveOrders(onTheSuspension())),
				"precondition: while the patient's own order resolves BOTH rows, so the strict row is a "
						+ "resolved row of this pass and the only thing missing was the comparison");
	}

	@Test
	public void aDoseOnlyTheStricterSiblingCeilingExceedsStillWarns() throws Exception {
		// The defect itself. 1250 mg twice daily is 2500 mg/day: under the 3000 the bare word's own row
		// publishes, over the 2000 the patient's charted presentation publishes. Before the fix this
		// raised NOTHING — the lax row was the only row the dose was ever attributed to, and it was not
		// exceeded, so the strict row's band was never consulted and no chip, log line or record recorded
		// that a comparison had been skipped.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service(entries)).validate(
				"Give amoxicillin 1250 mg twice daily.", QUESTION, onTheSuspension());

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin (suspension)"),
				"a dose over a ceiling this dataset publishes for the substance must warn, was: "
						+ warnings);
		assertEquals("The stated Amoxicillin (suspension) dose ~2500 mg/day exceeds the 2000 mg/day "
				+ "maximum for ages 0-120",
				DrugReferenceTestSupport.overdoseDetail(warnings, "Amoxicillin (suspension)"),
				"and it is the CHARTED row's own ceiling, so the sentence has no provenance to add");
	}

	@Test
	public void theDoseWarningAndTheInteractionChipStillNameTheSubstanceOneWay() throws Exception {
		// Issue #206's property over the response the fix newly produces, asserted here rather than left
		// to the arm that already had a warning: the dose arm now warns in a case where it previously
		// said nothing at all, so this is a NEW response shape and the invariant has to hold over it too.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service(entries)).validate(
				"Give amoxicillin 1250 mg twice daily.", QUESTION, onTheSuspension());

		// Through the shared selector rather than a local last-match-wins loop, which could not see a
		// DUPLICATE warning of either type — the shape issues #162/#173/#206 keep removing, and the
		// property this case is about. See DrugReferenceTestSupport.onlyOfType.
		SafetyWarning interaction = DrugReferenceTestSupport.onlyOfType(warnings,
				SafetyWarning.TYPE_INTERACTION);
		SafetyWarning overdose = DrugReferenceTestSupport.onlyOfType(warnings,
				SafetyWarning.TYPE_OVERDOSE);
		assertEquals("Amoxicillin (suspension)", interaction.getDrug(),
				"the interaction chip must still name the charted row, was: " + warnings);
		assertEquals(interaction.getDrug(), overdose.getDrug(),
				"and the dose warning must call the substance the same thing, was: " + warnings);
	}

	@Test
	public void aDoseUnderEveryPublishedCeilingWarnsAboutNothing() throws Exception {
		// The half that bounds the widening, and the reason it is not simply "compare against the
		// strictest number anywhere in the family": 750 mg twice daily is 1500 mg/day, under BOTH
		// published ceilings and under the 30 mg/kg per-dose limit this 70 kg patient's band sets
		// (2100 mg). An attribution that fired on any dose it could find, or a comparison that reached
		// past this substance's own rows, reddens here.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service(entries)).validate(
				"Give amoxicillin 750 mg twice daily.", QUESTION, onTheSuspension());

		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin (suspension)"),
				"a dose within every ceiling the dataset publishes must warn about nothing, was: "
						+ warnings);
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin"),
				"under neither name, was: " + warnings);
	}

	/** Shared with {@code OverdoseSubstanceCollapseTest}: {@code Cefalexin}'s route-unspecified row — the
	 *  row the substance is named after — publishes NO band, and only its paediatric sibling does. */
	private static final String DOSING_ROWS_FIXTURE =
			"chartsearchai-test/drug-reference-substance-dosing-rows.json";

	@Test
	public void aSubjectPublishingNoBandStillFallsBackToASiblingAndSaysWhoseCeilingItIs()
			throws Exception {
		// The trap, and the reason this is a composition rather than "prefer the subject row's band".
		// Issue #208 rejected that as a whole-cloth answer precisely because a subject row publishing no
		// band would then drop the warning, and the fix above must not have quietly introduced it: the
		// walk still tries every row, so a band only a sibling publishes is still reached, and the
		// sentence still says whose ceiling it is (issue #244's clause, which since this fix is what the
		// FALLBACK narrates rather than something a subject row's own ceiling ever needs).
		//
		// This case passes before the fix as well, and is here for that reason — it is the assertion a
		// lazier reading of issue #245 reddens, not one the fix turns green. Both cefalexin rows list the
		// bare alias, so per-row and per-substance attribution agree here and only the CHOICE of row
		// could move. 550 mg twice daily is 1100 mg/day against the paediatric row's 1000 for a
		// 6-year-old, and no weight is given, so the daily arm is unambiguously the arm that trips.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(DOSING_ROWS_FIXTURE);
		DrugReference named = DrugReferenceTestSupport.row(entries, "Cefalexin");
		DrugReference paediatric = DrugReferenceTestSupport.row(entries, "Cefalexin (paediatric)");

		assertEquals(named.substanceGroupKey(), paediatric.substanceGroupKey(),
				"precondition: one substance");
		assertNull(named.bandForAge(6),
				"precondition: the row the warning is named after publishes no band at all");
		assertNotNull(paediatric.bandForAge(6), "precondition: while its sibling does");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service(entries)).validate(
				"Give cefalexin 550 mg twice daily.", "What dose of cefalexin?",
				DrugReferenceTestSupport.ctx(6, null, null, null, null, null));

		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, "Cefalexin"),
				"a band only a sibling publishes must still warn, was: " + warnings);
		assertEquals("The stated Cefalexin dose ~1100 mg/day exceeds the 1000 mg/day maximum for ages "
				+ "0-11 — a ceiling this dataset publishes for Cefalexin (paediatric), not for Cefalexin",
				DrugReferenceTestSupport.overdoseDetail(warnings, "Cefalexin"),
				"and the daily arm must still name the row that published the ceiling it fell back to");
	}

	@Test
	public void aDoseStatedForAnotherDrugIsStillNotChargedToThisSubstance() throws Exception {
		// The other bound, and the one the widening could plausibly have broken: the nearest-alias guard
		// still separates SUBSTANCES, it merely stopped separating rows of one substance.
		//
		// Both drugs sit in ONE clause deliberately, and that is the whole point of the case rather than
		// a detail of phrasing. Split across two sentences this reads as a bound and tests nothing:
		// CLAUSE_DELIMITER cuts at the full stop, the warfarin clause names no amoxicillin alias so the
		// clause gate rejects it before any dose is read, and the amoxicillin clause carries no `mg` at
		// all — so substanceOwnsDose is never reached. Mutation-verified: with the drugs in separate
		// clauses, replacing the whole body of substanceOwnsDose with `return true` leaves every test in
		// this package green, i.e. the only thing standing between one drug's stated dose and another
		// drug's ceiling could be deleted silently. In one clause the guard is genuinely exercised —
		// warfarin's alias sits adjacent to the dose and amoxicillin's is ~30 characters further off, so
		// the veto is what keeps 2500 mg/day (which exceeds BOTH published amoxicillin ceilings) off this
		// substance.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service(entries)).validate(
				"Give warfarin 2500 mg twice daily with amoxicillin.", QUESTION, onTheSuspension());

		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin (suspension)"),
				"another drug's dose is not this substance's, was: " + warnings);
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(warnings, "Amoxicillin"),
				"under neither name, was: " + warnings);
	}
}
