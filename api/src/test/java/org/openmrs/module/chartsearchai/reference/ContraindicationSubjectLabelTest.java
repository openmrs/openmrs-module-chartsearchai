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
 * <p>Issue #205 widened that: before it, the two arms disagreed only for the shipped families whose
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
		// recorded finding. A subject resolved per substance while the ledger still keyed per ROW answers
		// this with FOUR, two of them word-for-word repeats of the other two, which is duplicating the
		// chips rather than renaming them. Measured by mutation: keying ContraindicationChips on the
		// subject ROW rather than on its substanceGroupKey fails this at 4.
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
