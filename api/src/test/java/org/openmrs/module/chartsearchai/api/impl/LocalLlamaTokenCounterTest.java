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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

public class LocalLlamaTokenCounterTest {

	@Test
	public void availabilityMatchesTheConfiguredEngine() {
		assertTrue(LocalLlamaTokenCounter.supportsExactCount(null));
		assertTrue(LocalLlamaTokenCounter.supportsExactCount("local"));
		assertTrue(LocalLlamaTokenCounter.supportsExactCount(" LOCAL "));
		assertFalse(LocalLlamaTokenCounter.supportsExactCount("remote"));
		assertFalse(LocalLlamaTokenCounter.supportsExactCount(" REMOTE "));
	}

	@Test
	public void delegatesCountingAndDerivesTheInputBudget() {
		AtomicReference<String> measuredUserMessage = new AtomicReference<>();
		LocalLlmEngine engine = new LocalLlmEngine() {

			@Override
			synchronized int countTokens(String text) {
				return text.length();
			}

			@Override
			int getContextSize() {
				return 8192;
			}

			@Override
			synchronized int countChatInputTokens(String systemPrompt, String userMessage) {
				measuredUserMessage.set(userMessage);
				return 37;
			}
		};
		LocalLlamaTokenCounter counter = new LocalLlamaTokenCounter() {

			@Override
			String systemPrompt() {
				return "system";
			}
		};
		counter.setLocalLlmEngine(engine);

		assertEquals(5, counter.count("chart"));
		assertEquals(37, counter.countPrompt("[1] Medication: Aspirin",
				"What medications is the patient taking?"));
		assertTrue(measuredUserMessage.get().contains("[1] Medication: Aspirin"));
		assertTrue(measuredUserMessage.get().endsWith(
				"Clinician's query: What medications is the patient taking?"));
		assertEquals(8192 - ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS,
				counter.inputBudget());
	}
}
