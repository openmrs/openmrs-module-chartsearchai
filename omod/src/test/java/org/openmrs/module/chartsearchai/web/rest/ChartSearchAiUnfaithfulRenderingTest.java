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
import org.springframework.oxm.xstream.XStreamMarshaller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The citations whose rendering the module found unfaithful reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/337">#337</a>, second
 * round).
 *
 * <p>PR #345 gave the module an instrument for the defect and it fires on real answers — the issue's
 * own comment records it naming a live divergence where {@code naproxen} came back as
 * {@code naproxenic}. What it did not do is make the divergence visible to anyone but a maintainer
 * reading a log: the degraded sentence still reached the clinician carrying a citation marker, with
 * nothing in the response saying the marker's own record reads otherwise. That is the shape #354
 * answered for the drug-class note and #336 for the interaction extent, and this key is the same
 * remedy.
 *
 * <p>What the value MEANS — why it is the citation and not a word of the record's prose, and what an
 * empty list does not say — is pinned one layer down by {@code ReferenceProseFidelityTest}, and is
 * canonical at {@code ChartAnswer.getUnfaithfullyRenderedCitations()}. Here the subject is the wire:
 * that the key reaches every surface, that {@code null} and empty survive as themselves, and that it
 * marshals for an XML client, which is the one shape a list-valued key is already known to break
 * (issue #347).
 */
public class ChartSearchAiUnfaithfulRenderingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Is ibuprofen safe for her?";

	/**
	 * The live answer shape from the issue's own comment: the model reproduces the cited record's
	 * mechanism and alters a drug name inside the sentence it was copying. Nothing here is parsed —
	 * the check runs one layer down — but a canned answer that looked nothing like the defect would
	 * make this file's premise unreadable.
	 */
	private static final String MODEL_ANSWER = "There is evidence that others including "
			+ "indomethacin, naproxenic and tiaprofenic acid may also interact [253].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case: the divergence by default, and reset per case. */
	private List<Integer> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(Integer.valueOf(253)));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new UnfaithfulAnswerStubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null, stated);
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
	 * The defect, on the wire: an answer that degraded the record it cites now says so, where before
	 * this key every observable field read exactly as a faithful answer's would.
	 */
	@Test
	public void theSearchResponseNamesTheCitationWhoseRenderingDiverged() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("unfaithfullyRenderedCitations"),
				"the blocking /search response must state which citations it found unfaithfully "
						+ "rendered: " + payload);
		assertEquals(Arrays.asList(Integer.valueOf(253)),
				payload.get("unfaithfullyRenderedCitations"));
		// What made the defect invisible: nothing else on the response distinguishes this answer
		// from one that reproduced the record faithfully.
		assertEquals(0, ((List<?>) payload.get("references")).size(),
				"precondition: this stub cites nothing, so no reference field carries the news either");
	}

	/**
	 * Empty and null are different statements and both have to survive serialization as themselves.
	 * Empty says the check ran and named no citation; null says this producer made no measurement —
	 * which is what the early {@code done} of the async path carries, the check running after that
	 * handoff.
	 */
	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertTrue(empty.containsKey("unfaithfullyRenderedCitations"),
				"the key must be present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("unfaithfullyRenderedCitations"),
				"a check that ran and named no citation states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("unfaithfullyRenderedCitations"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("unfaithfullyRenderedCitations"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("unfaithfullyRenderedCitations"),
				"the done event carried no unfaithfullyRenderedCitations key");
		assertEquals(1, done.get("unfaithfullyRenderedCitations").size());
		assertEquals(253, done.get("unfaithfullyRenderedCitations").get(0).asInt());
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this case pins is the CONTROLLER half of that — that an answer
	 * stating nothing is serialized as {@code null} on the early event and not flattened to an empty
	 * list, which would tell a client the answer had been compared and found faithful. That
	 * PRODUCTION states nothing there is a different claim and is pinned one layer down, by
	 * {@code ReferenceProseFidelityTest.searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne},
	 * which drives the real orchestration; the stub below only reproduces its shape.
	 * {@code interactionPairs} is null on that event for the same class of reason.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("unfaithfullyRenderedCitations"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("unfaithfullyRenderedCitations").isNull(),
				"the check has not run when this event is emitted, and an empty list here would tell a "
						+ "client the answer was compared against its records and found faithful");

		JsonNode grounded = eventData("grounded");
		assertEquals(1, grounded.get("unfaithfullyRenderedCitations").size(),
				"the trailing event is where the measurement lands");
		assertEquals(253, grounded.get("unfaithfullyRenderedCitations").get(0).asInt());
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml},
	 * and it refuses {@code Collections}' immutable wrappers — publishing an accessor's own list
	 * turned every chip-carrying XML response into a 500 in issue #347, the empty case included. All
	 * three shapes this key can take have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated divergence");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. Stated
	 * as what it holds rather than as what would be useful — it reddens on the literal being
	 * relocated (into a constant, across a line wrap, into a quoted comment), and it CANNOT force a
	 * new emission surface through {@code putModuleStatements}. Measured by mutation: a fourth
	 * payload-building method that serializes the answer and never calls that one leaves this guard,
	 * and its two neighbours on the sibling keys, green. The three surfaces that exist today are
	 * covered behaviourally by the cases above; a fourth would need its own.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"unfaithfullyRenderedCitations\"");
		assertEquals(1, keys,
				"the unfaithfullyRenderedCitations key must be written in exactly one place, beside "
						+ "the chips and the class statement (issue #337). Found " + keys
						+ " writes of it.");
	}

	/** An answer that degraded the record it cites, on both the classic and the async shapes. */
	private class UnfaithfulAnswerStubService implements ChartSearchService {

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
			// Production's own shape: the early answer is built BEFORE the prose check runs, so it
			// states no measurement whatever the final one says.
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
