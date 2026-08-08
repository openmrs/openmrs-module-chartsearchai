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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * One substance, one contraindication chip — however many rows the reference data files it as
 * (issue #145).
 *
 * <p><b>The defect.</b> Both contraindication arms are keyed on a subject ENTRY, and DDInter files
 * one substance as several route/formulation rows. One clinician-facing string resolves all of them
 * ({@code findByQuery} and {@code findByDrugName} return every entry whose aliases match), so one
 * clinical fact became one chip per row. Worse than duplication: only the row
 * {@link DrugReferenceService#lookupByToken} resolves the allergy to matched by IDENTITY, so its
 * siblings fell through to the class comparison and the patient was told a substance is
 * cross-reactive with their allergy to <em>itself</em> — measured live on the 3.7.1 standalone, a
 * dexamethasone allergy asked about dexamethasone gave 1 identity chip plus 3
 * "Dexamethasone (nasal/ophthalmic/topical) is in the same ATC class (A01AC) as the patient's allergy
 * to Dexamethasone" chips. Since issue #110 every chip is also injected as a citable pre-answer
 * record, so each duplicate reached the prompt too.
 *
 * <p><b>What the grouping key has to get right</b>, and why {@code rxnorm_name} equality alone is not
 * it. The reference data's own substance name (equivalently its {@code rxcui} — measured over the
 * shipped 19 MB KB, the two partition the 2283 entries identically: 142 families, 332 entries) is
 * what the route variants share, but it is also shared by pairs of genuinely DIFFERENT substances:
 * {@code Omeprazole}/{@code Esomeprazole} (both {@code rxnorm_name=esomeprazole},
 * {@code rxcui=283742}, both {@code A02BC05}), {@code Amphetamine}/{@code Dextroamphetamine},
 * {@code Fenfluramine}/{@code Dexfenfluramine}, {@code Gabapentin}/{@code Gabapentin enacarbil},
 * {@code Netupitant}/{@code Fosnetupitant}, {@code Ketoconazole}/{@code Levoketoconazole},
 * {@code Fenofibrate}/{@code Fenofibric acid}, {@code Atropine}/{@code Hyoscyamine} — every one of
 * them the {@code enalapril}/{@code enalaprilat} shape issue #121 decided must stay two chips. So the
 * key is the substance name AND the display-name stem (the name with trailing parenthesized
 * qualifiers removed): see {@link DrugReference#substanceKey()}, which records the measurement for
 * both halves.
 *
 * <p>Both directions are asserted here — and so is the ordering the collapse cannot assume, a group
 * whose allergen row is not its first — against slices taken verbatim from the shipped KB, through
 * the real {@link DdiDrugReferenceSource} parser and the real {@link DrugSafetyValidator#validate}
 * entry points.
 */
public class ContraindicationRouteVariantTest {

	/**
	 * Verbatim KB rows, in KB order: the two PPI entries the KB files under one substance name
	 * ({@code Omeprazole} + {@code Esomeprazole}) with a third PPI to be allergic to
	 * ({@code Pantoprazole}, {@code A02BC02}, so the shared level-4 subgroup is {@code A02BC}), and
	 * the four {@code hydrocortisone} rows with {@code Dexamethasone} to be allergic to (both carry
	 * {@code A01AC}), plus the five {@code Tozinameran} rows — the group whose allergen row is not its
	 * first, because only the bare row carries the bare substance name as an alias. Its
	 * {@code interactions} array is empty, deliberately: this file asserts contraindication chip COUNTS,
	 * and an interaction chip in the same list would have to be filtered out of every assertion here.
	 */
	private static final String FIXTURE = "chartsearchai-test/ddi-contra-route-variants.json";

	/** One curated entry carrying the same contraindication rule twice, differing only in the CASE of
	 *  its type and token — the ledger's other key space, on the source that publishes no substance
	 *  name. Curated schema, so it is parsed by {@link JsonDrugReferenceSource} rather than the DDInter
	 *  parser above. */
	private static final String DUPLICATE_RULE_FIXTURE =
			"chartsearchai-test/drug-reference-duplicate-rule-tokens.json";

	/** A question that resolves no reference drug and is not an interaction screen, so the only arm
	 *  that can chip is the order-driven one ({@code addActiveOrderContraindications}). */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	@Test
	public void theFixturesReallyCarryTheTwoShapesUnderTest() throws IOException {
		// Preconditions, through the production matchers the validator itself uses. Without these the
		// cases below could pass while resolving one entry each — i.e. while testing nothing.
		DrugReferenceService ppi = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<DrugReference> esomeprazole = ppi.findByQuery("Is it safe to give esomeprazole?");
		assertEquals(2, esomeprazole.size(),
				"one PPI word must resolve BOTH rows the KB files under it, was: "
						+ DrugReferenceTestSupport.names(esomeprazole));
		assertEquals(esomeprazole.get(0).normalizedAtcCodes(), esomeprazole.get(1).normalizedAtcCodes(),
				"and their ATC codes must be identical, so no class comparison can tell them apart");
		assertFalse(esomeprazole.get(0).displayLabel().equals(esomeprazole.get(1).displayLabel()),
				"while their labels differ — they are two substances, not two routes of one");

		List<DrugReference> hydrocortisone = ppi.findByQuery("Is hydrocortisone safe for her?");
		assertEquals(4, hydrocortisone.size(),
				"one order word must resolve all four hydrocortisone rows, was: "
						+ DrugReferenceTestSupport.names(hydrocortisone));

		DrugReferenceService variants = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		List<DrugReference> dexamethasone = variants.findByQuery("Is it safe to give dexamethasone?");
		assertEquals(4, dexamethasone.size(),
				"and one question word must resolve all four dexamethasone rows, was: "
						+ DrugReferenceTestSupport.names(dexamethasone));
		DrugReference allergen = variants.lookupByToken("Dexamethasone");
		assertNotNull(allergen, "the allergy must resolve to one of them");
		assertEquals("Dexamethasone", allergen.displayLabel(),
				"and it is the base row, so the identity chip is the one that must survive");
	}

	@Test
	public void anAllergyToADrugFiledAsFourRouteVariantsRaisesOneChip() throws IOException {
		// THE case, and Richard Jones's live shape: a recorded dexamethasone allergy, asked about
		// dexamethasone. Four rows are in play; three of them are not the row the allergy resolved to,
		// so before this fix they each reported the substance as cross-reactive with itself.
		List<SafetyWarning> warnings = fixtureValidator(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS)
				.validate("", "Is it safe to give dexamethasone?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(),
				"four route variants of one substance are one clinical fact, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("Dexamethasone", warnings.get(0).getDrug());
		assertEquals("The patient has a recorded allergy to Dexamethasone.", warnings.get(0).getDetail(),
				"and the surviving chip is the IDENTITY one — the strongest statement about the "
						+ "substance, not a sibling's cross-reactivity hedge");
	}

	@Test
	public void noSurvivingChipReportsASubstanceAsCrossReactiveWithItself() throws IOException {
		// The same call, asserted on content rather than on count: whatever the collapse keeps, nothing
		// may say "X is in the same ATC class as the patient's allergy to X". A collapse that merely
		// picked one of the four chips at random would satisfy the count above and fail this.
		List<SafetyWarning> warnings = fixtureValidator(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS)
				.validate("", "Is it safe to give dexamethasone?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Dexamethasone"), null));

		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("same ATC class")
					&& warning.getDetail().contains("allergy to Dexamethasone"),
					"a dexamethasone row must not be reported as cross-reactive with the patient's "
							+ "dexamethasone allergy: " + warning.getDetail());
		}
	}

	@Test
	public void routeVariantsOfOneActiveOrderRaiseOneChipPerSubstance() throws IOException {
		// Sarah Taylor's live shape, on the ORDER-DRIVEN arm (issue #143's), which the question-driven
		// arm's collapse cannot reach: the patient is on one hydrocortisone order and allergic to
		// dexamethasone, and the question names no drug at all. Four rows resolve from that one order
		// name and all four share subgroup A01AC with the allergen, so before this fix it was four
		// chips.
		//
		// TWO chips, not one: `Hydrocortisone butyrate` is an ester whose display name is not the
		// family stem plus a qualifier, so the key deliberately keeps it separate — the conservative
		// direction, and the same refusal that keeps Omeprazole and Esomeprazole apart below.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Hydrocortisone Injection vial 100mg"), null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(2, warnings.size(),
				"three route variants collapse and the ester stays its own chip, was: " + warnings);
		assertEquals("Hydrocortisone is in the same ATC class (H02AB) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity", warnings.get(0).getDetail(),
				"the surviving variant chip is the dataset's first row, named by displayLabel()");
		assertEquals("Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's allergy"
				+ " to Dexamethasone — possible cross-reactivity", warnings.get(1).getDetail());
	}

	/**
	 * The must-not-collapse case below is a test of the display-stem VETO only while its two rows
	 * genuinely share the reference data's substance name. Pinned separately, because a fixture
	 * regenerated with differing {@code rxnorm_name}s would leave that case passing 2-for-2 on the
	 * substance name alone — and the stem half, which a mutation sweep showed drops real chips when
	 * removed, would then be exercised by nothing.
	 */
	@Test
	public void theTwoPpiRowsShareTheSubstanceNameTheStemHasToVeto() throws IOException {
		List<DrugReference> ppis = DrugReferenceTestSupport.ddiFixtureService(FIXTURE)
				.findByQuery("Is it safe to give esomeprazole?");

		assertEquals(2, ppis.size(), "was: " + DrugReferenceTestSupport.names(ppis));
		assertEquals(DrugReference.normalizeName(ppis.get(0).getSubstanceName()),
				DrugReference.normalizeName(ppis.get(1).getSubstanceName()),
				"the two rows must share ONE substance name, or the stem is not what keeps them apart");
		assertNotEquals(ppis.get(0).substanceKey(), ppis.get(1).substanceKey(),
				"while the combined key still separates them");
	}

	@Test
	public void twoDistinctSubstancesTheKbFilesUnderOneSubstanceNameStayTwoChips() throws IOException {
		// The must-NOT-collapse case, and the sharp edge of the whole key: Omeprazole and Esomeprazole
		// carry the same rxnorm_name, the same RxCUI and the same single ATC code, so every key made of
		// reference-data identity alone merges them — and they are two substances, exactly as
		// enalapril and enalaprilat are (issue #121). One PPI word puts both in play, and a Pantoprazole
		// allergy is class-related to both, so a merging key would drop one of two real chips.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("",
				"Is it safe to give esomeprazole?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Pantoprazole"), null));

		assertEquals(2, warnings.size(),
				"two distinct substances sharing one substance name keep their own chips, was: "
						+ warnings);
		assertEquals("Omeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Pantoprazole — possible cross-reactivity", warnings.get(0).getDetail());
		assertEquals("Esomeprazole is in the same ATC class (A02BC) as the patient's allergy to"
				+ " Pantoprazole — possible cross-reactivity", warnings.get(1).getDetail());
	}

	@Test
	public void aPrescribedRouteVariantFamilyTheyAreAllergicToRaisesOneChip() throws IOException {
		// Richard Jones exactly: the dexamethasone allergy AND a dexamethasone order, asked about
		// dexamethasone. The four rows are in play, so the order-driven arm skips them all and the
		// question-driven arm owns the finding — one chip, and the identity one. Both arms feeding one
		// ledger is what makes this hold: a collapse living inside a single arm would still emit the
		// order arm's chips beside the question arm's.
		List<SafetyWarning> warnings = fixtureValidator(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS)
				.validate("", "Is it safe to give dexamethasone?",
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Dexamethasone: 4.0 Milligram Oral Once daily"),
								null, DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(), "one substance, one chip, whichever arm reaches it, was: "
				+ warnings);
		assertEquals("The patient has a recorded allergy to Dexamethasone.",
				warnings.get(0).getDetail());
	}

	/**
	 * The premise that makes the case below what it is, asserted through the production resolvers so it
	 * cannot rot into a test of nothing: a question naming the vaccine resolves all five presentation
	 * rows, while an allergy to {@code Tozinameran} resolves the FOURTH of them — so the identity match
	 * arrives after three class matches rather than before them, unlike the dexamethasone shape above.
	 */
	@Test
	public void theFixtureReallyCarriesAnAllergenRowThatIsNotItsGroupsFirst() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		List<DrugReference> inPlay = service
				.findByQuery("Is it safe to give the Pfizer-BioNTech COVID-19 vaccine?");
		assertEquals(5, inPlay.size(),
				"one brand word must resolve every presentation row, was: "
						+ DrugReferenceTestSupport.names(inPlay));
		assertEquals("Tozinameran (12y+)", inPlay.get(0).getName(),
				"and a presentation comes FIRST, so it is the group's incumbent chip");

		DrugReference allergen = service.lookupByToken("Tozinameran");
		assertNotNull(allergen, "the allergy must resolve to one of the rows");
		assertSame(inPlay.get(3), allergen,
				"and the allergen is the FOURTH: this KB gives each presentation row only its own "
						+ "qualified name as an alias, so the bare substance name skips them — was: "
						+ allergen.getName());
		assertEquals(allergen.substanceKey(), inPlay.get(0).substanceKey(),
				"while all of them are still one substance to the collapse");
	}

	@Test
	public void theIdentityChipSurvivesWhenTheAllergenRowIsNotItsGroupsFirst() throws IOException {
		// The collapse must keep the most specific relationship even when the weaker candidate is raised
		// FIRST — an incumbent chip has to be REPLACED, not merely suppressed. Reachable on the shipped
		// KB, not a hypothetical: the allergy resolves a row that is not its collapsed group's first, so
		// the earlier members chip first. `Tozinameran`, `Insulin aspart (aspart)` and
		// `Iobenguane (I-131)` were each verified this way through validate() over the full 19 MB KB —
		// under the earliest-match resolution that issue #176 replaced; today `Tozinameran` reaches the
		// same row because that row's own display name IS the recorded string, and it is still not the
		// group's first. This case no longer discriminates a first-wins ledger from
		// replace-in-place: since issue #164 the identity test matches on the substance, so the allergen
		// raises IDENTITY whichever member of the group is reached first. What it still pins is that the
		// surviving chip is the identity one and that it names the allergen as the chart records it.
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("",
				"Is it safe to give the Pfizer-BioNTech COVID-19 vaccine?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Tozinameran"), null));

		assertEquals(1, warnings.size(),
				"five presentations of one vaccine are one clinical fact, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Tozinameran (sars-cov-2 (covid-19) vaccine,"
				+ " mrna spike protein).", warnings.get(0).getDetail(),
				"and the IDENTITY chip is the survivor, replacing the class chip an earlier row had "
						+ "already raised — not the other way round");
	}

	@Test
	public void oneLedgerSpansBothCallSites() throws IOException {
		// The decisive reason this is a ledger and not a filter: the two arms run at TWO call sites, so a
		// collapse living inside either one leaves the other emitting the siblings. Here both call sites
		// raise a candidate for the SAME (substance, allergen's substance) key and neither is redundant
		// with the other — the question resolves the (12y+) presentation and the bare row, the active order
		// resolves the (5y-11y) presentation, which no question word reaches. A ledger per call site
		// answers this with two chips; the tests above cannot see the difference, because in each of them
		// one call site supplies the whole group.
		String question = "Is it safe to give Tozinameran (12y+)?";
		String order = "Tozinameran (5y-11y)";
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		// The premise, through the production resolvers: without it the order arm could stop reaching a
		// row of its own and this would pass on the question arm's collapse alone, testing nothing.
		List<DrugReference> fromQuestion = service.findByQuery(question);
		List<DrugReference> orderOnly = new ArrayList<DrugReference>(service.findByDrugName(order));
		orderOnly.removeAll(fromQuestion);
		assertEquals(1, orderOnly.size(),
				"the order must resolve exactly one row no question word reaches, was: "
						+ DrugReferenceTestSupport.names(orderOnly));
		assertEquals(orderOnly.get(0).substanceKey(), fromQuestion.get(0).substanceKey(),
				"and it must be the same substance, or the two call sites share no key");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("", question,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(order), null,
						DrugReferenceTestSupport.set("Tozinameran"), null));

		assertEquals(1, warnings.size(),
				"a question-driven chip and an order-driven one about one substance are one chip, was: "
						+ warnings);
		assertEquals("The patient has a recorded allergy to Tozinameran (sars-cov-2 (covid-19) vaccine,"
				+ " mrna spike protein).", warnings.get(0).getDetail());
	}

	@Test
	public void oneSubstanceStillReportsEveryRecordedFindingSeparately() throws IOException {
		// The other half of the key: the RECORDED FINDING is in it, so the collapse is per (substance,
		// finding) and never per substance. Two allergies about two DIFFERENT substances are two clinical
		// facts and must stay two chips — a collapse keyed on the subject alone would answer this with
		// one, dropping the identity statement or the cross-reactivity one depending on which arrived
		// first. (Two allergies about ONE substance are one clinical fact and do collapse, since issue
		// #176 keyed the finding half on the allergen's substance; that is the case
		// AllergenExactNameResolutionTest pins.)
		List<SafetyWarning> warnings = fixtureValidator(FIXTURE).validate("",
				"Is hydrocortisone safe for her?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dexamethasone", "Hydrocortisone"), null));

		assertEquals(4, warnings.size(), "two substances x two findings, was: " + warnings);
		assertEquals("Hydrocortisone is in the same ATC class (H02AB) as the patient's allergy to"
				+ " Dexamethasone — possible cross-reactivity", warnings.get(0).getDetail());
		assertEquals("The patient has a recorded allergy to Hydrocortisone.",
				warnings.get(1).getDetail(),
				"the identity finding about the SAME substance keeps its own chip beside the "
						+ "cross-reactivity one");
		assertEquals("Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's allergy"
				+ " to Dexamethasone — possible cross-reactivity", warnings.get(2).getDetail());
		assertEquals("Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's allergy"
				+ " to Hydrocortisone — possible cross-reactivity", warnings.get(3).getDetail());
	}

	@Test
	public void eachCollapsedChipIsInjectedIntoThePromptExactlyOnce() throws IOException {
		// The other half of a chip (issue #110): every chip is injected as a numbered, citable
		// safety-finding record, so N duplicate chips were N near-identical records in the context
		// window as well. Real injector wired to the real validator, so the record count follows the
		// chips rather than being asserted separately.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<?> findings = DrugReferenceTestSupport.injectedFindings(injector.injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dexamethasone"), null),
				"Is it safe to give dexamethasone?"));

		assertEquals(1, findings.size(),
				"one chip is one citable record, not one per route variant, was: " + findings);
	}

	@Test
	public void oneCuratedRuleAuthoredTwiceRaisesOneChip() throws IOException {
		// The ledger's OTHER key space, and the one source that publishes no substance name at all: a
		// curated entry keys on its own identity, so nothing about it collapses across rows — but its
		// rules still key on (type, token) NORMALIZED the way the arm compared them, so one rule authored
		// twice in different case is one chip rather than two. Pre-fix both were appended unconditionally.
		//
		// The cost this pins: ties keep the incumbent, so the second rule's NOTE is dropped. That is the
		// right call for a re-spelling of one rule and a lossy one for two genuinely different notes
		// under one token; the latter is authoring pathology no shipped dataset contains, and is
		// reported rather than designed around here.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(DUPLICATE_RULE_FIXTURE));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		DrugReference ibuprofen = service.lookupByToken("Ibuprofen");
		assertNotNull(ibuprofen, "precondition: the fixture entry must resolve");
		assertNull(ibuprofen.substanceKey(),
				"precondition: a curated entry publishes no substance name, so it keys on itself");
		assertEquals(2, ibuprofen.getContraindications().size(),
				"precondition: the fixture must really carry the rule twice");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("ibuprofen"), null);
		for (DrugReference.Contraindication rule : ibuprofen.getContraindications()) {
			assertTrue(context.hasAllergyToken(rule.getToken()),
					"precondition: BOTH spellings must MATCH the recorded allergy, or the collapse is "
							+ "not what makes this one chip — unmatched: " + rule.getToken());
		}

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is ibuprofen safe for her?", context);

		assertEquals(2, warnings.size(),
				"the rule collapses to one chip, and the identity chip beside it is issue #146's separate "
						+ "key space — deliberately NOT collapsed with it, was: " + warnings);
		List<String> ruleChips = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains("is contraindicated by an")) {
				ruleChips.add(warning.getDetail());
			}
		}
		assertEquals(1, ruleChips.size(), "one rule is one chip however it is spelled, was: " + ruleChips);
		assertEquals("Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy",
				ruleChips.get(0), "and the incumbent survives");
		assertFalse(warnings.toString().contains("avoid all NSAIDs"),
				"so the re-spelling's own note is the one dropped, was: " + warnings);
	}


	private static DrugSafetyValidator fixtureValidator(String fixture) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture));
	}
}
