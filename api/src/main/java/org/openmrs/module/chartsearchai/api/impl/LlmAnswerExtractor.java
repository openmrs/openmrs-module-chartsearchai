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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the LLM's structured answer payload into an {@link LlmResponse}
 * (answer text + citation indices). Handles three response shapes the
 * model may emit, in priority order:
 *
 * <ol>
 *   <li>Clean JSON: {@code {"answer":"...","citations":[1,2]}}.</li>
 *   <li>Markdown-fenced JSON: a {@code ```json\n...\n```} wrapper that
 *       some models (e.g. Gemma) add even when constrained.</li>
 *   <li>Truncated/malformed JSON: regex fallback that pulls the
 *       {@code answer} value and any integers in the {@code citations}
 *       array, so a token-cap cutoff still returns useful content.</li>
 * </ol>
 *
 * <p>Also normalizes shorthand citation syntax: {@code [1/2/3]} → {@code [1], [2], [3]}.</p>
 */
final class LlmAnswerExtractor {

	private LlmAnswerExtractor() {
	}

	private static final Logger log = LoggerFactory.getLogger(LlmAnswerExtractor.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Pattern SLASH_CITATION = Pattern.compile("\\[(\\d+(?:/\\d+)+)\\]");

	/**
	 * Matches the JSON "answer" value — captures the string content (may be truncated).
	 * Possessive quantifiers ({@code ++}) prevent backtracking, so this stays stack-safe
	 * even on long truncated answers (the regex engine recurses per alternation choice
	 * point with normal {@code *}).
	 */
	private static final Pattern ANSWER_VALUE = Pattern.compile(
			"\"answer\"\\s*+:\\s*+\"((?:[^\"\\\\]++|\\\\.)*+)\"?+");

	/** Matches a bare integer inside a citations array. */
	private static final Pattern CITATION_NUMBER = Pattern.compile("(?:^|[,\\[])\\s*(\\d+)");

	static LlmResponse extractResponse(String response, int inputTokens, int outputTokens) {
		return extractResponse(response, inputTokens, outputTokens, 0);
	}

	static LlmResponse extractResponse(String response, int inputTokens, int outputTokens,
			int cachedTokens) {
		LlmResponse parsed = extractResponse(response);
		return new LlmResponse(parsed.getAnswer(), parsed.getCitations(),
				parsed.getBlocks(), inputTokens, outputTokens, cachedTokens);
	}

	static LlmResponse extractResponse(String response) {
		String trimmed = response.trim();
		if (trimmed.isEmpty()) {
			return new LlmResponse(trimmed, Collections.emptyList());
		}

		// Strip markdown code fences that some models (e.g. Gemma) wrap
		// around JSON output: ```json\n{...}\n``` or ```\n{...}\n```
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline > 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
			}
		}

		try {
			JsonNode root = MAPPER.readTree(trimmed);
			JsonNode answerNode = root.get("answer");
			if (answerNode != null && answerNode.isTextual()) {
				String answer = normalizeSlashCitations(answerNode.asText().trim());
				List<Integer> citations = new ArrayList<>();
				JsonNode citationsNode = root.get("citations");
				if (citationsNode != null && citationsNode.isArray()) {
					for (JsonNode n : citationsNode) {
						if (n.isInt()) {
							citations.add(n.asInt());
						}
					}
				}
				List<ResponseBlock> blocks = parseBlocks(root.get("blocks"));
				return new LlmResponse(answer, citations, blocks, 0, 0, 0);
			}
		}
		catch (IOException e) {
			log.warn("LLM response did not parse as JSON (possibly truncated), "
					+ "attempting regex extraction");
		}

		// Fallback: extract the answer value using regex when the JSON is
		// malformed or truncated (e.g. LLM ran out of output tokens before
		// closing the citations array). This mirrors what
		// AnswerExtractingConsumer does for streaming.
		try {
			Matcher answerMatcher = ANSWER_VALUE.matcher(trimmed);
			if (answerMatcher.find()) {
				String raw = answerMatcher.group(1);
				// Unescape JSON string escapes
				String answer = raw.replace("\\n", "\n")
						.replace("\\t", "\t")
						.replace("\\\"", "\"")
						.replace("\\\\/", "/")
						.replace("\\\\", "\\");
				answer = normalizeSlashCitations(answer.trim());
				// Try to extract citations from whatever was produced
				List<Integer> citations = new ArrayList<>();
				int citationsStart = trimmed.indexOf("\"citations\"");
				if (citationsStart >= 0) {
					Matcher numMatcher = CITATION_NUMBER.matcher(
							trimmed.substring(citationsStart));
					while (numMatcher.find()) {
						citations.add(Integer.parseInt(numMatcher.group(1)));
					}
				}
				return new LlmResponse(answer, citations);
			}
		}
		catch (StackOverflowError e) {
			// Defensive backstop — the possessive-quantifier regex shouldn't recurse,
			// but if a future edit reintroduces a recursive alternation we'd rather
			// return the raw text than 500 the request.
			log.warn("Regex fallback overflowed the stack on {}-char response; "
					+ "returning raw text", trimmed.length());
		}

		return new LlmResponse(trimmed, Collections.emptyList());
	}

	/**
	 * Walk the {@code blocks} JSON array and materialize a list of
	 * {@link ResponseBlock} values. Tolerates a missing or non-array node
	 * (returns empty list) so legacy {@code {answer, citations}} responses
	 * keep working. Skips blocks of unknown {@code kind} silently — future
	 * block types (list, timeline) won't be understood by older parsers but
	 * shouldn't break the response.
	 */
	private static List<ResponseBlock> parseBlocks(JsonNode blocksNode) {
		if (blocksNode == null || !blocksNode.isArray()) {
			return Collections.emptyList();
		}
		List<ResponseBlock> out = new ArrayList<>(blocksNode.size());
		for (JsonNode blockNode : blocksNode) {
			JsonNode kindNode = blockNode.get("kind");
			if (kindNode == null || !ResponseBlock.KIND_TABLE.equals(kindNode.asText())) {
				continue;
			}
			String title = blockNode.path("title").asText(null);
			List<ResponseBlock.Column> columns = parseColumns(blockNode.get("columns"));
			List<ResponseBlock.Row> rows = parseRows(blockNode.get("rows"));
			out.add(new ResponseBlock(ResponseBlock.KIND_TABLE, title, columns, rows));
		}
		return out;
	}

	private static List<ResponseBlock.Column> parseColumns(JsonNode columnsNode) {
		if (columnsNode == null || !columnsNode.isArray()) {
			return Collections.emptyList();
		}
		List<ResponseBlock.Column> out = new ArrayList<>(columnsNode.size());
		for (JsonNode c : columnsNode) {
			String key = c.path("key").asText("");
			String label = c.path("label").asText(key);
			if (!key.isEmpty()) {
				out.add(new ResponseBlock.Column(key, label));
			}
		}
		return out;
	}

	private static List<ResponseBlock.Row> parseRows(JsonNode rowsNode) {
		if (rowsNode == null || !rowsNode.isArray()) {
			return Collections.emptyList();
		}
		List<ResponseBlock.Row> out = new ArrayList<>(rowsNode.size());
		for (JsonNode rowNode : rowsNode) {
			JsonNode cellsNode = rowNode.get("cells");
			Map<String, ResponseBlock.Cell> cells = new LinkedHashMap<>();
			if (cellsNode != null && cellsNode.isObject()) {
				Iterator<Map.Entry<String, JsonNode>> fields = cellsNode.fields();
				while (fields.hasNext()) {
					Map.Entry<String, JsonNode> entry = fields.next();
					JsonNode cellNode = entry.getValue();
					String text = cellNode.path("text").asText("");
					List<Integer> refs = new ArrayList<>();
					JsonNode refsNode = cellNode.get("refs");
					if (refsNode != null && refsNode.isArray()) {
						for (JsonNode r : refsNode) {
							if (r.isInt()) {
								refs.add(r.asInt());
							}
						}
					}
					cells.put(entry.getKey(), new ResponseBlock.Cell(text, refs));
				}
			}
			out.add(new ResponseBlock.Row(cells));
		}
		return out;
	}

	static String normalizeSlashCitations(String text) {
		Matcher matcher = SLASH_CITATION.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		StringBuffer sb = new StringBuffer();
		matcher.reset();
		while (matcher.find()) {
			String[] parts = matcher.group(1).split("/");
			StringBuilder replacement = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					replacement.append(", ");
				}
				replacement.append("[").append(parts[i]).append("]");
			}
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}
}
