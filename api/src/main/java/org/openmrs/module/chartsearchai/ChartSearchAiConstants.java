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

public class ChartSearchAiConstants {

	public static final String PRIV_QUERY_PATIENT_DATA = "AI Query Patient Data";

	public static final String PRIV_VIEW_AUDIT_LOGS = "View AI Audit Logs";

	public static final String GP_AUDIT_LOG_RETENTION_DAYS = "chartsearchai.auditLogRetentionDays";

	public static final int DEFAULT_AUDIT_LOG_RETENTION_DAYS = 90;

	public static final String GP_CHAT_RETENTION_DAYS = "chartsearchai.chat.retentionDays";

	public static final int DEFAULT_CHAT_RETENTION_DAYS = 90;

	public static final String GP_HUB_ENDPOINT_URL = "chartsearchai.hub.endpointUrl";

	public static final String RP_HUB_API_KEY = "chartsearchai.hub.apikey";

	public static final String GP_HUB_PROFILE_ID = "chartsearchai.hub.profileId";

	public static final String GP_RATE_LIMIT_PER_MINUTE = "chartsearchai.rateLimitPerMinute";

	public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 10;

	private ChartSearchAiConstants() {
	}
}
