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
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * What {@link ChartSearchAiRestController} logged, and at what LEVEL — for the cases in this
 * package whose subject is the level rather than the return value.
 *
 * <p><b>Deliberately not {@code org.openmrs.module.chartsearchai.LogCapture}</b>, which is the
 * repo's general instrument for this question and would be the obvious reuse. It lives in
 * {@code api/src/test}, and reaching it from here means publishing an api test-jar and depending
 * on it — which was tried and reverted on this change: it opens api's whole test classpath to
 * omod, and prose in both modules states the opposite as a load-bearing fact. The one that decides
 * is a PRODUCTION javadoc: {@code DrugSafetyValidator}'s {@code StandingChartAlerts} factories are
 * public because {@code omod/pom.xml} declares no api test-jar. ADR Decision 103 names the rest. One
 * level assertion does not buy that. So this asks the one question those cases need, over one
 * logger, and claims to be no general instrument — {@code LogCapture} is that, and its javadoc is
 * where the reasoning about levels as the only observable lives.
 *
 * <p>Shared rather than nested per test class, the reason {@code StubAuditLogService} and
 * {@code CapturingAuditLogService} each give about themselves: two copies let two files quietly
 * pin different answers to the same question, and this one is used by both a positive assertion
 * (an ERROR is reported) and a negative one (no log line carries the question or the answer).
 *
 * <p><b>It raises the logger CONFIG, not the instance, and undoes an INSTALLED config rather than
 * only a level</b> — both of which it takes from {@code LogCapture}, which had each of them wrong
 * once. {@code Logger.setLevel} reaches only the one instance, which is why {@link Configurator} is
 * what raises the level here. And attaching an appender to a logger that has no config of its own
 * makes log4j2 install one: removing only the appender and the level then leaves that config behind
 * at its inherited level for the life of the JVM, so a later capture of the PACKAGE is blind to this
 * logger's events below it and every negative asserted over such a capture passes vacuously,
 * decided by nothing but which test file surefire ran first. That is issue #439's third review round,
 * pinned there by {@code LogCaptureRestorationTest} — which cannot see this class, so
 * {@code ChartSearchAiAuditWriteFailureLoudnessTest.aCloseLeavesNoLoggerConfigBehind} is what keeps
 * it pinned here.
 */
final class ControllerLog implements AutoCloseable {

	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<LogEvent>());

	/** The logger this captures, named rather than located so {@link #close} can undo a config. */
	static final String LOGGER_NAME = ChartSearchAiRestController.class.getName();

	private final Logger logger;

	private final Level priorLevel;

	private final boolean ownConfigExisted;

	private final AbstractAppender appender;

	ControllerLog() {
		Logger target = (Logger) LogManager.getLogger(LOGGER_NAME);
		priorLevel = target.getLevel();
		ownConfigExisted = hasOwnConfig(target.getContext().getConfiguration(), LOGGER_NAME);
		Configurator.setLevel(LOGGER_NAME, Level.DEBUG);
		logger = (Logger) LogManager.getLogger(LOGGER_NAME);
		appender = new AbstractAppender("controller-log", null, null, false, Property.EMPTY_ARRAY) {

			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		logger.addAppender(appender);
	}

	/** Whether {@code loggerName} resolves to a config of its OWN, rather than to an ancestor's. */
	static boolean hasOwnConfig(Configuration configuration, String loggerName) {
		LoggerConfig config = configuration.getLoggerConfig(loggerName);
		return config != null && loggerName.equals(config.getName());
	}

	/** Whether this logger has a config of its own right now — for the assertion about {@link #close}. */
	static boolean hasOwnConfigNow() {
		Logger target = (Logger) LogManager.getLogger(LOGGER_NAME);
		return hasOwnConfig(target.getContext().getConfiguration(), LOGGER_NAME);
	}

	/**
	 * Removes this logger's own config if it has one, so a case asserting about {@link #close} starts
	 * from a known state rather than from whatever surefire ran before it in this JVM.
	 *
	 * <p>Only that case should call this. Without it the assertion is decided by run order — a sibling
	 * that leaked a config satisfies the precondition, and the leak this is all about goes unmeasured.
	 */
	static void clearOwnConfig() {
		Logger target = (Logger) LogManager.getLogger(LOGGER_NAME);
		LoggerContext context = target.getContext();
		context.getConfiguration().removeLogger(LOGGER_NAME);
		context.updateLoggers();
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
		if (ownConfigExisted) {
			Configurator.setLevel(LOGGER_NAME, priorLevel);
		}
		else {
			// The config log4j2 installed when the appender was attached, removed rather than left at
			// its inherited level — see the class javadoc for what leaving it costs a later capture.
			LoggerContext context = logger.getContext();
			context.getConfiguration().removeLogger(LOGGER_NAME);
			context.updateLoggers();
		}
		appender.stop();
	}
}
