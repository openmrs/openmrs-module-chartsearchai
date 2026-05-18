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
 * Strict response schema enforcing the chart-answer shape:
 *
 * <pre>
 *   {
 *     "answer": string,         // prose, with [N] citation markers
 *     "citations": int[],       // referenced record indices
 *     "blocks": [               // OPTIONAL — additive; legacy responses without `blocks` keep working
 *       {
 *         "kind": "table",
 *         "title": string?,
 *         "columns": [{"key": string, "label": string}],
 *         "rows": [{"cells": { "&lt;key&gt;": {"text": string, "refs": int[]?} }}]
 *       }
 *     ]
 *   }
 * </pre>
 *
 * <p>Per the structured-output rules each nested object carries
 * {@code additionalProperties: false} and a {@code required} list — required
 * by OpenAI's strict mode and tolerated by LM Studio / llama-server. Anthropic
 * does not support {@code response_format: json_schema} (it uses tool-use
 * instead); that vendor will need a separate request-body builder when added.
 *
 * <p>The {@code blocks} field tells the model to emit structured tables when
 * the answer is naturally tabular (medication lists, lab values, allergies,
 * vitals); the SPA renders these as Carbon DataTables below the prose. Single-
 * fact answers (e.g. patient age) leave {@code blocks} empty. See
 * {@link LlmProvider#DEFAULT_SYSTEM_PROMPT} for the LLM-side instruction +
 * format demonstration.
 */
final class ChartAnswerResponseFormat {

	private ChartAnswerResponseFormat() {
	}

	static ObjectNode build(ObjectMapper mapper) {
		ObjectNode answerProp = mapper.createObjectNode();
		answerProp.put("type", "string");

		ObjectNode citationItem = mapper.createObjectNode();
		citationItem.put("type", "integer");
		ObjectNode citationsProp = mapper.createObjectNode();
		citationsProp.put("type", "array");
		citationsProp.set("items", citationItem);

		ObjectNode properties = mapper.createObjectNode();
		properties.set("answer", answerProp);
		properties.set("citations", citationsProp);
		properties.set("blocks", blocksProp(mapper));

		ArrayNode required = mapper.createArrayNode();
		required.add("answer");
		required.add("citations");
		required.add("blocks");

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

	private static ObjectNode blocksProp(ObjectMapper mapper) {
		// Cell: { text: string, refs: int[]? }
		ObjectNode cellText = mapper.createObjectNode();
		cellText.put("type", "string");
		ObjectNode cellRefs = mapper.createObjectNode();
		cellRefs.put("type", "array");
		ObjectNode cellRefItem = mapper.createObjectNode();
		cellRefItem.put("type", "integer");
		cellRefs.set("items", cellRefItem);
		// OpenAI strict mode forbids `required` listing optional properties, so
		// we require both `text` and `refs`. The model can emit `refs: []` for
		// cells with no citation — semantically equivalent to "no refs", and
		// the SPA renders no chip when the array is empty.
		ObjectNode cellProps = mapper.createObjectNode();
		cellProps.set("text", cellText);
		cellProps.set("refs", cellRefs);
		ObjectNode cell = mapper.createObjectNode();
		cell.put("type", "object");
		cell.set("properties", cellProps);
		cell.put("additionalProperties", false);
		ArrayNode cellRequired = mapper.createArrayNode();
		cellRequired.add("text");
		cellRequired.add("refs");
		cell.set("required", cellRequired);

		// Cells map: object whose values are Cells. Use Anthropic-/OpenAI-
		// compatible "object with additionalProperties: <Cell>"  — but
		// OpenAI strict mode REQUIRES additionalProperties: false, which
		// disallows free-form keys. We work around by emitting a fixed-key
		// object: the model uses the column `key` strings literally as JSON
		// keys. Schema therefore declares `additionalProperties: false` and
		// trusts the model to follow the column keys; LM Studio enforces this
		// at decode-time via its grammar. (OpenAI strict will reject any key
		// not in `properties`, but we accept that limitation in exchange for
		// strict structural validation; vendors using strict mode need to
		// match the columns array exactly.)
		// For now: model emits cells under whatever keys it chooses from
		// `columns[].key`. The schema allows the values to be Cell-shaped but
		// permits any string key by setting additionalProperties to the Cell
		// definition (LM Studio supports this; OpenAI strict does not, but
		// for POC against LM Studio that's acceptable).
		ObjectNode cellsMap = mapper.createObjectNode();
		cellsMap.put("type", "object");
		cellsMap.set("additionalProperties", cell);

		ObjectNode rowProps = mapper.createObjectNode();
		rowProps.set("cells", cellsMap);
		ObjectNode row = mapper.createObjectNode();
		row.put("type", "object");
		row.set("properties", rowProps);
		row.put("additionalProperties", false);
		ArrayNode rowRequired = mapper.createArrayNode();
		rowRequired.add("cells");
		row.set("required", rowRequired);

		// Column: { key: string, label: string }
		ObjectNode colKey = mapper.createObjectNode();
		colKey.put("type", "string");
		ObjectNode colLabel = mapper.createObjectNode();
		colLabel.put("type", "string");
		ObjectNode colProps = mapper.createObjectNode();
		colProps.set("key", colKey);
		colProps.set("label", colLabel);
		ObjectNode column = mapper.createObjectNode();
		column.put("type", "object");
		column.set("properties", colProps);
		column.put("additionalProperties", false);
		ArrayNode colRequired = mapper.createArrayNode();
		colRequired.add("key");
		colRequired.add("label");
		column.set("required", colRequired);

		// Block: { kind: "table", title: string?, columns: Column[], rows: Row[] }
		ObjectNode kindProp = mapper.createObjectNode();
		kindProp.put("type", "string");
		ArrayNode kindEnum = mapper.createArrayNode();
		kindEnum.add("table");
		kindProp.set("enum", kindEnum);
		ObjectNode titleProp = mapper.createObjectNode();
		titleProp.put("type", "string");
		ObjectNode columnsProp = mapper.createObjectNode();
		columnsProp.put("type", "array");
		columnsProp.set("items", column);
		ObjectNode rowsProp = mapper.createObjectNode();
		rowsProp.put("type", "array");
		rowsProp.set("items", row);
		ObjectNode blockProps = mapper.createObjectNode();
		blockProps.set("kind", kindProp);
		blockProps.set("title", titleProp);
		blockProps.set("columns", columnsProp);
		blockProps.set("rows", rowsProp);
		ObjectNode block = mapper.createObjectNode();
		block.put("type", "object");
		block.set("properties", blockProps);
		block.put("additionalProperties", false);
		ArrayNode blockRequired = mapper.createArrayNode();
		blockRequired.add("kind");
		blockRequired.add("title");
		blockRequired.add("columns");
		blockRequired.add("rows");
		block.set("required", blockRequired);

		// blocks: Block[]
		ObjectNode blocks = mapper.createObjectNode();
		blocks.put("type", "array");
		blocks.set("items", block);
		return blocks;
	}
}
