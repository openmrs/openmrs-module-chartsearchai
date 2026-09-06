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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * A tripwire on the KEY that {@code DrugReference.LOCALLY_APPLIED_ATC_GROUPS}' published figures are
 * measured against — the sibling of {@link UnclassifyingAtcVetoSetTest}, one list along (issue
 * #263).
 *
 * <p><b>Why there are two.</b> That constant's 46/21 and 19/2 are answers of
 * {@code DrugSafetyValidator.sharedClass} taken under a configuration of the list they are about, so
 * a change to THAT list moves them — and the veto-set tripwire never touches it.
 * {@code DrugReference.UNCLASSIFYING_ATC_GROUPS}' javadoc anticipates the change ("extend that list
 * and it has to be re-derived against the same index"),
 * which is precisely the shape issue #263 was filed over: a figure keyed on a list that moved under
 * it, with nothing red. PR #331's first review round measured exactly that on head
 * {@code 97445233}, where this case did not yet exist: adding {@code A06AD} to
 * {@code LOCALLY_APPLIED_ATC_GROUPS} and, as the partition guard in
 * {@code UnmappedOrderAdministrationSiteTest} demands, to {@code SITE_GUT}, left the whole api suite
 * green while moving 46 to 47 and 19 to 20.
 *
 * <p><b>What it pins.</b> Of the level-4 subgroups the shipped knowledge base actually publishes,
 * which ones {@link DrugReference#isLocallyAppliedAtcCode} reads as a locally applied presentation —
 * one direct call of the production predicate per subgroup, over the production load, with no
 * composition of its own. That predicate consults both halves of the rule, so this case is keyed on
 * {@code LOCALLY_APPLIED_ATC_GROUPS} and {@code SYSTEMIC_USE_EXCEPTIONS} together, which is how the
 * figures are keyed too. It reddens when a change to either reaches a subgroup this knowledge base
 * publishes, and on a knowledge-base refresh that changes the NUMBER of level-4 subgroups the
 * dataset publishes or which of them the rule reads as locally applied — the sibling's wording, and
 * for the sibling's reason: a refresh that swaps one subgroup the rule does NOT read as locally
 * applied for another leaves all three assertions here alike and escapes. When it goes red, re-measure the
 * figures at {@code DrugReference.LOCALLY_APPLIED_ATC_GROUPS} and update them with it, saying which
 * method produced them.
 *
 * <p><b>And not only those.</b> {@code DrugSafetyValidator.sharedClass} consults
 * {@link DrugReference#isLocallyAppliedAtcCode} to decide WHICH of a pair's shared subgroups it
 * returns — a non-locally-applied one eagerly, a locally applied one only as the fallback — so every
 * figure that is an answer of that method is keyed on this list as well as on whatever else it is
 * keyed on, {@code DrugReference.UNCLASSIFYING_ATC_GROUPS}' own counts and per-subgroup attributions
 * included. The asymmetry with the sibling is not a symmetry to restore: that one can rule 46/21 and
 * 19/2 out of ITS red for a stated reason, that they are taken with the veto list emptied, and this
 * list has no such configuration — it is live in every run. Whether a given figure actually MOVES is
 * a separate question this case cannot answer, since it turns on whether the edit reaches a pair
 * sharing both a locally applied and a non-locally-applied subgroup with the locally applied one
 * sorting first; re-taking a figure may well reproduce the published value. Re-take it and say so.
 *
 * <p><b>It is not the partition guards.</b> {@code UnmappedOrderAdministrationSiteTest} already
 * carries two cases over this list, and neither can see the change above: they ask whether every
 * locally applied group is accounted for by a site, which an edit that adds the group to a site
 * satisfies. (A third case there is over {@code SYSTEMIC_USE_EXCEPTIONS} and does redden on removing
 * {@code D01B}, which is a different edit.) Same list, different question — theirs is that the site
 * table stays a partition of it,
 * this one's is that the list itself has not moved under a published figure. Measured 2026-08-30
 * under that {@code A06AD} edit: 1 of the api suite's 1571 cases red, and it is this one.
 *
 * <p><b>Two holes, the same two the sibling has.</b> A prefix added that reaches no subgroup this
 * knowledge base publishes is invisible here, exactly as {@code S02DC} and {@code V07A} are to the
 * sibling. And a knowledge-base refresh can move every pair figure this case is a tripwire for while
 * leaving it green, because the assertion is keyed on which PUBLISHED SUBGROUPS the rule reads as
 * locally applied, which counts neither rows nor pairs. Two shapes escape, as at the sibling: rows
 * added or removed under subgroups the dataset already publishes, and a swap of one subgroup the
 * rule does not read as locally applied for another, which leaves the count and the set alike.
 *
 * <p><b>What it does NOT pin, stated so the guard does not look stronger than it is.</b> Not the
 * figures themselves. {@code DrugSafetyValidator.sharedClass} is private, and reaching 46/21 and
 * 19/2 needs the four prefixes issue #166 measured struck out of the list — which
 * {@link UnclassifyingAtcVetoSetTest}'s javadoc records as the one of issue #263's two
 * counterfactuals that is NOT reachable from the inputs, so computing it here would re-express that
 * method's own ordering rule. This case only says when those figures are due to be taken again.
 */
public class LocallyAppliedAtcGroupKeyTest {

	/**
	 * The level-4 subgroups of the shipped KB that {@code isLocallyAppliedAtcCode} reads as a locally
	 * applied presentation. 111, which is the 117 that {@code SYSTEMIC_USE_EXCEPTIONS}' javadoc
	 * records under these prefixes less the six it names as published under a systemic-use group — an
	 * independent corroboration of that sentence, and the reason this case is keyed on both halves.
	 */
	private static final List<String> LOCALLY_APPLIED_IN_THE_SHIPPED_KB = Arrays.asList(
			"A01AB", "A01AC", "A01AD", "A07AA", "A07AC", "A07EA", "A07EB", "A07EC", "B02BC",
			"B05CA", "B05CB", "B05CX", "C05AA", "C05AD", "C05AE", "C05AX", "C05BA", "D01AA",
			"D01AC", "D01AE", "D02AE", "D02BA", "D04AA", "D04AB", "D04AX", "D05AD", "D05AX",
			"D06AA", "D06AX", "D06BA", "D06BB", "D06BX", "D07AA", "D07AB", "D07AC", "D07AD",
			"D07XA", "D07XB", "D07XC", "D08AC", "D08AE", "D08AG", "D08AH", "D08AJ", "D08AK",
			"D08AL", "D08AX", "D09AA", "D10AA", "D10AD", "D10AE", "D10AF", "D10AX", "D11AA",
			"D11AC", "D11AH", "D11AX", "G01AA", "G01AC", "G01AD", "G01AF", "G01AX", "G02CC",
			"M02AA", "M02AB", "M02AX", "P03AA", "P03AB", "P03AX", "R01AA", "R01AB", "R01AC",
			"R01AD", "R01AX", "R02AA", "R02AB", "R02AD", "R02AX", "R03AA", "R03AB", "R03AC",
			"R03BA", "R03BB", "R03BC", "S01AA", "S01AB", "S01AD", "S01AE", "S01AX", "S01BA",
			"S01BC", "S01CB", "S01EA", "S01EB", "S01EC", "S01ED", "S01EE", "S01EX", "S01FA",
			"S01FB", "S01GA", "S01GX", "S01HA", "S01JA", "S01LA", "S01XA", "S02AA", "S02BA",
			"S02DA", "S03AA", "S03BA");

	@Test
	public void theLocallyAppliedSetTheClassClaimFiguresAreKeyedOnHasNotChanged() {
		Set<String> published = new TreeSet<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			published.addAll(entry.atcSubgroups());
		}
		assertEquals(111, LOCALLY_APPLIED_IN_THE_SHIPPED_KB.size(),
				"the 111 this class's field javadoc reconciles against SYSTEMIC_USE_EXCEPTIONS' own"
						+ " 117 less six; the set comparison below would not see a duplicate entry");
		assertEquals(594, published.size(),
				"the shipped KB's level-4 subgroups, the population this rule is read over");

		Set<String> locallyApplied = new TreeSet<String>();
		for (String subgroup : published) {
			if (DrugReference.isLocallyAppliedAtcCode(subgroup)) {
				locallyApplied.add(subgroup);
			}
		}

		assertEquals(new TreeSet<String>(LOCALLY_APPLIED_IN_THE_SHIPPED_KB), locallyApplied,
				"the locally-applied set moved, so every figure measured against it is now unverified "
						+ "— see this class's javadoc for which ones and where they live");
	}
}
