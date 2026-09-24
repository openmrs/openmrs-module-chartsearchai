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
import org.openmrs.module.chartsearchai.api.ChartSearchService.InteractionClaimPairs;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code interactionClaimPairs} key on every surface an answer reaches — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/514">#514</a>. What the
 * module states is decided one layer down, by {@code InteractionClaimPairFidelityCheck}, and pinned
 * there over the real pipeline; this file pins that the controller carries the statement as it is —
 * a measurement as a measurement, none as none — on {@code /search} and on the {@code done} and
 * {@code grounded} SSE events.
 */
public class ChartSearchAiInteractionClaimPairsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "The patient is currently on Lamivudine, Nevirapine, Stavudine, "
			+ "is it safe to give metformin?";

	/** The ticket's case 2 in shape: a finding about another drug cited for the claim. */
	private static final String MODEL_ANSWER = "Metformin interacts with active order Lamivudine / "
			+ "zidovudine [353].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case — three DISTINCT values, so a serializer that
	 *  transposed two of them is seen. */
	private InteractionClaimPairs stated;

	@BeforeEach
	public void setUp() {
		stated = new InteractionClaimPairs(3, Arrays.asList(Integer.valueOf(353)), 1);
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new StubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, false, stated);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	@SuppressWarnings("unchecked")
	@Test
	public void theSearchResponseStatesAllThreeParts() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("interactionClaimPairs"), "was: " + payload);
		Map<String, Object> pairs = (Map<String, Object>) payload.get("interactionClaimPairs");
		assertNotNull(pairs, "a measurement must not serialize as null");
		assertEquals(3, pairs.get("judged"));
		assertEquals(Arrays.asList(Integer.valueOf(353)), pairs.get("misattributedCitations"));
		assertEquals(1, pairs.get("unfounded"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void aZeroedStatementAndNoStatementAreDifferentOnTheWire() {
		stated = new InteractionClaimPairs(0, Collections.<Integer> emptyList(), 0);
		Map<String, Object> zeroed = (Map<String, Object>) searchPayload().get("interactionClaimPairs");
		assertNotNull(zeroed, "a measurement of none is a measurement");
		assertEquals(0, zeroed.get("judged"));
		assertEquals(Collections.emptyList(), zeroed.get("misattributedCitations"));
		assertEquals(0, zeroed.get("unfounded"));

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("interactionClaimPairs"), "the key is present where nothing is stated");
		assertEquals(null, none.get("interactionClaimPairs"), "no measurement is null, never a zeroed object");
	}

	@Test
	public void theDoneEventStatesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode pairs = SseEvents.dataOfType(out, "done", MAPPER).get("interactionClaimPairs");
		assertNotNull(pairs, "the done event carried no interactionClaimPairs key");
		assertEquals(3, pairs.get("judged").asInt());
		assertEquals(353, pairs.get("misattributedCitations").get(0).asInt());
		assertEquals(1, pairs.get("unfounded").asInt());
	}

	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertTrue(done.has("interactionClaimPairs"), "the early done still carries the key");
		assertTrue(done.get("interactionClaimPairs").isNull(),
				"the check has not run when this event is emitted, so it states nothing");
		JsonNode grounded = SseEvents.dataOfType(out, "grounded", MAPPER);
		assertEquals(1, grounded.get("interactionClaimPairs").get("unfounded").asInt(),
				"the trailing event is where the measurement lands");
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated measurement");
		stated = new InteractionClaimPairs(0, Collections.<Integer> emptyList(), 0);
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		int keys = ChartSearchAiStreamingTest.occurrences(ChartSearchAiStreamingTest.controllerSource(),
				"\"interactionClaimPairs\"");
		assertEquals(1, keys, "the interactionClaimPairs key must be written in exactly one place. Found "
				+ keys + " writes of it.");
	}

	private class StubService implements ChartSearchService {

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
			// Production's own shape: the early answer is built before the check runs.
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(MODEL_ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
