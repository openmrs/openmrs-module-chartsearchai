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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link LlmProvider} response parsing and configuration logic.
 */
public class LlmProviderTest {

	@Test
	public void defaultSystemPrompt_shouldMentionClinicalAssistant() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("clinical assistant"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireCitations() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[1]") || LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[2]"));
	}

	@Test
	public void defaultSystemPrompt_shouldConstrainToQuestionAsked() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("ONLY the specific query"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireAllRelevantRecords() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("ALL relevant records"),
				"System prompt must instruct LLM to include ALL relevant records");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("never omit"),
				"System prompt must explicitly tell LLM not to omit records for brevity");
	}

	@Test
	public void defaultSystemPrompt_shouldDescribeRecordOrderingAccurately() {
		// PatientRecordLoader sorts records by date descending (nullsLast) and
		// nothing else — there is no per-type grouping pass. The prompt must
		// describe this accurately so the LLM's mental model of the input
		// matches the actual structure.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("most recent first"),
				"System prompt must describe records as sorted most recent first");
		assertFalse(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("grouped by type"),
				"System prompt must not claim records are grouped by type — they are not");
	}

	@Test
	public void defaultSystemPrompt_shouldInstructAbstentionWhenNoRecordsRelevant() {
		// The few-shot demonstrates abstention in FOCUS mode (a "Records ranked by
		// similarity..." line followed by an empty-citations answer). On the non-focus path
		// (full-chart mode / usePreFilter=false, or any query whose focus list is empty) the
		// user message carries NO ranked line, so this explicit instruction is the sole
		// abstention guidance the model gets. A future prompt edit that drops it would
		// silently strip all "none found" behaviour from the non-focus path with no demo to
		// fall back on — pin it.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("If no records are relevant"),
				"System prompt must keep the explicit abstention instruction for the non-focus "
				+ "path — the focus-mode few-shot does not cover it");
	}

	@Test
	public void defaultSystemPrompt_shouldDemonstrateAbstentionDespiteFocusHint() {
		// In querystore focus-hint mode, retrieval (searchByPatient + topK) has no
		// relevance gate — it always returns the K nearest records, so the user message
		// always carries a non-empty "Records ranked by similarity to the query: ..." line,
		// even when NOTHING in the chart is actually about the query. The few-shot must
		// demonstrate abstaining in exactly that situation, otherwise the model treats the
		// ranked list as proof of relevance and answers about off-topic records (the
		// "Is she in any programs?" -> lists conditions failure on the demo).
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		int rankedAt = prompt.indexOf("Records ranked by similarity to the query:");
		assertTrue(rankedAt > 0,
				"System prompt few-shot must include a focus-hint line in the same "
				+ "'Records ranked by similarity to the query: ...' format the user message uses, "
				+ "so the demonstration matches the real prompt shape. Prompt:\n" + prompt);
		String afterRanked = prompt.substring(rankedAt);
		// The demo object leads with a "reasoning" field (chain-of-thought), so match the
		// abstaining answer + empty citations + closing brace rather than a literal "{...}" — the
		// intent is unchanged: the focus-hint line is immediately followed by an abstaining answer.
		assertTrue(afterRanked.contains(
				"\"answer\": \"There are no records of banana deliveries.\", \"citations\": []}"),
				"The focus-hint line must be immediately followed by the abstaining banana answer "
				+ "(empty citations) — not just any later empty-citations answer. This pins the "
				+ "demonstration order: a non-empty ranked list does NOT guarantee the listed "
				+ "records are about the query, so the correct response can be to cite nothing. A "
				+ "loose check would pass even if the abstention few-shot were reordered before the "
				+ "ranked line or its answer swapped. Prompt:\n" + prompt);
	}

	@Test
	public void extractResponse_shouldIgnoreLeadingReasoningField() {
		// The output schema puts a "reasoning" field first (chain-of-thought). The extractor must
		// read answer/citations and ignore reasoning — it is the model's scratchpad, never shown.
		String response = "{\"reasoning\": \"Hearing Loss is an ear-related condition, so [89] is "
				+ "relevant.\", \"answer\": \"Hearing Loss [89].\", \"citations\": [89]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Hearing Loss [89].", result.getAnswer());
		assertEquals(Arrays.asList(89), result.getCitations());
	}

	@Test
	public void defaultSystemPrompt_fewShotShouldDemonstrateReasoningField() {
		// The few-shot demo answers must match the schema shape (reasoning first), or the
		// demonstrated format contradicts the grammar the model is constrained to.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("\"reasoning\":"),
				"Few-shot demo answers must include a \"reasoning\" field so the demonstrated "
				+ "format matches the response schema");
	}

	/** Feeds {@code json} to a fresh AnswerExtractingConsumer in the given chunk sizes and
	 *  returns what reached the client. chunkSize 0 means feed the whole string at once. */
	private static String streamThrough(String json, int chunkSize) {
		StringBuilder out = new StringBuilder();
		Consumer<String> delegate = out::append;
		LlmProvider.AnswerExtractingConsumer consumer = new LlmProvider.AnswerExtractingConsumer(delegate);
		if (chunkSize <= 0) {
			consumer.accept(json);
		} else {
			for (int i = 0; i < json.length(); i += chunkSize) {
				consumer.accept(json.substring(i, Math.min(i + chunkSize, json.length())));
			}
		}
		return out.toString();
	}

	/** Splits {@code json} into the (reasoning, answer) channels the streaming path uses: two
	 *  field-scanning consumers fed the same stream, exactly as LlmProvider.searchStreaming tees. */
	private static String[] streamSplit(String json, int chunkSize) {
		StringBuilder reasoning = new StringBuilder();
		StringBuilder answer = new StringBuilder();
		LlmProvider.AnswerExtractingConsumer r = new LlmProvider.AnswerExtractingConsumer("reasoning", reasoning::append);
		LlmProvider.AnswerExtractingConsumer a = new LlmProvider.AnswerExtractingConsumer("answer", answer::append);
		Consumer<String> tee = chunk -> { r.accept(chunk); a.accept(chunk); };
		if (chunkSize <= 0) {
			tee.accept(json);
		} else {
			for (int i = 0; i < json.length(); i += chunkSize) {
				tee.accept(json.substring(i, Math.min(i + chunkSize, json.length())));
			}
		}
		return new String[] { reasoning.toString(), answer.toString() };
	}

	@Test
	public void streamingConsumer_shouldSplitReasoningAndAnswerOntoSeparateChannels() {
		// The "thinking" feature: the reasoning channel must receive ONLY the reasoning value and
		// the answer channel ONLY the answer value — no cross-leak — even when the stream arrives
		// char-by-char. Reasoning contains a quoted span and [n] markers to stress the scanner.
		String json = "{\"reasoning\": \"The query is about ears; record [89] is 'Hearing Loss', an "
				+ "ear problem.\", \"answer\": \"Hearing Loss [89].\", \"citations\": [89]}";
		String expReasoning = "The query is about ears; record [89] is 'Hearing Loss', an ear problem.";
		for (int chunk : new int[] { 0, 1, 5 }) {
			String[] ra = streamSplit(json, chunk);
			assertEquals(expReasoning, ra[0], "reasoning channel must get the full reasoning value (chunk=" + chunk + ")");
			assertEquals("Hearing Loss [89].", ra[1], "answer channel must get only the answer (chunk=" + chunk + ")");
		}
	}

	@Test
	public void streamingConsumer_shouldNotLeakLeadingReasoningField() {
		// The schema emits "reasoning" FIRST, before "answer". The streaming path (/search/stream)
		// must forward only the answer value to the clinician — never the model's reasoning
		// scratchpad. Exercised both as one chunk and char-by-char (real token streams arrive in
		// arbitrary fragments, so the state machine must skip reasoning across chunk boundaries).
		String json = "{\"reasoning\": \"Hearing Loss is an ear-related condition, so record [89] "
				+ "is relevant to the query about ears.\", \"answer\": \"Hearing Loss [89].\", "
				+ "\"citations\": [89]}";
		assertEquals("Hearing Loss [89].", streamThrough(json, 0),
				"whole-string: only the answer value must reach the client, not reasoning");
		assertEquals("Hearing Loss [89].", streamThrough(json, 1),
				"char-by-char: reasoning must be skipped across token boundaries too");
		assertEquals("Hearing Loss [89].", streamThrough(json, 7),
				"7-char chunks: reasoning must be skipped regardless of fragment alignment");
	}

	@Test
	public void streamingConsumer_shouldDecodeUnicodeEscapes() {
		// The non-streaming path decodes \\uXXXX via Jackson (see
		// extractResponse_shouldDecodeUnicodeEscapes); the streaming consumer must match, or a
		// streamed clinical value like "38.9°C" reaches the clinician as literal "38.9\\u00b0C".
		// The 4 hex digits must also decode when they straddle streaming-chunk boundaries.
		String json = "{\"reasoning\": \"unit conversion\", \"answer\": \"Temperature is "
				+ "38.9\\u00b0C\", \"citations\": [1]}";
		assertEquals("Temperature is 38.9°C", streamThrough(json, 0),
				"whole-string: streamed answer must decode \\uXXXX like the non-streaming path");
		assertEquals("Temperature is 38.9°C", streamThrough(json, 1),
				"char-by-char: \\uXXXX must decode even when its hex digits span chunk boundaries");
	}

	@Test
	public void streamingConsumer_shouldDecodeControlCharEscapes() {
		// JSON control-char escapes (\r, \b, \f) must decode like Jackson on the non-streaming
		// path — otherwise a streamed answer with one shows the literal backslash sequence.
		String json = "{\"reasoning\": \"x\", \"answer\": \"line1\\r\\nline2\\tend\", \"citations\": []}";
		assertEquals("line1\r\nline2\tend", streamThrough(json, 0));
		assertEquals("line1\r\nline2\tend", streamThrough(json, 1));
	}

	@Test
	public void streamingConsumer_reasoningMentioningAnswerWordShouldNotFalseTrigger() {
		// Adversarial: the reasoning text itself contains the escaped word \"answer\". An escaped
		// quote inside the reasoning value must NOT be mistaken for the real "answer" key, or the
		// client would see reasoning text. Only the genuine answer value may be forwarded.
		String json = "{\"reasoning\": \"The query literally says \\\"answer\\\" but I judge by "
				+ "meaning.\", \"answer\": \"No relevant records.\", \"citations\": []}";
		assertEquals("No relevant records.", streamThrough(json, 0));
		assertEquals("No relevant records.", streamThrough(json, 1));
	}

	@Test
	public void extractResponse_shouldParseJsonWithCitations() {
		String response = "{\"answer\": \"The patient has Hypertension [48] and Diabetes [49].\", \"citations\": [48, 49]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("The patient has Hypertension [48] and Diabetes [49].", result.getAnswer());
		assertEquals(Arrays.asList(48, 49), result.getCitations());
	}

	@Test
	public void extractResponse_shouldHandleEmptyCitations() {
		String response = "{\"answer\": \"There are no records about diabetes in this patient's chart.\", \"citations\": []}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("There are no records about diabetes in this patient's chart.", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldHandleEscapedQuotes() {
		String response = "{\"answer\": \"The patient said \\\"I feel fine\\\" during the visit.\", \"citations\": []}";
		assertEquals("The patient said \"I feel fine\" during the visit.",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEscapedBackslashes() {
		String response = "{\"answer\": \"Path: C:\\\\Users\\\\data\", \"citations\": []}";
		assertEquals("Path: C:\\Users\\data",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEscapedNewlines() {
		String response = "{\"answer\": \"Line 1\\nLine 2\", \"citations\": []}";
		assertEquals("Line 1\nLine 2",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEmptyString() {
		LlmProvider.LlmResponse result = LlmProvider.extractResponse("");
		assertEquals("", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldFallBackToRawResponseWhenNotJson() {
		String response = "The patient has Diabetes [1].";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals(response, result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldDecodeUnicodeEscapes() {
		String response = "{\"answer\": \"Temperature is 38.9\\u00b0C\", \"citations\": [1]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Temperature is 38.9\u00b0C", result.getAnswer());
		assertEquals(Arrays.asList(1), result.getCitations());
	}

	@Test
	public void extractResponse_shouldHandleWhitespaceInJson() {
		String response = "{ \"answer\" : \"There are no records about diabetes in this patient's chart.\" , \"citations\" : [] }";
		assertEquals("There are no records about diabetes in this patient's chart.",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleMissingCitationsField() {
		String response = "{\"answer\": \"Some answer.\"}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Some answer.", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void normalizeSlashCitations_shouldConvertSlashesToSeparateBrackets() {
		assertEquals("Tuberculosis [1], [2] and Malaria [3], [4]",
				LlmProvider.normalizeSlashCitations("Tuberculosis [1/2] and Malaria [3/4]"));
	}

	@Test
	public void normalizeSlashCitations_shouldHandleTripleSlash() {
		assertEquals("Infections [5], [12], [15]",
				LlmProvider.normalizeSlashCitations("Infections [5/12/15]"));
	}

	@Test
	public void normalizeSlashCitations_shouldLeaveNormalCitationsUnchanged() {
		assertEquals("Has condition [1], [2].",
				LlmProvider.normalizeSlashCitations("Has condition [1], [2]."));
	}

	@Test
	public void normalizeSlashCitations_shouldLeaveTextWithNoCitationsUnchanged() {
		assertEquals("No citations here.",
				LlmProvider.normalizeSlashCitations("No citations here."));
	}

	@Test
	public void extractResponse_shouldNormalizeSlashCitationsInAnswer() {
		String response = "{\"answer\": \"Infections [7/13] and HIV [4/11].\", \"citations\": [7, 13, 4, 11]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Infections [7], [13] and HIV [4], [11].", result.getAnswer());
		assertEquals(Arrays.asList(7, 13, 4, 11), result.getCitations());
	}

	@Test
	public void extractResponse_shouldPreserveClinicalSlashTerms() {
		String response = "{\"answer\": \"The patient has HIV/AIDS [1] and nausea/vomiting [2].\", \"citations\": [1, 2]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("The patient has HIV/AIDS [1] and nausea/vomiting [2].", result.getAnswer());
	}

	@Test
	public void extractResponse_shouldNotSplitBracketedValueAbsentFromCitations() {
		// A blood-pressure value the model bracketed as [120/80] must NOT be rewritten into
		// citation markers [120], [80] — 120 and 80 are not in the citations array, so they
		// are a slash-separated clinical value, not citation shorthand.
		String response = "{\"answer\": \"Blood pressure was [120/80] mmHg [1].\", \"citations\": [1]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Blood pressure was [120/80] mmHg [1].", result.getAnswer());
		assertEquals(Arrays.asList(1), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNotSplitSlashGroupWhenAnyPartAbsentFromCitations() {
		// [5/120]: 5 is a cited record but 120 is not. Splitting would emit a half-correct
		// [5], [120]; the whole group must be left untouched unless EVERY part is cited.
		String response = "{\"answer\": \"Reading [5/120] noted [5].\", \"citations\": [5]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Reading [5/120] noted [5].", result.getAnswer());
	}

	@Test
	public void normalizeSlashCitations_withCitations_shouldSplitOnlyWhollyCitedGroups() {
		// [1/2] -> both cited -> split; [120/80] -> neither cited -> preserved as a value.
		assertEquals("BP [120/80] and conditions [1], [2]",
				LlmProvider.normalizeSlashCitations("BP [120/80] and conditions [1/2]", Arrays.asList(1, 2)));
	}

	@Test
	public void extractResponse_fallbackPath_shouldValidateSlashGroupsAgainstCitations() {
		// Missing closing brace forces the regex-fallback path (Jackson rejects it). Even there,
		// a slash value [120/80] must be preserved while a genuinely cited group [1/2] still splits.
		String malformed = "{\"answer\": \"BP [120/80] and conditions [1/2]\", \"citations\": [1, 2]";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(malformed);
		assertEquals("BP [120/80] and conditions [1], [2]", result.getAnswer());
		assertEquals(Arrays.asList(1, 2), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNotStackOverflowOnLongTruncatedJson() {
		// Reproduces the production failure: an LLM answer that hits max_tokens mid-string
		// produces a long JSON missing its closing quote and brace, sending Jackson into the
		// regex fallback path. The recursive (?:|)* alternation in ANSWER_VALUE used to blow
		// the JVM stack on inputs above ~30KB, returning HTTP 500.
		StringBuilder sb = new StringBuilder("{\"answer\": \"");
		for (int i = 0; i < 4000; i++) {
			sb.append("On 2026-01-").append(i % 28 + 1)
					.append(" the patient had encounter [").append(i).append("]. ");
		}
		// no closing quote, no closing brace — this is what we get when the model is cut off
		String truncated = sb.toString();
		assertTrue(truncated.length() > 30000,
				"need a long input to trigger the original stack overflow");

		LlmProvider.LlmResponse result = LlmProvider.extractResponse(truncated);
		assertTrue(result.getAnswer().startsWith("On 2026-01-1 the patient had encounter [0]"),
				"should recover the answer prefix from a truncated JSON, got: "
						+ result.getAnswer().substring(0, Math.min(80, result.getAnswer().length())));
	}

}
