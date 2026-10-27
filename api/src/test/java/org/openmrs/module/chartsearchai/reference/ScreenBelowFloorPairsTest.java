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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen of her orders states the pairs of them the data relates only below the severity floor, and its answer does
 * not say no interactions were found where the data lists some (ADR Decision 467). On the public demo patient
 * dd74dc61, on Lamivudine, Nevirapine and Stavudine, "Does he have any drug contraindications?" was answered "No
 * interactions were found among this patient's active medications." with {@code interactionPairs.belowFloor} null,
 * while the shipped DDInter knowledge base relates all three pairs, each rated Unknown with no mechanism on file. The
 * same answer said nothing of his conditions, which the shipped data carries no rule to check.
 */
public class ScreenBelowFloorPairsTest extends BaseModuleContextSensitiveTest {

	private static final String CONTRAINDICATIONS = "Does he have any drug contraindications?";

	private static final String INTERACTIONS = "Does he have any drug interactions?";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	/** The shipped dataset as production loads it, so its load status is the demo's: no drug-condition rule. */
	private static DrugReferenceService loaded() {
		DrugReferenceService service = new DrugReferenceService();
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
			service.getLoadStatus().coverageOf(DrugReferenceLoad.Arm.CONDITION_RULES),
			"premise: the shipped dataset loaded, and publishes no drug-condition rule");
		return service;
	}

	@Test
	public void aScreenOfHisOrdersStatesEachPairItRelatedBelowTheFloorOnce() {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		DrugReferenceTestSupport.validator(loaded()).validate("",
			CONTRAINDICATIONS, DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null, null, null),
			null, null, sink);
		PairChipExtent extent = sink.stated();

		assertNotNull(extent, "precondition: the screen stated its extent");
		assertEquals(0, extent.getFound(), "precondition: nothing clears the floor: " + extent);
		assertNotNull(extent.getBelowFloor(), "the screen measured the pairs below the floor: " + extent);
		Set<Set<String>> pairs = new HashSet<Set<String>>();
		for (PairChipExtent.BelowFloorPair pair : extent.getBelowFloor()) {
			assertEquals("Unknown", pair.getSeverity(), "was: " + extent);
			assertEquals(Boolean.FALSE, pair.mechanismOnFile(), "was: " + extent);
			pairs.add(new HashSet<String>(Arrays.asList(pair.getDrug().toLowerCase(), pair.getPartner().toLowerCase())));
		}
		assertEquals(3, extent.getBelowFloor().size(), "each unordered pair once: " + extent);
		assertEquals(new HashSet<Set<String>>(Arrays.asList(set("lamivudine", "nevirapine"), set("lamivudine", "stavudine"),
			set("nevirapine", "stavudine"))), pairs);
	}

	@Test
	public void theAnswerSaysTheDataListsHisPairsBelowTheFloorRatherThanThatNoneWereFound() {
		String answer = screen(CONTRAINDICATIONS, "Lamivudine", "Nevirapine", "Stavudine");

		assertNotNull(answer, "the module answers");
		assertFalse(answer.contains("No interactions were found"), "the data relates three pairs of his: " + answer);
		assertTrue(answer.startsWith("No interactions at or above the configured severity level were found among this "
				+ "patient's active medications. 3 substances in them were checked against each other. The reference data "
				+ "lists 3 pairs of them only below that level, none with a severity or mechanism on file."),
			"was: " + answer);
	}

	/** The demo's own chart: each drug ordered three times, so each raises a duplicate-therapy finding beside the screen. */
	@Test
	public void theDemoPatientsThreeOrdersOfEachDrugAreAnsweredWithTheScreenTheDuplicatesAndHisConditions() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int n = 1; n <= 3; n++) {
			for (String drug : new String[] { "Lamivudine", "Nevirapine", "Stavudine" }) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug + "-" + n, drug, drug));
			}
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(loaded()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null,
				DrugReferenceTestSupport.set("Lamivudine", "Nevirapine", "Stavudine"), null, null, null, orders),
			CONTRAINDICATIONS).getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(5, lines.length, "was: " + answer);
		assertTrue(lines[0].startsWith("No interactions at or above the configured severity level were found among this "
				+ "patient's active medications. 3 substances in them were checked against each other. The reference data "
				+ "lists 3 pairs of them only below that level, none with a severity or mechanism on file."),
			"was: " + answer);
		assertTrue(lines[1].startsWith("Lamivudine is in active orders Lamivudine (3 orders) — possible duplicate therapy."),
			"was: " + answer);
		assertTrue(lines[2].startsWith("Nevirapine is in active orders Nevirapine (3 orders) — possible duplicate therapy."),
			"was: " + answer);
		assertTrue(lines[3].startsWith("Stavudine is in active orders Stavudine (3 orders) — possible duplicate therapy."),
			"was: " + answer);
		assertEquals(DrugReferenceInjector.CONDITIONS_NOT_CHECKED, lines[4], "was: " + answer);
	}

	@Test
	public void aScreenTheDataRelatesNoPairOfAtAnyRatingStillSaysNoInteractionsWereFound() {
		String answer = screen(INTERACTIONS, "Lamivudine", "Tenofovir disoproxil");

		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("No interactions were found among this patient's active medications. 2 substances in "
				+ "them were checked against each other, and the reference data relates none of them at or above the "
				+ "configured severity level."), "was: " + answer);
	}

	@Test
	public void aScreenBeyondInteractionsSaysHisConditionsWereNotChecked() {
		String answer = screen(CONTRAINDICATIONS, "Lamivudine", "Nevirapine", "Stavudine");

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(DrugReferenceInjector.CONDITIONS_NOT_CHECKED, lines[lines.length - 1], "was: " + answer);
		assertEquals("This patient's conditions were not checked against these medications: the drug data loaded carries "
				+ "no drug-condition rules.", DrugReferenceInjector.CONDITIONS_NOT_CHECKED);
	}

	@Test
	public void aScreenAskingOnlyAboutInteractionsDoesNotSpeakOfHisConditions() {
		String answer = screen(INTERACTIONS, "Lamivudine", "Nevirapine", "Stavudine");

		assertNotNull(answer, "the module answers");
		assertFalse(answer.contains(DrugReferenceInjector.CONDITIONS_NOT_CHECKED), "was: " + answer);
	}

	private static Set<String> set(String... members) {
		return new HashSet<String>(Arrays.asList(members));
	}

	private static String screen(String question, String... drugs) {
		Set<String> names = new LinkedHashSet<String>();
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (String drug : drugs) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-" + drug, drug, drug));
			names.add(drug);
		}
		return DrugReferenceTestSupport.injectorWithSafety(loaded())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(70, null, names, null, null, null, orders), question)
				.getModuleAnswer();
	}
}
