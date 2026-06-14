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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

public class ChartAnswerResponseFormatTest {

	private JsonNode schema() {
		return ChartAnswerResponseFormat.build(new ObjectMapper())
				.get("json_schema").get("schema");
	}

	@Test
	public void build_shouldEmitReasoningBeforeAnswer() {
		// Forcing the model straight to {answer, citations} suppresses the chain-of-thought it
		// needs to connect a query to a differently-worded record (e.g. "ear problems" ->
		// "Hearing Loss") — it abstains instead. A leading "reasoning" field gives it room to
		// think first. json_schema is compiled to a grammar that generates object properties IN
		// SCHEMA ORDER, so reasoning MUST be the first property to actually precede the answer.
		JsonNode props = schema().get("properties");
		List<String> order = new ArrayList<>();
		props.fieldNames().forEachRemaining(order::add);
		assertEquals("reasoning", order.get(0),
				"reasoning must be the first schema property so the model thinks before it answers");
		assertTrue(order.indexOf("reasoning") < order.indexOf("answer"),
				"reasoning must come before answer");
		assertEquals("string", props.get("reasoning").get("type").asText());
	}

	@Test
	public void build_shouldRequireAllThreeFields() {
		List<String> required = new ArrayList<>();
		schema().get("required").forEach(n -> required.add(n.asText()));
		assertTrue(required.contains("reasoning"), "reasoning must be required");
		assertTrue(required.contains("answer"), "answer must be required");
		assertTrue(required.contains("citations"), "citations must be required");
	}

	@Test
	public void build_withReasoningCap_addsMaxLengthToReasoningOnly() {
		// The reasoning scratchpad is the dominant decode cost on CPU-only servers (3-27s of
		// thinking before any answer token). A grammar-enforced maxLength bounds it; the probe
		// against the bundled llama-server confirmed json_schema maxLength is enforced exactly.
		// The ANSWER must never be capped — it is the clinical content.
		JsonNode props = ChartAnswerResponseFormat.build(new ObjectMapper(), 400)
				.get("json_schema").get("schema").get("properties");
		assertEquals(400, props.get("reasoning").get("maxLength").asInt(),
				"the cap must land on the reasoning property");
		assertTrue(props.get("answer").get("maxLength") == null,
				"the answer must never be length-capped");
	}

	@Test
	public void build_withZeroCap_matchesUncappedSchema() {
		// 0 = disabled (the GP default): the schema must be byte-identical to the classic one so
		// existing deployments see no behavior change whatsoever.
		assertEquals(ChartAnswerResponseFormat.build(new ObjectMapper()),
				ChartAnswerResponseFormat.build(new ObjectMapper(), 0),
				"cap=0 must produce the exact uncapped schema");
	}
}
