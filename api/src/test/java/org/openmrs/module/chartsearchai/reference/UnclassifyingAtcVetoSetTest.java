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
 * {@code UNCLASSIFYING_ATC_GROUPS} held then; issue #241 added six more and the figure was not
 * re-measured, so a sentence saying "keep it <em>here</em>" came to describe a rule this class no
 * longer applies. Nothing reddened, because the change was to a list and the stale value was in
 * prose. The true figure over the shipped list is 1331, and 486 -> 643 the same way.
 *
 * <p><b>So this pins the veto set rather than the figures.</b> It asserts which of the level-4
 * subgroups the shipped knowledge base actually publishes {@code isUnclassifyingAtcCode} refuses —
 * one direct call of the production predicate per subgroup, over the production load, with no
 * composition of its own. Adding a group to that list, removing one, or refreshing the KB into one
 * whose codes fall differently all redden it, and every one of those is an occasion to re-measure
 * the figures rather than a defect in itself. When it goes red: re-measure the counts at
 * {@code isUnclassifyingAtcCode} and at {@code LOCALLY_APPLIED_ATC_GROUPS}, update them together
 * with this list, and say which method produced them.
 *
 * <p><b>What it does NOT pin, stated so the guard does not look stronger than it is.</b> Not the
 * figures themselves — 1974, 1331, 643, 46 and 21 are all answers of
 * {@code DrugSafetyValidator.sharedClass}, which is private, and computing its null condition here
 * would be a reimplementation of pipeline logic. Reaching them needs either a temporary mutation of
 * the production source (the {@code drop4} counterfactual, which requires a group to be RETURNED
 * eagerly and so cannot be produced from the inputs) or a residue list this repo does not carry
 * (the blanket-residue counterfactual, which an input-side removal does reach). Issue #263 measured
 * them that way and recorded the method beside each figure; this guard only says when they are due
 * to be taken again.
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
