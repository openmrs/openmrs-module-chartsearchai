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
		// one, and the only thing that can put the ester in play is findByQuery.
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

	@Test
	public void theInjectedReferenceRecordsCarryOnlyTheSubstancesTheQuestionNames() throws IOException {
		// The other consumer of the prose leg (DrugReferenceInjector.matchingEntries): a superset put a
		// citable reference record about the ester into the prompt as well, which no chip stood behind.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<?> references = DrugReferenceTestSupport.injectedReferences(injector.injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dexamethasone"), null),
				"Is hydrocortisone safe for her?"));

		assertEquals(1, references.size(),
				"one question word is one reference record, not one per substance sharing an alias, was: "
						+ references);
		assertFalse(references.toString().contains("Hydrocortisone butyrate"), "was: " + references);
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
