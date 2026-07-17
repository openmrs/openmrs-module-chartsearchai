/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link QueryScopeRouter}: the question → typed-record-scope mapping
 * that decides which record types are included <em>complete</em> in a query-scoped slice
 * chart. Routing must be conservative — only unambiguous intent keywords map to a typed
 * scope; everything else is TOPICAL (similarity-only), because a wrong typed scope biases
 * the slice while similarity still provides the semantic catch-all.
 */
public class QueryScopeRouterTest {

	@Test
	public void route_shouldDetectMedicationsIntent() {
		assertEquals(QueryScopeRouter.Intent.MEDICATIONS,
				QueryScopeRouter.route("What medications is the patient taking?"));
		assertEquals(QueryScopeRouter.Intent.MEDICATIONS,
				QueryScopeRouter.route("current meds?"));
		assertEquals(QueryScopeRouter.Intent.MEDICATIONS,
				QueryScopeRouter.route("Is she on any drugs or prescriptions?"));
	}

	@Test
	public void route_shouldDetectAllergiesIntent() {
		assertEquals(QueryScopeRouter.Intent.ALLERGIES,
				QueryScopeRouter.route("Does the patient have any allergies?"));
		assertEquals(QueryScopeRouter.Intent.ALLERGIES,
				QueryScopeRouter.route("is he allergic to penicillin"));
	}

	@Test
	public void route_shouldDetectProgramsIntent() {
		assertEquals(QueryScopeRouter.Intent.PROGRAMS,
				QueryScopeRouter.route("Is the patient enrolled in any programs?"));
	}

	@Test
	public void route_shouldDetectConditionsIntent() {
		assertEquals(QueryScopeRouter.Intent.CONDITIONS,
				QueryScopeRouter.route("What active conditions does the patient have?"));
		assertEquals(QueryScopeRouter.Intent.CONDITIONS,
				QueryScopeRouter.route("what are the patient's diagnoses?"));
	}

	@Test
	public void route_shouldDetectVisitsIntent() {
		assertEquals(QueryScopeRouter.Intent.VISITS,
				QueryScopeRouter.route("When was the patient's last visit?"));
		assertEquals(QueryScopeRouter.Intent.VISITS,
				QueryScopeRouter.route("any upcoming appointments?"));
	}

	@Test
	public void route_shouldFallBackToTopical_forClinicalTopicQuestions() {
		// "problems" alone is NOT a conditions cue — "eye problems" is a topical question and
		// must stay similarity-driven, not be routed to the condition/diagnosis tables.
		assertEquals(QueryScopeRouter.Intent.TOPICAL,
				QueryScopeRouter.route("Does the patient have any eye problems?"));
		assertEquals(QueryScopeRouter.Intent.TOPICAL,
				QueryScopeRouter.route("Has the patient had any fractures or broken bones?"));
		assertEquals(QueryScopeRouter.Intent.TOPICAL,
				QueryScopeRouter.route("Is she pregnant?"));
	}

	@Test
	public void route_shouldFallBackToTopical_forBlankOrNullQuestions() {
		assertEquals(QueryScopeRouter.Intent.TOPICAL, QueryScopeRouter.route(null));
		assertEquals(QueryScopeRouter.Intent.TOPICAL, QueryScopeRouter.route("   "));
	}

	@Test
	public void route_shouldNotMatchIntentKeywordsInsideOtherWords() {
		// "programmer", "medicated dressing" style substrings must not trigger a typed scope.
		assertEquals(QueryScopeRouter.Intent.TOPICAL,
				QueryScopeRouter.route("does the note mention reprogramming the pacemaker device"));
	}

	@Test
	public void isTemporal_shouldDetectRecencyCues() {
		assertTrue(QueryScopeRouter.isTemporal("What is the patient's most recent weight?"));
		assertTrue(QueryScopeRouter.isTemporal("what was the LAST serum creatinine"));
		assertTrue(QueryScopeRouter.isTemporal("latest blood pressure?"));
		assertTrue(QueryScopeRouter.isTemporal("When was the patient's last visit?"));
		assertTrue(QueryScopeRouter.isTemporal("what is her current weight"));
	}

	@Test
	public void isTemporal_shouldDetectVagueRecencyPhrases() {
		// "What's happened lately?" got no anchor and answered with billing noise (measured);
		// these phrasings all ask about the recent past and need the recency anchor.
		assertTrue(QueryScopeRouter.isTemporal("What's happened lately?"));
		assertTrue(QueryScopeRouter.isTemporal("any changes recently?"));
		assertTrue(QueryScopeRouter.isTemporal("How has the patient's weight changed over the past year?"));
		assertTrue(QueryScopeRouter.isTemporal("What has been ordered over the past 6 months?"));
		assertTrue(QueryScopeRouter.isTemporal("any lab results this month?"));
		assertTrue(QueryScopeRouter.isTemporal("what has changed since the previous consultation?"));
	}

	@Test
	public void route_shouldDetectOrdersIntent() {
		assertEquals(QueryScopeRouter.Intent.ORDERS,
				QueryScopeRouter.route("What things have been ordered for this patient over the past 6 months?"));
		assertEquals(QueryScopeRouter.Intent.ORDERS,
				QueryScopeRouter.route("any outstanding orders?"));
		assertEquals(QueryScopeRouter.Intent.ORDERS,
				QueryScopeRouter.route("show the patient's test orders"));
	}

	@Test
	public void route_shouldPreferMedicationsOverOrders_whenBothCuesPresent() {
		// "prescriptions ordered" is a medications question first — the med scope includes the
		// drug orders anyway, and medications carries the stronger completeness expectation.
		assertEquals(QueryScopeRouter.Intent.MEDICATIONS,
				QueryScopeRouter.route("what prescriptions were ordered for her?"));
	}

	@Test
	public void isTemporal_shouldStayFalse_forScopeQuestions() {
		// Scope questions must NOT get the recency anchor: anchored recent vitals in a small
		// slice bait the model into enumerating them as findings on absent-topic questions
		// (measured: an absent "heart" cell drifted to 39 vitals citations with the anchor on).
		assertFalse(QueryScopeRouter.isTemporal("Does the patient have any heart or cardiac problems?"));
		assertFalse(QueryScopeRouter.isTemporal("What medications is the patient taking?"));
		assertFalse(QueryScopeRouter.isTemporal("Is the patient enrolled in any programs?"));
		assertFalse(QueryScopeRouter.isTemporal(null));
	}

	@Test
	public void typedSlice_shouldMapIntentsToQuerystoreResourceTypes() {
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.MEDICATIONS)
				.containsAll(java.util.Arrays.asList("drug_order", "medication_dispense")));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.ALLERGIES).contains("allergy"));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.PROGRAMS).contains("program"));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.CONDITIONS)
				.containsAll(java.util.Arrays.asList("condition", "diagnosis")));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.VISITS)
				.containsAll(java.util.Arrays.asList("visit", "encounter")));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.ORDERS)
				.containsAll(java.util.Arrays.asList("drug_order", "test_order", "referral_order")));
		assertTrue(QueryScopeRouter.typedSlice(QueryScopeRouter.Intent.TOPICAL).isEmpty());
	}
}
