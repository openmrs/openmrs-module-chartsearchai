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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.model.ChatMessage;
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

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final String DEFAULT_SYSTEM_PROMPT = "You are a clinical assistant helping a clinician "
			+ "review a patient's chart. Answer ONLY the specific query. "
			+ "Use only the patient records below (sorted most recent first). "
			+ "Never infer, assume, or add information not explicitly stated in the records. "
			+ "Include ALL relevant records in your answer — never omit any for brevity. "
			+ "Cite EVERY record you reference by its number in brackets (e.g. [1], [3]). "
			+ "Respond with ONLY a JSON object matching the schema: "
			+ "\"answer\" (string), \"citations\" (array of ints), \"blocks\" (array, may be empty).\n\n"
			+ "STRUCTURED TABLES — IMPORTANT:\n"
			+ "If the query asks to LIST, SHOW, ENUMERATE, or TABULATE multiple items (medications, "
			+ "allergies, lab results, vital signs, problems, diagnoses, encounters, orders, "
			+ "immunizations, procedures), you MUST emit a \"table\" block in \"blocks\" with "
			+ "columns + rows. The \"answer\" string gives a brief one-sentence prose summary; "
			+ "the table carries the structured detail.\n"
			+ "Table construction rules:\n"
			+ "  - ONE ROW PER UNIQUE ITEM. \"List medications\" → one row per unique medication "
			+ "name, NOT one row per prescription record. Repeated record indices for the same "
			+ "item all go into that single row's cell.refs array.\n"
			+ "  - Pick semantic columns from the data (e.g. medications → Medication, Dose, "
			+ "Route, Start date). Do NOT add a \"References\" or \"Citations\" or \"Indices\" "
			+ "column — record indices belong in each cell's \"refs\" array, NOT in a column.\n"
			+ "  - Column keys must be unique and must NOT be \"text\" or \"refs\" (those are "
			+ "reserved cell field names).\n"
			+ "  - Each row's cells map column key → {\"text\": ..., \"refs\": [record indices]}. "
			+ "Cells with no citation use refs: [].\n"
			+ "Only leave \"blocks\" as [] for single-fact answers (e.g. patient age, gender, "
			+ "yes/no questions).\n\n"
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
			+ "{\"answer\": \"12 apples on 2024-03-10 [1] and 5 apples on 2024-01-20 [3].\","
			+ " \"citations\": [1, 3], \"blocks\": []}\n\n"
			+ "Clinician's query: Show all fruit deliveries.\n"
			+ "{\"answer\": \"Three fruit deliveries on record [1][2][3].\","
			+ " \"citations\": [1, 2, 3], \"blocks\": [{"
			+ "\"kind\": \"table\", \"title\": \"Fruit deliveries\","
			+ " \"columns\": [{\"key\":\"date\",\"label\":\"Date\"},"
			+ "{\"key\":\"fruit\",\"label\":\"Fruit\"},"
			+ "{\"key\":\"qty\",\"label\":\"Qty\"}],"
			+ " \"rows\": ["
			+ "{\"cells\":{\"date\":{\"text\":\"2024-03-10\",\"refs\":[1]},"
			+ "\"fruit\":{\"text\":\"apples\",\"refs\":[1]},"
			+ "\"qty\":{\"text\":\"12\",\"refs\":[1]}}},"
			+ "{\"cells\":{\"date\":{\"text\":\"2024-02-15\",\"refs\":[2]},"
			+ "\"fruit\":{\"text\":\"oranges\",\"refs\":[2]},"
			+ "\"qty\":{\"text\":\"8\",\"refs\":[2]}}},"
			+ "{\"cells\":{\"date\":{\"text\":\"2024-01-20\",\"refs\":[3]},"
			+ "\"fruit\":{\"text\":\"apples\",\"refs\":[3]},"
			+ "\"qty\":{\"text\":\"5\",\"refs\":[3]}}}"
			+ "]}]}\n\n"
			+ "Clinician's query: Were any bananas delivered?\n"
			+ "{\"answer\": \"There are no records of banana deliveries.\","
			+ " \"citations\": [], \"blocks\": []}\n\n"
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
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, question);
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
		String systemPrompt = getSystemPrompt();
		String userMessage = buildUserMessage(numberedRecords, question);
		int timeoutSeconds = getTimeoutSeconds();

		// Wrap the consumer to extract only the "answer" value from the JSON
		// envelope the LLM generates, so the UI sees clean text instead of raw JSON.
		AnswerExtractingConsumer filter = new AnswerExtractingConsumer(tokenConsumer);

		LlmEngine.InferenceResult result = getActiveEngine().inferStreaming(
				systemPrompt, userMessage, timeoutSeconds, filter);

		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	/**
	 * A streaming token consumer that buffers the raw JSON tokens from the LLM
	 * and only forwards text content from inside the {@code "answer"} value.
	 *
	 * <p>The LLM is prompted to produce {@code {"answer": "...", "citations": [...]}}.
	 * This consumer processes each character through a simple state machine and only
	 * forwards characters that belong to the answer string value.</p>
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

		private static final String ANSWER_KEY = "\"answer\"";

		/** How many characters of ANSWER_KEY we have matched so far. */
		private int keyMatchPos;

		/** Whether the next character in the answer value is escaped. */
		private boolean escaped;

		AnswerExtractingConsumer(Consumer<String> delegate) {
			this.delegate = delegate;
		}

		@Override
		public void accept(String token) {
			if (state == State.DONE) {
				return;
			}

			if (state == State.IN_VALUE) {
				forwardAnswerContent(token);
				return;
			}

			// Process character by character until we enter the answer value
			for (int i = 0; i < token.length(); i++) {
				char c = token.charAt(i);
				switch (state) {
					case BEFORE_KEY:
						if (c == ANSWER_KEY.charAt(0)) {
							keyMatchPos = 1;
							state = State.IN_KEY;
						}
						break;
					case IN_KEY:
						if (c == ANSWER_KEY.charAt(keyMatchPos)) {
							keyMatchPos++;
							if (keyMatchPos == ANSWER_KEY.length()) {
								state = State.AFTER_KEY;
							}
						} else {
							// Mismatch — restart. Check if current char starts a new match.
							state = State.BEFORE_KEY;
							if (c == ANSWER_KEY.charAt(0)) {
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
								forwardAnswerContent(token.substring(i + 1));
							}
							return; // rest of token handled by forwardAnswerContent
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
		private void forwardAnswerContent(String text) {
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (escaped) {
					switch (c) {
						case 'n':
							out.append('\n');
							break;
						case 't':
							out.append('\t');
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
	 * Multi-turn variant: assembles {@code [system, ...priorTurns..., currentUser]}
	 * with drop-oldest budget trimming via {@link ChatMessages#fromTurns} and
	 * hands the prebuilt array to the engine.
	 *
	 * <p>Note: only the CURRENT user message carries the patient chart. Prior
	 * user turns store just the original question text (not chart+question), so
	 * the chart never appears twice in the message array and stale chart copies
	 * never confuse the LLM if data changed between turns.
	 */
	/**
	 * Multi-turn chat assembly using the stable system+chart prefix design
	 * ({@link ChatMessages#assembleChat}). The {@code chartEnvelope} is the
	 * frozen-per-session bytes (typically "Patient records (most recent
	 * first):\n<numbered records>") and stays byte-identical across all
	 * turns; the {@code question} is just the raw clinician text; priors
	 * carry user/assistant pairs (no chart bytes inside).
	 *
	 * <p>This produces:
	 * <pre>
	 *   [system, user(chartEnvelope), ...priors..., user(question)]
	 * </pre>
	 * which hits the LLM server's prompt cache on every follow-up because
	 * the first two messages are byte-identical across turns of a session.
	 */
	public LlmResponse chat(String chartEnvelope, List<ChatMessage> priorTurns, String question) {
		String systemPrompt = getSystemPrompt();
		int maxTokens = getMaxContextTokens();
		int responseReserve = ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;
		ArrayNode messages = ChatMessages.assembleChat(
				MAPPER, systemPrompt, chartEnvelope, priorTurns, question, maxTokens, responseReserve);
		int includedPriors = messages.size() - 3; // size minus [system, chart_user, current_user]
		log.debug("chat: priors available={}, included={}, chart_chars={}, budget={} tokens",
				priorTurns == null ? 0 : priorTurns.size(), Math.max(0, includedPriors),
				chartEnvelope == null ? 0 : chartEnvelope.length(), maxTokens);
		LlmEngine.InferenceResult result = getActiveEngine().infer(messages, getTimeoutSeconds());
		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	/**
	 * Streaming multi-turn variant. Same assembly contract as
	 * {@link #chat(String, List, String)}; tokenConsumer receives unwrapped
	 * answer text via {@link AnswerExtractingConsumer}.
	 */
	public LlmResponse chatStreaming(String chartEnvelope, List<ChatMessage> priorTurns,
			String question, Consumer<String> tokenConsumer) {
		String systemPrompt = getSystemPrompt();
		int maxTokens = getMaxContextTokens();
		int responseReserve = ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;
		ArrayNode messages = ChatMessages.assembleChat(
				MAPPER, systemPrompt, chartEnvelope, priorTurns, question, maxTokens, responseReserve);
		int includedPriors = messages.size() - 3;
		log.debug("chatStreaming: priors available={}, included={}, chart_chars={}, budget={} tokens",
				priorTurns == null ? 0 : priorTurns.size(), Math.max(0, includedPriors),
				chartEnvelope == null ? 0 : chartEnvelope.length(), maxTokens);

		AnswerExtractingConsumer filter = new AnswerExtractingConsumer(tokenConsumer);

		LlmEngine.InferenceResult result = getActiveEngine().inferStreaming(
				messages, getTimeoutSeconds(), filter);

		return extractResponse(result.getText(), result.getInputTokens(), result.getOutputTokens(),
				result.getCachedTokens());
	}

	protected int getMaxContextTokens() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_CHAT_MAX_CONTEXT_TOKENS);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
				log.warn("chartsearchai.chat.maxContextTokens must be positive, got '{}', using default", parsed);
			}
			catch (NumberFormatException e) {
				log.warn("Invalid chartsearchai.chat.maxContextTokens '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_CHAT_MAX_CONTEXT_TOKENS;
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
		return "Patient records (most recent first):\n" + normalizeRecords(numberedRecords)
				+ "\n\nClinician's query: " + question;
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

	static class LlmResponse {

		private final String answer;

		private final List<Integer> citations;

		private final List<ResponseBlock> blocks;

		private final int inputTokens;

		private final int outputTokens;

		private final int cachedTokens;

		LlmResponse(String answer, List<Integer> citations) {
			this(answer, citations, Collections.emptyList(), 0, 0, 0);
		}

		LlmResponse(String answer, List<Integer> citations, int inputTokens, int outputTokens) {
			this(answer, citations, Collections.emptyList(), inputTokens, outputTokens, 0);
		}

		LlmResponse(String answer, List<Integer> citations, int inputTokens, int outputTokens,
				int cachedTokens) {
			this(answer, citations, Collections.emptyList(), inputTokens, outputTokens, cachedTokens);
		}

		LlmResponse(String answer, List<Integer> citations, List<ResponseBlock> blocks,
				int inputTokens, int outputTokens, int cachedTokens) {
			this.answer = answer;
			this.citations = Collections.unmodifiableList(new ArrayList<>(citations));
			this.blocks = blocks == null
					? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(blocks));
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

		List<ResponseBlock> getBlocks() {
			return blocks;
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
