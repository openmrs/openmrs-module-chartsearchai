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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * WHICH MATCH lets a self-named allergy rule speak in the identity chip's place (issue #223).
 *
 * <p><b>The defect.</b> Issue #146 files a curated allergy rule naming its own entry in the allergen
 * arm's key space at {@code ContraindicationChips.SELF_NAMED_RULE}, above {@code IDENTITY}, because
 * such a rule reports that arm's fact in the deployment's own words. What decides that it reports it
 * is {@link PatientClinicalContext#hasAllergyToken}, which is deliberately BARE CONTAINMENT — right
 * for the class and free-text tokens it was built for ({@code nsaid} inside "NSAIDs"), and wrong for
 * a token that is a drug NAME, because drug names nest. So an allergen recorded as {@code Tiotropium}
 * gave an {@code Opium} rule the identity chip's rank, and where the patient ALSO had a genuine
 * allergy to that substance recorded under another of its names, the accidental match chose what the
 * clinician read about the genuine one:
 * <pre>
 * before  Opium is contraindicated by an active allergy: documented opium allergy
 * after   The patient has a recorded allergy to Opium.
 * </pre>
 *
 * <p><b>The fix, and what it does NOT do.</b> A self-named rule's token is by construction one of the
 * subject entry's own drug NAMES ({@link DrugSafetyValidator#selfNamedAllergyRule} is
 * {@link DrugReference#isNamed}), and a recorded allergen is a clinician-entered drug name, so the
 * question "does the allergy record this rule fired on NAME this entry" takes the drug-name accessor
 * {@link DrugReference#matchesDrugName} — CLAUDE.md's three-shapes rule, applied to the promotion
 * rather than to the match. It is a RANK change and not a
 * firing change: the rule still fires on bare containment and still lands on the same key, so
 * <em>where nothing else reports the drug</em> it is still the chip, word for word. That bound is
 * {@link SelfNamedAllergyRuleFoldTest#aSelfNamedRuleTheAllergenArmCannotCorroborateStillRaisesItsOwnChip},
 * which this change deliberately leaves green and which the alternative reading of #223 (stop matching
 * mid-word at all) would redden; a copy of it here would add an arrangement, and there is none to add.
 * Where something else DOES report the drug — the case this class exists for — the operator's note is
 * what yields.
 *
 * <p><b>Why the tightening is not applied to the match itself.</b> Measured 2026-08-14 over the
 * shipped 19 MB DDInter KB's 5169 published names as the allergen corpus, through
 * {@link PatientClinicalContext#hasAllergyToken} and {@link DrugReference#matchesOrderName}: of the
 * ten rules the bundled curated file publishes, moving the MATCH to the drug-name rule loses 5 real
 * allergen names and every one of them is on {@code penicillin} — a CLASS token, not a self-named one
 * ({@code benzylpenicillin}, {@code phenoxymethylpenicillin}, {@code procaine benzylpenicillin} …).
 * All three self-named tokens lose nothing. Tightening the leg wholesale would therefore trade a
 * false positive for a false NEGATIVE in a safety net, which is the wrong direction; tightening only
 * what the rank turns on costs nothing measured. {@link PatientClinicalContext#hasAllergyToken}
 * records what that corpus does and does not bound.
 *
 * <p>Every case drives the real {@code validate} over a dataset parsed by the real production parser.
 */
public class SelfNamedAllergyRuleRankTest {

	/** The shapes in which an accidental match and a genuine identity chip can meet on one key —
	 *  described in the fixture's own {@code description} field. */
	private static final String MID_WORD_TOKEN =
			"chartsearchai-test/drug-reference-mid-word-allergy-token.json";

	private static DrugReferenceService fixtureService() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(MID_WORD_TOKEN));
		// The fixture really parsed: a curated file whose entries the parser dropped (a blank id, a
		// missing name) loads as FEWER entries and every case below then passes vacuously, which is
		// issue #242's failure mode arriving through the fixture rather than through the document.
		assertEquals(6, service.getAll().size(),
				"precondition: the real parser must load all six fixture entries, was: "
						+ DrugReferenceTestSupport.names(service.getAll()));
		return service;
	}

	@Test
	public void aRuleMatchedOnlyMidWordCannotDisplaceAChipTheAllergenArmCorroborates() throws IOException {
		// THE case. Two allergy records: `Papaveretum`, which the allergen arm resolves to Opium, and
		// `Tiotropium`, which it resolves to Tiotropium and which the curated rule's token reaches only
		// by sitting inside a longer word. Both chips land on Opium's substance key, and before this
		// change the accidental one outranked the corroborated one and replaced its sentence.
		DrugReferenceService service = fixtureService();
		assertEquals("[Opium]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Papaveretum")).toString(),
				"precondition: one recorded allergy must genuinely resolve to Opium");
		assertEquals("[Tiotropium]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Tiotropium")).toString(),
				"precondition: and the other must resolve somewhere else entirely");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her opium?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Papaveretum", "Tiotropium"), null));

		assertEquals(1, warnings.size(), "one substance, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Opium.", warnings.get(0).getDetail(),
				"and the chip the allergen arm corroborates must survive a rule only a mid-word match "
						+ "put on its key, was: " + warnings);
	}

	@Test
	public void thePromptCarriesTheSurvivingChipAndNotTheRuleItOutranked() throws IOException {
		// The other half of a chip (issue #110): every chip is injected as a numbered, citable
		// safety-finding record, so a rank that moved and a safety_finding that did not would put the
		// demoted rule's wording into the context window as a finding with no chip behind it. Real
		// injector wired to the real validator, so the record follows the chips rather than being
		// asserted separately — the same arrangement
		// SelfNamedAllergyRuleFoldTest.theFoldedChipReachesThePromptAsOneCitableRecord makes for the rank
		// this one demotes FROM, which proves that sibling and not this variant.
		//
		// SCOPE, because injectedFindings sees only the safety_finding records: the drug_reference record
		// injected beside them is a separate surface, and its own patient-specific reading is now scoped
		// by whether anything CORROBORATES the match (issue #269 — it used to read "Recorded for this
		// patient: documented opium allergy" even for a patient whose ONLY allergy was the accidental
		// one). InjectedContraindicationCorroborationTest owns that half; asserting it here would assert
		// another decision's fix. The MATCH itself is still untightened, which is what this class's
		// javadoc records.
		DrugReferenceService service = fixtureService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Papaveretum", "Tiotropium"), null),
						"Is it safe to give her opium?"));

		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		// Plus the strength clause a contraindication record states since #283, from the constant —
		// its literal is pinned in SafetyFindingSeverityStrengthTest. Which chip survives, the thing
		// this case is about, is unchanged.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Opium: The patient has a recorded allergy to Opium."
				+ DrugReferenceInjector.STRENGTH_WITHHOLD, findings.get(0).getText(),
				"carrying the SURVIVING chip's detail verbatim, was: " + findings);
	}

	@Test
	public void anAllergenThatNamesTheDrugStillLetsTheRuleOutrankIdentity() throws IOException {
		// THE CONTROL, and what separates this from deleting issue #146's fold: the same entry, the same
		// rule, and an allergy recorded under the very name the rule's token is. Here the rule does report
		// the allergen arm's fact, so it keeps the identity chip's rank and the deployment's own wording
		// survives.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixtureService()).validate("",
				"Is it safe to give her opium?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Opium"), null));

		assertEquals(1, warnings.size(), "still one chip, was: " + warnings);
		assertEquals("Opium is contraindicated by an active allergy: documented opium allergy",
				warnings.get(0).getDetail(),
				"and it is still the curated note, was: " + warnings);
	}

	@Test
	public void anInflectedAllergenSpellingStillNamesTheDrug() throws IOException {
		// The rank turns on the drug-NAME rule and not on the prose one, and only an inflected spelling
		// separates them: `Ibuprofène` folds to `ibuprofene`, i.e. the token plus one inflectional letter,
		// which DrugReference.matchesOrderName tolerates (issue #147's whole point — an allergen name and
		// an order name are one shape out of one localized dictionary) and DrugReference.containsWord's
		// symmetric boundary does not. Swapping this rank's predicate for the prose matcher reddens here
		// and nowhere else in the suite.
		DrugReferenceService service = fixtureService();
		assertEquals("[Ibuprofen]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Ibuprofène")).toString(),
				"precondition: the allergen arm reads that spelling as Ibuprofen, so both chips are "
						+ "available and only the RANK decides which is read");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her ibuprofen?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ibuprofène"), null));

		assertEquals(1, warnings.size(), "one chip, was: " + warnings);
		assertEquals("Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy",
				warnings.get(0).getDetail(),
				"and an inflected spelling NAMES the drug, so the rule keeps its rank, was: " + warnings);
	}

	@Test
	public void anEntryTheChartNamedKeepsItsRankThoughItsTokenMatchedMidWord() throws IOException {
		// The other side of the boundary, and the case that decides the question is asked of the ENTRY
		// rather than of the token. Levothyroxine publishes `thyroxine` among its own names and rules on
		// THAT name; the patient's allergy is recorded as `Levothyroxine`, in which the token sits
		// mid-word. A token-scoped reading demotes the rule — and it must not: the entry publishes the
		// very name the chart used, the allergen arm raises its identity chip for that same substance,
		// and the note is the only thing in the response that says what the reaction was.
		DrugReferenceService service = fixtureService();
		DrugReference levothyroxine = service.lookupByToken("levothyroxine");
		assertNotNull(levothyroxine, "precondition: the fixture entry must resolve");
		assertTrue(DrugReferenceTestSupport
				.ctx(60, null, null, null, DrugReferenceTestSupport.set("Levothyroxine"), null)
				.hasAllergyToken("thyroxine"),
				"precondition: the token must reach the record only through bare containment");
		assertEquals("[Levothyroxine]", DrugReferenceTestSupport
				.names(service.findImpliedSubstances("Levothyroxine")).toString(),
				"precondition: and the allergen arm must raise an identity chip for that same substance, "
						+ "so there is a rank to lose");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her levothyroxine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Levothyroxine"), null));

		assertEquals(1, warnings.size(), "one chip, was: " + warnings);
		assertEquals("Levothyroxine is contraindicated by an active allergy: documented thyroxine "
				+ "allergy — anaphylaxis", warnings.get(0).getDetail(),
				"and the operator's note survives, because the record the rule fired on NAMES this entry, "
						+ "was: " + warnings);
	}

	@Test
	public void theOrderDrivenJoinRanksTheSameWay() throws IOException {
		// The OTHER call site (issue #143): both contraindication joins delegate to the one
		// addContraindications, so the rank reaches a drug the patient is PRESCRIBED as well as one the
		// question names. What this adds over the cases above is that arrangement and nothing else — the
		// question names no drug at all, so only the active order puts Opium in play — which is exactly
		// the composition a shared rank method can get wrong by being handed a differently-built context.
		DrugReferenceService service = fixtureService();
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Are there any problems with her medications?",
				service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Opium"), null,
						DrugReferenceTestSupport.set("Papaveretum", "Tiotropium"), null,
						java.util.Collections.singletonList(
								DrugReferenceTestSupport.activeOrder("order-opium", "Opium", "opium")))));

		assertEquals(1, warnings.size(), "one substance, one chip, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Opium.", warnings.get(0).getDetail(),
				"and the order-driven join demotes the mid-word rule exactly as the in-play one does, "
						+ "was: " + warnings);
	}

	@Test
	public void aBorrowedAliasFoldReplacesAnIdentitySentenceTheChartDoesNotSupport() throws IOException {
		// Issue #224's shape, constructed: two entries publish `ketoconazole`, which is the
		// alias-names-another-substance defect the loader reports and deliberately does not repair, and
		// the borrowing entry carries a curated allergy rule on that borrowed name. #224 reasoned the fold
		// would then displace an identity chip and speak for the wrong substance. Measured, the reverse
		// holds: the identity chip the borrowed alias produces is the one asserting an allergy the chart
		// does not record, and the fold is what removes it. The recorded allergen NAMES this entry — the
		// borrowed alias is one of the entry's own published names — so the rank guard leaves this alone.
		DrugReferenceService service = fixtureService();
		assertEquals("[Ketoconazole (oral), Levoketoconazole]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("ketoconazole")).toString(),
				"precondition: the borrowed alias must make the allergen arm reach BOTH entries, or "
						+ "there is no identity chip on the borrowing entry to displace");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her levoketoconazole?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("ketoconazole"), null));

		// Exactly one chip and its whole detail, which together already say that "The patient has a
		// recorded allergy to Levoketoconazole." is absent — an assertFalse on that string beside them
		// could not fail unless one of these had, and an assertion that cannot fail reads as evidence
		// without being any.
		assertEquals(1, warnings.size(), "one chip, was: " + warnings);
		assertEquals(
				"Levoketoconazole is contraindicated by an active allergy: documented ketoconazole allergy",
				warnings.get(0).getDetail(),
				"the operator's own rule about the drug being asked about — not \"The patient has a "
						+ "recorded allergy to Levoketoconazole\", which the chart does not say, was: "
						+ warnings);
	}

	@Test
	public void aDdinterLoadCannotReachThisRankAtAll() {
		// The reachability bound, as a PARSER property rather than a scan of one file: this rank is chosen
		// inside the walk over an entry's `contraindications`, and DdiDrugReferenceSource emits none — so
		// every ddinter deployment runs this change's code zero times. Asserted over every entry the load
		// produced, so a future source that started emitting rules is caught here rather than silently
		// changing what this bound means.
		//
		// The CORPUS is the DDInter excerpt, 16 entries — not the 2283-entry KB a real deployment
		// loads, which is not on the test classpath. That one was measured separately through this same
		// DdiDrugReferenceSource.parse (2026-08-14: 2283 entries, 0 contraindication rules, so 0 self-named
		// allergy rules). What makes 16 entries enough here is that the property is the PARSER's, not the
		// dataset's: it emits no contraindications for any input.
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		assertFalse(service.getAll().isEmpty(), "precondition: the DDInter excerpt must load something");
		for (DrugReference entry : service.getAll()) {
			assertTrue(entry.getContraindications().isEmpty(),
					"a DDInter load carries no curated rule — " + entry.displayLabel());
		}

		DrugReference lisinopril = service.lookupByToken("lisinopril");
		assertNotNull(lisinopril, "precondition: the probe entry must resolve");
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give her lisinopril?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("lisinopril"), null));

		assertEquals(1, warnings.size(), "the identity arm alone, exactly as before, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Lisinopril.", warnings.get(0).getDetail(),
				"in the identity arm's own wording, was: " + warnings);
	}
}
