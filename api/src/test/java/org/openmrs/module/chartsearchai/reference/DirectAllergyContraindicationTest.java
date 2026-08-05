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
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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
 * Leucovorin}, two of the 444, plus {@code Ciprofloxacin} (J01MA02) and {@code Levofloxacin}
 * (J01MA12) as a real same-subgroup pair — parsed by the real {@link DdiDrugReferenceSource}. The
 * bundled sample cannot host these cases: all 16 of its drugs carry ATC codes, which is why no
 * existing test covered a direct allergy to an unclassified drug.
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
	public void theFixtureEntryReallyCarriesNoClassificationData() throws IOException {
		// Precondition, through the real parser and the real curated groups: if a KB refresh ever gave
		// Ledipasvir an ATC code, every case below would keep passing while testing nothing.
		DrugReferenceService service = fixtureService();
		DrugReference ledipasvir = service.lookupByToken(UNCLASSIFIED);
		assertTrue(ledipasvir.normalizedAtcCodes().isEmpty(),
				"the fixture entry must carry no ATC codes, was: " + ledipasvir.normalizedAtcCodes());
		assertTrue(ledipasvir.atcSubgroups().isEmpty(), "and therefore no ATC level-4 subgroup");
		assertTrue(CrossReactivityGroup.groupsOf(ledipasvir, service.getCrossReactivityGroups()).isEmpty(),
				"and no curated cross-reactivity group, since group membership is by ATC prefix");
		assertTrue(ledipasvir.getContraindications().isEmpty(),
				"and no curated contraindication rule, so the rule arm cannot cover it either");
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

		List<RecordMapping> findings = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set(UNCLASSIFIED), null),
				"Is it safe to give her ledipasvir?")
				.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType()))
				.collect(java.util.stream.Collectors.toList()); // Stream.toList() is Java 16+; target is 11

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
	public void aClassifiedAllergenRaisesNothingForAnUnclassifiedDrug() throws IOException {
		// The guard's own contract, which the fix keeps: the two CLASS comparisons still need
		// classification data. A Ciprofloxacin (J01MA02) allergy says nothing about an entry carrying
		// no ATC code — there is no subgroup and no group to share — so the arm must stay silent
		// rather than warn about every allergy once the loop is reachable.
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
