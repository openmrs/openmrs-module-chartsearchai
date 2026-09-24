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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;

/**
 * Issue #451 — which failures of a STREAMING answer are the client hanging up, and which are the
 * module failing to answer.
 *
 * <p>The terminal handler of {@code streamAnswer} used to decide that by asking whether the
 * failure's cause was an {@link IOException}. Both engines wrap every transport failure as
 * {@code new APIException(message, ioException)}, so an inference endpoint that dropped the
 * connection mid-answer was logged at DEBUG as a client disconnect and the client was sent no
 * {@code error} event: the answer stopped mid-sentence and nobody was told. And the same test read a
 * refused write of the final {@code done} or {@code grounded} frame as a disconnect only when the
 * container happened to wrap that {@code IOException} in another one, as Tomcat's
 * {@code ClientAbortException} does.
 *
 * <p>These cases drive {@code streamAnswer}, the production entry point the package's other
 * streaming tests use, with no OpenMRS {@code Context} installed — see
 * {@code ChartSearchAiStreamDisconnectAuditTest}'s javadoc for why that absence is deliberate.
 */
public class ChartSearchAiStreamFailureClassificationTest {

	private static final User USER = RestControllerContext.user();

	private static final String DISCONNECT_LINE = "Streaming ended due to client disconnect";

	private ChartSearchAiRestController controller;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new CapturingAuditLogService());
		controller.setPatientAccessCheck((user, patient) -> true);
	}

	/**
	 * The ticket's own reproduction: the inference endpoint hangs up after the first token. The
	 * client must be told the answer failed, and the operator must be told why, with the cause.
	 */
	@Test
	public void anInferenceEndpointThatHangsUpMidAnswerIsReportedAsAFailure() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			try (ControllerLog capture = new ControllerLog()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				controller.setChartSearchService(new EndpointHangsUpAfterATokenStub());

				controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
						StreamingChartSearchStub.QUESTION, USER, asyncGrounding);

				assertEquals(Arrays.asList("token", "error"), SseEvents.types(out),
						"a failed inference must end the stream with an error event, not in silence;"
								+ " asyncGrounding=" + asyncGrounding);
				Throwable logged = capture.thrownWith(Level.ERROR, "Chart search streaming failed",
						"[id=" + StreamingChartSearchStub.PATIENT.getPatientId() + "]");
				assertTrue(logged instanceof APIException
						&& logged.getCause() == EndpointHangsUpAfterATokenStub.RESET,
						"the operator must get an ERROR naming the patient and carrying the engine's "
								+ "failure with its transport cause. Captured: " + capture.describeAll());
				assertFalse(capture.hasMessageAt(Level.DEBUG, DISCONNECT_LINE),
						"the client did not disconnect, and no line may say it did. Captured: "
								+ capture.describeAll());
			}
		}
	}

	/** A client gone on the first token frame — the incremental channels' write site. */
	@Test
	public void aClientGoneOnATokenFrameIsADisconnect() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			assertQuietDisconnect(0, asyncGrounding, "token");
		}
	}

	/** A client gone on the {@code references} frame, which {@code sendReferencesEvent} composes. */
	@Test
	public void aClientGoneOnTheReferencesFrameIsADisconnect() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			assertQuietDisconnect(StreamingChartSearchStub.FRAGMENTS.length, asyncGrounding,
					"references");
		}
	}

	/** A client gone on the early {@code done} the async shape writes from its handoff consumer. */
	@Test
	public void aClientGoneOnTheEarlyDoneFrameIsADisconnect() {
		assertQuietDisconnect(StreamingChartSearchStub.FRAGMENTS.length + 1, true, "done");
	}

	/** A client gone on the classic shape's single {@code done}, the last frame it writes. */
	@Test
	public void aClientGoneOnTheClassicDoneFrameIsADisconnect() {
		assertQuietDisconnect(StreamingChartSearchStub.FRAGMENTS.length + 1, false, "done");
	}

	/** A client gone on the async shape's trailing {@code grounded}, the last frame it writes. */
	@Test
	public void aClientGoneOnTheGroundedFrameIsADisconnect() {
		assertQuietDisconnect(StreamingChartSearchStub.FRAGMENTS.length + 2, true, "grounded");
	}

	/**
	 * Refuses the {@code acceptEventFrames + 1}th event frame, which must be {@code refusedType}, and
	 * asserts the controller took that for what it is: a client that went away, logged at DEBUG, with
	 * no ERROR and no {@code error} event written to a socket nobody is reading.
	 */
	private void assertQuietDisconnect(int acceptEventFrames, boolean asyncGrounding,
			String refusedType) {
		String where = "; refused " + refusedType + ", asyncGrounding=" + asyncGrounding;
		try (ControllerLog capture = new ControllerLog()) {
			DisconnectingSink gone = new DisconnectingSink(acceptEventFrames);
			controller.setChartSearchService(new StreamingChartSearchStub());

			controller.streamAnswer(gone, StreamingChartSearchStub.PATIENT,
					StreamingChartSearchStub.QUESTION, USER, asyncGrounding);

			List<String> expected = Arrays.asList("token", "token", "token", "references", "done",
					"grounded").subList(0, acceptEventFrames);
			assertEquals(expected, SseEvents.types(gone.sink()),
					"precondition: the frames before the refused one must all have landed" + where);
			assertEquals(1, gone.refused,
					"exactly the one refused frame: a second refusal is an error event the controller "
							+ "tried to write to a client that had already gone" + where);
			assertFalse(capture.hasEventAtOrAbove(Level.ERROR),
					"a client hanging up is not a failure of the module. Captured: "
							+ capture.describeAll() + where);
			assertTrue(capture.hasMessageAt(Level.DEBUG, DISCONNECT_LINE),
					"and it is the disconnect line that says what happened. Captured: "
							+ capture.describeAll() + where);
		}
	}

	/**
	 * Streams one token, then fails the way both engines report a transport failure: an
	 * {@link APIException} whose cause is the {@link IOException} the HTTP read raised — the
	 * {@code catch (IOException e)} in {@code RemoteLlmEngine.inferStreaming} and
	 * {@code LocalLlmEngine.inferStreaming}.
	 */
	private static final class EndpointHangsUpAfterATokenStub extends StreamingChartSearchStub {

		static final IOException RESET = new IOException("Connection reset");

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(FRAGMENTS[0]);
			throw new APIException("Failed to call remote LLM API: " + RESET.getMessage(), RESET);
		}
	}
}
