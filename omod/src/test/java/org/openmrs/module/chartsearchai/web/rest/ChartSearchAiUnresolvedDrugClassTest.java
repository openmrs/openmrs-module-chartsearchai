/*
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
import java.util.HashMap;
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
 * A question that named a drug CLASS the module could not resolve says so ON THE WIRE (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/354">#354</a>).
 *
 * <p>#354 blesses two outcomes and this change takes the second — the module states that the
 * question named a class and asks for a specific drug. Its deterministic half is one injected
 * {@code drug_class_note} record, and that record reaches a client only if the MODEL cites it: the
 * {@code /search} response returns cited references and nothing else. Measured on the issue's own
 * reproduction, the model cited nothing and answered "The records do not address starting an oral
 * contraceptive for this patient." — so nothing a {@code /search} consumer reads reported the class
 * at all, and the only delta was an audit-log column that consumer cannot see.
 *
 * <p>So the module states it itself, on the same three surfaces {@code interactionPairs} reaches and
 * for the same reason issue #336 put that key there: a safety statement that is only as reliable as
 * the wording of a generated answer is not a statement. These cases hold the key present on every
 * one of them — including the EARLY {@code done}, which with async grounding is the event a user
 * actually sees — and hold it structurally for a surface nobody has written yet.
 *
 * <p>What the value MEANS, and that it is read off the injected chart rather than by asking the
 * question a second time, is pinned one layer down by
 * {@code LlmInferenceServiceUnresolvedDrugClassTest}. Here the subject is the wire.
 */
public class ChartSearchAiUnresolvedDrugClassTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The issue's headline question and the class its note names. */
	private static final String CLASS_QUESTION = "Can I start this patient on an oral contraceptive?";

	private static final String CLASS_NAME = "oral contraceptive";

	/**
	 * The answer the issue's live run measured, verbatim. It relays no part of the note and carries
	 * no citation marker, which is the whole point: the response must still say what the module did.
	 */
	private static final String MODEL_ANSWER =
			"The records do not address starting an oral contraceptive for this patient.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** Null for the answer that states no class; set per case before the handler runs. */
	private String stated;

	@BeforeEach
	public void setUp() {
		stated = CLASS_NAME;
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ClassQuestionStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	/** The final answer: no citations, no chips, and the module's own class statement. */
	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(MODEL_ANSWER,
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Collections.<SafetyWarning> emptyList(),
				null, null, null, stated);
	}

	/** The {@code /search} response body. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", RestControllerContext.PATIENT_UUID);
		body.put("question", CLASS_QUESTION);

		ResponseEntity<Object> response = controller.search(body);
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	private JsonNode eventData(String eventType) throws Exception {
		return SseEvents.dataOfType(out, eventType, MAPPER);
	}

	/**
	 * The issue's reproduction, on the wire. Every other observable field reads exactly as it did
	 * before the change — which is why the class statement has to be its own key.
	 */
	@Test
	public void theSearchResponseNamesTheClassTheAnswerNeverMentionedResolving() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("unresolvedDrugClass"),
				"the blocking /search response must state the class it could not resolve: " + payload);
		assertEquals(CLASS_NAME, payload.get("unresolvedDrugClass"));
		// The defect itself: nothing else on this response changed. Without the key above, a client
		// sees the pre-#354 silence.
		assertEquals(0, ((List<?>) payload.get("references")).size(),
				"precondition: the model cited nothing, so the injected note reaches no reference");
		assertEquals(0, ((List<?>) payload.get("safetyWarnings")).size(),
				"precondition: a class puts no substance in play, so there are no chips either");
		assertEquals(null, payload.get("interactionPairs"),
				"precondition: and no pairwise screen ran, so that statement is absent too");
	}

	@Test
	public void theKeyIsPresentAndNullWhenNoClassWasStated() {
		// Present-and-null rather than omitted, like `grounded` and `interactionPairs` beside it, so
		// a client reads one field unconditionally instead of testing for its existence.
		stated = null;
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("unresolvedDrugClass"),
				"the key must be present even where the module states no class: " + payload);
		assertEquals(null, payload.get("unresolvedDrugClass"));
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, patient(), CLASS_QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("unresolvedDrugClass"), "the done event carried no unresolvedDrugClass key");
		assertEquals(CLASS_NAME, done.get("unresolvedDrugClass").asText());
	}

	/**
	 * With async grounding the class statement is on BOTH terminal events, and the early one is what
	 * matters: it is emitted the moment the answer exists, so a client that renders on {@code done}
	 * and never waits for {@code grounded} would otherwise see the pre-#354 silence. Unlike
	 * {@code interactionPairs} — which is legitimately null there, because validation has not run —
	 * this statement is known before the model is even called, so there is no reason for it to be
	 * absent and its absence would be the defect.
	 */
	@Test
	public void bothTerminalEventsNameIt_includingTheEarlyDoneThatPrecedesGrounding() throws Exception {
		controller.streamAnswer(out, patient(), CLASS_QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertEquals(0, done.get("safetyWarnings").size(),
				"precondition: this is the early done, emitted before validation ran");
		assertTrue(done.get("interactionPairs").isNull(),
				"precondition: it states no pair extent, which is what makes this event the one at risk");
		assertTrue(done.has("unresolvedDrugClass"), "the early done event carried no unresolvedDrugClass key");
		assertEquals(CLASS_NAME, done.get("unresolvedDrugClass").asText(),
				"the class is known before the model is called, so the early event must carry it");

		JsonNode grounded = eventData("grounded");
		assertTrue(grounded.has("unresolvedDrugClass"),
				"the trailing grounded event carried no unresolvedDrugClass key");
		assertEquals(CLASS_NAME, grounded.get("unresolvedDrugClass").asText());
	}

	/**
	 * Structural, and it is what makes the cases above hold for a surface nobody has written yet: the
	 * chips, the statement about how bounded they are, and the class statement are written by ONE
	 * method, so a payload site added later cannot carry the answer and drop one of them.
	 *
	 * <p>It is asserted as "{@code putSafetyChips} is called once" rather than as a count of emission
	 * sites, deliberately: a legitimate fourth site is expected, and a case that reddens on one would
	 * be re-measured and raised rather than read.
	 */
	@Test
	public void noEmissionSiteCanPublishAnAnswerWithoutTheModulesOwnStatements() throws Exception {
		// Through ChartSearchAiStreamingTest's resolver, which is taught both layouts and asserts the
		// file was found — a guard that silently cannot read its subject passes.
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"unresolvedDrugClass\"");
		assertEquals(1, keys,
				"the unresolvedDrugClass key must be written in exactly one place, beside the chips and "
						+ "the extent statement (issue #354). Found " + keys + " writes of it.");
		int chips = ChartSearchAiStreamingTest.occurrences(source, "putSafetyChips(");
		assertEquals(2, chips,
				"putSafetyChips must be named exactly twice — its own declaration and the one call "
						+ "inside putModuleStatements. A third naming is an emission site taking the "
						+ "chips without the class statement beside them. Found " + chips + ".");
	}

	/**
	 * Production's own shape for a class question: an answer with no citations and no chips, and an
	 * early-done answer that carries the class statement but not the chips — which is exactly what
	 * {@code LlmInferenceService} constructs, because the statement is read off the chart before the
	 * model runs while the chips are not known until after grounding.
	 */
	private class ClassQuestionStubService implements ChartSearchService {

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
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(MODEL_ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(),
					null, null, null, stated));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
