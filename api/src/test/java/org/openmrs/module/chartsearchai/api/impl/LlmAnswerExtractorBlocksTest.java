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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;

/**
 * Contract tests for the structured {@code blocks} field added in the
 * tabular-display iteration. The parser must:
 *
 * <ol>
 *   <li>Populate {@link LlmResponse#getBlocks()} from the JSON {@code blocks}
 *       array when present.</li>
 *   <li>Return an empty {@code blocks} list (not null) when the field is
 *       absent — legacy {@code {answer, citations}} responses keep working.</li>
 *   <li>Preserve column order, row order, per-cell text, and per-cell {@code refs}.</li>
 * </ol>
 *
 * <p>If these assertions hold but the parser actually ignores {@code blocks},
 * the SPA would render only prose and the tabular feature is silently broken
 * end-to-end. The RED-then-GREEN cycle for this test is enforced by stubbing
 * the parser's block-handling branch and confirming the assertions fail.
 */
public class LlmAnswerExtractorBlocksTest {

	@Test
	public void parserShouldPopulateBlocksFromJsonResponse() {
		String json = "{"
				+ "\"answer\":\"The patient takes Lisinopril 10mg [2] and Metformin 500mg [4].\","
				+ "\"citations\":[2,4],"
				+ "\"blocks\":["
				+ "  {"
				+ "    \"kind\":\"table\","
				+ "    \"title\":\"Active medications\","
				+ "    \"columns\":["
				+ "      {\"key\":\"name\",\"label\":\"Medication\"},"
				+ "      {\"key\":\"dose\",\"label\":\"Dose\"}"
				+ "    ],"
				+ "    \"rows\":["
				+ "      {\"cells\":{"
				+ "        \"name\":{\"text\":\"Lisinopril\",\"refs\":[2]},"
				+ "        \"dose\":{\"text\":\"10 mg\",\"refs\":[]}"
				+ "      }},"
				+ "      {\"cells\":{"
				+ "        \"name\":{\"text\":\"Metformin\",\"refs\":[4]},"
				+ "        \"dose\":{\"text\":\"500 mg\",\"refs\":[]}"
				+ "      }}"
				+ "    ]"
				+ "  }"
				+ "]"
				+ "}";

		LlmResponse parsed = LlmAnswerExtractor.extractResponse(json);

		List<ResponseBlock> blocks = parsed.getBlocks();
		assertEquals(1, blocks.size(),
				"expected exactly one block in the response; got " + blocks);

		ResponseBlock table = blocks.get(0);
		assertEquals(ResponseBlock.KIND_TABLE, table.getKind());
		assertEquals("Active medications", table.getTitle());

		assertEquals(2, table.getColumns().size());
		assertEquals("name", table.getColumns().get(0).getKey());
		assertEquals("Medication", table.getColumns().get(0).getLabel());
		assertEquals("dose", table.getColumns().get(1).getKey());

		assertEquals(2, table.getRows().size());

		ResponseBlock.Cell row1Name = table.getRows().get(0).getCells().get("name");
		assertEquals("Lisinopril", row1Name.getText());
		assertEquals(List.of(2), row1Name.getRefs(),
				"row 1 name cell should have refs=[2]");

		ResponseBlock.Cell row1Dose = table.getRows().get(0).getCells().get("dose");
		assertEquals("10 mg", row1Dose.getText());
		assertTrue(row1Dose.getRefs().isEmpty(),
				"row 1 dose cell should have empty refs (no citation)");

		ResponseBlock.Cell row2Name = table.getRows().get(1).getCells().get("name");
		assertEquals("Metformin", row2Name.getText());
		assertEquals(List.of(4), row2Name.getRefs());
	}

	@Test
	public void legacyResponseWithoutBlocksShouldReturnEmptyBlocksList() {
		// Pre-tabular-iteration responses don't carry the `blocks` field at
		// all. The parser must accept these and return an empty list rather
		// than null — otherwise the hydration path on legacy chat_message
		// rows blows up.
		String json = "{\"answer\":\"The patient is 47 years old.\",\"citations\":[1]}";

		LlmResponse parsed = LlmAnswerExtractor.extractResponse(json);

		assertEquals("The patient is 47 years old.", parsed.getAnswer());
		assertEquals(List.of(1), parsed.getCitations());
		assertTrue(parsed.getBlocks().isEmpty(),
				"legacy response (no blocks field) must yield empty list, not null");
	}

	@Test
	public void emptyBlocksArrayShouldDeserializeCleanly() {
		// Models that follow the schema strictly will emit `blocks: []` when
		// the answer is prose-only. Make sure that case works (no NPE, no
		// phantom entries).
		String json = "{\"answer\":\"prose only\",\"citations\":[],\"blocks\":[]}";

		LlmResponse parsed = LlmAnswerExtractor.extractResponse(json);

		assertTrue(parsed.getBlocks().isEmpty(),
				"empty blocks array must yield empty list");
	}

	@Test
	public void tokenCountOverloadShouldPreserveBlocks() {
		// Regression: the 4-arg extractResponse(json, in, out, cached) used to
		// re-wrap the parsed LlmResponse with the 5-arg constructor, which
		// dropped the blocks field. The chat / search code paths all flow
		// through this overload (so they can attach token counts from the
		// transport layer), so dropping blocks here was silently masking
		// every populated block the model emitted.
		String json = "{"
				+ "\"answer\":\"prose summary\",\"citations\":[1],\"blocks\":["
				+ "{\"kind\":\"table\",\"title\":\"Meds\","
				+ "\"columns\":[{\"key\":\"name\",\"label\":\"Medication\"}],"
				+ "\"rows\":[{\"cells\":{\"name\":{\"text\":\"Lisinopril\",\"refs\":[1]}}}]}"
				+ "]}";

		LlmResponse parsed = LlmAnswerExtractor.extractResponse(json, 100, 50, 0);

		assertEquals("prose summary", parsed.getAnswer());
		assertEquals(100, parsed.getInputTokens());
		assertEquals(50, parsed.getOutputTokens());
		assertEquals(1, parsed.getBlocks().size(),
				"token-count overload must thread parsed blocks through, not drop them");
		assertEquals("Meds", parsed.getBlocks().get(0).getTitle());
		assertEquals(1, parsed.getBlocks().get(0).getRows().size());
	}

	@Test
	public void multipleBlocksShouldAllParse() {
		// Multi-table answer (e.g. "list her meds AND allergies" → two
		// tables in one response).
		String json = "{"
				+ "\"answer\":\"summary\",\"citations\":[],\"blocks\":["
				+ "{\"kind\":\"table\",\"title\":\"Meds\",\"columns\":[{\"key\":\"n\",\"label\":\"N\"}],\"rows\":[]},"
				+ "{\"kind\":\"table\",\"title\":\"Allergies\",\"columns\":[{\"key\":\"a\",\"label\":\"A\"}],\"rows\":[]}"
				+ "]}";

		LlmResponse parsed = LlmAnswerExtractor.extractResponse(json);

		assertEquals(2, parsed.getBlocks().size());
		assertEquals("Meds", parsed.getBlocks().get(0).getTitle());
		assertEquals("Allergies", parsed.getBlocks().get(1).getTitle());
	}
}
