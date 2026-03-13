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
	public void cleanResponse_shouldStripTrailingParagraphsWithoutCitations() {
		String response = "The patient has Hypertension [48] and Diabetes [49].\n\n"
				+ "Note that there is no information about allergies in the records.";
		assertEquals("The patient has Hypertension [48] and Diabetes [49].",
				LlmProvider.cleanResponse(response));
	}

	@Test
	public void cleanResponse_shouldKeepMultipleParagraphsWithCitations() {
		String response = "The patient has Hypertension [48].\n\n"
				+ "They also have Diabetes [49].";
		assertEquals(response, LlmProvider.cleanResponse(response));
	}

	@Test
	public void cleanResponse_shouldKeepSingleParagraphWithCitation() {
		String response = "The patient is taking Metformin [2].";
		assertEquals(response, LlmProvider.cleanResponse(response));
	}

	@Test
	public void cleanResponse_shouldReturnFirstParagraphWhenNoCitations() {
		String response = "No relevant information was found in the patient's records.\n\n"
				+ "Please consult additional sources.";
		assertEquals("No relevant information was found in the patient's records.",
				LlmProvider.cleanResponse(response));
	}

	@Test
	public void cleanResponse_shouldHandleEmptyString() {
		assertEquals("", LlmProvider.cleanResponse(""));
	}

	@Test
	public void cleanResponse_shouldTrimWhitespace() {
		assertEquals("The patient has Diabetes [1].",
				LlmProvider.cleanResponse("\n  The patient has Diabetes [1].  \n"));
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
