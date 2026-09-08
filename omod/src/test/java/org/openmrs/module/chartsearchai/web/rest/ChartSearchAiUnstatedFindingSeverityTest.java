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
 * The safety findings whose RATING an answer states nowhere reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/337">#337</a>, round
 * three).
 *
 * <p>On the reported response five interaction findings were enumerated flat with no rating stated
 * for any of them, two of them Major, and nothing a client could read said so: the chips carried
 * every rating correctly but are a parallel list nothing reconciles against the prose, and
 * {@code unfaithfullyRenderedCitations} correctly read {@code []} because the round-two check
 * reports a substitution inside a reproduction and this answer reproduced nothing. That is the shape
 * #354 answered for the drug-class note, #336 for the interaction extent and #377 for a
 * misattributed citation, and this key is the same remedy.
 *
 * <p>What the value MEANS — why it is the citation and not a word of either text, why an empty list
 * is not a certificate, and which residues the check cannot see — is pinned one layer down by
 * {@code SafetyFindingSeverityFidelityTest} and is canonical at
 * {@code ChartAnswer.getUnstatedFindingSeverities()}. Here the subject is the wire: that the key
 * reaches every surface, that {@code null} and empty survive as themselves, and that it marshals for
 * an XML client, which is the one shape a list-valued key is already known to break (issue #347).
 */
public class ChartSearchAiUnstatedFindingSeverityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	/**
	 * The reported answer's shape: findings enumerated flat, each with its citation, and no rating
	 * word anywhere. Nothing here is parsed — the check runs one layer down — but a canned answer
	 * that looked nothing like the defect would make this file's premise unreadable.
	 */
	private static final String MODEL_ANSWER = "No — Clarithromycin should not be started: "
			+ "Clarithromycin interacts with active order Methylprednisolone [350], and "
			+ "Clarithromycin interacts with active order Budesonide [351].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case: the two dropped ratings by default, and reset per case. */
	private List<Integer> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(
				Arrays.asList(Integer.valueOf(350), Integer.valueOf(351)));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new UnstatedSeverityAnswerStubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, stated,
				null, null);
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
	 * The defect, on the wire: an answer that dropped the rating of the findings it states now says
	 * so, where before this key nothing on the response distinguished it from a faithful one. (Not
	 * "every observable field read as a faithful answer's would" — the same reported response also
	 * carries issue #377, whose key flags three of its chart citations. What nothing flagged is the
	 * dropped rating.)
	 */
	@Test
	public void theSearchResponseNamesTheFindingsWhoseRatingTheAnswerDropped() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("unstatedFindingSeverities"),
				"the blocking /search response must state which cited findings it found the answer "
						+ "stated no rating for: " + payload);
		assertEquals(Arrays.asList(Integer.valueOf(350), Integer.valueOf(351)),
				payload.get("unstatedFindingSeverities"));
		// What made the defect invisible: the chips are a parallel list, so a client rendering them
		// beside this prose sees correct ratings and a degraded sentence and nothing relating the two.
		assertEquals(0, ((List<?>) payload.get("safetyWarnings")).size(),
				"precondition: this stub raises no chip, so no chip field carries the news either");
	}

	/**
	 * Empty and null are different statements and both have to survive serialization as themselves.
	 * Empty says the check ran and named no finding; null says this producer made no measurement —
	 * which is what the early {@code done} of the async path carries, the check running after that
	 * handoff.
	 */
	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertTrue(empty.containsKey("unstatedFindingSeverities"),
				"the key must be present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("unstatedFindingSeverities"),
				"a check that ran and named no finding states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("unstatedFindingSeverities"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("unstatedFindingSeverities"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("unstatedFindingSeverities"),
				"the done event carried no unstatedFindingSeverities key");
		assertEquals(2, done.get("unstatedFindingSeverities").size());
		assertEquals(350, done.get("unstatedFindingSeverities").get(0).asInt());
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this case pins is the CONTROLLER half of that — that an answer
	 * stating nothing is serialized as {@code null} on the early event and not flattened to an empty
	 * list, which would tell a client the answer had been compared and found faithful. That
	 * PRODUCTION states nothing there is a different claim and is pinned one layer down, by
	 * {@code SafetyFindingSeverityFidelityTest.searchStreaming_statesItOnTheAnswerItReturnsAndNotOnTheEarlyOne},
	 * which drives the real orchestration; the stub below only reproduces its shape.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("unstatedFindingSeverities"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("unstatedFindingSeverities").isNull(),
				"the check has not run when this event is emitted, and an empty list here would tell a "
						+ "client the answer's ratings had been compared and found intact");

		JsonNode grounded = eventData("grounded");
		assertEquals(2, grounded.get("unstatedFindingSeverities").size(),
				"the trailing event is where the measurement lands");
		assertEquals(350, grounded.get("unstatedFindingSeverities").get(0).asInt());
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml},
	 * and it refuses {@code Collections}' immutable wrappers — publishing an accessor's own list
	 * turned every chip-carrying XML response into a 500 in issue #347, the empty case included. All
	 * three shapes this key can take have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated set of dropped ratings");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. Stated
	 * as what it holds rather than as what would be useful. It reddens on the literal being wrapped
	 * across a line or moved into a quoted comment, and it does NOT see the two mutations its sibling
	 * on {@code misattributedOrderCitations} records — hoisting the literal to a constant, and a
	 * fourth payload-building method that never calls {@code putModuleStatements} — which are the
	 * same two here for the same reason.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"unstatedFindingSeverities\"");
		assertEquals(1, keys,
				"the unstatedFindingSeverities key must be written in exactly one place, beside the "
						+ "chips and the module's other statements (issue #337). Found " + keys
						+ " writes of it.");
	}

	/** An answer that states its findings without their ratings, on both the classic and the async
	 *  shapes. */
	private class UnstatedSeverityAnswerStubService implements ChartSearchService {

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
