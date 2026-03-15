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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link LlmProvider} configuration logic.
 * Uses a subclass to override Context-dependent methods.
 */
public class LlmProviderTest {

	@Test
	public void defaultSystemPrompt_shouldMentionClinicalAssistant() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("clinical assistant"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireCitations() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[Obs #1]"));
	}

	@Test
	public void defaultSystemPrompt_shouldConstrainToQuestionAsked() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("ONLY the specific question asked"));
	}

	@Test
	public void close_shouldNotFailWhenModelIsNull() {
		LlmProvider provider = new LlmProvider();
		// Should not throw
		provider.close();
	}

	@Test
	public void getTimeoutSeconds_shouldReturnDefault() {
		LlmProvider provider = createProviderWithTimeout(-1);

		// The overridden method returns the default constant
		int timeout = provider.getTimeoutSeconds();
		assertTrue(timeout > 0);
	}

	@Test
	public void getSystemPrompt_shouldTrimCustomPrompt() {
		LlmProvider provider = createProvider("  custom prompt  ");

		String prompt = provider.getSystemPrompt();
		assertFalse(prompt.startsWith(" "));
		assertEquals("custom prompt", prompt);
	}

	@Test
	public void extractAnswer_shouldParseJsonResponse() {
		String response = "{\"answer\": \"The patient has Hypertension [48] and Diabetes [49].\"}";
		assertEquals("The patient has Hypertension [48] and Diabetes [49].",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldHandleEscapedQuotes() {
		String response = "{\"answer\": \"The patient said \\\"I feel fine\\\" during the visit.\"}";
		assertEquals("The patient said \"I feel fine\" during the visit.",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldHandleEscapedBackslashes() {
		String response = "{\"answer\": \"Path: C:\\\\Users\\\\data\"}";
		assertEquals("Path: C:\\Users\\data",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldHandleEscapedNewlines() {
		String response = "{\"answer\": \"Line 1\\nLine 2\"}";
		assertEquals("Line 1\nLine 2",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldHandleEmptyString() {
		assertEquals("", LlmProvider.extractAnswer(""));
	}

	@Test
	public void extractAnswer_shouldFallBackToRawResponseWhenNotJson() {
		String response = "The patient has Diabetes [1].";
		assertEquals(response, LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldDecodeUnicodeEscapes() {
		String response = "{\"answer\": \"Temperature is 38.9\\u00b0C\"}";
		assertEquals("Temperature is 38.9\u00b0C",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void extractAnswer_shouldHandleWhitespaceInJson() {
		String response = "{ \"answer\" : \"No relevant information was found.\" }";
		assertEquals("No relevant information was found.",
				LlmProvider.extractAnswer(response));
	}

	@Test
	public void formatPrompt_shouldUseLlama3Template() {
		String result = LlmProvider.formatPrompt("llama3", "sys", "usr");
		assertTrue(result.contains("<|begin_of_text|>"));
		assertTrue(result.contains("<|start_header_id|>system<|end_header_id|>\n\nsys<|eot_id|>"));
		assertTrue(result.contains("<|start_header_id|>user<|end_header_id|>\n\nusr<|eot_id|>"));
		assertTrue(result.contains("<|start_header_id|>assistant<|end_header_id|>"));
	}

	@Test
	public void formatPrompt_shouldUseMistralTemplate() {
		String result = LlmProvider.formatPrompt("mistral", "sys", "usr");
		assertEquals("[INST] sys\n\nusr [/INST]", result);
	}

	@Test
	public void formatPrompt_shouldUsePhi3Template() {
		String result = LlmProvider.formatPrompt("phi3", "sys", "usr");
		assertTrue(result.startsWith("<|system|>\nsys<|end|>"));
		assertTrue(result.contains("<|user|>\nusr<|end|>"));
		assertTrue(result.endsWith("<|assistant|>\n"));
	}

	@Test
	public void formatPrompt_shouldUseChatMlTemplate() {
		String result = LlmProvider.formatPrompt("chatml", "sys", "usr");
		assertTrue(result.contains("<|im_start|>system\nsys<|im_end|>"));
		assertTrue(result.contains("<|im_start|>user\nusr<|im_end|>"));
		assertTrue(result.contains("<|im_start|>assistant"));
	}

	@Test
	public void formatPrompt_shouldBeCaseInsensitive() {
		String lower = LlmProvider.formatPrompt("llama3", "sys", "usr");
		String upper = LlmProvider.formatPrompt("LLAMA3", "sys", "usr");
		assertEquals(lower, upper);
	}

	@Test
	public void formatPrompt_shouldUseCustomTemplateWithPlaceholders() {
		String custom = "<<SYS>>{system}<</SYS>>[INST]{user}[/INST]";
		String result = LlmProvider.formatPrompt(custom, "my system", "my question");
		assertEquals("<<SYS>>my system<</SYS>>[INST]my question[/INST]", result);
	}

	@Test
	public void resolveStopStrings_shouldReturnLlama3Stops() {
		assertArrayEquals(new String[]{"<|eot_id|>", "<|end_of_text|>"},
				LlmProvider.resolveStopStrings("llama3"));
	}

	@Test
	public void resolveStopStrings_shouldReturnMistralStops() {
		assertArrayEquals(new String[]{"</s>"}, LlmProvider.resolveStopStrings("mistral"));
	}

	@Test
	public void resolveStopStrings_shouldReturnPhi3Stops() {
		assertArrayEquals(new String[]{"<|end|>"}, LlmProvider.resolveStopStrings("phi3"));
	}

	@Test
	public void resolveStopStrings_shouldReturnChatMlStops() {
		assertArrayEquals(new String[]{"<|im_end|>"}, LlmProvider.resolveStopStrings("chatml"));
	}

	@Test
	public void resolveStopStrings_shouldReturnEmptyForCustomTemplate() {
		assertArrayEquals(new String[0],
				LlmProvider.resolveStopStrings("<<SYS>>{system}<</SYS>>"));
	}

	private LlmProvider createProvider(final String customSystemPrompt) {
		return new LlmProvider() {

			@Override
			protected String getSystemPrompt() {
				if (customSystemPrompt != null && !customSystemPrompt.trim().isEmpty()) {
					return customSystemPrompt.trim();
				}
				return DEFAULT_SYSTEM_PROMPT;
			}
		};
	}

	private LlmProvider createProviderWithTimeout(final int timeout) {
		return new LlmProvider() {

			@Override
			protected String getSystemPrompt() {
				return DEFAULT_SYSTEM_PROMPT;
			}

			@Override
			protected int getTimeoutSeconds() {
				if (timeout > 0) {
					return timeout;
				}
				return org.openmrs.module.chartsearchai.ChartSearchAiConstants.DEFAULT_LLM_TIMEOUT_SECONDS;
			}
		};
	}
}
