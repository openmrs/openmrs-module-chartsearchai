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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The wire half of issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a> item 1: a
 * chart the module could not read says so on every surface that carries an answer.
 *
 * <p>What made the defect invisible is that every OTHER safety key on the payload reports honestly
 * about nothing. A failed allergy, condition or active-order read degrades to an empty set, so
 * {@code safetyWarnings} is empty, {@code interactionPairs} states a screen of none, and
 * {@code conditionRuleCoverage} answers about the DATASET — all true, and together they read as a
 * clean chart. This key is the only one that can say the reads themselves did not happen.
 *
 * <p>What these cases do NOT pin is the verdict; that is
 * {@code LlmInferenceServiceChartReadForSafetyContextTest} in the api module, over real service
 * reads with a privilege revoked. Here the subject is the SERIALIZATION — that the key reaches all
 * three surfaces, keeps its three values apart, and is written in one place.
 */
public class ChartSearchAiChartReadForSafetyTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Can I give her ibuprofen?";

	private static final String MODEL_ANSWER = "Ibuprofen can be given.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** The verdict the stub service states; set per case before the handler runs. */
	private Boolean stated;

	@BeforeEach
	public void setUp() {
		stated = Boolean.FALSE;
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ChartReadStubService());
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
	 * The defect, on the wire: the response says the chart behind this answer was not read, beside
	 * the empty chip list that would otherwise read as a clear chart.
	 */
	@Test
	public void theSearchResponseSaysTheChartCouldNotBeRead() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("chartReadForSafety"),
				"the blocking /search response must state whether the chart was read: " + payload);
		assertEquals(Boolean.FALSE, payload.get("chartReadForSafety"),
				"the module could not read this chart, so the response must not let the empty "
						+ "safetyWarnings beside it be read as a measurement of none");
		assertTrue(payload.containsKey("safetyWarnings"),
				"precondition: the empty chip list this key qualifies is on the payload, which is the "
						+ "arrangement the issue is about");
	}

	/**
	 * The three values stay apart on the wire. {@code TRUE} is a measurement, {@code FALSE} is a
	 * measurement, and {@code null} is the absence of one — collapsing any pair loses exactly the
	 * distinction the issue exists for, which is that "we read it and found nothing" and "nobody
	 * read it" produced identical responses.
	 */
	@Test
	public void aReadChartAnUnreadOneAndNoStatementAreThreeDifferentAnswers() {
		stated = Boolean.TRUE;
		assertEquals(Boolean.TRUE, searchPayload().get("chartReadForSafety"));

		stated = Boolean.FALSE;
		assertEquals(Boolean.FALSE, searchPayload().get("chartReadForSafety"));

		stated = null;
		Map<String, Object> payload = searchPayload();
		assertTrue(payload.containsKey("chartReadForSafety"),
				"the key must be present even where the module states nothing, so a client never has "
						+ "to tell 'the module said nothing' from 'this build does not publish it': "
						+ payload);
		assertNull(payload.get("chartReadForSafety"),
				"no statement is null and never false — false would claim a failed read that never "
						+ "happened");
	}

	@Test
	public void theDoneEventStatesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("chartReadForSafety"), "the done event carried no chartReadForSafety key");
		assertEquals(false, done.get("chartReadForSafety").asBoolean());
	}

	/**
	 * Both terminal events state it, and the EARLY one in full.
	 *
	 * <p>With async grounding the {@code done} a user sees is emitted before validation runs — which
	 * is why {@code interactionPairs} is {@code null} there — but this verdict comes off the
	 * injector's chart read, which happens before the model is called. So there is no reason for that
	 * event to carry less, and a client would otherwise see the key go from {@code null} to
	 * {@code false} with nothing to explain the change.
	 */
	@Test
	public void bothTerminalEventsStateIt_includingTheEarlyDoneThatPrecedesGrounding() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("chartReadForSafety"),
				"the early done must carry the key, so a client reads one field unconditionally");
		assertEquals(false, done.get("chartReadForSafety").asBoolean(),
				"and carry it in FULL, unlike interactionPairs, which cannot be known that early");

		JsonNode grounded = eventData("grounded");
		assertEquals(false, grounded.get("chartReadForSafety").asBoolean(),
				"and the trailing event re-sends it unchanged, as conditionRuleCoverage is re-sent");
	}

	/**
	 * The whole payload still marshals for a client asking for XML, in each of the three shapes this
	 * key can take. A boxed Boolean cannot trip the failure {@code chartOrderBridges} did —
	 * {@code XStreamMarshaller} refuses {@code java.util.Collections}' immutable wrappers — and this
	 * case is what makes that a measurement rather than a claim, for anyone who later reaches for a
	 * richer shape here.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "an unread chart");
		stated = Boolean.TRUE;
		XmlPayloads.assertMarshals(searchPayload(), "a chart that was read");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no statement at all");
	}

	/**
	 * The key is SPELLED in exactly one place.
	 *
	 * <p>Asserted as a count of the KEY rather than of emission sites, for the reason its neighbours
	 * record: a further payload-building method that serializes an answer without calling
	 * {@code putModuleStatements} leaves this guard and all of its neighbours green. The answer
	 * surfaces are covered behaviourally by the cases above; a further surface would need its own.
	 *
	 * <p>It is deliberately NOT written onto {@code GET /chartsearchai/chartalerts}, which carries no
	 * answer and states its own narrower verdict through {@code StandingChartAlerts.isScreened()};
	 * {@code ChartAnswer.getChartReadForSafety()} is where that comparison lives. Two verdicts, two
	 * names, neither derived from the other.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"chartReadForSafety\"");
		assertEquals(1, keys,
				"the chartReadForSafety key must be spelled in exactly one place, inside the shared "
						+ "putModuleStatements every answer surface goes through (issue #247). Found "
						+ keys + " writes of it.");
	}

	/** Production's own shape for this question: the verdict is on the ungrounded answer as well as
	 *  on the returned one, because it is known before the model runs. */
	private class ChartReadStubService implements ChartSearchService {

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
				Consumer<List<ChartSearchService.RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(MODEL_ANSWER);
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		private ChartAnswer answer() {
			return new ChartAnswer(MODEL_ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null,
					null, null, stated, null);
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
