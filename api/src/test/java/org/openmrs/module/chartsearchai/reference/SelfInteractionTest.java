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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * No drug is reported as interacting with itself (issue #152).
 *
 * <p>The shipped DDInter 2.0 knowledge base carries one row pairing a drug with ITSELF —
 * {@code DDInter225}, botulinum toxin type A, 1 of 295,184 — plus further rows pairing two ROUTE
 * VARIANTS of one substance with each other. The total the guard drops is not restated here because
 * it moves whenever substance identity widens: it was 26 when issue #152 shipped and 28 after issue
 * #164 resolved two more pairs to one substance by registry id. The parser logs the live count in a
 * WARN at load, which is the figure to read. The
 * parser loaded both kinds, so a substance could be raised as interacting with itself: a chip reading
 * "Lidocaine interacts with active order lidocaine", and the same partner listed inside the drug's own
 * injected reference record.
 *
 * <p>The route-variant kind is not a cosmetic duplicate either, and it is why the guard is at the
 * SUBSTANCE level rather than on the row id alone. Every variant of a substance publishes the same
 * {@code rxnorm_name}, which is the token its rules match on and the label a chip prints, so such a
 * pair can only ever render as a substance interacting with itself — the clinical content the KB row
 * carries (concurrent systemic and topical exposure) is not expressible by anything this layer prints.
 *
 * <p>Guarded at load rather than per arm, because the interaction rows feed five consumers — the
 * drug-in-play chips, the screening arm, the question-pair arm, the promoted notes in the injected
 * reference record and the pre-answer finding derived from a chip — and one invariant at the parse
 * boundary covers all of them, and a future KB revision too.
 *
 * <p>A third shape is latent rather than shipped, and it is the only one the guard's ID leg can catch:
 * a row publishing no {@code rxnorm_name} paired with itself. 28 rows publish none and 0 of them carry
 * such a pair, so the leg cannot be exercised by any verbatim slice — the last three cases below use the
 * one fixture that authors that row, and say so.
 *
 * <p>Slices taken verbatim from the shipped KB — apart from that one authored row, which its fixture's
 * {@code metadata.note} identifies — through the real {@link DdiDrugReferenceSource} parser and the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}.
 */
public class SelfInteractionTest {

	/** Verbatim KB rows and interaction rows — see the fixture's own {@code metadata.note}. */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_SELF_INTERACTION;

	/**
	 * The shape only the guard's ID leg can catch: verbatim KB rows publishing NO {@code rxnorm_name},
	 * one of them paired with itself. That last row is the fixture's one authored row — see its own
	 * {@code metadata.note} for why no verbatim slice can carry it.
	 */
	private static final String UNNAMED_SUBSTANCE_FIXTURE =
			"chartsearchai-test/ddi-self-pair-unnamed-substance.json";

	private static DrugReference entry(List<DrugReference> entries, String name) {
		for (DrugReference entry : entries) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		throw new AssertionError("the fixture must carry the " + name + " row, was: "
				+ DrugReferenceTestSupport.names(entries));
	}

	@Test
	public void theFixtureReallyCarriesBothSelfPairShapes() throws IOException {
		// The premise, read off the fixture rather than trusted: the guard is at parse time, so the
		// only way to show the rows are really there is to read the file. Asserted on the raw JSON
		// through the same resource the parser reads.
		String json = DrugReferenceTestSupport.fixtureText(FIXTURE);

		assertTrue(json.contains("\"DDInter225\",\n   \"DDInter225\""),
				"the KB's one row pairing a drug with itself must be in the slice, was: " + json);
		assertTrue(json.contains("Lidocaine (ophthalmic)") && json.contains("Lidocaine (topical)"),
				"and the two route-variant rows the KB pairs with plain Lidocaine, was: " + json);
	}

	@Test
	public void aRowPairingADrugWithItselfIsNotLoaded() throws IOException {
		// DDInter225 x DDInter225. The mechanism text is about administering different botulinum
		// SEROTYPES together — which the KB also files on genuine cross-row pairs. Type A against type B
		// is one substance against another and stays loaded; Daxibotulinumtoxina against type A is NOT,
		// since issue #164 resolved them to one substance by registry id. So the self-pair is an artifact
		// of the KB's granularity that costs no clinical content, and as rendered it reads
		// "Botulinum toxin type A interacts with active order botulinum toxin type A".
		DrugReference botulinum = entry(DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE),
				"Botulinum toxin type A");

		for (DrugReference.Interaction rule : botulinum.getInteractions()) {
			assertFalse(botulinum.isNamed(rule.getToken()),
					"no rule of an entry may name that entry, was: " + rule.getToken());
		}
		assertEquals(1, botulinum.getInteractions().size(),
				"and the slice's genuine kanamycin pair must survive, was: "
						+ botulinum.getInteractions().size() + " rule(s)");
	}

	@Test
	public void aRowPairingTwoRouteVariantsOfOneSubstanceIsNotLoaded() throws IOException {
		// The 25 rows the id check alone cannot see. Plain Lidocaine is paired with its own ophthalmic
		// and topical rows, which share its rxnorm_name — so both rules carry the token "lidocaine",
		// which is the entry's own name.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference lidocaine = entry(entries, "Lidocaine");

		for (DrugReference.Interaction rule : lidocaine.getInteractions()) {
			assertFalse("lidocaine".equals(rule.getToken()),
					"a rule naming this entry's own substance must not be loaded, was: " + rule.getToken());
		}
		// The two genuine cross-substance pairs in the slice (kanamycin Minor, metoclopramide Major).
		assertEquals(2, lidocaine.getInteractions().size(),
				"while every genuine partner survives, was: " + lidocaine.getInteractions().size());
	}

	@Test
	public void noEntryInTheSliceCarriesARuleNamingItsOwnSubstance() throws IOException {
		// The invariant itself, over every entry, so the two cases above cannot be satisfied by a guard
		// that only recognises the shapes they name. Both sides checked: the partner variants also
		// carried the mirror row, since the parser writes every pair into BOTH drugs' entries.
		for (DrugReference entry : DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE)) {
			for (DrugReference.Interaction rule : entry.getInteractions()) {
				assertFalse(entry.isNamed(rule.getToken()), entry.getName()
						+ " must carry no rule naming itself, was: " + rule.getToken());
			}
		}
	}

	@Test
	public void aPatientOnTheDrugIsNotWarnedAboutItInteractingWithItself() throws IOException {
		// End to end, through the real validator: a patient on botulinum toxin asked about botulinum
		// toxin. Before the guard, hasActiveDrug matched the self-rule's own token against the patient's
		// own order and the chip named the drug on both sides of "interacts with active order".
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give botulinum toxin type A?",
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Botulinum toxin type A 100 units"), null,
								null, null));

		assertEquals(0, warnings.size(),
				"a drug cannot interact with itself, was: " + warnings);
	}

	@Test
	public void aPatientOnOneRouteVariantIsNotWarnedAboutTheSubstanceInteractingWithItself()
			throws IOException {
		// The route-variant route to the same nonsense, and the reason the guard is at substance level:
		// one Lidocaine order resolves all three rows, and the two self-pair rows carry the token
		// "lidocaine", so the chip read "Lidocaine interacts with active order lidocaine — Moderate".
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give lidocaine?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Lidocaine 2% injection"), null, null, null));

		assertEquals(0, warnings.size(),
				"one substance's route variants are not an interacting pair, was: " + warnings);
	}

	@Test
	public void agenuinePairInTheSameSliceIsStillReported() throws IOException {
		// The negative control, without which every assertion above passes on a guard that drops
		// everything. Metoclopramide is a real cross-substance partner of lidocaine, rated Major against
		// the systemic row and Moderate against the two variants — so this also shows the #162 collapse
		// composing with the guard: one chip, at the pair's most severe rating.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", "Is it safe to give lidocaine?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Metoclopramide 10mg"), null, null, null));

		assertEquals(1, warnings.size(), "the genuine pair must still chip, was: " + warnings);
		assertEquals("Lidocaine", warnings.get(0).getDrug());
		assertTrue(warnings.get(0).getDetail()
				.startsWith("Lidocaine interacts with active order metoclopramide — Major. "),
				"at the pair's most severe rating, was: " + warnings.get(0).getDetail());
	}

	@Test
	public void theFixtureForTheIdLegReallyPublishesNoSubstanceNameOnEitherRow() throws IOException {
		// The premise of the two cases below, read off the FILE and off the parsed model, because both
		// halves have to hold: the rows must publish no rxnorm_name (so substanceKey is null and the
		// substance leg is inert), and the self-pair row must actually be in the file (the guard drops it,
		// so it is invisible in the parser's output).
		String json = DrugReferenceTestSupport.fixtureText(UNNAMED_SUBSTANCE_FIXTURE);
		assertTrue(json.contains("\"DDInter1075\",\n   \"DDInter1075\""),
				"the slice must pair the rxnorm-less row with itself, was: " + json);

		for (DrugReference row : DrugReferenceTestSupport.ddiFixtureEntries(UNNAMED_SUBSTANCE_FIXTURE)) {
			assertNull(row.getSubstanceName(), row.getName()
					+ " must publish no substance name, or the substance leg would catch the self-pair and "
					+ "this file would assert nothing about the id leg");
		}
	}

	@Test
	public void aSelfPairSurvivesOnTheIdLegWhenTheRowPublishesNoSubstanceName() throws IOException {
		// The guard's id leg, which the shipped KB cannot exercise: 28 of its 2283 rows publish no
		// rxnorm_name, and 0 of those carry a self-pair today, so the leg is latent — deleting it leaves
		// every other case passing. It is not redundant. With no substance name there is no substanceKey to
		// compare, so for such a row the id equality is the ONLY test between the KB and a rule whose token
		// is the entry's own name; the chip a refresh would then produce reads "Liotrix interacts with
		// active order liotrix", which is the nonsense issue #152 exists to stop. Asserted through the real
		// validator on the real production path, not on the parsed rule list alone, so it pins the
		// clinician-visible outcome.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(UNNAMED_SUBSTANCE_FIXTURE))
				.validate("", "Is it safe to give liotrix?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Liotrix 60mcg"), null, null, null));

		assertEquals(0, warnings.size(),
				"a row paired with itself must not be loaded even when it names no substance, was: "
						+ warnings);
	}

	@Test
	public void twoRowsThatBothPublishNoSubstanceNameAreStillTwoDrugs() throws IOException {
		// The other half of the id leg, and the negative control without which the case above passes on a
		// guard that drops every pair among rxnorm-less rows. substanceKey is null for BOTH of these rows,
		// so a substance comparison that treated two nulls as equal — dropping the {@code substance != null}
		// half of the guard — would silently discard all 13 shipped KB rows joining two of the 28. This is a
		// genuine, verbatim KB pair and it must still chip.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(UNNAMED_SUBSTANCE_FIXTURE))
				.validate("", "Is it safe to give liotrix?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Multivitamin with iron"), null, null, null));

		assertEquals(1, warnings.size(), "the genuine pair must still chip, was: " + warnings);
		assertEquals("Liotrix", warnings.get(0).getDrug());
		assertTrue(warnings.get(0).getDetail().startsWith(
				"Liotrix interacts with active order multivitamin with iron — Moderate. "),
				"was: " + warnings.get(0).getDetail());
	}

	@Test
	public void theInjectedReferenceRecordDoesNotListTheDrugAsItsOwnPartner() throws IOException {
		// The other prompt surface. render() writes every interaction row as "label (note)", so a
		// self-pair put the drug's own name in its own Interactions: list — a reference record telling
		// the model that lidocaine interacts with lidocaine.
		String text = DrugReferenceTestSupport
				.injectedReference(DrugReferenceTestSupport
						.injector(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
								DrugReferenceTestSupport.ctx(60, null, null, null, null, null),
								"Is it safe to give lidocaine?"))
				.getText();

		assertTrue(text.startsWith("Drug reference — Lidocaine"), "was: " + text);
		String interactions = text.substring(text.indexOf("Interactions:"));
		assertFalse(interactions.contains("lidocaine ("),
				"the record must not list the drug as its own interaction partner, was: " + interactions);
	}
}
