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
 * A tripwire on the KEY that {@link DrugReference#isUnclassifyingAtcCode}'s published figures are
 * measured against (issue #263).
 *
 * <p><b>The defect it exists to prevent, which has already happened once.</b> That javadoc's
 * "vetoing every residue would drop a class claim from 1974 ROW pairs; N of those keep it here"
 * read N = 1488 from issue #182 until issue #263. 1488 is the answer over the THIRTY groups
 * {@code UNCLASSIFYING_ATC_GROUPS} held then; issue #184 (PR #241) added six more and the figure was
 * not re-measured, so a sentence saying "keep it <em>here</em>" came to describe a rule this class no
 * longer applies. Nothing reddened, because the change was to a list and the stale value was in
 * prose. The corrected figures, and their bases, are at {@code DrugReference.isUnclassifyingAtcCode};
 * they are deliberately not repeated here, because nothing below asserts one.
 *
 * <p><b>So this pins the veto set rather than the figures.</b> It asserts which of the level-4
 * subgroups the shipped knowledge base actually publishes {@code isUnclassifyingAtcCode} refuses —
 * one direct call of the production predicate per subgroup, over the production load, with no
 * composition of its own. It reddens when a change reaches a subgroup this KB publishes — a group
 * added to that list or removed from it, or a KB refresh that changes which subgroups the dataset
 * publishes — and each of those is an occasion to re-measure the figures rather than a defect in
 * itself. When it goes red: re-measure the counts at {@code isUnclassifyingAtcCode} and at
 * {@code LOCALLY_APPLIED_ATC_GROUPS}, update them together with this list, and say which method
 * produced them.
 *
 * <p><b>Two holes, both real, and the second is the likelier one.</b> A group added that covers no
 * subgroup this KB publishes is invisible: 9 of the shipped list's 36 members are already in that
 * position (measured 2026-08-29 — {@code A07AX}, {@code C05BX}, {@code D02AX}, {@code D03AX},
 * {@code R03BX}, {@code S01JX}, {@code S01KX}, {@code S02DC}, {@code V07A}), and the list's own
 * javadoc says of two of them that they are members on the criterion rather than on measured impact.
 * And a KB refresh that adds or removes ROWS under subgroups the dataset already publishes moves
 * every pair figure this class is a tripwire for while leaving both assertions here green, because
 * both are keyed on the SET of subgroups and neither counts rows or pairs.
 *
 * <p><b>What it does NOT pin, stated so the guard does not look stronger than it is.</b> Not the
 * figures themselves — the counts at {@code isUnclassifyingAtcCode} and at
 * {@code LOCALLY_APPLIED_ATC_GROUPS} are all answers of {@code DrugSafetyValidator.sharedClass},
 * which is private, and computing its null condition here would be a reimplementation of pipeline
 * logic. Reaching them needs either a temporary mutation of
 * the production source or a residue list this repo does not carry, and which of the two differs by
 * counterfactual: the blanket-residue one is reachable from the INPUTS, since vetoing a subgroup and
 * removing it from the caller's code set both end in the same {@code continue}; the {@code drop4} one
 * is not, because it needs one of those four groups RETURNED, and that turns on
 * {@link DrugReference#isLocallyAppliedAtcCode} reading the subgroup string, which no choice of
 * inputs changes. Issue #263 measured them that way and recorded the method beside each figure;
 * this guard only says when they are due to be taken again.
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
