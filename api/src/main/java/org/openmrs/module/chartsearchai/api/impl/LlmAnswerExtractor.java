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
 * <p>Also normalizes shorthand citation syntax: {@code [1/2/3]} and compact comma groups
 * {@code [1, 2]} → {@code [1], [2], [3]} — but only when every number in the group is a
 * record the model actually cited, so a bracketed clinical value like {@code [120/80]}
 * or {@code [120, 80]} is left intact.</p>
 */
final class LlmAnswerExtractor {

	private LlmAnswerExtractor() {
	}

	private static final Logger log = LoggerFactory.getLogger(LlmAnswerExtractor.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Pattern SLASH_CITATION = Pattern.compile("\\[(\\d+(?:/\\d+)+)\\]");

	/** Compact comma shorthand: {@code [6, 7]} — two or more indices in one bracket. Measured
	 *  on the rc.2 standalone (2026-07-21, bc4ba445|heart): unrecognized by the single-index
	 *  {@code INLINE_CITATION} pattern, the #76 guard read the answer as citing nothing inline
	 *  and dropped every reference. Normalized like slash shorthand, corroborated-only. */
	private static final Pattern COMMA_CITATION = Pattern.compile("\\[(\\d{1,9}(?:\\s*,\\s*\\d{1,9})+)\\]");

	/**
	 * Matches the JSON "answer" value — captures the string content (may be truncated).
	 * Possessive quantifiers ({@code ++}) prevent backtracking, so this stays stack-safe
	 * even on long truncated answers (the regex engine recurses per alternation choice
	 * point with normal {@code *}).
	 */
	private static final Pattern ANSWER_VALUE = Pattern.compile(
			"\"answer\"\\s*+:\\s*+\"((?:[^\"\\\\]++|\\\\.)*+)\"?+");

	/** Matches an integer inside a citations array, bare or quoted — {@code [1, 2]} and
	 *  {@code ["1", "2"]} alike, for the same reason {@link #citationIndex} accepts both. The
	 *  optional quote is what keeps this path from being STRICTER than the clean-JSON path it exists
	 *  to back up: a model that types its indices as strings can also hit the output-token cap, and
	 *  dropping the citations of a response that is already truncated leaves an answer with no
	 *  references at all. */
	private static final Pattern CITATION_NUMBER = Pattern.compile("(?:^|[,\\[])\\s*\"?(\\d+)");

	/** A citations-array string that names an index: the whole string is one integer, in exactly the
	 *  form JSON allows a number to take — so an optional MINUS and no leading {@code +}, keeping the
	 *  string domain a mirror of the number domain rather than a wider one. Anything else
	 *  ({@code "eight"}, {@code "9x"}, {@code ""}) names no record. */
	private static final Pattern INTEGER_TEXT = Pattern.compile("-?\\d+");

	/** How many unusable citation entries {@link #reportNonConformantCitations} names before it stops
	 *  listing them; the count is always reported in full. Nothing bounds how many entries a model
	 *  can put in the array, and a log line should not be one of them. */
	private static final int MAX_REPORTED_UNUSABLE = 5;

	/** Shared opening of both WARNs {@link #readNonArrayCitations} can emit, so one grep finds every
	 *  non-array {@code citations} container whichever way it was resolved (issue #221). */
	private static final String NON_ARRAY_CITATIONS_MSG =
			"The LLM's citations field was not the array the request's schema asked for";

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
				// An absent field and an explicit null say the same thing — no citations — and
				// neither is reported at any level, deliberately: see readNonArrayCitations (#221).
				if (citationsNode != null && !citationsNode.isNull()) {
					if (citationsNode.isArray()) {
						readCitationsArray(citationsNode, citations);
					} else {
						readNonArrayCitations(citationsNode, citations);
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
						// The schema's "type":"integer" bounds shape, not digit count — a
						// truncation-era degenerate digit run must not NumberFormatException
						// the very salvage path that exists for truncated output. Skip
						// unparseable runs; keep salvaging the rest.
						try {
							citations.add(Integer.parseInt(numMatcher.group(1)));
						}
						catch (NumberFormatException e) {
							log.warn("Skipping unparseable citation number in truncated response: {}…",
									numMatcher.group(1).substring(0, Math.min(20, numMatcher.group(1).length())));
						}
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
	 * The record index a {@code citations} entry names, or {@code null} when it names none. Unchanged
	 * by issue #221, which merely ALSO applies it to the whole {@code citations} value when that is
	 * not an array (see {@link #readNonArrayCitations}) — so the container and its entries admit
	 * exactly the same JSON types, and cannot come to differ.
	 *
	 * <p><b>Why a string counts (issue #219).</b> The module asks for a strict json_schema whose
	 * citation items are {@code "type":"integer"}, but that schema is enforced by the SERVER, not
	 * here: llama-server compiles it to a grammar, while an OpenAI-compatible remote (the engine is
	 * configurable to any base URL) may approximate it, downgrade it to a plain JSON constraint, or
	 * ignore it. So {@code "citations": ["9","10"]} is a shape this parser can be handed, and a
	 * strict {@code isInt()} check dropped it in SILENCE — an answer whose references the model got
	 * right arrived with none, visible only when the prose did not also anchor them inline. The
	 * regex salvage path in {@link #extractResponse(String)} already concedes the same point about
	 * truncation, which is the same class of event: a response that does not honour the schema.
	 *
	 * <p><b>Why coerce rather than reject the response.</b> {@code "9"} has exactly one reading, so
	 * there is nothing to guess — unlike the bracketed groups {@code normalizeSlashCitations} leaves
	 * alone, where {@code [120, 80]} genuinely reads two ways and is presumed a clinical value. And
	 * coercing cannot fabricate: {@code LlmInferenceService.extractCitedReferences} surfaces only
	 * indices with a retrieved record behind them, and discards the array wholesale when the prose
	 * anchors nothing. Rejecting the response instead would throw away the answer — the clinically
	 * useful half, and the half that is not in doubt — over the typing of a field beside it. What
	 * silence cost is fixed separately, by {@link #reportNonConformantCitations}: the outcome is
	 * accepted AND reported, not one or the other.
	 *
	 * <p><b>The bound.</b> This widens which JSON TYPES name an index, never which VALUES do:
	 * {@code 0}, {@code -1} and an index past the end of the chart parse here exactly as they did as
	 * integers, and are still filtered by the one place that knows which records exist. A
	 * non-integral number is not accepted either —
	 * {@code 9.7} would have to be truncated to mean anything, and silently truncating is the
	 * failure mode this change exists to remove, so it is reported as unusable instead. Nothing here
	 * touches PROSE: {@code ChartSearchAiUtils.INLINE_CITATION} stays single-index, and an
	 * uncorroborated bracket in the answer text is still read as a clinical value.
	 */
	private static Integer citationIndex(JsonNode n) {
		if (n.isIntegralNumber() && n.canConvertToInt()) {
			return n.intValue();
		}
		if (n.isTextual()) {
			String text = n.textValue().trim();
			if (INTEGER_TEXT.matcher(text).matches()) {
				try {
					return Integer.valueOf(text);
				}
				catch (NumberFormatException e) {
					// More digits than an int holds — no record can have that index. Falls through to
					// the unusable branch, so it is reported rather than dropped in silence.
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Reads the conformant shape — a {@code citations} ARRAY — appending each index its entries name.
	 * Entry typing is issue #219/#220's subject: a numeric string is coerced through
	 * {@link #citationIndex} and the coercion is reported, an entry naming no index is dropped and
	 * reported. The body is unchanged; extracting it is what lets the call site state the container
	 * decision in one guard — "absent or explicit null: nothing to read and nothing to say" — instead
	 * of leaving that rule inside a compound condition beside an unrelated array test. It is half of
	 * what #221 decided and the half easiest to delete by accident.
	 */
	private static void readCitationsArray(JsonNode citationsNode, List<Integer> citations) {
		int coerced = 0;
		List<String> unusable = new ArrayList<>();
		for (JsonNode n : citationsNode) {
			Integer index = citationIndex(n);
			if (index == null) {
				unusable.add(abbreviate(n.toString()));
			} else {
				if (!n.isIntegralNumber()) {
					coerced++;
				}
				citations.add(index);
			}
		}
		reportNonConformantCitations(coerced, unusable);
	}

	/**
	 * Reads a {@code citations} value that is not an array, appending to {@code citations} the one
	 * index it names — if it names one.
	 *
	 * <p><b>Why this exists (issue #221).</b> #219/#220 fixed how an ENTRY may be typed; the
	 * {@code isArray()} guard above is the same defect one level out. The schema the module sends
	 * declares {@code citations} an array, but the SERVER enforces that, so a remote that
	 * approximates the schema can send a bare value — and the guard discarded the whole field in
	 * silence, exactly as {@code isInt()} discarded string entries.
	 *
	 * <p><b>The split in what is READ.</b> A scalar {@code 8} has exactly one reading: an array of
	 * one. That is the same test #219 applied to {@code "9"}, so it is coerced — through
	 * {@link #citationIndex} rather than a second rule, so the container and its entries admit the
	 * same JSON types and cannot drift apart. Anything with no single reading (an object, a
	 * non-numeric string, {@code 9.7}, a boolean) is left alone: there is nothing to recover, and
	 * picking a reading would widen which VALUES name a record, which is the line #219/#220 drew and
	 * this does not cross.
	 *
	 * <p><b>Both outcomes WARN, and the split above is not a split in reporting.</b> Whichever branch
	 * runs, a provider has broken the array contract the request asked for, and either an index was
	 * recovered by guesswork or one was lost. {@link #reportNonConformantCitations} already WARNs for
	 * the same information loss one level in — an array whose ENTRIES name no index — so reporting
	 * the container case any more quietly would be two channels for one failure. Both lines here
	 * share a prefix with each other for the same reason: one grep finds every non-array container.
	 *
	 * <p><b>Why {@code null} is different, and stays silent.</b> This is the load-bearing distinction
	 * and the two branches must not be collapsed on the strength of both being "not an array". An
	 * explicit {@code null} never reaches here (the caller's guard): it ASSERTS ABSENCE — the same
	 * statement as an omitted field, made by a provider whose answer simply cites nothing — and this
	 * code already does exactly what it says. Nothing is lost and nothing is guessed, so there is
	 * nothing to report; a channel that fires on a provider behaving correctly is worth less than no
	 * channel. Everything that does reach here ASSERTS PRESENCE of something this parser could not
	 * use. Absence honoured is not a defect; presence discarded is.
	 *
	 * <p>The earlier draft of this logged the unreadable case at DEBUG, reasoning that the shape had
	 * no observed instance. That was wrong twice over: the branch cannot fire unless a real provider
	 * really did send an unusable container, so it is not a warning on hypothetical data at all; and
	 * the default {@code org.openmrs.*} level is WARN, so DEBUG would have made it evidence for
	 * someone already looking rather than a signal that arrives.
	 */
	private static void readNonArrayCitations(JsonNode citationsNode, List<Integer> citations) {
		Integer index = citationIndex(citationsNode);
		if (index == null) {
			log.warn(NON_ARRAY_CITATIONS_MSG + ", and names no index — it was dropped: {}",
					abbreviate(citationsNode.toString()));
			return;
		}
		citations.add(index);
		log.warn(NON_ARRAY_CITATIONS_MSG + ": read as the one index its single value names: {}",
				abbreviate(citationsNode.toString()));
	}

	/**
	 * Reports a citations array that did not honour the integer schema — whether the entry was
	 * recovered or dropped.
	 *
	 * <p>Silently discarding a well-formed field was the defect (issue #219), so recovering it is
	 * only half the remedy: an operator whose model has drifted off-schema in this field needs to be
	 * able to find that out, because a model doing this is likely misbehaving in ways this parser
	 * cannot repair. WARN because it always means the model or the server ignored a constraint the
	 * module asked for. A conformant array logs nothing, so this stays a signal rather than noise
	 * every install learns to ignore — and the regex salvage path needs no equivalent, since it
	 * already WARNs on entry that the response did not parse.
	 */
	private static void reportNonConformantCitations(int coerced, List<String> unusable) {
		if (coerced == 0 && unusable.isEmpty()) {
			return;
		}
		log.warn("The LLM's citations array did not honour the integer schema the request asked for: "
				+ "{} index(es) recovered from a numeric string, {} entry(ies) named no index and were "
				+ "dropped {}", coerced, unusable.size(),
				unusable.subList(0, Math.min(MAX_REPORTED_UNUSABLE, unusable.size())));
	}

	/** {@code value} capped for a log line, so one degenerate entry cannot dominate it. */
	private static String abbreviate(String value) {
		return value.length() <= 40 ? value : value.substring(0, 40) + "…";
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
	 * Rewrites citation shorthand — slash groups {@code [a/b/...]} and compact comma groups
	 * {@code [a, b]} — into {@code [a], [b], ...}, but only for groups whose every part appears
	 * in {@code validCitations}. This keeps a bracketed clinical value the model wrote — e.g. a
	 * blood pressure {@code [120/80]} or a value pair {@code [120, 80]} — intact, since 120 and
	 * 80 are not record numbers the model cited. When {@code validCitations} is {@code null}
	 * the slash split is unconditional (no context to validate against; the historical
	 * contract of this overload) and comma groups are left untouched — a comma group is only
	 * ever citation shorthand when the array corroborates it.
	 *
	 * <p>Trade-off: if the model writes {@code [5/12]} or {@code [5, 12]} inline but omits 5/12
	 * from its citations array, the group is left as-is rather than risk mangling a value. The
	 * structured citations array is the authority — chart size is irrelevant, unlike validating
	 * against record indices (where a 150-record chart would wrongly split a {@code [120/80]}
	 * value). Downstream, the single-index {@code INLINE_CITATION} pattern sees no marker in an
	 * untouched group — and when the answer contains NO other single-index marker, the #76
	 * unanchored-array guard then surfaces no references at all for that answer, including any
	 * array entries the group only partially matched. That total blast radius is the accepted
	 * conservative trade-off: an uncorroborated bracket is presumed a value, and a value-bearing
	 * answer with no anchored citations must not ship the model's unanchored review list.
	 */
	static String normalizeSlashCitations(String text, Collection<Integer> validCitations) {
		String normalized = rewriteShorthand(text, SLASH_CITATION, "/", validCitations, validCitations != null);
		if (validCitations != null) {
			normalized = rewriteShorthand(normalized, COMMA_CITATION, ",", validCitations, true);
		}
		return normalized;
	}

	/** One shorthand pass: every {@code pattern} group is split on {@code separator} and, when
	 *  {@code corroborate} is set, rewritten only if {@link #allCited} against
	 *  {@code validCitations}; otherwise left byte-identical. */
	private static String rewriteShorthand(String text, Pattern pattern, String separator,
			Collection<Integer> validCitations, boolean corroborate) {
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		StringBuffer sb = new StringBuffer();
		matcher.reset();
		while (matcher.find()) {
			String[] parts = matcher.group(1).split(separator);
			for (int i = 0; i < parts.length; i++) {
				parts[i] = parts[i].trim();
			}
			if (corroborate && !allCited(parts, validCitations)) {
				// Not citation shorthand — a bracketed value. Leave it untouched.
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
