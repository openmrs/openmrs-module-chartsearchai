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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Whether the question asked if the patient has EVER taken one drug reaches the wire as
 * {@code asksWhetherSheHasTakenADrug} (ADR Decision 156), on the search response and on every SSE event carrying the
 * answer, so a client can draw the chips beside such an answer apart from it.
 */
public class ChartSearchAiAsksWhetherSheHasTakenADrugTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Has she ever taken ibuprofen?";

	private static final String COMPOSED = "This patient's chart records no Ibuprofen order, active or ended.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	private boolean history;

	@BeforeEach
	public void setUp() {
		history = true;
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ComposedAnswerStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(COMPOSED,
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, false, null, null, null, null, null, null,
				org.openmrs.module.chartsearchai.reference.DrugSafetyValidator.STATUS_UNAVAILABLE,
				Collections.emptyList(), null, history);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	@Test
	public void theSearchResponseStatesBothValues() {
		assertEquals(Boolean.TRUE, searchPayload().get("asksWhetherSheHasTakenADrug"));
		history = false;
		Map<String, Object> other = searchPayload();
		assertTrue(other.containsKey("asksWhetherSheHasTakenADrug"),
				"the key is present on every answer, so a client reads one field unconditionally");
		assertEquals(Boolean.FALSE, other.get("asksWhetherSheHasTakenADrug"));
	}

	@Test
	public void theEarlyDoneAndTheGroundedEventStateItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertTrue(done.get("asksWhetherSheHasTakenADrug").asBoolean(),
				"the early done is what a streaming user sees");
		assertTrue(SseEvents.dataOfType(out, "grounded", MAPPER).get("asksWhetherSheHasTakenADrug").asBoolean());
	}

	@Test
	public void theClassicDoneEventStatesIt() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		assertTrue(SseEvents.dataOfType(out, "done", MAPPER).get("asksWhetherSheHasTakenADrug").asBoolean());
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a history question's answer");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		int keys = ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"asksWhetherSheHasTakenADrug\"");
		assertEquals(1, keys, "asksWhetherSheHasTakenADrug must be written in exactly one place. Found " + keys + " writes of it.");
	}

	private class ComposedAnswerStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(COMPOSED);
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
