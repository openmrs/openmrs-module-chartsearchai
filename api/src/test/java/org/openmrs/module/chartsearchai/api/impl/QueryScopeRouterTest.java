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

import java.util.Collections;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter.Intent;

/**
 * Pure unit tests for {@link QueryScopeRouter}: the question → typed-record-scope mapping
 * that decides which record types are included <em>complete</em> in a query-scoped slice
 * chart. Routing must be conservative — only unambiguous intent keywords match; everything
 * else matches nothing (TOPICAL, similarity-only), because a wrong typed scope biases the
 * slice while similarity still provides the semantic catch-all. Multi-cue questions carry
 * EVERY matched intent (the builder unions their typed slices): first-match routing silently
 * dropped the runner-up's completeness on exactly the type being enumerated ("any drug
 * allergies?" routed MEDICATIONS-only, leaving the allergy list to similarity luck).
 */
public class QueryScopeRouterTest {

	@Test
	public void matchedIntents_shouldDetectMedicationsIntent() {
		assertEquals(EnumSet.of(Intent.MEDICATIONS),
				QueryScopeRouter.matchedIntents("What medications is the patient taking?"));
		assertEquals(EnumSet.of(Intent.MEDICATIONS),
				QueryScopeRouter.matchedIntents("current meds?"));
		assertEquals(EnumSet.of(Intent.MEDICATIONS),
				QueryScopeRouter.matchedIntents("Is she on any drugs or prescriptions?"));
	}

	@Test
	public void matchedIntents_shouldDetectAllergiesIntent() {
		assertEquals(EnumSet.of(Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("Does the patient have any allergies?"));
		assertEquals(EnumSet.of(Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("is he allergic to penicillin"));
	}

	@Test
	public void matchedIntents_shouldReturnEveryMatchedIntent_forDrugAllergyQuestions() {
		// The collision class: a medications cue AND an allergies cue in one question. First-match
		// routing returned MEDICATIONS alone, so the allergy list's completeness hung on the
		// similarity top-K — a silently incomplete allergy enumeration if the embedding missed one.
		// Both intents must come back so the builder unions the typed slices.
		assertEquals(EnumSet.of(Intent.MEDICATIONS, Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("any drug allergies?"));
		assertEquals(EnumSet.of(Intent.MEDICATIONS, Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("does the patient have any medication allergies?"));
		assertEquals(EnumSet.of(Intent.MEDICATIONS, Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("is she allergic to any medications?"));
	}

	@Test
	public void matchedIntents_shouldTreatAdverseReactionVocabulary_asAllergiesCues() {
		// "adverse", "reaction(s)" and "intolerance" are the allergy table's own vocabulary
		// (records read "Allergy: X. Reaction: rash") — without them, "any adverse drug
		// reactions?" enumerated the allergy domain on similarity alone.
		assertEquals(EnumSet.of(Intent.MEDICATIONS, Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("any adverse drug reactions?"));
		assertEquals(EnumSet.of(Intent.ALLERGIES),
				QueryScopeRouter.matchedIntents("any known intolerances?"));
	}

	@Test
	public void matchedIntents_shouldDetectProgramsIntent() {
		assertEquals(EnumSet.of(Intent.PROGRAMS),
				QueryScopeRouter.matchedIntents("Is the patient enrolled in any programs?"));
	}

	@Test
	public void matchedIntents_shouldDetectConditionsIntent() {
		assertEquals(EnumSet.of(Intent.CONDITIONS),
				QueryScopeRouter.matchedIntents("What active conditions does the patient have?"));
		assertEquals(EnumSet.of(Intent.CONDITIONS),
				QueryScopeRouter.matchedIntents("what are the patient's diagnoses?"));
	}

	@Test
	public void matchedIntents_shouldDetectVisitsIntent() {
		assertEquals(EnumSet.of(Intent.VISITS),
				QueryScopeRouter.matchedIntents("When was the patient's last visit?"));
		assertEquals(EnumSet.of(Intent.VISITS),
				QueryScopeRouter.matchedIntents("any upcoming appointments?"));
	}

	@Test
	public void matchedIntents_shouldMatchNothing_forClinicalTopicQuestions() {
		// "problems" alone is NOT a conditions cue — "eye problems" is a topical question and
		// must stay similarity-driven, not be routed to the condition/diagnosis tables. An empty
		// set is the TOPICAL case: the slice is the similarity top-K alone.
		assertEquals(Collections.emptySet(),
				QueryScopeRouter.matchedIntents("Does the patient have any eye problems?"));
		assertEquals(Collections.emptySet(),
				QueryScopeRouter.matchedIntents("Has the patient had any fractures or broken bones?"));
		assertEquals(Collections.emptySet(),
				QueryScopeRouter.matchedIntents("Is she pregnant?"));
	}

	@Test
	public void matchedIntents_shouldMatchNothing_forBlankOrNullQuestions() {
		assertEquals(Collections.emptySet(), QueryScopeRouter.matchedIntents(null));
		assertEquals(Collections.emptySet(), QueryScopeRouter.matchedIntents("   "));
	}

	@Test
	public void matchedIntents_shouldNotMatchIntentKeywordsInsideOtherWords() {
		// "programmer", "medicated dressing" style substrings must not trigger a typed scope.
		assertEquals(Collections.emptySet(),
				QueryScopeRouter.matchedIntents("does the note mention reprogramming the pacemaker device"));
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
	public void asksAboutMedications_isTheWideningSignalAndNotADrugDetector() {
		// Read by the drug-safety layer to decide that a question about what the patient is TAKING puts
		// her whole active-order list in the response's subject matter.
		assertTrue(QueryScopeRouter.asksAboutMedications("What are her current medications?"));
		assertTrue(QueryScopeRouter.asksAboutMedications("is she on any drugs?"));
		assertTrue(QueryScopeRouter.asksAboutMedications("what has she been prescribed?"));
		assertFalse(QueryScopeRouter.asksAboutMedications("Does she have cancer?"));
		assertFalse(QueryScopeRouter.asksAboutMedications("What is her date of birth?"));
		assertFalse(QueryScopeRouter.asksAboutMedications(null));
		assertFalse(QueryScopeRouter.asksAboutMedications("   "));

		// LOAD-BEARING, and the reason the safety layer must not be gated on this predicate alone: a
		// question naming a drug outright carries none of the medication cue words. Such a question is
		// answered by the drug-in-play arm, which the subject-matter scoping never touches — but a
		// reader who mistook this for "is this a drug question" would gate that arm on it and silence it.
		assertFalse(QueryScopeRouter.asksAboutMedications("Can I give her bupivacaine?"));
	}

	@Test
	public void asksAboutAllergies_coversTheAllergyTablesOwnVocabulary() {
		assertTrue(QueryScopeRouter.asksAboutAllergies("any allergies?"));
		assertTrue(QueryScopeRouter.asksAboutAllergies("has she had any adverse reactions?"));
		assertTrue(QueryScopeRouter.asksAboutAllergies("any intolerance on record?"));
		assertFalse(QueryScopeRouter.asksAboutAllergies("Does she have cancer?"));
		assertFalse(QueryScopeRouter.asksAboutAllergies(null));
	}

	@Test
	public void matchedIntents_shouldDetectOrdersIntent() {
		assertEquals(EnumSet.of(Intent.ORDERS),
				QueryScopeRouter.matchedIntents("What things have been ordered for this patient over the past 6 months?"));
		assertEquals(EnumSet.of(Intent.ORDERS),
				QueryScopeRouter.matchedIntents("any outstanding orders?"));
		assertEquals(EnumSet.of(Intent.ORDERS),
				QueryScopeRouter.matchedIntents("show the patient's test orders"));
	}

	@Test
	public void matchedIntents_shouldReturnBothMedicationsAndOrders_whenBothCuesPresent() {
		// Supersedes the old first-match precedence (MEDICATIONS shadowed ORDERS): shadowing was
		// the same mechanism that broke drug-allergy questions, and the union costs only a few
		// extra order records while completeness holds for whichever reading the user meant.
		assertEquals(EnumSet.of(Intent.MEDICATIONS, Intent.ORDERS),
				QueryScopeRouter.matchedIntents("what prescriptions were ordered for her?"));
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

	@Test
	public void typedSlice_shouldUnionTheTypesOfEveryMatchedIntent() {
		assertEquals(new java.util.HashSet<String>(
						java.util.Arrays.asList("drug_order", "medication_dispense", "allergy")),
				QueryScopeRouter.typedSlice(EnumSet.of(Intent.MEDICATIONS, Intent.ALLERGIES)));
		assertTrue(QueryScopeRouter.typedSlice(EnumSet.noneOf(Intent.class)).isEmpty());
	}
}
