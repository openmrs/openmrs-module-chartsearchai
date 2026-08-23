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
import java.util.regex.Pattern;

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
 * the two arms are provably about one prescription — the rule's own token naming the ladder's ENTRY
 * unambiguously, or naming the very ORDER the label came from — and each arm keeps its own name where
 * they are not. Unfolded rule chips, class-only chips, the grouping keys and the injected
 * {@code drug_reference} note list are untouched — see {@code foldedPartnerLabel}.
 *
 * <p>Driven through the real {@link DrugSafetyValidator#validate} over datasets read by the real
 * parsers, because the defect is in what the two arms make of a real context: the bundled curated seed
 * through its real load path, the bundled DDInter sample, a pinned DDInter excerpt, and the five json
 * fixtures named below.
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

	/**
	 * An ATC code as the ladder renders one when it has no name — {@code A01AD05}, {@code N02BA01},
	 * and the level-4 form {@code M01AE} that a dictionary may map an order to instead.
	 *
	 * <p><b>Level 3 is deliberately NOT matched</b> ({@code [A-Z]\d{2}}), because that is also the shape
	 * of a real drug name: an order for vitamin {@code B12} would be rejected as a code by any pattern
	 * loose enough to catch {@code A01}. So the check is narrower than "looks like an ATC code" and is
	 * exactly the two levels the ladder is known to be handed.
	 */
	private static final Pattern ATC_CODE_SHAPED = Pattern.compile("[A-Z]\\d{2}[A-Z]{2}\\d{0,2}");

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

	/** As above, but the code the token-less rule cites is carried by no entry, so NEITHER arm holds a
	 *  name — see the fixture. */
	private static final String TOKENLESS_UNCOVERED_FIXTURE =
			"chartsearchai-test/drug-reference-fold-tokenless-uncovered.json";

	/** A partner keyed on one substance and then renamed after a DIFFERENT order — see the fixture. */
	private static final String RENAMED_PARTNER_FIXTURE =
			"chartsearchai-test/drug-reference-fold-order-renamed-partner.json";

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
	 * {@code A02BC05} and sharing the {@code rxnorm_name} the token is, while remaining two substances.
	 *
	 * <p>What separates them is the DISPLAY STEM, not the {@code drugbank_id}, and the difference matters
	 * to anyone regenerating this slice. Measured through the real parser, the two keys are
	 * {@code [esomeprazole, omeprazole]} and {@code [esomeprazole, esomeprazole]}: because the substance
	 * family {@code esomeprazole} names two DrugBank ids, {@code substanceKey} withholds the id and falls
	 * back to the stem of each row's own display name (contrast this fixture's {@code Pantoprazole},
	 * whose key keeps its id, {@code [pantoprazole, db00213]}). So keeping the ids distinct but letting
	 * the two display names share a stem collapses them into ONE substance and the ambiguity half of the
	 * guard stops being tested. Measured: renaming the second row to {@code Omeprazole (magnesium)} does
	 * collapse the keys, and this case then fails LOUDLY rather than passing — which is the good
	 * direction, and is why the warning is about a regenerated fixture reading as green elsewhere rather
	 * than about this case going quiet.
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

	/**
	 * A name supplied by an order the rule's token does NOT name is never handed to the rule sentence,
	 * because an ORDER is not a substance.
	 *
	 * <p>Named for the condition rather than for the rung: the gate on this rung is
	 * {@code DrugSafetyValidator.namesNamingOrder} — the ladder's label goes to the rule sentence where
	 * the RULE's own token names the very order that supplied it, and
	 * {@link #anOrderSuppliedNameTheRulesTokenNamesIsHandedToBothSentences} is that half. Here it does
	 * not: the partner is keyed on Naproxen (through the covered {@code M01AE02}) and then renamed after
	 * a DIFFERENT order whose display says esomeprazole, because that order also carries
	 * {@code A02BC05}, which the dataset cannot name, and {@code soleSubstanceOf} resolves its codes
	 * back to Naproxen. Token {@code naproxen} against naming order names {@code {esomeprazole 20mg}},
	 * so the gate answers false.
	 *
	 * <p>{@code OrderPartner.labelEntry} is deliberately not what decides it, and this arrangement is
	 * why: {@code nameByOrder} does not update that field, so here it still identifies Naproxen — the
	 * drug the rule IS about — while the label names esomeprazole. Validating against it would prove a
	 * fact about one drug and hand out the name of another.
	 *
	 * <p>Without the refusal the chip reads {@code Ibuprofen interacts with active order Esomeprazole
	 * 20mg — Moderate. Duplicate NSAID therapy …}: an NSAID duplicate-therapy finding printed entirely
	 * under a PPI order's name, with the prescription it is actually about — {@code Naproxen 500mg} —
	 * named nowhere in the detail. That is the #161/#187/#194 failure, and it is strictly worse than the
	 * legibility cost issue #292 removes, so this shape keeps two names.
	 */
	@Test
	public void anOrderSuppliedNameTheRulesTokenDoesNotNameIsNeverHandedToTheRuleSentence()
			throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(RENAMED_PARTNER_FIXTURE)));
		PatientClinicalContext.ActiveDrugOrder naproxen = DrugReferenceTestSupport.activeOrder(
			"order-naproxen", "Naproxen 500mg", DrugReferenceTestSupport.set("naproxen 500mg"),
			DrugReferenceTestSupport.set("M01AE02"));
		PatientClinicalContext.ActiveDrugOrder esomeprazole = DrugReferenceTestSupport.activeOrder(
			"order-esomeprazole", "Esomeprazole 20mg", DrugReferenceTestSupport.set("esomeprazole 20mg"),
			DrugReferenceTestSupport.set("A02BC05", "M01AE02"));

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("naproxen 500mg", "esomeprazole 20mg"),
				DrugReferenceTestSupport.set("M01AE02", "A02BC05"), null, null,
				Arrays.asList(naproxen, esomeprazole)));

		assertEquals(1, warnings.size(), "one partner, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("interacts with active order naproxen "),
			"the rule's finding must stay under the name the RULE names — the label here was supplied by"
					+ " a DIFFERENT order and naming the finding after it loses the prescription the"
					+ " finding is about, was: " + detail);
	}

	/**
	 * The same refusal reached the other way: ONE order carrying codes of two different substances.
	 *
	 * <p>{@code ruleAbout} correlates the arms through whichever of the partner's codes sorts first, so
	 * the rule it picks can be about one constituent while the ladder's label names the prescription.
	 * Here the bundled curated seed carries neither {@code B01AA03} nor {@code N02BA01}, so the partner
	 * is keyed on the ORDER and labelled {@code Aspirin 81mg}, while {@code B01AA03} sorts first and
	 * selects the seed's WARFARIN rule.
	 *
	 * <p>Without the refusal the chip reads {@code Ibuprofen interacts with active order Aspirin 81mg —
	 * increased risk of GI bleeding}, leaving {@code warfarin} nowhere but inside the mechanism prose —
	 * and since {@code DrugReferenceInjector.renderFinding} copies the detail verbatim, the model reads
	 * that same sentence as citable evidence.
	 *
	 * <p><b>This case guards the CONJUNCTION, not either condition alone</b>, and says so rather than
	 * looking better defended than it is: removing only the order-rung branch leaves it green, because
	 * this partner resolved no entry and the unambiguity branch then refuses anyway; removing only that
	 * branch leaves it green for the mirror reason, the order-rung gate answering false here (token
	 * {@code warfarin}, naming order names {@code {aspirin}}). It reddens against the pre-issue-#292 gate,
	 * both conditions gone, and against a mutation making {@code namesNamingOrder} always permit — which
	 * is what pins the ORDER rung's half of it now that that rung is a gate rather than a refusal.
	 */
	@Test
	public void oneOrderCarryingTwoSubstancesCodesKeepsTheRulesOwnName() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());
		PatientClinicalContext.ActiveDrugOrder mixed = DrugReferenceTestSupport.activeOrder(
			"order-mixed", "Aspirin 81mg", DrugReferenceTestSupport.set("aspirin"),
			DrugReferenceTestSupport.set("B01AA03", "N02BA01"));

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("aspirin"),
				DrugReferenceTestSupport.set("B01AA03", "N02BA01"), null, null,
				Arrays.asList(mixed)));

		// TWO chips, and that is the arrangement rather than a surprise: the seed's ibuprofen entry also
		// carries an aspirin rule, which the order's own name matches, so bestRulePerPartner groups a
		// second partner. The folded one is the warfarin chip — the class sentence rides on it because
		// B01AA03 sorts first — and it is the one this case is about.
		assertEquals(2, warnings.size(), "the folded warfarin chip and the seed's own aspirin chip, was: "
				+ warnings);
		String detail = DrugReferenceTestSupport.classChipDetails(warnings).get(0);
		assertTrue(detail.contains("is in the same cross-reactivity group"),
			"precondition: the first chip must be the FOLDED one, was: " + detail);
		assertTrue(detail.contains("interacts with active order warfarin "),
			"the rule the fold picked is the WARFARIN rule, so its finding must stay under that name —"
					+ " the label names the whole prescription and the rule is about one code of it,"
					+ " was: " + detail);
	}

	/**
	 * The reconciling half of the ORDER rung: where the rule's own token DOES name the order the label
	 * came from, the folded chip names that one prescription once.
	 *
	 * <p>Added by round 2 of the review on this change, which measured the first version's refusal as
	 * broader than its evidence. Both measurements behind that refusal are cases where the order-supplied
	 * name does not name the rule's drug —
	 * {@link #anOrderSuppliedNameTheRulesTokenDoesNotNameIsNeverHandedToTheRuleSentence}
	 * (token {@code naproxen}, naming order {@code Esomeprazole 20mg}) and
	 * {@link #oneOrderCarryingTwoSubstancesCodesKeepsTheRulesOwnName} (token {@code warfarin}, naming
	 * order names {@code {aspirin}}) — so a blanket refusal also declined the ticket's second named shape,
	 * the ordinary formulation ({@code naproxen} / {@code Naproxen 500mg}). The gate is now
	 * {@code DrugSafetyValidator.namesNamingOrder}: {@code DrugReference.matchesOrderName} over the naming
	 * order's own names, the same predicate {@code PatientClinicalContext.hasActiveDrug} used to admit the
	 * rule, narrowed from the patient's flattened name list to that one prescription. Both refusals above
	 * stay green and redden when the predicate is mutated to always permit.
	 *
	 * <p>The SAME fixture as that first refusal and the same rung — a partner keyed on {@code Naproxen}
	 * through the covered {@code M01AE02} and then renamed by {@code OrderPartner.nameByOrder} because it
	 * also holds {@code A02BC05}, which the fixture cannot name — with the two codes on ONE order instead
	 * of two. That is the whole difference between reconciling and refusing here.
	 *
	 * <p>Before this narrowing the same arrangement read {@code Ibuprofen interacts with active order
	 * naproxen — Moderate. … as active order Naproxen 500mg — …}: two names for one prescription, in the
	 * ticket's own shape.
	 *
	 * <p><b>What this case does NOT pin</b>, said rather than left to look better defended than it is:
	 * that the ORDER rung is what reconciled it. Deleting that rung outright leaves this green, because
	 * the branch below it then reads {@code labelEntry} — {@code Naproxen} here, which the token names
	 * unambiguously — and hands out the same label. Measured by disabling the rung and running the file.
	 * What pins the rung's reconciling half is
	 * {@code ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName}, whose
	 * ladder resolved no entry at all, so nothing else can hand out its label; and what pins its refusing
	 * half is the case above, which reddens with the rung gone. This case is the one that shows the
	 * reconciled OUTPUT on a partner that carries an entry as well.
	 */
	@Test
	public void anOrderSuppliedNameTheRulesTokenNamesIsHandedToBothSentences() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(RENAMED_PARTNER_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			renamedByItsOwnNaproxenOrder());

		assertEquals(1, warnings.size(), "one partner, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertEquals("Ibuprofen interacts with active order Naproxen 500mg — Moderate. Duplicate NSAID"
				+ " therapy: additive gastrointestinal toxicity. Ibuprofen is in the same ATC class"
				+ " (M01AE) as active order Naproxen 500mg — possible duplicate therapy", detail,
			"the rule's token names this very order, so the order's own display is the one name both"
					+ " sentences take");
		assertEquals(1, orderNamesIn(detail).size(), "one prescription, one name, was: " + detail);
	}

	/** One order carrying a code the fixture covers ({@code M01AE02}, resolving {@code Naproxen}) and one
	 *  it cannot name ({@code A02BC05}), so the partner is keyed on that substance and then renamed after
	 *  this order — the issue #186 rung, reached by the prescription the rule is actually about. */
	private static PatientClinicalContext renamedByItsOwnNaproxenOrder() {
		Set<String> codes = DrugReferenceTestSupport.set("M01AE02", "A02BC05");
		return DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("naproxen 500mg"), codes, null, null,
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-naproxen", "Naproxen 500mg",
				DrugReferenceTestSupport.set("naproxen 500mg"), codes)));
	}

	/**
	 * <b>A pinned FALSE claim, not desired behaviour.</b> The residue of the branch that lets the rule's
	 * token stand in for a missing ladder name: where the nameless order carries codes of two
	 * substances, the class sentence ends up asserting a class relationship about the substance the
	 * RULE's token names rather than about the one the class-matched code classifies.
	 *
	 * <p>The arrangement is {@link #oneOrderCarryingTwoSubstancesCodesKeepsTheRulesOwnName}'s, with the
	 * order NAMELESS. {@code B01AA03} sorts first, so {@code ruleAbout} picks the curated seed's WARFARIN
	 * rule; the NSAID group's prefixes are {@code M01AE} and {@code N02BA}, so the class arm matched on
	 * {@code N02BA01} — aspirin — and warfarin is in no cross-reactivity group at all. The premise is
	 * asserted below rather than reasoned about: the same nameless order carrying {@code B01AA03} ALONE
	 * raises the rule sentence and no class sentence. Its label is the code list, so the partner is NOT
	 * recorded as order-named — since issue #298 {@code OrderPartner.recordNameSource} admits an order
	 * only where the label is that order's name — and the order-named branch is therefore not entered at
	 * all, leaving the {@code !namesADrug} case to hand the token to both sentences. Before #298 the
	 * partner did carry its order here and what kept this arrangement on the same path was that
	 * {@code !namesADrug} was tested FIRST; the output is identical either way, which is why no
	 * expectation below moved. The chip therefore states
	 * {@code Ibuprofen is in the same cross-reactivity group (NSAID) as active order warfarin}, which is
	 * false of warfarin. {@code DrugReferenceInjector.renderFinding} copies the detail verbatim, so it
	 * reaches the prompt as citable {@code safety_finding} evidence too.
	 *
	 * <p>Before issue #292 the same arrangement read {@code as active order [ATC B01AA03, N02BA01]} —
	 * vague, and true of the prescription. Measured, by returning null unconditionally from
	 * {@code foldedPartnerLabel}. So this is the trade ADR Decision 39 records as its outcome-1
	 * trade-off — the class sentence's subject moving from the prescription to whatever the rule's token
	 * names — and it is pinned here rather than left to that prose. No clean narrowing exists on this
	 * branch: the ladder holds no entry to put the token to, and a "the token's own substance publishes
	 * the class-matched code" test would refuse the ticket's own live case, whose three codes the curated
	 * seed carries none of. <b>A change that closes it must fail here</b>, which is the whole point of
	 * pinning an output nobody wants.
	 *
	 * <p>Deliberately NOT swept by {@link #noFoldedChipNamesOneActiveOrderTwoWays}, and it would pass
	 * there: it names its one order ONCE, which is exactly the invariant that sweep asserts. That is why
	 * it is kept beside it — the sweep's count reads as the number of arrangements whose single name is
	 * the RIGHT one, and admitting a false name into it would make a residue look like a clean
	 * reconciliation.
	 */
	@Test
	public void aNamelessOrderCarryingTwoSubstancesCodesNamesTheClassSentenceAfterTheRulesDrug() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());
		Set<String> mixedCodes = DrugReferenceTestSupport.set("B01AA03", "N02BA01");

		List<SafetyWarning> warnings = validator.validate("", QUESTION,
			DrugReferenceTestSupport.ctx(60, null, null, mixedCodes, null, null,
				Arrays.asList(PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly(
					"order-nameless-mixed", "[ATC B01AA03, N02BA01]", mixedCodes))));

		Set<String> anticoagulantOnly = DrugReferenceTestSupport.set("B01AA03");
		List<String> ruleCodeAlone = DrugReferenceTestSupport.classChipDetails(validator.validate("",
			QUESTION, DrugReferenceTestSupport.ctx(60, null, null, anticoagulantOnly, null, null,
				Arrays.asList(PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly(
					"order-nameless-anticoagulant", "[ATC B01AA03]", anticoagulantOnly)))));
		assertEquals(Arrays.asList("Ibuprofen interacts with active order warfarin"
				+ " — increased risk of GI bleeding"), ruleCodeAlone,
			"premise: the code the picked rule is filed under classifies NOTHING here, so the class"
					+ " sentence below cannot be about the drug that rule names");

		assertEquals(1, warnings.size(), "one prescription, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertEquals("Ibuprofen interacts with active order warfarin — increased risk of GI bleeding."
				+ " Ibuprofen is in the same cross-reactivity group (NSAID) as active order warfarin"
				+ " — possible additive or duplicate-class therapy", detail,
			"the residue, pinned as WRONG so a change that closes it is visible here: the class sentence"
					+ " names warfarin, which is in no cross-reactivity group — the NSAID group was"
					+ " matched through the order's OTHER code");
		assertEquals(1, orderNamesIn(detail).size(),
			"one name, which is why the sweep would accept this arrangement and why it is pinned here"
					+ " instead, was: " + detail);
	}

	/**
	 * Where the ladder has no name AND the rule has no token, neither sentence yields.
	 *
	 * <p>The branch that lets the rule's token stand in for a missing ladder name asks for the TOKEN and
	 * not for {@code partnerLabel}, which falls back to the ATC code. Otherwise a nameless order beside a
	 * token-less rule would move the class sentence from {@code [ATC C09AA05]} — a code list explicitly
	 * labelled as codes — to a bare {@code C09AA05} reading as a drug name, which is the issue #155 shape
	 * the same change refuses on the other write site.
	 */
	@Test
	public void aTokenlessRuleBesideANamelessOrderLeavesTheCodeListLabelled() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(TOKENLESS_UNCOVERED_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", "Is lisinopril safe here?",
			DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("C09AA99"),
				null, null,
				Arrays.asList(PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly(
					"order-nameless-uncovered", "[ATC C09AA99]",
					DrugReferenceTestSupport.set("C09AA99")))));

		assertEquals(1, warnings.size(), "one order, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("as active order [ATC C09AA99] "),
			"the class sentence must keep its codes labelled AS codes: the rule has no token to lend it,"
					+ " and a bare code reads as a drug name, was: " + detail);
	}

	/**
	 * A chip no fold applies to must be byte-identical to what it always was: the rule arm keeps
	 * {@code partnerLabel}, which is also the label the injected {@code drug_reference} note and the
	 * grouping keys read, and the class arm keeps its ladder.
	 *
	 * <p>Byte-identical for THESE arrangements, which is narrower than the method name reads. The same
	 * change moved the guard on {@code OrderPartner.nameByOrder} into that method, so an unfolded
	 * class-only chip for a partly-covered order whose display is BLANK does change — from the bare code
	 * to the dataset's name. That is issue #155's defect being removed where the shared class was matched
	 * through a code the dataset COVERS, and issue #161's shape where it was matched through the
	 * uncovered code alone, because the covered constituent's name then does not classify the drug the
	 * sentence names. Both directions are stated in ADR Decision 39; the shape is
	 * {@link #aBlankDisplayNeverDisplacesTheDatasetName}'s, folded and covered there, and no arrangement
	 * here reaches the uncovered one.
	 */
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
	 * The shape issue #298's invariant actually closes, and the only case here that reaches it.
	 *
	 * <p>An order on the ladder's ORDER rung — the curated seed carries none of its three codes — whose
	 * display is BLANK but whose names are not. Its label is therefore a bare ATC code (the blank display
	 * resolves to the code), and unlike
	 * {@code namedByCodesOnly} it has names for {@code namesNamingOrder} to match: the one combination in
	 * which the order-named branch can say YES about a partner whose label is not a name. Under issue
	 * #298 it cannot arise — {@code displayNamesADrug} answers false for a blank display, and
	 * {@code OrderPartner.recordNameSource} admits an order only where that answer is true, so
	 * {@code namingOrder} is null and the branch is not entered — and the rule's own token is handed to both sentences, exactly as
	 * for the nameless order in {@link #theTicketsLiveCaseNamesTheOrderOnce}.
	 *
	 * <p><b>Why it is worth a case of its own.</b> The other arrangements that redden when
	 * {@code recordNameSource} is mutated to admit the order unconditionally all fail the OTHER way: such
	 * an order has no names, so {@code namesNamingOrder} answers false, nothing reconciles and the chip
	 * names one prescription twice — issue #292's defect returning. None of them reaches the failure
	 * issue #298 names, a bare ATC code handed to BOTH sentences, because that needs the branch to answer
	 * yes. This arrangement is where it does: mutate {@code recordNameSource} and this case reads
	 * {@code active order A01AD05} in both halves of the detail.
	 *
	 * <p>Builder-unreachable, like {@link #aBlankDisplayNeverDisplacesTheDatasetName}, and for the same
	 * reason — {@code PatientClinicalContextBuilder} takes a display from a name {@code addRaw} has
	 * already trimmed and dropped if blank. It needs the public constructor's latitude, which is what
	 * makes it a statement about the invariant rather than about production data.
	 */
	@Test
	public void aBlankDisplayWithNamesNeverHandsItsCodeToBothSentences() {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());
		List<SafetyWarning> warnings = validator.validate("", QUESTION, blankDisplayWithNames());

		assertEquals(1, warnings.size(), "one prescription, one folded chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertEquals("Ibuprofen interacts with active order aspirin — additive GI and bleeding risk."
				+ " Ibuprofen is in the same cross-reactivity group (NSAID) as active order aspirin"
				+ " — possible additive or duplicate-class therapy", detail,
			"the whole detail and not just its names: both sentences must be PRESENT as well as agreeing,"
					+ " or a fold that stopped firing would satisfy the name assertion below by vacuity");
		assertEquals(DrugReferenceTestSupport.set("aspirin"), orderNamesIn(detail),
			"the rule's own token is the only name either arm holds, and it must be the only one the"
					+ " detail carries, was: " + detail);
		assertFalse(detail.contains("A01AD05") || detail.contains("B01AC06")
				|| detail.contains("N02BA01"),
			"a bare ATC code is the label a blank display resolves to on this rung, and it must reach"
					+ " NEITHER sentence — handing it to both is the defect issue #298 closes, was: "
					+ detail);
	}

	/** An order on the ladder's ORDER rung whose display is blank and whose names are not — the one shape
	 *  where {@code namesNamingOrder} can answer yes for a partner whose label is a bare code. Shared
	 *  with {@link #noFoldedChipNamesOneActiveOrderTwoWays} so the sweep covers it too (issue #298). */
	private static PatientClinicalContext blankDisplayWithNames() {
		Set<String> names = DrugReferenceTestSupport.set("aspirin 81mg");
		return DrugReferenceTestSupport.ctx(60, null, names, ASPIRIN_ORDER_CODES, null, null,
			Arrays.asList(new PatientClinicalContext.ActiveDrugOrder("order-blank-but-named", "   ",
				names, ASPIRIN_ORDER_CODES)));
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
	 * <p>The REFUSAL arrangements are deliberately not swept here: a folded chip about two different
	 * co-medications, or about a rule with no name of its own, is MEANT to carry two names, so including
	 * them would make this assertion false for the right reason. There are FIVE of them, not the three an
	 * earlier form of this javadoc counted — the shared code, the ambiguous token, the token-less rule,
	 * and the two shapes on the ORDER rung where the rule's token does not name the naming order. Each
	 * has its own case above, and the count below is what stops a missing arrangement from passing as a
	 * clean sweep.
	 *
	 * <p>The fifth SWEPT arrangement is the ORDER rung's reconciling half
	 * ({@link #anOrderSuppliedNameTheRulesTokenNamesIsHandedToBothSentences}), which the first version of
	 * this change refused along with those two. The other arrangement on that rung lives in
	 * {@code ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName} — the
	 * curated seed, where the ladder resolved no entry at all — and is asserted byte-exact there rather
	 * than swept here.
	 *
	 * <p>The sixth is the blank-display order of
	 * {@link #aBlankDisplayWithNamesNeverHandsItsCodeToBothSentences} (issue #298), whose label is a bare
	 * ATC code and whose single name therefore comes from the rule's token.
	 *
	 * <p><b>Adding it required a second assertion to be worth anything</b>, and that is worth recording
	 * because the first version of this paragraph claimed otherwise. The one-name property alone does NOT
	 * see the failure that arrangement exists for: substituting the code for the name leaves exactly ONE
	 * name in both sentences, so the count below still passes. Measured, by a mutation that breaks only
	 * this arrangement — the byte-exact case reddened and this sweep did not. So the sweep also asserts
	 * that the name it counted is not ATC-CODE-SHAPED, which is what makes a code-for-name substitution
	 * visible here rather than in one case alone. {@link #ATC_CODE_SHAPED} says which shapes that covers
	 * and which it deliberately does not.
	 *
	 * <p>One RECONCILING arrangement is excluded too, and for the opposite reason:
	 * {@link #aNamelessOrderCarryingTwoSubstancesCodesNamesTheClassSentenceAfterTheRulesDrug} names its
	 * one order once and would pass, but the name it settles on is false of the class relationship, so
	 * counting it here would read as one more clean reconciliation. So the count below is the number of
	 * arrangements whose single name is the RIGHT one, not of every folded chip this file builds.
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
		runs.add(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.fixtureEntries(RENAMED_PARTNER_FIXTURE)))
				.validate("", QUESTION, renamedByItsOwnNaproxenOrder()));
		runs.add(DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.validate("", QUESTION, blankDisplayWithNames()));

		int foldedSeen = 0;
		for (List<SafetyWarning> warnings : runs) {
			for (String detail : DrugReferenceTestSupport.classChipDetails(warnings)) {
				if (!detail.contains("interacts with active order") || !detail.contains("is in the same")) {
					continue;
				}
				foldedSeen++;
				Set<String> named = orderNamesIn(detail);
				assertEquals(1, named.size(),
					"a folded detail must name its one active order once, was: " + detail);
				String only = named.iterator().next();
				assertFalse(only.startsWith("[ATC") || ATC_CODE_SHAPED.matcher(only).matches(),
					"and that one name must be a NAME: a bare ATC code standing in for it is issue #155's"
							+ " defect, and in a folded detail it is in both sentences at once, was: "
							+ detail);
			}
		}
		assertEquals(6, foldedSeen,
			"precondition: all six folded chips must have been reached, or this invariant passed by"
					+ " vacuity — the nameless order, the DDInter formulation, both subjects of the"
					+ " note-less same-class fixture, the order-named partner the rule's token names, and"
					+ " the blank display whose label is a bare code");
	}
}
