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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The lines a module-composed answer opens with, which a client shows with the rest collapsed under them, reach the wire
 * as {@code answerSummary} — ADR Decision 512. What it means is canonical at {@code
 * ChartSearchService.ChartAnswer.getAnswerSummary()}, and that the orchestration sets it is pinned one layer down by
 * {@code LlmInferenceServiceAnswerFromFindingsContextTest}. Here the subject is the wire: every surface, both values,
 * and one write.
 */
public class ChartSearchAiAnswerSummaryTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Does she have any drug interactions?";

	private static final String SUMMARY = "1 Major interaction — Warfarin with Aspirin.";

	private static final String COMPOSED = SUMMARY + "\nWarfarin interacts with active order Aspirin — Major. [3]";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	private String summary;

	private boolean limits;

	@BeforeEach
	public void setUp() {
		summary = SUMMARY;
		limits = true;
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
				null, null, null, null, null, null, true, null, null, null, null, null, null, null, false, summary, limits);
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
		assertEquals(SUMMARY, searchPayload().get("answerSummary"));
		summary = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("answerSummary"),
				"the key is present on every answer, so a client reads one field unconditionally");
		assertNull(none.get("answerSummary"));
	}

	/** ADR Decision 519: whether what is collapsed under it says what was not checked, on every answer, both values. */
	@Test
	public void theSearchResponseStatesWhetherTheDetailsStateLimits() {
		assertEquals(Boolean.TRUE, searchPayload().get("answerDetailsStateLimits"));
		limits = false;
		assertEquals(Boolean.FALSE, searchPayload().get("answerDetailsStateLimits"));
		summary = null;
		limits = true;
		assertEquals(Boolean.FALSE, searchPayload().get("answerDetailsStateLimits"), "never without a summary");
	}

	@Test
	public void theEarlyDoneAndTheGroundedEventStateItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);
		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertEquals(SUMMARY, done.get("answerSummary").asText(),
				"the early done is what a streaming user sees, and the answer is whole by then");
		assertEquals(SUMMARY, SseEvents.dataOfType(out, "grounded", MAPPER).get("answerSummary").asText());
		assertTrue(done.get("answerDetailsStateLimits").asBoolean());
		assertTrue(SseEvents.dataOfType(out, "grounded", MAPPER).get("answerDetailsStateLimits").asBoolean());
	}

	@Test
	public void theClassicDoneEventStatesIt() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);
		assertEquals(SUMMARY, SseEvents.dataOfType(out, "done", MAPPER).get("answerSummary").asText());
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a composed answer with its summary");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		assertEquals(1, ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"answerDetailsStateLimits\""), "answerDetailsStateLimits must be written in exactly one place");
		int keys = ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"answerSummary\"");
		assertEquals(1, keys, "answerSummary must be written in exactly one place, beside answeredByTheModule. Found "
				+ keys + " writes of it.");
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
