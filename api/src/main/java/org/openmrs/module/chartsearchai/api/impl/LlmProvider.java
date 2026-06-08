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
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, focusIndices, question);
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
				systemPrompt, userMessage, timeoutSeconds, tee);

		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	/**
	 * A streaming token consumer that buffers the raw JSON tokens from the LLM
	 * and only forwards text content from inside the {@code "answer"} value.
	 *
	 * <p>The LLM is prompted to produce
	 * {@code {"reasoning": "...", "answer": "...", "citations": [...]}}. The {@code reasoning}
	 * field is emitted FIRST (the model's chain-of-thought) and must NOT reach the client: the
	 * state machine scans for the {@code "answer"} key and forwards only its value, so the
	 * leading reasoning tokens are silently consumed. (Trade-off: the visible answer starts
	 * streaming only after reasoning finishes generating.) This consumer processes each
	 * character through a simple state machine and only forwards characters that belong to the
	 * answer string value.</p>
	 */
	static class AnswerExtractingConsumer implements Consumer<String> {

		private final Consumer<String> delegate;

		/**
		 * Character-level state machine:
		 * BEFORE_KEY   — scanning for the start of {@code "answer"}
		 * IN_KEY       — matching characters of {@code "answer"}
		 * AFTER_KEY    — matched key, looking for {@code :}
		 * AFTER_COLON  — found {@code :}, looking for opening {@code "}
		 * IN_VALUE     — inside the answer string, forwarding content
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

		/** Whether the next character in the answer value is escaped. */
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

			// Process character by character until we enter the answer value
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

	/**
	 * Pre-warm the LLM's prompt cache by sending the same prefix a real query
	 * would, with an empty trailing question. See {@link #buildUserMessage} for
	 * the byte-prefix contract that lets llama-server reuse the cached tokens.
	 */
	public void warmup(String numberedRecords) {
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, "");
		int timeoutSeconds = getTimeoutSeconds();
		getActiveEngine().warmup(systemPrompt, userMessage, timeoutSeconds);
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
