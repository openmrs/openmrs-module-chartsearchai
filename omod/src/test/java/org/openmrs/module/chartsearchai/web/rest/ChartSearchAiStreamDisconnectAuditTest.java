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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

/**
 * Issue #450 — the {@code chartsearchai_audit_log} row for a STREAMING query, when the stream does
 * not finish.
 *
 * <p>The row used to be a statement on the success path: at the tail of {@code streamAnswer}'s try
 * block in the classic shape, or inside the ungrounded consumer in the async one. Every streamed
 * frame is written through {@code writeSseEventOrThrow}, which turns an {@code IOException} into a
 * {@code RuntimeException}, and that unwinds out of the service call past BOTH of those sites into
 * the catch-all, which reads an {@code IOException} cause as a benign disconnect. So a user holding
 * <em>AI Query Patient Data</em> could read a streamed answer about any patient and then reset the
 * socket, and the only accountability record the module keeps of who asked what about whom was
 * never written — deterministically, for any prefix of the answer the caller chose to stop at.</p>
 *
 * <p>These cases drive {@code streamAnswer} — the production entry point every audit test in this
 * package uses — over a sink that accepts a chosen number of event frames and then refuses every
 * later one, which is how {@code ChartSearchAiStreamKeepAliveTest.DisconnectedClientSink} models a
 * peer that has gone away. Each disconnect case asserts the sink actually refused something:
 * without that the pipeline RETURNS instead of unwinding and the case silently becomes a duplicate
 * of the happy path, which the neighbouring audit tests already cover.</p>
 *
 * <p>No OpenMRS {@code Context} is installed, deliberately, as in
 * {@code ChartSearchAiStreamEventOrderTest} — {@code streamAnswer} is contract-bound to be free of
 * {@code Context} reads and a fallback audit write that re-added one would throw here rather than
 * read a leaked stub. See {@code RestControllerContext}'s javadoc for why that absence is the
 * enforcement.</p>
 */
public class ChartSearchAiStreamDisconnectAuditTest {

	private static final String QUESTION = "is she still on isoniazid?";

	/**
	 * The answer as the model emits it — SEVERAL fragments, which is what makes the accumulation
	 * observable. With one fragment "the answer produced so far" and "the last fragment written" are
	 * the same string, and a fallback that recorded only the latest token would pass.
	 */
	private static final String[] FRAGMENTS = { "Yes, ", "isoniazid ", "since 3 March [8]." };

	private static final String ANSWER = "Yes, isoniazid since 3 March [8].";

	/**
	 * One instance, held: {@code RestControllerContext.user()} mints a fresh {@link User} per call
	 * and neither of them has an id, so comparing the row's user to a second call would compare two
	 * objects that are equal to nothing including each other.
	 */
	private static final User USER = RestControllerContext.user();

	private ChartSearchAiRestController controller;

	private CapturingAuditLogService audit;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		audit = new CapturingAuditLogService();
		controller.setAuditLogService(audit);
		controller.setPatientAccessCheck((user, patient) -> true);
	}

	/**
	 * The ticket's first verification scenario: reset the socket after the first token. Everything
	 * the pipeline had produced by then is on the record.
	 */
	@Test
	public void aResetAfterTheFirstTokenStillAuditsTheQuery() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			DisconnectingSink gone = streamWith(new StreamingStub(), 1, asyncGrounding);

			assertTrue(gone.refused >= 1, canary(asyncGrounding));
			assertEquals(1, audit.saved.size(),
					"a disconnect mid-answer must leave exactly one audit row, not none and not two;"
							+ " asyncGrounding=" + asyncGrounding);
			ChartSearchAuditLog row = audit.saved.get(0);
			assertSame(USER, row.getUser(),
				"the row must name the user the request authenticated as");
			assertSame(StreamingStub.PATIENT, row.getPatient());
			assertEquals(QUESTION, row.getQuestion());
			assertEquals(FRAGMENTS[0] + FRAGMENTS[1], row.getAnswer(),
					"the row records the answer the pipeline had PRODUCED when the stream ended — the "
							+ "fragment whose write was refused included, since over-recording is the "
							+ "safe direction for an audit trail. Two fragments and not one: a fallback "
							+ "that kept only the latest token would pass on one");
			audit.saved.clear();
		}
	}

	/**
	 * The ticket's second verification scenario, and the one that obtains the whole answer: reset in
	 * the window after the last {@code token} frame and before the {@code references} frame.
	 */
	@Test
	public void aResetOnTheReferencesFrameStillAuditsTheWholeAnswer() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			DisconnectingSink gone = streamWith(new StreamingStub(), FRAGMENTS.length, asyncGrounding);

			assertTrue(gone.refused >= 1, canary(asyncGrounding));
			assertEquals(Collections.nCopies(FRAGMENTS.length, "token"),
					SseEvents.types(gone.sink()),
					"the refused frame must be the references one, or this case is not the window the "
							+ "ticket describes; asyncGrounding=" + asyncGrounding);
			assertEquals(1, audit.saved.size(),
					"the whole answer was delivered, so exactly one row must record it;"
							+ " asyncGrounding=" + asyncGrounding);
			assertEquals(ANSWER, audit.saved.get(0).getAnswer());
			assertEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN,
					audit.saved.get(0).getSearchMode(),
					"a stated residue rather than an oversight: the mode travels on the answer, and the "
							+ "pipeline has not surfaced one by this point in the stream, so the row "
							+ "says unknown rather than claiming a mode nobody handed the controller");
			audit.saved.clear();
		}
	}

	/**
	 * The other way the answer is delivered and the row is not: the pipeline finishes the answer,
	 * hands it over, and then something in the grounding / fidelity tail throws. That tail has no
	 * catch of its own, so it unwinds into the controller with the answer already on the wire.
	 *
	 * <p>Two things at once. It is the case that tells a {@code finally} from a statement at the tail
	 * of the catch-all's disconnect branch — this exception's cause is not an {@code IOException}, so
	 * it takes the other branch. And it is the case where the controller is HOLDING the pipeline's own
	 * answer: the ungrounded consumer fires in both shapes, by that interface's contract, so the row
	 * here states the mode and the reference count the pipeline resolved rather than the blanks a
	 * hand-built answer would file.</p>
	 */
	@Test
	public void aFailureAfterTheAnswerIsCompleteAuditsThePipelinesOwnAnswer() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			controller.setChartSearchService(new FailsAfterTheHandoffStub());

			controller.streamAnswer(out, StreamingStub.PATIENT, QUESTION,
					USER, asyncGrounding);

			assertEquals(1, audit.saved.size(),
					"a failure after the answer was delivered must still leave exactly one row;"
							+ " asyncGrounding=" + asyncGrounding);
			ChartSearchAuditLog row = audit.saved.get(0);
			assertEquals(ANSWER, row.getAnswer());
			assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, row.getSearchMode(),
					"the controller was handed the pipeline's own answer before the failure, so the row "
							+ "must state the mode that answer carries, not the unknown a hand-built one "
							+ "would file; asyncGrounding=" + asyncGrounding);
			assertNotEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN, row.getSearchMode());
			assertEquals(Integer.valueOf(1), row.getReferenceCount(),
					"and its references, for the same reason");
			audit.saved.clear();
		}
	}

	/**
	 * The negative: a query that failed BEFORE the model produced anything writes no row, exactly as
	 * today. Nothing was disclosed and no inference ran, so there is nothing to record — and without
	 * this case the gate on the fallback could be deleted, and the module would quietly start
	 * auditing, and rate-limiting, queries that ran no inference at all.
	 */
	@Test
	public void aFailureBeforeTheModelProducedAnythingWritesNoRow() {
		for (RuntimeException failure : new RuntimeException[] {
				new ChartTooLargeException("chart too large"),
				new IllegalStateException("chart search is not configured") }) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			controller.setChartSearchService(new FailsBeforeInferenceStub(failure));

			controller.streamAnswer(out, StreamingStub.PATIENT, QUESTION,
					USER, false);

			assertEquals(0, audit.saved.size(),
					"no row for a query that produced nothing; got one after "
							+ failure.getClass().getSimpleName());
		}
	}

	private DisconnectingSink streamWith(ChartSearchService service, int acceptEventFrames,
			boolean asyncGrounding) {
		DisconnectingSink gone = new DisconnectingSink(acceptEventFrames);
		controller.setChartSearchService(service);
		controller.streamAnswer(gone, StreamingStub.PATIENT, QUESTION,
				USER, asyncGrounding);
		return gone;
	}

	private static String canary(boolean asyncGrounding) {
		return "a frame must actually have been refused, or this case proves nothing: with nothing "
				+ "refused streamAnswer RETURNS instead of unwinding and audits on its ordinary path, "
				+ "which the neighbouring audit tests already cover; asyncGrounding=" + asyncGrounding;
	}

	/**
	 * A sink that accepts a chosen number of EVENT frames and refuses every later one, so a case can
	 * choose where in the stream the peer went away.
	 *
	 * <p>A frame is recognised as an event by NOT opening with the {@code :} of an SSE comment, rather
	 * than by matching {@code event:}, so a future frame shape the controller writes is refused too
	 * instead of quietly turning these cases green — the reason
	 * {@code ChartSearchAiStreamKeepAliveTest.DisconnectedClientSink} gives for its own inverse of
	 * this test. Keep-alive comments pass through, which is what keeps the interval out of the
	 * arithmetic.</p>
	 */
	private static final class DisconnectingSink extends OutputStream {

		private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

		private final int acceptEventFrames;

		private int eventFramesSeen;

		/** Read after {@code streamAnswer} returns, on the thread that wrote every event frame. */
		int refused;

		DisconnectingSink(int acceptEventFrames) {
			this.acceptEventFrames = acceptEventFrames;
		}

		@Override
		public void write(int b) {
			sink.write(b);
		}

		@Override
		public void write(byte[] frame, int off, int len) throws IOException {
			if (len > 0 && frame[off] != ':' && eventFramesSeen++ >= acceptEventFrames) {
				refused++;
				throw new IOException("client gone");
			}
			sink.write(frame, off, len);
		}

		ByteArrayOutputStream sink() {
			return sink;
		}
	}

	/**
	 * Streams the answer in {@link #FRAGMENTS}, then the citations, then hands over the ungrounded
	 * answer and returns the final one — the shape {@code LlmInferenceService.searchStreaming}
	 * produces, with the two answers as separate objects as they are there.
	 */
	private static class StreamingStub implements ChartSearchService {

		static final Patient PATIENT = RestControllerContext.patient();

		static ChartAnswer answer() {
			return new ChartAnswer(ANSWER,
					Arrays.asList(new RecordReference(8, "drug_order", "u8", null, Boolean.TRUE)),
					0, 0, 0, Collections.emptyList(),
					ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED);
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
			for (String fragment : FRAGMENTS) {
				tokenConsumer.accept(fragment);
			}
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	/** Finishes the answer, hands it over, then fails in the tail that has no catch of its own. */
	private static final class FailsAfterTheHandoffStub extends StreamingStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			super.searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
					citationsConsumer, ungroundedAnswerConsumer);
			throw new RuntimeException("the grounding pass failed");
		}
	}

	/** Fails before the model produces anything, as a too-large chart or a misconfiguration does. */
	private static final class FailsBeforeInferenceStub extends StreamingStub {

		private final RuntimeException failure;

		FailsBeforeInferenceStub(RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			throw failure;
		}
	}
}
