/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

/**
 * A non-success HTTP response from med-agent-hub. Carries the upstream status and raw body so the
 * provider can extract the hub's machine-readable problem code without inventing one.
 */
public class HubTransportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int statusCode;

	private final String body;

	public HubTransportException(int statusCode, String body) {
		super("Hub HTTP " + statusCode);
		this.statusCode = statusCode;
		this.body = body == null ? "" : body;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public String getBody() {
		return body;
	}
}
