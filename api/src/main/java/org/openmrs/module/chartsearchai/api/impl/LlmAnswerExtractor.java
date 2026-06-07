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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
 * <p>The schema emits a leading {@code "reasoning"} field (the model's chain-of-thought) before
 * {@code answer}; it is the model's scratchpad and is ignored here — only {@code answer} and
 * {@code citations} are read.</p>
 *
 * <ol>
 *   <li>Clean JSON: {@code {"reasoning":"...","answer":"...","citations":[1,2]}}.</li>
 *   <li>Markdown-fenced JSON: a {@code ```json\n...\n```} wrapper that
 *       some models (e.g. Gemma) add even when constrained.</li>
 *   <li>Truncated/malformed JSON: regex fallback that pulls the
 *       {@code answer} value and any integers in the {@code citations}
 *       array, so a token-cap cutoff still returns useful content.</li>
 * </ol>
 *
 * <p>Also normalizes shorthand citation syntax: {@code [1/2/3]} → {@code [1], [2], [3]} —
 * but only when every number in the group is a record the model actually cited, so a
 * slash-separated clinical value like {@code [120/80]} is left intact.</p>
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
				inputTokens, outputTokens, cachedTokens);
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
				List<Integer> citations = new ArrayList<>();
				JsonNode citationsNode = root.get("citations");
				if (citationsNode != null && citationsNode.isArray()) {
					for (JsonNode n : citationsNode) {
						if (n.isInt()) {
							citations.add(n.asInt());
						}
					}
				}
				String answer = normalizeSlashCitations(answerNode.asText().trim(), citations);
				return new LlmResponse(answer, citations);
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
				// Try to extract citations from whatever was produced — parsed before
				// normalizing the answer so slash groups can be validated against them.
				List<Integer> citations = new ArrayList<>();
				int citationsStart = trimmed.indexOf("\"citations\"");
				if (citationsStart >= 0) {
					Matcher numMatcher = CITATION_NUMBER.matcher(
							trimmed.substring(citationsStart));
					while (numMatcher.find()) {
						citations.add(Integer.parseInt(numMatcher.group(1)));
					}
				}
				answer = normalizeSlashCitations(answer.trim(), citations);
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
	 * Unconditional split: rewrites every {@code [a/b/...]} group into {@code [a], [b], ...}.
	 * Used when no citation context is available to disambiguate citation shorthand from a
	 * slash-separated value. Prefer {@link #normalizeSlashCitations(String, Collection)}.
	 */
	static String normalizeSlashCitations(String text) {
		return normalizeSlashCitations(text, null);
	}

	/**
	 * Rewrites citation shorthand {@code [a/b/...]} into {@code [a], [b], ...}, but only for
	 * groups whose every part appears in {@code validCitations}. This keeps a slash-separated
	 * clinical value the model bracketed — e.g. a blood pressure {@code [120/80]} — intact,
	 * since 120 and 80 are not record numbers the model cited. When {@code validCitations} is
	 * {@code null} the split is unconditional (no context to validate against).
	 *
	 * <p>Trade-off: if the model writes {@code [5/12]} inline but omits 5/12 from its citations
	 * array, the group is left as-is rather than risk mangling a value. The structured citations
	 * array is the authority — chart size is irrelevant, unlike validating against record
	 * indices (where a 150-record chart would wrongly split a {@code [120/80]} value).
	 */
	static String normalizeSlashCitations(String text, Collection<Integer> validCitations) {
		Matcher matcher = SLASH_CITATION.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		StringBuffer sb = new StringBuffer();
		matcher.reset();
		while (matcher.find()) {
			String[] parts = matcher.group(1).split("/");
			if (validCitations != null && !allCited(parts, validCitations)) {
				// Not citation shorthand — a slash-separated value. Leave it untouched.
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
				continue;
			}
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

	/** True only if every {@code part} parses to an integer present in {@code validCitations}. */
	private static boolean allCited(String[] parts, Collection<Integer> validCitations) {
		for (String part : parts) {
			try {
				if (!validCitations.contains(Integer.valueOf(part.trim()))) {
					return false;
				}
			}
			catch (NumberFormatException e) {
				// A part too large to be an int cannot be a record number — treat as not cited.
				return false;
			}
		}
		return true;
	}
}
