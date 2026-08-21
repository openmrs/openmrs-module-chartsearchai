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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Issue #292: inside ONE chip detail, one active order is named ONCE.
 *
 * <p>Issue #88's fold puts two arms' sentences into a single detail, and each arm named the partner
 * from its own source — the class arm by issue #155/#186/#290's ladder in
 * {@code DrugSafetyValidator.orderPartners} (the dataset entry's display label, else the ORDER's own
 * display where a code is dataset-unnameable, else the bare code or the {@code [ATC …]} stand-in), the
 * rule arm by {@code DrugSafetyValidator.partnerLabel}, which is the rule's own match token and
 * reaches nothing the context carries. So one prescription appeared under two names in one sentence.
 *
 * <p>The fix makes `partnerLabel` the ladder's last-but-one rung instead of a second, independent
 * ladder: a folded chip names the partner by the class arm's label where that ladder found a NAME and
 * the two arms provably agree about which substance it is, and by the rule's own token where it did
 * not. Unfolded rule chips, class-only chips, the grouping keys and the injected
 * {@code drug_reference} note list are untouched — see {@code foldedPartnerLabel}.
 *
 * <p>Driven through the real {@link DrugSafetyValidator#validate} over datasets read by the real
 * parsers, because the defect is in what the two arms make of a real context: the bundled curated seed
 * through its real load path, the pinned DDInter excerpt, and two verbatim-shaped json fixtures.
 */
public class FoldedChipOnePartnerNameTest {

	/** The three {@code WHOATC} codes the 3.7.1 demo dictionary maps an aspirin order's concept to, and
	 *  the ones the ticket's live run carried. The curated seed carries none of them, which is what
	 *  leaves its ladder with no name at all. */
	private static final Set<String> ASPIRIN_ORDER_CODES = DrugReferenceTestSupport
			.set("A01AD05", "B01AC06", "N02BA01");

	/** What {@code PatientClinicalContextBuilder.codeOnlyDisplay} builds for an order no name could be
	 *  read for: the codes it carries, labelled as codes, sorted. */
	private static final String CODE_ONLY_DISPLAY = "[ATC A01AD05, B01AC06, N02BA01]";

	private static final String QUESTION = "Can I give ibuprofen?";

	/** A fold whose two arms resolve one shared ATC code to two DIFFERENT substances — see the
	 *  fixture's own description. */
	private static final String SHARED_CODE_FIXTURE =
			"chartsearchai-test/drug-reference-fold-shared-code.json";

	/** The same collision as {@link #SHARED_CODE_FIXTURE} but in DDINTER shape, so the real parser
	 *  produces the alias overlap itself rather than a hand-written alias list standing in for it. */
	private static final String AMBIGUOUS_TOKEN_FIXTURE =
			"chartsearchai-test/ddi-fold-ambiguous-token.json";

	/** A rule filed under an ATC code with no match token at all. */
	private static final String TOKENLESS_RULE_FIXTURE =
			"chartsearchai-test/drug-reference-fold-tokenless-rule.json";

	/** Lisinopril and enalapril each interact with ramipril and all three share subgroup C09AA;
	 *  enalapril's rule carries NO mechanism note, which is the shape the extraction below has to cope
	 *  with. */
	private static final String SAME_CLASS_FIXTURE = "chartsearchai-test/drug-reference-sameclass.json";

	/**
	 * Every name a detail calls an active order by: each {@code "active order "} occurrence's following
	 * name, taken to the first of {@code " — "}, {@code "."} or the end of the detail.
	 *
	 * <p>Two of the three terminators are load-bearing over the arrangements in this file, and which
	 * does what was measured by dropping each in turn rather than reasoned about. Dropping {@code "."}
	 * reddens {@code noFoldedChipNamesOneActiveOrderTwoWays} on {@link #SAME_CLASS_FIXTURE}'s note-less
	 * enalapril rule, whose sentence {@code interactionWarning} ends on the partner name and
	 * {@code endSentence} closes with a full stop — loudly, not silently. Dropping {@code " — "} yields
	 * {@code "Ramipril — possible duplicate therapy"} for the class sentence. The end-of-detail case is
	 * reached by NO arrangement here — mutating it to throw leaves every case green — and is kept only
	 * so a future detail that ends on a name does not read past its end.
	 *
	 * <p><b>The hazard it does not close</b>, stated because a silent pass is worse than a loud fail: a
	 * partner NAME containing a full stop is cut at that stop in both sentences, so two genuinely
	 * different names could reduce to one string and the invariant would pass on a detail that really
	 * does name the order twice ({@code St. John's Wort extract} and {@code St. John's Wort} both
	 * reducing to {@code St}). The shipped knowledge base carries such names. No arrangement here
	 * reaches it, and closing it needs a terminator that is not a sentence boundary — which the chip
	 * format does not offer, the class sentence's own dash being the only structural marker.
	 */
	private static Set<String> orderNamesIn(String detail) {
		Set<String> names = new LinkedHashSet<String>();
		String marker = "active order ";
		int at = detail.indexOf(marker);
		while (at >= 0) {
			int from = at + marker.length();
			int end = detail.length();
			for (String terminator : Arrays.asList(" — ", ".")) {
				int found = detail.indexOf(terminator, from);
				if (found >= 0 && found < end) {
					end = found;
				}
			}
			names.add(detail.substring(from, end));
			at = detail.indexOf(marker, from);
		}
		return names;
	}

	/** The ticket's own arrangement: a nameless order the class arm can only call by its codes, beside a
	 *  curated rule that names the same prescription {@code aspirin}. */
	private static PatientClinicalContext namelessAspirinOrder() {
		return DrugReferenceTestSupport.ctx(60, null, null, ASPIRIN_ORDER_CODES, null, null,
			Arrays.asList(PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-nameless",
				CODE_ONLY_DISPLAY, ASPIRIN_ORDER_CODES)));
	}

	/**
	 * The ticket's live case, verbatim from the 3.7.0-rc.2 standalone at {@code b0a24a96}.
	 *
	 * <p>The class arm's ladder has no name here — the curated seed carries none of the order's three
	 * codes and the order itself carries no readable name — so what it prints is the {@code [ATC …]}
	 * stand-in, which is the ABSENCE of a name (issue #290) and may not displace one. The rule's own
	 * token is the only name either arm holds, so both sentences take it.
	 */
	@Test
	public void theTicketsLiveCaseNamesTheOrderOnce() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());

		List<SafetyWarning> warnings = validator.validate("", QUESTION, namelessAspirinOrder());

		assertEquals(1, warnings.size(), "one prescription, one folded chip, was: " + warnings);
		assertEquals("Ibuprofen interacts with active order aspirin — additive GI and bleeding risk."
				+ " Ibuprofen is in the same cross-reactivity group (NSAID) as active order aspirin"
				+ " — possible additive or duplicate-class therapy", warnings.get(0).getDetail());
		assertFalse(warnings.get(0).getDetail().contains("[ATC"),
			"a code list is the absence of a name and must not survive beside the name the rule"
					+ " supplies, was: " + warnings.get(0).getDetail());
	}

	/**
	 * The ordinary case the ticket names beside its live one: the rule's token and the resolved order
	 * name differ for every formulation, because the {@code ddinter} parser lower-cases each token from
	 * the partner row's RxNorm generic while the class arm prints that row's display label.
	 *
	 * <p>Asserted on the NAMES rather than on the whole detail so the case does not pin DDInter's real
	 * mechanism prose, which is neither this test's subject nor stable across a knowledge-base refresh.
	 */
	@Test
	public void theOrdinaryFormulationCaseNamesTheOrderOnce() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("aspirin 81mg"),
				DrugReferenceTestSupport.set("N02BA01"), null, null));

		assertEquals(1, warnings.size(), "one pair, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("interacts with active order") && detail.contains("is in the same"),
			"precondition: this must be a FOLDED detail, or the invariant is not being tested, was: "
					+ detail);
		assertEquals(DrugReferenceTestSupport.set("Acetylsalicylic acid (aspirin)"),
			orderNamesIn(detail),
			"both sentences must name the order by the one name the class arm's ladder resolved, was: "
					+ detail);
	}

	/**
	 * The fold must NOT displace the rule's own token where the two arms have resolved one ATC code to
	 * two DIFFERENT substances.
	 *
	 * <p>{@code addPartnersForUnmappedOrders} records this as the standing bound at the cross-arm fold:
	 * {@code ruleAbout} correlates through {@code entryForAtcCode}, which answers with the canonical row
	 * publishing a code, and a level-5 code can be published by two substances in this knowledge base
	 * ({@code Omeprazole} and {@code Esomeprazole} share {@code A02BC05}). Both sentences stay true and
	 * "what is lost is which co-medication the second sentence is about" — but only while the two names
	 * differ, because those two names are the only evidence a reader has that two partners are in play.
	 * Displacing the token here would render one substance's rated mechanism under the other's name,
	 * which is the #161/#187/#194 failure ("right finding, wrong reason") and strictly worse than the
	 * legibility cost issue #292 exists to remove.
	 *
	 * <p>So the gate is a NAME test — does the rule's own token name the entry the class arm's label came
	 * from — and deliberately not a comparison of the two arms' resolved substances:
	 * {@code identifies} accepts a bare shared ATC code, so on this very fixture both arms resolve
	 * {@code Omeprazole} and a substance comparison would agree spuriously.
	 *
	 * <p>This fixture exercises the IDENTITY half of that test only ({@code DrugReference.isNamed}). Its
	 * three rows are hand-written with one self-name each, so the token names exactly the row it belongs
	 * to and the refusal comes from that identity answering false. The shipped knowledge base is not like
	 * that, and {@link #aRuleWhoseTokenNamesTwoSubstancesKeepsItsOwnToken} is the case for the half this
	 * one cannot reach — kept separate rather than merged, because a fixture that refuses for the wrong
	 * reason is how the guard first came to be written with a premise the default dataset does not
	 * satisfy.
	 */
	@Test
	public void aRuleAboutAnotherSubstanceSharingTheCodeKeepsItsOwnToken() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(SHARED_CODE_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", "Is pantoprazole safe here?",
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("omeprazole 20mg"),
				DrugReferenceTestSupport.set("A02BC05"), null, null));

		assertEquals(1, warnings.size(), "one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("interacts with active order") && detail.contains("is in the same"),
			"precondition: the two arms must have folded, or there is no displacement to refuse, was: "
					+ detail);
		assertEquals(DrugReferenceTestSupport.set("esomeprazole", "Omeprazole"), orderNamesIn(detail),
			"the rule's mechanism must stay under the name the RULE names, and the class sentence under"
					+ " the one the class arm resolved — two partners, two names, was: " + detail);
	}

	/**
	 * The refusal's real premise, in DDINTER shape — and the case that showed a hand-written fixture
	 * cannot stand in for it.
	 *
	 * <p>{@link #SHARED_CODE_FIXTURE} refuses the displacement because each of its three curated rows
	 * carries one self-name, so the rule's token names exactly the row it belongs to. The shipped
	 * knowledge base is not like that: the {@code ddinter} parser writes each entry's aliases from its
	 * name AND its {@code rxnorm_name}, and its row named {@code Omeprazole} carries
	 * {@code rxnorm_name: esomeprazole} — measured on the pinned excerpt, that row really is
	 * {@code name=Omeprazole, rxnorm_name=esomeprazole}. A bare {@code isNamed} test therefore answers
	 * TRUE for the token {@code esomeprazole} against the {@code Omeprazole} row and would license the
	 * displacement on exactly the pair the guard exists to refuse — a rated mechanism about one substance
	 * printed under the other's name, and 25 of the shipped KB's 2093 rule tokens are named by more than
	 * one substance ({@code hydrocortisone}, {@code trastuzumab}, {@code gabapentin} …).
	 *
	 * <p>So the guard asks {@code unambiguouslyNames}: one substance, not merely this one among several.
	 * This fixture reproduces the collision through the real parser — two rows publishing
	 * {@code A02BC05} with different {@code drugbank_id}s and the same {@code rxnorm_name}, so
	 * {@code substanceGroupKey} separates them while the token names both.
	 */
	@Test
	public void aRuleWhoseTokenNamesTwoSubstancesKeepsItsOwnToken() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.ddiFixtureEntries(AMBIGUOUS_TOKEN_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", "Is pantoprazole safe here?",
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("omeprazole 20mg"),
				DrugReferenceTestSupport.set("A02BC05"), null, null));

		assertEquals(1, warnings.size(), "one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("interacts with active order") && detail.contains("is in the same"),
			"precondition: the two arms must have folded, or there is no displacement to refuse, was: "
					+ detail);
		assertTrue(detail.contains("interacts with active order esomeprazole "),
			"the rule's mechanism must stay under the name the RULE names: its token is named by two"
					+ " substances here, so it cannot tell the fold which of them the rule is about,"
					+ " was: " + detail);
		assertEquals(2, orderNamesIn(detail).size(),
			"two partners, two names — collapsing them would print one substance's rated mechanism"
					+ " under the other's name, was: " + detail);
	}

	/**
	 * The residue {@code foldedPartnerLabel} records as its third case: a rule carrying no match token at
	 * all still names its partner by a raw ATC code, so the folded detail keeps two names.
	 *
	 * <p>{@code hasActiveDrug} joins such a rule on its ATC code and {@code partnerLabel} then renders
	 * that code, while the class arm resolves the same order to its dataset entry and renders a name.
	 * {@code DrugReference.isNamed} answers false for a null token, so the gate refuses and neither
	 * sentence yields. Unchanged from before issue #292 rather than introduced by it — and NOT protecting
	 * against a mis-attribution here, since {@code ruleAbout} correlated these arms through its
	 * exact-code leg, so they are demonstrably about one co-medication. What it protects against is
	 * asserting a substance a bare code does not license, a level-5 code being publishable by two
	 * substances in this KB. Closing it means giving a token-less rule a NAME, which is a change to
	 * {@code partnerLabel} and therefore to the injected record too.
	 *
	 * <p>Pinned so the residue is visible rather than latent: the sweep below cannot see it, because its
	 * anti-vacuity guard counts the arrangements it was given and a missing one is exactly what it cannot
	 * notice.
	 */
	@Test
	public void aRuleThatCarriesOnlyAnAtcCodeKeepsBothNames() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(TOKENLESS_RULE_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", "Is lisinopril safe here?",
			DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("C09AA05"),
				null, null));

		assertEquals(1, warnings.size(), "one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertEquals(DrugReferenceTestSupport.set("C09AA05", "Ramipril"), orderNamesIn(detail),
			"the code the rule carries and the name the class arm resolved, both still standing — the"
					+ " residue, pinned so a change that closes it is visible here, was: " + detail);
	}

	/** A chip no fold applies to must be byte-identical to what it always was: the rule arm keeps
	 *  {@code partnerLabel}, which is also the label the injected {@code drug_reference} note and the
	 *  grouping keys read, and the class arm keeps its ladder. */
	@Test
	public void chipsOutsideTheFoldAreUnchanged() {
		DrugSafetyValidator curated = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());

		List<SafetyWarning> ruleOnly = curated.validate("", QUESTION, DrugReferenceTestSupport.ctx(60,
			null, DrugReferenceTestSupport.set("aspirin 81mg"), null, null, null));
		assertEquals(Arrays.asList("Ibuprofen interacts with active order aspirin"
				+ " — additive GI and bleeding risk"), DrugReferenceTestSupport.classChipDetails(ruleOnly),
			"a rule-only chip carries the rule's own token, as it always has");

		// The seed carries no naproxen entry, so this exercises the ladder's MIDDLE rung — the order's
		// own display — which is the rung a folded chip can now displace `partnerLabel` with. Without an
		// order in the per-order list the label would fall to issue #118's bare-code rung and the case
		// would assert nothing about a name.
		List<SafetyWarning> classOnly = curated.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("naproxen 500mg"),
				DrugReferenceTestSupport.set("M01AE02"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-naproxen", "Naproxen 500mg",
					DrugReferenceTestSupport.set("naproxen 500mg"),
					DrugReferenceTestSupport.set("M01AE02")))));
		assertEquals(Arrays.asList("Ibuprofen is in the same ATC class (M01AE) as active order"
				+ " Naproxen 500mg — possible duplicate therapy"), DrugReferenceTestSupport.classChipDetails(classOnly),
			"a class-only chip keeps the ladder's own name — there is no rule to borrow a token from");
	}

	/**
	 * A display that is BLANK is not a name either, however the order answers {@code hasKnownName()}.
	 *
	 * <p>{@code OrderPartner.nameByOrder} used to be gated on {@code hasKnownName()} alone while the
	 * label it was handed was {@code firstNonBlank(order.getDisplay(), orderCode)} — so an order that
	 * claims a known name and carries a blank display renamed a partner the dataset HAD named after the
	 * bare ATC code, which is what issue #155 exists to remove. Before issue #292 that only reached the
	 * class sentence; with the fold reconciling the two, an unguarded rename would put the code in BOTH
	 * sentences and make this shape worse than the defect being repaired. Both write sites therefore ask
	 * one guard, {@code displayNamesADrug}.
	 *
	 * <p>Partly covered on purpose, which is the only arrangement that can see it: {@code N02BA01}
	 * resolves the dataset's aspirin row so the partner is created with the ENTRY's name, and only
	 * {@code nameByOrder} can replace it. The builder produces no such order — it takes the display from
	 * a non-blank name and routes the nameless case through {@code namedByCodesOnly} — but the public
	 * constructor defaults {@code nameKnown} to true, which is the latitude this case uses and which
	 * {@code NamelessActiveOrderPartnerTest.aRealDisplayWithNoMatchTokensStillOutranksTheDatasetName}
	 * relies on for the opposite direction.
	 */
	@Test
	public void aBlankDisplayNeverDisplacesTheDatasetName() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());
		PatientClinicalContext.ActiveDrugOrder blankDisplay = new PatientClinicalContext.ActiveDrugOrder(
				"order-blank-display", "   ", DrugReferenceTestSupport.set("aspirin 81mg"),
				DrugReferenceTestSupport.set("N02BA01", "N02BA99"));

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("aspirin 81mg"),
				DrugReferenceTestSupport.set("N02BA01", "N02BA99"), null, null,
				Arrays.asList(blankDisplay)));

		assertEquals(1, warnings.size(), "one order, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertEquals(DrugReferenceTestSupport.set("Acetylsalicylic acid (aspirin)"),
			orderNamesIn(detail),
			"the dataset's name must survive a blank display — and once, in both sentences, was: "
					+ detail);
		assertFalse(detail.contains("N02BA"),
			"an ATC code must not reach either sentence: it is what a blank display resolves to, and"
					+ " naming an active order by its code is the defect issue #155 removed, was: "
					+ detail);
	}

	/**
	 * The invariant itself, over every folded arrangement this file builds plus the note-less one, which
	 * no other case here reaches.
	 *
	 * <p>{@link #SAME_CLASS_FIXTURE}'s enalapril rule carries no mechanism note, so its rule sentence
	 * ends on the partner name and {@code endSentence} closes it with a full stop rather than with the
	 * {@code " — "} every other arrangement here has. Both of that fixture's subjects are in play from
	 * one question, so this asserts the invariant over two chips at once.
	 *
	 * <p>The three REFUSAL arrangements are deliberately not swept here: a folded chip about two
	 * different co-medications, or about a rule with no name of its own, is MEANT to carry two names, so
	 * including them would make this assertion false for the right reason. Each has its own case above,
	 * and the count below is what stops a missing arrangement from passing as a clean sweep.
	 */
	@Test
	public void noFoldedChipNamesOneActiveOrderTwoWays() throws IOException {
		List<List<SafetyWarning>> runs = new ArrayList<List<SafetyWarning>>();
		runs.add(DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.validate("", QUESTION, namelessAspirinOrder()));
		runs.add(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.validate("", QUESTION, DrugReferenceTestSupport.ctx(60, null,
					DrugReferenceTestSupport.set("aspirin 81mg"),
					DrugReferenceTestSupport.set("N02BA01"), null, null)));
		runs.add(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(SAME_CLASS_FIXTURE)))
				.validate("Lisinopril and enalapril are both options.",
					"Is lisinopril or enalapril safe here?", DrugReferenceTestSupport.ctx(60, null, null,
						DrugReferenceTestSupport.set("C09AA05"), null, null)));

		int foldedSeen = 0;
		for (List<SafetyWarning> warnings : runs) {
			for (String detail : DrugReferenceTestSupport.classChipDetails(warnings)) {
				if (!detail.contains("interacts with active order") || !detail.contains("is in the same")) {
					continue;
				}
				foldedSeen++;
				assertEquals(1, orderNamesIn(detail).size(),
					"a folded detail must name its one active order once, was: " + detail);
			}
		}
		assertEquals(4, foldedSeen,
			"precondition: all four folded chips must have been reached, or this invariant passed by"
					+ " vacuity — the nameless order, the DDInter formulation, and both subjects of the"
					+ " note-less same-class fixture");
	}
}
