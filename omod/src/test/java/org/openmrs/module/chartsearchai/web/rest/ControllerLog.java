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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * What {@link ChartSearchAiRestController} logged, and at what LEVEL — for the cases in this
 * package whose subject is the level rather than the return value.
 *
 * <p><b>Deliberately not {@code org.openmrs.module.chartsearchai.LogCapture}</b>, which is the
 * repo's general instrument for this question and would be the obvious reuse. It lives in
 * {@code api/src/test}, and reaching it from here means publishing an api test-jar and depending
 * on it — which was tried and reverted on this change: it opens api's whole test classpath to
 * omod, and prose across both modules states the opposite as a load-bearing fact. Grep
 * {@code no api test-jar} for them; the one that decides is a PRODUCTION javadoc,
 * {@code DrugSafetyValidator}'s {@code StandingChartAlerts} factories, which are public because
 * {@code omod/pom.xml} declares none. One
 * level assertion does not buy that. So this asks the one question those cases need, over one
 * logger, and claims to be no general instrument — {@code LogCapture} is that, and its javadoc is
 * where the reasoning about levels as the only observable lives.
 *
 * <p>Shared rather than nested per test class, the reason {@code StubAuditLogService} and
 * {@code CapturingAuditLogService} each give about themselves: two copies let two files quietly
 * pin different answers to the same question, and this one is used by both a positive assertion
 * (an ERROR is reported) and a negative one (no log line carries the question or the answer).
 *
 * <p>It raises the logger CONFIG rather than the instance, for the reason {@code LogCapture}'s
 * javadoc gives: {@code Logger.setLevel} reaches only the one instance. It restores the prior
 * level on {@link #close()} — a leaked level alters whichever test class surefire runs next in
 * this reused JVM.
 */
final class ControllerLog implements AutoCloseable {

	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<LogEvent>());

	private final Logger logger;

	private final Level priorLevel;

	private final AbstractAppender appender;

	ControllerLog() {
		logger = (Logger) LogManager.getLogger(ChartSearchAiRestController.class);
		priorLevel = logger.getLevel();
		appender = new AbstractAppender("controller-log", null, null, false, Property.EMPTY_ARRAY) {

			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
	}

	/** Whether anything was logged at {@code level} or more severe. */
	boolean hasEventAtOrAbove(Level level) {
		synchronized (events) {
			for (LogEvent event : events) {
				if (event.getLevel().isMoreSpecificThan(level)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether an event at exactly {@code level} carries a throwable — the cause, not just the fact. */
	boolean hasThrowableAt(Level level) {
		synchronized (events) {
			for (LogEvent event : events) {
				if (level.equals(event.getLevel()) && event.getThrown() != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether one message at exactly {@code level} carries every one of {@code needles}.
	 *
	 * <p>For a POSITIVE control beside a negative assertion, which is the only thing a message match
	 * is for here: it names a line the logger writes at the captured level, so "nothing was logged
	 * carrying the question" cannot pass on a capture that received nothing. A level assertion is
	 * what a rule about loudness uses — see {@link #hasEventAtOrAbove}.
	 */
	boolean hasMessageAt(Level level, String... needles) {
		synchronized (events) {
			for (LogEvent event : events) {
				if (!level.equals(event.getLevel())) {
					continue;
				}
				String message = event.getMessage().getFormattedMessage();
				boolean all = true;
				for (String needle : needles) {
					all = all && message.contains(needle);
				}
				if (all) {
					return true;
				}
			}
		}
		return false;
	}

	/** Every captured event as {@code LEVEL message [thrown TYPE: message]}, for failure text. */
	List<String> describeAll() {
		List<String> out = new ArrayList<String>();
		synchronized (events) {
			for (LogEvent event : events) {
				Throwable thrown = event.getThrown();
				out.add(event.getLevel() + " " + event.getMessage().getFormattedMessage()
						+ (thrown == null ? "" : " [thrown " + thrown.getClass().getName() + ": "
								+ thrown.getMessage() + "]"));
			}
		}
		return out;
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		appender.stop();
		logger.setLevel(priorLevel);
	}
}
