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

/**
 * An exact token count from the LLM engine actually serving this deployment — mirrors
 * med-agent-hub's {@code TokenCounter} protocol (server/context_sources.py), which likewise
 * delegates counting to the configured engine's own tokenizer rather than approximating in the
 * application layer. Deliberately narrow: the mandatory-first budget policy that consumes this
 * (see {@code QueryStoreChartBuilder#applyContextBudget}) is application-owned and cannot be
 * delegated, but WHAT a given piece of text costs in tokens is the engine's own fact, not a
 * guess this module should make on its behalf.
 */
public interface TokenCounter {

	/**
	 * False when no engine currently configured for this deployment can answer a token-count
	 * query (e.g. a remote OpenAI-compatible endpoint with no {@code /tokenize} route). Callers
	 * must skip budget enforcement rather than fabricate an approximate count in that case —
	 * matching the hub's "never guess" rule for token counting specifically.
	 */
	boolean isAvailable();

	/** Exact token count for {@code text} from the real, currently-configured engine. */
	int count(String text);

	/** Exact count for the assembled answer request, including chat-template and prompt overhead. */
	default int countPrompt(String numberedRecords, String question) {
		return count(numberedRecords);
	}

	/** The current input budget: the engine's configured context window minus its output
	 *  reservation — mirrors med-agent-hub's {@code ContextBudget.input_limit}. */
	int inputBudget();
}
