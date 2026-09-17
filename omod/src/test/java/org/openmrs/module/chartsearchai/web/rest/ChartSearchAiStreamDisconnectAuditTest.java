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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
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
 * <p>The row used to be a statement on the success path, so a user holding <em>AI Query Patient
 * Data</em> could read a streamed answer about any patient, reset the socket, and leave no record of
 * who asked what about whom. ADR Decision 103 is canonical for why that happened and for what the
 * fix files; these cases are what pins it.
 *
 * <p>They drive {@code streamAnswer} — the production entry point every audit test in this package
 * uses — over {@link DisconnectingSink}, and each disconnect case asserts the sink actually refused
 * something: without that the pipeline RETURNS instead of unwinding and the case silently becomes a
 * duplicate of the happy path, which the neighbouring audit tests already cover.
 *
 * <p>No OpenMRS {@code Context} is installed, deliberately, as in
 * {@code ChartSearchAiStreamEventOrderTest} — {@code streamAnswer} is contract-bound to be free of
 * {@code Context} reads and a fallback audit write that re-added one would throw here rather than
 * read a leaked stub. See {@code RestControllerContext}'s javadoc for why that absence is the
 * enforcement.
 */
public class ChartSearchAiStreamDisconnectAuditTest {

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
			DisconnectingSink gone = streamWith(new StreamingChartSearchStub(), 1, asyncGrounding);

			assertTrue(gone.refused >= 1, canary(asyncGrounding));
			assertEquals(1, audit.saved.size(),
					"a disconnect mid-answer must leave exactly one audit row, not none and not two;"
							+ " asyncGrounding=" + asyncGrounding);
			ChartSearchAuditLog row = audit.saved.get(0);
			assertSame(USER, row.getUser(),
					"the row must name the user the request authenticated as");
			assertSame(StreamingChartSearchStub.PATIENT, row.getPatient());
			assertEquals(StreamingChartSearchStub.QUESTION, row.getQuestion());
			assertEquals(StreamingChartSearchStub.FRAGMENTS[0] + StreamingChartSearchStub.FRAGMENTS[1],
					row.getAnswer(),
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
			DisconnectingSink gone = streamWith(new StreamingChartSearchStub(),
					StreamingChartSearchStub.FRAGMENTS.length, asyncGrounding);

			assertTrue(gone.refused >= 1, canary(asyncGrounding));
			assertEquals(Collections.nCopies(StreamingChartSearchStub.FRAGMENTS.length, "token"),
					SseEvents.types(gone.sink()),
					"the refused frame must be the references one, or this case is not the window the "
							+ "ticket describes; asyncGrounding=" + asyncGrounding);
			assertEquals(1, audit.saved.size(),
					"the whole answer was delivered, so exactly one row must record it;"
							+ " asyncGrounding=" + asyncGrounding);
			assertEquals(StreamingChartSearchStub.ANSWER, audit.saved.get(0).getAnswer());
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
	 * hand-built answer would file.
	 */
	@Test
	public void aFailureAfterTheAnswerIsCompleteAuditsThePipelinesOwnAnswer() {
		for (boolean asyncGrounding : new boolean[] { false, true }) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			controller.setChartSearchService(new FailsAfterTheHandoffStub());

			controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
					StreamingChartSearchStub.QUESTION, USER, asyncGrounding);

			assertEquals(1, audit.saved.size(),
					"a failure after the answer was delivered must still leave exactly one row;"
							+ " asyncGrounding=" + asyncGrounding);
			ChartSearchAuditLog row = audit.saved.get(0);
			assertEquals(StreamingChartSearchStub.ANSWER, row.getAnswer());
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
	 * The earliest window a stock install has, and one no answer token reaches: {@code thinking} is the
	 * first frame the module writes unless the progressive-reasoning preview is enabled, which ships
	 * off. A client that goes away on it has had chart-derived reasoning about the patient and nothing
	 * else. That is still a disclosure, and the row that records it is owed.
	 *
	 * <p>It is the {@code reasoning} channel alone that carries the query past the gate here, which is
	 * why this case exists as well as the token ones: with every fixture streaming a token first, the
	 * gate could be satisfied by the token channel alone and nothing would notice.
	 */
	@Test
	public void aClientGoneBeforeTheFirstTokenIsStillAuditedOffTheThinkingFrame() {
		DisconnectingSink gone = streamWith(new ReasoningFirstStub(), 0, false);

		assertTrue(gone.refused >= 1, canary(false));
		assertEquals(Collections.emptyList(), SseEvents.types(gone.sink()),
				"nothing may have reached the client, or a later channel could be what audited this");
		assertEquals(1, audit.saved.size(), "the reasoning frame alone must leave a row");
		assertEquals("", audit.saved.get(0).getAnswer(),
				"and it records no answer, because the model had produced none — the row's subject is "
						+ "the question and the patient, which are both on it");
		assertEquals(StreamingChartSearchStub.QUESTION, audit.saved.get(0).getQuestion());
	}

	/**
	 * A chart too large for the model, discovered after the progressive-reasoning preview has already
	 * been streamed. The preview runs over a focused top-K slice and the committed pass over the whole
	 * chart, so the preview is precisely what succeeds when the full chart overflows — the two are
	 * positively correlated, not merely co-possible.
	 *
	 * <p>So this query IS audited, and ADR Decision 103 says so: the preview is model output about
	 * this patient and it reached the client. It also consumes a rate-limit slot, which is the
	 * consequence that decision names rather than hides.
	 */
	@Test
	public void aChartTooLargeDiscoveredAfterThePreviewIsStillAudited() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.setChartSearchService(new PreviewThenTooLargeStub());

		controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
				StreamingChartSearchStub.QUESTION, USER, false);

		assertTrue(SseEvents.types(out).contains("preliminary"),
				"the preview must have reached the client, or this is not the window under test. Got "
						+ SseEvents.types(out));
		assertEquals(1, audit.saved.size(),
				"a preview delivered about this patient is a disclosure, so the row is owed even though "
						+ "the answer never arrived");
		assertEquals("", audit.saved.get(0).getAnswer());
	}

	/**
	 * The citations channel, on its own — a shipped arrangement and not merely one the interface
	 * permits. {@code LlmResponseParser} fires the token consumer only for a non-empty content chunk
	 * and {@code LlmProvider} filters it to the {@code answer} field, so a blank answer streams ZERO
	 * token frames while {@code citationsConsumer} runs unconditionally; the repo treats that case as
	 * real, pinned by {@code FindingEnumerationRepairTest.aBlankAnswerIsNotRepairedAtAll}. The
	 * citations of a blank answer still resolve, off the structured array.
	 */
	@Test
	public void theCitationsChannelAloneIsEnoughToAuditTheQuery() {
		DisconnectingSink gone = streamWith(new CitationsOnlyStub(), 1, false);

		// Not the shared canary: here the references frame LANDS and what the sink refuses is the error
		// event the catch-all writes afterwards. What this case needs is that no token preceded the
		// citations, which is what the event list says.
		assertEquals(Collections.singletonList("references"), SseEvents.types(gone.sink()),
				"the citations frame must be the only thing that reached the client, or a token channel "
						+ "could be what audited this");
		assertEquals(1, audit.saved.size(), "the citations frame alone must leave a row");
		assertEquals("", audit.saved.get(0).getAnswer());
	}

	/**
	 * A save that THREW is not an attempt that succeeded, and the {@code finally} is the only thing
	 * left to write the row — so the flag each ordinary write site raises goes up AFTER its save
	 * returns, never before it.
	 *
	 * <p>Reached here by a service that streams a token and then returns null, which makes
	 * {@code saveAuditLog} dereference a null answer and throw before its own {@code try}. Neither
	 * shipped implementation returns null; this is collaborator misbehaviour, held to the same standard
	 * as {@link #aSecondUngroundedHandoffWritesNoSecondRow} and for the same reason — a token about
	 * this patient reached the client, and with the flag raised early the query went unrecorded, which
	 * is issue #450's own defect reappearing inside its own fix.
	 */
	@Test
	public void aSaveThatThrewLeavesTheFinallyToWriteTheRow() {
		// One service per shape, because each site is reached by a different misbehaviour: the classic
		// site audits the RETURNED answer, so a null return is what makes its save throw, and the async
		// site audits the answer it is HANDED, so a null handoff is what makes that one throw. A single
		// stub reaches only one of them, and the async site then goes unmeasured.
		for (ChartSearchService misbehaving : new ChartSearchService[] {
				new ReturnsNullAfterATokenStub(), new HandsOverNullAfterATokenStub() }) {
			boolean asyncGrounding = misbehaving instanceof HandsOverNullAfterATokenStub;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			controller.setChartSearchService(misbehaving);

			controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
					StreamingChartSearchStub.QUESTION, USER, asyncGrounding);

			assertEquals(1, audit.saved.size(),
					"the answer reached the client and the ordinary save threw, so the finally owes the "
							+ "row; service=" + misbehaving.getClass().getSimpleName());
			assertEquals(StreamingChartSearchStub.FRAGMENTS[0], audit.saved.get(0).getAnswer(),
					"and it records what the model had produced");
			audit.saved.clear();
		}
	}

	/**
	 * The handoff as the FIRST channel to speak, in the classic shape — the one arrangement in which
	 * {@code recordAnswerOnce} is what opens the gate rather than a token or a frame before it.
	 */
	@Test
	public void theHandoffAloneIsEnoughToAuditTheQuery() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.setChartSearchService(new HandoffOnlyStub());

		controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
				StreamingChartSearchStub.QUESTION, USER, false);

		assertEquals(1, audit.saved.size(), "the handoff alone must leave a row");
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				audit.saved.get(0).getSearchMode(),
				"off the answer it handed over, which is the only thing the controller holds here");
	}

	/**
	 * An answer whose TEXT is null, handed over and then abandoned. The row records the null, and —
	 * the point of the case — reading its length for the log line does not throw: this runs in a
	 * {@code finally} on a request whose own failure has already been handled, so an exception here
	 * would leave {@code streamAnswer} by a path its caller has no catch for.
	 */
	@Test
	public void aPipelineAnswerWithNoTextIsAuditedWithoutThrowingOutOfTheFinally() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.setChartSearchService(new NullTextAnswerStub());

		controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
				StreamingChartSearchStub.QUESTION, USER, false);

		assertEquals(1, audit.saved.size(), "the query is still recorded");
		assertNull(audit.saved.get(0).getAnswer(),
				"the row states the null the producer stated, rather than inventing an empty answer");
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

			controller.streamAnswer(out, StreamingChartSearchStub.PATIENT,
					StreamingChartSearchStub.QUESTION, USER, false);

			assertEquals(0, audit.saved.size(),
					"no row for a query that produced nothing; got one after "
							+ failure.getClass().getSimpleName());
		}
	}

	/**
	 * One row per query holds for ANY implementation, not only for one honouring the ungrounded
	 * consumer's at-most-once contract.
	 *
	 * <p>The shape that would otherwise write two: async grounding on, the early {@code done} write
	 * refused, and a service that swallows the {@code RuntimeException} that failure raises and fires
	 * the consumer again. Neither shipped implementation does that — {@code LlmInferenceService} and
	 * {@code ChartSearchServiceRouter} each call the consumer once with no surrounding try — so this
	 * pins a guard rather than fixing an observed defect. It is worth pinning anyway: the audit
	 * suites' "exactly one row" is a specification about the TABLE, and the module should not owe it
	 * to a collaborator's good behaviour.
	 */
	@Test
	public void aSecondUngroundedHandoffWritesNoSecondRow() {
		DisconnectingSink gone = streamWith(new FiresTheHandoffTwiceStub(), 0, true);

		assertTrue(gone.refused >= 1,
				"the early done write must have been refused, or the second fire is not the shape "
						+ "under test");
		assertEquals(1, audit.saved.size(),
				"a second handoff after a refused done must not write a second row");
	}

	/**
	 * The line this path writes carries one count and the patient's id — never the question and never
	 * a word of the answer. Issue #439 established that rule for a diagnostic about a patient, and
	 * this is a new site bound by it: an audit row exists precisely so the question and the answer
	 * live in a table behind <em>View AI Audit Logs</em> rather than in a server log, which a wider
	 * audience reads.
	 *
	 * <p>Captured from DEBUG up and asserted over EVERY captured event, rendered with its throwables:
	 * that is the shape issue #439's own review rounds arrived at, having lost the same guard three
	 * times over — once to a capture raised to WARN while the details went out at {@code info}, once
	 * to a renderer that showed a throwable's type and not its message, and once to a sibling's
	 * leftover logger config.
	 *
	 * <p>The first assertion is the positive control, and a negative over a capture needs one: it
	 * names a line this logger writes at the captured level, so the two negatives cannot pass on a
	 * capture that received nothing. It fails on a re-wording of that line, which is the intended
	 * cost of being the control rather than a claim about the wording.
	 */
	@Test
	public void theAuditLineForAnEndedStreamNamesNoQuestionAndNoAnswerText() {
		try (ControllerLog capture = new ControllerLog()) {
			DisconnectingSink gone = streamWith(new StreamingChartSearchStub(), 1, false);

			assertTrue(gone.refused >= 1, canary(false));
			assertEquals(1, audit.saved.size(),
					"precondition: this must be the exit that writes the line under test");
			assertTrue(capture.hasMessageAt(Level.INFO, "ended before its audit row was written",
					"[id=" + StreamingChartSearchStub.PATIENT.getPatientId() + "]"),
					"the control: the line must have arrived, at the level it is written at, naming the "
							+ "patient by id. Captured: " + capture.describeAll());

			String logged = capture.describeAll().toString();
			assertFalse(logged.contains(StreamingChartSearchStub.QUESTION),
					"no log line may carry the clinician's question. Captured: " + logged);
			// Per FRAGMENT and not over the whole answer, which would be the weaker assertion and is
			// implied by these: the pipeline produces the answer in pieces and a row written mid-stream
			// holds only some of them, so a line carrying what was produced so far contains no fragment
			// of a complete answer to match. Measured — logging the produced text at DEBUG reddens the
			// fragment assertion and leaves a whole-answer one green.
			for (String fragment : StreamingChartSearchStub.FRAGMENTS) {
				assertFalse(logged.contains(fragment.trim()),
						"nor a fragment of the answer. Captured: " + logged);
			}
		}
	}

	private DisconnectingSink streamWith(ChartSearchService service, int acceptEventFrames,
			boolean asyncGrounding) {
		DisconnectingSink gone = new DisconnectingSink(acceptEventFrames);
		controller.setChartSearchService(service);
		controller.streamAnswer(gone, StreamingChartSearchStub.PATIENT,
				StreamingChartSearchStub.QUESTION, USER, asyncGrounding);
		return gone;
	}

	private static String canary(boolean asyncGrounding) {
		return "a frame must actually have been refused, or this case proves nothing: with nothing "
				+ "refused streamAnswer RETURNS instead of unwinding and audits on its ordinary path, "
				+ "which the neighbouring audit tests already cover; asyncGrounding=" + asyncGrounding;
	}

	/** Finishes the answer, hands it over, then fails in the tail that has no catch of its own. */
	private static final class FailsAfterTheHandoffStub extends StreamingChartSearchStub {

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

	/** Streams the committed reasoning before any answer token, as the production order does. */
	private static final class ReasoningFirstStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			reasoningConsumer.accept("Her active orders include isoniazid.");
			return super.searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
					citationsConsumer, ungroundedAnswerConsumer);
		}
	}

	/**
	 * Streams a progressive-reasoning preview and then finds the committed chart too large. Overrides
	 * the SEVEN-arg overload, which is the only way to reach the preliminary channel at all — see
	 * {@link StreamingChartSearchStub}'s javadoc.
	 */
	private static final class PreviewThenTooLargeStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer,
				Consumer<String> preliminaryReasoningConsumer) {
			preliminaryReasoningConsumer.accept("Quick look: [8] is an isoniazid order.");
			throw new ChartTooLargeException("chart too large");
		}
	}

	/** Hands over citations with no token before them — what the interface permits. */
	private static final class CitationsOnlyStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			citationsConsumer.accept(answer().getReferences());
			throw new RuntimeException("the pipeline failed after citations");
		}
	}

	/** Violates the at-most-once contract, swallowing the first fire's write failure. */
	private static final class FiresTheHandoffTwiceStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			try {
				ungroundedAnswerConsumer.accept(answer());
			}
			catch (RuntimeException e) {
				// The refused done write, swallowed — which is the contract violation under test.
			}
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}
	}

	/** Streams a token and then returns null, so the ordinary save throws rather than swallowing. */
	private static final class ReturnsNullAfterATokenStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(FRAGMENTS[0]);
			return null;
		}
	}

	/** Streams a token and hands over a null answer, so the ASYNC save throws before its own try. */
	private static final class HandsOverNullAfterATokenStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(FRAGMENTS[0]);
			ungroundedAnswerConsumer.accept(null);
			return answer();
		}
	}

	/** Hands over the finished answer with nothing before it, then abandons the request. */
	private static final class HandoffOnlyStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			ungroundedAnswerConsumer.accept(answer());
			throw new RuntimeException("the grounding pass failed");
		}
	}

	/** Hands over an answer whose text is null, which a producer is free to do. */
	private static final class NullTextAnswerStub extends StreamingChartSearchStub {

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			ungroundedAnswerConsumer.accept(new ChartAnswer(null, Collections.emptyList()));
			throw new RuntimeException("the grounding pass failed");
		}
	}

	/** Fails before the model produces anything, as a too-large chart or a misconfiguration does. */
	private static final class FailsBeforeInferenceStub extends StreamingChartSearchStub {

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
