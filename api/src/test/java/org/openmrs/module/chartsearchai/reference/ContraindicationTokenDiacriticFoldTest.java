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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The diacritic fold on the ALLERGY and CONDITION token haystacks — {@code
 * PatientClinicalContext.containsToken}, behind {@code hasAllergyToken}/{@code hasConditionToken}
 * (issue #141).
 *
 * <p><b>The defect.</b> This is the one matcher issues #129/#138 did not reach: that work folded
 * {@link DrugReference#containsBoundedToken}, the ORDER-NAME scan, and deliberately scoped itself
 * there. The allergy/condition haystack kept raw {@link String#contains}, so on the SHIPPED DEFAULT
 * source format ({@code sourceFormat=json}) the curated Amoxicillin entry's
 * {@code {"type":"allergy","token":"penicillin"}} rule missed an allergen recorded as
 * {@code Pénicilline G} — a real fr locale-preferred name in this dictionary, reached by default in a
 * francophone deployment because {@code PatientClinicalContextBuilder} reads the concept name in the
 * CURRENT locale. Unlike most defects found this week this one needs no {@code ddinter} configuration
 * to reach.
 *
 * <p><b>Measured 2026-08-05</b> over the 3.7.1 demo dictionary's 1219 allergen-candidate names (237
 * of them accented) against the shipped curated dataset's six allergy tokens: raw containment matched
 * 27, folded containment matches 38 — <b>11 names gained, 0 lost</b>, over the tokens
 * {@code penicillin} (6 names) and {@code paracetamol} (5). {@code ibuprofen}, {@code amoxicillin},
 * {@code nsaid} and {@code aminoglycoside} are unaffected: no accent falls inside those stems. The
 * condition arm gained 0 over the dictionary's 9472 diagnosis/finding names, because none of the four
 * curated condition tokens has an accent anywhere in its stem either — which is why the condition case
 * below is driven from a fixture whose tokens are the accented side, the direction a francophone
 * deployment's own curated file takes.
 *
 * <p>Folding alone suffices, and that is the whole change: bare containment already tolerates the
 * trailing {@code -e}/{@code -s} of {@code Pénicillines}, so no boundary or inflection rule is added
 * here. The last case pins that, because it is what separates this relaxation from the boundary rule
 * the order-name arm needs — a token deliberately matches a FRAGMENT of allergy/condition free text
 * ({@code nsaid} inside "NSAIDs", {@code peptic ulcer} inside "history of peptic ulcer disease"), and
 * a boundary rule would silently stop matching the rules that exist.
 *
 * <p>Every case runs the real pipeline: the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} over the real curated
 * dataset (the bundled classpath default, which is what an unset {@code sourceFormat} GP selects) or
 * over a fixture parsed by the real {@link JsonDrugReferenceSource}.
 */
public class ContraindicationTokenDiacriticFoldTest {

	/** A francophone deployment's own curated rules — accented TOKENS, the opposite direction from
	 *  the shipped file's accented haystacks. */
	private static final String FRENCH_RULES = "chartsearchai-test/drug-reference-accented-tokens.json";

	private static final String AMOXICILLIN_QUESTION = "Is it safe to give her amoxicillin?";

	private static final String PARACETAMOL_QUESTION = "Is it safe to give her paracetamol?";

	/** The shipped curated dataset, through the production classpath default. */
	private DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.bundledService());
	}

	private PatientClinicalContext allergicTo(String allergen) {
		return DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set(allergen), null);
	}

	@Test
	public void anAccentedAllergenNameStillMatchesTheCuratedPenicillinRule() {
		// The headline: the canonical francophone spelling of the drug the curated rule is about.
		// Paired with the unaccented spelling of the same product — also a real row in this
		// dictionary — so the accent is the only difference between the case that matched and the case
		// that did not.
		DrugSafetyValidator validator = curatedValidator();

		List<SafetyWarning> unaccented = validator.validate("Amoxicillin could be given.",
				AMOXICILLIN_QUESTION, allergicTo("Penicillin G"));
		assertTrue(DrugReferenceTestSupport.detailContains(unaccented,
				SafetyWarning.TYPE_CONTRAINDICATION, "Amoxicillin", "penicillin-class hypersensitivity"),
				"precondition: the unaccented spelling must raise the curated penicillin rule, else the "
						+ "accented case proves nothing, was: " + unaccented);

		List<SafetyWarning> accented = validator.validate("Amoxicillin could be given.",
				AMOXICILLIN_QUESTION, allergicTo("Pénicilline G"));
		assertTrue(DrugReferenceTestSupport.detailContains(accented, SafetyWarning.TYPE_CONTRAINDICATION,
				"Amoxicillin", "penicillin-class hypersensitivity"),
				"an allergy recorded as \"Pénicilline G\" must raise the curated penicillin rule — a "
						+ "diacritic is a spelling of the drug, not a different drug, was: " + accented);
	}

	@Test
	public void everyMeasuredLocalePreferredFrenchAllergenNameMatches() {
		// All 11 names the measurement found, asserted one by one rather than as a count, so a
		// regression names the spelling it lost. Each is a locale-preferred fr name in the 3.7.1 demo
		// dictionary; "Allergie à la pénicilline" is the canonical allergy concept itself.
		DrugSafetyValidator validator = curatedValidator();
		String[] penicillin = { "Pénicilline G", "Pénicillines", "Allergie à la pénicilline",
				"Benzathine pénicilline", "Benzyl pénicilline", "Benzyl pénicilline procaine" };
		for (String allergen : penicillin) {
			List<SafetyWarning> warnings = validator.validate("Amoxicillin could be given.",
					AMOXICILLIN_QUESTION, allergicTo(allergen));
			assertTrue(DrugReferenceTestSupport.detailContains(warnings,
					SafetyWarning.TYPE_CONTRAINDICATION, "Amoxicillin", "penicillin-class hypersensitivity"),
					"\"" + allergen + "\" must raise the curated penicillin rule, was: " + warnings);
		}
		String[] paracetamol = { "Paracétamol", "Paracétamol pédiatrique", "Codéine / Paracétamol",
				"Paracétamol et phosphate de codeine", "Ibuprofene et paracétamol" };
		for (String allergen : paracetamol) {
			List<SafetyWarning> warnings = validator.validate("Paracetamol could be given.",
					PARACETAMOL_QUESTION, allergicTo(allergen));
			assertTrue(DrugReferenceTestSupport.detailContains(warnings,
					SafetyWarning.TYPE_CONTRAINDICATION, "Paracetamol", "documented paracetamol allergy"),
					"\"" + allergen + "\" must raise the curated paracetamol rule, was: " + warnings);
		}
	}

	@Test
	public void theConditionArmFoldsTooAndSoDoesTheTokenSide() throws IOException {
		// The condition arm shares containsToken, and this fixture drives the fold from the other side:
		// the operator's own curated token carries the accents ("ulcère gastroduodénal") while the
		// condition as entered does not — the ordinary shape of French clinical data typed on a
		// keyboard set to another layout. Folding only the haystack would leave this failing.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FRENCH_RULES)));
		String question = "Is it safe to give her ibuprofen?";
		String answer = "Ibuprofen could be given.";

		List<SafetyWarning> accentedAsEntered = validator.validate(answer, question,
				DrugReferenceTestSupport.ctx(60, null, null, null, null,
						DrugReferenceTestSupport.set("Ulcère gastroduodénal")));
		assertTrue(DrugReferenceTestSupport.detailContains(accentedAsEntered,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofène", "ulcère gastroduodénal évolutif"),
				"precondition: the rule must fire when the condition is entered exactly as the token "
						+ "spells it, was: " + accentedAsEntered);

		List<SafetyWarning> typedFlat = validator.validate(answer, question,
				DrugReferenceTestSupport.ctx(60, null, null, null, null,
						DrugReferenceTestSupport.set("Ulcere gastroduodenal")));
		assertTrue(DrugReferenceTestSupport.detailContains(typedFlat, SafetyWarning.TYPE_CONTRAINDICATION,
				"Ibuprofène", "ulcère gastroduodénal évolutif"),
				"a condition typed without its accents must still match an accented curated token, was: "
						+ typedFlat);

		List<SafetyWarning> allergyTypedFlat = validator.validate(answer, question,
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Anti-inflammatoire non steroidien"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(allergyTypedFlat,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofène", "hypersensibilité aux AINS"),
				"and the same on the allergy arm, which shares the matcher, was: " + allergyTypedFlat);
	}

	@Test
	public void aCuratedTokenOfNothingButCombiningMarksMatchesNothing() throws IOException {
		// The one branch the fold ADDS rather than relaxes, and the reason it is needed: folding is
		// applied to the token as well, so a token that is nothing but combining marks folds to the
		// EMPTY string — and the empty string is contained in every haystack, which would
		// contraindicate the drug for every patient carrying any allergy at all. Unlike every other
		// case here this is not a defect the unfolded code had (unfolded, such a token simply matched
		// nothing); it pins the guard the relaxation makes necessary, on the same operator-authored
		// surface that can produce it — a curated file is not sanitized, and a mis-typed token is a
		// dead accent with no letter attached.
		//
		// The entry's other rule is the precondition in the same call: it must fire, so the absence
		// below is attributable to the guard and not to an entry that never reached the loop.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FRENCH_RULES)));

		List<SafetyWarning> warnings = validator.validate("Ketoprofen could be given.",
				"Is it safe to give her ketoprofen?", allergicTo("Aspirine"));

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_CONTRAINDICATION,
				"Kétoprofène", "hypersensibilité croisée aux salicylés"),
				"precondition: the entry's ordinary token must fire, else the absence below proves "
						+ "nothing, was: " + warnings);
		assertEquals(1, warnings.size(),
				"a token of nothing but combining marks folds to the empty string, which every haystack "
						+ "contains — it must match nothing rather than everything, was: " + warnings);
	}

	@Test
	public void deliberateFragmentMatchingIsUnchanged() {
		// What this relaxation must NOT become. The allergy/condition haystacks are free text where a
		// curated rule is meant to match a FRAGMENT, so the order-name arm's boundary rule would break
		// the rules that exist: "nsaid" is not a whole word of "NSAIDs" and "peptic ulcer" is not the
		// whole of "history of peptic ulcer disease". Both pass before and after the fold, and both fail
		// if the fold is mistaken for a licence to give this haystack a boundary rule too.
		DrugSafetyValidator validator = curatedValidator();
		String question = "Is it safe to give her ibuprofen?";
		String answer = "Ibuprofen could be given.";

		List<SafetyWarning> classAllergy = validator.validate(answer, question, allergicTo("NSAIDs"));
		assertTrue(DrugReferenceTestSupport.detailContains(classAllergy,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "NSAID hypersensitivity"),
				"a class allergy recorded in the plural must still match the fragment token, was: "
						+ classAllergy);

		List<SafetyWarning> condition = validator.validate(answer, question,
				DrugReferenceTestSupport.ctx(60, null, null, null, null,
						DrugReferenceTestSupport.set("History of peptic ulcer disease")));
		assertTrue(DrugReferenceTestSupport.detailContains(condition, SafetyWarning.TYPE_CONTRAINDICATION,
				"Ibuprofen", "active peptic ulcer disease"),
				"a condition in the clinician's own wording must still match the fragment token, was: "
						+ condition);
	}
}
