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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;

public class LlmInferenceServiceTest {

	@Test
	public void filterCitedReferences_shouldReturnOnlyCitedReferences() {
		List<RecordReference> all = Arrays.asList(
				new RecordReference(1, "obs", 101),
				new RecordReference(2, "obs", 102),
				new RecordReference(3, "order", 201),
				new RecordReference(4, "condition", 301));

		List<RecordReference> result = LlmInferenceService.filterCitedReferences(
				"The patient is on Metformin [1] and has hypertension [3].", all);

		assertEquals(2, result.size());
		assertEquals(1, result.get(0).getIndex());
		assertEquals(3, result.get(1).getIndex());
	}

	@Test
	public void filterCitedReferences_shouldReturnEmptyWhenNoCitations() {
		List<RecordReference> all = Arrays.asList(
				new RecordReference(1, "obs", 101),
				new RecordReference(2, "obs", 102));

		List<RecordReference> result = LlmInferenceService.filterCitedReferences(
				"I could not find relevant information in the records.", all);

		assertTrue(result.isEmpty());
	}

	@Test
	public void filterCitedReferences_shouldHandleMultipleCitationsOfSameIndex() {
		List<RecordReference> all = Arrays.asList(
				new RecordReference(1, "obs", 101),
				new RecordReference(2, "obs", 102));

		List<RecordReference> result = LlmInferenceService.filterCitedReferences(
				"Blood pressure was 120/80 [1]. This is within normal range [1].", all);

		assertEquals(1, result.size());
		assertEquals(1, result.get(0).getIndex());
	}

	@Test
	public void filterCitedReferences_shouldIgnoreCitationsWithNoMatchingReference() {
		List<RecordReference> all = Arrays.asList(
				new RecordReference(1, "obs", 101),
				new RecordReference(2, "obs", 102));

		List<RecordReference> result = LlmInferenceService.filterCitedReferences(
				"The patient takes Metformin [1] and Lisinopril [5].", all);

		assertEquals(1, result.size());
		assertEquals(1, result.get(0).getIndex());
	}

	@Test
	public void filterCitedReferences_shouldHandleEmptyReferenceList() {
		List<RecordReference> result = LlmInferenceService.filterCitedReferences(
				"Some answer [1] [2].", Collections.<RecordReference>emptyList());

		assertTrue(result.isEmpty());
	}

	@Test
	public void filterCitedReferences_shouldHandleEmptyAnswer() {
		List<RecordReference> all = Arrays.asList(
				new RecordReference(1, "obs", 101));

		List<RecordReference> result = LlmInferenceService.filterCitedReferences("", all);

		assertTrue(result.isEmpty());
	}
}
