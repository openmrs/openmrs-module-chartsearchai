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
 *
 * <p>The three parser-model cases here read the BUNDLED dataset, which since ADR Decision 36 is the whole
 * knowledge base — deliberately, and unlike the cases that reach for
 * {@link DrugReferenceTestSupport#ddinterEntries()}: each is a statement about what the parser does to
 * any real DDInter document rather than about one bounded slice, and the interning case reasons about the
 * full KB's ~300k rows explicitly, so the shipped dataset is the honest data for it rather than merely a
 * larger one.
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
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_RXCUI_COLLISION);
		for (DrugReference entry : entries) {
			assertNull(entry.getGenericName(),
					entry.getName() + " contains its generic and must not gain a synonym");
		}
	}

	@Test
	public void redundantSynonymsAreNeverRendered() {
		// displayLabel defends itself for ANY source (a curated json file can bind genericName
		// too, bypassing the ddinter parser's guard): a synonym that merely repeats or extends
		// the name ("Kava (kava preparation)", "Aspirin (Aspirin)") adds qualifier noise, not
		// recognition, and is suppressed. 61 of the full KB's 276 divergences are this shape.
		DrugReference kava = new DrugReference();
		kava.setName("Kava");
		kava.setGenericName("kava preparation");
		assertEquals("Kava", kava.displayLabel(),
				"a generic that contains the name is redundancy, not a synonym");

		DrugReference plain = new DrugReference();
		plain.setName("Aspirin");
		plain.setGenericName("aspirin");
		assertEquals("Aspirin", plain.displayLabel(),
				"a generic equal to the name must render plainly");
	}

	@Test
	public void severityStringsAreInternedAcrossTheParsedModel() {
		// The parser's documented memory policy: per-pair strings are interned to the unique
		// vocabulary. Severity has exactly four values across ~300k full-KB rows — without
		// interning, the structured field alone retains ~13.5 MB for the module lifetime.
		DrugReference warfarin = new DdiDrugReferenceSource().load().stream()
				.filter(r -> "Warfarin".equalsIgnoreCase(r.getName())).findFirst().orElseThrow();
		List<DrugReference.Interaction> majors = warfarin.getInteractions().stream()
				.filter(i -> "Major".equals(i.getSeverity()))
				.collect(java.util.stream.Collectors.toList()); // Stream.toList() is Java 16+; target is 11
		assertTrue(majors.size() >= 2, "precondition: warfarin has several Major rows");
		assertTrue(majors.get(0).getSeverity() == majors.get(1).getSeverity(),
				"equal severities must share one interned String instance");
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
				&& w.getDetail().equals("The patient has a recorded allergy to Acetylsalicylic acid (aspirin).")),
				"the allergy detail must be one complete standalone sentence naming the drug exactly once"
						+ " (renderers show the detail alone; the drug field is grouping metadata), was: "
						+ warnings);
	}

	@Test
	public void displayLabelNeverLeaksIntoTheRenderedRecordText() {
		// Prompt stability is this slice's own settled priority: the injected record the LLM
		// sees renders getName(), never the synonym-augmented label. Every "Drug reference — X"
		// containment assertion elsewhere would still pass if render() switched to the label
		// (the label starts with the name), so the absence needs its own pin.
		String rendered = DrugReferenceTestSupport
				.injectedDdinterReferenceText("Is it safe to give her aspirin?");
		assertTrue(rendered.contains("Acetylsalicylic acid"),
				"precondition: the aspirin reference record renders its display name");
		assertTrue(!rendered.contains("Acetylsalicylic acid (aspirin)"),
				"the synonym-augmented label is chip-display only and must never enter prompt text");
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
				"Ibuprofen", "Ibuprofen is in the same cross-reactivity group",
				"active order Acetylsalicylic acid (aspirin)"),
				"the class detail must be a standalone sentence leading with the subject drug and naming"
						+ " the order with the synonym-augmented label, was: " + warnings);
	}
}
