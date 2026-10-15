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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * An ingredient a finding names of her combination order, where the order's display names it only by the combination's
 * name, is said to come from that order (ADR Decision 278). Since the knowledge base names co-trimoxazole under both of its
 * ingredients (ADR Decision 266), a "Cotrimoxazole 960mg" order displays a name of each, and the clause the finding used to
 * carry — "Trimethoprim from Cotrimoxazole 960mg" — was refused as noise: on the 3.7.1 standalone Amina, on that order,
 * asked "Can I give her phenytoin?" and read "Phenytoin interacts with active order Trimethoprim" with nothing saying which
 * of her orders that is. Over the shipped knowledge base.
 */
public class CombinationOrderIngredientAttributionTest extends BaseModuleContextSensitiveTest {

	@Test
	public void eachIngredientOfHerCombinationOrderIsSaidToComeFromIt() {
		List<SafetyWarning> warnings = ask("Can I give her phenytoin?", "Cotrimoxazole 960mg");
		assertEquals(Arrays.asList("Trimethoprim from Cotrimoxazole 960mg"), bridgesOf(warnings, "Trimethoprim"),
			"was: " + DrugReferenceTestSupport.details(warnings));
		assertEquals(Arrays.asList("Sulfamethoxazole from Cotrimoxazole 960mg"), bridgesOf(warnings, "Sulfamethoxazole"),
			"was: " + DrugReferenceTestSupport.details(warnings));
	}

	/** An order whose display names the ingredient by a name of its own still states no clause (issue #347). */
	@Test
	public void anOrderNamingTheIngredientByItsOwnNameStatesNone() {
		List<SafetyWarning> warnings = ask("Can I give her phenytoin?", "Trimethoprim 200mg");
		assertTrue(bridgesOf(warnings, "Trimethoprim").isEmpty(), "was: " + DrugReferenceTestSupport.details(warnings));
		assertTrue(DrugReferenceTestSupport.details(warnings).toString().contains("active order Trimethoprim — Moderate."),
			"precondition: the finding is about her order, was: " + DrugReferenceTestSupport.details(warnings));
	}

	/** The bridge texts of the chips naming {@code ingredient} as the partner, in order. */
	private static List<String> bridgesOf(List<SafetyWarning> warnings, String ingredient) {
		List<String> bridged = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail() != null && warning.getDetail().contains("active order " + ingredient + " ")) {
				for (SafetyWarning.ChartOrderBridge bridge : warning.chartOrderBridges()) {
					bridged.add(bridge.toString());
				}
			}
		}
		return bridged;
	}

	private static List<SafetyWarning> ask(String question, String... orderDisplays) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (int i = 0; i < orderDisplays.length; i++) {
			PatientClinicalContext.ActiveDrugOrder order = DrugReferenceTestSupport.activeOrder("order-" + i, orderDisplays[i]);
			orders.add(order);
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.shippedServiceWithGroups()).validate("", question,
			DrugReferenceTestSupport.ctx(40, null, names, Collections.<String> emptySet(), null, null, orders));
	}
}
