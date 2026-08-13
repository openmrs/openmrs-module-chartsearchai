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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * One substance is named ONE way in one response, whichever arm names it (issue #206).
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.ContraindicationChips} broke its representative tie
 * POSITIONALLY — the first row of the substance to reach the arm kept the chip, and named it — while
 * the interaction and dose arms name their subject through
 * {@code DrugSafetyValidator.interactionSubject}: the row the patient's own record claims most
 * strongly ({@link DrugReference#nameMatchStrength}), then {@link DrugReference#canonicalRow} among
 * the rows tied on that. So a substance whose route-unspecified row is not the dataset's first got an
 * interaction chip calling it {@code Chloroprocaine} beside a contraindication chip calling it
 * {@code Chloroprocaine (ophthalmic)} — one substance, two names, in one response.
 *
 * <p>Issue #194 widened that: before it, the two arms disagreed only for the shipped families whose
 * unqualified row is not the dataset's first; after it, they disagree wherever the CHART names a row
 * that is neither, which is a property of the patient's data rather than of the knowledge base.
 *
 * <p><b>What is asserted, and why it is not two chip strings.</b> The property is that the two arms
 * AGREE, so the cases below compare the arms' answers to each other rather than pinning two literals
 * that could both move and still disagree. The value is asserted beside it, so a fix that made both
 * arms wrong in the same way is still caught.
 *
 * <p><b>The identity chip is deliberately outside this.</b> "The patient has a recorded allergy to X"
 * names X after the ALLERGEN the chart records (issue #164), and naming the charted row is what makes a
 * finding truthful — folding it onto the canonical row renames a charted {@code Ketorolac
 * (ophthalmic)} allergy, which is the regression issue #187 settled and #192 re-measured. So these
 * cases use an allergy to a DIFFERENT, class-related substance, which is the shape the subject label
 * actually decides.
 *
 * <p>Every case drives the real {@link DrugSafetyValidator#validate} over a verbatim KB slice parsed by
 * the real {@link DdiDrugReferenceSource}. Nothing here calls an internal or hand-builds a
 * {@link DrugReference}.
 */
public class ContraindicationSubjectLabelTest {

	/** Verbatim KB rows, in KB order: the two {@code chloroprocaine} rows the KB files
	 *  route-qualified-first, {@code Lorazepam} as the interaction partner (its rule sits on the
	 *  unqualified row only) and {@code Procaine} and {@code Tetracaine} as two class-related
	 *  allergens. */
	private static final String FIXTURE = "chartsearchai-test/ddi-contraindication-subject-label.json";

	/** A question that resolves no reference drug and is not an interaction screen, so the only arm that
	 *  can chip is the order-driven one ({@code addActiveOrderContraindications}). */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	private static final String QUESTION = "Is it safe to give chloroprocaine?";

	@Test
	public void theFixtureReallyFilesTheUnqualifiedRowSecond() throws IOException {
		// The premise, through the production predicates: without it every case below would pass on a
		// family whose first row IS its canonical one, i.e. while testing nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<DrugReference> rows = service.findImpliedByQuery(QUESTION);

		assertEquals(2, rows.size(), "one question word must put both rows in play, was: "
				+ DrugReferenceTestSupport.names(rows));
		assertEquals(rows.get(0).substanceGroupKey(), rows.get(1).substanceGroupKey(),
				"and they must be ONE substance, or there is no representative to choose");
		assertEquals("Chloroprocaine (ophthalmic)", rows.get(0).getName(),
				"the ROUTE-QUALIFIED row comes first, so a positional tie-break names the chip after it");
		assertFalse(rows.get(0).namesNoRoute(), "which is not the row that names the substance");
		assertTrue(rows.get(1).namesNoRoute(), "and the second one is");
		assertSame(rows.get(1), DrugReference.canonicalRow(rows),
				"so dataset order and canonicalRow genuinely disagree on this family");
	}

	@Test
	public void bothArmsNameOneSubstanceOneWay() throws IOException {
		// THE case. One response carrying an interaction chip and a contraindication chip about the SAME
		// substance: the patient is on lorazepam (which chloroprocaine interacts with) and allergic to
		// procaine (which shares ATC subgroup N01BA with it), and the question names chloroprocaine.
		List<SafetyWarning> warnings = fixtureValidator().validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Lorazepam"), null,
						DrugReferenceTestSupport.set("Procaine"), null));

		assertEquals(2, warnings.size(), "one interaction chip and one contraindication chip, was: "
				+ warnings);
		assertEquals(1, subjects(warnings).size(),
				"one substance must be named ONE way in one response, was: " + subjects(warnings)
						+ " in " + warnings);
		assertEquals("[Chloroprocaine]", subjects(warnings).toString(),
				"and the name is the resolved subject's, not the dataset-first row's");
	}

	@Test
	public void twoFindingsAboutOneSubjectStayTwoChips() throws IOException {
		// The ledger key and the subject choice have to move together. Two rows of one substance are in
		// play and TWO recorded allergies are class-related to it, so two chips are correct — one per
		// recorded finding. Measured by mutation: resolving the chip's LABEL from the subject while still
		// handing the ledger the RAISING ROW, and keying the ledger on that row, fails this at FOUR —
		// `Chloroprocaine … allergy to Procaine` and `… to Tetracaine`, each twice, word for word. That is
		// duplicating the chips rather than renaming them, which is what "a more precise resolver without
		// a matching ledger key" costs.
		//
		// Keying the ledger on the resolved subject ROW alone is NOT that mutation. While every subject
		// reaching the ledger came from the resolver it was an EQUIVALENT one — measured green — because
		// the resolver answers with one row per substance. It stopped being equivalent when the identity
		// branch began handing the ledger the ALLERGEN row on purpose: re-measured, it now fails
		// AllergenExactNameResolutionTest.twoRecordedAllergiesToOneSubstanceStillRaiseOneChipPerSubject,
		// because two allergy records resolving two rows of one substance then key apart. So
		// substanceGroupKey is a requirement, not a statement of intent — see ContraindicationChips.add.
		List<SafetyWarning> warnings = fixtureValidator().validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Procaine", "Tetracaine"), null));

		assertEquals(2, warnings.size(), "two recorded findings about one subject are two chips, was: "
				+ warnings);
		assertEquals(1, subjects(warnings).size(),
				"both named for the one subject, was: " + subjects(warnings));
		assertEquals("Chloroprocaine is in the same ATC class (N01BA) as the patient's allergy to"
				+ " Procaine — possible cross-reactivity", warnings.get(0).getDetail());
		assertEquals("Chloroprocaine is in the same ATC class (N01BA) as the patient's allergy to"
				+ " Tetracaine — possible cross-reactivity", warnings.get(1).getDetail(),
				"two findings, two chips, and neither is the other repeated");
	}

	@Test
	public void theIdentityChipStillNamesTheRecordedAllergen() throws IOException {
		// The exemption, pinned where it is explained — and pinned as the case where it is VISIBLE, two
		// chips about one substance carrying two names, both correct. The patient is recorded as allergic
		// to the ophthalmic presentation, so the sentence quoting that RECORD has to keep saying so
		// (issue #164; folding it is the #187 regression), while the class chip asserts something about
		// the drug being CHECKED and takes the shared subject. Live shape: Kevin Brown, whose chart
		// records exactly this.
		//
		// Measured by mutation: subjecting the identity chip too fails this and four existing cases.
		List<SafetyWarning> warnings = fixtureValidator().validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Chloroprocaine (ophthalmic)", "Procaine"), null));

		assertEquals(2, warnings.size(), "two recorded findings, two chips, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Chloroprocaine (ophthalmic).",
				warnings.get(0).getDetail(),
				"the identity chip names the row the CHART records, was: " + warnings);
		assertEquals("Chloroprocaine is in the same ATC class (N01BA) as the patient's allergy to"
				+ " Procaine — possible cross-reactivity", warnings.get(1).getDetail(),
				"while the class chip names the substance's shared subject, was: " + warnings);
	}

	@Test
	public void theOrderDrivenArmNamesTheSubstanceTheSameWay() throws IOException {
		// The other call site (issue #143's), which the question-driven arm cannot reach: the substance is
		// resolved from the patient's own ORDER and the question names no drug at all. It needs the rows of
		// a substance that is not in play at all to be groupable, which the drug-in-play arm's row groups
		// never carry.
		List<SafetyWarning> warnings = fixtureValidator().validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Chloroprocaine Injection 1%"), null,
						DrugReferenceTestSupport.set("Procaine"), null));

		assertEquals(1, warnings.size(), "one substance, one finding, one chip, was: " + warnings);
		assertEquals("Chloroprocaine is in the same ATC class (N01BA) as the patient's allergy to"
				+ " Procaine — possible cross-reactivity", warnings.get(0).getDetail(),
				"named by the resolved subject on the order-driven arm too, was: " + warnings);
	}

	@Test
	public void aChartedRouteVariantIsNotRenamedByTheFold() throws IOException {
		// The anchor is the CHART first and the fold only among the rows tied on it — issue #187's
		// constraint, which is what separates this fix from "call canonicalRow at the chip site". The
		// order records the ophthalmic row by its own display name, so that is the row this response is
		// about and the chip must keep saying so. Measured by mutation: replacing the resolution with
		// DrugReference.canonicalRow alone fails this at "Chloroprocaine", while every other case here
		// still passes.
		List<SafetyWarning> warnings = fixtureValidator().validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Chloroprocaine (ophthalmic)"), null,
						DrugReferenceTestSupport.set("Procaine"), null));

		assertEquals(1, warnings.size(), "still one chip for the one substance, was: " + warnings);
		assertEquals("Chloroprocaine (ophthalmic) is in the same ATC class (N01BA) as the patient's"
				+ " allergy to Procaine — possible cross-reactivity", warnings.get(0).getDetail(),
				"the charted row names the chip, was: " + warnings);
	}

	/** Three rows of one substance, the two rule shapes that do NOT name their own entry authored on the
	 *  route-qualified one. Curated schema, so it is parsed by {@link JsonDrugReferenceSource}. */
	private static final String CLASS_RULE_ON_A_LATER_ROW =
			"chartsearchai-test/drug-reference-class-rule-on-a-later-row.json";

	@Test
	public void aCuratedRuleOnALaterRowIsReportedUnderTheSubstancesName() throws IOException {
		// The curated arm's OTHER two rule shapes, which no fixture reached: a rule whose token names a
		// CLASS rather than this entry, and a CONDITION rule. Both stay in the rule arm's own key space —
		// they are not selfNamedAllergyRule, so they never fold onto the allergen arm's chip — and both
		// are renamed by the same line as the class chips. SelfNamedAllergyRuleFoldTest's fixtures cannot
		// cover them: every rule there names its own entry.
		//
		// It is also the case that shows what the rename COSTS, stated rather than hidden: the rules are
		// authored on `Ibuprofen (tablets)` alone and are now asserted about the substance. That is the
		// trade issue #162 already took for an interaction rule, and addContraindications records why
		// exempting a curated rule instead re-creates issue #206 inside this arm.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.fixtureEntries(CLASS_RULE_ON_A_LATER_ROW));
		List<DrugReference> rows = service.findByQuery("Is ibuprofen safe for her?");
		assertEquals(3, rows.size(), "precondition: one word must resolve all three rows, was: "
				+ DrugReferenceTestSupport.names(rows));
		assertEquals("Ibuprofen (tablets)", rows.get(2).getName(),
				"precondition: and the rules must sit on a row that is not the subject");
		assertTrue(rows.get(0).getContraindications().isEmpty(),
				"precondition: while the row the chip is named after carries none");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is ibuprofen safe for her?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("nsaid"),
						DrugReferenceTestSupport.set("peptic ulcer")));

		assertEquals(2, warnings.size(), "one allergy rule and one condition rule, was: " + warnings);
		assertEquals(1, subjects(warnings).size(),
				"both named for the one subject, was: " + subjects(warnings));
		assertEquals("Ibuprofen is contraindicated by an active allergy: NSAID hypersensitivity",
				warnings.get(0).getDetail(),
				"a class-token allergy rule is reported under the substance's name, was: " + warnings);
		assertEquals("Ibuprofen is contraindicated by an active condition: active peptic ulcer disease",
				warnings.get(1).getDetail(),
				"and so is a condition rule, which no other case here reaches, was: " + warnings);
	}

	/** @return the distinct subjects the chips name, in the order they first appear — every chip here is
	 *          about the one substance under test, so a size above 1 IS the defect. */
	private static Set<String> subjects(List<SafetyWarning> warnings) {
		Set<String> named = new LinkedHashSet<String>();
		for (SafetyWarning warning : warnings) {
			named.add(warning.getDrug());
		}
		return named;
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
