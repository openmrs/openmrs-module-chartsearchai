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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;
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

	/**
	 * And that the widening the oracle above needed did not become a production surface. Issue #447
	 * made {@link DrugSafetyValidator#identifies} package-private so this class could compose it with
	 * {@code clearsSeverityFloor} independently of the type under test; its javadoc then states that no
	 * production class outside {@code DrugSafetyValidator} may call it, because the three name
	 * questions have their own accessors and reaching past them is #86/#128/#147's shape. A rule
	 * stated and not enforced is one the next caller breaks, so it is read off the source here.
	 *
	 * <p>Over {@code api/src/main} and by FILE rather than by body: what is forbidden is a caller in
	 * another production class, and any mention in one is that. {@code DrugSafetyValidator}'s own file
	 * — including the nested {@code AboveFloorRules}, which reaches it as a nestmate — is the one
	 * permitted home.
	 *
	 * <p><b>The needle is the bare {@code identifies(}, and that is deliberate</b>: a same-package
	 * caller writes it unqualified, so a needle naming the class would miss the shape this forbids.
	 * The residue is the other direction — an unrelated production method of that name would fail this
	 * case spuriously. It fails loudly and says which file, so that is a cost paid in legibility
	 * rather than in silence, which is the trade this repo takes for a rule that would otherwise have
	 * no enforcement at all.
	 */
	@Test
	public void noProductionClassButTheValidatorItselfCallsTheNamingPredicate() throws IOException {
		List<String> callers = new ArrayList<String>();
		Path root = ModuleSourceRoot.apiRoot().resolve("src/main/java");
		try (Stream<Path> sources = Files.walk(root)) {
			for (Path file : sources.filter(f -> f.toString().endsWith(".java")).collect(
				Collectors.toList())) {
				if (file.getFileName().toString().equals("DrugSafetyValidator.java")) {
					continue;
				}
				String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				if (text.contains("identifies(")) {
					callers.add(root.relativize(file).toString());
				}
			}
		}

		assertTrue(Files.exists(root.resolve("org/openmrs/module/chartsearchai/reference/"
				+ "DrugSafetyValidator.java")), "this guard must be reading the real source root, or it "
						+ "forbids nothing by scanning nothing: " + root);
		assertEquals(Collections.<String> emptyList(), callers,
			"DrugSafetyValidator.identifies is package-private for this class's oracle alone (issue"
					+ " #447), and its javadoc says no production class outside it may call it — ask"
					+ " DrugReference.matchesText, matchesDrugName or isNamed instead, per #86/#128/#147");
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

		assertEquals(
			readable(AboveFloorRules.of(entries, 0).aboveFloorRulesAgainst(entries.get(0), entries.get(1))),
			readable(AboveFloorRules.of(repeated, 0).aboveFloorRulesAgainst(entries.get(0), entries.get(1))),
			"a row the caller's list carries twice must relate its rules once, or the join reports the"
					+ " reference data carrying two rules where it carries one (issue #447)");
		assertAgrees(repeated, "the excerpt with its first row handed twice");
	}

	/**
	 * The join hands back a view a consumer cannot edit. The scan this replaced built a fresh list per
	 * ask, so sorting or filtering the answer harmed nobody; this list is the join's own and lives for
	 * the whole arm. {@code pairKeyNames} takes {@code get(0)} off it while
	 * {@code collectQuestionPairInteraction} hands the same object to {@code bestRule}, so an in-place
	 * sort at either site would move the partner label the other picks — and that label is the
	 * {@code unorderedPairKey}, so a chip merges or moves, silently and in one direction.
	 *
	 * <p>Asserted because nothing else could see it: with both wraps removed the whole api suite stays
	 * green, measured. That is what makes them the kind of clause a later edit deletes for free.
	 */
	@Test
	public void theRulesTheJoinHandsBackCannotBeEditedByAConsumer() {
		List<DrugReference> screened = DrugReferenceTestSupport.ddinterEntries();
		AboveFloorRules rules = AboveFloorRules.of(screened, 0);
		List<DrugReference.Interaction> related = null;
		for (DrugReference subject : screened) {
			for (DrugReference other : screened) {
				if (subject != other && !rules.aboveFloorRulesAgainst(subject, other).isEmpty()) {
					related = rules.aboveFloorRulesAgainst(subject, other);
				}
			}
		}
		assertNotNull(related, "the excerpt must relate some pair, or this asserts nothing");

		try {
			related.set(0, null);
			fail("the join handed back a list a consumer can edit; sorting or filtering it in place would"
					+ " change what every later reader of the arm is told about a pair (issue #447)");
		}
		catch (UnsupportedOperationException expected) {
			// what an unmodifiable view owes its caller
		}
	}

	/**
	 * And the same of the ATC index's own accessor, which has the same hazard for the same reason —
	 * its answer is the index's list, and {@code of} is still iterating the screened rows when it is
	 * read. Asserted separately because the case above reads only the JOIN's accessor: with this wrap
	 * removed and that one kept, the whole api suite stayed green, measured.
	 */
	@Test
	public void theEntriesAnAtcCodeReachesCannotBeEditedByAConsumer() throws IOException {
		List<DrugReference> screened = DrugReferenceTestSupport.fixtureEntries(PAIR_FIXTURE);
		Map<String, List<DrugReference>> index = AboveFloorRules.atcIndexOf(screened);
		List<DrugReference> coded = AboveFloorRules.entriesCodedBy("b01aa04", index);

		assertTrue(coded.size() > 1, "the fixture must file more than one row under that code, or this"
				+ " asserts nothing: " + coded.size());
		try {
			coded.set(0, null);
			fail("entriesCodedBy handed back a list a consumer can edit; the join is still building over"
					+ " these rows when it is read (issue #447)");
		}
		catch (UnsupportedOperationException expected) {
			// what an unmodifiable view owes its caller
		}
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
