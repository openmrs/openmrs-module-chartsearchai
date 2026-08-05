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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The DIRECT allergy check — "they are allergic to this drug, do not give it" — for a reference
 * entry that carries no classification data at all (issue #135).
 *
 * <p><b>The defect.</b> The allergen arm of the validator resolved each recorded allergy to a
 * reference entry and asked three questions in decreasing specificity: is the allergen THIS drug,
 * does it share this drug's ATC level-4 subgroup, does it share a curated
 * {@link CrossReactivityGroup}. Only the last two need classification data, but the whole loop sat
 * behind one early return that skipped it when the drug being checked had neither a subgroup nor a
 * group — so the identity question, which is a comparison of two object references and needs no
 * ATC code at all, was never asked for such an entry.
 *
 * <p><b>How wide that is.</b> Measured over the full openmrs-ddi-knowledge-base DDInter 2.0 dataset
 * (2283 drugs, 295184 interaction rows) on 2026-08-05: <b>444 entries (19.4%) carry no ATC codes at
 * all</b>, and 0 carry ATC codes without a level-4 subgroup — so the guard's two halves fail
 * together, on nearly a fifth of the dataset. A curated cross-reactivity group cannot rescue any of
 * them either, and not merely because that file is small: group membership is defined by ATC PREFIX
 * ({@link CrossReactivityGroup#containsCode}), so an entry with no ATC code can never belong to one
 * however much curated data a deployment authors. Nor can the curated-rule arm
 * ({@code addContraindications}): {@link DdiDrugReferenceSource}'s entries expose {@code
 * interactions}, "never {@code ageBands} or {@code contraindications}". For those 444 drugs the
 * direct-allergy warning had no path to the clinician at all — no chip, and (since issue #110, which
 * turns every chip into a citable pre-answer record) nothing in the prompt either.
 *
 * <p><b>The fixture</b> is a verbatim excerpt of that dataset — {@code Ledipasvir} and {@code
 * Leucovorin}, two of the 444, plus {@code Ciprofloxacin} and {@code Levofloxacin} as a real
 * classified pair — parsed by the real {@link DdiDrugReferenceSource}. The bundled sample cannot host
 * these cases: all 16 of its drugs carry ATC codes, which is why no existing test covered a direct
 * allergy to an unclassified drug.
 *
 * <p>That pair shares <em>two</em> level-4 subgroups, not one — {@code J01MA} (J01MA02/J01MA12,
 * fluoroquinolone antibacterials) and {@code S01AE} (S01AE03/S01AE05, ophthalmic
 * fluoroquinolones) — because DDInter files both substances under several ATC codes. {@code
 * sharedClass} returns the first of the allergen's subgroups that the drug in play also carries, so
 * the case below asserting {@code (J01MA)} is pinning the dataset's own code order as well as the
 * match; a KB refresh that reordered Ciprofloxacin's {@code atc} array would report {@code S01AE}
 * with the behaviour unchanged.
 *
 * <p>The curated cross-reactivity groups are loaded in every case here, deliberately: the identity
 * chip has to appear for an entry the real curated data genuinely cannot classify, not merely for
 * one whose groups a test pinned empty.
 */
public class DirectAllergyContraindicationTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-unclassified-allergen.json";

	/** The ATC-less entry the patient is allergic to; {@code Leucovorin} is the second one. */
	private static final String UNCLASSIFIED = "Ledipasvir";

	@Test
	public void theFixtureEntriesReallyCarryNoClassificationData() throws IOException {
		// Precondition, through the real parser and the real curated groups: if a KB refresh ever gave
		// one of these entries an ATC code, the cases below would keep passing while testing the
		// CLASSIFIED path under names that say "unclassified". Asserted for BOTH ATC-less entries, not
		// only the headline one: Leucovorin is what two of those cases actually use, and DDInter omits
		// an ATC code (V03AF03, calcium folinate) that a refresh could plausibly add.
		DrugReferenceService service = fixtureService();
		for (String token : new String[] { UNCLASSIFIED, "Leucovorin" }) {
			DrugReference entry = service.lookupByToken(token);
			assertNotNull(entry, token + ": the fixture must carry an entry the allergy token resolves to");
			assertTrue(entry.normalizedAtcCodes().isEmpty(),
					token + " must carry no ATC codes, was: " + entry.normalizedAtcCodes());
			assertTrue(entry.atcSubgroups().isEmpty(), token + ": and therefore no ATC level-4 subgroup");
			assertTrue(CrossReactivityGroup.groupsOf(entry, service.getCrossReactivityGroups()).isEmpty(),
					token + ": and no curated cross-reactivity group, since group membership is by ATC prefix");
			assertTrue(entry.getContraindications().isEmpty(),
					token + ": and no curated contraindication rule, so the rule arm cannot cover it either");
		}
	}

	@Test
	public void directAllergyToAnUnclassifiedDrugRaisesOneContraindication() throws IOException {
		// THE case: the clinician asks about the very drug the chart records an allergy to. The answer
		// deliberately never names it, so this is the question-driven path — the one a clinician's
		// "is it safe to give her X?" takes, and the one the pre-answer findings pass runs.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"Her hepatitis C could be treated with a direct-acting antiviral regimen.",
				"Is it safe to give her ledipasvir?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set(UNCLASSIFIED), null));

		assertEquals(1, warnings.size(), "one recorded allergy to the asked-about drug is one chip, was: "
				+ warnings);
		SafetyWarning chip = warnings.get(0);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, chip.getType());
		assertEquals("Ledipasvir", chip.getDrug(), "the chip is labelled by displayLabel()");
		assertEquals("The patient has a recorded allergy to Ledipasvir.", chip.getDetail(),
				"the identity detail is one standalone sentence, worded as it is for a classified drug");
	}

	@Test
	public void theDirectAllergyFindingAlsoReachesThePromptAsACitableRecord() throws IOException {
		// The other half of "it reaches the clinician" (issue #110): every chip is injected as a
		// numbered, citable safety-finding record, so the answer can ground a refusal on it instead of
		// having to notice the allergy record on its own. Real injector, real validator, real fixture.
		DrugReferenceService service = fixtureService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(UNCLASSIFIED), null),
						"Is it safe to give her ledipasvir?"));

		assertEquals(1, findings.size(), "the finding must be injected exactly once, was: " + findings);
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Ledipasvir: The patient has a recorded allergy to Ledipasvir.",
				findings.get(0).getText(), "the record carries the chip's own detail verbatim");
	}

	@Test
	public void anotherUnclassifiedDrugTheyAreNotAllergicToStaysSilent() throws IOException {
		// Same patient, same allergy, a DIFFERENT ATC-less drug: identity is identity, so nothing
		// fires. The no-false-positive direction — an arm that warned on any resolved allergen, or one
		// that compared the two unclassified entries by their (equally empty) class data, fails here.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Is it safe to give her leucovorin?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set(UNCLASSIFIED), null));

		assertEquals(0, warnings.size(),
				"an unclassified drug the patient is not allergic to must raise nothing, was: " + warnings);
	}

	@Test
	public void anEarlierUnrelatedAllergenDoesNotHideTheDirectOne() throws IOException {
		// The guard is a per-allergen SKIP, not an exit — and that is the whole of its new placement.
		// The guard tests the DRUG IN PLAY, not the allergen, so with an unclassified drug in play
		// every iteration meets it; each recorded allergy is one iteration, in the chart's own order.
		// Here the unrelated allergen is listed FIRST, so it is the one that trips the guard and the
		// identity match is queued behind it. If the guard left the METHOD instead of the iteration,
		// that queued match would never be looked at and issue #135 would be reinstated for exactly
		// the patients most likely to hit it — the ones with more than one recorded drug allergy.
		//
		// The token order is therefore load-bearing and must not be "tidied": with the identity
		// allergen first its chip is already added before the guard is ever reached, and a method-exit
		// guard looks correct. Nor can the two single-allergen absence cases either side of this one
		// catch it: they pass one allergen, so nothing is ever queued. Measured through this path: 1 on
		// this build, 0 with the guard's `continue` changed to `return` (which still sits after the
		// identity check, so it reads as correct), and 0 pre-fix.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Is it safe to give her ledipasvir?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ciprofloxacin", UNCLASSIFIED), null));

		assertEquals(1, warnings.size(),
				"the allergen listed after an unrelated one must still be compared, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Ledipasvir.", warnings.get(0).getDetail());
	}

	@Test
	public void aClassifiedAllergenRaisesNothingForAnUnclassifiedDrug() throws IOException {
		// The guard's own contract, which the fix keeps: the two CLASS comparisons still need
		// classification data. A Ciprofloxacin (J01MA02) allergy says nothing about an entry carrying
		// no ATC code — there is no subgroup and no group to share — so the arm must stay silent
		// rather than warn about every allergy once the loop is reachable.
		//
		// What this pins is the REQUIREMENT, not the guard statement: with the guard deleted outright
		// the assertion still holds, because both comparisons are no-ops on empty sets (measured — the
		// whole suite stays green). The guard's placement is pinned separately, by the case above.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Is it safe to give her ledipasvir?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ciprofloxacin"), null));

		assertEquals(0, warnings.size(),
				"a classified allergen must not warn about an unclassified drug, was: " + warnings);
	}

	@Test
	public void twoAliasesOfOneUnclassifiedAllergyWarnOnce() throws IOException {
		// The seenAllergens dedup, on a path that could not run before: "Leucovorin" and "Leucovorin
		// calcium" are both aliases the dataset gives that one entry (its CIEL concept names), so two
		// allergy records resolve to one allergen and must produce ONE chip.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Is leucovorin safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Leucovorin", "Leucovorin calcium"), null));

		assertEquals(1, warnings.size(),
				"two aliases of one unclassified allergen must not double-warn, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Leucovorin.", warnings.get(0).getDetail());
	}

	@Test
	public void aClassifiedDrugStillGetsItsClassChip() throws IOException {
		// The regression control on the arm the guard protects: Levofloxacin (J01MA12) asked about with
		// a Ciprofloxacin (J01MA02) allergy still raises the shared-subgroup cross-reactivity chip, and
		// exactly one.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Can we give her levofloxacin?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ciprofloxacin"), null));

		assertEquals(1, warnings.size(), "one allergen, one chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("Levofloxacin is in the same ATC class (J01MA) as the patient's allergy to"
				+ " Ciprofloxacin — possible cross-reactivity", warnings.get(0).getDetail());
	}

	@Test
	public void identityWinsOverTheClassMatchForTheSameAllergen() throws IOException {
		// Most-specific-match-wins, on the one allergen that trips both: an allergen that IS the drug
		// asked about also shares every one of its subgroups, so without the precedence the clinician
		// would read "you are allergic to ciprofloxacin" and "ciprofloxacin is in the same ATC class as
		// your ciprofloxacin allergy" side by side.
		List<SafetyWarning> warnings = fixtureValidator().validate(
				"", "Is ciprofloxacin safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ciprofloxacin"), null));

		assertEquals(1, warnings.size(), "identity and a class match must not both fire, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Ciprofloxacin.", warnings.get(0).getDetail(),
				"and the surviving chip is the more specific one");
	}

	@Test
	public void anUnclassifiedAllergenWithASiblingRouteVariantStillWarnsOnce() throws IOException {
		// One question can put SEVERAL entries in play, because DDInter files one substance as several
		// route/formulation rows sharing an rxnorm_name — 142 such groups in the full KB, 28 of them
		// entirely ATC-less. Only one of those rows is the resolved allergen, so the others reach the
		// per-allergen loop as a DIFFERENT reference: they must fall through the in-loop classification
		// guard rather than each add a chip. Measured through this same path: 0 chips before the fix
		// (the whole arm returned early for both rows), 1 after.
		//
		// The shared route-variant slice supplies the shape: its two Iron rows (DDInter975 and
		// DDInter2187 "Iron (bisglycinate)") are the full KB's ONLY two rxnorm_name=iron entries, both
		// verbatim and both carrying no ATC code — so no new fixture is needed for this case.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.ddiFixtureEntries(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());

		// Precondition, through the production matcher the validator itself uses: the question really
		// does resolve to BOTH rows, so the case is the multi-entry one and not a single-entry retest.
		List<DrugReference> inPlay = service.findByQuery("Is it safe to give her iron?");
		assertEquals(2, inPlay.size(), "the question must put both Iron rows in play, was: " + inPlay);
		for (DrugReference entry : inPlay) {
			assertTrue(entry.normalizedAtcCodes().isEmpty(),
					entry.getName() + " must carry no ATC codes, was: " + entry.normalizedAtcCodes());
		}

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				"", "Is it safe to give her iron?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Iron"), null));

		assertEquals(1, warnings.size(),
				"a sibling route variant must not add a second chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("The patient has a recorded allergy to Iron.", warnings.get(0).getDetail(),
				"and the surviving chip is the identity one, about the row the allergy resolved to");
	}

	/** The real fixture entries behind a service carrying the real curated cross-reactivity groups. */
	private static DrugReferenceService fixtureService() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return service;
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(fixtureService());
	}
}
