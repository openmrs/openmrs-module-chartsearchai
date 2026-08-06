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

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link TokenCounter} for {@link LocalLlmEngine}, available exactly when
 * {@code chartsearchai.llm.engine} resolves to local (the same default {@link LlmProvider} uses).
 * A remote engine has no assumed input-token route — an arbitrary OpenAI-compatible
 * endpoint (vLLM, Ollama, a cloud provider) is not guaranteed to expose one — so
 * {@link #isAvailable()} is false in that case and callers must skip proactive budget
 * enforcement rather than approximate.
 */
@Component("chartSearchAi.localLlamaTokenCounter")
public class LocalLlamaTokenCounter implements TokenCounter {

	@Autowired
	private LocalLlmEngine localLlmEngine;

	/** Test seam: production wires {@link LocalLlmEngine} via {@link Autowired}. */
	void setLocalLlmEngine(LocalLlmEngine localLlmEngine) {
		this.localLlmEngine = localLlmEngine;
	}

	@Override
	public boolean isAvailable() {
		String engineType = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE);
		return supportsExactCount(engineType);
	}

	static boolean supportsExactCount(String engineType) {
		return engineType == null
				|| !ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(engineType.trim());
	}

	@Override
	public int count(String text) {
		return localLlmEngine.countTokens(text);
	}

	@Override
	public int countPrompt(String numberedRecords, String question) {
		return localLlmEngine.countChatInputTokens(systemPrompt(),
				LlmProvider.buildUserMessage(numberedRecords, question));
	}

	/** Package-visible configuration seam for a context-free contract test. */
	String systemPrompt() {
		String configured = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SYSTEM_PROMPT);
		return configured == null || configured.trim().isEmpty()
				? LlmProvider.DEFAULT_SYSTEM_PROMPT : configured.trim();
	}

	@Override
	public int inputBudget() {
		return localLlmEngine.getContextSize() - ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;
	}
}
