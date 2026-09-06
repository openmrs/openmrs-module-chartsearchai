/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The positive control for {@link LogCapture#hasEventAtOrAbove(Level, String)}, added with it in
 * issue #337's third round.
 *
 * <p><b>Why this needs a test of its own.</b> Every use of that arity is a NEGATIVE assertion — a
 * case claiming its own check said nothing, while capturing the whole package so the pipeline's own
 * INFO line proves the capture is live. An exclusion that matched too much would make every one of
 * those pass whatever was logged, and the suite would stay green while those cases stopped testing
 * anything. A helper whose only callers assert its answer is {@code false} cannot be covered by
 * those callers.
 *
 * <p>It logs through real SLF4J loggers rather than fabricating events, so what is asserted is the
 * relation between a capture and the loggers beneath it — which is the thing the callers depend on.
 */
public class LogCaptureExclusionTest {

	private static final String PACKAGE = "org.openmrs.module.chartsearchai.logcapturetest";

	private static final String EXCLUDED = PACKAGE + ".Excluded";

	private static final String OTHER = PACKAGE + ".Other";

	/** A logger whose name has {@link #EXCLUDED} as a string prefix but is not beneath it. */
	private static final String LOOKALIKE = EXCLUDED + "Sibling";

	@Test
	public void theExcludedLoggersOwnWarnIsIgnored() {
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			LoggerFactory.getLogger(EXCLUDED).warn("from the excluded logger");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"precondition: the capture must have received the WARN at all, or the assertion "
							+ "below passes for the wrong reason. Captured: " + capture.describeAll());
			assertFalse(capture.hasEventAtOrAbove(Level.WARN, EXCLUDED),
					"the named logger's own event must not count. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aWarnFromAnyOtherLoggerStillCounts() {
		// The control the negative callers cannot supply. An exclusion that swallowed everything
		// would satisfy all of them silently; this is the case that fails when it does.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			LoggerFactory.getLogger(EXCLUDED).warn("from the excluded logger");
			LoggerFactory.getLogger(OTHER).warn("from a logger nobody excluded");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN, EXCLUDED),
					"excluding one logger must not excuse the rest of the package — this is what the "
							+ "callers' reach over their neighbours rests on. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aLoggerBeneathTheExcludedOneIsExcludedToo() {
		// The relation is "is it, or is it beneath it" — the same one LogCapture.on captures by, so an
		// exclusion cannot cover less than a capture of the same name would.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			LoggerFactory.getLogger(EXCLUDED + ".Inner").warn("from beneath the excluded logger");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN), "precondition: the WARN was captured");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN, EXCLUDED),
					"a logger beneath the excluded one is excluded with it. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aLoggerMerelySHARINGThePrefixIsNotExcluded() {
		// The dot in the prefix test is what holds this: without it, excluding a check would also
		// excuse anything whose name merely begins with that check's name.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			LoggerFactory.getLogger(LOOKALIKE).warn("from a logger that only looks nested");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN, EXCLUDED),
					"a sibling sharing the excluded name as a string prefix is not beneath it and must "
							+ "still count. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aNullExclusionExcludesNothing() {
		// Not decoration: isFrom guards both operands, and a caller passing null must get the
		// unfiltered answer rather than a swallowed one.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			LoggerFactory.getLogger(OTHER).warn("from a logger nobody excluded");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN, null),
					"a null exclusion must not silence the capture. Captured: "
							+ capture.describeAll());
		}
	}
}
