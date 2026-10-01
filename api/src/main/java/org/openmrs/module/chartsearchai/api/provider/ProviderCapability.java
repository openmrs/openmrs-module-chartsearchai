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
 * A capability a clinical answer provider truthfully advertises. Optional turn events are
 * capability-driven: a provider may only emit an optional {@link TurnEventType} when it advertises
 * the capability that covers it (see {@link TurnLifecycleValidator}), and the UI may only offer a
 * feature a provider actually advertises. Wire names are the stable identifiers shared with the
 * dual-provider conformance fixtures, the ESM, and med-agent-hub.
 */
public enum ProviderCapability {

	/** Answers patient-chart questions. Every usable provider advertises this. */
	ANSWER("answer"),

	/** Streams incremental answer and reasoning text while the answer is generated. */
	TOKEN_STREAMING("token_streaming"),

	/** Runs a deterministic post-answer check (temporal/citation gates) on the final answer. */
	ANSWER_CHECK("answer_check"),

	/** Runs an asynchronous post-answer review whose verdict may arrive after the answer. */
	ANSWER_REVIEW("answer_review"),

	/** Produces an asynchronous In-Depth elaboration after the primary answer. */
	INDEPTH("indepth"),

	/** Verifies that cited records actually support the answer text. */
	GROUNDING("grounding"),

	/** Raises deterministic drug-safety advisories alongside the answer. */
	DRUG_SAFETY("drug_safety"),

	/** Emits structured answer blocks beyond plain text. */
	STRUCTURED_BLOCKS("structured_blocks"),

	/** Carries prior clinical turns of the conversation into a new request. */
	MULTI_TURN_CONTEXT("multi_turn_context");

	private final String wireName;

	ProviderCapability(String wireName) {
		this.wireName = wireName;
	}

	public String getWireName() {
		return wireName;
	}

	/**
	 * Resolves a stable wire name (e.g. {@code "answer_review"}) to its capability.
	 *
	 * @throws IllegalArgumentException when the name is not a known capability
	 */
	public static ProviderCapability fromWireName(String wireName) {
		for (ProviderCapability capability : values()) {
			if (capability.wireName.equals(wireName)) {
				return capability;
			}
		}
		throw new IllegalArgumentException("Unknown provider capability: " + wireName);
	}
}
