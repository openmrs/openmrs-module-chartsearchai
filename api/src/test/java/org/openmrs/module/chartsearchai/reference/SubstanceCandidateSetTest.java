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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Which SUBSTANCES a recorded string puts in play — issue #209.
 *
 * <p><b>The defect.</b> Two name-resolution rules were in force and disagreed about how many
 * substances one name denotes. The ranked one ({@link DrugReference#nameMatchStrength},
 * {@link DrugReferenceService#findImpliedSubstances}) keeps the strongest claimants; the unranked
 * boolean matchers ({@link DrugReference#matchesText} for prose,
 * {@link DrugReference#matchesDrugName} for a recorded name) admit EVERY candidate. Where a chip arm
 * built its candidate set by iterating a boolean, a name admitted a superset and a chip named a
 * substance nobody had named and the patient was not on.
 *
 * <p>Sarah Taylor's live shape on the 3.7.1 standalone, reproduced here row-for-row: one
 * {@code Hydrocortisone Injection vial 100mg} order, a recorded dexamethasone allergy, and a chip
 * reading "Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's allergy to
 * Dexamethasone". The ester carries {@code hydrocortisone} as an alias (its DDInter
 * {@code rxnorm_name}) and is a DIFFERENT substance — measured through the production predicates over
 * the shipped 19 MB KB, {@code matchesText("hydrocortisone")} is true for all four hydrocortisone rows
 * while {@code nameMatchStrength("hydrocortisone")} scores {@code Hydrocortisone} 2 and
 * {@code Hydrocortisone butyrate} 1.
 *
 * <p><b>Both legs, or the chip comes back from the other arm.</b> The same substance reached the same
 * chip by two routes — the question's prose ({@code findByQuery}) and the patient's own order name
 * ({@code findByDrugName}) — and the two arms skip whatever the other already covered, so narrowing
 * one leg alone leaves the chip standing. {@link #theEsterIsGoneWhateverArmWouldHaveRaisedIt} is the
 * case that fails for a one-leg fix; the two before it isolate each leg.
 *
 * <p><b>What must NOT narrow</b> is asserted beside it, because the risk of a more precise resolver is
 * dropping a real finding: a string that names the weaker claimant OUTRIGHT still puts it in play
 * ({@link #aStringThatNamesTheEsterOutrightStillPutsItInPlay}), a combination order name still puts
 * every constituent substance in play ({@link #aCombinationOrderNameKeepsEveryConstituentSubstance}),
 * and a localized order name still resolves at all ({@link #aLocalizedOrderNameStillResolves}, issue
 * #147's shape).
 *
 * <p>Slices taken verbatim from the shipped KB, driven through the real {@link DdiDrugReferenceSource}
 * parser and the real {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}.
 */
public class SubstanceCandidateSetTest {

	/** The four hydrocortisone rows — three route variants of one substance plus the ester the KB files
	 *  as its own — with {@code Dexamethasone} to be allergic to (both carry {@code H02AB}). */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS;

	/** A question that resolves no reference drug and is not an interaction screen, so the only arm that
	 *  can chip is the order-driven one ({@code addActiveOrderContraindications}). */
	private static final String NO_DRUG_QUESTION = "What are her current medications?";

	private static final String ESTER_CHIP = "Hydrocortisone butyrate is in the same ATC class (H02AB) as"
			+ " the patient's allergy to Dexamethasone — possible cross-reactivity";

	private static final String SUBSTANCE_CHIP = "Hydrocortisone is in the same ATC class (H02AB) as the"
			+ " patient's allergy to Dexamethasone — possible cross-reactivity";

	@Test
	public void theFixtureReallyCarriesTheTwoClaimStrengthsUnderTest() throws IOException {
		// Preconditions through the production predicates. Without them every case below could pass while
		// the ester was never a candidate at all, i.e. while asserting nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<DrugReference> matched = service.findByQuery("is hydrocortisone safe for her?");

		assertEquals(4, matched.size(), "the prose matcher must still admit all four rows — this fix is "
				+ "not a narrowing of matchesText, was: " + DrugReferenceTestSupport.names(matched));
		DrugReference substance = DrugReferenceTestSupport.row(matched, "Hydrocortisone");
		DrugReference ester = DrugReferenceTestSupport.row(matched, "Hydrocortisone butyrate");
		assertEquals(DrugReference.NAME_IS_THE_DISPLAY_NAME, substance.nameMatchStrength("hydrocortisone"),
				"the substance is NAMED the recorded word");
		assertEquals(DrugReference.NAME_IS_ANOTHER_NAME, ester.nameMatchStrength("hydrocortisone"),
				"while the ester merely lists it among its aliases — the weaker claim");
		assertNotEquals(substance.substanceGroupKey(), ester.substanceGroupKey(),
				"and they are two substances, or nothing here is about a substance the patient is not on");
		assertEquals("[Hydrocortisone]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("hydrocortisone")).toString(),
				"so the ranked resolution names one of them");
	}

	@Test
	public void aQuestionWordPutsOnlyTheSubstanceItNamesInPlay() throws IOException {
		// The prose leg on its own: no orders at all, so the only arm that can chip is the drug-in-play
		// one, and the only thing that can put the ester in play is the question's prose.
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is hydrocortisone safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(),
				"one question word names one substance, was: " + warnings);
		assertEquals(SUBSTANCE_CHIP, warnings.get(0).getDetail());
	}

	@Test
	public void anOrderNamePutsOnlyTheSubstanceItNamesInPlay() throws IOException {
		// The order-name leg on its own, and Sarah Taylor's shape with the question naming no drug: the
		// ester can only arrive through findForActiveOrders' name resolution of her one hydrocortisone
		// order.
		List<SafetyWarning> warnings = fixtureValidator().validate("", NO_DRUG_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Hydrocortisone Injection vial 100mg"), null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(),
				"one order names one substance, was: " + warnings);
		assertEquals(SUBSTANCE_CHIP, warnings.get(0).getDetail());
	}

	@Test
	public void theEsterIsGoneWhateverArmWouldHaveRaisedIt() throws IOException {
		// Sarah Taylor's live shape entire — the order AND the question naming hydrocortisone. This is the
		// case a one-leg fix cannot pass: narrowing only the prose leg leaves the ester in the order
		// entries, where the order-driven arm reaches it because the drug-in-play set no longer holds it;
		// narrowing only the order leg leaves it in the drugs in play.
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is hydrocortisone safe for her?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Hydrocortisone Injection vial 100mg"), null,
						DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals(SUBSTANCE_CHIP, warnings.get(0).getDetail());
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("Hydrocortisone butyrate"),
					"no chip may name a substance the patient is not prescribed: " + warning.getDetail());
		}
	}

	@Test
	public void aStringThatNamesTheEsterOutrightStillPutsItInPlay() throws IOException {
		// The over-narrowing direction, and what makes the rule about the CLAIM rather than about the
		// longest name in the string: asked about the ester by its own name, the ester is the strongest
		// claimant and stays — and so does the parent substance, whose own name the string also carries.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is hydrocortisone butyrate safe for her?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Dexamethasone"), null));

		assertEquals(2, warnings.size(), "both substances the question names, was: " + warnings);
		assertTrue(details(warnings).contains(ESTER_CHIP),
				"the ester the question named outright, was: " + details(warnings));
		assertTrue(details(warnings).contains(SUBSTANCE_CHIP),
				"and the substance whose own name the string also carries, was: " + details(warnings));
	}

	@Test
	public void aCombinationOrderNameKeepsEveryConstituentSubstance() throws IOException {
		// The other drop direction, and what makes the rule read the name each row matched BY rather than
		// the whole recorded string. A combination order name denotes each of its ingredients (issue #193),
		// and with a strength appended — the ordinary shape of an order, and a real one in the 3.7.1
		// dictionary ({@code Oxycodone/Acetaminophen 5mg+325mg}) — the WHOLE string is no entry's name at
		// all, so its strongest claim is mere containment, the rank at which findImpliedSubstances
		// deliberately refuses to widen. Filtering on that would keep the earliest-matching ingredient and
		// silence the rest: the patient allergic to lamivudine and prescribed the two-drug tablet would
		// stop being told so.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.ddiFixtureService(DrugReferenceTestSupport.DDI_COMBINATION_ALLERGEN))
				.validate("", NO_DRUG_QUESTION, DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Abacavir / lamivudine 600mg+300mg"), null,
						DrugReferenceTestSupport.set("Lamivudine"), null));

		assertTrue(details(warnings).contains("The patient has a recorded allergy to Lamivudine."),
				"the constituent the allergy names, was: " + details(warnings));
		assertTrue(details(warnings).contains("Abacavir is in the same ATC class (J05AF) as the patient's"
				+ " allergy to Lamivudine — possible cross-reactivity"),
				"and the other constituent, was: " + details(warnings));
	}

	@Test
	public void aLocalizedOrderNameStillResolves() throws IOException {
		// Issue #147's shape, which the order-name leg's ranked filter must not undo: the order name is a
		// localized spelling with a strength appended, matched by the inflection-tolerant rule rather than
		// the prose one, and the entry it resolves to is named after a different string again
		// (`Acetylsalicylic acid`, whose rules all carry the token `aspirin`).
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.ddiFixtureService(DrugReferenceTestSupport.DDI_ALIAS_DRUG_NAMES))
				.validate("", NO_DRUG_QUESTION, DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirine Co 81mg"), null,
						DrugReferenceTestSupport.set("Aspirin"), null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("The patient has a recorded allergy to Acetylsalicylic acid (aspirin).",
				warnings.get(0).getDetail());
	}

	/** Verbatim KB rows: {@code Estrone} and {@code Estrone sulfate} are two substances under one
	 *  {@code rxnorm_name}, and {@code Estrone sulfate (topical)} is a presentation of the second whose
	 *  aliases carry the FIRST one's display name and nothing spelled {@code estrone sulfate}. */
	private static final String PRESENTATION_GAP_FIXTURE =
			"chartsearchai-test/ddi-presentation-alias-gap.json";

	@Test
	public void aPresentationOfASubstanceInPlayIsKeptEvenWhenItsOwnAliasesNameAnother()
			throws IOException {
		// Why the verdict is taken per SUBSTANCE and then applied to every matched row of it, rather than
		// per row. This presentation's alias list carries `estrone` — the OTHER substance's display name —
		// and nothing spelled `estrone sulfate`, so resolving ITS witnesses alone says it is not the
		// substance the name denotes, and a per-row verdict drops a row of a substance that IS in play.
		// Losing a row loses whatever sits only on it: the rule the interaction arm would have chosen
		// across the family, and the age band the dose arm would have read.
		//
		// Asserted on the resolution rather than on a chip, and deliberately: measured over the shipped KB,
		// no interaction rule sits on that presentation alone, so a chip-level case here would have to
		// invent reference data. What is real, and what this pins, is the row set the arms are handed.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(PRESENTATION_GAP_FIXTURE);

		assertEquals("[Estrone, Estrone sulfate, Estrone sulfate (topical)]",
				DrugReferenceTestSupport.names(service.findImpliedByDrugName("Estrone sulfate")).toString(),
				"the presentation must survive with its substance");
		assertEquals("[Estrone, Estrone sulfate, Estrone sulfate (topical)]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery("is it safe to give estrone sulfate?")).toString(),
				"and on the prose leg too");
		// The premise, through the production predicates: without it this passes while asserting nothing.
		DrugReference presentation = DrugReferenceTestSupport.row(service.getAll(),
				"Estrone sulfate (topical)");
		assertTrue(presentation.aliasesNaming("Estrone sulfate").contains("estrone"),
				"the presentation must carry the other substance's name, was: "
						+ presentation.aliasesNaming("Estrone sulfate"));
		assertFalse(presentation.aliasesNaming("Estrone sulfate").contains("estrone sulfate"),
				"and must NOT carry its own family's bare name, or the per-row verdict would keep it");
		assertEquals(presentation.substanceGroupKey(),
				DrugReferenceTestSupport.row(service.getAll(), "Estrone sulfate").substanceGroupKey(),
				"while being the same substance as the row that does");
	}

	@Test
	public void theInjectedReferenceRecordsCarryOnlyTheSubstancesTheQuestionNames() throws IOException {
		// The other consumer of the prose leg (DrugReferenceInjector.matchingEntries): a superset put a
		// citable reference record about the ester into the prompt as well, which no chip stood behind.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> references = DrugReferenceTestSupport
				.injectedReferences(injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Dexamethasone"), null),
						"Is hydrocortisone safe for her?"));

		// On the TEXT, not on the mappings: RecordMapping defines no toString, so a containment assertion
		// over the list itself reads identity hashes and can never fail.
		List<String> texts = new ArrayList<String>();
		for (RecordMapping reference : references) {
			texts.add(reference.getText());
		}
		assertEquals(1, texts.size(),
				"one question word is one reference record, not one per substance sharing an alias, was: "
						+ texts);
		assertTrue(texts.get(0).startsWith("Drug reference — Hydrocortisone "),
				"and it is the substance the question named, was: " + texts.get(0));
		assertFalse(texts.toString().contains("Hydrocortisone butyrate"), "was: " + texts);
	}

	/** A hand-authored {@code json} dataset carrying the one shape the DDInter and ATC parsers cannot
	 *  produce: an entry whose {@code aliases} omit its own {@code name}. See the fixture's own
	 *  {@code description}. */
	private static final String NAME_NOT_ITS_OWN_ALIAS_FIXTURE =
			"chartsearchai-test/drug-reference-name-not-its-own-alias.json";

	@Test
	public void narrowingNeverEmptiesACandidateSetEvenWhenNoMatchedRowIsTheStrongestClaimant()
			throws IOException {
		// The invariant findImpliedByQuery's javadoc states — it cannot empty a non-empty set — asserted
		// rather than assumed, on the one dataset shape that breaks the reasoning behind it.
		//
		// That reasoning is: an entry's carried alias resolves to some strongest claimant, that claimant
		// carries the same alias and so is in the matched set too, so whatever the text most strongly
		// names always survives. The middle step is a property of the PARSERS, not of this filter —
		// DdiDrugReferenceSource makes the display name alias[0] and AtcDrugReferenceSource makes it the
		// only alias, so on both of those an entry always names itself. A hand-authored `json` dataset
		// need not, and `json` is the DEFAULT sourceFormat.
		//
		// Here `Ibuprofen` publishes only `ibuprof`. The recorded-name matcher reaches `ibuprofen` from
		// that stem by its two-letter inflection allowance, so it is the rank-2 claimant on the word; the
		// PROSE matcher does not reach it at all, so it is absent from the prose candidate set for that
		// same word. Both presentations carry `ibuprofen` as an alias, so each IS named it and each is in
		// the candidate set — but neither is DISPLAY-named it, so neither is the strongest claimant, and a
		// filter that only asks "does my carried alias denote MY substance" answers no for every matched row.
		//
		// Emptying is the worst available failure: the drugs-in-play set is what every arm iterates, so a
		// question naming a drug would silently get no contraindication, no interaction and no overdose
		// check at all — and no log line says so. A superset is the pre-#209 answer and merely
		// over-reports, which for a non-blocking advisory is the safe direction.
		//
		// This is the PROSE leg specifically. Without rowsOf's fallback it returns [] here, measured.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(NAME_NOT_ITS_OWN_ALIAS_FIXTURE));

		// The premises, through the production predicates: without them this passes while asserting nothing.
		DrugReference stem = DrugReferenceTestSupport.row(service.getAll(), "Ibuprofen");
		assertFalse(stem.isNamed(stem.getName()),
				"the fixture's point is an entry whose aliases omit its own name, was: " + stem.getAliases());
		assertEquals(DrugReference.NAME_IS_THE_DISPLAY_NAME, stem.nameMatchStrength("ibuprofen"),
				"it must still be the strongest claimant on the word");
		assertEquals("[Ibuprofen tablets, Ibuprofen suspension]",
				DrugReferenceTestSupport.names(service.findByQuery("is ibuprofen 400mg safe?")).toString(),
				"while being absent from the prose candidate set for that same word");

		assertEquals("[Ibuprofen tablets, Ibuprofen suspension]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery("is ibuprofen 400mg safe?")).toString(),
				"a narrowing that keeps nothing must keep everything instead of emptying the set");

		// The order-name leg does not reach the emptying shape from the same fixture, and the reason is
		// worth recording rather than papering over: its boundary rule tolerates two inflection letters, so
		// `Ibuprofen tablets 400mg` matches the stem row `Ibuprofen` (via `ibuprof` + `en`) as well as the
		// two presentations. That row's own alias denotes its own substance, so it survives on the rule and
		// the set is non-empty without the fallback. Asserted as the exact set rather than as "non-empty",
		// because the interesting part is WHICH row survives.
		assertEquals("[Ibuprofen]", DrugReferenceTestSupport
				.names(service.findImpliedByDrugName("Ibuprofen tablets 400mg")).toString(),
				"the order-name leg keeps the row whose own alias names it, so it never needed the fallback");

		// And the consequence that makes it matter: the safety arms still run. Without the fallback this
		// question yields ZERO chips for a patient recorded allergic to the drug it names — every arm
		// iterates the drugs-in-play set, so emptying it switches the whole validator off silently.
		// Asserted on the subjects rather than on the four rendered strings: what this case is about is
		// that each admitted row was checked, not how the contraindication arms word themselves.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"is ibuprofen 400mg safe?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ibuprofen"), null));
		assertEquals(4, warnings.size(),
				"both admitted rows must still be checked, against both findings, was: " + warnings);
		for (String subject : new String[] { "Ibuprofen tablets", "Ibuprofen suspension" }) {
			assertTrue(details(warnings).toString().contains(subject),
					"every admitted row must be a chip subject, missing " + subject + ", was: "
							+ details(warnings));
		}
	}

	private static List<String> details(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			out.add(warning.getDetail());
		}
		return out;
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
