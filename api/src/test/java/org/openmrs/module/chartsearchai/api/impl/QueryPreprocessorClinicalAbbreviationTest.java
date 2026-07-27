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
 * Pure unit tests for {@link QueryPreprocessor#expandClinicalAbbreviations} — the composed
 * retrieval-text normalizer both chart-build paths call.
 *
 * <p>Clinicians write conditions the way they say them ("any HTN?", "is this CKD?"), but
 * querystore indexes the concept's full name ("Hypertension", "Chronic kidney disease, stage
 * IIIA"). A bi-encoder embeds the bare initialism far from the record, so the similarity slice
 * misses records the patient demonstrably has — the same failure the lab-panel expansion already
 * fixes for "BMP". Expansion is <em>additive</em> (the original token is kept), so a false
 * positive can only add a couple of words to the retrieval text; it can never remove the
 * clinician's own wording.
 */
public class QueryPreprocessorClinicalAbbreviationTest {

	@Test
	public void expand_shouldAppendFullConditionNameAfterInitialism() {
		String expanded = QueryPreprocessor.expandClinicalAbbreviations("Any HTN in the chart?");
		assertTrue(expanded.toLowerCase().contains("hypertension"),
				"HTN must expand to hypertension: " + expanded);
		assertTrue(expanded.contains("HTN"), "the clinician's own token must be preserved: " + expanded);
	}

	@Test
	public void expand_shouldCoverTheCommonChronicDiseaseInitialisms() {
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("does she have CKD").toLowerCase()
				.contains("chronic kidney disease"));
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("any COPD?").toLowerCase()
				.contains("chronic obstructive pulmonary disease"));
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("history of CVA").toLowerCase()
				.contains("stroke"));
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("any UTI recently?").toLowerCase()
				.contains("urinary tract infection"));
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("latest bp").toLowerCase()
				.contains("blood pressure"));
	}

	@Test
	public void expand_shouldAlsoApplyTheLabPanelExpansions() {
		// One composed entry point: the scoped builder must not have to remember to call two
		// normalizers, and a future caller cannot get only half the vocabulary.
		String expanded = QueryPreprocessor.expandClinicalAbbreviations("results of the last BMP");
		assertTrue(expanded.toLowerCase().contains("basic metabolic panel"),
				"the composed expander must include the lab-panel vocabulary: " + expanded);
	}

	@Test
	public void expand_shouldMatchWordLikeInitialismsOnlyInCapitals() {
		// "MI"/"TB"/"SOB" in capitals are unambiguous clinical initialisms; the same letters in
		// lowercase are ordinary words or units ("5 mi", "2 tb", "began to sob") and must not
		// drag an unrelated diagnosis into the retrieval text.
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("previous MI?").toLowerCase()
				.contains("myocardial infarction"));
		assertFalse(QueryPreprocessor.expandClinicalAbbreviations("walked 5 mi today").toLowerCase()
				.contains("myocardial infarction"),
				"a lowercase word-like token is not a clinical initialism");
		assertFalse(QueryPreprocessor.expandClinicalAbbreviations("the child began to sob").toLowerCase()
				.contains("shortness of breath"),
				"'sob' the verb must not expand");
		assertTrue(QueryPreprocessor.expandClinicalAbbreviations("any TB?").toLowerCase()
				.contains("tuberculosis"));
	}

	@Test
	public void expand_shouldNotTouchInitialismsInsideWords() {
		assertEquals("the subcadence module",
				QueryPreprocessor.expandClinicalAbbreviations("the subcadence module"),
				"word-boundary only — 'CAD' inside another word must not expand");
		assertEquals("photon emission",
				QueryPreprocessor.expandClinicalAbbreviations("photon emission"),
				"'PE' inside another word must not expand");
	}

	@Test
	public void expand_shouldPassThroughNullBlankAndCueFreeQuestions() {
		assertEquals(null, QueryPreprocessor.expandClinicalAbbreviations(null));
		assertEquals("   ", QueryPreprocessor.expandClinicalAbbreviations("   "));
		assertEquals("Does the patient have any eye problems?",
				QueryPreprocessor.expandClinicalAbbreviations("Does the patient have any eye problems?"));
	}

	@Test
	public void expand_shouldLeaveTheDriftMetricGoldQuestionsByteIdentical() {
		// The nine scope questions the answer-quality gold is captured with carry no initialism.
		// Pinning that here makes the expansion provably gold-NEUTRAL: any gold movement measured
		// alongside this change comes from something else.
		String[] goldQuestions = {
				"Is the patient enrolled in any programs?",
				"Does the patient have any allergies?",
				"What medications is the patient taking?",
				"Does the patient have any eye problems?",
				"Does the patient have any heart or cardiac problems?",
				"Has the patient had any fractures or broken bones?",
				"Does the patient have any kidney problems?",
				"Does the patient have any mental health or psychiatric conditions?",
				"Does the patient have any drug allergies?" };
		for (String question : goldQuestions) {
			assertEquals(question, QueryPreprocessor.expandClinicalAbbreviations(question),
					"gold question must be untouched by expansion: " + question);
		}
	}
}
