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
 * Strict verdict-only response schema for <em>batch</em> citation grounding: a single
 * {@code "verdicts"} array of exactly {@code count} {@code enum("YES","NO")} items — one per
 * (record, claim) pair, in order.
 *
 * <p>Unlike {@link ChartAnswerResponseFormat}, this schema deliberately has <strong>no</strong>
 * leading {@code reasoning} field. The chart-answer schema forces the model to emit reasoning
 * before any value; for a one-word entailment verdict that reasoning <em>is</em> the entire cost.
 * Direct measurement against the local llama-server (Gemma E2B): a single per-call verdict took
 * ~1.7&nbsp;s (decode-bound on ~60–95 reasoning tokens), so verifying an answer's citations one at
 * a time cost up to ~34&nbsp;s. Verifying all of them in ONE verdict-only call cost ~1.7–3.3&nbsp;s
 * (≈10× faster) with no loss of accuracy — verdict agreement with the per-call path was 19/20, and
 * the single disagreement was the batch being correct where the reasoning-laden single call had
 * reasoned itself into the wrong answer. The {@code enum} plus {@code minItems == maxItems ==
 * count} constraints guarantee a parseable, correctly-sized array so verdicts align to pairs by
 * position.
 */
final class EntailmentBatchResponseFormat {

	private EntailmentBatchResponseFormat() {
	}

	static ObjectNode build(ObjectMapper mapper, int count) {
		ArrayNode enumValues = mapper.createArrayNode();
		enumValues.add("YES");
		enumValues.add("NO");

		ObjectNode verdictItem = mapper.createObjectNode();
		verdictItem.put("type", "string");
		verdictItem.set("enum", enumValues);

		ObjectNode verdictsProp = mapper.createObjectNode();
		verdictsProp.put("type", "array");
		verdictsProp.set("items", verdictItem);
		// Exactly one verdict per pair: the grammar emits a fixed-length array, so verdicts
		// align to the numbered pairs by position and a short/over-long reply is impossible.
		verdictsProp.put("minItems", count);
		verdictsProp.put("maxItems", count);

		ObjectNode properties = mapper.createObjectNode();
		properties.set("verdicts", verdictsProp);

		ArrayNode required = mapper.createArrayNode();
		required.add("verdicts");

		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		schema.set("properties", properties);
		schema.set("required", required);
		schema.put("additionalProperties", false);

		ObjectNode jsonSchema = mapper.createObjectNode();
		jsonSchema.put("name", "entailment_verdicts");
		jsonSchema.put("strict", true);
		jsonSchema.set("schema", schema);

		ObjectNode responseFormat = mapper.createObjectNode();
		responseFormat.put("type", "json_schema");
		responseFormat.set("json_schema", jsonSchema);
		return responseFormat;
	}
}
