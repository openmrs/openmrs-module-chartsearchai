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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #260 — the dose arm located a substance's name in the answer with {@link String#indexOf}, so it
 * disagreed with the module's own rule for what prose NAMES about the very clause it had just gated on.
 *
 * <p><b>Where the two rules meet.</b> {@code DrugSafetyValidator.attributedDoses} gates each clause on
 * {@link DrugReference#matchesText} — the PROSE rule, symmetric word boundaries and a diacritic fold,
 * which CLAUDE.md's matching bullet makes the rule for a question, an answer or a rendered record. It then
 * asks WHERE that name sits relative to the {@code N mg}, because a dose belongs to the drug named nearest
 * it and any OTHER substance's name sitting strictly closer takes it away. The gate used the module's rule
 * and the locator used raw substring search, and they answer differently in both directions:
 * <ul>
 *   <li>a name the prose rule does NOT find is found by {@code indexOf} — {@code penicillin} inside the
 *       plural {@code penicillins} — so an entry the clause does not name can veto a real dose;</li>
 *   <li>a name the prose rule DOES find is not found by {@code indexOf} — {@code paracetamol} written
 *       {@code paracétamol} — so a substance the clause names cannot locate itself and its own dose is
 *       never attributed to it.</li>
 * </ul>
 * Both are the SILENT direction: no chip, no log line, nothing separating "within the ceiling" from "never
 * compared against it". That is the direction {@code DoseCeilingBySubstanceTest} was written for one layer
 * over, and the reason this is a defect rather than a rough edge.
 *
 * <p><b>What the issue predicted and what the construction found.</b> Issue #260 predicted the symptom as
 * a nested name CLAIMING another substance's dose, with {@code estrone} inside {@code estrone sulfate} as
 * the example. That example is not this defect: {@code estrone} is a whole word inside {@code estrone
 * sulfate}, so the prose rule finds it exactly where {@code indexOf} does and the boundary rule changes
 * nothing about it. The blocking direction above is the one the raw locator actually produces, and it is
 * what these cases pin. The nested-name shape is real for a different reason and is issue #270.
 *
 * <p><b>And one case here is not about the boundary at all.</b>
 * {@link #aSubstanceNamedTwiceInOneClauseIsLocatedByItsNearestMention} pins the scan over an alias's
 * LATER occurrences — the thing the shared rule's {@code from} parameter exists for. It is here because
 * that parameter arrived with this fix, and because a mutation run during hardening found the scan pinned
 * by nothing: stopping at the first occurrence left the whole api suite green.
 *
 * <p>Hand-authored fixture, and not for convenience: a dose warning needs {@code ageBands} and the
 * grouping needs {@code substanceName}, and no bundled dataset carries both — which
 * {@code DoseCeilingAttributionTest.noShippedConfigurationCanReachTheAttributionAtAll} asserts over the
 * seed rather than argues. Every case runs the REAL production path: the fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, real question and answer strings.
 */
public class DoseAliasBoundaryTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-dose-alias-boundary.json";

	private static final String QUESTION = "Is paracetamol safe for her?";

	/** 1500 mg four times daily is 6000 mg/day against the fixture's published 4000, so every case below
	 *  states a dose that MUST be warned about once it reaches the substance it belongs to. The rival is
	 *  named in the same clause, between the drug's own name and the dose, which is the only arrangement
	 *  in which a rival's position can decide anything: the veto compares distance to the {@code mg}. */
	private static final String NO_RIVAL =
			"Paracetamol is a reasonable choice given her history, so 1500 mg four times daily is appropriate.";

	private static final String RIVAL_AS_A_SUBSTRING =
			"Paracetamol is a reasonable choice given her reaction to penicillins, so 1500 mg four times "
					+ "daily is appropriate.";

	private static final String RIVAL_AS_A_WORD =
			"Paracetamol is a reasonable choice given her reaction to penicillin, so 1500 mg four times "
					+ "daily is appropriate.";

	private static final String ACCENTED_NAME =
			"Paracétamol is a reasonable choice given her history, so 1500 mg four times daily is appropriate.";

	/** The subject named TWICE in one clause: once at the head, 168 characters from the dose and so
	 *  outside {@code DrugSafetyValidator.MAX_ALIAS_TO_DOSE_DISTANCE}, and once immediately before it.
	 *  Commas only, so this really is one clause — {@code CLAUSE_DELIMITER} splits on {@code . ; ! ? \n}
	 *  and nothing else. */
	private static final String NAMED_TWICE =
			"Paracetamol is generally the first choice for her because it is gentler on the stomach than "
					+ "the alternatives and does not interact with any of her other medicines, so paracetamol "
					+ "1500 mg four times daily is appropriate.";

	/** The same clause with the near mention removed, so the head mention (156 characters out) is the only
	 *  one. It is what keeps the case above from going vacuous: the pair discriminates only while the head
	 *  mention is OUTSIDE the attribution window, and that window is a private constant a test cannot read.
	 *  Widen it past this distance and this case reddens, which is the notice that the pair needs
	 *  re-tuning — rather than the case above quietly passing for the wrong reason. */
	private static final String FAR_MENTION_ONLY =
			"Paracetamol is generally the first choice for her because it is gentler on the stomach than "
					+ "the alternatives and does not interact with any of her other medicines, so "
					+ "1500 mg four times daily is appropriate.";

	private static List<SafetyWarning> validate(String answer) throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		return DrugReferenceTestSupport.validator(service).validate(answer, QUESTION,
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null));
	}

	@Test
	public void theProseRuleAndTheDoseLocatorMustAgreeAboutWhatEachClauseNames() throws Exception {
		// The premises, through the production predicates, so that no case below can pass on a fixture or a
		// wording where the two rules happen to agree — which would make the whole defect unexpressible
		// while every assertion still went green.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(FIXTURE);
		DrugReference paracetamol = DrugReferenceTestSupport.row(entries, "Paracetamol");
		DrugReference penicillin = DrugReferenceTestSupport.row(entries, "Penicillin");

		assertFalse(paracetamol.substanceGroupKey().equals(penicillin.substanceGroupKey()),
				"precondition: the two rows must be different SUBSTANCES, or the veto excludes the rival "
						+ "as a sibling and the wording decides nothing");
		assertFalse(penicillin.matchesText(RIVAL_AS_A_SUBSTRING.toLowerCase()),
				"precondition: the module's prose rule must say this clause does NOT name the rival — the "
						+ "plural is the whole point, and a rule that reached it would make the case vacuous");
		assertTrue(penicillin.matchesText(RIVAL_AS_A_WORD.toLowerCase()),
				"precondition: and that it DOES name it in the singular, which is the bound below");
		assertTrue(paracetamol.matchesText(ACCENTED_NAME.toLowerCase()),
				"precondition: the prose rule folds diacritics, so it says the accented clause names the "
						+ "subject — the locator disagreeing with that is the second half of this issue");
	}

	@Test
	public void aDoseOverThePublishedCeilingWarnsWhenNoRivalIsNamed() throws Exception {
		// The control, and the one case that passes before the fix as well: it is what makes every other
		// case below a statement about the RIVAL's wording rather than about the fixture, the ceiling or
		// the dose parser. Delete it and "no warning" becomes indistinguishable from "this dose never
		// warned in the first place".
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(validate(NO_RIVAL), "Paracetamol"),
				"6000 mg/day against a published 4000 must warn");
	}

	@Test
	public void aNameOnlyASubstringSearchFindsDoesNotTakeTheDose() throws Exception {
		// The defect. The clause is the control's, plus "penicillins" between the subject's own name and
		// the dose. The module's prose rule says that clause does not name Penicillin at all (asserted
		// above), yet the raw locator placed it six characters from the "1500 mg" — nearer than
		// "Paracetamol" at the head of the sentence — and the veto dropped the dose. The warning simply
		// disappeared: an answer recommending twice the published daily maximum, and nothing said so.
		assertEquals(1,
				DrugReferenceTestSupport.overdoseCount(validate(RIVAL_AS_A_SUBSTRING), "Paracetamol"),
				"an entry the clause does not name must not take the dose away from one it does");
	}

	@Test
	public void aNameTheProseRuleDoesFindStillTakesTheDose() throws Exception {
		// The bound, and the reason the fix is a change of RULE rather than a removal of the veto. The
		// same sentence with the singular does name the rival, it sits nearer the dose than the subject
		// does, and the arm's contract is that the nearest drug name owns the number — so this dose is not
		// attributed to Paracetamol and the missing warning here is the conservative, documented answer
		// (see DrugSafetyValidator.substanceOwnsDose). This case passes before the fix too, and is here
		// for that reason: without it, deleting the veto outright turns the case above green.
		assertEquals(0, DrugReferenceTestSupport.overdoseCount(validate(RIVAL_AS_A_WORD), "Paracetamol"),
				"a rival the clause really does name, sitting closer to the dose, still takes it");
	}

	@Test
	public void aSubstanceNamedTwiceInOneClauseIsLocatedByItsNearestMention() throws Exception {
		// The scan over an alias's LATER occurrences, which nothing else reaches. Measured 2026-08-14 by
		// mutation: making the locator stop at the first occurrence left all 1199 api tests green, so this
		// arm's "nearest" was a promise no case collected on — and it is a promise the boundary rule makes
		// more load-bearing, not less, because the rule that decides an occurrence counts is now the same
		// rule that decides the clause names the drug at all. Here the head mention sits 168 characters
		// from the "1500 mg", past the 120-character attribution window, and the second sits against it:
		// first-occurrence-only reads the substance as too far away and drops a 6000 mg/day statement in
		// silence.
		assertEquals(0,
				DrugReferenceTestSupport.overdoseCount(validate(FAR_MENTION_ONLY), "Paracetamol"),
				"premise, through the production path rather than by arithmetic: the head mention alone is "
						+ "too far from the dose to attribute it, which is what makes the next assertion a "
						+ "statement about the SECOND mention");
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(validate(NAMED_TWICE), "Paracetamol"),
				"the nearest mention of the drug's own name is the one that locates it");
	}

	@Test
	public void aSubjectTheClauseNamesWithAnAccentStillOwnsItsOwnDose() throws Exception {
		// The same disagreement read the other way, and the sharper half: here the locator cannot find a
		// name the gate has already accepted, so the subject fails to locate ITSELF and its own dose is
		// never attributed to anything. An answer that writes the drug the way a localized dictionary
		// does — the shape issues #129/#147 exist for — silently lost its overdose check.
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(validate(ACCENTED_NAME), "Paracetamol"),
				"a substance the clause names must be able to locate itself however that name is spelled");
	}
}
