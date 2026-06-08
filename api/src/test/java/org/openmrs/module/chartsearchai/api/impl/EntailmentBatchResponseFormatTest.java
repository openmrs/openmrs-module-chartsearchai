/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EntailmentBatchResponseFormat} — the verdict-only batch-grounding schema.
 */
public class EntailmentBatchResponseFormatTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	public void build_producesStrictFixedLengthYesNoArray() {
		ObjectNode rf = EntailmentBatchResponseFormat.build(MAPPER, 5);
		assertEquals("json_schema", rf.get("type").asText());
		JsonNode jsonSchema = rf.get("json_schema");
		assertTrue(jsonSchema.get("strict").asBoolean(),
				"must be strict so the model cannot return the wrong number of verdicts");
		JsonNode schema = jsonSchema.get("schema");
		assertFalse(schema.get("additionalProperties").asBoolean());
		JsonNode verdicts = schema.get("properties").get("verdicts");
		assertEquals("array", verdicts.get("type").asText());
		// Exactly one verdict per pair: so verdicts align to the numbered pairs by position.
		assertEquals(5, verdicts.get("minItems").asInt());
		assertEquals(5, verdicts.get("maxItems").asInt());
		JsonNode enumValues = verdicts.get("items").get("enum");
		assertEquals(2, enumValues.size());
		assertEquals("YES", enumValues.get(0).asText());
		assertEquals("NO", enumValues.get(1).asText());
	}

	@Test
	public void build_hasNoReasoningField() {
		// The whole point vs ChartAnswerResponseFormat: NO leading reasoning field. The per-verdict
		// reasoning the chart-answer schema forces is the decode-bound latency a one-word verdict
		// does not need (measured ~10x slower). Only the verdicts array may be present.
		JsonNode properties = EntailmentBatchResponseFormat.build(MAPPER, 3)
				.get("json_schema").get("schema").get("properties");
		assertFalse(properties.has("reasoning"),
				"batch verdict schema must not force per-call reasoning");
		assertEquals(1, properties.size(), "only the verdicts array");
	}

	@Test
	public void build_arrayLengthTracksPairCount() {
		JsonNode verdicts = EntailmentBatchResponseFormat.build(MAPPER, 1)
				.get("json_schema").get("schema").get("properties").get("verdicts");
		assertEquals(1, verdicts.get("minItems").asInt());
		assertEquals(1, verdicts.get("maxItems").asInt());
	}
}
