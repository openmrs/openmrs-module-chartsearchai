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
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Shared date formatting utility for clinical text serializers: a clinical date is the calendar day in the server's
 * zone. OpenMRS stores a date and time without an offset, in the zone the server runs in, and a day entered as a date is
 * stored at that zone's midnight; spelled in UTC, every such day read one earlier on a server east of UTC (the demo
 * replica runs at +03:00) than the chart shows it. A {@code LocalDate} becomes a {@code Date} at the same zone's midnight,
 * so the two methods round-trip a day whatever the zone.
 */
public final class DateFormatUtil {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private DateFormatUtil() {
	}

	public static String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT);
	}

	public static Date toLegacyDate(LocalDate date) {
		if (date == null) {
			return null;
		}
		return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
	}
}
