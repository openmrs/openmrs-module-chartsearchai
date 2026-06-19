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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Behavioral tests for the SSE event order emitted by the controller's streaming
 * orchestration ({@code streamAnswer}), driven with a stubbed {@link ChartSearchService}
 * and a captured output stream — no Spring context, matching this test package's
 * conventions.
 *
 * <p>The async-grounding contract under test: when async grounding is active, {@code done}
 * is emitted as soon as the answer exists (its references carry NO verdicts), and a trailing
 * {@code grounded} event delivers the verdict-annotated references afterwards — so the user's
 * perceived completion no longer waits out the Tier-2 grounding tail. When async grounding is
 * off, the classic single grounded {@code done} is preserved byte-for-byte in shape. When the
 * service returns without ever firing the ungrounded-answer consumer (a cache hit — the cached
 * answer is already final), the controller must fall back to the classic {@code done} even in
 * async mode, and emit no {@code grounded} event.</p>
 */
public class ChartSearchAiStreamEventOrderTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		out = new ByteArrayOutputStream();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	private static User user() {
		return new User(3);
	}

	/** One parsed SSE event: {@code event:} type plus the concatenated {@code data:} payload. */
	private static final class SseEvent {

		final String type;

		final String data;

		SseEvent(String type, String data) {
			this.type = type;
			this.data = data;
		}
	}

	private List<SseEvent> emittedEvents() {
		List<SseEvent> events = new ArrayList<SseEvent>();
		for (String block : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n\n")) {
			String type = null;
			StringBuilder data = new StringBuilder();
			for (String line : block.split("\n")) {
				if (line.startsWith("event: ")) {
					type = line.substring(7).trim();
				} else if (line.startsWith("data: ")) {
					data.append(line.substring(6));
				}
			}
			if (type != null) {
				events.add(new SseEvent(type, data.toString()));
			}
		}
		return events;
	}

	private List<String> eventTypes() {
		List<String> types = new ArrayList<String>();
		for (SseEvent e : emittedEvents()) {
			types.add(e.type);
		}
		return types;
	}

	private SseEvent eventOfType(String type) {
		for (SseEvent e : emittedEvents()) {
			if (e.type.equals(type)) {
				return e;
			}
		}
		return null;
	}

	@Test
	public void asyncGrounding_emitsDoneBeforeGroundedAndVerdictsArriveInGroundedEvent()
			throws Exception {
		controller.setChartSearchService(new LiveStubService());

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", true);

		List<String> types = eventTypes();
		int doneIdx = types.indexOf("done");
		int groundedIdx = types.indexOf("grounded");
		assertTrue(doneIdx >= 0, "done event must be emitted; got " + types);
		assertTrue(groundedIdx >= 0, "grounded event must be emitted; got " + types);
		assertTrue(doneIdx < groundedIdx, "done must precede grounded; got " + types);

		JsonNode done = MAPPER.readTree(eventOfType("done").data);
		assertEquals("Has TB [8].", done.get("answer").asText());
		assertEquals("42", done.get("questionId").asText(),
				"done must still carry the audit questionId so feedback works immediately");
		JsonNode doneRef = done.get("references").get(0);
		assertTrue(doneRef.get("grounded") == null || doneRef.get("grounded").isNull(),
				"async done must carry the references WITHOUT verdicts (grounding still pending)");

		JsonNode grounded = MAPPER.readTree(eventOfType("grounded").data);
		assertEquals("42", grounded.get("questionId").asText(),
				"grounded must carry the same questionId so the client can correlate");
		assertTrue(grounded.get("references").get(0).get("grounded").asBoolean(),
				"the grounded event must deliver the verifier's verdicts");
	}

	@Test
	public void syncGrounding_keepsClassicSingleDoneWithVerdicts() throws Exception {
		controller.setChartSearchService(new LiveStubService());

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", false);

		List<String> types = eventTypes();
		assertFalse(types.contains("grounded"),
				"sync mode must not emit a grounded event; got " + types);
		JsonNode done = MAPPER.readTree(eventOfType("done").data);
		assertTrue(done.get("references").get(0).get("grounded").asBoolean(),
				"sync done must carry the final verdicts exactly as today");
		assertEquals("42", done.get("questionId").asText());
	}

	@Test
	public void asyncGrounding_cacheHitFallsBackToClassicDone() throws Exception {
		// A cache hit returns an already-grounded answer without ever firing the
		// ungrounded-answer consumer — the controller must then emit the classic done
		// (with verdicts) and no grounded event, even though async mode is on.
		controller.setChartSearchService(new CacheHitStubService());

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", true);

		List<String> types = eventTypes();
		assertEquals(1, frequency(types, "done"), "exactly one done event; got " + types);
		assertFalse(types.contains("grounded"),
				"no grounded event when the answer was final at return; got " + types);
		JsonNode done = MAPPER.readTree(eventOfType("done").data);
		assertTrue(done.get("references").get(0).get("grounded").asBoolean(),
				"the cached answer's verdicts ride in done as before");
	}

	@Test
	public void asyncGrounding_emitsExactlyOneDone() throws Exception {
		controller.setChartSearchService(new LiveStubService());

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", true);

		assertEquals(1, frequency(eventTypes(), "done"),
				"async mode must not double-emit done; got " + eventTypes());
	}

	@Test
	public void asyncGrounding_misbehavingServiceDoubleFireStillEmitsOneDone() throws Exception {
		// The interface contract is at-most-once, but a delegating wrapper bug could fire the
		// consumer twice; the controller must stay idempotent — a duplicate done event would
		// corrupt every client's completion handling.
		controller.setChartSearchService(new LiveStubService() {

			@Override
			public ChartAnswer searchStreaming(Patient patient, String question,
					Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
					Consumer<List<RecordReference>> citationsConsumer,
					Consumer<ChartAnswer> ungroundedAnswerConsumer) {
				ungroundedAnswerConsumer.accept(ungroundedAnswer());
				ungroundedAnswerConsumer.accept(ungroundedAnswer());
				return groundedAnswer();
			}
		});

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", true);

		assertEquals(1, frequency(eventTypes(), "done"),
				"a misbehaving double-fire must not double-emit done; got " + eventTypes());
		assertEquals(1, frequency(eventTypes(), "grounded"));
	}

	private static int frequency(List<String> list, String value) {
		int n = 0;
		for (String s : list) {
			if (s.equals(value)) {
				n++;
			}
		}
		return n;
	}

	private static ChartSearchService.ChartAnswer ungroundedAnswer() {
		return new ChartSearchService.ChartAnswer("Has TB [8].",
				Arrays.asList(new ChartSearchService.RecordReference(8, "condition", "u8", null)));
	}

	private static ChartSearchService.ChartAnswer groundedAnswer() {
		return new ChartSearchService.ChartAnswer("Has TB [8].",
				Arrays.asList(new ChartSearchService.RecordReference(8, "condition", "u8", null,
						Boolean.TRUE)));
	}

	@Test
	public void streamAnswer_emitsPreliminaryEvent_whenServiceStreamsPreviewReasoning() throws Exception {
		controller.setChartSearchService(new PreliminaryStubService());

		controller.streamAnswer(out, patient(), "any infections?", user(), "full-chart", true);

		List<String> types = eventTypes();
		assertTrue(types.contains("preliminary"),
				"the controller must surface progressive preview reasoning on a 'preliminary' SSE event, "
						+ "distinct from 'thinking', so the UI can render it provisionally");
		assertEquals("Quick look: [8] mentions TB.", eventOfType("preliminary").data);
		assertTrue(types.indexOf("preliminary") < types.indexOf("token"),
				"the preview must arrive before the committed answer token");
	}

	/** Preview-path stub: emits a preliminary reasoning chunk on the 7-arg's preliminary channel
	 *  before the committed answer, to exercise the controller's "preliminary" SSE wiring. */
	private static class PreliminaryStubService extends LiveStubService {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer,
				Consumer<String> preliminaryReasoningConsumer) {
			preliminaryReasoningConsumer.accept("Quick look: [8] mentions TB.");
			return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, citationsConsumer,
					ungroundedAnswerConsumer);
		}
	}

	/** Live-path stub: streams a token, fires citations + the ungrounded answer, returns grounded. */
	private static class LiveStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return groundedAnswer();
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
			tokenConsumer.accept("Has TB [8].");
			citationsConsumer.accept(ungroundedAnswer().getReferences());
			ungroundedAnswerConsumer.accept(ungroundedAnswer());
			return groundedAnswer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	/** Cache-hit stub: never fires the ungrounded consumer; returns the final answer directly. */
	private static class CacheHitStubService extends LiveStubService {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept("Has TB [8].");
			citationsConsumer.accept(groundedAnswer().getReferences());
			return groundedAnswer();
		}
	}

	private static class StubAuditLogService implements AuditLogService {

		@Override
		public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
			auditLog.setAuditLogId(42);
			return auditLog;
		}

		@Override
		public ChartSearchAuditLog getAuditLog(Integer auditLogId) {
			return null;
		}

		@Override
		public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user,
				java.util.Date fromDate, java.util.Date toDate, Integer startIndex, Integer limit) {
			return java.util.Collections.emptyList();
		}

		@Override
		public Long getAuditLogCount(Patient patient, User user, java.util.Date fromDate,
				java.util.Date toDate) {
			return 0L;
		}

		@Override
		public long getQueryCountByUserSince(User user, java.util.Date since) {
			return 0L;
		}

		@Override
		public int deleteAuditLogsBefore(java.util.Date before) {
			return 0;
		}
	}
}
