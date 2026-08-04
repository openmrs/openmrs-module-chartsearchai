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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * Tests LLM answer quality for multi-concept trend queries by calling an
 * OpenAI-compatible endpoint (e.g. llama-server). Skipped automatically
 * unless the system property is set and the endpoint is reachable.
 *
 * <p>Run with: {@code -Dchartsearchai.llm.quality.test=true} and optionally
 * {@code -Dchartsearchai.llm.quality.endpoint=http://localhost:9999/v1/chat/completions}</p>
 */
public class LlmAnswerQualityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String DEFAULT_ENDPOINT = "http://localhost:18085/v1/chat/completions";

	// 28 records grouped by concept, matching production pipeline output.
	private static final String RECORDS =
			"Patient: 16-year-old Male\n\n"
			+ "[1] (2025-09-17) Test — Systolic Blood Pressure: 97.0 mmHg\n"
			+ "[2] (2025-06-29) Test — Systolic Blood Pressure: 122.0 mmHg\n"
			+ "[3] (2025-06-28) Test — Systolic Blood Pressure: 101.0 mmHg\n"
			+ "[4] (2023-04-30) Test — Systolic Blood Pressure: 123.0 mmHg\n"
			+ "[5] (2022-10-07) Test — Systolic Blood Pressure: 137.0 mmHg\n"
			+ "[6] (2022-01-16) Test — Systolic Blood Pressure: 147.0 mmHg\n"
			+ "[7] (2021-11-30) Test — Systolic Blood Pressure: 98.0 mmHg\n"
			+ "[8] (2025-09-17) Test — Diastolic Blood Pressure: 99.0 mmHg\n"
			+ "[9] (2025-09-17) Test — Diastolic Blood Pressure: 92.0 mmHg\n"
			+ "[10] (2025-06-28) Test — Diastolic Blood Pressure: 99.0 mmHg\n"
			+ "[11] (2023-04-29) Test — Diastolic Blood Pressure: 71.0 mmHg\n"
			+ "[12] (2022-10-07) Test — Diastolic Blood Pressure: 67.0 mmHg\n"
			+ "[13] (2022-04-27) Test — Diastolic Blood Pressure: 105.0 mmHg\n"
			+ "[14] (2022-01-16) Test — Diastolic Blood Pressure: 58.0 mmHg\n"
			+ "[15] (2025-10-30) Test — Weight (kg): 94.0 kg\n"
			+ "[16] (2025-09-17) Test — Weight (kg): 107.0 kg\n"
			+ "[17] (2025-09-17) Test — Weight (kg): 139.0 kg\n"
			+ "[18] (2023-04-29) Test — Weight (kg): 38.0 kg\n"
			+ "[19] (2022-10-07) Test — Weight (kg): 146.0 kg\n"
			+ "[20] (2022-01-16) Test — Weight (kg): 68.0 kg\n"
			+ "[21] (2021-11-30) Test — Weight (kg): 121.0 kg\n"
			+ "[22] (2025-10-30) Test — Temperature (C): 36.7 DEG C\n"
			+ "[23] (2025-09-17) Test — Temperature (C): 37.7 DEG C\n"
			+ "[24] (2025-06-29) Test — Temperature (C): 40.3 DEG C\n"
			+ "[25] (2022-10-07) Test — Temperature (C): 39.3 DEG C\n"
			+ "[26] (2022-01-16) Test — Temperature (C): 36.4 DEG C\n"
			+ "[27] (2021-11-30) Test — Temperature (C): 37.8 DEG C\n"
			+ "[28] (2021-09-16) Test — Temperature (C): 40.1 DEG C\n";

	private static final String QUESTION =
			"How have this patient's blood pressure, weight, and temperature "
			+ "trended across their last 7 visits?";

	private static final int TOTAL_RECORDS = 28;

	private static final int MIN_REQUIRED_CITATIONS = 20;

	private static final Set<String> REQUIRED_CONCEPTS = new HashSet<>(Arrays.asList(
			"Systolic Blood Pressure", "Diastolic Blood Pressure",
			"Weight", "Temperature"));

	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

	private static String getEndpoint() {
		String explicit = System.getProperty("chartsearchai.llm.quality.endpoint");
		return (explicit != null && !explicit.isEmpty()) ? explicit : DEFAULT_ENDPOINT;
	}

	private static boolean isEndpointReachable() {
		try {
			String healthUrl = getEndpoint().replace("/v1/chat/completions", "/health");
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder().uri(URI.create(healthUrl))
							.timeout(Duration.ofSeconds(5)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200;
		}
		catch (Exception e) {
			return false;
		}
	}

	private static String runLlm(String systemPrompt) throws IOException, InterruptedException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("temperature", 0.0);
		root.put("max_tokens", 2048);
		root.put("stream", false);

		ObjectNode responseFormat = MAPPER.createObjectNode();
		responseFormat.put("type", "json_object");
		root.set("response_format", responseFormat);

		ArrayNode messages = MAPPER.createArrayNode();
		ObjectNode sysMsg = MAPPER.createObjectNode();
		sysMsg.put("role", "system");
		sysMsg.put("content", systemPrompt);
		messages.add(sysMsg);

		ObjectNode userMsg = MAPPER.createObjectNode();
		userMsg.put("role", "user");
		userMsg.put("content",
				"Patient records (grouped by type, most recent first within each group):\n"
						+ RECORDS + "\nQuestion: " + QUESTION);
		messages.add(userMsg);
		root.set("messages", messages);

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder()
						.uri(URI.create(getEndpoint()))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(
								MAPPER.writeValueAsString(root), StandardCharsets.UTF_8))
						.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (response.statusCode() != 200) {
			throw new IOException("LLM endpoint returned HTTP " + response.statusCode());
		}

		JsonNode respRoot = MAPPER.readTree(response.body());
		JsonNode choices = respRoot.get("choices");
		if (choices != null && choices.isArray() && !choices.isEmpty()) {
			JsonNode message = choices.get(0).get("message");
			if (message != null && message.has("content")) {
				return message.get("content").asText("");
			}
		}
		return "";
	}

	private static Set<Integer> extractTextCitations(String answer) {
		Set<Integer> citations = new HashSet<>();
		Matcher m = CITATION_PATTERN.matcher(answer);
		while (m.find()) {
			citations.add(Integer.parseInt(m.group(1)));
		}
		return citations;
	}

	private static Set<String> findMentionedConcepts(String answer) {
		Set<String> found = new HashSet<>();
		String lower = answer.toLowerCase();
		if (lower.contains("systolic")) found.add("Systolic Blood Pressure");
		if (lower.contains("diastolic")) found.add("Diastolic Blood Pressure");
		if (lower.contains("weight")) found.add("Weight");
		if (lower.contains("temperature") || lower.contains("temp")) found.add("Temperature");
		return found;
	}

	@Test
	public void trendQuery_shouldCiteRecordsAndCoverAllConcepts() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(
				"true".equalsIgnoreCase(System.getProperty("chartsearchai.llm.quality.test")),
				"Skipping: set -Dchartsearchai.llm.quality.test=true to run");
		org.junit.jupiter.api.Assumptions.assumeTrue(isEndpointReachable(),
				"Skipping: LLM endpoint not reachable at " + getEndpoint());

		List<String> promptVariations = buildPromptVariations();

		String bestConfig = null;
		Set<Integer> bestCitations = new HashSet<>();
		Set<String> bestConcepts = new HashSet<>();
		String bestAnswer = null;

		for (int p = 0; p < promptVariations.size(); p++) {
			String rawResponse = runLlm(promptVariations.get(p));
			LlmProvider.LlmResponse parsed = LlmProvider.extractResponse(rawResponse);
			String answer = parsed.getAnswer();

			Set<Integer> allCitations = new HashSet<>();
			allCitations.addAll(extractTextCitations(answer));
			for (int c : parsed.getCitations()) {
				allCitations.add(c);
			}
			allCitations.removeIf(c -> c < 1 || c > TOTAL_RECORDS);

			Set<String> concepts = findMentionedConcepts(answer);

			String config = "Prompt " + (p + 1);
			System.out.println("\n=== " + config + ": "
					+ allCitations.size() + "/" + TOTAL_RECORDS
					+ " citations, " + concepts.size() + " concepts ===");
			System.out.println("Text citations: " + extractTextCitations(answer));
			System.out.println("JSON citations: " + parsed.getCitations());
			System.out.println("All unique: " + allCitations);

			if (allCitations.size() > bestCitations.size()
					|| (allCitations.size() == bestCitations.size()
						&& concepts.size() > bestConcepts.size())) {
				bestCitations = allCitations;
				bestConcepts = concepts;
				bestConfig = config;
				bestAnswer = answer;
			}
		}

		System.out.println("\n=== BEST: " + bestConfig + " ("
				+ bestCitations.size() + "/" + TOTAL_RECORDS + ") ===");
		System.out.println("Citations: " + bestCitations);
		System.out.println("Concepts: " + bestConcepts);
		System.out.println("Answer: " + bestAnswer);

		for (String concept : REQUIRED_CONCEPTS) {
			assertTrue(bestConcepts.contains(concept),
					"Best answer should mention " + concept + ". Answer: " + bestAnswer);
		}

		Set<Integer> missing = new HashSet<>();
		for (int i = 1; i <= TOTAL_RECORDS; i++) {
			if (!bestCitations.contains(i)) {
				missing.add(i);
			}
		}
		assertTrue(bestCitations.size() >= MIN_REQUIRED_CITATIONS,
				"Best answer should cite at least " + MIN_REQUIRED_CITATIONS
				+ "/" + TOTAL_RECORDS + " records. Got: " + bestCitations.size()
				+ ". Missing: " + missing + ". Answer: " + bestAnswer);
	}

	/**
	 * The instrument has to assert it did something. This is the only test in this class that runs
	 * unconditionally — it needs no LLM — because the arms it checks were silently not what they
	 * claimed to be, and the opt-in test above is where that hid.
	 *
	 * <p>{@code String.replace} returns the ORIGINAL string when the needle is absent, so a
	 * substitution anchored on a sentence that has drifted out of
	 * {@link LlmProvider#DEFAULT_SYSTEM_PROMPT} simply does not happen, and nothing says so: the run
	 * compares four prompts believing it has four variants, and every number it prints is
	 * well-formed. An arm whose ONLY edit drifts is byte-identical to the baseline arm, which is what
	 * the assertions here catch. The fourth arm was subtler and is why {@link #replaceOrFail} exists:
	 * it anchored on {@code "Answer ONLY the specific question asked."} while the prompt says
	 * {@code "Answer ONLY the specific query."}, and its SECOND substitution did apply — so it
	 * differed from the baseline and from its siblings while missing the trend instruction that is
	 * its whole reason to exist as a separate arm (issue #126).</p>
	 *
	 * <p>One assertion per arm, against the baseline and against each other: an arm that duplicates a
	 * sibling is the same vacuous comparison one step removed. Building the arms at all is the other
	 * half of the check — {@code replaceOrFail} throws on a needle the prompt no longer contains.</p>
	 */
	@Test
	public void promptVariations_shouldEachDifferFromTheBaselineAndFromEachOther() {
		List<String> variations = buildPromptVariations();

		assertEquals(4, variations.size(), "expected the baseline arm plus three variants");
		assertEquals(LlmProvider.DEFAULT_SYSTEM_PROMPT, variations.get(0),
				"arm 1 is the baseline and must be the compiled prompt verbatim");
		for (int i = 1; i < variations.size(); i++) {
			assertNotEquals(LlmProvider.DEFAULT_SYSTEM_PROMPT, variations.get(i),
					"prompt variation " + (i + 1) + " is byte-identical to the baseline arm, so "
							+ "comparing them measures nothing");
		}
		for (int i = 0; i < variations.size(); i++) {
			for (int j = i + 1; j < variations.size(); j++) {
				assertNotEquals(variations.get(i), variations.get(j),
						"prompt variations " + (i + 1) + " and " + (j + 1) + " are identical");
			}
		}
	}

	/**
	 * One substitution on a prompt, which must actually happen.
	 *
	 * <p>{@code String.replace} fails open: handed a needle that is not there it returns the original
	 * and reports nothing, so an arm anchored on a sentence the prompt no longer contains keeps
	 * producing numbers that look like a measurement of an edit it never applied. That is the defect
	 * this class carried (issue #126), and the reason every substitution below goes through this
	 * method instead of calling {@code replace} directly: a future arm cannot regain the fault without
	 * tripping {@link #promptVariations_shouldEachDifferFromTheBaselineAndFromEachOther}, which builds
	 * the arms unconditionally and therefore runs this check in CI.
	 */
	private static String replaceOrFail(String prompt, String needle, String replacement) {
		if (!prompt.contains(needle)) {
			throw new IllegalStateException("prompt-variation anchor is no longer in "
					+ "LlmProvider.DEFAULT_SYSTEM_PROMPT, so this arm would silently measure an edit "
					+ "it never made (issue #126). Re-point it at the current sentence: " + needle);
		}
		String edited = prompt.replace(needle, replacement);
		if (edited.equals(prompt)) {
			throw new IllegalStateException("prompt-variation substitution changed nothing — the "
					+ "replacement is identical to the anchor: " + needle);
		}
		return edited;
	}

	private static List<String> buildPromptVariations() {
		List<String> prompts = new ArrayList<>();

		prompts.add(LlmProvider.DEFAULT_SYSTEM_PROMPT);

		prompts.add(replaceOrFail(LlmProvider.DEFAULT_SYSTEM_PROMPT,
				"Include ALL relevant records in your answer — never omit any for brevity.",
				"NEVER summarize multiple values into a range. List each measurement individually "
				+ "with its date, value, and citation. Every record MUST appear in the answer."));

		prompts.add(replaceOrFail(LlmProvider.DEFAULT_SYSTEM_PROMPT,
				"Include ALL relevant records in your answer — never omit any for brevity. "
				+ "Cite EVERY record you reference by its number in brackets (e.g. [1], [3]).",
				"CRITICAL REQUIREMENT: You must cite EVERY record provided. "
				+ "For each record [N], include: the exact value, date, and [N] citation. "
				+ "Do NOT summarize ranges. Do NOT skip records. "
				+ "Use the arrow format: val (date) [N] \u2192 val (date) [N] \u2192 ..."));

		// The prompt's scope sentence is "Answer ONLY the specific query." This arm anchored on
		// "Answer ONLY the specific question asked." — a sentence the prompt has never contained — so
		// its trend instruction never entered the prompt while its second substitution did, and the
		// harness reported a trend variant with no trend instruction in it. Measured on the compiled
		// prompt before the fix: baseline 6034 chars, this arm 6013, "oldest to newest" absent — so it
		// was not identical to the baseline arm, which is how it survived a diff of the four prompts.
		// The anchor text belongs to LlmProvider; if that sentence is reworded again, replaceOrFail
		// fails loudly instead of dropping this edit.
		prompts.add(replaceOrFail(
				replaceOrFail(LlmProvider.DEFAULT_SYSTEM_PROMPT,
						"Answer ONLY the specific query.",
						"Answer ONLY the specific query. When the question asks about trends, "
						+ "you MUST list every single value from oldest to newest."),
				"Include ALL relevant records in your answer — never omit any for brevity. "
				+ "Cite EVERY record you reference by its number in brackets (e.g. [1], [3]).",
				"Cite EVERY record by its [N] number. Your answer must contain ALL record numbers. "
				+ "Format: val (date) [N] \u2192 val (date) [N] \u2192 ..."));

		return prompts;
	}
}
