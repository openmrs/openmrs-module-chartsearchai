/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

/**
 * Unit tests for {@link AuditLogPurgeTask} retention day parsing.
 */
public class AuditLogPurgeTaskTest {

	@Test
	public void parseRetentionDays_shouldReturnDefaultWhenNull() {
		assertEquals(ChartSearchAiConstants.DEFAULT_AUDIT_LOG_RETENTION_DAYS,
				AuditLogPurgeTask.parseRetentionDays(null));
	}

	@Test
	public void parseRetentionDays_shouldReturnDefaultWhenEmpty() {
		assertEquals(ChartSearchAiConstants.DEFAULT_AUDIT_LOG_RETENTION_DAYS,
				AuditLogPurgeTask.parseRetentionDays(""));
	}

	@Test
	public void parseRetentionDays_shouldReturnDefaultWhenBlank() {
		assertEquals(ChartSearchAiConstants.DEFAULT_AUDIT_LOG_RETENTION_DAYS,
				AuditLogPurgeTask.parseRetentionDays("   "));
	}

	@Test
	public void parseRetentionDays_shouldReturnDefaultWhenInvalid() {
		assertEquals(ChartSearchAiConstants.DEFAULT_AUDIT_LOG_RETENTION_DAYS,
				AuditLogPurgeTask.parseRetentionDays("not-a-number"));
	}

	@Test
	public void parseRetentionDays_shouldParseValidValue() {
		assertEquals(30, AuditLogPurgeTask.parseRetentionDays("30"));
	}

	@Test
	public void parseRetentionDays_shouldTrimWhitespace() {
		assertEquals(60, AuditLogPurgeTask.parseRetentionDays("  60  "));
	}

	@Test
	public void parseRetentionDays_shouldReturnZeroWhenSetToZero() {
		assertEquals(0, AuditLogPurgeTask.parseRetentionDays("0"));
	}

	@Test
	public void parseRetentionDays_shouldHandleNegativeValue() {
		assertEquals(-1, AuditLogPurgeTask.parseRetentionDays("-1"));
	}
}
