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


import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * which is the argument {@code LogCapture}'s own javadoc makes about issue #149 (that class is in
 * the api module and out of reach here — see {@link ControllerLog}).
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

	private ChartSearchAiRestController controller;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setChartSearchService(new StreamingChartSearchStub());
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
		try (ControllerLog capture = new ControllerLog()) {
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

		try (ControllerLog capture = new ControllerLog()) {
			controller.search(RestControllerContext.searchBody("any infections?"));

			assertFalse(capture.hasEventAtOrAbove(Level.ERROR),
					"nothing is wrong on this path, so nothing may be reported at ERROR. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * The capture leaves the logger as it found it — no level pinned and, where it installed one, no
	 * config either.
	 *
	 * <p>Here rather than in the api module because {@code LogCaptureRestorationTest}, which pins the
	 * same property of {@code LogCapture}, cannot see {@link ControllerLog}. What a leftover config
	 * costs is in that class's javadoc; the short version is that it silently filters this logger out
	 * of any later capture of the package, and every negative asserted over one then passes on
	 * nothing but surefire's run order.
	 *
	 * <p>It starts from a KNOWN state rather than from whatever surefire ran before it: a sibling
	 * that had leaked a config would otherwise satisfy the precondition and the leak would go
	 * unmeasured, which is the same run-order vacuity the leak itself causes. And it asserts the
	 * config is installed WHILE open, so a close that did nothing cannot pass by having had nothing
	 * to undo.
	 */
	@Test
	public void aCloseLeavesNoLoggerConfigBehind() {
		ControllerLog.clearOwnConfig();
		assertFalse(ControllerLog.hasOwnConfigNow(), "precondition: no config of its own to start with");

		try (ControllerLog capture = new ControllerLog()) {
			assertTrue(ControllerLog.hasOwnConfigNow(),
					"an open capture installs one, which is what close has to undo — without this the case "
							+ "would pass on a close that did nothing");
		}

		assertFalse(ControllerLog.hasOwnConfigNow(),
				"a closed capture must leave no config behind; a leftover one filters this logger out of "
						+ "every later capture of the package, whatever level that capture asks for");
	}

	/** An audit log service whose persistence fails, as a full disk or a locked table would. */
	private static final class ThrowingAuditLogService extends StubAuditLogService {

		@Override
		public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
			throw new IllegalStateException("could not write the audit row");
		}
	}
}
