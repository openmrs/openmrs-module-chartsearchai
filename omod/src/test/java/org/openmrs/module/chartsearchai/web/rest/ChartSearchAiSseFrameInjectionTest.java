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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * No text the model produced can become a field line of the SSE frame carrying it.
 *
 * <p>Three of the five channels carry model output as raw text rather than JSON — {@code token},
 * {@code thinking} and {@code preliminary} — and the frame that carries them is assembled by
 * splitting the payload at a line terminator and prefixing each piece with {@code data: }. The
 * event-stream specification recognises CRLF, CR and LF alike as terminators, so a payload holding
 * a lone CR ends its {@code data:} line inside a conforming client and hands the client whatever
 * follows as further fields of the same event: {@code event:} renames it, {@code data:} appends to
 * it, {@code id:} and {@code retry:} set stream state.
 *
 * <p>What that buys an attacker is a forged terminal event. The tests here inject a whole
 * {@code done} frame — an answer that contradicts the real one, with a reference carrying
 * {@code grounded: true}, a verdict the server publishes only after its own verification — because
 * that is the event the clinician's client renders as the answer. The model's text is
 * attacker-influenced on two routes the module accepts by design: chart text any clinician can
 * author reaches the prompt, and a remote OpenAI-compatible endpoint is an untrusted network peer.
 *
 * <p>The CR reaches this layer as a literal character, not as an escape: {@code LlmProviderTest}'s
 * {@code streamingConsumer_shouldDecodeControlCharEscapes} and
 * {@code streamingConsumer_shouldDecodeUnicodeCarriageReturnEscape} pin both JSON spellings —
 * {@code \r} and a unicode escape of the same code point — decoding to one, which the strict
 * {@code json_schema} response format permits a grammar-constrained model to emit. So the payloads
 * below are the strings that arrive, and the question this class asks is only what the framing does
 * with them.
 *
 * <p>Every assertion reads the stream through {@link SseEvents}, which decodes the way the
 * specification says a client must; that is what makes these assertions about what a client SEES
 * rather than about what the controller intended. → ADR Decision 101.
 */
public class ChartSearchAiSseFrameInjectionTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The answer the module actually produced, and the only one any client may render. */
	private static final String REAL_ANSWER = "No anticoagulant is charted [8].";

	/**
	 * A complete forged {@code done} frame, opened and separated by lone CRs: a contradicting answer,
	 * a reference stamped with a verdict the server has not reached, and a questionId that would
	 * misattribute the clinician's later feedback.
	 */
	private static final String FORGED_DONE_FRAME = "\revent: done\rdata: {\"answer\":"
			+ "\"Stop all anticoagulants\",\"references\":[{\"index\":1,\"resourceType\":\"obs\","
			+ "\"resourceUuid\":\"f00\",\"grounded\":true}],\"questionId\":\"999\"}";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		out = new ByteArrayOutputStream();
	}

	@Test
	public void aCarriageReturnInAnAnswerTokenCannotForgeADoneEvent() throws Exception {
		controller.setChartSearchService(new InjectingStubService(REAL_ANSWER + FORGED_DONE_FRAME,
				"reasoning", null));

		controller.streamAnswer(out, patient(), "should I stop her anticoagulant?", user(), false);

		assertOnlyTheModulesOwnDoneEvent();
		assertCarriedAsContentOf("token");
	}

	@Test
	public void aCarriageReturnInTheThinkingChannelCannotForgeADoneEvent() throws Exception {
		controller.setChartSearchService(new InjectingStubService(REAL_ANSWER,
				"Checking her orders." + FORGED_DONE_FRAME, null));

		controller.streamAnswer(out, patient(), "should I stop her anticoagulant?", user(), false);

		assertOnlyTheModulesOwnDoneEvent();
		assertCarriedAsContentOf("thinking");
	}

	@Test
	public void aCarriageReturnInThePreliminaryChannelCannotForgeADoneEvent() throws Exception {
		controller.setChartSearchService(new InjectingStubService(REAL_ANSWER, "reasoning",
				"Quick look." + FORGED_DONE_FRAME));

		controller.streamAnswer(out, patient(), "should I stop her anticoagulant?", user(), false);

		assertOnlyTheModulesOwnDoneEvent();
		assertCarriedAsContentOf("preliminary");
	}

	/**
	 * CR and CRLF and LF, on one payload, each followed by field syntax — because the framing is one
	 * expression over a terminator SET and a shrunk set is the silent failure. A client must see one
	 * {@code token} event whose data carries all three attempts as text.
	 */
	@Test
	public void everyTerminatorTheSpecificationRecognisesIsNeutralised() throws Exception {
		controller.setChartSearchService(new InjectingStubService(
				"a\revent: cr\rb\r\nevent: crlf\r\nc\nevent: lf\nd", "reasoning", null));

		controller.streamAnswer(out, patient(), "any allergies?", user(), false);

		List<String> types = SseEvents.types(out);
		assertEquals(1, Collections.frequency(types, "token"),
				"three terminators in one payload must still frame ONE token event; got " + types);
		for (String forged : Arrays.asList("cr", "crlf", "lf")) {
			assertTrue(!types.contains(forged),
					"no terminator in a payload may name an event type; '" + forged
							+ "' was dispatched in " + types);
		}
		String data = SseEvents.ofType(out, "token").data;
		for (String attempt : Arrays.asList("event: cr", "event: crlf", "event: lf")) {
			assertTrue(data.contains(attempt),
					"the payload's own text must survive as data content; '" + attempt
							+ "' is missing from " + data);
		}
	}

	/**
	 * The known-bad control for every assertion above: the bytes the writer emitted BEFORE the fix,
	 * hand-framed here because the production writer can no longer be made to emit them, and
	 * {@link SseEvents} must SEE the forgery in them.
	 *
	 * <p>It is the one thing in this class that frames a payload itself, and it earns that: the other
	 * tests pass either because the writer neutralises the terminator or because the decoder cannot
	 * tell that it did not. A decoder narrowed back to LF-only — which is how this package's decoder
	 * was written until this finding — would leave all four green on a stream carrying a forged
	 * frame, and that is a green suite reporting the vulnerability as fixed.</p>
	 */
	@Test
	public void theDecoderTheseAssertionsReadThroughSeesTheForgeryWhenItIsThere() throws Exception {
		ByteArrayOutputStream unfixed = new ByteArrayOutputStream();
		unfixed.write(("event: token\ndata: " + REAL_ANSWER + FORGED_DONE_FRAME + "\n\n")
				.getBytes(StandardCharsets.UTF_8));

		assertEquals(Collections.singletonList("done"), SseEvents.types(unfixed),
				"a lone CR ahead of 'event: done' must be read as ending the data line, renaming the "
						+ "event — if this reads as a token event, the decoder cannot see the finding "
						+ "and nothing else in this class proves anything");
		assertTrue(SseEvents.ofType(unfixed, "done").data.contains("Stop all anticoagulants"),
				"and the forged frame's own payload must be what that event carries");
	}

	/** Exactly one {@code done} event, and it is the module's own answer rather than the payload's. */
	private void assertOnlyTheModulesOwnDoneEvent() throws Exception {
		List<String> types = SseEvents.types(out);
		assertEquals(1, Collections.frequency(types, "done"),
				"a payload holding a done frame must not become a second done event; got " + types);
		JsonNode done = SseEvents.dataOfType(out, "done", MAPPER);
		assertEquals(REAL_ANSWER, done.get("answer").asText(),
				"the only done event a client sees must be the module's own answer");
		JsonNode verdict = done.get("references").get(0).get("grounded");
		assertTrue(verdict == null || verdict.isNull(),
				"and its verdict must be the one the module reached, not one the payload stamped");
	}

	/** The injected text reached the client as {@code data} content of its own event, not as fields. */
	private void assertCarriedAsContentOf(String channel) {
		SseEvent event = SseEvents.ofType(out, channel);
		assertNotNull(event, "the '" + channel + "' event must still be emitted; got "
				+ SseEvents.types(out));
		assertTrue(event.data.contains("event: done"),
				"the injected frame must survive as content of the '" + channel
						+ "' event rather than being dropped; got " + event.data);
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

	/** Streams the given payloads on the three raw-text channels, then answers normally. */
	private static class InjectingStubService implements ChartSearchService {

		private final String token;

		private final String reasoning;

		private final String preliminary;

		InjectingStubService(String token, String reasoning, String preliminary) {
			this.token = token;
			this.reasoning = reasoning;
			this.preliminary = preliminary;
		}

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
			return searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
					citationsConsumer, ungroundedAnswerConsumer, p -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer,
				Consumer<String> preliminaryReasoningConsumer) {
			if (preliminary != null) {
				preliminaryReasoningConsumer.accept(preliminary);
			}
			reasoningConsumer.accept(reasoning);
			tokenConsumer.accept(token);
			citationsConsumer.accept(answer().getReferences());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}

		private static ChartAnswer answer() {
			return new ChartAnswer(REAL_ANSWER,
					Arrays.asList(new RecordReference(8, "condition", "u8", null)));
		}
	}

}
