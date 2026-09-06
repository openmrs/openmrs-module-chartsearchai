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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A recorded drug name that names a COMBINATION PRODUCT is checked against every constituent, not
 * against whichever one claims the name most strongly (issue #193).
 *
 * <p><b>The defect.</b> Issues #176/#192 fixed <em>which</em> entry a recorded name resolves to; it
 * still resolved to exactly one. A combination name denotes several substances, so one of them was
 * compared and the rest were never checked at all — and which one survived is decided by the KB's
 * alias lists, which is arbitrary with respect to which moiety matters clinically. Measured through
 * {@link DrugReferenceService#lookupByToken} over the shipped 19 MB KB (2026-08-09; re-measure before
 * relying on the figures): {@code sulfamethoxazole / trimethoprim} answers {@code Trimethoprim}
 * ({@code J01EA}) and nothing is ever compared against the sulfa moiety ({@code J01EB}) that drives
 * that allergy, and {@code omeprazole / sodium bicarbonate} answers {@code Sodium bicarbonate}
 * ({@code B05CB}/{@code B05XA}), losing the PPI class {@code A02BC} entirely.
 *
 * <p>Every case runs verbatim shipped-KB slices through the real {@link DdiDrugReferenceSource} parser
 * and the real {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}, and asserts
 * the chip TEXT — the failure mode here is silence, so a case that passes on "some chip appeared" would
 * prove nothing. The {@code theFixtureReallyCarries…} cases are their premises, stated through the
 * production predicates.
 */
public class CombinationAllergenResolutionTest {

	private static final String FIXTURE = DrugReferenceTestSupport.DDI_COMBINATION_ALLERGEN;

	/** The co-trimoxazole name as the shipped KB publishes it, and as issue #193 measured it. */
	private static final String COTRIMOXAZOLE = "sulfamethoxazole / trimethoprim";

	/** The PPI combination whose class the ranking dropped. */
	private static final String OMEPRAZOLE_BICARBONATE = "omeprazole / sodium bicarbonate";

	/** A combination name BOTH of whose constituents claim it equally — the 1367-name population. */
	private static final String ABACAVIR_LAMIVUDINE = "abacavir / lamivudine";

	/** A combination name carrying NO separator and no qualifier, so only the equal claim reaches its
	 *  ingredients. The KB spells many combinations this way ({@code rifampicin and isoniazid},
	 *  {@code sultamicillin tosylate}, {@code potassium chloride-potassium gluconate}). */
	private static final String CO_AMOXICLAV = "amoxicillin and clavulanic acid";

	/** A combination joining its ingredients with a BARE separator, which is what a structural
	 *  "only a spaced separator counts" rule would silently close. */
	private static final String POTASSIUM_SALTS = "potassium citrate/potassium gluconate";

	/** A combination one of whose constituents the KB only CONTAINS rather than NAMES — the fragment
	 *  shape the gate on the split has to refuse. */
	private static final String BENZOCAINE_CLOVE = "benzocaine / clove oil";

	/** A SALT name a third entry claims only by containment — what the equal-claimant leg must not
	 *  admit. */
	private static final String KETOROLAC_SALT = "ketorolac tromethamine";

	private static List<SafetyWarning> warningsFor(String question, String allergy) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", question, DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set(allergy), null));
	}

	@Test
	public void theFixtureReallyCarriesACombinationOnlyOneConstituentClaims() throws IOException {
		// The premise: the two constituents claim the combination name at DIFFERENT ranks, so this is not
		// the equal-claim tie below — the ranking is working exactly as issue #192 defined it, and the
		// constituent it drops is dropped correctly and still has to be checked.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference sulfamethoxazole = DrugReferenceTestSupport.row(entries, "Sulfamethoxazole");
		DrugReference trimethoprim = DrugReferenceTestSupport.row(entries, "Trimethoprim");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, trimethoprim.nameMatchStrength(COTRIMOXAZOLE),
				"precondition: the combination must be one of Trimethoprim's own names");
		assertEquals(DrugReference.NAME_TOKEN_INSIDE_A_NAME,
				sulfamethoxazole.nameMatchStrength(COTRIMOXAZOLE),
				"precondition: while Sulfamethoxazole only occurs inside it, so the rank drops it");
		assertNotEquals(sulfamethoxazole.substanceKey(), trimethoprim.substanceKey(),
				"precondition: and the two are different substances");
		assertEquals("Trimethoprim",
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE).lookupByToken(COTRIMOXAZOLE).getName(),
				"precondition: so the single-entry resolution answers the constituent that is NOT the "
						+ "sulfonamide");
	}

	@Test
	public void aCombinationAllergyIsADirectAllergyToTheConstituentInPlay() throws IOException {
		// The headline case. Before: the allergy resolved to Trimethoprim, whose J01EA shares nothing with
		// Sulfamethoxazole's J01EB and which is a different substance, so the arm raised NOTHING — a
		// patient with a recorded co-trimoxazole allergy asked about the sulfonamide itself got silence.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give sulfamethoxazole?", COTRIMOXAZOLE);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		// The parenthesized synonym is displayLabel()'s, not this fix's: the shipped KB gives this row
		// the rxnorm_name "sulfamethazine", a DIFFERENT sulfonamide, so the two names diverge and the
		// label carries both. Reported as a KB data defect; asserted verbatim here so the label is the
		// one production prints rather than the one this test would prefer.
		assertEquals("The patient has a recorded allergy to Sulfamethoxazole (sulfamethazine).",
				warnings.get(0).getDetail(),
				"and it names the constituent the patient is allergic to, not the one whose alias list "
						+ "happened to claim the combination name");
		assertEquals("Sulfamethoxazole (sulfamethazine)", warnings.get(0).getDrug());
	}

	@Test
	public void aCombinationAllergyIsComparedByClassAgainstEveryConstituent() throws IOException {
		// The class arm on the same allergy: the sulfa moiety has to reach the CLASS comparison too, or a
		// sulfonamide the patient has never had is checked only against trimethoprim's class.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give sulfisoxazole?", COTRIMOXAZOLE);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals("Sulfisoxazole is in the same ATC class (J01EB) as the patient's allergy to"
				+ " Sulfamethoxazole (sulfamethazine) — possible cross-reactivity",
				warnings.get(0).getDetail(),
				"the sulfonamide subgroup, reached through the constituent the ranking drops");
	}

	@Test
	public void aCombinationAllergyRegainsTheClassOfTheConstituentTheRankingDrops() throws IOException {
		// Issue #193's second measured case, and the one where a whole therapeutic class was lost: the
		// combination is one of Sodium bicarbonate's CIEL names, so the answer was B05CB/B05XA and A02BC —
		// the class a clinician asking about another PPI needs — was never compared.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give pantoprazole?",
				OMEPRAZOLE_BICARBONATE);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals("Pantoprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Omeprazole — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void theFixtureReallyCarriesACombinationBothConstituentsClaimEqually() throws IOException {
		// The other half of the population, and the one a rank cannot decide at all: both constituents
		// publish the combination among their own names, so the tie is broken by dataset order.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference abacavir = DrugReferenceTestSupport.row(entries, "Abacavir");
		DrugReference lamivudine = DrugReferenceTestSupport.row(entries, "Lamivudine");
		DrugReference zidovudine = DrugReferenceTestSupport.row(entries, "Zidovudine");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, abacavir.nameMatchStrength(ABACAVIR_LAMIVUDINE),
				"precondition: the combination must be one of Abacavir's own names");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, lamivudine.nameMatchStrength(ABACAVIR_LAMIVUDINE),
				"precondition: and one of Lamivudine's, at the same rank — so no rank can decide between them");
		assertTrue(entries.indexOf(abacavir) < entries.indexOf(lamivudine),
				"precondition: the slice must keep KB order, so the tie resolves to Abacavir");
		assertFalse(zidovudine.matchesDrugName(ABACAVIR_LAMIVUDINE),
				"precondition: the third drug must NOT be claimed by the combination name");
		assertEquals(abacavir.atcSubgroups(), zidovudine.atcSubgroups(),
				"precondition: while sharing an ATC subgroup with both constituents, so it is related to "
						+ "the recorded allergy twice over");
		assertEquals(lamivudine.atcSubgroups(), zidovudine.atcSubgroups(),
				"precondition: with the OTHER constituent too, so the subject is related to both");
	}

	@Test
	public void aCombinationAllergyIsADirectAllergyRatherThanCrossReactivityWithItsOtherConstituent()
			throws IOException {
		// Precedence has to be decided across the whole set a name implies, not per constituent: the
		// earliest claimant (Abacavir) shares J05AF with lamivudine, so a per-constituent scan reports the
		// drug in play as merely cross-reactive with a DIFFERENT drug — while the chart records an allergy
		// to that very drug.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give lamivudine?", ABACAVIR_LAMIVUDINE);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Lamivudine.", warnings.get(0).getDetail(),
				"identity outranks the class relationship the other constituent also has");
	}

	@Test
	public void aCombinationNamingSeveralSubstancesStillRaisesOneChipPerClinicalFact() throws IOException {
		// The constraint that binds this fix: one recorded name yielding N substances is still ONE clinical
		// fact. Zidovudine shares J05AF with both constituents, so a chip per implied substance would
		// report the same recorded allergy twice.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give zidovudine?", ABACAVIR_LAMIVUDINE);

		assertEquals(1, warnings.size(), "two constituents, one recorded allergy, one chip, was: "
				+ warnings);
		assertEquals("Zidovudine is in the same ATC class (J05AF) as the patient's allergy to"
				+ " Abacavir — possible cross-reactivity", warnings.get(0).getDetail(),
				"named after the first constituent that carries the relationship");
	}

	@Test
	public void theFixtureReallyCarriesACombinationNoSeparatorRuleCanReach() throws IOException {
		// The premise for the case below: this name joins its ingredients with a WORD, so neither the
		// constituent leg (which reads a separator) nor the moiety leg (which reads a trailing
		// qualifier) can see either ingredient. Only the equal-claimant leg reaches them.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference amoxicillin = DrugReferenceTestSupport.row(entries, "Amoxicillin");
		DrugReference clavulanate = DrugReferenceTestSupport.row(entries, "Clavulanic acid");
		assertTrue(DrugReference.combinationConstituents(CO_AMOXICLAV).isEmpty(),
				"precondition: the name must carry no combination separator");
		assertNull(DrugReference.parentMoietyName(CO_AMOXICLAV),
				"precondition: nor a trailing qualifier");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, amoxicillin.nameMatchStrength(CO_AMOXICLAV),
				"precondition: the combination must be one of Amoxicillin's own names");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, clavulanate.nameMatchStrength(CO_AMOXICLAV),
				"precondition: both ingredients must claim it equally, at the alias rank");
		assertTrue(entries.indexOf(clavulanate) < entries.indexOf(amoxicillin),
				"precondition: the slice must keep KB order, so the tie resolves to Clavulanic acid — "
						+ "the ingredient the patient is NOT asked about");
		assertNotEquals(amoxicillin.substanceKey(), clavulanate.substanceKey(),
				"precondition: and the two are different substances");
	}

	@Test
	public void aCombinationTheKbSpellsWithoutASeparatorIsStillCheckedAgainstEveryIngredient()
			throws IOException {
		// The equal-claimant leg on its own. Every other case in this file joins its ingredients with a
		// separator, so the constituent leg reaches them too and this leg can be removed without any of
		// them noticing. Here it cannot: the name resolves to Clavulanic acid, which is a different
		// substance from amoxicillin and carries no ATC code, so both class comparisons have nothing to
		// compare and the arm returns NOTHING — silence on a recorded penicillin allergy, the #86 class.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give amoxicillin?", CO_AMOXICLAV);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Amoxicillin.", warnings.get(0).getDetail(),
				"reached by the equal claim alone — no separator and no qualifier to derive it from");
	}

	@Test
	public void aCombinationJoiningItsIngredientsWithoutSpacesStillReachesBothOfThem()
			throws IOException {
		// The shape a structural "only a spaced separator counts" split would stop reaching, which is
		// why DrugReference.combinationConstituents splits on the separator itself. This case does NOT
		// isolate that decision — both rows publish the whole name, so the equal-claimant leg reaches
		// the second ingredient with the split reverted, and the shipped KB offers no name that
		// separates the two. Recorded there rather than claimed here.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give potassium gluconate?",
				POTASSIUM_SALTS);

		assertEquals(1, warnings.size(), "one recorded allergy, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Potassium gluconate.",
				warnings.get(0).getDetail(),
				"the second ingredient, which the resolution's own answer (Potassium citrate) is not");
	}

	@Test
	public void aConstituentTheKbOnlyCONTAINSRatherThanNAMESIsNotReached() throws IOException {
		// The split's lower bound, and the reason it can be unconditional. Splitting a name on the
		// separator produces fragments as readily as ingredients, and the gate — an entry must be NAMED
		// the constituent — is what separates them. It is doing real work rather than being satisfied by
		// accident: 'clove oil' DOES resolve, to the Clove row, but only by CONTAINMENT, which is the
		// rank issue #192 established is a false claim on a name. This is the same shape as
		// PresentationMoietyAllergenTest's Peanut oil / Peanut bound, arriving through the constituent
		// leg instead of the moiety leg.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference clove = DrugReferenceTestSupport.row(entries, "Clove");
		assertEquals(DrugReference.NAME_TOKEN_INSIDE_A_NAME, clove.nameMatchStrength("clove oil"),
				"precondition: the row must reach the constituent by containment and no more");
		assertNotEquals(clove.substanceKey(), DrugReferenceTestSupport.row(entries, "Benzocaine (topical)").substanceKey(),
				"precondition: and be a different substance from the constituent that does resolve");

		assertEquals(0, warningsFor("Is it safe to give clove?", BENZOCAINE_CLOVE).size(),
				"so the fragment reaches no substance — the bound this split carries");
		assertEquals(1, warningsFor("Is it safe to give benzocaine?", BENZOCAINE_CLOVE).size(),
				"while the constituent the KB IS named still does, so this case cannot pass by the "
						+ "recorded name resolving to nothing at all");
	}

	@Test
	public void anEntryClaimingTheRecordedNameOnlyByContainmentIsNotAnImpliedSubstance()
			throws IOException {
		// The equal-claimant leg's own condition, and the one case in this file whose failure is a chip
		// that is WRONG rather than one that is missing. The leg admits an entry only when it makes the
		// SAME claim as the strongest, so an entry the recorded string merely CONTAINS is not an implied
		// substance — issue #192's rule applied to the leg rather than to the resolution. Here that is
		// Tromethamine, the buffer ketorolac is salted with. Relaxing the condition to "any match" fails
		// this case and several others across the resolution tests (measured by mutation), so it is well
		// held; what this case adds is the salt shape, which is how such a name reaches a chart.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference ketorolac = DrugReferenceTestSupport.row(entries, "Ketorolac");
		DrugReference tromethamine = DrugReferenceTestSupport.row(entries, "Tromethamine");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, ketorolac.nameMatchStrength(KETOROLAC_SALT),
				"precondition: the salt must be one of Ketorolac's own names");
		assertEquals(DrugReference.NAME_TOKEN_INSIDE_A_NAME,
				tromethamine.nameMatchStrength(KETOROLAC_SALT),
				"precondition: while the buffer only occurs inside it");
		assertNotEquals(ketorolac.substanceKey(), tromethamine.substanceKey(),
				"precondition: and the two are different substances");

		assertEquals(0, warningsFor("Is it safe to give tromethamine?", KETOROLAC_SALT).size(),
				"the buffer is not what the patient is allergic to");
		assertEquals(1, warningsFor("Is it safe to give ketorolac?", KETOROLAC_SALT).size(),
				"while the salt's own substance still reports, and once — the KB files it as two rows");
	}

	@Test
	public void aRecordedNameThatIsNotACombinationResolvesExactlyAsBefore() throws IOException {
		// The bound in the other direction: the widening is gated on the KB being NAMED the derived string,
		// so a salt or an antidote whose name merely CONTAINS another drug's name gains nothing — which is
		// what issue #192 measured and fixed, and what this must not undo. Esomeprazole is Omeprazole's own
		// rxnorm_name, so an esomeprazole allergy must still be attributed to esomeprazole alone.
		List<SafetyWarning> warnings = warningsFor("Is it safe to give omeprazole?", "Esomeprazole");

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Omeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Esomeprazole — possible cross-reactivity", warnings.get(0).getDetail(),
				"the two PPIs stay two substances (issue #187), so this is cross-reactivity and not an "
						+ "identity chip");
	}
}
