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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Issue #513 item 4 (ADR Decision 184): a question naming two drugs she already takes screens each against her orders,
 * so the one pair between them is raised from each side — on the 3.7.1 standalone <em>"Is it safe to continue her
 * nevirapine and rifampicin together?"</em> showed <em>"Nevirapine interacts with active order Rifampicin (rifampin) —
 * Major."</em> and <em>"Rifampicin (rifampin) interacts with active order Nevirapine — Major."</em>, one mechanism note
 * twice. Both findings stay — the scheduled-start date (#553) and the referent (#515) are asked of each side — and what
 * a clinician is SHOWN states the pair once: {@link RestatedReversePairs#folded}. Driven over the real checks' own chips.
 */
public class RestatedReversePairsTest {

	private static List<SafetyWarning> interactions(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ofType(DrugReferenceTestSupport.chipsOverAnswerFromDataset(
			DrugReferenceTestSupport.shippedServiceWithGroups(), "", question, names, null, Arrays.asList(orders)),
			SafetyWarning.TYPE_INTERACTION);
	}

	private static List<SafetyWarning> about(List<SafetyWarning> chips, String a, String b) {
		List<SafetyWarning> pair = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : chips) {
			String detail = chip.getDetail().toLowerCase();
			if (detail.contains(a) && detail.contains(b)) {
				pair.add(chip);
			}
		}
		return pair;
	}

	@Test
	public void onePairOfHerOwnOrdersIsShownOnce() {
		List<SafetyWarning> chips = interactions("Is it safe to continue her nevirapine and rifampicin together?",
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg", "Rifampicin"));
		assertEquals(2, about(chips, "nevirapine", "rifamp").size(), "precondition: the pair is raised from each side");

		List<SafetyWarning> shown = RestatedReversePairs.folded(chips);
		assertEquals(1, about(shown, "nevirapine", "rifamp").size(), "shown once: " + shown);
		assertEquals(chips.size() - 1, shown.size(), "and nothing else is folded");
	}

	@Test
	public void theFoldKeepsTheFullerNoteWhereOneSideCarriesMore() {
		List<SafetyWarning> chips = interactions("Is it ok that she is on methotrexate and salicylic acid?",
			DrugReferenceTestSupport.activeOrder("order-mtx", "Methotrexate", "Methotrexate"),
			DrugReferenceTestSupport.activeOrder("order-sal", "Salicylic acid", "Salicylic acid"));
		List<SafetyWarning> pair = about(chips, "methotrexate", "salicyl");
		assertEquals(2, pair.size(), "precondition: the pair is raised from each side: " + chips);
		int longest = Math.max(pair.get(0).getDetail().length(), pair.get(1).getDetail().length());

		List<SafetyWarning> shown = about(RestatedReversePairs.folded(chips), "methotrexate", "salicyl");
		assertEquals(1, shown.size(), "shown once: " + shown);
		assertEquals(longest, shown.get(0).getDetail().length(), "with the side carrying the fuller note");
	}

	@Test
	public void twoDifferentPairsAreNeverFolded() {
		List<SafetyWarning> chips = interactions("Can I give her rifampicin?",
			DrugReferenceTestSupport.activeOrder("order-nvp", "Nevirapine 200mg", "Nevirapine"),
			DrugReferenceTestSupport.activeOrder("order-sim", "Simvastatin 20mg", "Simvastatin"));
		assertTrue(chips.size() >= 2, "precondition: rifampicin relates to both: " + chips);
		assertEquals(chips, RestatedReversePairs.folded(chips));
	}
}
