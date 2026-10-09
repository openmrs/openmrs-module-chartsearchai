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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pins the content-agnostic provider-answer boundary: Java understands only the canonical
 * {@code answer} text needed for display, history replay, and audit; every other provider field is
 * preserved unchanged for persistence and relay.
 */
public class AnswerEnvelopeTest {

	@Test
	public void preservesUnknownProviderFieldsWithoutReshapingThem() {
		Map<String, Object> validation = new LinkedHashMap<>();
		validation.put("status", "needs_review");
		validation.put("futureProviderField", Collections.singletonMap("score", 0.73));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Final answer");
		payload.put("answerValidation", validation);
		payload.put("providerExtension", Collections.singletonMap("opaque", true));

		AnswerEnvelope envelope = AnswerEnvelope.fromPayload(payload);

		assertEquals("Final answer", envelope.getText());
		assertSame(validation, envelope.getPayload().get("answerValidation"),
				"nested provider content is preserved, not converted to Java-owned domain types");
		assertSame(payload.get("providerExtension"), envelope.getPayload().get("providerExtension"));
	}

	@Test
	public void snapshotsTheTopLevelPayloadAndExposesItReadOnly() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", "Original");

		AnswerEnvelope envelope = AnswerEnvelope.fromPayload(payload);
		payload.put("answer", "Mutated");

		assertEquals("Original", envelope.getText());
		assertEquals("Original", envelope.getPayload().get("answer"));
		assertThrows(UnsupportedOperationException.class,
				() -> envelope.getPayload().put("newField", "not allowed"));
	}

	@Test
	public void requiresCanonicalAnswerTextButDoesNotRequireAnyOptionalField() {
		assertEquals("Only text",
				AnswerEnvelope.fromPayload(Collections.<String, Object>singletonMap("answer", "Only text"))
						.getText());
		assertThrows(IllegalArgumentException.class,
				() -> AnswerEnvelope.fromPayload(Collections.<String, Object>emptyMap()));
		assertThrows(IllegalArgumentException.class,
				() -> AnswerEnvelope.fromPayload(
						Collections.<String, Object>singletonMap("answer", 42)));
	}
}
