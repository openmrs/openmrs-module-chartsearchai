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

	/** A DDInter-shaped partner row whose display name is WHITESPACE — see the case that reads it. */
	private static final String BLANK_PARTNER_NAME_FIXTURE =
			"chartsearchai-test/ddi-fold-blank-partner-name.json";

	/** The DDInter-shaped fixture whose rule token {@code esomeprazole} is named by TWO substances, so
	 *  {@code unambiguouslyNames} refuses the fold — {@code FoldedChipOnePartnerNameTest} owns the
	 *  chip-side case over the same file. */
	private static final String AMBIGUOUS_TOKEN_FIXTURE =
			"chartsearchai-test/ddi-fold-ambiguous-token.json";

	/**
	 * A context of ONE active drug order, carrying the per-order structure
	 * {@link PatientClinicalContextBuilder} always attaches for a real patient — the shape the record's
	 * adoption of the fold's name is gated on. {@code PatientClinicalContextBuilder} attaches that list
	 * for every patient whose orders it can read; the one shape it builds without it is its null-patient
	 * early return, which carries no drug names or codes either and so folds nothing. The flattened sets
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
		assertFalse(record.contains("Acetylsalicylic acid (aspirin)"),
			"and it takes the RECORD's vocabulary: the synonym-augmented label is chip-display only and"
					+ " must not enter this record's text, was: " + record);
	}

	/**
	 * The change is scoped to a fold that RECONCILED. Where the class arm says nothing about the
	 * partner there is no fold, the chip's rule sentence is {@code partnerLabel} and the note must
	 * agree with THAT — moving it would create the divergence in the other direction.
	 *
	 * <p><b>What it pins is narrower than it reads</b>, said rather than left to look better defended:
	 * it reddens only on breaking the shared {@code partnerLabel} fallback, which many existing
	 * note-text cases already catch. Its value is the ARRANGEMENT — a real chip that did not fold, read
	 * from the record side — not a guard nothing else holds.
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
	 *
	 * <p><b>Only its PRECONDITION is falsifiable</b>, and that is worth saying: a refused fold carries no
	 * name to hand out, so the record assertion below cannot fail while the precondition holds. Break
	 * {@code unambiguouslyNames} and the precondition reddens, together with three pre-existing
	 * {@code FoldedChipOnePartnerNameTest} cases. The arrangement is what this case adds — the refusal
	 * read from the record side — not a guard of its own.
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
	 * A KB row whose display name is WHITESPACE still leaves the note naming its partner.
	 *
	 * <p>The ENTRY rung is the one place a string not produced by {@code partnerLabel} reaches this
	 * note, and it is the one place that string can be blank: {@code partnerLabel} trims a
	 * {@code firstNonBlank} of two fields and so never is, while {@code DdiDrugReferenceSource} refuses a
	 * row whose name {@code isEmpty()} and admits one that is whitespace. What a blank then costs depends
	 * on whether the rule carries mechanism prose, and THIS fixture reaches the first of the two: with a
	 * note the assembled piece is still non-blank, so {@code orderedInteractionNotes}' own
	 * {@code isBlank(rendered)} guard does not fire and the record read
	 * {@code Interactions: (Major. Probe mechanism text.)}, naming no partner at all. Without one the
	 * piece IS the blank label, that guard fires, and the partner leaves the record entirely — the worse
	 * of the two, reachable only by nulling the note, since no shipped parser produces a blank name and a
	 * blank note together.
	 *
	 * <p>Operator-data only: measured through the real {@code DdiDrugReferenceSource.load}, no row of the
	 * shipped knowledge base publishes a blank name. A hand-authored file reaches it at once, which is
	 * the latency this fixture exists to remove.
	 */
	@Test
	public void aPartnerRowPublishingABlankNameStillNamesItselfInTheNote() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.ddiFixtureEntries(BLANK_PARTNER_NAME_FIXTURE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			oneOrder("Naproxen 500mg", DrugReferenceTestSupport.set("M01AE02")), QUESTION);

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("interacts with active order") && finding.contains("is in the same"),
			"precondition: the ENTRY rung must have folded, or the blank name never reaches the note,"
					+ " was: " + finding);

		String record = recordFor(chart, "Ibuprofen");
		assertTrue(record.contains("naproxen (Major"),
			"a row publishing no usable name of its own leaves the note on the rule's token rather than"
					+ " naming nobody, was: " + record);
	}

	/**
	 * The rung where the ladder found NO name at all — a nameless order the class arm can only call by
	 * its codes — hands the rule's own token to both chip sentences, and the note keeps that same token.
	 *
	 * <p>Behaviour-neutral against the state before issue #297, which is why it is pinned: the fold's
	 * answer for this rung is {@code partnerLabel} in both vocabularies, so nothing moves. What the case
	 * guards is the direction a later change would move it in — before this case existed, handing this
	 * rung's note {@code OrderPartner.label} instead left the whole api suite green while putting the
	 * {@code [ATC …]} stand-in into the record's prose, and thence into the prompt as citable text.
	 * Re-derive that by making the substitution and running the suite, rather than from a tally here. A code
	 * list is the ABSENCE of a name (issue #290) and ADR Decision 38 already measured that direction on
	 * the chip.
	 *
	 * <p>The arrangement is {@code DrugReferenceTestSupport.namelessAspirinOrder()}, shared with the
	 * chip-side case rather than copied: the curated seed carries none of the order's three codes, and
	 * the order itself carries no readable name.
	 */
	@Test
	public void aNamelessOrdersNoteKeepsTheRulesTokenAndNeverItsCodeList() {
		PatientChart chart = DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.curatedService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.namelessAspirinOrder(), QUESTION);

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("interacts with active order aspirin")
				&& finding.contains("cross-reactivity group (NSAID) as active order aspirin"),
			"precondition: the ladder found no name, so both sentences take the rule's token, was: "
					+ finding);

		String record = recordFor(chart, "Ibuprofen");
		assertTrue(record.contains("aspirin ("),
			"the note keeps that same token, was: " + record);
		assertFalse(record.contains("[ATC"),
			"a code list is the absence of a name and must never enter the record's prose, was: "
					+ record);
	}

	/**
	 * The ORDER rung reconciles, and the note still keeps the rule's own token — the one rung where the
	 * two surfaces are deliberately NOT handed the same string, pinned so the decision is visible rather
	 * than latent.
	 *
	 * <p>The record names a partner by the DATASET's name for it, and this rung was reached precisely
	 * because the dataset could not name the order's codes, so it has none to offer. What
	 * {@code namesNamingOrder} has just proved is that the rule's token names that display, so the note's
	 * name is a WORD of the chip's rather than a second name — and handing the note the prescription
	 * display would put a strength and a formulation the knowledge base knows nothing about into a list
	 * of that knowledge base's own partners.
	 *
	 * <p>The arrangement is {@code DrugReferenceTestSupport.renamedByItsOwnNaproxenOrder()}, shared with
	 * the chip-side case rather than copied: one order carrying a code the fixture covers
	 * ({@code M01AE02}, resolving {@code Naproxen}) and one it cannot name ({@code A02BC05}), so the
	 * partner is keyed on that substance and renamed after this order.
	 */
	@Test
	public void anOrderRungFoldStillLeavesTheNoteOnTheRulesOwnToken() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(DrugReferenceTestSupport.RENAMED_PARTNER_FIXTURE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.renamedByItsOwnNaproxenOrder(), QUESTION);

		String finding = DrugReferenceTestSupport.safetyFindingIn(chart).getText();
		assertTrue(finding.contains("interacts with active order Naproxen 500mg")
				&& finding.contains("is in the same"),
			"precondition: the ORDER rung must have reconciled a FOLDED chip, was: " + finding);

		String record = recordFor(chart, "Ibuprofen");
		assertTrue(record.contains("naproxen ("),
			"the note keeps the rule's own token: the dataset has no name of its own for this partner,"
					+ " and the token names the very display the chip prints, was: " + record);
		assertFalse(record.contains("Naproxen 500mg"),
			"a prescription display must not enter a list of the knowledge base's own partners, was: "
					+ record);
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
