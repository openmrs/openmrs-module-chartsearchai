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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * No drug is reported as interacting with itself (issue #152).
 *
 * <p>The shipped DDInter 2.0 knowledge base carries one row pairing a drug with ITSELF —
 * {@code DDInter225}, botulinum toxin type A, 1 of 295,184 — and 25 more pairing two ROUTE VARIANTS of
 * one substance with each other (measured 2026-08-06; re-measure before relying on the figures). The
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
 * <p>Slices taken verbatim from the shipped KB, through the real {@link DdiDrugReferenceSource} parser
 * and the real {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}.
 */
public class SelfInteractionTest {

	/** Verbatim KB rows and interaction rows — see the fixture's own {@code metadata.note}. */
	private static final String FIXTURE = "chartsearchai-test/ddi-self-interaction.json";

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
		// SEROTYPES together, which this KB has no second row for — so the pair is an artifact of its
		// granularity, and as rendered it reads "Botulinum toxin type A interacts with active order
		// botulinum toxin type A".
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
