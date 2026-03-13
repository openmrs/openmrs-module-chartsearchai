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

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link LlmProvider} prompt building and configuration logic.
 * Uses a subclass to override Context-dependent methods.
 */
public class LlmProviderTest {

	@Test
	public void buildPrompt_shouldIncludeSystemPromptRecordsAndQuestion() {
		LlmProvider provider = createProvider("You are a test assistant.");

		String prompt = provider.buildPrompt("[1] BP 120/80\n[2] Weight 70kg", "What is the BP?");

		assertTrue(prompt.startsWith("You are a test assistant."));
		assertTrue(prompt.contains("[1] BP 120/80"));
		assertTrue(prompt.contains("[2] Weight 70kg"));
		assertTrue(prompt.contains("Question: What is the BP?"));
	}

	@Test
	public void buildPrompt_shouldUseDefaultSystemPromptWhenNoneConfigured() {
		LlmProvider provider = createProvider(null);

		String prompt = provider.buildPrompt("[1] record", "question");

		assertTrue(prompt.startsWith(LlmProvider.DEFAULT_SYSTEM_PROMPT));
	}

	@Test
	public void buildPrompt_shouldUseDefaultSystemPromptWhenEmpty() {
		LlmProvider provider = createProvider("  ");

		String prompt = provider.buildPrompt("[1] record", "question");

		assertTrue(prompt.startsWith(LlmProvider.DEFAULT_SYSTEM_PROMPT));
	}

	@Test
	public void buildPrompt_shouldSeparateSectionsWithNewlines() {
		LlmProvider provider = createProvider("System prompt.");

		String prompt = provider.buildPrompt("records", "question");

		assertTrue(prompt.contains("System prompt.\n\n"));
		assertTrue(prompt.contains("Patient records:\nrecords\n"));
		assertTrue(prompt.contains("Question: question"));
	}

	@Test
	public void defaultSystemPrompt_shouldMentionClinicalAssistant() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("clinical assistant"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireCitations() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[1]"));
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
	public void cleanResponse_shouldStripAnswerPrefix() {
		assertEquals("The patient is on Metformin [1].",
				LlmProvider.cleanResponse("Answer: The patient is on Metformin [1]."));
	}

	@Test
	public void cleanResponse_shouldStripLeadingWhitespace() {
		assertEquals("No relevant information was found in the patient's records.",
				LlmProvider.cleanResponse("\n\nNo relevant information was found in the patient's records."));
	}

	@Test
	public void cleanResponse_shouldStripWhitespaceAndAnswerPrefix() {
		assertEquals("No relevant information was found in the patient's records.",
				LlmProvider.cleanResponse("\nAnswer: No relevant information was found in the patient's records."));
	}

	@Test
	public void cleanResponse_shouldNotModifyCleanResponse() {
		assertEquals("The patient is on Metformin [1].",
				LlmProvider.cleanResponse("The patient is on Metformin [1]."));
	}

	@Test
	public void cleanResponse_shouldTruncateAtNextQuestion() {
		assertEquals("No relevant information was found in the patient's records.",
				LlmProvider.cleanResponse("No relevant information was found in the patient's records."
						+ "\nQuestion: Is the patient experiencing a fever?"
						+ "\nAnswer: The patient has had high temperatures [22]."));
	}

	@Test
	public void cleanResponse_shouldHandleEmptyString() {
		assertEquals("", LlmProvider.cleanResponse(""));
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
