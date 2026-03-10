/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.adapter;

/**
 * Abstraction for LLM providers. Implementations handle the HTTP communication with specific AI
 * services (Claude, Ollama, etc.) while the rest of the module remains provider-agnostic.
 */
public interface LlmAdapter {

	/**
	 * Send a chat request with a system prompt and user message.
	 *
	 * @param systemPrompt the system-level instructions for the LLM
	 * @param userMessage the user's question along with retrieved clinical context
	 * @return the LLM's response
	 */
	LlmResponse chat(String systemPrompt, String userMessage);
}
