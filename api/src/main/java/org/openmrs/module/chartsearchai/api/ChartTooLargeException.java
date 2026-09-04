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

/**
 * Thrown when the serialized patient chart exceeds the LLM's configured context
 * window. Lets the REST layer return a specific 413 response with actionable
 * guidance instead of a generic 500.
 */
public class ChartTooLargeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ChartTooLargeException(String message) {
		super(message);
	}
}
