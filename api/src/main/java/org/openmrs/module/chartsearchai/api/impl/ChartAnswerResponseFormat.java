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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strict {reasoning, answer, citations} schema — forces close-brace stop and avoids Anthropic's
 * `json_object` 400.
 *
 * <p>The leading {@code reasoning} field is deliberate and load-bearing. A json_schema response
 * format is compiled to a grammar that emits object properties <em>in schema order</em>, so making
 * {@code reasoning} the first property forces the model to think before it commits to an answer.
 * Without it — jumping straight to {@code answer} — the small local model (Gemma E4B) defaults to
 * literal-token matching and abstains whenever the query word is not verbatim in the chart (e.g.
 * "ear problems" failing to surface a "Hearing Loss" record). The reasoning step lets it make the
 * clinical-meaning connection it is otherwise fully capable of. {@code reasoning} is the model's
 * scratchpad: {@link LlmAnswerExtractor} reads only {@code answer}/{@code citations} and never
 * surfaces it.
 */
final class ChartAnswerResponseFormat {

	private ChartAnswerResponseFormat() {
	}

	static ObjectNode build(ObjectMapper mapper) {
		ObjectNode reasoningProp = mapper.createObjectNode();
		reasoningProp.put("type", "string");

		ObjectNode answerProp = mapper.createObjectNode();
		answerProp.put("type", "string");

		ObjectNode citationItem = mapper.createObjectNode();
		citationItem.put("type", "integer");
		ObjectNode citationsProp = mapper.createObjectNode();
		citationsProp.put("type", "array");
		citationsProp.set("items", citationItem);

		// Insertion order matters: the grammar generates properties in this order, so reasoning
		// is emitted (thought) before the answer is committed.
		ObjectNode properties = mapper.createObjectNode();
		properties.set("reasoning", reasoningProp);
		properties.set("answer", answerProp);
		properties.set("citations", citationsProp);

		ArrayNode required = mapper.createArrayNode();
		required.add("reasoning");
		required.add("answer");
		required.add("citations");

		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		schema.set("properties", properties);
		schema.set("required", required);
		schema.put("additionalProperties", false);

		ObjectNode jsonSchema = mapper.createObjectNode();
		jsonSchema.put("name", "chart_answer");
		jsonSchema.put("strict", true);
		jsonSchema.set("schema", schema);

		ObjectNode responseFormat = mapper.createObjectNode();
		responseFormat.put("type", "json_schema");
		responseFormat.set("json_schema", jsonSchema);
		return responseFormat;
	}
}
