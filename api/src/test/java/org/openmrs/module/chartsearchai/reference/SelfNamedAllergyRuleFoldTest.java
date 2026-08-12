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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * One recorded allergy is ONE chip, whichever of the two contraindication arms can see it (issue
 * #146).
 *
 * <p><b>The defect.</b> On the <em>shipped default</em> {@code sourceFormat=json}, an allergy to a
 * drug the curated file also carries a self-named allergy rule for produced two chips for one fact:
 * <pre>
 * Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy
 * The patient has a recorded allergy to Ibuprofen.
 * </pre>
 * {@link DrugSafetyValidator}'s curated arm walked the entry's {@code contraindications} and fired on
 * {@code {"type":"allergy","token":"ibuprofen"}}; the allergen arm resolved the same allergy to the
 * same entry and fired the identity chip. The two used different ledger keys and so could not see
 * each other. {@link #theShippedCuratedFileCarriesThatShapeOnThreeOfItsFourEntries} measures how much
 * of the shipped file has it, through production predicates rather than by reading the file.
 *
 * <p><b>The fix, and what it keys on.</b> A curated ALLERGY rule whose token names the very entry it
 * is filed against reports the allergen arm's fact, so it is keyed the way that arm keys it — on the
 * subject's own substance — and the two collapse in {@code ContraindicationChips}, the ledger that
 * already collapses issue #145's route variants. It is the FACT that is keyed, never "both arms
 * fired": a rule naming something else (a class token like {@code nsaid}, or another substance) keeps
 * its own chip beside the folded one, which
 * {@link #aClassLevelRuleKeepsItsOwnChipBesideTheFoldedOne} pins.
 *
 * <p><b>Which chip survives is decided by CONTENT</b>, not by a fixed arm-yields-to-arm precedence —
 * issue #88's lesson, that the yielding arm can be the one carrying something. The curated rule
 * carries an operator-authored note the identity sentence cannot reproduce, so it outranks identity
 * while it has one; with a blank note it renders its own token back
 * ({@code ChartSearchAiUtils.firstNonBlank}) and says strictly less than the identity sentence, so it
 * ranks below and the identity wording survives instead.
 *
 * <p>Every case drives the real {@code validate} (and, for the prompt half, the real injector wired to
 * the real validator) over a dataset parsed by the real production parser. Nothing here calls an
 * internal or hand-builds a {@link DrugReference}.
 */
public class SelfNamedAllergyRuleFoldTest {

	/** A curated fixture carrying the self-named-rule shapes the shipped file does not — one entry per
	 *  shape, each described in the fixture's own {@code description} field. Shared with
	 *  {@link InjectedContraindicationClauseTest}, which asks the same collapse question of the
	 *  injected record instead of the chip. */
	static final String SELF_NAMED_SHAPES =
			"chartsearchai-test/drug-reference-self-named-rule-shapes.json";

	/** Three rows of ONE substance, the rule-bearing ones FIRST — issue #145's shape, so the two
	 *  collapses meet on it. Shared with {@code DrugReferenceValidityContextTest}, which asserts the
	 *  loader reports nothing about it. */
	private static final String SUBSTANCE_DECLARED =
			"chartsearchai-test/drug-reference-substance-name-declared.json";

	/** The same substance with the rule on a LATER row, so the identity chip is raised first and the
	 *  curated one has to replace it rather than decline. */
	private static final String RULE_ON_A_LATER_ROW =
			"chartsearchai-test/drug-reference-rule-on-a-later-row.json";

	private static DrugReferenceService fixtureService() throws IOException {
		return DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(SELF_NAMED_SHAPES));
	}

	@Test
	public void theShippedCuratedFileCarriesThatShapeOnThreeOfItsFourEntries() {
		// The issue's own table, re-measured here through the PRODUCTION predicate rather than by
		// reading the JSON: CLAUDE.md forbids quoting a knowledge-base figure a reimplementation
		// produced, and this figure is quoted in four places (ContraindicationChips' javadoc, ADR
		// Decision 30, config.xml and the README). So it asks DrugSafetyValidator.selfNamedAllergyRule
		// itself — the same call the arm makes — over entries the real loader loaded.
		//
		// It is also the reachability statement for this whole class: without it, every case below could
		// be about a shape no shipped deployment ever loads.
		DrugReferenceService bundled = DrugReferenceTestSupport.bundledService();
		List<String> selfNamed = new ArrayList<String>();
		for (DrugReference entry : bundled.getAll()) {
			for (DrugReference.Contraindication rule : entry.getContraindications()) {
				if (DrugSafetyValidator.selfNamedAllergyRule(entry, rule)) {
					selfNamed.add(entry.displayLabel());
				}
			}
		}

		assertEquals(4, bundled.getAll().size(), "the shipped curated file's entry count");
		assertEquals("[Ibuprofen, Paracetamol, Amoxicillin]", selfNamed.toString(),
				"three of the four shipped entries carry an allergy rule naming themselves");
		DrugReference gentamicin = bundled.lookupByToken("gentamicin");
		assertNotNull(gentamicin, "precondition: the control entry must resolve");
		assertFalse(selfNamed.contains(gentamicin.displayLabel()),
				"and Gentamicin is the control — its allergy rule names a CLASS, not itself");
	}

	@Test
	public void anAllergyToTheDrugItselfIsOneChipAndKeepsTheCuratedNote() {
		// THE case, on the shipped default sourceFormat=json. Pre-fix: 2 chips, one per arm.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService())
				.validate("", "Is it safe to give her ibuprofen?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("ibuprofen"), null));

		assertEquals(1, warnings.size(),
				"one recorded allergy to the asked-about drug is one chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType(),
				"a contraindication, not an interaction, was: " + warnings);
		assertEquals("Ibuprofen", warnings.get(0).getDrug(),
				"labelled by the subject's displayLabel(), was: " + warnings);
		assertEquals("Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy",
				warnings.get(0).getDetail(),
				"and the surviving chip is the one carrying the deployment's own wording — a fold that "
						+ "kept the identity sentence would drop the operator's note, was: " + warnings);
	}

	@Test
	public void theFoldedChipReachesThePromptAsOneCitableRecord() {
		// The other half of a chip (issue #110): every chip is injected as a numbered, citable
		// safety-finding record, so two chips for one fact were also two near-identical records in the
		// context window. Real injector wired to the real validator, so the record count follows the
		// chips rather than being asserted separately.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("ibuprofen"), null),
						"Is it safe to give her ibuprofen?"));

		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		assertEquals(DrugReferenceInjector.FINDING_PREFIX + "Ibuprofen: Ibuprofen is contraindicated "
				+ "by an active allergy: documented ibuprofen allergy", findings.get(0).getText(),
				"carrying the surviving chip's own detail verbatim");
	}

	@Test
	public void aDrugWithNoSelfNamedRuleStillRaisesItsIdentityChip() {
		// THE CONTROL, and the case that separates this fix from deleting an arm: Gentamicin's only
		// allergy rule names a CLASS, so the curated arm cannot fire on a gentamicin allergy and the
		// identity arm is the only thing that can chip at all. It was one chip before and must stay one.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService())
				.validate("", "Is it safe to give her gentamicin?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("gentamicin"), null));

		assertEquals(1, warnings.size(), "the control must still raise its one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Gentamicin.", warnings.get(0).getDetail(),
				"from the identity arm, untouched");
	}

	@Test
	public void aClassLevelRuleKeepsItsOwnChipBesideTheFoldedOne() {
		// The fold keys on the FACT, never on "both arms fired". This patient carries two allergy
		// records — one to the drug, one to its class — and Ibuprofen's curated list has a rule for each.
		// The self-named rule folds with identity; the nsaid rule names something the identity arm never
		// reported, so it keeps its own chip. A fold keyed on "the curated arm and the allergen arm both
		// fired for this entry" would silence the class rule and lose a genuine second finding.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService())
				.validate("", "Is it safe to give her ibuprofen?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("ibuprofen", "nsaid"), null));

		assertEquals(2, warnings.size(), "two allergy records, two facts, two chips, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.details(warnings).contains(
				"Ibuprofen is contraindicated by an active allergy: NSAID hypersensitivity"),
				"the class-level rule keeps its own chip, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.details(warnings).contains(
				"Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy"),
				"beside the folded self-named one, was: " + warnings);
	}

	@Test
	public void aSelfNamedRuleWithNoNoteFallsBackToTheIdentityWording() throws IOException {
		// The other direction of "whichever chip carries content wins". With a blank note the curated
		// sentence renders the rule's own TOKEN back (ChartSearchAiUtils.firstNonBlank), i.e.
		// "Ibuprofen is contraindicated by an active allergy: ibuprofen" — strictly less than the
		// identity sentence says. So the fold must keep identity here, not the rule.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixtureService())
				.validate("", "Is it safe to give her ibuprofen?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("ibuprofen"), null));

		assertEquals(1, warnings.size(), "still one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Ibuprofen.", warnings.get(0).getDetail(),
				"and a contentless rule must not displace the identity wording, was: " + warnings);
	}

	@Test
	public void aSelfNamedRuleTheAllergenArmCannotCorroborateStillRaisesItsOwnChip() throws IOException {
		// The curated arm must keep standing ALONE where the allergen arm resolves nothing about this
		// substance — otherwise re-keying it onto the identity key would have quietly made it
		// conditional on that arm. What this adds over
		// aSelfNamedRuleWithNoNoteIsStillTheChipWhenNothingElseReportsTheDrug, which asserts the same
		// outcome, is the arrangement: there the allergen arm returns at its recordedAllergens.isEmpty()
		// guard, here it runs its loop to completion over a substance that is not this one and declines.
		// The fixture rides on an asymmetry this change does not introduce and
		// PatientClinicalContext.hasAllergyToken documents by this very example: that test is bare
		// containment, so "opium" matches an allergen recorded as "Tiotropium", while
		// findImpliedSubstances is boundary-aware and resolves that allergen to the Tiotropium entry.
		DrugReferenceService service = fixtureService();
		assertEquals("[Tiotropium]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Tiotropium")).toString(),
				"precondition: the allergen must resolve to the OTHER entry, so identity cannot fire "
						+ "for Opium");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her opium?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Tiotropium"), null));

		assertEquals(1, warnings.size(), "the curated arm alone must still chip, was: " + warnings);
		assertEquals("Opium is contraindicated by an active allergy: documented opium allergy",
				warnings.get(0).getDetail(), "in its own wording, was: " + warnings);
	}

	@Test
	public void aSelfNamedRuleWithNoNoteIsStillTheChipWhenNothingElseReportsTheDrug() throws IOException {
		// The rank aSelfNamedRuleWithNoNoteFallsBackToTheIdentityWording chooses is BELOW every other
		// relationship, which is a different decision from "do not raise it at all", and only this shape
		// separates the two: the allergen names no entry in the loaded dataset, so the allergen arm
		// contributes nothing and the contentless
		// rule is all there is. Not raising it would have made a curated rule conditional on an arm that
		// never gated it — the two fire on different evidence, hasAllergyToken's bare containment here
		// against findImpliedSubstances' boundary-aware resolution.
		DrugReferenceService service = fixtureService();
		assertTrue(service.findImpliedSubstances("dihydrocodeine").isEmpty(),
				"precondition: the allergen must name no entry, so the allergen arm cannot chip");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her codeine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("dihydrocodeine"), null));

		assertEquals(1, warnings.size(), "the curated rule must still chip, was: " + warnings);
		assertEquals("Codeine is contraindicated by an active allergy: codeine",
				warnings.get(0).getDetail(), "in its own wording, was: " + warnings);
	}

	@Test
	public void aConditionRuleNamingItsOwnDrugIsADifferentFindingAndKeepsItsChip() throws IOException {
		// Only an ALLERGY rule may join the allergen arm's key space. A condition rule is a fact about a
		// CONDITION record, which no chip in that space is about, so one naming its own drug — an
		// operator flagging "diclofenac" in the condition list — stays in the rule key space and reports
		// beside the folded allergy chip. Ungated, it would land on the identity key at the same rank as
		// the allergy rule and one of the two findings would be lost to the incumbent.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixtureService()).validate("",
				"Is it safe to give her diclofenac?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("diclofenac"),
						DrugReferenceTestSupport.set("diclofenac-induced gastropathy")));

		assertEquals(2, warnings.size(), "an allergy record and a condition record are two findings, "
				+ "was: " + warnings);
		assertTrue(DrugReferenceTestSupport.details(warnings).contains(
				"Diclofenac is contraindicated by an active allergy: documented diclofenac allergy"),
				"the allergy rule, folded with identity, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.details(warnings).contains(
				"Diclofenac is contraindicated by an active condition: diclofenac-induced gastropathy"),
				"and the condition rule beside it, was: " + warnings);
	}

	@Test
	public void aClassLevelRuleAuthoredTwiceIsStillOneChip() throws IOException {
		// The rule key space's own collapse, which used to be pinned by
		// ContraindicationRouteVariantTest#oneCuratedRuleAuthoredTwiceRaisesOneChip and no longer is:
		// both spellings there name the entry, so since this change they collapse on the SUBSTANCE key
		// instead and that case would pass with the rule key's case normalization deleted. This token
		// names a class, so it stays in the rule key space and only (type, token) normalized can
		// collapse the two spellings — mutation-verified by dropping DrugReference.normalizeName from
		// that key, which reddens this case and nothing else in the suite.
		DrugReferenceService service = fixtureService();
		DrugReference naproxen = service.lookupByToken("naproxen");
		assertNotNull(naproxen, "precondition: the fixture entry must resolve");
		assertEquals(2, naproxen.getContraindications().size(),
				"precondition: the fixture must really carry the rule twice");
		for (DrugReference.Contraindication rule : naproxen.getContraindications()) {
			assertFalse(DrugSafetyValidator.selfNamedAllergyRule(naproxen, rule),
					"precondition: and neither spelling may NAME the entry, or this pins the other key "
							+ "space — " + rule.getToken());
		}

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her naproxen?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("nsaid"), null));

		assertEquals(1, warnings.size(), "one rule is one chip however it is spelled, was: " + warnings);
		assertEquals("Naproxen is contraindicated by an active allergy: NSAID hypersensitivity",
				warnings.get(0).getDetail(), "and the incumbent survives, was: " + warnings);
	}

	@Test
	public void aDatasetCarryingNoCuratedRulesAtAllIsUnchanged() {
		// Issue #135's case — the entries the identity arm exists for, which the 444 ATC-less rows of the
		// full DDInter dataset are the sharpest of, and which for the CURATED-rule question is every
		// entry of every DDInter
		// load: that parser emits no contraindications at all, so nothing here can fold and the identity
		// arm must fire exactly as it did. Asserted over the whole loaded dataset rather than over the
		// one entry probed below, so a future source that started emitting rules would be caught here
		// rather than silently changing what this case tests.
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		for (DrugReference entry : service.getAll()) {
			assertTrue(entry.getContraindications().isEmpty(),
					"precondition: a DDInter load carries no curated rule — " + entry.displayLabel());
		}

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her lisinopril?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("lisinopril"), null));

		assertEquals(1, warnings.size(), "the identity arm alone, exactly as before, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Lisinopril.", warnings.get(0).getDetail(),
				"in the identity arm's own wording, was: " + warnings);
	}

	@Test
	public void thisFoldAndTheRouteVariantFoldComposeRatherThanStack() throws IOException {
		// Issue #145's collapse and this one meet on one entry set: three rows of ONE substance, two of
		// which carry the same self-named curated allergy rule. Four chips are available — two rule
		// chips and (at least) one identity chip — and the answer is one, because both collapses key
		// through the same ledger on the same substanceGroupKey. Two dedups that merely stacked would
		// leave the rule chips beside the identity one, or one rule chip per row.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries(SUBSTANCE_DECLARED));
		List<DrugReference> rows = service.findByQuery("Is it safe to give her ibuprofen?");
		assertEquals(3, rows.size(), "precondition: one word must resolve all three rows, was: " + rows);
		int ruleBearing = 0;
		for (DrugReference row : rows) {
			assertEquals(rows.get(0).substanceGroupKey(), row.substanceGroupKey(),
					"precondition: and the fixture must really file them as ONE substance");
			ruleBearing += row.getContraindications().size();
		}
		assertEquals(2, ruleBearing, "precondition: with the rule authored on two of the three rows");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her ibuprofen?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("ibuprofen"), null));

		assertEquals(1, warnings.size(),
				"one substance, one allergy, one chip — however many rows carry the rule, was: "
						+ warnings);
		assertEquals("Ibuprofen (tablets) is contraindicated by an active allergy: documented ibuprofen "
				+ "allergy", warnings.get(0).getDetail(),
				"and it is the first rule-bearing row's chip, carrying the note, was: " + warnings);
	}

	@Test
	public void aRuleOnALaterRowReplacesTheIdentityChipInPlace() throws IOException {
		// The other direction through the ledger, which the case above cannot reach: there the
		// rule-bearing row leads, so the curated chip is raised first and the identity chip is simply
		// declined. Here the BARE row leads — the allergen arm raises identity first and the rule has to
		// REPLACE it, in the position the identity chip already took, so no client sees the chip sequence
		// reshuffle. Nothing else in the suite exercises that branch for this rank pair, and a fold that
		// only ever declined newcomers would report the module's stock sentence and drop the note for
		// every dataset whose rule happens to sit on a later row.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries(RULE_ON_A_LATER_ROW));
		List<DrugReference> rows = service.findByQuery("Is it safe to give her ibuprofen?");
		assertEquals(3, rows.size(), "precondition: one word must resolve all three rows, was: " + rows);
		assertTrue(rows.get(0).getContraindications().isEmpty(),
				"precondition: and the FIRST of them must carry no rule, or identity is not raised first");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her ibuprofen?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("ibuprofen"), null));

		assertEquals(1, warnings.size(), "still one chip, was: " + warnings);
		assertEquals("Ibuprofen (tablets) is contraindicated by an active allergy: documented ibuprofen "
				+ "allergy", warnings.get(0).getDetail(),
				"and the note survives however late its row is, was: " + warnings);
	}
}
