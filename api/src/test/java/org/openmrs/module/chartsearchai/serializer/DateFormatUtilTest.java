/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.Test;

public class DateFormatUtilTest {

	@Test
	public void formatDate_shouldReturnUnknownForNull() {
		assertEquals("unknown", DateFormatUtil.formatDate(null));
	}

	@Test
	public void formatDate_shouldFormatDateAsYyyyMmDd() {
		Calendar cal = Calendar.getInstance();
		cal.set(2024, Calendar.JANUARY, 15, 10, 30, 0);
		Date date = cal.getTime();

		String result = DateFormatUtil.formatDate(date);
		assertEquals("2024-01-15", result);
	}

	@Test
	public void formatDate_shouldHandleEpochDate() {
		Date epoch = new Date(0);
		String result = DateFormatUtil.formatDate(epoch);
		assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"));
	}
}
