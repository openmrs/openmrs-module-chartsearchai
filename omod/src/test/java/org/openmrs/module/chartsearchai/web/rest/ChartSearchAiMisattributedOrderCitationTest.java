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
 * The chart citations the module found an answer could not have been offering as evidence of an
 * active drug order reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/377">#377</a>).
 *
 * <p>On the reported response, three of five interaction sentences cited a condition, a visit and an
 * encounter as the active order they named, and the other two cited the right drug order — so the
 * citations read as uniformly plausible and nothing a client could see separated them. Every
 * reference in it serialized {@code grounded: null}, which is what the #284 carve-out publishes for
 * a chart citation whose sentence also rests on a {@code safety_finding}, so the field a client
 * WOULD have read said nothing about any of the eleven. That is the shape #354 answered for the
 * drug-class note, #336 for the interaction extent and #337 for a degraded rendering, and this key
 * is the same remedy.
 *
 * <p>What the value MEANS — why it is the citation and not a word of the record's text, why an empty
 * list is not a certificate, and which residues the check cannot see — is pinned one layer down by
 * {@code ActiveOrderCitationFidelityTest} and is canonical at
 * {@code ChartAnswer.getMisattributedOrderCitations()}. Here the subject is the wire: that the key
 * reaches every surface, that {@code null} and empty survive as themselves, and that it marshals for
 * an XML client, which is the one shape a list-valued key is already known to break (issue #347).
 */
public class ChartSearchAiMisattributedOrderCitationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	/**
	 * The reported answer's shape: the module's own active-order claim, followed by the chart
	 * citation the model chose and the finding's own. Nothing here is parsed — the check runs one
	 * layer down — but a canned answer that looked nothing like the defect would make this file's
	 * premise unreadable.
	 */
	private static final String MODEL_ANSWER = "Clarithromycin interacts with active order "
			+ "Methylprednisolone [253] [350].";

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
		controller.setChartSearchService(new MisattributedAnswerStubService());
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
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, stated);
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
	 * The defect, on the wire: an answer that cited a record which cannot be the order it names now
	 * says so, where before this key every observable field read exactly as a correct answer's would.
	 */
	@Test
	public void theSearchResponseNamesTheCitationThatCannotBeTheOrder() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("misattributedOrderCitations"),
				"the blocking /search response must state which citations it found could not be the "
						+ "order they were offered for: " + payload);
		assertEquals(Arrays.asList(Integer.valueOf(253)),
				payload.get("misattributedOrderCitations"));
		// What made the defect invisible: nothing else on the response distinguishes this answer
		// from one whose citations all pointed at the orders it named.
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
		assertTrue(empty.containsKey("misattributedOrderCitations"),
				"the key must be present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("misattributedOrderCitations"),
				"a check that ran and named no citation states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("misattributedOrderCitations"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("misattributedOrderCitations"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("misattributedOrderCitations"),
				"the done event carried no misattributedOrderCitations key");
		assertEquals(1, done.get("misattributedOrderCitations").size());
		assertEquals(253, done.get("misattributedOrderCitations").get(0).asInt());
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this case pins is the CONTROLLER half of that — that an answer
	 * stating nothing is serialized as {@code null} on the early event and not flattened to an empty
	 * list, which would tell a client the answer had been compared and found faithful. That
	 * PRODUCTION states nothing there is a different claim and is pinned one layer down, by
	 * {@code ActiveOrderCitationFidelityTest.searchStreaming_reportsItOnThePrimaryProductionPathToo},
	 * which drives the real orchestration; the stub below only reproduces its shape.
	 * {@code interactionPairs} is null on that event for the same class of reason.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("misattributedOrderCitations"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("misattributedOrderCitations").isNull(),
				"the check has not run when this event is emitted, and an empty list here would tell a "
						+ "client the answer was compared against its records and found faithful");

		JsonNode grounded = eventData("grounded");
		assertEquals(1, grounded.get("misattributedOrderCitations").size(),
				"the trailing event is where the measurement lands");
		assertEquals(253, grounded.get("misattributedOrderCitations").get(0).asInt());
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
	 * as what it holds rather than as what would be useful. It reddens on the literal being wrapped
	 * across a line or moved into a quoted comment. TWO mutations it does not see, both measured
	 * rather than reasoned: hoisting the key to a {@code private static final String} and using that
	 * at the put site keeps the count at one, which is the refactor a maintainer is most likely to
	 * actually perform; and a fourth payload-building method that serializes the answer without
	 * calling {@code putModuleStatements} leaves this guard and both its neighbours on the sibling
	 * keys green. The three surfaces that exist today are covered behaviourally by the cases above; a
	 * fourth would need its own.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"misattributedOrderCitations\"");
		assertEquals(1, keys,
				"the misattributedOrderCitations key must be written in exactly one place, beside "
						+ "the chips and the class statement (issue #377). Found " + keys
						+ " writes of it.");
	}

	/** An answer citing a record that cannot be the order it names, on both the classic and the
	 *  async shapes. */
	private class MisattributedAnswerStubService implements ChartSearchService {

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
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
