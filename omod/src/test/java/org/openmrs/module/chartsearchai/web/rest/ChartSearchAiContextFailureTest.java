/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.IncompleteChartException;
import org.openmrs.module.chartsearchai.api.InsufficientContextException;
import org.springframework.http.ResponseEntity;

/** Context failures give the same actionable explanation on blocking and streaming requests. */
public class ChartSearchAiContextFailureTest {

	private final RestControllerContext context = new RestControllerContext();

	private ChartSearchAiRestController controller;

	@BeforeEach
	public void setUp() {
		context.install();
		controller = new ChartSearchAiRestController();
		controller.setPatientAccessCheck((user, patient) -> true);
		controller.setAuditLogService(new CapturingAuditLogService());
	}

	@AfterEach
	public void tearDown() {
		context.restore();
	}

	@Test
	public void requiredEvidenceOverflowIsAnExplicitContextLimit() {
		assertFailure(new InsufficientContextException("internal patient details", Collections.emptyList()),
				422, "increase the LLM context size", "try again");
	}

	@Test
	public void incompleteChartReadDoesNotRecommendALargerModelContext() {
		assertFailure(new IncompleteChartException("internal indexing details"),
				503, "chart could not be read completely", "increase the LLM context size");
	}

	@Test
	public void engineContextOverflowKeepsItsExistingRemedy() {
		assertFailure(new ChartTooLargeException("internal engine details"),
				413, "increase the LLM context size", "try again");
	}

	private void assertFailure(RuntimeException failure, int expectedStatus, String remedy, String wrongRemedy) {
		controller.setChartSearchService(new StreamingChartSearchStub() {
			@Override
			public ChartAnswer search(Patient patient, String question) {
				throw failure;
			}

			@Override
			public ChartAnswer searchStreaming(Patient patient, String question,
					Consumer<String> tokens, Consumer<String> reasoning,
					Consumer<List<RecordReference>> references, Consumer<ChartAnswer> answer) {
				throw failure;
			}
		});
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody("What medications?"));
		assertEquals(expectedStatus, response.getStatusCodeValue());
		String message = (String) ((Map<?, ?>) response.getBody()).get("error");
		assertTrue(message.contains(remedy));
		assertFalse(message.contains(wrongRemedy));
		assertFalse(message.contains("internal"));
		for (boolean async : new boolean[] {false, true}) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			controller.streamAnswer(out, RestControllerContext.patient(), "What medications?",
					RestControllerContext.user(), async);
			assertEquals(Collections.singletonList("error"), SseEvents.types(out));
			assertTrue(out.toString().contains(remedy));
			assertFalse(out.toString().contains(wrongRemedy));
			assertFalse(out.toString().contains("internal"));
		}
	}
}
