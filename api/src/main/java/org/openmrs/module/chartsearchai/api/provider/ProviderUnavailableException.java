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
 * A requested provider cannot serve a turn. Carries the normalized machine-readable problem code
 * ({@code unknown_provider}, {@code provider_not_enabled}, or {@code provider_not_ready}) so the
 * REST layer maps it to one canonical error instead of falling back to a different provider.
 */
public class ProviderUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String problemCode;

	public ProviderUnavailableException(String problemCode, String message) {
		super(message);
		this.problemCode = problemCode;
	}

	public String getProblemCode() {
		return problemCode;
	}
}
