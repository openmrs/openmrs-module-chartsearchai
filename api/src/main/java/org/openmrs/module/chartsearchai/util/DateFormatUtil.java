/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Shared date formatting utility for clinical text serializers.
 */
public final class DateFormatUtil {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private DateFormatUtil() {
	}

	public static String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		return date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate().format(DATE_FORMAT);
	}

	/**
	 * Today, rendered exactly as {@link #formatDate} renders every record date (UTC,
	 * {@code yyyy-MM-dd}). Single-sourced through {@code formatDate} on purpose: the LLM prompt
	 * puts this next to record dates and the model is asked to compare them, so a second date
	 * shape — or a second time zone — would turn "is this recent?" into guesswork.
	 */
	public static String today() {
		return formatDate(new Date());
	}

	public static Date toLegacyDate(LocalDate date) {
		if (date == null) {
			return null;
		}
		return Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
	}
}
