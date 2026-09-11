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
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingCitationExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How many injected safety findings the prompt carried, and how many of them the answer cited, reach
 * the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/395">#395</a>).
 *
 * <p>It is the base its four neighbours needed and none of them is: each of those judges a finding
 * the answer DID cite, so on the reported response — six findings stated of seven carried — not one
 * of them reported the dropped finding. The first case below states this key beside the two whose
 * empty lists were the misleading part, and asserts those as PRECONDITIONS rather than as results.
 *
 * <p>What the two numbers MEAN is canonical at {@code ChartSearchService.FindingCitationExtent} and
 * pinned one layer down by {@code SafetyFindingCitationExtentTest}, which drives the real
 * orchestration. Here the subject is the wire: that the key reaches every surface, that each number
 * lands under its own name rather than the other's, that a zeroed statement and no statement survive
 * as themselves, and that it marshals for an XML client.
 */
public class ChartSearchAiFindingCitationsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "should i give Amlodipine?";

	/** The measured shape, abridged: the answer names six active orders with a citation apiece and
	 *  the seventh finding reaches the prose nowhere. Nothing here is parsed — the measurement runs
	 *  one layer down — but a canned answer that looked nothing like the defect would make this
	 *  file's premise unreadable. */
	private static final String MODEL_ANSWER = "No — Amlodipine should not be given: it interacts "
			+ "with active order Solu-Medrol 125mg/5ml, a Moderate problem [349]. Finally, it "
			+ "interacts with active order Hydrocortisone, a Moderate problem [354].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case. The default is the ticket's own reading, and
	 *  it is ASYMMETRIC deliberately: a fixture whose two numbers are equal cannot see the
	 *  serializer's two right-hand sides transposed. */
	private FindingCitationExtent stated;

	@BeforeEach
	public void setUp() {
		stated = new FindingCitationExtent(7, 6);
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new DroppedFindingStubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null,
				Collections.<Integer> emptyList(), Collections.<Integer> emptyList(),
				Collections.<ChartSearchService.UnstatedFindingSeverity> emptyList(), null, stated,
				null);
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
	 * The defect, on the wire. This response's {@code unstatedFindingSeverities} and
	 * {@code unfaithfullyRenderedCitations} are both empty — the measured response's own state — and
	 * an empty list from either says the check ran and found nothing, which is indistinguishable
	 * from a faithful answer. So they are asserted here as preconditions, and this key is what makes
	 * the pair readable.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void theSearchResponseSaysHowManyFindingsThePromptCarriedAndTheAnswerCited() {
		Map<String, Object> payload = searchPayload();

		assertEquals(Collections.emptyList(), payload.get("unstatedFindingSeverities"),
				"precondition: the dropped finding's rating is stated by its six siblings, so the "
						+ "whole-answer rating check reads exactly as it does on a faithful answer");
		assertEquals(Collections.emptyList(), payload.get("unfaithfullyRenderedCitations"),
				"precondition: the answer reproduces nothing, so there is no substitution to find");
		assertTrue(payload.containsKey("findingCitations"),
				"the blocking /search response must state what makes those empty lists readable: "
						+ payload);
		Map<String, Object> extent = (Map<String, Object>) payload.get("findingCitations");
		assertNotNull(extent, "the statement must not be null where the module made one");
		assertEquals(7, extent.get("carried"), "seven findings were carried into the prompt");
		assertEquals(6, extent.get("cited"), "and the answer cited six of them");
	}

	/**
	 * A zeroed statement and no statement are different and both have to survive serialization as
	 * themselves. Zero says the check ran and the prompt carried no finding — the shipped default's
	 * ordinary state, where {@code chartsearchai.drugReference.enabled} is false; null says this
	 * producer made no measurement, which is what the early {@code done} of the async path carries,
	 * the check running after that handoff.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void aZeroedStatementAndNoStatementAreDifferentOnTheWire() {
		stated = new FindingCitationExtent(0, 0);
		Map<String, Object> zeroed = searchPayload();
		assertTrue(zeroed.containsKey("findingCitations"),
				"the key must be present for a measurement of none: " + zeroed);
		Map<String, Object> extent = (Map<String, Object>) zeroed.get("findingCitations");
		assertNotNull(extent, "a zeroed statement is a measurement and must not serialize as null");
		assertEquals(0, extent.get("carried"));
		assertEquals(0, extent.get("cited"));

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("findingCitations"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("findingCitations"),
				"no measurement is null, and must not be flattened to a zeroed object");
	}

	/**
	 * The streaming {@code done} event, on the same asymmetric reading — which is what tells
	 * {@code carried} apart from {@code cited} on the wire. Transposed, the reported response
	 * publishes {@code carried: 6, cited: 7}, an answer that cited more findings than the prompt
	 * held; a client comparing the two would read the gap backwards and conclude nothing was
	 * dropped. Swap the two {@code map.put} arms in {@code serializeFindingCitationExtent} and read
	 * the failure.
	 */
	@Test
	public void theDoneEventStatesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("findingCitations"), "the done event carried no findingCitations key");
		assertEquals(7, done.get("findingCitations").get("carried").asInt(),
				"seven findings were carried, and the key must not carry the other number here");
		assertEquals(6, done.get("findingCitations").get("cited").asInt(),
				"and six of them were cited");
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this pins is the CONTROLLER half — that an answer stating nothing
	 * serializes as {@code null} and is not flattened to a zeroed object, which would tell a client
	 * the prompt had carried no finding at all. That PRODUCTION states nothing there is a different
	 * claim, pinned by {@code SafetyFindingCitationExtentTest
	 * .searchStreaming_statesNoMeasurementOnTheEarlyAnswerAndOneOnTheAnswerItReturns}.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("findingCitations"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("findingCitations").isNull(),
				"the check has not run when this event is emitted, and a zeroed object here would "
						+ "tell a client the prompt carried no finding");

		JsonNode grounded = eventData("grounded");
		assertEquals(7, grounded.get("findingCitations").get("carried").asInt(),
				"the trailing event is where the measurement lands");
		assertEquals(6, grounded.get("findingCitations").get("cited").asInt());
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml}.
	 * Publishing an accessor's own list turned every chip-carrying XML response into a 500 in issue
	 * #347; a nested map is a different shape from a list, so all three states this key can take
	 * have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated measurement");
		stated = new FindingCitationExtent(0, 0);
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. It
	 * reddens on the literal being wrapped across a line or moved into a quoted comment, and it does
	 * NOT see the two mutations its siblings' guards do not see — hoisting the literal to a constant,
	 * and a fourth payload-building method that bypasses {@code putModuleStatements}. Both are
	 * recorded on {@code ChartSearchAiMisattributedOrderCitationTest.theKeyIsWrittenInExactlyOnePlace}
	 * rather than restated; the three surfaces that exist today are covered behaviourally above.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"findingCitations\"");
		assertEquals(1, keys,
				"the findingCitations key must be written in exactly one place, beside the siblings "
						+ "it is read with (issue #395). Found " + keys + " writes of it.");
	}

	/** An answer that cites six of the seven findings the prompt carried, on both the classic and
	 *  the async shapes. */
	private class DroppedFindingStubService implements ChartSearchService {

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
