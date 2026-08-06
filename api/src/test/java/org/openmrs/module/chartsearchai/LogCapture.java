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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

/**
 * Captures what production code actually logs, so a test can assert the LEVEL an outcome is
 * reported at rather than only its return value.
 *
 * <p>Why this exists: a configured drug-reference source that resolves to zero entries used to be
 * reported exactly as a successful load — {@code log.info("Loaded {} ...", 0)} — which turned the
 * whole drug-safety feature off with nothing at default log levels to say so (issue #149). The
 * return value alone cannot pin that fix: an empty list is the correct fail-safe return in BOTH the
 * healthy-but-empty and the misconfigured case, so the only observable difference is the level. A
 * test that asserts on the WARN's message text instead would let a re-wording silently drop the
 * guard.
 *
 * <p>Attaches a collecting appender to the named logger and, because log4j2 appenders are inherited
 * by descendant loggers, sees everything logged under that name — pass a package name to capture a
 * whole package. The level is raised to {@code INFO} for the duration (via {@link Configurator}, so
 * the change lands on the shared logger CONFIG and therefore reaches descendant loggers —
 * {@code Logger.setLevel} would only affect the one instance) and is restored on {@link #close()}.
 * Capturing INFO as well as WARN matters for more than an INFO assertion: a capture that silently
 * received nothing at all would make "no WARN was logged" pass vacuously.
 *
 * <p>Use with try-with-resources; it is not thread-safe against a concurrent
 * {@link #close()} but the collected event list is.
 */
public final class LogCapture implements AutoCloseable {

	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<LogEvent>());

	private final String loggerName;

	private final Logger logger;

	private final Appender appender;

	private final Level restoreLevel;

	private LogCapture(String loggerName, Level level) {
		this.loggerName = loggerName;
		this.restoreLevel = ((Logger) LogManager.getLogger(loggerName)).getLevel();
		Configurator.setLevel(loggerName, level);
		this.logger = (Logger) LogManager.getLogger(loggerName);
		this.appender = new CollectingAppender(events);
		this.appender.start();
		this.logger.addAppender(appender);
	}

	/**
	 * @param loggerName a logger or package name; events from it and every logger beneath it are
	 *            captured
	 */
	public static LogCapture on(String loggerName) {
		return new LogCapture(loggerName, Level.INFO);
	}

	/**
	 * {@link #on(String)} at a level of the caller's choosing, for the outputs whose only surface is a
	 * line logged BELOW info — issue #163's injected-character total, which exists precisely because the
	 * REST response cannot show the size of the reference slice, so a test has no other way to observe
	 * it.
	 *
	 * <p>{@code INFO} stays the default rather than becoming a parameter everywhere, because the reason
	 * for it is specific to the assertions this class was built for: see the class javadoc — capturing
	 * INFO alongside WARN is what stops "no WARN was logged" passing vacuously. A caller lowering the
	 * level gets strictly more events, so that protection is not weakened, only widened.
	 *
	 * @param loggerName as {@link #on(String)}
	 * @param level the level to raise the logger CONFIG to for the duration; restored on {@link #close()}
	 */
	public static LogCapture on(String loggerName, Level level) {
		return new LogCapture(loggerName, level);
	}

	/** @return true when at least one captured event was logged at {@code level} or more severe. */
	public boolean hasEventAtOrAbove(Level level) {
		synchronized (events) {
			for (LogEvent event : events) {
				if (event.getLevel().isMoreSpecificThan(level)) {
					return true;
				}
			}
		}
		return false;
	}

	/** @return the formatted messages captured at exactly {@code level}, in order. */
	public List<String> messagesAt(Level level) {
		List<String> out = new ArrayList<String>();
		synchronized (events) {
			for (LogEvent event : events) {
				if (level.equals(event.getLevel())) {
					out.add(event.getMessage().getFormattedMessage());
				}
			}
		}
		return out;
	}

	/** @return every captured event rendered as {@code LEVEL message}, for assertion failure text. */
	public List<String> describeAll() {
		List<String> out = new ArrayList<String>();
		synchronized (events) {
			for (LogEvent event : events) {
				out.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
			}
		}
		return out;
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		Configurator.setLevel(loggerName, restoreLevel);
		appender.stop();
	}

	private static final class CollectingAppender extends AbstractAppender {

		private final List<LogEvent> sink;

		CollectingAppender(List<LogEvent> sink) {
			super("chartsearchai-log-capture", null, null, true, Property.EMPTY_ARRAY);
			this.sink = sink;
		}

		@Override
		public void append(LogEvent event) {
			sink.add(event.toImmutable());
		}
	}
}
