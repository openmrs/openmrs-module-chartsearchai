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
 * Whether any model wrote the answer reaches the wire as {@code answeredByTheModule} — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/469">#469</a>. What it
 * means is canonical at {@code ChartSearchService.ChartAnswer.isAnsweredByTheModule()}, and that the
 * orchestration sets it is pinned one layer down by {@code LlmInferenceServiceAnswerFromFindingsContextTest}.
 * Here the subject is the wire: every surface, both values, and one write.
 */
public class ChartSearchAiAnsweredByTheModuleTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Can I give her ibuprofen?";

	private static final String COMPOSED = "No — this module's drug-safety check found a reason to withhold "
			+ "Ibuprofen.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	private boolean byTheModule;

	@BeforeEach
	public void setUp() {
		byTheModule = true;
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
				null, null, null, null, null, null, byTheModule);
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
		assertEquals(Boolean.TRUE, searchPayload().get("answeredByTheModule"));
		byTheModule = false;
		Map<String, Object> modelWritten = searchPayload();
		assertTrue(modelWritten.containsKey("answeredByTheModule"),
				"the key is present on every answer, so a client reads one field unconditionally");
		assertEquals(Boolean.FALSE, modelWritten.get("answeredByTheModule"));
	}

	@Test
	public void theEarlyDoneAndTheGroundedEventStateItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertTrue(done.get("answeredByTheModule").asBoolean(),
				"the early done is what a streaming user sees, and the flag is known as soon as the chart is built");
		assertTrue(SseEvents.dataOfType(out, "grounded", MAPPER).get("answeredByTheModule").asBoolean());
	}

	@Test
	public void theClassicDoneEventStatesIt() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		assertTrue(SseEvents.dataOfType(out, "done", MAPPER).get("answeredByTheModule").asBoolean());
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a composed answer");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		int keys = ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"answeredByTheModule\"");
		assertEquals(1, keys, "answeredByTheModule must be written in exactly one place, beside the keys "
				+ "it explains. Found " + keys + " writes of it.");
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
