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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-neutral answer content carried by {@link TurnEvent} and {@link TurnResult}.
 *
 * <p>The common OpenMRS layer understands only the required canonical {@code answer} text, which
 * it needs for display, conversation replay, and audit. The complete provider payload is otherwise
 * preserved without converting validation, evidence, safety, In-Depth, structured blocks, or
 * provider extensions into Java-owned domain types. This keeps persistence content-agnostic while
 * allowing bundled and hub answers to share one lifecycle.</p>
 *
 * <p>The top-level map is snapshotted and exposed read-only. Nested values remain the provider's
 * original objects and are treated as immutable after envelope construction so unknown fields and
 * exact nested structures survive relay and serialization.</p>
 */
public final class AnswerEnvelope {

	private final String text;

	private final Map<String, Object> payload;

	private AnswerEnvelope(String text, Map<String, Object> payload) {
		this.text = text;
		this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}

	/**
	 * Creates an envelope from a canonical provider payload.
	 *
	 * @throws IllegalArgumentException when {@code payload.answer} is absent or is not text
	 */
	public static AnswerEnvelope fromPayload(Map<String, Object> payload) {
		if (payload == null || !(payload.get("answer") instanceof String)) {
			throw new IllegalArgumentException("Provider answer payload must contain textual 'answer'");
		}
		return new AnswerEnvelope((String) payload.get("answer"), payload);
	}

	/** Canonical final prose used for display, conversation replay, and audit. */
	public String getText() {
		return text;
	}

	/**
	 * Complete provider payload, including unknown provider extensions, with a read-only top level.
	 */
	public Map<String, Object> getPayload() {
		return payload;
	}
}
