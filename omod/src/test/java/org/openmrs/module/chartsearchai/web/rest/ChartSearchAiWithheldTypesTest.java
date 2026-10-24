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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The record types the asking user could not read reach the client on the answer, so it can say
 * why they are absent rather than show an answer that reads as a chart without them (ADR
 * Decision 91's rule).
 */
public class ChartSearchAiWithheldTypesTest {

	private static final String QUESTION = "Any allergies?";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the stub answer states; set per case before the handler runs. */
	private Set<String> withheld;

	@BeforeEach
	public void setUp() {
		withheld = Collections.singleton("allergy");
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new WithheldStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		return (Map<String, Object>) response.getBody();
	}

	@Test
	public void theSearchResponseNamesTheTypesTheUserCouldNotRead() {
		assertEquals(Collections.singletonList("allergy"), searchPayload().get("withheldTypes"));

		withheld = null;
		Map<String, Object> payload = searchPayload();
		assertTrue(payload.containsKey("withheldTypes"), "the key is present where the answer states nothing");
		assertEquals(null, payload.get("withheldTypes"));
	}

	@Test
	public void theDoneEventNamesThemToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = SseEvents.dataOfType(out, "done", new ObjectMapper());
		assertEquals("allergy", done.get("withheldTypes").get(0).asText(), done.toString());
	}

	private class WithheldStubService implements ChartSearchService {

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
				Consumer<String> reasoningConsumer, Consumer<List<ChartSearchService.RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(answer().getAnswer());
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		private ChartAnswer answer() {
			return new ChartAnswer("Allergy records are not visible to this user.",
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, false, null, null, null, null, null, null,
					DrugSafetyValidator.STATUS_UNAVAILABLE, Collections.emptyList(), null, false, null, false, withheld);
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
