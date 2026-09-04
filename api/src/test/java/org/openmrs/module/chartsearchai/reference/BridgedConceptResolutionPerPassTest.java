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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The bridged-concept leg is resolved at most ONCE per {@code validate} pass, and not at all by a pass
 * no arm of which asks for it (issue #353).
 *
 * <p>Both halves are cost properties with no other guard, and each fails silently. {@code resolvesFrom}
 * runs once per (row, order) pair inside two nested loops, so a leg resolved per CALL would put a
 * ranked dataset resolution inside that product — the shape issue #256 removed from the class arm, and
 * nothing about it would go red. And every consumer of the leg sits inside an interaction arm, so a
 * pass whose toggles are off or whose question and answer resolve no drug must not pay for it at all;
 * an eager hoist into {@code validate} is a one-line change that likewise reddens nothing.
 *
 * <p>Counted on {@code DrugReferenceService.findByBridgedConcept} itself rather than on
 * {@code getAll()}, which the pass calls for many other reasons: this is a statement about how often
 * the LEG is resolved, and the two are different questions. The candidate set is resolved OUTSIDE the
 * measured window and handed to {@code validate}, which is what production does
 * ({@code DrugReferenceInjector} passes its own resolution down, issue #255) — so what these count is
 * the pass's own cost and not {@code findForActiveOrders}'.
 */
public class BridgedConceptResolutionPerPassTest {

	/** The uuid the shipped bridge records for CIEL 105281 — the fixture's own bridged concept. */
	private static final String COTRIMOXAZOLE_CONCEPT = "105281AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** The real service over the fixture, counting the calls that resolve the leg. */
	private static final class LegCountingService extends DrugReferenceService {

		private final List<String> resolutions = new ArrayList<String>();

		@Override
		List<DrugReference> findByBridgedConcept(String conceptUuid,
				Map<Object, Set<Object>> impliedByName) {
			resolutions.add(String.valueOf(conceptUuid));
			return super.findByBridgedConcept(conceptUuid, impliedByName);
		}
	}

	private static LegCountingService service() throws Exception {
		LegCountingService counting = new LegCountingService();
		counting.setEntries(DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_COMBINATION_ALLERGEN));
		return counting;
	}

	/** The ticket's own order, plus a second one written against the same concept. Two rather than one
	 *  so that the expected count is not also the count a leg resolving once for the whole pass would
	 *  produce — this leg resolves per ORDER, and the two share one resolution cache. */
	private static PatientClinicalContext chart() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			PatientClinicalContext.ActiveDrugOrder.named("order-a", "Cotrimoxazole 960mg",
				DrugReferenceTestSupport.set("Cotrimoxazole 960mg"), null, null,
				COTRIMOXAZOLE_CONCEPT),
			PatientClinicalContext.ActiveDrugOrder.named("order-b", "Cotrimoxazole 480mg",
				DrugReferenceTestSupport.set("Cotrimoxazole 480mg"), null, null,
				COTRIMOXAZOLE_CONCEPT));
		return DrugReferenceTestSupport.ctx(38, null,
			DrugReferenceTestSupport.set("Cotrimoxazole 960mg", "Cotrimoxazole 480mg"), null, null,
			null, orders);
	}

	/**
	 * A pass that reaches the leg resolves it once per ORDER and no more, however many rows and orders
	 * the arms compare — the count is the size of the chart's own order list, not of the comparison
	 * product, which for this arrangement is in the dozens.
	 */
	@Test
	public void thePassResolvesTheLegOncePerOrderAndNeverPerComparison() throws Exception {
		LegCountingService service = service();
		PatientClinicalContext raw = chart();
		List<DrugReference> orderEntries = service.findForActiveOrders(raw);
		int afterCandidateSet = service.resolutions.size();

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
			"Can I give this patient Lamivudine?", raw, null, orderEntries);

		assertFalse(warnings.isEmpty(),
			"precondition: the pass must reach the leg at all, or this counts nothing");
		assertEquals(2, service.resolutions.size() - afterCandidateSet,
			"one resolution per active order for the whole pass, was: " + service.resolutions);
	}

	/**
	 * And a pass no arm of which asks for it resolves it NOT AT ALL. The chart is the same; only the
	 * question changes, to one that puts no drug in play and reaches no interaction arm.
	 */
	@Test
	public void aPassThatReachesNoInteractionArmResolvesTheLegNotAtAll() throws Exception {
		LegCountingService service = service();
		PatientClinicalContext raw = chart();
		List<DrugReference> orderEntries = service.findForActiveOrders(raw);
		int afterCandidateSet = service.resolutions.size();
		assertTrue(afterCandidateSet > 0,
			"precondition: the candidate set resolves it, so a non-zero count below would be real");

		DrugReferenceTestSupport.validator(service).validate("",
			"What was her last blood pressure?", raw, null, orderEntries);

		assertEquals(0, service.resolutions.size() - afterCandidateSet,
			"a pass with no drug in play must not pay for a leg no chip in it can reach, was: "
					+ service.resolutions);
	}
}
