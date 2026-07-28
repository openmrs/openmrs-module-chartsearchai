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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Synonym-augmented chip labels: a safety warning about a drug whose dataset display name
 * diverges from its everyday generic ("Acetylsalicylic acid" vs "aspirin") must carry both,
 * or the most critical chip on the page reads as if it concerns a different drug than the one
 * the clinician asked about and the chart records (measured 2026-07-29: "Is it safe to give
 * her aspirin?" produced a contraindication chip naming only "Acetylsalicylic acid" against a
 * chart allergy recorded as "Aspirin"). Renaming entries outright was measured and rejected:
 * of the full KB's 276 diverging names, most are INN-vs-USAN pairs or worse, and the lidocaine
 * route variants share one RxNorm name — a swap mistranslates or collapses them. So display
 * names stay, and labels append the generic as a parenthetical when it genuinely diverges.
 */
public class DrugSafetyChipLabelTest {

	@Test
	public void divergingDisplayNameCarriesTheGenericSynonym() {
		DrugReference aspirin = new DdiDrugReferenceSource().load().stream()
				.filter(r -> "Acetylsalicylic acid".equalsIgnoreCase(r.getName())).findFirst().orElseThrow();
		assertEquals("aspirin", aspirin.getGenericName(),
				"a display name that diverges from the RxNorm generic must carry it as a synonym");
		assertEquals("Acetylsalicylic acid (aspirin)", aspirin.displayLabel(),
				"the display label must show both vocabularies");
	}

	@Test
	public void matchingDisplayNameCarriesNoSynonym() {
		DrugReference warfarin = new DdiDrugReferenceSource().load().stream()
				.filter(r -> "Warfarin".equalsIgnoreCase(r.getName())).findFirst().orElseThrow();
		assertNull(warfarin.getGenericName(),
				"a display name already containing the generic needs no synonym");
		assertEquals("Warfarin", warfarin.displayLabel());
	}

	@Test
	public void routeVariantNamesAreNotCollapsedIntoTheSharedGeneric() throws Exception {
		// The lidocaine variants all share rxnorm "lidocaine"; their suffixed display names
		// contain it, so no synonym is appended and the variants stay distinguishable.
		try (InputStream in = DrugSafetyChipLabelTest.class.getClassLoader()
				.getResourceAsStream("chartsearchai-test/ddi-rxcui-collision.json")) {
			List<DrugReference> entries = DdiDrugReferenceSource.parse(in);
			for (DrugReference entry : entries) {
				assertNull(entry.getGenericName(),
						entry.getName() + " contains its generic and must not gain a synonym");
			}
		}
	}

	@Test
	public void contraindicationChipLabelsCarryBothVocabularies() {
		// The measured scenario end-to-end: aspirin allergy on the chart, aspirin proposed by
		// the question — the chip must be recognizable against both the question ("aspirin")
		// and the dataset ("Acetylsalicylic acid").
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterService());
		List<SafetyWarning> warnings = validator.validate(
				"The patient has a recorded severe allergy to Aspirin.", "Is it safe to give her aspirin?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("aspirin"), null));

		assertTrue(warnings.stream().anyMatch(w -> w.getType().equals(SafetyWarning.TYPE_CONTRAINDICATION)
				&& w.getDrug().equals("Acetylsalicylic acid (aspirin)")
				&& w.getDetail().contains("Acetylsalicylic acid (aspirin)")),
				"the allergy chip must carry the synonym-augmented label, was: " + warnings);
	}

	@Test
	public void classChipOrderNamesCarryBothVocabularies() {
		// The other display surface: the duplicate-therapy/cross-reactivity chip names the
		// ACTIVE ORDER via the dataset entry — it must be synonym-augmented the same way
		// ("as active order Acetylsalicylic acid (aspirin)").
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		List<SafetyWarning> warnings = validator.validate(
				"Ibuprofen could be considered.", "Can she take ibuprofen?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
						DrugReferenceTestSupport.set("N02BA01"), null, null));

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "Acetylsalicylic acid (aspirin)"),
				"the class chip must name the order with the synonym-augmented label, was: " + warnings);
	}
}
