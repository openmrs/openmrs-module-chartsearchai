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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A substance is not reported as cross-reactive, or interacting, with ITSELF (issue #164) — and two
 * substances the reference data files under one substance NAME still get a chip each (issues #121,
 * #145).
 *
 * <p><b>The defect, two routes.</b> Both were measured live on the 3.7.1 standalone and both survive
 * the substance key #145/#160/#173 built, because neither is a key that was too wide — one is a test
 * that was too narrow and the other is a key that was too conservative.
 * <ul>
 *   <li><b>The mirror path.</b> {@code IDENTITY} was awarded on ENTRY identity
 *       ({@code allergen == ref}). When the row an allergy resolves to is a SIBLING that the question
 *       does not itself put in play, the group holds no identity candidate at all, so the best
 *       available relationship is the class comparison — against the same substance. Measured as
 *       "Is Tetryzoline (nasal) safe?" against an allergy recorded as {@code Tetryzoline
 *       (ophthalmic)}, and as the Pfizer/Tozinameran pair below.</li>
 *   <li><b>Stem divergence.</b> {@code Pfizer-BioNTech Covid-19 Vaccine} and {@code Tozinameran}
 *       publish one {@code rxnorm_name} and one {@code rxcui} but no display stem in common, so the
 *       stem VETO kept them apart — and the resulting chip reads as a self-reference anyway. The same
 *       shape sits in the interaction arm: {@code Daxibotulinumtoxina} against
 *       {@code Botulinum toxin type A}.</li>
 * </ul>
 *
 * <p><b>What decides that two rows are one substance</b>, and why the stem alone could not. The
 * reference data's substance name over-merges — {@code Omeprazole}/{@code Esomeprazole} publish one
 * {@code rxnorm_name}, one {@code rxcui} and one ATC code and are two substances — so something has
 * to veto it. The stem was that veto, and it is a display-name heuristic: it separates the two PPIs
 * correctly and separates {@code Tozinameran} from its own brand row incorrectly, because it cannot
 * tell a second SUBSTANCE from a second NAME. The reference data can: each row carries a
 * {@code drugbank_id}, an identifier of a substance rather than of a presentation. So the veto is now
 * that identifier — a substance-name family that names TWO OR MORE DrugBank substances holds more
 * than one substance and keeps the stem in force; one that names at most one is a single substance
 * and has nothing for the stem to veto. See {@link DrugReference#substanceKey()} for the
 * measurements.
 *
 * <p>Both directions are asserted here, against slices taken verbatim from the shipped KB, through
 * the real {@link DdiDrugReferenceSource} parser and the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}.
 */
public class SubstanceIdentityTest {

	/** Verbatim KB rows and interaction rows — see the fixture's own {@code metadata.note}. */
	private static final String FIXTURE = "chartsearchai-test/ddi-substance-identity.json";

	/** The must-NOT-collapse pair, in the slice that already carries it: {@code Omeprazole} and
	 *  {@code Esomeprazole} under one {@code rxnorm_name}. Read from there rather than copied here, so
	 *  the two files cannot come to disagree about what the KB says. */
	private static final String PPI_FIXTURE = "chartsearchai-test/ddi-contra-route-variants.json";

	private static DrugReference row(List<DrugReference> entries, String name) {
		for (DrugReference entry : entries) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		throw new AssertionError("the fixture must carry the " + name + " row, was: "
				+ DrugReferenceTestSupport.names(entries));
	}

	@Test
	public void oneSubstanceNameAndTwoDrugbankIdsIsTwoSubstancesWhileOneOfEachIsOne() throws IOException {
		// The criterion itself, on the two families that show it is not the stem: BOTH are one
		// rxnorm_name spread over rows whose display stems differ, and they must resolve OPPOSITE ways.
		// Without this contrast either half alone is satisfiable by a key that ignores the registry id
		// (the stem separates both pairs) or by one that ignores the stem (neither pair is separated).
		List<DrugReference> vaccine = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference tozinameran = row(vaccine, "Tozinameran");
		DrugReference pfizer = row(vaccine, "Pfizer-BioNTech Covid-19 Vaccine");
		assertEquals(DrugReference.normalizeName(tozinameran.getSubstanceName()),
				DrugReference.normalizeName(pfizer.getSubstanceName()),
				"precondition: the two vaccine rows must publish ONE substance name");
		assertNotEquals(DrugReference.normalizeName(tozinameran.getName()),
				DrugReference.normalizeName(pfizer.getName()),
				"precondition: with different display names, or the stem would have merged them anyway");
		assertNotNull(tozinameran.getSubstanceId(),
				"the family names exactly one DrugBank substance, so the parser resolves an id for it");
		assertEquals(tozinameran.getSubstanceId(), pfizer.getSubstanceId(),
				"and BOTH rows take it, including the one carrying no drugbank_id of its own");
		assertEquals(tozinameran.substanceKey(), pfizer.substanceKey(),
				"a substance-name family naming ONE DrugBank substance is one substance");

		List<DrugReference> ppis = DrugReferenceTestSupport.ddiFixtureEntries(PPI_FIXTURE);
		DrugReference omeprazole = row(ppis, "Omeprazole");
		DrugReference esomeprazole = row(ppis, "Esomeprazole");
		assertEquals(DrugReference.normalizeName(omeprazole.getSubstanceName()),
				DrugReference.normalizeName(esomeprazole.getSubstanceName()),
				"precondition: the two PPI rows must publish ONE substance name too");
		assertNull(omeprazole.getSubstanceId(),
				"but that family names TWO DrugBank substances, so the parser resolves no id for it and "
						+ "the display stem stays in force");
		assertNull(esomeprazole.getSubstanceId());
		assertNotEquals(omeprazole.substanceKey(), esomeprazole.substanceKey(),
				"while a family naming TWO DrugBank substances is two substances — the enalapril/"
						+ "enalaprilat decision of issue #121, which the widening must not undo");
	}

	@Test
	public void theFixtureReallyCarriesTheMirrorShape() throws IOException {
		// The premise of the case below, through the production resolvers: the allergy must resolve to a
		// row the QUESTION does not put in play. Without that the identity test would already fire on
		// entry identity and the case would assert nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		List<DrugReference> inPlay = service.findByQuery("Is Tetryzoline (nasal) safe for her?");
		assertEquals(1, inPlay.size(), "the question must resolve exactly one row, was: "
				+ DrugReferenceTestSupport.names(inPlay));
		assertEquals("Tetryzoline (nasal)", inPlay.get(0).getName());

		DrugReference allergen = service.lookupByToken("Tetryzoline (ophthalmic)");
		assertNotNull(allergen, "the allergy must resolve to a row");
		assertEquals("Tetryzoline (ophthalmic)", allergen.getName());
		assertNotSame(inPlay.get(0), allergen,
				"and it must be a DIFFERENT row from the one in play — the mirror shape");
		assertEquals(allergen.substanceGroupKey(), inPlay.get(0).substanceGroupKey(),
				"while the two are one substance, which is what the identity test has to see");
	}

	@Test
	public void anAllergenRowThatIsNotItselfInPlayIsADirectAllergyNotCrossReactivity() throws IOException {
		// The mirror path. Pre-fix this raised "Tetryzoline (nasal) (tetrahydrozoline) is in the same ATC
		// class (R01AA) as the patient's allergy to Tetryzoline (ophthalmic) (tetrahydrozoline) —
		// possible cross-reactivity": one substance, reported as cross-reactive with itself, because the
		// only row that could have matched by identity was not among the drugs in play.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is Tetryzoline (nasal) safe for her?", DrugReferenceTestSupport.ctx(60, null,
						null, null, DrugReferenceTestSupport.set("Tetryzoline (ophthalmic)"), null));

		assertEquals(1, warnings.size(), "one substance, one finding, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, warnings.get(0).getType());
		assertEquals("The patient has a recorded allergy to Tetryzoline (ophthalmic) (tetrahydrozoline).",
				warnings.get(0).getDetail(),
				"an allergy to the same substance is a DIRECT allergy, and the chip names the patient's "
						+ "own record rather than the row the question resolved to");
		assertEquals("Tetryzoline (ophthalmic) (tetrahydrozoline)", warnings.get(0).getDrug(),
				"including on the chip's drug field, so the two cannot name different things");
	}

	@Test
	public void theFixtureReallyCarriesTheStemDivergentPair() throws IOException {
		// The premise of the case below: the brand row must be in play on its own, and the allergy must
		// resolve to the row whose display stem differs from it.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		List<DrugReference> inPlay = service
				.findByQuery("Is the Pfizer-BioNTech Covid-19 Vaccine safe for him?");
		assertTrue(DrugReferenceTestSupport.names(inPlay).contains("Pfizer-BioNTech Covid-19 Vaccine"),
				"the brand row must be in play, was: " + DrugReferenceTestSupport.names(inPlay));

		DrugReference allergen = service.lookupByToken("Tozinameran");
		assertNotNull(allergen, "the allergy must resolve to a row");
		assertEquals("Tozinameran", allergen.getName());
		assertNotEquals(DrugReference.normalizeName(allergen.getName()),
				DrugReference.normalizeName("Pfizer-BioNTech Covid-19 Vaccine"),
				"and the two display names must diverge, or the stem would already have merged them");
	}

	@Test
	public void aStemDivergentRowOfTheAllergensOwnSubstanceIsADirectAllergyNotCrossReactivity()
			throws IOException {
		// Kevin Brown's live shape: a coded Tozinameran allergy, asked about the Pfizer vaccine. Pre-fix
		// this raised the correct identity chip AND "Pfizer-BioNTech Covid-19 Vaccine (sars-cov-2
		// (covid-19) vaccine, mrna spike protein) is in the same ATC class (J07BN) as the patient's
		// allergy to Tozinameran (sars-cov-2 (covid-19) vaccine, mrna spike protein) — possible
		// cross-reactivity": the KB's own rxcui and rxnorm_name say those are one substance.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is the Pfizer-BioNTech Covid-19 Vaccine safe for him?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Tozinameran"), null));

		assertEquals(1, warnings.size(),
				"one substance under two names is one clinical fact, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Tozinameran (sars-cov-2 (covid-19) vaccine,"
				+ " mrna spike protein).", warnings.get(0).getDetail());
	}

	@Test
	public void aStemDivergentPairOfOneSubstanceIsNotAnInteraction() throws IOException {
		// The same shape on the interaction arm, and the reason the fix is one key rather than one patch
		// per arm: the parse-time self-pair guard of issue #152 already asks "are these two rows one
		// substance?", so widening what that means removes the row before any arm can render it. Pre-fix
		// this raised "Daxibotulinumtoxina (botulinum toxin type a) interacts with active order botulinum
		// toxin type a — Moderate. …", a drug interacting with itself.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give daxibotulinumtoxina?",
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Botulinum toxin type A 100 units"), null, null,
								null));

		assertEquals(0, warnings.size(),
				"two rows of one substance are not an interacting pair, was: " + warnings);
	}

	@Test
	public void aGenuineCrossSubstancePairInTheSameSliceIsStillReported() throws IOException {
		// The negative control, without which the case above passes on a guard that drops everything.
		// Botulinum Toxin Type B publishes a DIFFERENT rxnorm_name, so it is a different substance and
		// the KB's Moderate pair with it must survive — the serotype-pair distinction #173's hardening
		// found the old javadoc denying.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give daxibotulinumtoxina?",
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Botulinum Toxin Type B 5000 units"), null, null,
								null));

		assertEquals(1, warnings.size(), "the genuine pair must still chip, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_INTERACTION, warnings.get(0).getType());
		assertTrue(warnings.get(0).getDetail().startsWith("Daxibotulinumtoxina (botulinum toxin type a) "
				+ "interacts with active order botulinum toxin type b — Moderate. "),
				"was: " + warnings.get(0).getDetail());
	}

	@Test
	public void aFamilyThatNamesNoRegistrySubstanceAtAllIsStillOneSubstance() throws IOException {
		// The criterion's third branch, and the one the two cases above cannot reach: the KB files
		// `Typhoid vaccine (live)` and `Typhoid vaccine live` under one rxnorm_name and one rxcui with NO
		// drugbank_id on either, so the registry distinguishes nothing and the family is one substance.
		// Neither row carries an ATC code, so the class arms are provably silent for them — before this
		// change an allergy to one of them, asked about the other, produced NO chip at all rather than a
		// wrong one, which is issue #135's direct-allergy gap reached through stem divergence.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		List<DrugReference> inPlay = service.findByQuery("Is Typhoid vaccine (live) safe for her?");
		assertEquals(1, inPlay.size(), "precondition: the question must resolve exactly one row, was: "
				+ DrugReferenceTestSupport.names(inPlay));
		DrugReference allergen = service.lookupByToken("Typhoid vaccine live");
		assertNotNull(allergen, "precondition: the allergy must resolve to a row");
		assertNotSame(inPlay.get(0), allergen, "precondition: and to the OTHER row");
		assertTrue(inPlay.get(0).atcSubgroups().isEmpty() && allergen.atcSubgroups().isEmpty(),
				"precondition: neither row may carry an ATC subgroup, or a class chip could mask this");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is Typhoid vaccine (live) safe for her?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Typhoid vaccine live"), null));

		assertEquals(1, warnings.size(), "the direct allergy must be reported, was: " + warnings);
		assertEquals("The patient has a recorded allergy to Typhoid vaccine live (salmonella typhi ty21a"
				+ " live antigen).", warnings.get(0).getDetail());
	}

	@Test
	public void twoSubstancesTheKbGivesTwoRegistryIdsStayTwoInteractionChips() throws IOException {
		// Issue #121's decision, on the arm where the widening could have undone it: enalapril and
		// enalaprilat are a prodrug and its active metabolite, they share 376 of the shipped KB's
		// interaction partners, and they must stay two subjects. The KB gives them two rxnorm_names as
		// well as two drugbank_ids, so they are not the sharp edge — that is Omeprazole/Esomeprazole
		// above — but they are the case #121 named, and a key that merged on either would answer this
		// with one chip.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give enalapril or enalaprilat?",
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Potassium chloride 600mg"), null, null, null));

		assertEquals(2, warnings.size(), "a prodrug and its metabolite are two drugs, was: " + warnings);
		assertTrue(warnings.get(0).getDetail().startsWith(
				"Enalapril interacts with active order potassium chloride — Major. "),
				"was: " + warnings.get(0).getDetail());
		assertTrue(warnings.get(1).getDetail().startsWith(
				"Enalaprilat interacts with active order potassium chloride — Major. "),
				"was: " + warnings.get(1).getDetail());
	}
}
