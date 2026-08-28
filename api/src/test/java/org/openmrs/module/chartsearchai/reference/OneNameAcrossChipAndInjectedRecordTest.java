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
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issue #297: across ONE prompt, one active order is named one way.
 *
 * <p>Issue #292's fold reconciles the two sentences of a folded CHIP, and the chip reaches the prompt
 * verbatim as a citable {@code safety_finding} record ({@code DrugReferenceInjector.renderFinding}).
 * The {@code drug_reference} record's own interaction-note list kept
 * {@code DrugSafetyValidator.partnerLabel}, so where the fold reconciled the prompt carried one
 * prescription under two names — the property {@code CLAUDE.md} states {@code partnerLabel} exists to
 * hold.
 *
 * <p>Driven through the real {@code DrugReferenceInjector.injectRecords} with the real validator behind
 * it, over datasets read by the real parsers, because the defect is a disagreement between two records
 * of one injection and nothing short of the whole injection holds both.
 */
public class OneNameAcrossChipAndInjectedRecordTest {

	private static final String QUESTION = "Can I give ibuprofen?";

	/** The DDInter-shaped fixture whose rule token {@code esomeprazole} is named by TWO substances, so
	 *  {@code unambiguouslyNames} refuses the fold — {@code FoldedChipOnePartnerNameTest} owns the
	 *  chip-side case over the same file. */
	private static final String AMBIGUOUS_TOKEN_FIXTURE =
			"chartsearchai-test/ddi-fold-ambiguous-token.json";

	/**
	 * A context of ONE active drug order, carrying the per-order structure
	 * {@link PatientClinicalContextBuilder} always attaches for a real patient — the shape the record's
	 * adoption of the fold's name is gated on, and the only shape production builds. The flattened sets
	 * carry the same name and codes, exactly as that builder writes them.
	 */
	private static PatientClinicalContext oneOrder(String display, Set<String> codes) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(display), codes,
			null, null,
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-1", display,
				DrugReferenceTestSupport.set(display), codes)));
	}

	/** The real injector with the real validator behind it over the real DDInter excerpt and the real
	 *  bundled cross-reactivity groups — the arrangement {@code preAnswerFindings} needs before a chip
	 *  can become a citable safety-finding record beside the reference one. */
	private static PatientChart inject(PatientClinicalContext context, String question) {
		return DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}

	/** The injected {@code drug_reference} record rendered for {@code drug} in {@code chart}. */
	private static String recordFor(PatientChart chart, String drug) {
		String text = DrugReferenceTestSupport.referenceTextNaming(chart, drug);
		assertNotNull(text, "precondition: a drug_reference record must be injected for " + drug
				+ ", was: " + DrugReferenceTestSupport.referenceTexts(chart));
		return text;
	}

	/**
	 * The ticket's reproducer: patient on {@code aspirin 81mg}/{@code N02BA01}, "Can I give ibuprofen?".
	 *
	 * <p>The class arm's ladder resolves the DDInter entry, so the folded chip names the order
	 * {@code Acetylsalicylic acid (aspirin)} and that string reaches the prompt as a
	 * {@code safety_finding}. The {@code drug_reference} note must name the same substance — in the
	 * record's own vocabulary, {@code getName()}, because the synonym-augmented label may not enter this
	 * record's text ({@code DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText}).
	 */
	@Test
	public void theInjectedNoteNamesTheSubstanceTheFoldedChipNames() {
		PatientChart chart = inject(
			oneOrder("Aspirin 81mg", DrugReferenceTestSupport.set("N02BA01")), QUESTION);

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("active order Acetylsalicylic acid (aspirin)"),
			"precondition: the fold must have reconciled, or there is no second name to remove, was: "
					+ finding);

		String record = recordFor(chart, "Ibuprofen");
		assertTrue(record.contains("Acetylsalicylic acid (Major"),
			"the note must name the partner by the dataset's own name for the substance the chip"
					+ " names, was: " + record);
		assertFalse(record.contains("aspirin (Major"),
			"the rule's own token is the second name this ticket removes, was: " + record);
	}

	/**
	 * The change is scoped to a fold that RECONCILED. Where the class arm says nothing about the
	 * partner there is no fold, the chip's rule sentence is {@code partnerLabel} and the note must
	 * agree with THAT — moving it would create the divergence in the other direction.
	 */
	@Test
	public void anUnfoldedChipsPartnerKeepsTheRulesOwnTokenInTheNote() {
		PatientChart chart = inject(
			oneOrder("Warfarin 5mg", DrugReferenceTestSupport.set("B01AA03")),
			"Is methotrexate safe here?");

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("interacts with active order warfarin"),
			"precondition: an UNFOLDED chip naming its partner by the rule's token, was: " + finding);
		assertFalse(finding.contains("is in the same"),
			"precondition: nothing may have folded here, was: " + finding);

		String record = recordFor(chart, "Methotrexate");
		assertTrue(record.contains("warfarin ("),
			"an unfolded chip's partner keeps the rule's own token in the note, was: " + record);
	}

	/**
	 * Where the fold REFUSED, the note keeps the rule's token — the chip's rule sentence does too, so
	 * the two still agree. Over the DDInter-shaped fixture whose token names two substances, which is
	 * the shape a hand-written json cannot stand in for.
	 */
	@Test
	public void aRefusedFoldLeavesTheNoteOnTheRulesOwnToken() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.ddiFixtureEntries(AMBIGUOUS_TOKEN_FIXTURE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			oneOrder("Omeprazole 20mg", DrugReferenceTestSupport.set("A02BC05")),
			"Is pantoprazole safe here?");

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("interacts with active order esomeprazole"),
			"precondition: the fold must have REFUSED, keeping the rule's own token, was: " + finding);

		String record = recordFor(chart, "Pantoprazole");
		assertTrue(record.contains("esomeprazole ("),
			"a refused fold leaves the note exactly where it was, was: " + record);
	}

	/**
	 * The record's vocabulary, not the chip's. {@code getName()} and never
	 * {@code DrugReference.displayLabel()} — asserted on the arrangement where this record now carries
	 * the partner's name at all, which the existing pin (over a question naming aspirin itself) does not
	 * reach.
	 */
	@Test
	public void theSynonymAugmentedLabelNeverEntersTheNoteEither() {
		PatientChart chart = inject(
			oneOrder("Aspirin 81mg", DrugReferenceTestSupport.set("N02BA01")), QUESTION);

		String record = recordFor(chart, "Ibuprofen");
		assertTrue(record.contains("Acetylsalicylic acid"),
			"precondition: the note names the substance, was: " + record);
		assertFalse(record.contains("Acetylsalicylic acid (aspirin)"),
			"the synonym-augmented label is chip-display only and must not enter THIS record's text,"
					+ " was: " + record);
	}

	/**
	 * The residue, pinned so it is visible rather than latent: on a context carrying NO per-order
	 * structure — the flattened shape of issue #118, which {@code PatientClinicalContextBuilder} does not
	 * build for a real patient — the note keeps the rule's own token however the chip came out.
	 *
	 * <p>Because the class arm's own reach is key-dependent there: {@code orderPartners} reads the
	 * flattened set for its code rung and the per-order list for its name rung, so this same order folds
	 * when a dictionary published its ATC code and does not when it published only its name.
	 * {@code DuplicateInteractionChipTest.aRuleOnlyPairIsWordedExactlyAsBefore} pins that asymmetry as
	 * the chip's current behaviour and
	 * {@code OrderDrivenInjectionResolutionTest.oneOrderInjectsOneRecordSetWhicheverWayItResolves}
	 * forbids it from reaching the prompt — so the record stays out of it, and the note is byte-identical
	 * whichever key this context carries.
	 */
	@Test
	public void aContextWithNoPerOrderStructureKeepsTheRulesOwnTokenWhicheverKeyItCarries() {
		String byCode = recordFor(inject(DrugReferenceTestSupport.ctx(60, null, null,
			DrugReferenceTestSupport.set("N02BA01"), null, null), QUESTION), "Ibuprofen");
		String byName = recordFor(inject(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Aspirin 81mg"), null, null, null), QUESTION), "Ibuprofen");

		assertTrue(byCode.contains("aspirin (Major"),
			"the code-keyed context folds, but with no order behind it the record stays on the rule's"
					+ " own token, was: " + byCode);
		assertEquals(byCode, byName,
			"and the two keys must produce one record — the invariant"
					+ " OrderDrivenInjectionResolutionTest states");
	}
}
