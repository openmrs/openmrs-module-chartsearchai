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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.OrderStopDate;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The date a cited prescription stopped being in force reaches the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/315">#315</a>).
 *
 * <p>Since #321 the answer says an order is no longer in force and never says WHEN. Nothing a client
 * read could say either: a citation publishes its record's own clinical date, no record text reaches
 * the wire at all, and the record's order status is not published. Asking the prompt for the date
 * was measured and refused — ADR Decision 47's residue 1 — so the module states it. That is the
 * shape #354 answered for the drug-class note, #336 for the interaction extent, #377 for a
 * misattributed citation, #337 for a dropped rating and #276 for an unstated ceiling, and this key is
 * the same remedy.
 *
 * <p>What the value MEANS — that it is not a claim about the prose, and that an order out of force
 * need not publish a date so absence is never a claim of currency — is pinned one layer down by
 * {@code OrderStopDateStatementTest} and is canonical at
 * {@code ChartSearchService.OrderStopDate}. Here the subject is the wire: that the key reaches every
 * surface, that its fields are spelled {@code citation} and {@code stopDate}, that the date is
 * spelled the way a citation's own {@code date} is, that {@code null} and empty survive as
 * themselves, and that it marshals for an XML client — the one shape a list-valued key is already
 * known to break (issue #347).
 *
 * <p><b>One thing here differs from the CHECK-derived keys and is deliberate:</b> the early
 * {@code done} of the async path CARRIES this measurement rather than stating null. It is a
 * projection over the answer's own markers and its resolution, both in hand before the grounding
 * handoff, so unlike those checks it does not run after that event — the same reason
 * {@code unresolvedDrugClass}, {@code chartReadForSafety} and {@code conditionRuleCoverage} are
 * already final there, though none of those reads the answer at all. The case below draws the
 * contrast against one named check rather than against "every sibling".
 */
public class ChartSearchAiOrderStopDateTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "what medications is he taking?";

	/** The ticket's own answer shape after #321: it names the drug and reports the ending, and states
	 *  no date. Nothing here is parsed — the projection runs one layer down — but a canned answer
	 *  that looked nothing like the defect would make this file's premise unreadable. */
	private static final String MODEL_ANSWER =
			"Nevirapine was prescribed, but its order is no longer in force [1].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case. TWO entries with different dates, so a
	 *  serializer writing the first entry's date onto every entry cannot stay green. */
	private List<OrderStopDate> stated;

	private static Date utc(String isoDate) {
		return Date.from(LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(
				new OrderStopDate(1, utc("2026-08-24")),
				new OrderStopDate(4, utc("2008-01-08"))));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new OrderStopDateAnswerStubService());
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
				null, null, null, null, null, stated, null);
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
	 * The defect, on the wire: an answer that reports a prescription ended without saying when now
	 * carries the date beside the citation it printed, where before this key nothing on the response
	 * could tell a client when the order stopped.
	 */
	@Test
	public void theSearchResponseNamesTheDateACitedOrderStopped() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("orderStopDates"),
				"the blocking /search response must state when each cited ended order stopped: "
						+ payload);
		assertEquals(Arrays.asList(entry(1, "2026-08-24"), entry(4, "2008-01-08")),
				payload.get("orderStopDates"),
				"each entry pairs the citation the answer printed with that order's own end — the "
						+ "citation alone would leave a client exactly where the prose already left "
						+ "it: " + payload);
		assertFalse(MODEL_ANSWER.contains("2026-08-24"),
				"precondition: the answer itself must not carry the date, or this key is reporting "
						+ "something a client could already read");
	}

	/**
	 * Empty and null are different statements and both have to survive serialization as themselves.
	 * Empty says the projection ran and had no date to state; null says this producer made no
	 * measurement. Neither is a claim that the cited orders are in force —
	 * {@code ChartSearchService.OrderStopDate} is canonical for that and it is not restated here.
	 */
	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertTrue(empty.containsKey("orderStopDates"),
				"the key must be present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("orderStopDates"),
				"a projection that ran and had nothing to state is an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("orderStopDates"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("orderStopDates"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("orderStopDates"), "the done event carried no orderStopDates key");
		assertEquals(2, done.get("orderStopDates").size());
		assertEquals(1, done.get("orderStopDates").get(0).get("citation").asInt());
		assertEquals("2026-08-24", done.get("orderStopDates").get(0).get("stopDate").asText());
		assertEquals("2008-01-08", done.get("orderStopDates").get(1).get("stopDate").asText(),
				"and the SECOND entry carries its own date, not the first one's");
	}

	/**
	 * The one place this key's timing differs from its siblings'. Every check-derived statement is
	 * {@code null} on the early {@code done}, the check running after that handoff. This one is a
	 * projection over the answer's markers and its resolution, so it is already final there — and
	 * withholding it would leave exactly this ticket's clinician reading an answer about an ended
	 * prescription with no end date beside it, until a later event they may not be waiting for.
	 *
	 * <p>That PRODUCTION resolves it before the handoff is pinned one layer down; the stub below only
	 * reproduces that shape, which is why this case asserts the CONTROLLER passes it through rather
	 * than that the module computed it.
	 */
	@Test
	public void theEarlyDoneAlreadyCarriesTheMeasurementUnlikeEveryCheckDerivedKey() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("orderStopDates"),
				"the early done must carry the key, so a client reads one field unconditionally");
		assertEquals(2, done.get("orderStopDates").size(),
				"and must carry the measurement itself, not null: the date is known before the "
						+ "grounding handoff and the early done is what a streaming user reads");
		assertEquals("2026-08-24", done.get("orderStopDates").get(0).get("stopDate").asText());
		assertTrue(done.get("unstatedDosingCeilings").isNull(),
				"precondition: a check-derived key IS null on this event, which is what makes the "
						+ "assertion above a difference rather than an accident");

		JsonNode grounded = eventData("grounded");
		assertEquals(2, grounded.get("orderStopDates").size(),
				"the trailing event restates it unchanged");
		assertEquals("2026-08-24", grounded.get("orderStopDates").get(0).get("stopDate").asText());
	}

	/**
	 * The date is spelled the way a citation's own {@code date} is on the same response, so a client
	 * parses one format and not two. Both go through the controller's {@code formatDate}.
	 */
	@Test
	public void theDateIsSpelledTheWayACitationsOwnDateIs() {
		Map<String, Object> payload = searchPayload();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("orderStopDates");
		assertEquals("2026-08-24", entries.get(0).get("stopDate"),
				"a bare ISO calendar date, as every other date on this response is: " + entries);
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml},
	 * and it refuses {@code Collections}' immutable wrappers — publishing an accessor's own list
	 * turned every chip-carrying XML response into a 500 in issue #347, the empty case included. All
	 * three shapes this key can take have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated set of order stop dates");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. Stated
	 * as what it holds rather than as what would be useful. It reddens on the literal being wrapped
	 * across a line or moved into a quoted comment, and it does NOT see a fourth payload-building
	 * method that never calls {@code putModuleStatements}, which is the residue every sibling of this
	 * case records.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"orderStopDates\"");
		assertEquals(1, keys,
				"the orderStopDates key must be written in exactly one place, beside the chips and "
						+ "the module's other statements (issue #315). Found " + keys
						+ " writes of it.");
	}

	/** The wire shape of one entry, spelled out as a map rather than compared through the value
	 *  type — the subject here is what a JSON client receives, so the KEYS are part of the claim and
	 *  a renamed one must redden. */
	private static Map<String, Object> entry(int citation, String stopDate) {
		Map<String, Object> expected = new LinkedHashMap<String, Object>();
		expected.put("citation", Integer.valueOf(citation));
		expected.put("stopDate", stopDate);
		return expected;
	}

	/** An answer that reports an ended prescription without dating it, on both the classic and the
	 *  async shapes. */
	private class OrderStopDateAnswerStubService implements ChartSearchService {

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
			// Production's own shape: this statement is resolved BEFORE the handoff, so the early
			// answer carries it while every check-derived statement beside it is still null.
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(MODEL_ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null,
					null, null, null, null, null, stated, null));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
