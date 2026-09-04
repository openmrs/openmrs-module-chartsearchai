/*
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
 * A tripwire on the KEY that {@code DrugReference.UNCLASSIFYING_ATC_GROUPS}' published figures are
 * measured against (issue #263).
 *
 * <p><b>The defect it exists to prevent, which has already happened once.</b> A figure keyed on
 * {@code UNCLASSIFYING_ATC_GROUPS} was read from issue #182 until issue #263 while that list grew
 * from 30 members to 36 at issue #184 (PR #241), so a sentence saying a rule keeps N pairs came to
 * describe a rule this class no longer applies. Nothing reddened: the change was to a list and the
 * stale value was in prose. The figures, their bases and the whole of that story are at
 * {@code DrugReference.UNCLASSIFYING_ATC_GROUPS}, and are deliberately not repeated here.
 *
 * <p><b>So this pins the veto set rather than the figures.</b> It asserts, of the level-4 subgroups
 * the shipped knowledge base actually publishes, which ones
 * {@link DrugReference#isUnclassifyingAtcCode} refuses — one direct call of the production predicate
 * per subgroup, over the production load, with no composition of its own. It reddens when a change
 * reaches a subgroup this KB publishes: a group added to {@code UNCLASSIFYING_ATC_GROUPS} or removed
 * from it, or a KB refresh that changes the NUMBER of level-4 subgroups the dataset publishes or
 * which of them the list refuses. When it goes red,
 * re-measure that constant's own counts and update them with it, saying which method produced them.
 * {@code LOCALLY_APPLIED_ATC_GROUPS}' 46/21 and 19/2 are NOT due on that red — they are taken with
 * the claim filters off, i.e. with {@code UNCLASSIFYING_ATC_GROUPS} emptied, so a change to it
 * cannot move them. They are keyed on {@code LOCALLY_APPLIED_ATC_GROUPS} instead, and their tripwire
 * is {@link LocallyAppliedAtcGroupKeyTest}, which reddens on a change to that list this one is
 * silent on; a KB refresh is due on both. The exemption does not run the other way, and that class's
 * javadoc says why: {@code sharedClass} consults {@link DrugReference#isLocallyAppliedAtcCode} on
 * every call, to decide WHICH of a pair's shared subgroups it returns, so
 * {@code UNCLASSIFYING_ATC_GROUPS}' own counts and attributions are due on that case's red as well as
 * on this one's.
 *
 * <p><b>Two holes, both real, and the second is the likelier one.</b> A group added that covers no
 * subgroup this KB publishes is invisible: 9 of the shipped list's 36 members are already in that
 * position (measured 2026-08-29 — {@code A07AX}, {@code C05BX}, {@code D02AX}, {@code D03AX},
 * {@code R03BX}, {@code S01JX}, {@code S01KX}, {@code S02DC}, {@code V07A}), and the list's own
 * javadoc says of two of them that they are members on the criterion rather than on measured impact.
 * And a KB refresh can move every pair figure this class is a tripwire for while leaving this case
 * green, because its assertions are keyed on the NUMBER of level-4 subgroups the dataset publishes
 * and on which of them the list refuses, and neither of those counts rows or pairs. Two shapes
 * escape: ROWS added or removed under subgroups the dataset already publishes, and a swap of one
 * unrefused subgroup for another, which leaves the count and the refused set alike. Measured for the
 * first: dropping 1372 of the shipped KB's 2283 rows and adding 2, chosen so the published set is
 * unchanged, leaves this case passing.
 *
 * <p><b>What it does NOT pin, stated so the guard does not look stronger than it is.</b> Not the
 * figures themselves: they are answers of {@code DrugSafetyValidator.sharedClass}, which is private,
 * so computing its null condition here would be a reimplementation of pipeline logic. Issue #263
 * reached them by temporarily mutating the production source, and of the two counterfactuals named
 * below only one could have been reached from the INPUTS instead. Vetoing a subgroup and removing it
 * from the caller's code set both end in the same {@code continue}, so the blanket-residue one is
 * input-reachable. Striking the four locally-applied prefixes behind
 * {@code LOCALLY_APPLIED_ATC_GROUPS}' 46/21 is not: it needs one of those four returned EAGERLY,
 * ahead of a surviving systemic subgroup, and no uniform input transformation promotes one there —
 * the unmutated method does return them, but through the fallback, when nothing systemic survives.
 * Deciding per pair which subgroups to withhold would re-express this loop's own ordering rule,
 * which is the re-implementation the measurement rule forbids. This guard only says when the figures
 * are due to be taken again.
 */
public class UnclassifyingAtcVetoSetTest {

	/**
	 * The level-4 subgroups of the shipped KB that {@code isUnclassifyingAtcCode} refuses: the 20
	 * residues under {@link DrugReference#isLocallyAppliedAtcCode}'s groups that this KB uses, the 8
	 * children of {@code V03A} it uses, and the 6 issue #184 added. 34, over the 594 level-4 subgroups
	 * {@link DrugReference#atcSubgroups()} yields across the whole dataset.
	 */
	private static final List<String> REFUSED_IN_THE_SHIPPED_KB = Arrays.asList("A01AD", "A16AX",
			"B05CX", "B06AX", "C05AX", "D01AE", "D04AX", "D05AX", "D06AX", "D06BX", "D08AX", "D10AX",
			"D11AX", "G01AX", "G02CX", "M02AX", "M09AX", "N07XX", "P03AX", "R01AX", "R02AX", "R07AX",
			"S01AX", "S01EX", "S01GX", "S01XA", "V03AB", "V03AC", "V03AE", "V03AF", "V03AH", "V03AN",
			"V03AX", "V03AZ");

	@Test
	public void theVetoSetTheClassClaimFiguresAreKeyedOnHasNotChanged() {
		Set<String> published = new TreeSet<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			published.addAll(entry.atcSubgroups());
		}
		assertEquals(34, REFUSED_IN_THE_SHIPPED_KB.size(),
				"the 34 this class's field javadoc decomposes as 20 + 8 + 6; the set comparison below"
						+ " would not see a duplicate entry");
		assertEquals(594, published.size(),
				"the shipped KB's level-4 subgroups, the population the veto set is read over");

		Set<String> refused = new TreeSet<String>();
		for (String subgroup : published) {
			if (DrugReference.isUnclassifyingAtcCode(subgroup)) {
				refused.add(subgroup);
			}
		}

		assertEquals(new TreeSet<String>(REFUSED_IN_THE_SHIPPED_KB), refused,
				"the veto set moved, so every figure measured against it is now unverified — see this "
						+ "class's javadoc for which ones and where they live");
	}
}
