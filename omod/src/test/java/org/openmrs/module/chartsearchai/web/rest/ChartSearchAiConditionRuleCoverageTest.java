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
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceLoad;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The wire half of issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/378">#378</a>: what the
 * loaded dataset publishes for the hand-authored CONDITION-rule arm, on every surface that carries
 * an answer.
 *
 * <p>Under the shipped {@code sourceFormat=ddinter} default that arm is {@code absent} — its
 * condition leg has no rule to evaluate, so a patient's recorded conditions are put to nothing — and
 * before this key nothing a {@code /search} consumer reads could tell that from a screen that asked
 * and found nothing, while {@code interactionPairs} beside it stated a completeness the interaction
 * arm genuinely had. That asymmetry, visible in a single payload, is the issue.
 *
 * <p>What these cases do NOT pin is the verdict itself; that is
 * {@code LlmInferenceServiceConditionRuleCoverageContextTest} and
 * {@code ShippedDrugReferenceDefaultTest} in the api module, over a real load. Here the subject is
 * the SERIALIZATION — that the key reaches all three surfaces, spells the verdict the way
 * {@code /chartsearchai/drugreferencestatus} spells it, and is written in one place.
 */
public class ChartSearchAiConditionRuleCoverageTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static final String MODEL_ANSWER = "No — Clarithromycin should not be started.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** The verdict the stub service states; set per case before the handler runs. */
	private DrugReferenceLoad.Coverage stated;

	@BeforeEach
	public void setUp() {
		stated = DrugReferenceLoad.Coverage.ABSENT;
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new CoverageStubService());
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
	 * The defect, on the wire: the response now says what this install's contraindication screen had
	 * to ask the patient's recorded conditions with — in the vocabulary
	 * {@code /chartsearchai/drugreferencestatus} already uses, so a client that reads
	 * {@code arms.conditionRules.coverage} needs no second one.
	 */
	@Test
	public void theSearchResponseStatesWhatTheConditionArmHadToAskWith() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("conditionRuleCoverage"),
				"the blocking /search response must state what the condition arm had to ask with: "
						+ payload);
		assertEquals("absent", payload.get("conditionRuleCoverage"),
				"and spell the verdict the way the status endpoint spells it — one verdict must not be "
						+ "named two ways on two surfaces");
		// What made the defect invisible: the one completeness statement the response already carried
		// belongs to the arm that had nothing to hide.
		assertTrue(payload.containsKey("interactionPairs"),
				"precondition: the interaction arm's own statement is on this payload, which is the "
						+ "asymmetry the issue is about");
	}

	/**
	 * The key is always present, and no statement is a {@code null} rather than a missing key or an
	 * invented token — the shape {@code interactionPairs} and {@code unresolvedDrugClass} keep, so a
	 * client never has to tell "the module said nothing" from "this build does not publish it".
	 */
	@Test
	public void theKeyIsPresentAndNullWhereTheModuleStatesNothing() {
		stated = null;

		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("conditionRuleCoverage"),
				"the key must be present even where the module states nothing: " + payload);
		assertEquals(null, payload.get("conditionRuleCoverage"));
	}

	/**
	 * {@code unloaded} is not {@code absent}, and the wire has to keep them apart: "we looked and
	 * there is none" against "nobody looked" is the distinction issue #378 asks for by name, and the
	 * one {@code entriesPublishing} cannot make, reading {@code 0} for both.
	 */
	@Test
	public void nobodyLookedIsNotTheSameTokenAsWeLookedAndThereIsNone() {
		stated = DrugReferenceLoad.Coverage.UNLOADED;
		assertEquals("unloaded", searchPayload().get("conditionRuleCoverage"));

		stated = DrugReferenceLoad.Coverage.ABSENT;
		assertEquals("absent", searchPayload().get("conditionRuleCoverage"),
				"the two must not serialize alike; a client rendering 'this install did not screen her "
						+ "conditions' off the second would otherwise say it on an install that loaded "
						+ "no dataset at all, which is a different claim");
	}

	@Test
	public void theDoneEventStatesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("conditionRuleCoverage"),
				"the done event carried no conditionRuleCoverage key");
		assertEquals("absent", done.get("conditionRuleCoverage").asText());
	}

	/**
	 * Both terminal events state it, and the EARLY one in full. With async grounding the {@code done}
	 * a user sees is emitted before validation runs — which is why {@code interactionPairs} is
	 * {@code null} there — but this verdict is read off the load before the model is called, so there
	 * is no reason for that event to carry less, and a client would otherwise see the key go from
	 * {@code null} to a token for no reason it could explain.
	 */
	@Test
	public void bothTerminalEventsStateIt_includingTheEarlyDoneThatPrecedesGrounding() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("conditionRuleCoverage"),
				"the early done must carry the key, so a client reads one field unconditionally");
		assertEquals("absent", done.get("conditionRuleCoverage").asText(),
				"and carry it in FULL, unlike interactionPairs, which cannot be known that early");

		JsonNode grounded = eventData("grounded");
		assertEquals("absent", grounded.get("conditionRuleCoverage").asText(),
				"and the trailing event re-sends it unchanged, as unresolvedDrugClass is re-sent");
	}

	/**
	 * The whole payload still marshals for a client asking for XML. A bare token cannot trip the
	 * failure {@code chartOrderBridges} did — {@code XStreamMarshaller} refuses
	 * {@code java.util.Collections}' immutable wrappers, not strings — and this case is what makes
	 * that a measurement rather than a claim, for anyone who later reaches for a richer shape here.
	 * Both of the shapes this key can take are put to it.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated verdict");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no statement at all");
	}

	/**
	 * Written in exactly one place, beside the chips, the interaction extent and the class statement.
	 *
	 * <p>Asserted as a count of the KEY rather than of emission sites, for the reason
	 * {@code ChartSearchAiUnfaithfulRenderingTest} records on the sibling key: a fourth
	 * payload-building method that serializes an answer without calling
	 * {@code putModuleStatements} leaves this guard and all three of its neighbours green. The three
	 * surfaces that exist today are covered behaviourally by the cases above; a fourth would need its
	 * own.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"conditionRuleCoverage\"");
		assertEquals(1, keys,
				"the conditionRuleCoverage key must be written in exactly one place, beside the chips, "
						+ "the interaction extent and the class statement (issue #378). Found " + keys
						+ " writes of it.");
	}

	/**
	 * Production's own shape for this question: the verdict is on the ungrounded answer as well as on
	 * the returned one, because {@code LlmInferenceService} reads it off the load before the model
	 * runs — the same reason the class statement is on both.
	 */
	private class CoverageStubService implements ChartSearchService {

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
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, stated);
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
