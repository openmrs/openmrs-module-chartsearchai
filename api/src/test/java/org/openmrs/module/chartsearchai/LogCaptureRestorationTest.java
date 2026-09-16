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
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What a closed {@link LogCapture} leaves behind — the property every PACKAGE-scoped capture in the
 * suite depends on, and the one nothing asserted until issue #439's third review round.
 *
 * <p><b>The defect.</b> {@code Configurator.setLevel(name, level)} does not raise a level on an
 * existing object where the named logger has no {@code LoggerConfig} of its own: it INSTALLS one for
 * that exact name. {@code close()} then set a level back on that installed config and left the config
 * in place for the rest of the JVM. A {@code LoggerConfig} for a descendant name wins over its
 * package's, so from that moment a capture of the PACKAGE could raise the package to {@code DEBUG}
 * and still not see that one class's {@code INFO} or {@code DEBUG} events — the appender was reached,
 * but the event was filtered before it. Two captures in different test FILES, and the trap was armed
 * only when the class-named one happened to run first: measured on
 * {@code PairChipCapContextTest.theScreeningWarnRatesTheWithheldPairsAtTheConfiguredCapAndNamesNoDrug}
 * against {@code UnreadableOrderDrugTest}, where an {@code INFO} probe naming every withheld pair was
 * caught under {@code -Dsurefire.runOrder=alphabetical} and invisible under
 * {@code reversealphabetical}. Surefire's default order is {@code filesystem}, so which it would have
 * been on a CI host was not a property of the code at all.
 *
 * <p><b>Why it is fixed here rather than in the guards.</b> The blind spot belongs to no one guard —
 * every capture of a package is exposed to it, by any sibling file that captures a logger beneath that
 * package by name, and the suite has both in quantity. A guard that only works under one test ORDER
 * is a guard whose other value was never built, and the shared helper is where that value is decided.
 * {@code FindingPartnerLogDisclosureTest}'s own PACKAGE javadoc records the same mechanism biting a
 * module-root capture from the other direction — a narrowing that file kept until this fix let it
 * name the root again.
 *
 * <p>It logs through real SLF4J loggers rather than fabricating events, as
 * {@link LogCaptureExclusionTest} does, because what is asserted is the relation between a capture
 * and log4j's own configuration.
 */
public class LogCaptureRestorationTest {

	private static final String PACKAGE = "org.openmrs.module.chartsearchai.logcapturerestorationtest";

	/** The logger a class-named capture takes, as the sibling test files take one. */
	private static final String SUBJECT = PACKAGE + ".Subject";

	/** A second fixture logger, used only by the case that pins a level outside any capture, so that
	 *  pin cannot reach another case here. */
	private static final String PINNED = PACKAGE + ".Pinned";

	@Test
	public void aClosedCaptureLeavesNoLevelPinnedOnTheLoggerItNamed() {
		try (LogCapture named = LogCapture.on(SUBJECT)) {
			LoggerFactory.getLogger(SUBJECT).warn("from the logger this capture names");
			assertTrue(named.hasEventAtOrAbove(Level.WARN),
					"precondition: the class-named capture works at all. Captured: "
							+ named.describeAll());
		}

		try (LogCapture wholePackage = LogCapture.on(PACKAGE, Level.DEBUG)) {
			LoggerFactory.getLogger(SUBJECT).warn("a WARN from the logger the earlier capture named");
			// The control that separates the two explanations, and it is the reviewer's own: with the
			// appender unreached NOTHING from this logger arrives, and every assertion below would be
			// about a capture that is simply dead. It is the LEVEL that was filtered.
			assertTrue(wholePackage.hasEventAtOrAbove(Level.WARN),
					"precondition: a package capture still reaches that logger's WARN. Captured: "
							+ wholePackage.describeAll());

			LoggerFactory.getLogger(SUBJECT).info("an INFO from the logger the earlier capture named");
			LoggerFactory.getLogger(SUBJECT).debug("a DEBUG from the logger the earlier capture named");

			assertTrue(hasMessage(wholePackage, "an INFO from the logger the earlier capture named"),
					"a capture of the package at DEBUG must see the INFO of a logger an earlier "
							+ "capture named — a level left pinned by that capture filters it before "
							+ "the appender, so every negative asserted over a DEBUG capture would "
							+ "pass vacuously. Captured: " + wholePackage.describeAll());
			assertTrue(hasMessage(wholePackage, "a DEBUG from the logger the earlier capture named"),
					"and its DEBUG, which is the level the disclosure guards capture at. Captured: "
							+ wholePackage.describeAll());
		}
	}

	@Test
	public void aLevelPinnedOutsideACaptureSurvivesOne() {
		// The other direction, and the reason close() cannot simply always remove the config: a level
		// the log4j configuration itself declares for one logger, or one a caller pinned deliberately,
		// is not this helper's to drop. OFF rather than a level near the inherited one, so the
		// assertion cannot pass because removal happened to restore the same effective level.
		Configurator.setLevel(PINNED, Level.OFF);
		try (LogCapture named = LogCapture.on(PINNED)) {
			// The pin does NOT hold while the capture is open, and saying otherwise here was wrong
			// until issue #439's fourth review round: the constructor raises the named logger to its
			// requested level unconditionally, so for the duration this logger is at INFO and not OFF.
			// That is what a capture is for — a helper that yielded to an existing pin could not
			// observe anything on a logger the configuration had turned down — so it is asserted
			// rather than merely noted. What survives the capture is the other half, and the package
			// capture below is where that is read.
			LoggerFactory.getLogger(PINNED).info("an INFO through the logger pinned OFF");
			assertTrue(hasMessage(named, "an INFO through the logger pinned OFF"),
					"a capture raises the level of the logger it names even where something had pinned "
							+ "it lower. Captured: " + named.describeAll());
		}

		try (LogCapture wholePackage = LogCapture.on(PACKAGE, Level.DEBUG)) {
			LoggerFactory.getLogger(PINNED).warn("a WARN from the logger pinned OFF");
			LoggerFactory.getLogger(SUBJECT).warn("a WARN from a logger nobody pinned");

			assertTrue(hasMessage(wholePackage, "a WARN from a logger nobody pinned"),
					"precondition: this capture is live. Captured: " + wholePackage.describeAll());
			assertFalse(hasMessage(wholePackage, "a WARN from the logger pinned OFF"),
					"a capture must not drop a level pinned on that logger before it opened. "
							+ "Captured: " + wholePackage.describeAll());
		}
		// The pin stays: it is this case's own fixture logger, named nowhere else, so leaving it is
		// cheaper than a second mechanism for taking it down — and taking it down is what the code
		// under test is being asserted NOT to do.
	}

	/** @return whether any captured event, at any level, carries {@code message}. */
	private static boolean hasMessage(LogCapture capture, String message) {
		for (String described : capture.describeAll()) {
			if (described.contains(message)) {
				return true;
			}
		}
		return false;
	}
}
