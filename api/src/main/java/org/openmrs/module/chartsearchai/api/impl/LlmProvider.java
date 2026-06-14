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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Orchestrates LLM inference by constructing prompts, delegating to the active
 * {@link LlmEngine}, and parsing responses. The engine is selected via the
 * {@code chartsearchai.llm.engine} global property ({@code local} or {@code remote}).
 */
@Component
public class LlmProvider {

	private static final Logger log = LoggerFactory.getLogger(LlmProvider.class);

	/** Builds the batch-grounding response_format and parses the verdict array it returns. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Label that prefixes the focus-hint line in the user message. Shared with the
	 *  DEFAULT_SYSTEM_PROMPT few-shot so the demonstration always mirrors the real prompt
	 *  shape — if they drift, the few-shot stops teaching the format the model actually sees. */
	static final String FOCUS_HINT_LABEL = "Records ranked by similarity to the query: ";

	static final String DEFAULT_SYSTEM_PROMPT = "You are a clinical assistant helping a clinician "
			+ "review a patient's chart. Answer ONLY the specific query. "
			+ "Use only the patient records below (sorted most recent first). "
			+ "When the query asks for the latest, current, or most recent value, the relevant "
			+ "record is the FIRST matching one in the list; report that value and do not present "
			+ "an older reading as the current one. "
			+ "Never infer, assume, or add information not explicitly stated in the records. "
			+ "Records beginning with \"Drug reference\" are clinical reference data, not this "
			+ "patient's data; cite them the same way, but never present reference dosing as a value "
			+ "already recorded for the patient. "
			+ "Include ALL relevant records in your answer — never omit any for brevity. "
			+ "Cite EVERY record you reference by its number in brackets (e.g. [1], [3]). "
			+ "Respond with ONLY a JSON object with a \"reasoning\" string, then an \"answer\" string "
			+ "and a \"citations\" array listing every record number you cited. In \"reasoning\", first "
			+ "work out what the query refers to and which records match it by clinical meaning — not "
			+ "just shared words — before you write the answer.\n"
			+ "Use plain text only in the answer — no markdown, no bullet markers like * or -, "
			+ "no headers. Use numbered lines or simple newlines to structure lists.\n\n"
			+ "If no records are relevant, name what is missing.\n"
			+ "Your answer must not vary based on the punctuation or phrasing of the query "
			+ "— focus only on its semantic meaning.\n\n"
			+ "The following is a FORMAT DEMONSTRATION ONLY using fake non-medical data. "
			+ "Do NOT use any of this data in your answer.\n\n"
			+ "Records:\n"
			+ "[1] (2024-03-10) Fruit delivery: 12 apples\n"
			+ "[2] (2024-02-15) Fruit delivery: 8 oranges\n"
			+ "[3] (2024-01-20) Fruit delivery: 5 apples\n\n"
			+ "Clinician's query: How many apples were delivered?\n"
			+ "{\"reasoning\": \"The query is about apples. Records [1] and [3] are apple deliveries; "
			+ "[2] is oranges, a different fruit.\", "
			+ "\"answer\": \"12 apples on 2024-03-10 [1] and 5 apples on 2024-01-20 [3].\","
			+ " \"citations\": [1, 3]}\n\n"
			+ FOCUS_HINT_LABEL + "2.\n"
			+ "Clinician's query: Were any bananas delivered?\n"
			+ "{\"reasoning\": \"The query is about bananas. The ranked record [2] is oranges and no "
			+ "other record mentions bananas, so nothing matches the query.\", "
			+ "\"answer\": \"There are no records of banana deliveries.\", \"citations\": []}\n\n"
			+ "END OF FORMAT DEMONSTRATION. Now answer using ONLY the actual patient records below.";

	@Autowired
	@Qualifier("chartSearchAi.localLlmEngine")
	private LocalLlmEngine localEngine;

	@Autowired
	@Qualifier("chartSearchAi.remoteLlmEngine")
	private RemoteLlmEngine remoteEngine;

	/**
	 * Send numbered patient records and a question to the LLM for synthesis.
	 *
	 * @param numberedRecords the numbered patient records text
	 * @param question the clinician's natural language question
	 * @return the LLM's response with answer text and structured citation indices
	 */
	public LlmResponse search(String numberedRecords, String question) {
		return search(numberedRecords, Collections.<Integer>emptyList(), question);
	}

	/**
	 * Focus-hint variant of {@link #search}: renders a short "Records ranked by similarity to the
	 * query: ..." line between the records section and the question. The numberedRecords
	 * are still the full patient chart in stable date-desc order, so the prompt prefix is
	 * byte-identical across queries for the same patient and llama-server's KV cache reuses
	 * the prefill. The variable bytes are the small focus-hint line plus the question.
	 */
	public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, focusIndices, question);
		int timeoutSeconds = getTimeoutSeconds();

		LlmEngine.InferenceResult result = getActiveEngine().infer(
				systemPrompt, userMessage, timeoutSeconds);

		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	/**
	 * Streaming variant of {@link #search}. Calls the tokenConsumer for each token as it is
	 * generated, and returns the full response when complete.
	 *
	 * @param numberedRecords the numbered patient records text
	 * @param question the clinician's natural language question
	 * @param tokenConsumer called with each token fragment as it is generated
	 * @return the complete LLM response with answer text and structured citation indices
	 */
	public LlmResponse searchStreaming(String numberedRecords, String question,
			Consumer<String> tokenConsumer) {
		return searchStreaming(numberedRecords, Collections.<Integer>emptyList(), question, tokenConsumer);
	}

	/** Focus-hint variant of {@link #searchStreaming}. See {@link #search(String, List, String)}. */
	public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
			Consumer<String> tokenConsumer) {
		return searchStreaming(numberedRecords, focusIndices, question, tokenConsumer, chunk -> { });
	}

	/**
	 * Reasoning-aware streaming variant. Forwards the model's leading {@code "reasoning"} value to
	 * {@code reasoningConsumer} (so a caller can surface it as a live "thinking" indicator) and the
	 * {@code "answer"} value to {@code tokenConsumer}, as each is generated. Two independent
	 * field-scanning {@link AnswerExtractingConsumer}s split the single engine token stream;
	 * reasoning is emitted first (schema order: reasoning precedes answer). The reasoning channel is
	 * purely additive — the answer stream is byte-identical to the non-reasoning overload.
	 */
	public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
		return searchStreaming(numberedRecords, focusIndices, question, tokenConsumer, reasoningConsumer, null);
	}

	/**
	 * KV-scope-aware variant of {@link #searchStreaming(String, List, String, Consumer, Consumer)}.
	 * When {@code cacheScope} is non-null (the pipeline mode produces a question-independent chart
	 * prefix — see {@code LlmInferenceService.shouldRunWarmup}), the engine may restore this
	 * patient's prefilled chart KV from disk instead of re-prefilling, and persist a fresh cold
	 * prefill, so a query arriving cold (server restart, prompt-cache overflow, or warmup never
	 * fired) does not re-pay the full prefill. The KV filename is keyed on the question-INDEPENDENT
	 * prefix {@code buildUserMessage(numberedRecords, "")} — the exact bytes {@link #warmup} sends —
	 * so warmup-saved and query-saved entries share one file per patient+chart. A null scope sends a
	 * null seed, which makes the engine skip all disk KV work (behavior identical to the 5-arg form).
	 */
	public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope) {
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, focusIndices, question);
		// The KV seed must be the question-independent prefix so it matches the warmup key exactly.
		String cacheSeed = cacheScope == null ? null : buildUserMessage(numberedRecords, "");
		int timeoutSeconds = getTimeoutSeconds();

		// Extract the "answer" value (shown to the clinician) and the "reasoning" value (the
		// model's chain-of-thought) from the JSON envelope, each on its own channel.
		AnswerExtractingConsumer answerFilter = new AnswerExtractingConsumer("answer", tokenConsumer);
		AnswerExtractingConsumer reasoningFilter = new AnswerExtractingConsumer("reasoning", reasoningConsumer);
		Consumer<String> tee = chunk -> {
			reasoningFilter.accept(chunk);
			answerFilter.accept(chunk);
		};

		LlmEngine.InferenceResult result = getActiveEngine().inferStreaming(
				systemPrompt, userMessage, timeoutSeconds, tee, cacheScope, cacheSeed);

		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	/**
	 * A streaming token consumer that buffers the raw JSON tokens from the LLM and forwards the
	 * decoded text content of <em>one</em> configured string field — its {@link #key} — to its
	 * delegate, character by character as it arrives.
	 *
	 * <p>The LLM is prompted to produce
	 * {@code {"reasoning": "...", "answer": "...", "citations": [...]}}. Two instances split this
	 * single stream onto two channels: one with {@code key="answer"} feeds the clinician-facing
	 * answer, and one with {@code key="reasoning"} feeds the live "thinking" indicator (the model's
	 * chain-of-thought, emitted first). An instance scans for its key, forwards only that field's
	 * value, and ignores everything else (other fields, punctuation, the citations array). JSON
	 * string escapes (including {@code \\uXXXX}, possibly split across chunks) are decoded so the
	 * streamed text matches the non-streaming path.</p>
	 */
	static class AnswerExtractingConsumer implements Consumer<String> {

		private final Consumer<String> delegate;

		/**
		 * Character-level state machine ({@link #key} is the target field, e.g. {@code "answer"}):
		 * BEFORE_KEY   — scanning for the start of {@link #key}
		 * IN_KEY       — matching characters of {@link #key}
		 * AFTER_KEY    — matched key, looking for {@code :}
		 * AFTER_COLON  — found {@code :}, looking for opening {@code "}
		 * IN_VALUE     — inside the field's string value, forwarding content
		 * DONE         — found closing quote, ignoring remaining tokens
		 */
		private enum State { BEFORE_KEY, IN_KEY, AFTER_KEY, AFTER_COLON, IN_VALUE, DONE }

		private State state = State.BEFORE_KEY;

		/** The quoted JSON key whose string value this instance extracts, e.g. {@code "answer"}
		 *  or {@code "reasoning"}. Two instances (one per key) split the single token stream into
		 *  the answer and the reasoning ("thinking") channels. */
		private final String key;

		/** How many characters of {@link #key} we have matched so far. */
		private int keyMatchPos;

		/** Whether the next character in the field's value is escaped. */
		private boolean escaped;

		/** When > 0, we are partway through a {@code \\uXXXX} escape and this many hex digits
		 *  remain. Held as a field (not a local) because the 4 digits can arrive across separate
		 *  streaming chunks. */
		private int unicodeRemaining;

		/** Accumulated value of the {@code \\uXXXX} hex digits seen so far. */
		private int unicodeValue;

		AnswerExtractingConsumer(Consumer<String> delegate) {
			this("answer", delegate);
		}

		/** Extracts the string value of {@code fieldName} (e.g. {@code answer} or {@code reasoning}). */
		AnswerExtractingConsumer(String fieldName, Consumer<String> delegate) {
			this.key = "\"" + fieldName + "\"";
			this.delegate = delegate;
		}

		@Override
		public void accept(String token) {
			if (state == State.DONE) {
				return;
			}

			if (state == State.IN_VALUE) {
				forwardValueContent(token);
				return;
			}

			// Process character by character until we enter the field's value
			for (int i = 0; i < token.length(); i++) {
				char c = token.charAt(i);
				switch (state) {
					case BEFORE_KEY:
						if (c == key.charAt(0)) {
							keyMatchPos = 1;
							state = State.IN_KEY;
						}
						break;
					case IN_KEY:
						if (c == key.charAt(keyMatchPos)) {
							keyMatchPos++;
							if (keyMatchPos == key.length()) {
								state = State.AFTER_KEY;
							}
						} else {
							// Mismatch — restart. Check if current char starts a new match.
							state = State.BEFORE_KEY;
							if (c == key.charAt(0)) {
								keyMatchPos = 1;
								state = State.IN_KEY;
							}
						}
						break;
					case AFTER_KEY:
						if (c == ':') {
							state = State.AFTER_COLON;
						} else if (c != ' ') {
							// Not the pattern we expected, reset
							state = State.BEFORE_KEY;
							keyMatchPos = 0;
						}
						break;
					case AFTER_COLON:
						if (c == '"') {
							state = State.IN_VALUE;
							// Forward remainder of this token as answer content
							if (i + 1 < token.length()) {
								forwardValueContent(token.substring(i + 1));
							}
							return; // rest of token handled by forwardValueContent
						} else if (c != ' ') {
							// Value isn't a string, reset
							state = State.BEFORE_KEY;
							keyMatchPos = 0;
						}
						break;
					default:
						break;
				}
			}
		}

		/**
		 * Forward characters from {@code text} that are part of the answer
		 * string value, stopping at the unescaped closing double-quote.
		 */
		private void forwardValueContent(String text) {
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (unicodeRemaining > 0) {
					int digit = Character.digit(c, 16);
					if (digit < 0) {
						// Malformed \\uXXXX (a grammar-constrained model shouldn't emit this) —
						// emit the marker literally rather than crash, then handle c normally.
						out.append("\\u");
						unicodeRemaining = 0;
					} else {
						unicodeValue = (unicodeValue << 4) | digit;
						if (--unicodeRemaining == 0) {
							out.append((char) unicodeValue);
						}
						continue;
					}
				}
				if (escaped) {
					switch (c) {
						case 'n':
							out.append('\n');
							break;
						case 't':
							out.append('\t');
							break;
						case 'r':
							out.append('\r');
							break;
						case 'b':
							out.append('\b');
							break;
						case 'f':
							out.append('\f');
							break;
						case '"':
							out.append('"');
							break;
						case '\\':
							out.append('\\');
							break;
						case '/':
							out.append('/');
							break;
						case 'u':
							// Begin a \\uXXXX escape; the 4 hex digits follow (possibly in the
							// next chunk). Mirrors Jackson's decoding on the non-streaming path so
							// streamed and non-streamed answers render identically.
							unicodeRemaining = 4;
							unicodeValue = 0;
							break;
						default:
							out.append('\\').append(c);
							break;
					}
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					state = State.DONE;
					break;
				} else {
					out.append(c);
				}
			}
			if (out.length() > 0) {
				delegate.accept(out.toString());
			}
		}
	}

	static final String ENTAILMENT_SYSTEM_PROMPT = "You are a strict clinical fact-checker. "
			+ "You are given a SOURCE record and a STATEMENT. The SOURCE supports the STATEMENT only "
			+ "if it explicitly states it. It does NOT support the STATEMENT when the STATEMENT is "
			+ "about a different person (e.g. a relative or family history), is negated or denied by "
			+ "the SOURCE, or is simply not stated. Do not use any outside knowledge. "
			// The shared response schema (ChartAnswerResponseFormat) makes the model emit
			// {"reasoning": ..., "answer": ..., "citations": ...} — reasoning first. So tell it to
			// reason briefly, then put the one-word verdict in the answer field; parseEntailmentVerdict
			// reads YES/NO from that answer field, not from the reasoning. Citations stay empty.
			+ "Briefly reason about whether the SOURCE supports the STATEMENT, then put your verdict "
			+ "— the single word YES or NO — in the \"answer\" field, with an empty \"citations\" array.";

	/**
	 * Tier-2 grounding check for a SINGLE {@code (source, statement)} pair: asks the active LLM
	 * whether {@code source} actually supports {@code statement}. It catches the subject/polarity
	 * flips cosine alone cannot judge (high lexical overlap but e.g. "patient has X" vs "mother had
	 * X"). The chart-grounding path now verifies all of an answer's citations in one round-trip via
	 * {@link #entailsBatch}; this single-pair form is retained as the primitive and for one-off
	 * checks.
	 *
	 * @param source the cited record's text
	 * @param statement the answer sentence that cites it
	 * @return {@code TRUE}/{@code FALSE} when the model answers YES/NO,
	 *         {@code null} when the answer is empty or unparseable (caller should
	 *         then fall back to the Tier-1 verdict rather than guess)
	 */
	public Boolean entails(String source, String statement) {
		if (source == null || source.trim().isEmpty() || statement == null || statement.trim().isEmpty()) {
			return null;
		}
		String userMessage = "SOURCE: " + source.trim() + "\nSTATEMENT: " + statement.trim()
				+ "\nDoes the SOURCE support the STATEMENT? Answer YES or NO.";
		LlmEngine.InferenceResult result = getActiveEngine().infer(
				ENTAILMENT_SYSTEM_PROMPT, userMessage, getTimeoutSeconds());
		return parseEntailmentVerdict(result == null ? null : result.getText());
	}

	static final String ENTAILMENT_BATCH_SYSTEM_PROMPT = "You are a strict clinical fact-checker. "
			+ "For EACH numbered pair you are given a SOURCE record and a STATEMENT. A SOURCE "
			+ "supports its STATEMENT only if it explicitly states it. It does NOT support the "
			+ "STATEMENT when the STATEMENT is about a different person (e.g. a relative or family "
			+ "history), is negated or denied by the SOURCE, is unconfirmed or merely suspected, or "
			+ "is simply not stated. Do not use any outside knowledge. Return a \"verdicts\" array "
			+ "with exactly one entry per numbered pair, IN ORDER — each the single word YES or NO.";

	/**
	 * Batch Tier-2 grounding: verifies many {@code (source, statement)} pairs in ONE LLM call,
	 * returning a verdict per pair aligned to the inputs by index. Replaces N sequential
	 * {@link #entails} calls. Measured against the local llama-server (Gemma E2B) it is ~10x faster:
	 * the verdict-only {@link EntailmentBatchResponseFormat} drops the per-call reasoning the
	 * chart-answer schema forces — the dominant, decode-bound cost of a one-word verdict — and pays
	 * one prefill and one round-trip instead of N (the engine is single-slot, so the N calls were
	 * strictly serial). Verdict accuracy matched the per-call path on a 20-pair stress set (19/20
	 * agreement; the lone difference was the batch being correct where the reasoning-laden single
	 * call had reasoned itself wrong).
	 *
	 * @param sources the cited records' texts
	 * @param statements the answer sentences citing them; must be the same length as {@code sources}
	 * @return a list the same size as the inputs: {@code TRUE}/{@code FALSE} per pair, or
	 *         {@code null} where the pair was blank or the model's verdict was missing/unparseable —
	 *         callers fall back to the Tier-1 verdict for those, exactly as for {@link #entails}
	 */
	public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
		if (sources == null || statements == null || sources.size() != statements.size()) {
			throw new IllegalArgumentException(
					"sources and statements must be non-null and the same length");
		}
		int n = sources.size();
		List<Boolean> result = new ArrayList<>(Collections.<Boolean> nCopies(n, null));

		// A blank source or statement can't be checked — leave it null (mirrors entails()). Number
		// only the checkable pairs so the model sees a clean 1..k list; map its k verdicts back to
		// the original positions afterwards.
		List<Integer> positions = new ArrayList<>();
		StringBuilder user = new StringBuilder();
		for (int i = 0; i < n; i++) {
			String source = sources.get(i);
			String statement = statements.get(i);
			if (source == null || source.trim().isEmpty()
					|| statement == null || statement.trim().isEmpty()) {
				continue;
			}
			positions.add(i);
			user.append("PAIR ").append(positions.size()).append(":\n SOURCE: ")
					.append(source.trim()).append("\n STATEMENT: ").append(statement.trim())
					.append('\n');
		}
		if (positions.isEmpty()) {
			return result;
		}

		ObjectNode responseFormat = EntailmentBatchResponseFormat.build(MAPPER, positions.size());
		LlmEngine.InferenceResult inference = getActiveEngine().infer(
				ENTAILMENT_BATCH_SYSTEM_PROMPT, user.toString(), getTimeoutSeconds(), responseFormat);
		List<Boolean> verdicts = parseBatchVerdicts(
				inference == null ? null : inference.getText(), positions.size());
		for (int k = 0; k < positions.size(); k++) {
			result.set(positions.get(k), k < verdicts.size() ? verdicts.get(k) : null);
		}
		return result;
	}

	/**
	 * Reads the YES/NO entailment verdict out of the LLM's raw reply. The shared response schema
	 * ({@link ChartAnswerResponseFormat}) emits a leading {@code "reasoning"} field before
	 * {@code "answer"}, and a fact-checker's reasoning routinely contains words like "no" ("no
	 * explicit mention…") — so scanning the RAW reply for the first YES/NO token (what
	 * {@link #parseYesNo} does) would read the verdict out of the reasoning and flip it. Parse the
	 * structured {@code answer} field first ({@link #extractResponse} ignores reasoning), then read
	 * the verdict from that. Degrades safely: a bare, envelope-free reply ("YES") falls through
	 * extractResponse unchanged, and a null/empty reply yields {@code null}.
	 */
	/** Matches a standalone YES or NO verdict token (word-boundary anchored). */
	private static final java.util.regex.Pattern VERDICT_TOKEN =
			java.util.regex.Pattern.compile("\\b(YES|NO)\\b");

	static Boolean parseEntailmentVerdict(String rawLlmText) {
		if (rawLlmText == null) {
			return null;
		}
		String answer = extractResponse(rawLlmText).getAnswer();
		// A compliant fact-check reply is the single word YES or NO in the answer
		// field. If the model wrote a verbose answer containing BOTH verdict words
		// (e.g. "Yes, but there is no explicit confirmation"), positional parsing
		// could pick the wrong one and silently flip a citation's grounded verdict
		// — so treat it as undecidable and let grounding fall back to its Tier-1
		// verdict rather than guess.
		if (answer != null && hasBothVerdicts(answer)) {
			return null;
		}
		return parseYesNo(answer);
	}

	/** True when {@code text} contains both a standalone YES and a standalone NO token. */
	private static boolean hasBothVerdicts(String text) {
		boolean hasYes = false;
		boolean hasNo = false;
		java.util.regex.Matcher m = VERDICT_TOKEN.matcher(text.toUpperCase(java.util.Locale.ROOT));
		while (m.find()) {
			if ("YES".equals(m.group(1))) {
				hasYes = true;
			} else {
				hasNo = true;
			}
		}
		return hasYes && hasNo;
	}

	/**
	 * Parses a YES/NO entailment reply. Tolerant of surrounding whitespace,
	 * punctuation, casing, and a leading JSON-ish or markdown wrapper — looks
	 * for the first standalone YES or NO token. Returns {@code null} when
	 * neither is found. Callers that must not misread a verbose both-words reply
	 * should screen with {@link #hasBothVerdicts} first (see
	 * {@link #parseEntailmentVerdict}).
	 */
	static Boolean parseYesNo(String text) {
		if (text == null) {
			return null;
		}
		java.util.regex.Matcher m = VERDICT_TOKEN.matcher(text.toUpperCase(java.util.Locale.ROOT));
		if (m.find()) {
			return Boolean.valueOf("YES".equals(m.group(1)));
		}
		return null;
	}

	/**
	 * Reads the {@code "verdicts"} array out of a batch entailment reply
	 * ({@link EntailmentBatchResponseFormat} emits {@code {"verdicts": ["YES","NO",...]}}) into a
	 * list of {@code TRUE}/{@code FALSE}, reusing the tolerant {@link #parseYesNo} token reader.
	 * Defensive so a misbehaving model never breaks grounding: a null/blank reply, a malformed or
	 * envelope-free reply, or a missing array yields an empty list (the caller then falls back to
	 * Tier-1 for the unfilled positions); an element that is neither YES nor NO becomes {@code null}.
	 * The strict json_schema makes the well-formed {@code {"verdicts":[...]}} envelope the norm.
	 *
	 * @param expectedCount the number of pairs sent; used only to log a (schema-should-prevent) size
	 *        mismatch — the array is returned as parsed, and the caller aligns by position
	 */
	static List<Boolean> parseBatchVerdicts(String rawLlmText, int expectedCount) {
		List<Boolean> verdicts = new ArrayList<>();
		if (rawLlmText == null || rawLlmText.trim().isEmpty()) {
			return verdicts;
		}
		try {
			JsonNode array = MAPPER.readTree(rawLlmText).get("verdicts");
			if (array != null && array.isArray()) {
				for (JsonNode element : array) {
					verdicts.add(parseYesNo(element.asText()));
				}
			}
		}
		catch (JsonProcessingException e) {
			log.warn("Could not parse batch entailment verdicts; falling back to Tier-1 ({})",
					e.getMessage());
		}
		if (!verdicts.isEmpty() && verdicts.size() != expectedCount) {
			log.debug("Batch entailment returned {} verdict(s) for {} pair(s)", verdicts.size(),
					expectedCount);
		}
		return verdicts;
	}

	/**
	 * Pre-warm the LLM's prompt cache by sending the same prefix a real query
	 * would, with an empty trailing question. See {@link #buildUserMessage} for
	 * the byte-prefix contract that lets llama-server reuse the cached tokens.
	 */
	public void warmup(String numberedRecords) {
		warmup(numberedRecords, null);
	}

	/**
	 * Scope-aware variant of {@link #warmup(String)}. The {@code cacheScope} (e.g. the patient
	 * UUID) lets an engine that persists its prompt cache to disk group a subject's entries, so a
	 * changed chart replaces the subject's stale entry rather than orphaning it.
	 */
	public void warmup(String numberedRecords, String cacheScope) {
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, "");
		int timeoutSeconds = getTimeoutSeconds();
		getActiveEngine().warmup(systemPrompt, userMessage, timeoutSeconds, cacheScope);
	}

	/**
	 * Whether the active engine benefits from a warmup call. Callers should skip
	 * pre-warmup work (chart serialization, etc.) when this returns false.
	 */
	public boolean supportsWarmup() {
		return getActiveEngine().supportsWarmup();
	}

	/**
	 * Builds the user-message body sent to the LLM, given the numbered patient
	 * records and the clinician's query. This is shared between {@link #search},
	 * {@link #searchStreaming}, and {@link #warmup} so that a warmup with
	 * {@code question = ""} produces a byte-prefix of every real query — that
	 * shared prefix is exactly what llama-server's KV cache reuses.
	 */
	static String buildUserMessage(String numberedRecords, String question) {
		return buildUserMessage(numberedRecords, Collections.<Integer>emptyList(), question);
	}

	/**
	 * Focus-hint variant: when {@code focusIndices} is non-empty, inserts a short
	 * {@code "Records ranked by similarity to the query: 3, 7, 12"} line between the records
	 * section and the {@code "Clinician's query:"} header. The records bytes stay byte-
	 * identical to a no-focus query (and to a warmup call with the same patient), so
	 * llama-server's KV cache reuse contract is preserved up to the focus-hint divergence
	 * point. With ~30 focus indices the hint is ~50 tokens, vs ~870 tokens for a
	 * filtered-records section under the old prefilter contract — that's the source of
	 * the 5-10x LLM-time reduction on local Gemma for same-patient/distinct-query traffic.
	 */
	static String buildUserMessage(String numberedRecords, List<Integer> focusIndices, String question) {
		StringBuilder sb = new StringBuilder();
		sb.append("Patient records (most recent first):\n").append(normalizeRecords(numberedRecords));
		if (focusIndices != null && !focusIndices.isEmpty()) {
			// Four-part hint, calibrated against the 4-patient × 10-query rubric on local
			// Gemma E4B (variants v1-v4 tested; the positive/negative pair below is v2, the
			// empirical winner; the similarity-vs-relevance + abstention clause is v5, added to
			// fix the querystore no-gate failure described below):
			//   1. List the ranked focus records (positive signal). The label says "ranked by
			//      similarity", NOT "most relevant": the querystore focus path
			//      (QueryStoreChartBuilder.searchByPatient + topK) has no relevance gate, so this
			//      list is the K nearest neighbours and is non-empty even when NOTHING in the
			//      chart is about the query. Calling them "most relevant" asserted a relevance the
			//      ranking never established and steered the LLM to answer about off-topic records
			//      ("Is she in any programs?" -> listed conditions on a patient with no programs).
			//   2. "Use these as the starting point. Also include other records ... clinically
			//      about the same topic" — soft permission to expand beyond the focus list,
			//      addresses the "thin answer" mode where the LLM treats the focus list as a
			//      hard cap and misses on-topic records elsewhere in the chart.
			//   3. "Similarity does not guarantee relevance: if none of these records are actually
			//      about the query, answer that no relevant records were found and cite nothing" —
			//      the abstention escape. Without it the focus line overrides the system prompt's
			//      "name what is missing" guidance whenever retrieval fails to gate (i.e. always,
			//      on the querystore path). Paired with the focus-mode abstention few-shot in
			//      DEFAULT_SYSTEM_PROMPT.
			//   4. "Do NOT cite records about unrelated clinical topics" — addresses the
			//      "drift" mode where the LLM cites records that share keywords or chart
			//      position but aren't about the query topic.
			// Two alternative phrasings were tested and rolled back:
			//   - v3 ("LIST EVERY chart record whose primary content is ...") triggered
			//     hallucinations: invented BP values echoing record indices ("699 mmHg [691]",
			//     "714 mmHg [714]"), off-topic listings with self-correcting "Note: this is a
			//     cardiac condition, not mental health" asides, and one topic loss
			//     (karen_sanchez CKD went from kidney-relevant to listing pneumonia and bone
			//     destruction).
			//   - v4 added "Quote numeric values and dates EXACTLY ... do not invent values"
			//     on top of v2 to absorb v3's hallucination risk pre-emptively. v2 didn't
			//     have those hallucinations in the first place, and the extra directive made
			//     the LLM less willing to abstain (it started listing benign Haemangioma when
			//     asked about cancer/tumor, where v2 correctly returned "no records").
			// v2 wins: 40/40 on-topic vs OLD prefilter's 39/40, 16 new_better vs 16 new_worse
			// (net neutral on rubric), no hallucinations observed.
			sb.append("\n\n").append(FOCUS_HINT_LABEL);
			for (int i = 0; i < focusIndices.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(focusIndices.get(i));
			}
			sb.append(".\nUse these as the starting point. Also include other records from the chart "
					+ "that are clinically about the same topic as the query. Similarity does not "
					+ "guarantee relevance: if none of these records are actually about the query, "
					+ "answer that no relevant records were found and cite nothing. Do NOT cite records "
					+ "about unrelated clinical topics, even if they share keywords or appear in "
					+ "the chart.");
		}
		sb.append("\n\nClinician's query: ").append(question);
		return sb.toString();
	}

	private static String normalizeRecords(String numberedRecords) {
		return (numberedRecords == null || numberedRecords.trim().isEmpty())
				? "This patient has no records matching this query."
				: numberedRecords.stripTrailing();
	}

	static LlmResponse extractResponse(String response, int inputTokens, int outputTokens) {
		return LlmAnswerExtractor.extractResponse(response, inputTokens, outputTokens);
	}

	static LlmResponse extractResponse(String response, int inputTokens, int outputTokens,
			int cachedTokens) {
		return LlmAnswerExtractor.extractResponse(response, inputTokens, outputTokens, cachedTokens);
	}

	static LlmResponse extractResponse(String response) {
		return LlmAnswerExtractor.extractResponse(response);
	}

	static String normalizeSlashCitations(String text) {
		return LlmAnswerExtractor.normalizeSlashCitations(text);
	}

	static String normalizeSlashCitations(String text, Collection<Integer> validCitations) {
		return LlmAnswerExtractor.normalizeSlashCitations(text, validCitations);
	}

	static class LlmResponse {

		private final String answer;

		private final List<Integer> citations;

		private final int inputTokens;

		private final int outputTokens;

		private final int cachedTokens;

		LlmResponse(String answer, List<Integer> citations) {
			this(answer, citations, 0, 0, 0);
		}

		LlmResponse(String answer, List<Integer> citations, int inputTokens, int outputTokens) {
			this(answer, citations, inputTokens, outputTokens, 0);
		}

		LlmResponse(String answer, List<Integer> citations, int inputTokens, int outputTokens,
				int cachedTokens) {
			this.answer = answer;
			this.citations = Collections.unmodifiableList(new ArrayList<>(citations));
			this.inputTokens = inputTokens;
			this.outputTokens = outputTokens;
			this.cachedTokens = cachedTokens;
		}

		String getAnswer() {
			return answer;
		}

		List<Integer> getCitations() {
			return citations;
		}

		int getInputTokens() {
			return inputTokens;
		}

		int getOutputTokens() {
			return outputTokens;
		}

		int getCachedTokens() {
			return cachedTokens;
		}
	}

	public void close() {
		localEngine.close();
		remoteEngine.close();
	}

	public void shutdown() {
		localEngine.shutdown();
		remoteEngine.shutdown();
	}

	protected String getSystemPrompt() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SYSTEM_PROMPT);
		if (value != null && !value.trim().isEmpty()) {
			return value.trim();
		}
		return DEFAULT_SYSTEM_PROMPT;
	}

	protected int getTimeoutSeconds() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_TIMEOUT_SECONDS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
				log.warn("Timeout must be positive, got '{}', using default", parsed);
			}
			catch (NumberFormatException e) {
				log.warn("Invalid timeout value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_TIMEOUT_SECONDS;
	}

	LlmEngine getActiveEngine() {
		String engineType = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE);
		if (engineType != null
				&& ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(engineType.trim())) {
			return remoteEngine;
		}
		return localEngine;
	}
}
