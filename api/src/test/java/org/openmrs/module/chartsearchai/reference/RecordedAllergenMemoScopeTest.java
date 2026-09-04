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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The one thing issue #172's constraint must not be "improved" into: a memo of anything derived from
 * {@link DrugReferenceService#getAll()} held in a FIELD of {@link DrugSafetyValidator} rather than in a
 * per-{@code validate} local.
 *
 * <p><b>Nothing pinned it.</b> Measured 2026-08-14 on {@code 445554a0}, by moving the recorded-allergen
 * resolution out of {@code validate}'s local and onto the bean, in the two shapes such a memo is
 * actually written in:
 * <ul>
 *   <li><b>keyed on the allergy tokens</b> — the plausible one, the "optimisation" the rule exists to
 *       refuse — reddened <b>0 of the 1171</b> api tests that existed, and reddens exactly
 *       <b>1 of 1173</b> once this file's two cases do: the one below. Every
 *       other test resolves against one dataset, so nothing else can see the difference;</li>
 *   <li><b>unkeyed</b> reddened three, the two others being
 *       {@code AllergenNameResolutionTest.localizedAllergenSpellingsResolveToTheirDrug} and
 *       {@code .anAllergenIsNotResolvedToADrugNestedInsideItsName}, which happen to reuse one validator
 *       across contexts and so catch a memo that ignores the patient. Neither catches the keyed
 *       form, and neither is about this rule.</li>
 * </ul>
 * So nine comments across three classes told the next author not to do it, and the only shape they
 * would plausibly have done it in was caught by nothing: a decision everyone believed was protected,
 * protected by nothing.
 *
 * <p><b>And the reason those comments gave was false.</b> They said {@code getAll()} is hot-reloadable
 * and that a memoised {@link DrugReference} outliving a reload fails the identity comparisons the
 * contraindication arms make. There is no reload: {@code DrugReferenceService.dataset} is written in
 * {@code ensureLoaded()} when it is null and in the package-private {@code setEntries} test seam, which
 * production never calls; the class's own javadoc has said "held for the life of the bean … requires a
 * module restart" all along. Confirmed live the same day, on the running 3.7.1 standalone: with
 * {@code chartsearchai.drugReference.sourceFormat} flipped from {@code ddinter} to {@code atc} and no
 * restart, {@code GET /chartsearchai/drugreferencestatus} went on reporting {@code ddinter}/2283 and a
 * {@code /search} went on raising its Major chip, where a reload would have re-parsed the DDInter file
 * with the ATC parser, loaded 0 entries and dropped it. The reasons that do hold are enumerated in one
 * place, {@link DrugReferenceService}'s class javadoc, and are not restated here. The one that is
 * specific to THIS memo is: it has no key at all, so a field version would have to key on the patient's
 * own allergy tokens.
 *
 * <p><b>So this case pins one shape of the rule, through the one seam that can replace the entries
 * after a read.</b> {@code setEntries} is that seam. The counterpart below is
 * {@code CrossReactivityGroupsTest#replacedPrefixesAreSeenOnTheNextQuestion_soTheNormalizationIsNeverCachedOnTheInstance}
 * (issue #248), and the analogy is one of SHAPE only: {@code setAtcPrefixes} is public API that Jackson
 * writes through, while {@code setEntries} is package-private with no caller outside tests. Neither
 * staleness is reachable from production today; both cases exist to fix where the memo LIVES. A local
 * cannot outlive the call that made it, so an assertion about the SECOND {@code validate} is the only
 * observation that separates the two.
 *
 * <p>Two things to carry over before copying this arrangement. {@code setEntries} resets THREE things,
 * not one — the entries, the cross-reactivity groups (to empty) and the load status — so a case built
 * on {@code ddiFixtureService} or {@code ddinterServiceWithGroups}, which install groups after seeding,
 * would silently lose them on the second call; here both extra effects are no-ops, because
 * {@code serviceWith} had already pinned the groups empty and nothing reads the status. And the
 * second pass's count of 1 leans on the one-substance-one-chip collapse as well as on memo scope, since
 * {@code DDI_ROUTE_VARIANTS} files {@code Iron} as two rows: a regression in that collapse reddens this
 * case with a message about allergen resolution, so read the detail assertions before believing the
 * name on the tin.
 *
 * <p><b>And here is what it does NOT pin.</b> Worth saying plainly, because a rule defended by more
 * than it has is the failure this case exists to correct. What reddens here is a memo that OUTLIVES the
 * entries it was resolved from. Two shapes stay unpinned. A field REASSIGNED at the
 * top of every pass ({@code this.recordedAllergens = recordedAllergens(context)}, dropping the
 * parameter the list is threaded through) recomputes each time, so this case is green on it — and it is
 * the ordinary tidy-up, since threading the list through two call sites is the only thing keeping it a
 * local. And the first of the reasons in {@link DrugReferenceService}'s class javadoc, one
 * unsynchronized structure shared by concurrent requests, is not exercised at all: this is a
 * single-threaded functional case, and the token-keyed memo it does catch is caught because it goes
 * STALE, never because it is shared. Neither gap is closed by asserting harder here — the first needs a
 * reader of {@code validate}'s shape and the second a concurrency case that would be flaky — so they
 * are recorded rather than papered over.
 */
public class RecordedAllergenMemoScopeTest {

	/** The only property this case needs of it is that it shares no drug with
	 *  {@link DrugReferenceTestSupport#DDI_ROUTE_VARIANTS} — which
	 *  {@link #neitherDatasetCarriesTheOthersDrug} asserts rather than this comment asserting it. Two
	 *  EXISTING fixtures, deliberately: a dataset replaced by another the suite already parses is the
	 *  shape a deployment's own re-point would take. */
	private static final String UNCLASSIFIED_ALLERGEN = DrugReferenceTestSupport.DDI_UNCLASSIFIED_ALLERGEN;

	/** Both tokens are recorded on the patient for BOTH passes, so a field memo keyed on the allergy
	 *  tokens — or on the context — is caught by this case just as an unkeyed one is: what changes
	 *  between the two passes is the loaded dataset and nothing else. */
	private static final Set<String> ALLERGIES = DrugReferenceTestSupport.set("Ledipasvir", "Iron");

	/** Names one drug of each dataset, so the same string is a question about the drug the patient is
	 *  allergic to whichever dataset is in force. Neither dataset carries the other's drug. */
	private static final String QUESTION = "Is it safe to give her ledipasvir with iron?";

	@Test
	public void replacedEntriesAreSeenOnTheNextValidate_soTheAllergenResolutionIsNeverCachedOnTheValidator()
			throws IOException {
		DrugReferenceService service = service(UNCLASSIFIED_ALLERGEN);
		// ONE validator across both passes — the singleton a Spring context would hand every request,
		// and the only arrangement on which a field could be observed at all.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				ALLERGIES, null);

		List<SafetyWarning> beforeReplacement = validator.validate("", QUESTION, context);

		assertEquals(1, beforeReplacement.size(),
				"the first dataset carries exactly one of the two recorded allergens, and the question "
						+ "names it, so it is one chip: " + beforeReplacement);
		assertEquals("The patient has a recorded allergy to Ledipasvir.",
				beforeReplacement.get(0).getDetail(),
				"and the chip is about the substance THAT dataset files");

		service.setEntries(DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS));

		List<SafetyWarning> afterReplacement = validator.validate("", QUESTION, context);

		// Where a field memo fails, and why the assertion has to be on the SECOND pass's chip rather
		// than on its count alone: the resolution memoised in the first pass holds the first dataset's
		// Ledipasvir rows, and the drug the second pass puts in play is a row of the second dataset, so
		// the allergen arm compares two substances that were never the same one and raises nothing.
		assertEquals(1, afterReplacement.size(),
				"the allergen must be re-resolved against the entries in force NOW — a resolution "
						+ "memoised on the validator answers with the replaced-away dataset's rows, which "
						+ "no drug in play can match: " + afterReplacement);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, afterReplacement.get(0).getType());
		assertEquals("The patient has a recorded allergy to Iron.", afterReplacement.get(0).getDetail(),
				"and the chip names the substance the dataset in force files, not the one it replaced");
	}

	@Test
	public void neitherDatasetCarriesTheOthersDrug() throws IOException {
		// The precondition that makes the case above discriminate. If a fixture edit ever gave one
		// dataset the other's drug, both passes would chip for the same substance and the case would go
		// on passing while testing nothing: the stale memo would still match. Asked through the
		// production accessor (issue #193/#195's entry point, which is what recordedAllergens itself
		// calls) rather than by reading the fixture files.
		DrugReferenceService unclassified = service(UNCLASSIFIED_ALLERGEN);
		DrugReferenceService routeVariants = service(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);

		assertTrue(unclassified.findImpliedSubstances("Iron").isEmpty(),
				"the first dataset must not name Iron, or the first pass would already chip for it");
		assertTrue(routeVariants.findImpliedSubstances("Ledipasvir").isEmpty(),
				"the second dataset must not name Ledipasvir, or a stale memo would still match");
		assertTrue(!unclassified.findImpliedSubstances("Ledipasvir").isEmpty()
				&& !routeVariants.findImpliedSubstances("Iron").isEmpty(),
				"and each dataset must name its own, or neither pass chips at all");
	}

	/** A service over one of the two fixtures, parsed by the real {@link DdiDrugReferenceSource}. The
	 *  {@code setEntries} seam underneath {@code serviceWith} pins the cross-reactivity groups EMPTY,
	 *  which is what this case wants: an empty group set is one fewer way for the second pass to raise a
	 *  chip, so the one it does raise can only be the identity match being re-resolved. */
	private static DrugReferenceService service(String fixture) throws IOException {
		return DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(fixture));
	}
}
