/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

/**
 * One parsed SSE event: the {@code event:} type plus the concatenated {@code data:} payload.
 * Top-level rather than nested so both controller-streaming test classes can name the type
 * unqualified — see {@link SseEvents} for the parser.
 */
final class SseEvent {

	final String type;

	final String data;

	SseEvent(String type, String data) {
		this.type = type;
		this.data = data;
	}
}
