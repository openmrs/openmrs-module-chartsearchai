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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator.AboveFloorRules;

/**
 * That the per-pass rule join answers exactly what asking the predicate of every rule answers, for
 * every ordered pair and at every severity floor (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/447">#447</a>).
 *
 * <p>{@link AboveFloorRules} exists to stop both pairwise arms scanning a subject's whole interaction
 * list once per ordered pair, and it does that by asking two indexes which screened entries a rule
 * could name. <b>A narrowing that loses one candidate drops an interaction chip, fail-CLOSED, in a
 * safety net</b> — so what has to be pinned is not the arm's speed but the join's ANSWER, which is
 * why this class exists beside {@code QuestionPairRuleScanPerPassTest} rather than inside it. The
 * chips a screen happens to raise cannot see it: over a dataset where each token names one entry,
 * a too-narrow index and an exact one raise the same chips.
 *
 * <p>The same shape as {@code NameIndexAgreesWithIsNamedTest}, which pins
 * {@link DrugReferenceService#nameIndex()} against {@link DrugReference#isNamed} for issue #339 and
 * for the same reason. This one reaches further in one respect that matters: that guard covers the
 * NAME leg of {@link DrugSafetyValidator#identifies} and nothing in the tree covered the ATC leg,
 * which {@link #theJoinAgreesOverAFixtureCarryingAnAtcOnlyRule} is here for.
 *
 * <p>The oracle is the predicate itself — {@link DrugSafetyValidator#clearsSeverityFloor} and
 * {@link DrugSafetyValidator#identifies}, composed over the entry's own rule list, which is what the
 * scan the join replaced did — asked of entries the real parsers produced. Not a second reading of
 * what naming means, which is the thing that could drift.
 */
public class AboveFloorRuleJoinAgreementTest {

	/** Route variants sharing a token, a pair joined by two differently-tokened rules, and an ATC-only
	 *  rule — the shapes the DDInter sources never write, which is where the ATC leg lives. */
	private static final String PAIR_FIXTURE = "chartsearchai-test/drug-reference-question-pairs.json";

	/**
	 * Every floor {@link DrugSafetyValidator#clearsSeverityFloor} can be handed, from one that filters
	 * nothing to one above every rank the source vocabulary has. Asked at all of them because the
	 * floor is applied at BUILD time now and once per pair before, so a floor the index handled
	 * differently from the scan would be invisible at the shipped default alone.
	 */
	private static final int[] FLOORS = { 0, 1, 2, 3, 4 };

	/** What the scan {@link AboveFloorRules} replaced returned: every rule of {@code subject} that
	 *  clears {@code floor} and names {@code other}, in the subject's own dataset order. */
	private static List<DrugReference.Interaction> byPredicate(DrugReference subject,
			DrugReference other, int floor) {
		List<DrugReference.Interaction> out = new ArrayList<DrugReference.Interaction>();
		for (DrugReference.Interaction rule : subject.getInteractions()) {
			if (DrugSafetyValidator.clearsSeverityFloor(rule, floor)
					&& DrugSafetyValidator.identifies(rule, other)) {
				out.add(rule);
			}
		}
		return out;
	}

	/** {@code DrugReference.Interaction} declares no {@code toString}, so an equality failure over the
	 *  rules themselves reports two lists of identity hashes — unreadable at exactly the moment it
	 *  matters. Compared through this instead: the fields that say WHICH rule it is. */
	private static List<String> readable(List<DrugReference.Interaction> rules) {
		List<String> out = new ArrayList<String>();
		for (DrugReference.Interaction rule : rules) {
			out.add(rule.getToken() + "/" + rule.getAtc() + " (" + rule.getSeverity() + ")");
		}
		return out;
	}

	/**
	 * @return how many of the related rules carried NO name token, so the ATC leg is what found them.
	 *         Returned rather than asserted here because only one dataset in the tree can carry that
	 *         shape, and a caller that cannot must not be made to claim it.
	 */
	private static int assertAgrees(List<DrugReference> screened, String dataset) {
		assertTrue(screened.size() >= 2, dataset + " must carry a pair to join, or this says nothing");
		int joined = 0;
		int byTheCodeLeg = 0;
		for (int floor : FLOORS) {
			AboveFloorRules rules = AboveFloorRules.of(screened, floor);
			// EVERY ordered pair, (subject, subject) included: the scan this replaced had no
			// self-exclusion, so the join must not have acquired one.
			for (DrugReference subject : screened) {
				for (DrugReference other : screened) {
					List<DrugReference.Interaction> expected = byPredicate(subject, other, floor);
					assertEquals(readable(expected),
						readable(rules.aboveFloorRulesAgainst(subject, other)),
						"the per-pass join and DrugSafetyValidator.identifies disagree about which rules"
								+ " of " + subject.displayLabel() + " name " + other.displayLabel()
								+ " at floor " + floor + " over " + dataset + "; a join that loses a rule"
								+ " drops an interaction chip fail-closed, and one that invents a rule"
								+ " states a relationship the data does not carry (issue #447)");
					joined += expected.size();
					for (DrugReference.Interaction rule : expected) {
						if (rule.getToken() == null || rule.getToken().trim().isEmpty()) {
							byTheCodeLeg++;
						}
					}
				}
			}
		}
		assertTrue(joined > 0, dataset + " related no pair at any floor, so the agreement is vacuous");
		return byTheCodeLeg;
	}

	@Test
	public void theJoinAgreesOverTheBundledDdinterExcerpt() {
		assertAgrees(DrugReferenceTestSupport.ddinterEntries(), "the pinned DDInter excerpt");
	}

	@Test
	public void theJoinAgreesOverAFixtureCarryingAnAtcOnlyRule() throws IOException {
		// The ATC leg of identifies is unreachable from either DDInter source, which always writes a
		// name token, so nothing in the tree asked it of an index before this.
		int byTheCodeLeg = assertAgrees(DrugReferenceTestSupport.fixtureEntries(PAIR_FIXTURE),
			"the question-pair fixture");

		// The premise, asserted rather than assumed: this fixture's name-token rules satisfy "something
		// was related" on their own, so without this the ATC-only rows could be deleted and the case
		// would stay green under a javadoc saying it is what covers that leg.
		assertTrue(byTheCodeLeg > 0, "no rule this fixture related carried a bare ATC code, so the leg"
				+ " this case exists for was never exercised — the name leg satisfied it alone");
	}

	/**
	 * A rule naming its partner by ATC code alone relates EVERY screened row filed under that code,
	 * not one of them — which is why {@code atcIndexOf} maps a code to a LIST. Until this case existed
	 * nothing could tell that from a map holding a single entry: keeping only the last row per code
	 * left the whole api suite green while dropping an interaction rule fail-closed. The shipped
	 * knowledge base cannot see it either, because {@code ddinter} writes each rule's ATC beside the
	 * partner's own name token, so the name leg covers for the code leg there.
	 */
	@Test
	public void anAtcOnlyRuleRelatesEveryScreenedRowFiledUnderThatCode() throws IOException {
		List<DrugReference> screened = DrugReferenceTestSupport.fixtureEntries(PAIR_FIXTURE);
		DrugReference subject = DrugReferenceTestSupport.row(screened, "Miconazole");
		List<DrugReference> coded = new ArrayList<DrugReference>();
		for (DrugReference entry : screened) {
			if (entry.normalizedAtcCodes().contains("B01AA04")) {
				coded.add(entry);
			}
		}

		assertTrue(coded.size() > 1, "the fixture must file more than one row under the rule's code, or"
				+ " an index keeping one entry per code would pass this: " + coded.size());
		AboveFloorRules rules = AboveFloorRules.of(screened, 0);
		for (DrugReference other : coded) {
			assertEquals(readable(byPredicate(subject, other, 0)),
				readable(rules.aboveFloorRulesAgainst(subject, other)),
				"the ATC-only rule must relate " + other.displayLabel() + " too; an index keeping one"
						+ " entry per code drops the rest fail-closed (issue #447)");
			assertTrue(!rules.aboveFloorRulesAgainst(subject, other).isEmpty(),
				"the arrangement must actually relate " + other.displayLabel() + " by its code, or this"
						+ " case asserts an agreement about nothing");
		}
	}

	/**
	 * A screened list carrying one row TWICE still relates that row's rules once. The scan this join
	 * replaced read a subject's list once per ASK, so it could not double anything; building per
	 * OCCURRENCE would append every rule of a repeated row twice, and the accessor would then say the
	 * data carries two rules where it carries one — which {@code bestRule} and {@code pairKeyNames}
	 * would swallow, since one takes the strongest and the other the first.
	 *
	 * <p>Not reachable from production today — both arms hand a list built from a set — and asserted
	 * anyway, because the old shape could not break this way and a precondition nothing checks is one
	 * the next caller breaks.
	 */
	@Test
	public void aScreenedRowHandedTwiceRelatesItsRulesOnce() {
		List<DrugReference> entries = DrugReferenceTestSupport.ddinterEntries();
		List<DrugReference> repeated = new ArrayList<DrugReference>(entries);
		repeated.add(entries.get(0));

		assertEquals(AboveFloorRules.of(entries, 0).aboveFloorRulesAgainst(entries.get(0), entries.get(1)),
			AboveFloorRules.of(repeated, 0).aboveFloorRulesAgainst(entries.get(0), entries.get(1)),
			"a row the caller's list carries twice must relate its rules once, or the join reports the"
					+ " reference data carrying two rules where it carries one (issue #447)");
		assertAgrees(repeated, "the excerpt with its first row handed twice");
	}

	@Test
	public void theJoinAgreesOverTheRowsARouteVariantQuestionResolvesFromTheShippedKnowledgeBase() {
		// The shipped knowledge base, not the excerpt: a token named by MORE THAN ONE entry is the only
		// shape where admitting or losing a claimant changes an answer, and route/formulation families
		// publishing one shared rxnorm_name are where the shipped data carries it. Resolved through the
		// real findImpliedByQuery so the slice is one a question can actually put in play.
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.shippedEntries());
		List<DrugReference> screened = service.findImpliedByQuery(
			"dexamethasone, lidocaine, timolol, atropine, neomycin, minoxidil and paclitaxel");

		assertTrue(screened.size() > 7, "the question must resolve more rows than the words it names,"
				+ " or the slice carries no family and the multi-claimant shape is untested: "
				+ screened.size());
		assertAgrees(screened, "the rows a route-variant question resolves from the shipped KB");
	}
}
