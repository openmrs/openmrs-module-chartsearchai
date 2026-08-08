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
 * Resolving an ALLERGEN — a clinician-entered drug name — to a reference entry (issue #147).
 *
 * <p><b>The defect.</b> The same string reached two matchers with different tolerance depending on
 * which half of the safety check was asking. An interaction rule's token was matched against an
 * active order's display name by {@link DrugReference#matchesOrderName}, which tolerates the one
 * inflectional ending a localized drug name adds; an ALLERGEN was resolved to its entry by
 * {@link DrugReference#matchesText}, the symmetric prose rule. So a patient prescribed
 * {@code Clarithromycine Co 500mg} and recorded as allergic to {@code Clarithromycine} was told
 * that simvastatin "interacts with active order clarithromycin — Major", while their own recorded
 * allergy to that same drug produced nothing at all: the module knew both facts and reported only
 * the one whose matcher happened to be tolerant.
 *
 * <p>An allergen string is a third shape, and it was never assigned a matcher — it inherited the
 * prose one. It is not prose: like an order's display name it is one localized, inflected drug name
 * read out of the dictionary in the current locale (or typed free-hand), which is why the fix gives
 * it {@link DrugReference#matchesDrugName} rather than loosening {@code matchesText}. Loosening that
 * is what issue #86 measured as wrong for prose ("advil" must not match inside a longer word), and
 * the last case here pins that prose matching did not move.
 *
 * <p><b>Both directions, measured 2026-08-05</b> over the 3.7.1 demo dictionary's 1219 allergen-
 * candidate names (locale-preferred and fully-specified names of the {@code Drug}, {@code MedSet}
 * and {@code Pharmacologic Drug Class} classes, the three allergen concept sets, and the
 * {@code Allergy to …} concepts) against the full 19MB KB's 2283 entries: the prose matcher resolved
 * 549 of those names, the drug-name matcher resolves <b>624 — 75 newly resolved, 0 lost</b>. These are
 * counts of WHETHER a name resolves, which is {@code matchesDrugName}'s question and is untouched by
 * issue #176; the count of names resolving to a different constituent of a multi-drug product, which
 * that issue does move, stood here and is removed rather than restated unmeasured. The whole
 * #86/#128/#129 kill set was
 * re-scored in the allergen direction as well: 0 of 21 nesting pairs resolve to the nested drug.
 *
 * <p>Every case runs the real pipeline: a verbatim KB slice through the real
 * {@link DdiDrugReferenceSource}, then the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}, with GP reads on
 * their no-context defaults (severity floor {@code minor}).
 */
public class AllergenNameResolutionTest {

	/** Warfarin, aspirin, clarithromycin, simvastatin and four nested-name pairs — see the fixture's
	 *  own {@code metadata.note}. Shared with {@link RuleTokenAliasOrderMatchingTest}, which pins the
	 *  order-name half of the same asymmetry. */
	static final String FIXTURE = "chartsearchai-test/ddi-alias-drug-names.json";

	private DrugSafetyValidator validator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE)));
	}

	private long contraindicationCount(List<SafetyWarning> warnings) {
		return warnings.stream()
				.filter(w -> SafetyWarning.TYPE_CONTRAINDICATION.equals(w.getType())).count();
	}

	@Test
	public void theSameLocalizedNameResolvesAsAnOrderAndAsAnAllergen() throws IOException {
		// Issue #147's headline, both halves in one patient so the asymmetry is the assertion rather
		// than the arrangement: one localized drug name, on the chart twice — as an active order and
		// as a recorded allergy. "Clarithromycine" and "Clarithromycine Co 500mg" are both real rows
		// in the 3.7.1 demo dictionary.
		DrugSafetyValidator validator = validator();
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Clarithromycine Co 500mg"), null,
				DrugReferenceTestSupport.set("Clarithromycine"), null);

		// The tolerant half, which already worked: the ORDER name resolves the rule's token, so a
		// simvastatin question reports the interaction. This is the precondition that makes the
		// absence below a defect rather than a dataset gap — the module demonstrably knows this
		// patient is on clarithromycin.
		List<SafetyWarning> onSimvastatin = validator.validate("Simvastatin could be started.",
				"Is it safe to give her simvastatin?", context);
		assertTrue(DrugReferenceTestSupport.detailContains(onSimvastatin, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "active order clarithromycin", "Major"),
				"precondition: the localized ORDER name must resolve the rule token, else there is no "
						+ "asymmetry to demonstrate, was: " + onSimvastatin);

		// The half that silently produced nothing: the same string as an ALLERGEN.
		List<SafetyWarning> onClarithromycin = validator.validate("Clarithromycin could be given.",
				"Is it safe to give her clarithromycin?", context);
		assertTrue(DrugReferenceTestSupport.detailContains(onClarithromycin,
				SafetyWarning.TYPE_CONTRAINDICATION, "Clarithromycin",
				"recorded allergy to Clarithromycin"),
				"an allergy recorded as \"Clarithromycine\" must contraindicate clarithromycin — the "
						+ "order side of this very chart already resolves that spelling, was: "
						+ onClarithromycin);
	}

	@Test
	public void theLocalizedNameIsCheckedAgainstTheChartWithNoDrugInTheQuestion() throws IOException {
		// The same asymmetry on the arm that needs no question at all — the patient's own active orders
		// against their own allergy records (issue #143) — which is where this defect does its real
		// damage: nobody has to ask about the drug for the module to notice that a prescription and an
		// allergy name the same substance, and both halves of that comparison were resolving a localized
		// name with the prose matcher. It takes BOTH fixes at once and is the only case here that
		// depends on neither the question nor the answer naming the drug: the subject comes from
		// DrugReferenceService.findForActiveOrders resolving the ORDER name, and the allergen from
		// lookupByToken resolving the ALLERGY text. Reverting either leg to the prose matcher leaves
		// this silent.
		List<SafetyWarning> warnings = validator().validate("Her current medications are listed above.",
				"What are her active medications?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Clarithromycine Co 500mg"), null,
						DrugReferenceTestSupport.set("Clarithromycine"), null));

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_CONTRAINDICATION,
				"Clarithromycin", "recorded allergy to Clarithromycin"),
				"a prescription and an allergy that name one drug in the localized spelling must be "
						+ "reported without the question naming it, was: " + warnings);
		assertEquals(1, warnings.size(),
				"and exactly once — one resolved allergen, one chip, was: " + warnings);
	}

	@Test
	public void localizedAllergenSpellingsResolveToTheirDrug() throws IOException {
		// The keep direction, over the localized spellings this dictionary actually carries. Each row
		// is {allergen as recorded, the question's drug word, the entry's display label}, and each is
		// paired with the unaccented/uninflected spelling as its own precondition, so a row cannot
		// pass by resolving nothing.
		String[][] rows = {
				{ "Clarithromycine", "clarithromycin", "Clarithromycin" },
				{ "Simvastatine", "simvastatin", "Simvastatin" },
				{ "Warfarine", "warfarin", "Warfarin" },
				{ "Aspirine", "aspirin", "Acetylsalicylic acid (aspirin)" } };
		DrugSafetyValidator validator = validator();
		for (String[] row : rows) {
			String question = "Is it safe to give her " + row[1] + "?";
			String answer = row[1] + " could be given.";
			List<SafetyWarning> unaccented = validator.validate(answer, question,
					DrugReferenceTestSupport.ctx(60, null, null, null,
							DrugReferenceTestSupport.set(row[1]), null));
			assertTrue(DrugReferenceTestSupport.detailContains(unaccented,
					SafetyWarning.TYPE_CONTRAINDICATION, row[2], "recorded allergy to " + row[2]),
					"precondition: the canonical spelling \"" + row[1] + "\" must contraindicate "
							+ row[2] + ", was: " + unaccented);

			List<SafetyWarning> localized = validator.validate(answer, question,
					DrugReferenceTestSupport.ctx(60, null, null, null,
							DrugReferenceTestSupport.set(row[0]), null));
			assertTrue(DrugReferenceTestSupport.detailContains(localized,
					SafetyWarning.TYPE_CONTRAINDICATION, row[2], "recorded allergy to " + row[2]),
					"an allergy recorded as \"" + row[0] + "\" must contraindicate " + row[2]
							+ " — an inflectional ending is a spelling of the drug, not a different "
							+ "drug, was: " + localized);
		}
	}

	@Test
	public void anAllergenIsNotResolvedToADrugNestedInsideItsName() throws IOException {
		// The kill direction. Widening the allergen matcher can only make it resolve MORE strings, so
		// it can resurrect the nested-name over-match issue #86 removed — here on the allergy arm,
		// where the consequence is a contraindication chip naming a drug the patient has no recorded
		// allergy to. Both fixture pairs assert the positive alongside: the drug nested inside the
		// longer name must still be reachable when it really is the allergen.
		String[][] rows = {
				// {allergen recorded, the nested drug the question asks about, that drug's label,
				//  an allergen string that legitimately IS that drug}
				{ "Tiotropium", "opium", "Opium", "Opium tincture" },
				{ "Hydroxyurea", "urea", "Urea", "Urea" } };
		DrugSafetyValidator validator = validator();
		for (String[] row : rows) {
			String question = "Is it safe to give her " + row[1] + "?";
			String answer = row[1] + " could be given.";
			List<SafetyWarning> nesting = validator.validate(answer, question,
					DrugReferenceTestSupport.ctx(60, null, null, null,
							DrugReferenceTestSupport.set(row[0]), null));
			assertEquals(0, contraindicationCount(nesting),
					"an allergy to \"" + row[0] + "\" must not contraindicate " + row[2]
							+ " — the nested name is a different molecule, was: " + nesting);

			List<SafetyWarning> real = validator.validate(answer, question,
					DrugReferenceTestSupport.ctx(60, null, null, null,
							DrugReferenceTestSupport.set(row[3]), null));
			assertTrue(DrugReferenceTestSupport.detailContains(real, SafetyWarning.TYPE_CONTRAINDICATION,
					row[2], "recorded allergy to " + row[2]),
					"precondition: an allergy recorded as \"" + row[3] + "\" must contraindicate "
							+ row[2] + ", else the absence above proves nothing, was: " + real);
		}
	}

	@Test
	public void proseMatchingIsNotWidenedWithTheAllergenSide() throws IOException {
		// The constraint issue #147 states: the fix must not be a relaxation of matchesText, which
		// serves question and answer PROSE and which issue #86 measured symmetric boundaries as
		// correct for. So the same string that now resolves as an allergen must still NOT resolve a
		// drug when it appears as a word of the question — that is the whole reason a second matcher
		// exists rather than one looser one. A change that widened matchesText instead would leave
		// every other case in this class green and fail here.
		DrugSafetyValidator validator = validator();
		PatientClinicalContext onOrder = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Clarithromycine Co 500mg"), null, null, null);

		List<SafetyWarning> englishQuestion = validator.validate("", "Is simvastatin safe with her medication?",
				onOrder);
		assertTrue(DrugReferenceTestSupport.detailContains(englishQuestion, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "active order clarithromycin"),
				"precondition: the question's own spelling must put simvastatin in play, was: "
						+ englishQuestion);

		List<SafetyWarning> localizedQuestion = validator.validate("",
				"La simvastatine est-elle sans danger avec ses medicaments?", onOrder);
		assertTrue(localizedQuestion.isEmpty(),
				"prose matching stays symmetric: an inflected drug word in the QUESTION resolves no "
						+ "entry, so nothing is checked — widening matchesText is not this fix, was: "
						+ localizedQuestion);
	}
}
