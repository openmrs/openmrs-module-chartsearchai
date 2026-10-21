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
 * A context mode a provider offers for building the evidence a question is answered from. Both
 * providers expose their modes through this one vocabulary so the UI and persistence layer never
 * need provider-specific mode names. Wire names are shared with the dual-provider conformance
 * contract.
 */
public enum ProviderMode {

	/** Question-aware evidence slice: mandatory safety evidence plus records relevant to the question. */
	QUERY_SCOPED("query_scoped"),

	/** The complete deterministic patient ledger in stable bytes; fits or fails explicitly. */
	FULL_CHART_STABLE("full_chart_stable");

	private final String wireName;

	ProviderMode(String wireName) {
		this.wireName = wireName;
	}

	public String getWireName() {
		return wireName;
	}

	/**
	 * Resolves a stable wire name (e.g. {@code "query_scoped"}) to its mode.
	 *
	 * @throws IllegalArgumentException when the name is not a known mode
	 */
	public static ProviderMode fromWireName(String wireName) {
		for (ProviderMode mode : values()) {
			if (mode.wireName.equals(wireName)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown provider mode: " + wireName);
	}
}
