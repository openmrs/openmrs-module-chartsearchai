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

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A co-medication that is BOTH an explicit interaction partner AND in the same ATC subgroup used to
 * raise two {@link SafetyWarning#TYPE_INTERACTION} chips (rule arm + class arm). The two arms are now
 * folded into one chip per pair. Driven through the real {@link DrugSafetyValidator} over a fixture
 * where lisinopril/enalapril each interact with ramipril and all three share subgroup C09AA.
 *
 * <p>The second fixture ({@link #FOLD_FIXTURE}) covers what the order's ATC code cannot correlate on
 * its own: an aspirin entry publishing the three ATC codes DDInter's does, rules against it carrying
 * only the first, and an order mapped to the third. See {@code DrugSafetyValidator.ruleAbout}.
 */
public class DuplicateInteractionChipTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-sameclass.json";

	private static final String FOLD_FIXTURE = "chartsearchai-test/drug-reference-crossarm-fold.json";

	private DrugSafetyValidator validator() throws Exception {
		return DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	/**
	 * A validator over {@link #FOLD_FIXTURE} WITH the real bundled cross-reactivity groups, which
	 * {@code setEntries} otherwise pins empty — ibuprofen and aspirin sit in different ATC branches,
	 * so the curated NSAID group is the only thing that class-links them.
	 */
	private DrugSafetyValidator foldValidator() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FOLD_FIXTURE));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return DrugReferenceTestSupport.validator(service);
	}

	/** Active order: ramipril (ATC C09AA05). */
	private PatientClinicalContext ramiprilActive() {
		return DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("C09AA05"), null, null);
	}

	private long interactionCount(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())
						&& drug.equalsIgnoreCase(w.getDrug()))
				.count();
	}

	private String onlyDetail(List<SafetyWarning> warnings) {
		assertEquals(1, warnings.size(), "exactly one warning expected, was: " + warnings);
		return warnings.get(0).getDetail();
	}

	@Test
	public void sameClassPairWithMechanismFoldsToOneContentfulChip() throws Exception {
		// Lisinopril asked about, ramipril active: explicit interaction (with a mechanism) AND same
		// ATC subgroup C09AA. One chip, carrying both the duplicate-therapy relationship and the note.
		List<SafetyWarning> warnings = validator().validate(
				"Lisinopril is a reasonable choice.", ramiprilActive());

		assertEquals(1, interactionCount(warnings, "Lisinopril"),
				"the rule arm and the class arm must fold into a single interaction chip, not two");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Lisinopril", "duplicate therapy", "additive hyperkalemia"),
				"the single chip should carry both the class/duplicate-therapy relationship and the mechanism");
	}

	@Test
	public void sameClassPairWithoutMechanismStillYieldsTheInformativeClassChip() throws Exception {
		// Enalapril's ramipril interaction carries no mechanism note. Naively preferring the rule arm
		// would drop to a contentless chip; folding keeps the informative class relationship.
		List<SafetyWarning> warnings = validator().validate(
				"Enalapril is a reasonable choice.", ramiprilActive());

		assertEquals(1, interactionCount(warnings, "Enalapril"),
				"still exactly one chip for the pair");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Enalapril", "duplicate therapy", "C09AA"),
				"with no mechanism note, the surviving chip must still carry the class/duplicate-therapy info");
	}

	@Test
	public void aPairIsFoldedWhenTheRuleNamesTheOrdersSubstanceUnderADifferentAtcCode() throws Exception {
		// The correlation the order's ATC code alone cannot make. Ibuprofen's rules against aspirin
		// carry aspirin's FIRST code (A01AD05), exactly as the ddinter parser writes them, while the
		// order is mapped to N02BA01 — so the two arms describe the same partner under two codes.
		// Resolving the order's code to the aspirin ENTRY and asking whether the rule names that entry
		// is what sees they are one pair.
		List<SafetyWarning> warnings = foldValidator().validate("Ibuprofen could help with the pain.",
				"Can I give ibuprofen?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("aspirin 81mg"),
						DrugReferenceTestSupport.set("N02BA01"), null, null));

		assertEquals("Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. "
				+ "Ibuprofen blunts the irreversible platelet inhibition of low-dose aspirin. Ibuprofen "
				+ "is in the same cross-reactivity group (NSAID) as active order Acetylsalicylic acid "
				+ "(aspirin) — possible additive or duplicate-class therapy", onlyDetail(warnings),
				"one chip must carry the rule's partner, its mechanism and the class relationship — and"
						+ " name that partner ONCE (issue #292): the rule sentence read \"active order"
						+ " aspirin\" beside the class sentence's resolved entry name until the fold"
						+ " reconciled them");
	}

	@Test
	public void foldedChipKeepsTheMostSevereRuleRowNotTheFirst() throws Exception {
		// Two aspirin rows reach the chip, Moderate first and Major second. The fold must be built on
		// the row the rule arm's own grouping chose (most severe), not on whichever row the dataset
		// happens to list first — the failure mode a first-wins collapse has. A named pin of its own
		// over the same arrangement as the test above, deliberately: which row feeds the fold is a
		// separate decision from how the fold composes, and it should not rest only on that test's
		// exact-string assertion, which a later reader could reasonably loosen.
		String detail = onlyDetail(foldValidator().validate("Ibuprofen could help with the pain.",
				"Can I give ibuprofen?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("aspirin 81mg"),
						DrugReferenceTestSupport.set("N02BA01"), null, null)));

		assertTrue(detail.contains("Major. Ibuprofen blunts"),
				"the folded chip must carry the Major row's mechanism, was: " + detail);
		assertFalse(detail.contains("Additive gastrointestinal"),
				"the folded chip must not carry the Moderate row it outranks, was: " + detail);
	}

	@Test
	public void foldedChipKeepsTheDualVocabularyDisplayLabel() throws Exception {
		// The same pair from the other side, so the subject is the entry with a generic-name synonym.
		// Both halves of the folded detail must name it as the chip label does; naming it by the bare
		// entry name would drop the synonym a clinician searches on.
		List<SafetyWarning> warnings = foldValidator().validate(
				"Acetylsalicylic acid could be continued.", "Is aspirin safe here?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("ibuprofen 400mg"),
						DrugReferenceTestSupport.set("M01AE01"), null, null));

		assertEquals("Acetylsalicylic acid (aspirin)", warnings.get(0).getDrug(),
				"the chip's drug must keep the dual-vocabulary display label");
		assertEquals("Acetylsalicylic acid (aspirin) interacts with active order Ibuprofen — Major. "
				+ "Ibuprofen blunts the irreversible platelet inhibition of low-dose aspirin. "
				+ "Acetylsalicylic acid (aspirin) is in the same cross-reactivity group (NSAID) as "
				+ "active order Ibuprofen — possible additive or duplicate-class therapy",
				onlyDetail(warnings),
				"both sentences of the folded detail must use the display label — for the SUBJECT, which"
						+ " is what this case is about, and since issue #292 for the PARTNER too, which"
						+ " the rule sentence used to call \"ibuprofen\" beside the class sentence's"
						+ " \"Ibuprofen\"");
	}

	@Test
	public void aClassRelatedOrderWithNoRuleKeepsItsOwnChipBesideAFoldedOne() throws Exception {
		// Two active orders: aspirin, which the rule arm also reaches, and naproxen, which only the
		// class arm does. Folding must be per (drug, order) — a fold that merely asked "does any rule
		// exist?" would swallow naproxen's duplicate-therapy finding into the aspirin chip.
		List<SafetyWarning> warnings = foldValidator().validate("Ibuprofen could help with the pain.",
				"Can I give ibuprofen?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("aspirin 81mg", "naproxen 500mg"),
						DrugReferenceTestSupport.set("N02BA01", "M01AE02"), null, null));

		assertEquals(2, warnings.size(), "nothing but the two interaction chips, was: " + warnings);
		assertEquals(2, interactionCount(warnings, "Ibuprofen"),
				"one chip per (drug, active order): the folded aspirin pair and naproxen's own, was: "
						+ warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "is in the same ATC class (M01AE) as active order Naproxen",
				"possible duplicate therapy"),
				"the class-only pair keeps the standalone class chip, naming the order, was: " + warnings);
	}

	@Test
	public void aRuleOnlyPairIsWordedExactlyAsBefore() throws Exception {
		// The same aspirin order with no ATC mapping, so the class arm has nothing to say. A chip no
		// fold applies to must render byte-identically to what it always did — the fold must not leak a
		// trailing sentence, or a full stop, into single-arm chips. The one test here that passes
		// against the pre-fold validator as well, which is exactly what it is for.
		//
		// It still does after issue #339, which makes an unfolded chip ask the same reconciliation a
		// folded one asks: this context carries NO ATC codes and no per-order list, so orderPartners
		// resolves no co-medication at all and the reconciliation declines. What that issue moved is
		// the name on a chip whose partner the ladder DID reach; this arrangement is not one.
		List<SafetyWarning> warnings = foldValidator().validate("Ibuprofen could help with the pain.",
				"Can I give ibuprofen?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("aspirin 81mg"), null, null, null));

		assertEquals("Ibuprofen interacts with active order aspirin — Major. Ibuprofen blunts the "
				+ "irreversible platelet inhibition of low-dose aspirin.", onlyDetail(warnings),
				"an unfolded rule chip must be unchanged");
	}

	@Test
	public void aFoldedChipStillSuppressesTheScreeningArmsPlainChipForTheSamePair() throws Exception {
		// The seam the fold opens in issue #113's screen. That arm stands down from a pair the
		// drug-in-play arm already reported by recognising a chip that words the finding identically —
		// and a folded chip is that arm's chip PLUS a sentence, so an exact-string test stops
		// recognising it and the screen re-reports the pair. #88's duplicate would come straight back,
		// now in two wordings, one folded and one not. A screening question names no drug, so the
		// subject reaches "in play" through the ANSWER (the mappings-less overload below, so echo
		// scoping has no record to attribute the mention to — not "uncited", which since issue #360 is
		// no longer the same thing) — the shape #127 measured this suppression against.
		List<SafetyWarning> warnings = foldValidator().validate("Ibuprofen is on the list.",
				DrugReferenceTestSupport.SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("ibuprofen 400mg", "aspirin 81mg"),
						DrugReferenceTestSupport.set("M01AE01", "N02BA01"), null, null));

		assertEquals("Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) — Major. "
				+ "Ibuprofen blunts the irreversible platelet inhibition of low-dose aspirin. Ibuprofen "
				+ "is in the same cross-reactivity group (NSAID) as active order Acetylsalicylic acid "
				+ "(aspirin) — possible additive or duplicate-class therapy", onlyDetail(warnings),
				"one chip for the one pair, and it is the FOLDED one — not the screen's plainer chip."
						+ " Its partner is named once since issue #292; the suppression itself keys on"
						+ " the PAIR and not on this text, which is why InteractionPairs exists");
	}
}
