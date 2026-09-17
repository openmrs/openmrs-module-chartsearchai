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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #450, second half — how LOUD an audit write that failed is.
 *
 * <p>{@code saveAuditLog} swallows every persistence failure and returns null, and the answer is
 * delivered either way, so the level is the only thing that tells an operator that a clinician read
 * a patient's chart through this module and nothing recorded it. It was WARN, which on a default
 * OpenMRS install sits among ordinary operational noise; an access to PHI that went unrecorded is
 * not that. The return value cannot carry this: null is also what a caller sees when the row was
 * written by a DAO that assigns no id, so the level is the whole of the observable difference —
 * which is the argument {@link LogCapture}'s own javadoc makes about issue #149.
 *
 * <p>Asserted as a LEVEL and not as message text, for the reason that javadoc gives: a test matching
 * the wording would let a re-phrasing silently drop the guard. The throwable is asserted beside it
 * because a cause-less line leaves the operator the fact and not the diagnosis.
 *
 * <p>Driven through the blocking {@code /search} handler, which is the shortest path to the one write
 * site all four share — the rule is about {@code saveAuditLog}, not about either endpoint.
 * {@link ChartSearchAiStreamDisconnectAuditTest} covers the row's EXISTENCE on the streaming path.
 */
public class ChartSearchAiAuditWriteFailureLoudnessTest {

	/** The controller's own logger, so a neighbour's ERROR cannot answer for the one under test. */
	private static final String CONTROLLER_LOGGER = ChartSearchAiRestController.class.getName();

	private ChartSearchAiRestController controller;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setChartSearchService(new StubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	@Test
	public void aFailedAuditWriteIsReportedAtError() {
		controller.setAuditLogService(new ThrowingAuditLogService());

		ResponseEntity<Object> response;
		try (LogCapture capture = LogCapture.on(CONTROLLER_LOGGER)) {
			response = controller.search(RestControllerContext.searchBody("any infections?"));

			assertTrue(capture.hasEventAtOrAbove(Level.ERROR),
					"an unrecorded read of a patient's chart must reach the operator at ERROR, not sit "
							+ "at WARN among ordinary operational noise. Captured: " + capture.describeAll());
			assertTrue(capture.hasThrowableAt(Level.ERROR),
					"and with the cause attached, or the operator has the fact and not the diagnosis. "
							+ "Captured: " + capture.describeAll());
		}
		assertEquals(HttpStatus.OK, response.getStatusCode(),
				"the answer is still delivered: this module does not fail a clinician's query closed on "
						+ "an audit-write failure, which is the half of issue #450's second recommendation "
						+ "that is a policy choice rather than a defect");
	}

	/**
	 * The control, without which the case above could pass on an ERROR the handler logs for some
	 * other reason, or on a capture that is collecting everything.
	 */
	@Test
	public void anAuditWriteThatSucceededIsSilentAtError() {
		controller.setAuditLogService(new StubAuditLogService());

		try (LogCapture capture = LogCapture.on(CONTROLLER_LOGGER)) {
			controller.search(RestControllerContext.searchBody("any infections?"));

			assertFalse(capture.hasEventAtOrAbove(Level.ERROR),
					"nothing is wrong on this path, so nothing may be reported at ERROR. Captured: "
							+ capture.describeAll());
		}
	}

	/** An audit log service whose persistence fails, as a full disk or a locked table would. */
	private static final class ThrowingAuditLogService extends StubAuditLogService {

		@Override
		public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
			throw new IllegalStateException("could not write the audit row");
		}
	}

	/** The minimum a {@code /search} call needs to reach the audit write. */
	private static final class StubService implements ChartSearchService {

		private ChartAnswer answer() {
			return new ChartAnswer("Has TB [8].",
					Arrays.asList(new RecordReference(8, "condition", "u8", null, Boolean.TRUE)),
					0, 0, 0, Collections.emptyList(), "queryScoped");
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
