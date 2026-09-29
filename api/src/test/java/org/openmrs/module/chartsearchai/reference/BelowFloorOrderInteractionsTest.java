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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The drug-in-play arm states the pairs it related between a drug the question put in play and one of
 * her own orders whose rules the severity floor kept out of the chips — ADR Decision 127.
 *
 * <p><b>What was reproduced.</b> Live on the 3.7.1 standalone over the bundled knowledge base
 * (2026-09-29, {@code main} @ {@code 945b89e0}), "Is it safe to start her on clarithromycin?" for a
 * patient on Lidocaine, Metoclopramide, Neomycin and Tiotropium came back with no chip and
 * {@code interactionPairs: {found: 0, reported: 0}}, while its answer listed all four as clarithromycin
 * interactions without saying they were hers. DDInter rates all four {@code Unknown}, below the shipped
 * {@code minor} floor, so the screen related them and said so nowhere.
 *
 * <p>Every case runs the real {@code validate} with the sink {@code LlmInferenceService} hands it, over
 * a real {@link DdiDrugReferenceSource} load, GP reads on their no-context defaults.
 */
public class BelowFloorOrderInteractionsTest {

	private static List<String> pairs(PairChipExtent extent) {
		assertNotNull(extent.getBelowFloor(), "the drug-in-play arm stated the extent, so it states these too");
		List<String> out = new ArrayList<String>();
		for (PairChipExtent.BelowFloorPair pair : extent.getBelowFloor()) {
			out.add(pair.getDrug() + " x " + pair.getPartner() + " (" + pair.getSeverity() + ")");
		}
		return out;
	}

	private static PairChipExtent stated(DrugReferenceService service, String question, String... orders) {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		DrugReferenceTestSupport.validator(service).validate("", question,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(orders), null, null, null),
				null, null, sink);
		assertNotNull(sink.stated(), "precondition: the screen stated its extent");
		return sink.stated();
	}

	@Test
	public void theReportedQuestionStatesHerFourUnratedPairs() {
		PairChipExtent extent = stated(DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.shippedEntries()),
				"Is it safe to start her on clarithromycin?", "Lidocaine", "Metoclopramide", "Neomycin", "Tiotropium");

		assertEquals(0, extent.getFound(), "precondition: nothing clears the floor: " + extent);
		assertEquals(List.of("Clarithromycin x lidocaine (Unknown)", "Clarithromycin x metoclopramide (Unknown)",
				"Clarithromycin x neomycin (Unknown)", "Clarithromycin x tiotropium (Unknown)"), pairs(extent));
	}

	@Test
	public void aPartnerWhoseRuleClearsTheFloorIsAChipAndNotBelowIt() {
		// The excerpt rates Metformin x Warfarin Moderate (a chip) and Metformin x Fluconazole Unknown.
		PairChipExtent extent = stated(DrugReferenceTestSupport.ddinterService(), "is it safe to give metformin?",
				"Warfarin", "Fluconazole");

		assertEquals(1, extent.getFound(), "precondition: the Moderate pair is the one pair found: " + extent);
		assertEquals(List.of("Metformin x fluconazole (Unknown)"), pairs(extent));
	}

	@Test
	public void aPartnerAnAboveFloorRuleChipsIsNotAlsoStatedBelowIt() throws Exception {
		// Warfarin is filed twice, Moderate and Unknown: the Moderate row chips it, so the Unknown row
		// must not also publish it as a pair the floor kept out, which would contradict the chip beside it.
		PairChipExtent extent = stated(DrugReferenceTestSupport.curatedFixtureService(
				"chartsearchai-test/drug-reference-partner-rated-and-unrated-rows.json"),
				"is it safe to give voriconazole?", "Warfarin", "Fluconazole");

		assertEquals(1, extent.getFound(), "precondition: the Moderate warfarin row is the pair found: " + extent);
		assertEquals(List.of("Voriconazole x fluconazole (Unknown)"), pairs(extent));
	}

	@Test
	public void aScreenRelatingNoSubFloorPairOfHersStatesNoneRatherThanNothing() {
		assertEquals(List.of(), pairs(stated(DrugReferenceTestSupport.ddinterService(),
				"is it safe to give metformin?", "Warfarin")));
	}

	@Test
	public void anExtentTheDrugInPlayArmDidNotStateCarriesNoSuchStatement() {
		// Two drugs in the question: the question-pair arm owns the field (issue #356), and it is not
		// the arm that measures this.
		PairChipExtent extent = stated(DrugReferenceTestSupport.ddinterService(),
				"can I give warfarin and ibuprofen together?", "Fluconazole");

		assertNull(extent.getBelowFloor(), "null is no measurement, never an empty one: " + extent);
	}
}
