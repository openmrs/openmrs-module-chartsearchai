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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The same clinical problem is routinely recorded twice in OpenMRS — once in {@code conditions}
 * and once as an {@code encounter_diagnosis} — and both rows reach the prompt as separate
 * numbered records. The model names the finding once and cites ONE of them, so the clinician
 * gets a reference chip for the condition row and none for the identical diagnosis row (measured
 * on the 3.7.1 demo set: "Yes — the patient has eye problems: Hordeolum … [2]" left the
 * confirmed Hordeolum diagnosis unlinked; 8% of all missed on-topic records across a 117-cell
 * capture were exactly this).
 *
 * <p>Which of the two rows the model happened to pick is arbitrary, so the fix is mechanical, not
 * a prompt plea: when a cited record is a coded problem, every OTHER retrieved record asserting
 * the SAME concept in the OTHER problem table is the same assertion and is surfaced alongside it.
 * Drift-neutral by construction — a twin cannot be off-topic if the record it duplicates is on
 * topic — and it costs no prompt tokens, because it happens after the answer is generated.
 */
public class LlmInferenceServiceTwinCitationTest {

	private static final String HORDEOLUM = "concept-hordeolum";

	private static final String CONJUNCTIVITIS = "concept-conjunctivitis";

	private static RecordMapping mapping(int index, String type, String conceptUuid, String text) {
		return new RecordMapping(index, type, "res-" + index, new Date(1_700_000_000_000L + index),
				text, conceptUuid);
	}

	private static List<Integer> indices(List<RecordReference> refs) {
		List<Integer> out = new ArrayList<Integer>();
		for (RecordReference r : refs) {
			out.add(r.getIndex());
		}
		Collections.sort(out);
		return out;
	}

	@Test
	public void citingTheConditionShouldAlsoSurfaceTheIdenticalDiagnosis() {
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", HORDEOLUM, "Condition: Hordeolum. Status: ACTIVE"),
				mapping(2, "diagnosis", HORDEOLUM, "Diagnosis: Hordeolum. Certainty: PROVISIONAL"),
				mapping(3, "condition", CONJUNCTIVITIS, "Condition: Conjunctivitis. Status: ACTIVE"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Yes — Hordeolum is recorded [1].", Arrays.asList(1), mappings);

		assertEquals(Arrays.asList(1, 2), indices(refs),
				"the identical diagnosis row must be linked alongside the cited condition row");
	}

	@Test
	public void twinExpansionShouldBeSymmetric() {
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", HORDEOLUM, "Condition: Hordeolum. Status: ACTIVE"),
				mapping(2, "diagnosis", HORDEOLUM, "Diagnosis: Hordeolum. Certainty: PROVISIONAL"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Yes — Hordeolum is recorded [2].", Arrays.asList(2), mappings);

		assertEquals(Arrays.asList(1, 2), indices(refs),
				"citing the diagnosis row must surface the condition row too");
	}

	@Test
	public void repeatedMeasuresOfOneConceptMustNotBeCoCited() {
		// Ten blood-pressure readings share a concept but are ten DISTINCT clinical events with
		// distinct values. Only a cross-table duplicate of the same assertion is co-cited, so the
		// same-type case must stay untouched — otherwise citing today's BP would drag in a year
		// of readings the answer never mentions.
		String bp = "concept-systolic";
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "obs", bp, "Systolic blood pressure: 152"),
				mapping(2, "obs", bp, "Systolic blood pressure: 118"),
				mapping(3, "obs", bp, "Systolic blood pressure: 131"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"The latest systolic is 152 [1].", Arrays.asList(1), mappings);

		assertEquals(Arrays.asList(1), indices(refs),
				"same-type records of one concept are separate events, not duplicates");
	}

	@Test
	public void unrelatedTypesSharingAConceptMustNotBeCoCited() {
		// The expansion is scoped to the two problem-list tables. A drug order and an obs that
		// happened to carry the same concept are not the same assertion.
		String shared = "concept-shared";
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", shared, "Condition: Malaria. Status: ACTIVE"),
				mapping(2, "obs", shared, "Malaria smear: POSITIVE"),
				mapping(3, "drug_order", shared, "Drug order: Malaria treatment"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Malaria is recorded [1].", Arrays.asList(1), mappings);

		assertEquals(Arrays.asList(1), indices(refs),
				"only condition<->diagnosis pairs are the same assertion recorded twice");
	}

	@Test
	public void recordsWithoutAConceptMustNeverBeCoCited() {
		// Non-coded conditions carry no concept_uuid. Treating "no concept" as a matchable key
		// would co-cite every non-coded problem with every other one.
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", null, "Condition: something free-text. Status: ACTIVE"),
				mapping(2, "diagnosis", null, "Diagnosis: something else. Certainty: CONFIRMED"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Recorded [1].", Arrays.asList(1), mappings);

		assertEquals(Arrays.asList(1), indices(refs), "a null concept is not a join key");
	}

	@Test
	public void anAbstentionThatAnchorsNothingStillSurfacesNoReferences() {
		// The #76 unanchored-array guard must still win: twin expansion runs on an ANCHORED
		// citation set, never on the model's unanchored review dump.
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", HORDEOLUM, "Condition: Hordeolum. Status: ACTIVE"),
				mapping(2, "diagnosis", HORDEOLUM, "Diagnosis: Hordeolum. Certainty: PROVISIONAL"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"No eye problems are recorded.", Arrays.asList(1, 2), mappings);

		assertTrue(refs.isEmpty(),
				"prose that anchors no inline citation must still surface nothing");
	}

	@Test
	public void twinsShouldNotBeDuplicatedWhenBothWereAlreadyCited() {
		List<RecordMapping> mappings = Arrays.asList(
				mapping(1, "condition", HORDEOLUM, "Condition: Hordeolum. Status: ACTIVE"),
				mapping(2, "diagnosis", HORDEOLUM, "Diagnosis: Hordeolum. Certainty: PROVISIONAL"));

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Hordeolum [1], [2].", Arrays.asList(1, 2), mappings);

		assertEquals(Arrays.asList(1, 2), indices(refs));
		assertEquals(2, refs.size(), "no duplicate reference entries");
	}
}
