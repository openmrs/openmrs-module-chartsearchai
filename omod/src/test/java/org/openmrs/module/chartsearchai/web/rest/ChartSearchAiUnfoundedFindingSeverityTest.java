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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnfoundedFindingSeverity;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The ratings an answer attaches to cited safety findings that carry NONE reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/560">#560</a>), as
 * {@code unfoundedFindingSeverities}: {@code unstatedFindingSeverities}' question asked in the opposite
 * direction, and published in that key's shape — {@code citation} and {@code rating}.
 *
 * <p>What the value MEANS — the sentence unit, the vocabulary, why an empty list is not a certificate and
 * which residues the check cannot see — is pinned one layer down by {@code UnfoundedFindingSeverityTest},
 * over the real pipeline and the shipped knowledge base, and is canonical at
 * {@code ChartSearchService.UnfoundedFindingSeverity}. Here the subject is the wire: that the key reaches
 * every surface, that its two fields are spelled as documented, that {@code null} and empty survive as
 * themselves, and that it marshals for an XML client (issue #347).
 */
public class ChartSearchAiUnfoundedFindingSeverityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Can I give her prednisone?";

	/** The reported answer's shape: an unrated cross-reactivity finding called Major. Nothing here is
	 *  parsed — the check runs one layer down. */
	private static final String MODEL_ANSWER = "Prednisone should be reviewed: her dexamethasone allergy "
			+ "makes this a Major finding [354], and her hydrocortisone allergy a Moderate one [355].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case. The two ratings DIFFER, so a serializer writing
	 *  the first entry's rating onto every entry would redden. */
	private List<UnfoundedFindingSeverity> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(
				new UnfoundedFindingSeverity(354, "Major"),
				new UnfoundedFindingSeverity(355, "Moderate")));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new UnfoundedSeverityAnswerStubService());
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
				null, null, null, null, null, null, null, false, null, null, stated, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	private JsonNode eventData(String eventType) throws Exception {
		return SseEvents.dataOfType(out, eventType, MAPPER);
	}

	@Test
	public void theSearchResponseNamesTheRatingsAttachedToFindingsThatCarryNone() {
		Map<String, Object> payload = searchPayload();
		assertEquals(Arrays.asList(entry(354, "Major"), entry(355, "Moderate")),
				payload.get("unfoundedFindingSeverities"),
				"each entry pairs the unrated finding's citation with the rating the answer gave it, under "
						+ "the two key names unstatedFindingSeverities uses: " + payload);
	}

	/** Empty says the check ran and found none; null says no measurement was made. */
	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertTrue(empty.containsKey("unfoundedFindingSeverities"), "present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("unfoundedFindingSeverities"));

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("unfoundedFindingSeverities"), "present where nothing is stated: " + none);
		assertEquals(null, none.get("unfoundedFindingSeverities"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("unfoundedFindingSeverities"), "the done event carried no unfoundedFindingSeverities key");
		assertEquals(2, done.get("unfoundedFindingSeverities").size());
		assertEquals(354, done.get("unfoundedFindingSeverities").get(0).get("citation").asInt());
		assertEquals("Major", done.get("unfoundedFindingSeverities").get(0).get("rating").asText());
		assertEquals("Moderate", done.get("unfoundedFindingSeverities").get(1).get("rating").asText(),
				"and the SECOND entry carries its own rating, not the first one's");
	}

	/**
	 * With async grounding the early {@code done} states nothing and the trailing {@code grounded}
	 * carries the measurement. This pins the CONTROLLER half — that null is serialized as null there.
	 * That production states null is pinned by
	 * {@code UnfoundedFindingSeverityTest.searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne}.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("unfoundedFindingSeverities"), "the early done must still carry the key");
		assertTrue(done.get("unfoundedFindingSeverities").isNull(),
				"the check has not run when this event is emitted, and an empty list here would tell a client "
						+ "the answer's ratings had been compared and found nothing");

		JsonNode grounded = eventData("grounded");
		assertEquals(2, grounded.get("unfoundedFindingSeverities").size(), "the trailing event carries it");
		assertEquals("Major", grounded.get("unfoundedFindingSeverities").get(0).get("rating").asText());
	}

	/** XStreamMarshaller refuses {@code Collections}' immutable wrappers (issue #347); all three shapes
	 *  this key can take have to marshal. */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated set of unfounded ratings");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/** Structural: exactly one write of the key, beside the module's other statements. */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();
		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"unfoundedFindingSeverities\"");
		assertEquals(1, keys, "the unfoundedFindingSeverities key must be written in exactly one place (issue "
				+ "#560). Found " + keys + " writes of it.");
	}

	/** The wire shape of one entry, spelled out as a map: the KEYS are part of the claim. */
	private static Map<String, Object> entry(int citation, String rating) {
		Map<String, Object> expected = new LinkedHashMap<String, Object>();
		expected.put("citation", Integer.valueOf(citation));
		expected.put("rating", rating);
		return expected;
	}

	private class UnfoundedSeverityAnswerStubService implements ChartSearchService {

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
			// Production's own shape: the early answer is built BEFORE the check runs.
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
