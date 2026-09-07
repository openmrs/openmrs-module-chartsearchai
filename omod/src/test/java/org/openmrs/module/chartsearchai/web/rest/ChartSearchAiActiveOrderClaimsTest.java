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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ActiveOrderClaims;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How many claims about the patient's active orders an answer made, and how many offered no chart
 * record, reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/379">#379</a>).
 *
 * <p>Its sibling {@code misattributedOrderCitations} names the chart citations that cannot be the
 * order they were offered for, and its {@code []} could not be read alone — ADR Decision 81 carries
 * the two live runs behind that. The first case below states the two keys together for that reason.
 *
 * <p>What the two numbers MEAN is canonical at {@code ChartSearchService.ActiveOrderClaims} and
 * pinned one layer down by {@code ActiveOrderCitationFidelityTest}, which drives the real
 * orchestration. Here the subject is the wire: that the key reaches every surface, that a zeroed
 * statement and no statement survive as themselves, and that it marshals for an XML client.
 */
public class ChartSearchAiActiveOrderClaimsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	/** The measured shape: the module's own active-order claim carrying the finding's citation and
	 *  no chart record. Nothing here is parsed — the check runs one layer down — but a canned answer
	 *  that looked nothing like the defect would make this file's premise unreadable. */
	private static final String MODEL_ANSWER = "Clarithromycin interacts with active order "
			+ "Solu-Medrol 125mg/5ml because of an interaction [350].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case. */
	private ActiveOrderClaims stated;

	@BeforeEach
	public void setUp() {
		stated = new ActiveOrderClaims(4, 4);
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new UncitedClaimStubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null,
				Collections.<Integer> emptyList(), null, stated, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		assertEquals(HttpStatus.OK, response.getStatusCode(),
				"the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	private JsonNode eventData(String eventType) throws Exception {
		return SseEvents.dataOfType(out, eventType, MAPPER);
	}

	/**
	 * The defect, on the wire. This response's {@code misattributedOrderCitations} is empty because
	 * nothing was cited to be misattributed, which is indistinguishable from an answer whose every
	 * citation landed — so the two keys are asserted together here, and the empty one is asserted as
	 * a PRECONDITION rather than as a result.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void theSearchResponseSaysHowManyClaimsOfferedNoChartRecord() {
		Map<String, Object> payload = searchPayload();

		assertEquals(Collections.emptyList(), payload.get("misattributedOrderCitations"),
				"precondition: with nothing cited there is nothing to misattribute, so the sibling "
						+ "key reads exactly as it does on an answer whose citations all landed");
		assertTrue(payload.containsKey("activeOrderClaims"),
				"the blocking /search response must state what makes that empty list readable: "
						+ payload);
		Map<String, Object> claims = (Map<String, Object>) payload.get("activeOrderClaims");
		assertNotNull(claims, "the statement must not be null where the module made one");
		assertEquals(4, claims.get("stated"), "four active-order claims were made");
		assertEquals(4, claims.get("uncited"), "and not one of them offered a chart record");
	}

	/**
	 * A zeroed statement and no statement are different and both have to survive serialization as
	 * themselves. Zero says the check ran and the answer stated no such claim — the overwhelmingly
	 * common response; null says this producer made no measurement, which is what the early
	 * {@code done} of the async path carries, the check running after that handoff.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void aZeroedStatementAndNoStatementAreDifferentOnTheWire() {
		stated = new ActiveOrderClaims(0, 0);
		Map<String, Object> zeroed = searchPayload();
		assertTrue(zeroed.containsKey("activeOrderClaims"),
				"the key must be present for a measurement of none: " + zeroed);
		Map<String, Object> claims = (Map<String, Object>) zeroed.get("activeOrderClaims");
		assertNotNull(claims, "a zeroed statement is a measurement and must not serialize as null");
		assertEquals(0, claims.get("stated"));
		assertEquals(0, claims.get("uncited"));

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("activeOrderClaims"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("activeOrderClaims"),
				"no measurement is null, and must not be flattened to a zeroed object");
	}

	@Test
	public void theDoneEventStatesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("activeOrderClaims"), "the done event carried no activeOrderClaims key");
		assertEquals(4, done.get("activeOrderClaims").get("stated").asInt());
		assertEquals(4, done.get("activeOrderClaims").get("uncited").asInt());
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this pins is the CONTROLLER half — that an answer stating nothing
	 * serializes as {@code null} and is not flattened to a zeroed object, which would tell a client
	 * every claim had been examined and found evidenced. That PRODUCTION states nothing there is a
	 * different claim, pinned by {@code ActiveOrderCitationFidelityTest
	 * .searchStreaming_statesTheClaimsOnTheAnswerItReturnsAndNotOnTheEarlyOne}.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("activeOrderClaims"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("activeOrderClaims").isNull(),
				"the check has not run when this event is emitted, and a zeroed object here would "
						+ "tell a client the answer's claims were examined and every one evidenced");

		JsonNode grounded = eventData("grounded");
		assertEquals(4, grounded.get("activeOrderClaims").get("stated").asInt(),
				"the trailing event is where the measurement lands");
		assertEquals(4, grounded.get("activeOrderClaims").get("uncited").asInt());
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml}.
	 * Publishing an accessor's own list turned every chip-carrying XML response into a 500 in issue
	 * #347; a nested map is a different shape from a list and is unproven here, so all three states
	 * this key can take have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated measurement");
		stated = new ActiveOrderClaims(0, 0);
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. It
	 * reddens on the literal being wrapped across a line or moved into a quoted comment, and it does
	 * NOT see the same two mutations its siblings' guards do not see — hoisting the literal to a
	 * constant, and a fourth payload-building method that bypasses {@code putModuleStatements}. Both
	 * are recorded on {@code ChartSearchAiMisattributedOrderCitationTest.theKeyIsWrittenInExactlyOnePlace}
	 * rather than restated; the three surfaces that exist today are covered behaviourally above.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"activeOrderClaims\"");
		assertEquals(1, keys,
				"the activeOrderClaims key must be written in exactly one place, beside the sibling "
						+ "it is read with (issue #379). Found " + keys + " writes of it.");
	}

	/** An answer whose active-order claims cite only the module's own finding, on both the classic
	 *  and the async shapes. */
	private class UncitedClaimStubService implements ChartSearchService {

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
			tokenConsumer.accept(MODEL_ANSWER);
			citationsConsumer.accept(answer().getReferences());
			// Production's own shape: the early answer is built BEFORE this check runs, so it states
			// no measurement whatever the final one says.
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
