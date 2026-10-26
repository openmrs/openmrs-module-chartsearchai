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

import java.util.List;

import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;

/**
 * An exact token count from the LLM engine actually serving this deployment. Counting is delegated
 * to the engine's own tokenizer and chat template rather than approximated here: which records a
 * budget admits is this module's policy, but what a given prompt costs in tokens is the engine's
 * fact.
 */
public interface TokenCounter {

	/**
	 * Whether the engine this deployment answers with is one this counter can ask. False for an
	 * engine with no assumed count route (a remote OpenAI-compatible endpoint is not guaranteed to
	 * expose one); a caller then skips budget enforcement rather than estimate a count. True does
	 * not promise that a count will succeed: see {@link #countPrompt}.
	 */
	boolean isAvailable();

	/**
	 * Exact count of the same assembled answer messages sent for inference, including all clauses.
	 *
	 * @throws RuntimeException an {@link IllegalStateException} or
	 *         {@link org.openmrs.api.APIException} when the engine cannot be started (no model
	 *         configured, no server binary) or does not answer the count (a {@code llama-server}
	 *         build without the count route, an error reply or a malformed one); a caller treats
	 *         that as no count, as it does an unavailable counter
	 */
	int countPrompt(String numberedRecords, List<Integer> focusIndices, String question,
			boolean enumerateFindings, List<AlreadyOrderedDrug> drugsAlreadyOrdered);

	/** The current input budget: the engine's configured context window minus its output reservation. */
	int inputBudget();
}
