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
import java.util.Arrays;
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
 * The {@code unstatedSignificanceQualifiers} key (ADR Decision 136): the cited findings whose unknown-significance
 * qualifier the answer drops, published as the module stated them, with {@code []} and {@code null} kept apart.
 */
public class ChartSearchAiUnstatedSignificanceQualifiersTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "The patient is currently on Lamivudine, Nevirapine, Stavudine, is it "
			+ "safe to give Rifampicin?";

	private static final String MODEL_ANSWER = "Nevirapine's order is no longer in force, but it interacts with "
			+ "Rifampicin (rifampin) — Major.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case. */
	private List<Integer> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(46, 51));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new QualifierStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(MODEL_ANSWER,
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, false, null, null, null, null, null, stated, null, false);
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
	public void theSearchResponseNamesEachCitationInOrder() {
		assertEquals(Arrays.asList(46, 51), searchPayload().get("unstatedSignificanceQualifiers"));
	}

	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		assertEquals(Collections.emptyList(), searchPayload().get("unstatedSignificanceQualifiers"),
				"a check that ran and found nothing states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("unstatedSignificanceQualifiers"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("unstatedSignificanceQualifiers"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theEarlyDoneCarriesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertEquals(2, done.get("unstatedSignificanceQualifiers").size());
		assertEquals(46, done.get("unstatedSignificanceQualifiers").get(0).asInt());
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated list of citations");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		assertEquals(1, ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"unstatedSignificanceQualifiers\""), "the key must be written in exactly one place");
	}

	/** An answer stating the claim on both the classic and the async shapes, as production's does. */
	private class QualifierStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(MODEL_ANSWER);
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
