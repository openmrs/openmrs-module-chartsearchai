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
import org.openmrs.module.chartsearchai.api.ChartSearchService.CautionLedOverWithholding;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The withholding findings about the drug an answer's caution lead gives reach the wire as
 * {@code cautionLedOverWithholding} (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>), on the blocking
 * response and on the early {@code done} alike, since production resolves them before that handoff.
 * Which findings they are is decided one layer down and pinned there, by
 * {@code LlmInferenceServiceListedMedicationsContextTest}; the stub here only reproduces its shapes.
 */
public class ChartSearchAiCautionLedOverWithholdingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "The patient is currently on Lamivudine, Nevirapine, Stavudine, is it "
			+ "safe to give Amlodipine?";

	private static final String MODEL_ANSWER = "Amlodipine can be given, with one caution: coadministration with "
			+ "nevirapine may decrease the plasma concentrations of amlodipine.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case. The two entries differ in both fields, and the second states no
	 *  rating, as an unrated authored rule's record does. */
	private List<CautionLedOverWithholding> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(new CautionLedOverWithholding(9, "Major"),
				new CautionLedOverWithholding(12, null)));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new CautionLeadAnswerStubService());
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
				null, null, null, null, null, null, null, false, null, stated);
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
	public void theSearchResponseNamesEachFindingWithItsRating() {
		Map<String, Object> payload = searchPayload();

		assertEquals(Arrays.asList(entry(9, "Major"), entry(12, null)), payload.get("cautionLedOverWithholding"),
				"each entry is the finding's citation and the rating its record states: " + payload);
	}

	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertEquals(Collections.emptyList(), empty.get("cautionLedOverWithholding"),
				"a check that ran and found nothing states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("cautionLedOverWithholding"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("cautionLedOverWithholding"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theEarlyDoneCarriesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertEquals(2, done.get("cautionLedOverWithholding").size(),
				"resolved before the handoff, so the early done carries the measurement");
		assertEquals(9, done.get("cautionLedOverWithholding").get(0).get("citation").asInt());
		assertEquals("Major", done.get("cautionLedOverWithholding").get(0).get("rating").asText());
		assertTrue(done.get("cautionLedOverWithholding").get(1).get("rating").isNull(),
				"and the second entry's absent rating stays absent");
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated set of findings");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		assertEquals(1, ChartSearchAiStreamingTest.occurrences(source, "\"cautionLedOverWithholding\""),
				"the cautionLedOverWithholding key must be written in exactly one place, beside the module's other "
						+ "statements (issue #515)");
	}

	/** The wire shape of one entry, spelled out as a map so a renamed key reddens. */
	private static Map<String, Object> entry(int citation, String rating) {
		Map<String, Object> expected = new LinkedHashMap<String, Object>();
		expected.put("citation", Integer.valueOf(citation));
		expected.put("rating", rating);
		return expected;
	}

	/** An answer whose caution lead stands beside withholding findings, on both the classic and the async
	 *  shapes; the early answer carries the same statement, as production's does. */
	private class CautionLeadAnswerStubService implements ChartSearchService {

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
